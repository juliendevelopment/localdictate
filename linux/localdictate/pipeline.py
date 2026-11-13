"""Dictation pipeline: capture → bounded PCM queue → segmenter → bounded segment queue → recognizer.

Port of the Android DictationPipeline with threads. The microphone feeds ``feed()`` from its own thread; the
segmenter is obtained lazily, so audio is queued while the model finishes loading. On overload the capture is
stopped and accepted audio is still drained: nothing accepted is dropped silently.
"""
from __future__ import annotations

import math
import queue
import threading
import time
from typing import Callable, Protocol

import numpy as np

from .dsp import RATE, InputChain, peak

MAX_SEGMENT_SAMPLES = 25 * RATE
DICTATION_MAX_SAMPLES = 15 * 60 * RATE
_END = object()


class Segmenter(Protocol):
    def accept(self, samples: np.ndarray) -> list[np.ndarray]: ...
    def finish(self) -> list[np.ndarray]: ...


class Diagnostic:
    """Numbers only, never content. Tells whether speech is lost at the capture, the VAD or the recognizer."""

    def __init__(self, press_time: float):
        self.press_time = press_time
        self.first_audio: float | None = None
        self.peak = 0.0
        self.lowest_gain: float | None = None
        self.segments = 0
        self.speech_samples = 0
        self.decode_seconds = 0.0
        self._lock = threading.Lock()

    def audio(self, origin: float, raw: np.ndarray, gain: float) -> None:
        with self._lock:
            if self.first_audio is None:
                self.first_audio = origin
            self.peak = max(self.peak, peak(raw))
            self.lowest_gain = gain if self.lowest_gain is None else min(self.lowest_gain, gain)

    def decoded(self, samples: int, seconds: float) -> None:
        with self._lock:
            self.segments += 1
            self.speech_samples += samples
            self.decode_seconds += seconds

    def line(self) -> str:
        def decimal(value: float, digits: int) -> str:
            return f"{value:.{digits}f}".replace(".", ",")

        with self._lock:
            parts = []
            if self.first_audio is None:
                parts.append("aucun son reçu du micro")
            else:
                parts.append(f"micro {max(0, round((self.first_audio - self.press_time) * 1000))} ms après l’appui")
                if self.peak <= 0:
                    parts.append("crête nulle")
                else:
                    db = round(-20 * math.log10(self.peak))
                    parts.append("crête 0 dBFS" if db <= 0 else f"crête −{db} dBFS")
                if self.lowest_gain is not None:
                    parts.append(f"gain ×{decimal(self.lowest_gain, 1)}")
            speech = self.speech_samples / RATE
            parts.append(f"{self.segments} segment{'s' if self.segments > 1 else ''}, {decimal(speech, 1)} s")
            if self.segments and speech > 0:
                parts.append(f"décodage {decimal(self.decode_seconds, 1)} s (×{decimal(self.decode_seconds / speech, 2)})")
            return "Diagnostic : " + " • ".join(parts)


class DictationPipeline:
    def __init__(
        self,
        segmenter: Callable[[], Segmenter],
        recognize: Callable[[np.ndarray], str],
        *,
        chain: InputChain,
        stop_capture: Callable[[], None],
        on_text: Callable[[str], None],
        on_error: Callable[[str], None] = lambda message: None,
        on_level: Callable[[float], None] = lambda level: None,
        recorder=None,
        diagnostic: Diagnostic | None = None,
        max_samples: int = DICTATION_MAX_SAMPLES,
        max_pending: int = 60 * RATE,
        segment_capacity: int = 8,
    ):
        self._segmenter = segmenter
        self._recognize = recognize
        self.chain = chain
        self._stop_capture = stop_capture
        self._on_text = on_text
        self._on_error = on_error
        self._on_level = on_level
        self.recorder = recorder
        self.diagnostic = diagnostic
        self.max_samples = max_samples
        self.max_pending = max_pending
        self.captured = 0
        self.texts: list[str] = []
        self.failure: str | None = None
        self.last_audio = time.monotonic()
        self._pending = 0
        self._lock = threading.Lock()
        # Serializes feed() and close_input(): no block is queued after the end marker, and the recorder is
        # never closed in the middle of a write.
        self._feed_lock = threading.RLock()
        self._input_closed = False
        self._capture_stopped = False
        self._pcm: queue.Queue = queue.Queue()
        self._segments: queue.Queue = queue.Queue(segment_capacity)
        self._abort = threading.Event()
        self._threads = [
            threading.Thread(target=self._segment_loop, name="ld-segmenter", daemon=True),
            threading.Thread(target=self._recognize_loop, name="ld-recognizer", daemon=True),
        ]

    # Lifecycle -------------------------------------------------------------------------------------------------
    def start(self) -> None:
        for thread in self._threads:
            thread.start()

    @property
    def exhausted(self) -> bool:
        return self.captured >= self.max_samples

    def fail(self, message: str) -> None:
        with self._lock:
            first = self.failure is None
            if first:
                self.failure = message
        self.stop_capture()
        if first:
            self._on_error(message)

    def stop_capture(self) -> None:
        with self._lock:
            if self._capture_stopped:
                return
            self._capture_stopped = True
        try:
            self._stop_capture()
        finally:
            self.close_input()

    def close_input(self) -> None:
        with self._feed_lock:
            if self._input_closed:
                return
            self._input_closed = True
            if self.recorder is not None:
                try:
                    self.recorder.close()
                except OSError:
                    with self._lock:
                        self.failure = self.failure or "Enregistrement du son incomplet (disque ?). Le texte reconnu est conservé."
            self._pcm.put(_END)

    def wait(self, timeout: float | None = None) -> bool:
        deadline = None if timeout is None else time.monotonic() + timeout
        for thread in self._threads:
            remaining = None if deadline is None else max(0.0, deadline - time.monotonic())
            thread.join(remaining)
        return not any(thread.is_alive() for thread in self._threads)

    # Capture thread --------------------------------------------------------------------------------------------
    def feed(self, raw: np.ndarray, origin: float | None = None) -> None:
        with self._feed_lock:
            if self._input_closed:
                return
            allowed = min(raw.size, self.max_samples - self.captured)
            block = raw if allowed == raw.size else raw[:allowed]
            self.captured += block.size
            self.last_audio = time.monotonic()
            if block.size:
                if self.recorder is not None:
                    try:
                        self.recorder.write(block)
                    except OSError:
                        self.recorder = None
                        self.fail("Écriture de l’enregistrement impossible (disque plein ?) : micro arrêté, texte conservé.")
                processed = self.chain.apply(block)
                if self.diagnostic is not None:
                    self.diagnostic.audio(origin if origin is not None else time.monotonic(), block, self.chain.lowest_gain)
                self._on_level(peak(processed))
                with self._lock:
                    self._pending += processed.size
                    overloaded = self._pending > self.max_pending
                # The block that crossed the limit is kept: it is queued before the end marker.
                self._pcm.put(processed)
                if overloaded:
                    self.fail("Capture saturée : micro arrêté, le son déjà reçu est transcrit.")
            if self.exhausted:
                self.stop_capture()

    # Workers ---------------------------------------------------------------------------------------------------
    def _send(self, items: list[np.ndarray]) -> None:
        # Back-pressure, not failure: a burst of segments (audio queued while the model loads, a slow CPU) waits
        # here, the PCM queue grows, and only its 60 s bound declares an overload.
        for item in items:
            if item.size > MAX_SEGMENT_SAMPLES:
                raise RuntimeError("Segment VAD trop long")
            while not self._abort.is_set():
                try:
                    self._segments.put(item, timeout=0.2)
                    break
                except queue.Full:
                    continue

    def _segment_loop(self) -> None:
        try:
            segmenter = self._segmenter()
            while True:
                block = self._pcm.get()
                if block is _END:
                    break
                with self._lock:
                    self._pending -= block.size
                if self._abort.is_set():
                    continue
                self._send(segmenter.accept(block))
            if not self._abort.is_set():
                self._send(segmenter.finish())
        except Exception as error:  # noqa: BLE001 - reported to the user, never silent
            self._abort.set()
            self.fail(f"Segmentation interrompue : une partie du son n’a pas été transcrite ({error.__class__.__name__}).")
            self._drain_pcm()
        finally:
            self._put_end()

    def _drain_pcm(self) -> None:
        # Unblock the capture side; the audio itself stays in the WAV file and can be transcribed again.
        self.stop_capture()
        while True:
            try:
                if self._pcm.get(timeout=1.0) is _END:
                    return
            except queue.Empty:
                return

    def _put_end(self) -> None:
        while True:
            try:
                self._segments.put(_END, timeout=0.2)
                return
            except queue.Full:
                if self._abort.is_set():
                    try:
                        self._segments.get_nowait()
                    except queue.Empty:
                        pass

    def _recognize_loop(self) -> None:
        try:
            while True:
                segment = self._segments.get()
                if segment is _END:
                    return
                if self._abort.is_set():
                    continue
                started = time.monotonic()
                text = self._recognize(segment).strip()
                if self.diagnostic is not None:
                    self.diagnostic.decoded(segment.size, time.monotonic() - started)
                if text:
                    self.texts.append(text)
                    self._on_text(text)
        except MemoryError:
            self._abort.set()
            self.fail("Mémoire insuffisante : transcription incomplète, texte déjà reconnu conservé.")
        except Exception as error:  # noqa: BLE001
            self._abort.set()
            self.fail(f"Reconnaissance ou sauvegarde interrompue : transcription incomplète ({error.__class__.__name__}).")
        finally:
            # Let the segmenter finish even if recognition stopped early.
            while self._abort.is_set():
                try:
                    if self._segments.get(timeout=0.2) is _END:
                        break
                except queue.Empty:
                    if not self._threads[0].is_alive():
                        break

    @property
    def text(self) -> str:
        return join_texts(self.texts)


def join_texts(texts: list[str]) -> str:
    return " ".join(part.strip() for part in texts if part.strip())
