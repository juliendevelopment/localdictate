"""Audio helpers ported from the Android app (VoiceGain, SegmentPadder, SpeechSplitter, PcmFramer).

Everything works on mono float32 samples in [-1, 1] at 16 kHz. No GTK, GStreamer or sherpa import, so the
behaviour is testable on any host.
"""
from __future__ import annotations

import math
from dataclasses import dataclass
from typing import Callable

import numpy as np

RATE = 16_000


def pcm16_to_float(data: bytes) -> np.ndarray:
    return np.frombuffer(data, dtype="<i2").astype(np.float32) / 32768.0


def float_to_pcm16(samples: np.ndarray) -> bytes:
    clipped = np.clip(samples, -1.0, 32767.0 / 32768.0)
    return (clipped * 32768.0).round().astype("<i2").tobytes()


def peak(samples: np.ndarray) -> float:
    return float(np.max(np.abs(samples))) if samples.size else 0.0


def rms(samples: np.ndarray) -> float:
    if not samples.size:
        return 0.0
    return min(1.0, float(np.sqrt(np.mean(np.square(samples, dtype=np.float64)))))


def dbfs(value: float) -> float:
    return -120.0 if value <= 1e-6 else 20.0 * math.log10(value)


def db_to_factor(db: float) -> float:
    return 10.0 ** (db / 20.0)


class FixedGain:
    """Manual microphone sensitivity, in dB. Clipped, never wraps."""

    def __init__(self, db: float = 0.0):
        self.factor = db_to_factor(db)

    def apply(self, samples: np.ndarray) -> np.ndarray:
        if self.factor == 1.0:
            return samples
        return np.clip(samples * self.factor, -1.0, 1.0).astype(np.float32)


class VoiceGain:
    """Automatic gain for soft speech (port of the Android VoiceGain).

    Peaks are brought towards ``target_peak`` with a gain between 1 (loud speech is never attenuated) and
    ``max_gain``. The peak envelope follows a louder chunk at once and decays slowly; the gain is computed from
    the envelope before it is applied, so an output peak never exceeds ``target_peak``. The gain rises slowly to
    avoid pumping the noise between words.
    """

    def __init__(self, target_peak: float = 0.5, max_gain: float = 8.0, chunk: int = 160,
                 release_seconds: float = 1.0, rise_db_per_second: float = 6.0, rate: int = RATE):
        if not (0.0 <= target_peak <= 1.0 and max_gain >= 1.0 and chunk > 0 and rate > 0):
            raise ValueError("invalid VoiceGain parameters")
        self.target_peak = target_peak
        self.max_gain = max_gain
        self.chunk = chunk
        self.release = 0.5 ** (chunk / rate / release_seconds)
        self.rise = 10.0 ** (rise_db_per_second * chunk / rate / 20.0)
        self.envelope = 0.0
        self.gain = max_gain
        self.lowest_gain = max_gain

    def apply(self, samples: np.ndarray) -> np.ndarray:
        output = np.empty_like(samples, dtype=np.float32)
        for offset in range(0, samples.size, self.chunk):
            part = samples[offset:offset + self.chunk]
            self.envelope = max(peak(part), self.envelope * self.release)
            wanted = min(max(self.target_peak / self.envelope, 1.0), self.max_gain) if self.envelope > 0 else self.max_gain
            self.gain = wanted if wanted < self.gain else min(wanted, self.gain * self.rise)
            self.lowest_gain = min(self.lowest_gain, self.gain)
            output[offset:offset + part.size] = np.clip(part * self.gain, -1.0, 1.0)
        return output


class InputChain:
    """Manual sensitivity, then optional automatic gain: what Silero, Parakeet and the level meter receive."""

    def __init__(self, gain_db: float = 0.0, auto_gain: bool = True, max_auto_gain_db: float = 18.0):
        self.fixed = FixedGain(gain_db)
        self.auto = VoiceGain(max_gain=db_to_factor(max_auto_gain_db)) if auto_gain else None

    @property
    def lowest_gain(self) -> float:
        return self.fixed.factor * (self.auto.lowest_gain if self.auto else 1.0)

    def apply(self, samples: np.ndarray) -> np.ndarray:
        out = self.fixed.apply(samples)
        return self.auto.apply(out) if self.auto else out


class PcmFramer:
    """Keeps partial reads; only finish() pads the final incomplete frame with zeros."""

    def __init__(self, frame_size: int = 512):
        if frame_size <= 0:
            raise ValueError("frame_size")
        self.frame_size = frame_size
        self.buffer = np.zeros(frame_size, dtype=np.float32)
        self.used = 0
        self.sample_count = 0

    def accept(self, samples: np.ndarray, emit: Callable[[np.ndarray], None]) -> None:
        self.sample_count += samples.size
        offset = 0
        while offset < samples.size:
            count = min(self.frame_size - self.used, samples.size - offset)
            self.buffer[self.used:self.used + count] = samples[offset:offset + count]
            self.used += count
            offset += count
            if self.used == self.frame_size:
                frame, self.buffer, self.used = self.buffer, np.zeros(self.frame_size, dtype=np.float32), 0
                emit(frame)

    def finish(self, emit: Callable[[np.ndarray], None]) -> None:
        if self.used == 0:
            return
        frame, self.buffer, self.used = self.buffer, np.zeros(self.frame_size, dtype=np.float32), 0
        emit(frame)


@dataclass
class TimedSpeech:
    start: int
    samples: np.ndarray


class SegmentPadder:
    """Recent audio by absolute sample index; widens each VAD segment by margins that never overlap the previous
    segment, because the VAD ends a segment exactly where silence starts and clips soft word endings."""

    def __init__(self, rate: int = RATE, before: int | None = None, after: int | None = None,
                 capacity: int | None = None):
        self.before = rate * 3 // 10 if before is None else before
        self.after = rate * 3 // 10 if after is None else after
        self.ring = np.zeros(rate * 60 if capacity is None else capacity, dtype=np.float32)
        if self.before < 0 or self.after < 0 or self.ring.size <= 0:
            raise ValueError("invalid SegmentPadder parameters")
        self.written = 0
        self.emitted_end = 0

    def write(self, samples: np.ndarray) -> None:
        size = self.ring.size
        offset = 0
        while offset < samples.size:
            position = self.written % size
            count = min(size - position, samples.size - offset)
            self.ring[position:position + count] = samples[offset:offset + count]
            offset += count
            self.written += count

    def widen(self, start: int, length: int) -> TimedSpeech | None:
        size = self.ring.size
        begin = max(start - self.before, self.emitted_end, self.written - size, 0)
        end = min(start + length + self.after, self.written)
        if end <= begin:
            return None
        self.emitted_end = end
        result = np.empty(end - begin, dtype=np.float32)
        index, offset = begin, 0
        while index < end:
            position = index % size
            count = min(size - position, end - index)
            result[offset:offset + count] = self.ring[position:position + count]
            offset += count
            index += count
        return TimedSpeech(begin, result)


def split_lengths(samples: np.ndarray, count: int | None = None, max_length: int = 20 * RATE,
                  search: int = 5 * RATE, window: int = 1_600) -> list[int]:
    """Cuts over-long VAD segments at their quietest window before each limit (Silero's maximum is not a hard
    limit). Pieces are contiguous: no sample dropped or decoded twice."""
    count = samples.size if count is None else count
    if not (0 <= count <= samples.size and window > 0 and search >= window and max_length > search):
        raise ValueError("invalid split parameters")
    energy = np.square(samples[:count], dtype=np.float64)
    result: list[int] = []
    position = 0
    while count - position > max_length:
        limit = position + max_length
        best, best_energy = limit - window, math.inf
        for start in range(limit - search, limit - window + 1, window // 2):
            value = float(energy[start:start + window].sum())
            if value < best_energy:
                best_energy, best = value, start
        cut = best + window // 2
        result.append(cut - position)
        position = cut
    if count > position:
        result.append(count - position)
    return result
