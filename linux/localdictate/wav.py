"""Progressive 16 kHz mono PCM16 WAV files.

The header is rewritten about once per second and data is synced, so a crash leaves a readable file holding
the audio captured up to the last sync. A header with stale sizes is repaired from the file size on read.
"""
from __future__ import annotations

import os
import struct
import time

import numpy as np

from .dsp import RATE, float_to_pcm16, pcm16_to_float

HEADER_SIZE = 44


def _header(data_bytes: int, rate: int = RATE) -> bytes:
    return (b"RIFF" + struct.pack("<I", 36 + data_bytes) + b"WAVE" + b"fmt " +
            struct.pack("<IHHIIHH", 16, 1, 1, rate, rate * 2, 2, 16) + b"data" + struct.pack("<I", data_bytes))


class WavWriter:
    def __init__(self, path: str, sync_seconds: float = 1.0):
        fd = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
        self.file = os.fdopen(fd, "wb")
        self.file.write(_header(0))
        self.data_bytes = 0
        self.sync_seconds = sync_seconds
        self.last_sync = time.monotonic()
        self.closed = False

    @property
    def samples(self) -> int:
        return self.data_bytes // 2

    def write(self, samples: np.ndarray) -> None:
        data = float_to_pcm16(samples)
        self.file.write(data)
        self.data_bytes += len(data)
        if time.monotonic() - self.last_sync >= self.sync_seconds:
            self.sync()

    def sync(self) -> None:
        self.file.seek(0)
        self.file.write(_header(self.data_bytes))
        self.file.seek(0, os.SEEK_END)
        self.file.flush()
        os.fsync(self.file.fileno())
        self.last_sync = time.monotonic()

    def close(self) -> None:
        if self.closed:
            return
        self.closed = True
        try:
            self.sync()
        finally:
            self.file.close()


def read_wav(path: str) -> np.ndarray:
    """Reads a file written by WavWriter (mono PCM16 at 16 kHz), repairing stale sizes after a crash."""
    with open(path, "rb") as file:
        header = file.read(HEADER_SIZE)
        if len(header) < HEADER_SIZE or header[:4] != b"RIFF" or header[8:12] != b"WAVE":
            raise ValueError("not a LocalDictate WAV file")
        channels, rate = struct.unpack("<HI", header[22:28])
        bits = struct.unpack("<H", header[34:36])[0]
        if channels != 1 or rate != RATE or bits != 16 or header[36:40] != b"data":
            raise ValueError("unexpected WAV format")
        data = file.read()
    usable = len(data) - len(data) % 2
    return pcm16_to_float(data[:usable])


def repair_header(path: str) -> int:
    """Rewrites the sizes of an interrupted file from its real length; returns the number of samples."""
    size = os.path.getsize(path)
    data_bytes = max(0, size - HEADER_SIZE)
    data_bytes -= data_bytes % 2
    with open(path, "r+b") as file:
        file.write(_header(data_bytes))
        file.flush()
        os.fsync(file.fileno())
    return data_bytes // 2
