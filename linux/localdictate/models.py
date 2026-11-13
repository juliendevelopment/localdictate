"""Explicit model preparation: the only network access of the app.

Same pinned artifacts as the Android app (sizes and SHA-256 from a verification of the real files): HTTPS,
resumable ``.part`` download with strict Content-Range/Length checks, allow-listed tar+bzip2 extraction with
quotas, rejection of links/traversals/duplicates, atomic install and a versioned marker. At launch only sizes,
regular files and the marker are checked; hashes are verified at install.
"""
from __future__ import annotations

import bz2
import hashlib
import os
import shutil
import tarfile
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
ASR_URL = "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/"
MIN_FREE_BYTES = 1_500_000_000
ARCHIVE = Asset(f"{PARAKEET}.tar.bz2", 487_170_055, "5793d0fd397c5778d2cf2126994d58e9d56b1be7c04d13c7a15bb1b4eafb16bf")
VAD = Asset("silero_vad.onnx", 643_854, "9e2449e1087496d8d4caba907f23e0bd3f78d91fa552479bb9c23ac09cbb1fd6")
MEMBERS = (
    Asset("encoder.int8.onnx", 652_184_281, "acfc2b4456377e15d04f0243af540b7fe7c992f8d898d751cf134c3a55fd2247"),
    Asset("decoder.int8.onnx", 11_845_275, "179e50c43d1a9de79c8a24149a2f9bac6eb5981823f2a2ed88d655b24248db4e"),
    Asset("joiner.int8.onnx", 6_355_277, "3164c13fc2821009440d20fcb5fdc78bff28b4db2f8d0f0b329101719c0948b3"),
    Asset("tokens.txt", 93_939, "d58544679ea4bc6ac563d1f545eb7d474bd6cfa467f0a6e2c1dc1c7d37e3c35d"),
)
MARKER = "verified-models-v1\n" + "\n".join(f"{a.name}:{a.size}:{a.sha256}" for a in (ARCHIVE, VAD, *MEMBERS))
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


def safe_member_path(name: str, prefix: str) -> str:
    path = name.rstrip("/")
    parts = path.split("/")
    if (not path or path.startswith("/") or "\\" in path or ":" in path or "\x00" in path
            or any(part in ("", ".", "..") for part in parts) or (path != prefix and not path.startswith(prefix + "/"))):
        raise IntegrityError(f"Unsafe archive path: {name}")
    return path


class _LimitedReader:
    """Bounds the decompressed stream, including tar metadata, and checks cancellation."""

    def __init__(self, raw, limit: int, cancel: threading.Event | None):
        self.raw, self.remaining, self.cancel = raw, limit, cancel

    def read(self, size: int = -1) -> bytes:
        if self.cancel is not None and self.cancel.is_set():
            raise Cancelled()
        data = self.raw.read(size if size >= 0 else CHUNK)
        self.remaining -= len(data)
        if self.remaining < 0:
            raise IntegrityError("Expanded archive limit exceeded")
        return data


def extract_archive(archive: Path, destination: Path, prefix: str = PARAKEET, members: Iterable[Asset] = MEMBERS,
                    on_fraction: Callable[[float], None] = lambda f: None, cancel: threading.Event | None = None,
                    unlisted_budget: int = 128_000_000) -> None:
    members = tuple(members)
    if destination.exists():
        raise IntegrityError("Extraction destination must be fresh")
    destination.mkdir(parents=True, mode=0o700)
    expected = {f"{prefix}/{a.name}": a for a in members}
    expected_bytes = sum(a.size for a in members)
    seen: set[str] = set()
    extracted = skipped = entries = 0
    try:
        with open(archive, "rb") as raw, bz2.BZ2File(raw) as decompressed:
            limited = _LimitedReader(decompressed, expected_bytes + unlisted_budget + 4_000_000, cancel)
            with tarfile.open(fileobj=limited, mode="r|") as tar:
                for info in tar:
                    entries += 1
                    if entries > 4096:
                        raise IntegrityError("Too many archive entries")
                    name = safe_member_path(info.name, prefix)
                    if info.isdir():
                        if info.size != 0:
                            raise IntegrityError("Nonempty directory entry")
                        continue
                    if not info.isreg() or info.sparse is not None:
                        raise IntegrityError(f"Unsupported archive entry: {name}")
                    asset = expected.get(name)
                    source = tar.extractfile(info)
                    if source is None:
                        raise IntegrityError(f"Unreadable archive entry: {name}")
                    if asset is None:
                        # Upstream ships test_wavs: discarded, never installed.
                        if info.size > 32_000_000 or info.size > unlisted_budget - skipped:
                            raise IntegrityError("Unlisted archive entry exceeds size budget")
                        skipped += info.size
                        while source.read(CHUNK):
                            pass
                        continue
                    if name in seen or info.size != asset.size:
                        raise IntegrityError(f"Duplicate or wrong-sized model: {name}")
                    seen.add(name)
                    digest = hashlib.sha256()
                    written = 0
                    fd = os.open(destination / asset.name, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
                    with os.fdopen(fd, "wb") as output:
                        while chunk := source.read(CHUNK):
                            if written + len(chunk) > asset.size:
                                raise IntegrityError("Oversized model")
                            output.write(chunk)
                            digest.update(chunk)
                            written += len(chunk)
                            extracted += len(chunk)
                            on_fraction(extracted / expected_bytes)
                        output.flush()
                        os.fsync(output.fileno())
                    if written != asset.size or digest.hexdigest() != asset.sha256:
                        raise IntegrityError(f"Invalid extracted model: {asset.name}")
        if seen != set(expected):
            raise IntegrityError("Archive is missing required models")
        on_fraction(1.0)
    except BaseException:
        shutil.rmtree(destination, ignore_errors=True)
        raise


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
            downloader: Downloader | None = None, base_url: str = ASR_URL, archive: Asset = ARCHIVE,
            vad: Asset = VAD) -> ModelFiles:
    """Downloads, verifies and installs the models. Raises Cancelled, IntegrityError or OSError."""
    if store.ready():
        return store.files()
    store.root.mkdir(parents=True, exist_ok=True, mode=0o700)
    store.downloads.mkdir(parents=True, exist_ok=True, mode=0o700)
    free = shutil.disk_usage(store.root).free
    if free < MIN_FREE_BYTES:
        raise OSError(f"Espace disque insuffisant : {free // 1_000_000} Mo libres, 1500 Mo nécessaires.")
    downloader = downloader or Downloader(cancel)
    total = archive.size + vad.size
    archive_path = store.downloads / archive.name
    vad_path = store.downloads / vad.name

    def report(label: str, done: int) -> None:
        on_progress(Progress(label, min(1.0, done / total)))

    downloader.fetch(base_url + archive.name, archive, archive_path, lambda n: report("Téléchargement du modèle Parakeet", n))
    downloader.fetch(base_url + vad.name, vad, vad_path, lambda n: report("Téléchargement de Silero VAD", archive.size + n))
    staging = store.root / "staging"
    shutil.rmtree(staging, ignore_errors=True)
    staging.mkdir(mode=0o700)
    extract_archive(archive_path, staging / store.directory, store.directory, store.members,
                    lambda f: on_progress(Progress("Extraction et vérification", f)), cancel)
    shutil.copyfile(vad_path, staging / vad.name)
    if not verified(staging / vad.name, vad, cancel):
        raise IntegrityError("Checksum mismatch: silero_vad.onnx")
    marker = staging / ".ready"
    marker.write_text(store.marker, encoding="utf-8")
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
