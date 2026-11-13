"""Explicit model preparation: the only network access of the app.

The Parakeet files are the ones verified for the Android app (same sizes and SHA-256), downloaded one by one
from an **immutable** Hugging Face revision of the sherpa-onnx export. The GitHub "asr-models" archive is a
mutable release asset: it was replaced by a new export on 7 November 2026 (different encoder and decoder), which
broke the pinned download. Silero VAD still comes from that release (unchanged, checked by SHA-256).

HTTPS, resumable ``.part`` downloads with strict Content-Range/Length checks, a hash check of every file before
it is installed, an atomic install and a versioned marker. At launch only sizes, regular files and the marker
are checked.
"""
from __future__ import annotations

import hashlib
import os
import shutil
import threading
import urllib.request
from dataclasses import dataclass
from pathlib import Path
from typing import Callable, Iterable

from .engine import ModelFiles


@dataclass(frozen=True)
class Asset:
    name: str
    size: int
    sha256: str


PARAKEET = "sherpa-onnx-nemo-parakeet-tdt-0.6b-v3-int8"
# Revision of 16 August 2025, whose files match the pinned hashes below (checked on 13 November 2026).
PARAKEET_REVISION = "2bda32ec70b097a55adaa07d9a7173915b43cc78"
PARAKEET_URL = f"https://huggingface.co/csukuangfj/{PARAKEET}/resolve/{PARAKEET_REVISION}/"
VAD_URL = "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/"
MIN_FREE_BYTES = 1_500_000_000
VAD = Asset("silero_vad.onnx", 643_854, "9e2449e1087496d8d4caba907f23e0bd3f78d91fa552479bb9c23ac09cbb1fd6")
MEMBERS = (
    Asset("encoder.int8.onnx", 652_184_281, "acfc2b4456377e15d04f0243af540b7fe7c992f8d898d751cf134c3a55fd2247"),
    Asset("decoder.int8.onnx", 11_845_275, "179e50c43d1a9de79c8a24149a2f9bac6eb5981823f2a2ed88d655b24248db4e"),
    Asset("joiner.int8.onnx", 6_355_277, "3164c13fc2821009440d20fcb5fdc78bff28b4db2f8d0f0b329101719c0948b3"),
    Asset("tokens.txt", 93_939, "d58544679ea4bc6ac563d1f545eb7d474bd6cfa467f0a6e2c1dc1c7d37e3c35d"),
)
MARKER = "verified-models-v2\n" + "\n".join(f"{a.name}:{a.size}:{a.sha256}" for a in (VAD, *MEMBERS))
CHUNK = 256 * 1024


class IntegrityError(OSError):
    pass


class Cancelled(Exception):
    pass


def regular_file_with_size(path: Path, size: int) -> bool:
    try:
        return path.is_file() and not path.is_symlink() and path.stat().st_size == size
    except OSError:
        return False


def sha256_file(path: Path, cancel: threading.Event | None = None) -> str:
    digest = hashlib.sha256()
    with open(path, "rb") as file:
        while chunk := file.read(CHUNK):
            if cancel is not None and cancel.is_set():
                raise Cancelled()
            digest.update(chunk)
    return digest.hexdigest()


def verified(path: Path, asset: Asset, cancel: threading.Event | None = None) -> bool:
    return regular_file_with_size(path, asset.size) and sha256_file(path, cancel) == asset.sha256


class ModelStore:
    def __init__(self, root: Path, members: Iterable[Asset] = MEMBERS, vad: Asset = VAD, marker: str = MARKER,
                 directory: str = PARAKEET):
        self.root = root
        self.members = tuple(members)
        self.vad = vad
        self.marker = marker
        self.directory = directory

    @property
    def installed(self) -> Path:
        return self.root / "installed"

    @property
    def downloads(self) -> Path:
        return self.root / "downloads"

    def files(self) -> ModelFiles:
        return ModelFiles(self.installed / self.directory, self.installed / self.vad.name)

    def ready(self) -> bool:
        installed = self.installed
        marker = installed / ".ready"
        parakeet = installed / self.directory
        try:
            return (installed.is_dir() and not installed.is_symlink() and parakeet.is_dir() and not parakeet.is_symlink()
                    and regular_file_with_size(marker, len(self.marker.encode()))
                    and marker.read_text(encoding="utf-8") == self.marker
                    and all(regular_file_with_size(parakeet / a.name, a.size) for a in self.members)
                    and regular_file_with_size(installed / self.vad.name, self.vad.size))
        except OSError:
            return False

    def clear(self) -> None:
        if self.root.exists():
            shutil.rmtree(self.root)


class Downloader:
    """HTTPS download to ``<target>.part`` with resume; the final file is verified before being renamed."""

    def __init__(self, cancel: threading.Event, opener: Callable = urllib.request.urlopen, timeout: float = 30.0):
        self.cancel = cancel
        self.opener = opener
        self.timeout = timeout

    def fetch(self, url: str, asset: Asset, target: Path, on_bytes: Callable[[int], None]) -> None:
        if not url.startswith("https://"):
            raise IntegrityError("HTTPS required")
        if regular_file_with_size(target, asset.size):
            if verified(target, asset, self.cancel):
                on_bytes(asset.size)
                return
            target.unlink()
        part = target.with_name(target.name + ".part")
        offset = part.stat().st_size if part.is_file() and not part.is_symlink() else 0
        if offset > asset.size:
            part.unlink()
            offset = 0
        if offset < asset.size:
            request = urllib.request.Request(url, headers={"Accept-Encoding": "identity", "User-Agent": "LocalDictate"})
            if offset:
                request.add_header("Range", f"bytes={offset}-")
            with self.opener(request, timeout=self.timeout) as response:
                final_url = response.geturl() if hasattr(response, "geturl") else url
                if not str(final_url).startswith("https://"):
                    raise IntegrityError("Redirected outside HTTPS")
                encoding = response.headers.get("Content-Encoding")
                if encoding not in (None, "", "identity"):
                    raise IntegrityError("Unexpected Content-Encoding")
                status = getattr(response, "status", 200)
                if offset and status == 206:
                    expected_range = f"bytes {offset}-{asset.size - 1}/{asset.size}"
                    if response.headers.get("Content-Range") != expected_range:
                        raise IntegrityError("Unexpected Content-Range")
                elif status == 200:
                    offset = 0
                else:
                    raise IntegrityError(f"HTTP status {status}")
                length = response.headers.get("Content-Length")
                if length is not None and int(length) != asset.size - offset:
                    raise IntegrityError("Unexpected Content-Length")
                mode = "ab" if offset else "wb"
                fd = os.open(part, os.O_WRONLY | os.O_CREAT | (os.O_APPEND if offset else os.O_TRUNC), 0o600)
                with os.fdopen(fd, mode) as output:
                    on_bytes(offset)
                    while True:
                        if self.cancel.is_set():
                            raise Cancelled()
                        chunk = response.read(CHUNK)
                        if not chunk:
                            break
                        if offset + len(chunk) > asset.size:
                            raise IntegrityError("Download larger than expected")
                        output.write(chunk)
                        offset += len(chunk)
                        on_bytes(offset)
                    output.flush()
                    os.fsync(output.fileno())
        if not verified(part, asset, self.cancel):
            part.unlink(missing_ok=True)
            raise IntegrityError(f"Checksum mismatch: {asset.name}")
        os.replace(part, target)


@dataclass
class Progress:
    label: str
    fraction: float | None = None


def prepare(store: ModelStore, cancel: threading.Event, on_progress: Callable[[Progress], None],
            downloader: Downloader | None = None, parakeet_url: str = PARAKEET_URL, vad_url: str = VAD_URL) -> ModelFiles:
    """Downloads, verifies and installs the models. Raises Cancelled, IntegrityError or OSError."""
    if store.ready():
        return store.files()
    store.root.mkdir(parents=True, exist_ok=True, mode=0o700)
    store.downloads.mkdir(parents=True, exist_ok=True, mode=0o700)
    free = shutil.disk_usage(store.root).free
    needed = sum(a.size for a in store.members) + store.vad.size
    if free < MIN_FREE_BYTES:
        raise OSError(f"Espace disque insuffisant : {free // 1_000_000} Mo libres, 1500 Mo nécessaires.")
    downloader = downloader or Downloader(cancel)
    plan = [(parakeet_url + a.name, a, "Téléchargement du modèle Parakeet") for a in store.members]
    plan.append((vad_url + store.vad.name, store.vad, "Téléchargement de Silero VAD"))
    done_before = 0
    for url, asset, label in plan:
        downloader.fetch(url, asset, store.downloads / asset.name,
                         lambda n, base=done_before, label=label: on_progress(Progress(label, min(1.0, (base + n) / needed))))
        done_before += asset.size
    on_progress(Progress("Installation", None))
    staging = store.root / "staging"
    shutil.rmtree(staging, ignore_errors=True)
    (staging / store.directory).mkdir(parents=True, mode=0o700)
    for asset in store.members:
        os.replace(store.downloads / asset.name, staging / store.directory / asset.name)
    os.replace(store.downloads / store.vad.name, staging / store.vad.name)
    (staging / ".ready").write_text(store.marker, encoding="utf-8")
    old = store.root / "old"
    shutil.rmtree(old, ignore_errors=True)
    if store.installed.exists():
        os.replace(store.installed, old)
    os.replace(staging, store.installed)
    shutil.rmtree(old, ignore_errors=True)
    shutil.rmtree(store.downloads, ignore_errors=True)
    if not store.ready():
        raise IntegrityError("Installed models failed verification")
    on_progress(Progress("Modèles prêts", 1.0))
    return store.files()
