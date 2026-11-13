"""Test doubles only: never shipped or used by the application."""
import queue
import threading
import time

import numpy as np


class ChunkSegmenter:
    """Emits one segment per ``size`` samples, like a VAD on continuous speech."""

    def __init__(self, size=1600):
        self.size = size
        self.buffer = np.zeros(0, dtype=np.float32)

    def accept(self, samples):
        self.buffer = np.concatenate([self.buffer, samples])
        out = []
        while self.buffer.size >= self.size:
            out.append(self.buffer[:self.size])
            self.buffer = self.buffer[self.size:]
        return out

    def finish(self):
        out, self.buffer = ([self.buffer] if self.buffer.size else []), np.zeros(0, dtype=np.float32)
        return out


class CountingEngine:
    def __init__(self, delay=0.0, fail_at=None):
        self.count = 0
        self.delay = delay
        self.fail_at = fail_at

    def recognize(self, samples):
        self.count += 1
        if self.fail_at == self.count:
            raise RuntimeError("boom")
        time.sleep(self.delay)
        return f"mot{self.count}"

    def segmenter(self, threshold=0.5):
        return ChunkSegmenter()


class FakeCapture:
    """Feeds ``blocks`` blocks of 0.1 s from a thread, then keeps silent until stopped."""

    def __init__(self, on_block, on_error, blocks=10, fail_start=False):
        self.on_block = on_block
        self.on_error = on_error
        self.blocks = blocks
        self.fail_start = fail_start
        self.stopped = threading.Event()
        self.device_found = True
        self.thread = None

    def start(self, device):
        if self.fail_start:
            raise RuntimeError("no microphone")

        def run():
            for _ in range(self.blocks):
                if self.stopped.is_set():
                    return
                self.on_block(np.full(1600, 0.1, dtype=np.float32), time.monotonic())

        self.thread = threading.Thread(target=run, daemon=True)
        self.thread.start()

    def stop(self):
        self.stopped.set()


class Loop:
    """Minimal main loop: dispatch() queues, pump() runs on the test thread."""

    def __init__(self):
        self.queue = queue.Queue()
        self.timers = []

    def dispatch(self, fn, *args):
        self.queue.put((fn, args))

    def timer(self, seconds, fn):
        self.timers.append(fn)

    def pump(self, until, timeout=10.0):
        deadline = time.monotonic() + timeout
        while time.monotonic() < deadline:
            try:
                fn, args = self.queue.get(timeout=0.02)
                fn(*args)
            except queue.Empty:
                pass
            if until():
                return True
        return False


class FakePaster:
    def __init__(self, insertion="pasted"):
        self.calls = []
        self.insertion = insertion

    def deliver(self, text, auto_paste, sequence, done):
        self.calls.append((text, auto_paste, sequence))
        done(self.insertion if auto_paste else "copied", "ok")
