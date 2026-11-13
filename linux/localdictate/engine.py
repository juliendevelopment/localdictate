"""Local recognition with sherpa-onnx: Parakeet TDT 0.6B v3 (int8) and Silero VAD, same settings as Android."""
from __future__ import annotations

import os
from dataclasses import dataclass
from pathlib import Path

import numpy as np

from .dsp import RATE, PcmFramer, SegmentPadder, split_lengths


@dataclass(frozen=True)
class ModelFiles:
    parakeet_dir: Path
    vad_file: Path


def _threads() -> int:
    return max(1, min(4, (os.cpu_count() or 2) // 2))


class Recognizer:
    """Owns the native recognizer. Callers serialize decoding (one session or re-transcription at a time)."""

    def __init__(self, files: ModelFiles, num_threads: int | None = None):
        import sherpa_onnx  # Imported lazily: the native library is large and absent from host-only tests.

        self.files = files
        self._sherpa = sherpa_onnx

        def path(name: str) -> str:
            return str(files.parakeet_dir / name)

        self._recognizer = sherpa_onnx.OfflineRecognizer.from_transducer(
            encoder=path("encoder.int8.onnx"), decoder=path("decoder.int8.onnx"), joiner=path("joiner.int8.onnx"),
            tokens=path("tokens.txt"), num_threads=num_threads or _threads(), sample_rate=RATE, feature_dim=128,
            decoding_method="greedy_search", model_type="nemo_transducer", provider="cpu", debug=False)

    def recognize(self, samples: np.ndarray) -> str:
        stream = self._recognizer.create_stream()
        stream.accept_waveform(RATE, samples)
        self._recognizer.decode_stream(stream)
        return stream.result.text

    def segmenter(self, threshold: float = 0.5) -> "SileroSegmenter":
        return SileroSegmenter(self.files.vad_file, threshold)


class SileroSegmenter:
    """Silero VAD: 512-sample windows, 0.4 s silence, 0.25 s minimum speech, 20 s maximum segment.

    A pause segments but never ends the session. Each segment is widened by 0.3 s of real captured audio and
    over-long segments are split at their quietest point.
    """

    def __init__(self, vad_file: Path, threshold: float = 0.5):
        import sherpa_onnx

        config = sherpa_onnx.VadModelConfig(
            silero_vad=sherpa_onnx.SileroVadModelConfig(
                model=str(vad_file), threshold=threshold, min_silence_duration=0.4, min_speech_duration=0.25,
                window_size=512, max_speech_duration=20.0),
            sample_rate=RATE, num_threads=1, provider="cpu", debug=False)
        self._vad = sherpa_onnx.VoiceActivityDetector(config, buffer_size_in_seconds=60)
        self._framer = PcmFramer(512)
        self._padder = SegmentPadder()

    def accept(self, samples: np.ndarray) -> list[np.ndarray]:
        result: list[np.ndarray] = []
        self._padder.write(samples)
        self._framer.accept(samples, lambda frame: self._feed(frame, result))
        return result

    def finish(self) -> list[np.ndarray]:
        result: list[np.ndarray] = []
        self._framer.finish(lambda frame: self._feed(frame, result))
        self._vad.flush()
        self._drain(result)
        return result

    def _feed(self, frame: np.ndarray, result: list[np.ndarray]) -> None:
        self._vad.accept_waveform(frame)
        self._drain(result)

    def _drain(self, result: list[np.ndarray]) -> None:
        while not self._vad.empty():
            speech = self._vad.front
            start = int(speech.start)
            samples = np.asarray(speech.samples, dtype=np.float32)
            self._vad.pop()
            # Never beyond real samples (the last frame is zero-padded).
            count = max(0, min(start + samples.size, self._framer.sample_count) - start)
            offset = 0
            for length in split_lengths(samples, count):
                widened = self._padder.widen(start + offset, length)
                if widened is not None:
                    result.append(widened.samples)
                offset += length
