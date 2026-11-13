"""Private dictation history: each dictation keeps its recording (WAV) and its transcript.

Layout: ``<data>/history/<id>/{audio.wav, transcript.txt, meta.json}``, directories 0700, files 0600. The
transcript is rewritten atomically after each recognized segment, and the WAV is synced about once per second,
so a crash loses at most the last second of audio and no recognized text. An entry still marked ``recording``
at startup is marked ``interrupted`` and its WAV header repaired; it can be transcribed again.
"""
from __future__ import annotations

import json
import os
import secrets
import shutil
import threading
import time
from dataclasses import asdict, dataclass, field
from pathlib import Path

from .dsp import RATE
from .paths import atomic_write, private_dir
from .wav import WavWriter, repair_header

STATES = ("recording", "transcribing", "done", "cancelled", "interrupted", "failed")
INSERTIONS = ("none", "pasted", "copied")


@dataclass
class EntryMeta:
    id: str
    created: float
    state: str = "recording"
    duration_ms: int = 0
    insertion: str = "none"
    message: str | None = None
    device_label: str | None = None
    extra: dict = field(default_factory=dict)


class Entry:
    def __init__(self, directory: Path, meta: EntryMeta):
        self.directory = directory
        self.meta = meta
        self._lock = threading.Lock()

    @property
    def id(self) -> str:
        return self.meta.id

    @property
    def audio_path(self) -> Path:
        return self.directory / "audio.wav"

    @property
    def transcript_path(self) -> Path:
        return self.directory / "transcript.txt"

    def text(self) -> str:
        try:
            return self.transcript_path.read_text(encoding="utf-8")
        except FileNotFoundError:
            return ""

    def has_audio(self) -> bool:
        try:
            return self.audio_path.stat().st_size > 44
        except FileNotFoundError:
            return False

    def set_text(self, text: str) -> None:
        with self._lock:
            atomic_write(self.transcript_path, text.encode("utf-8"))

    def update(self, **changes) -> None:
        with self._lock:
            for key, value in changes.items():
                if not hasattr(self.meta, key):
                    raise AttributeError(key)
                if key == "state" and value not in STATES:
                    raise ValueError(value)
                if key == "insertion" and value not in INSERTIONS:
                    raise ValueError(value)
                setattr(self.meta, key, value)
            atomic_write(self.directory / "meta.json", json.dumps(asdict(self.meta), ensure_ascii=False).encode("utf-8"))

    def open_audio(self) -> WavWriter:
        return WavWriter(str(self.audio_path))


class History:
    def __init__(self, root: Path):
        self.root = private_dir(root)
        self._lock = threading.Lock()

    def create(self, device_label: str | None = None) -> Entry:
        with self._lock:
            stamp = time.strftime("%Y%m%d-%H%M%S")
            while True:
                entry_id = f"{stamp}-{secrets.token_hex(3)}"
                directory = self.root / entry_id
                try:
                    directory.mkdir(mode=0o700)
                    break
                except FileExistsError:
                    continue
            entry = Entry(directory, EntryMeta(id=entry_id, created=time.time(), device_label=device_label))
            entry.update()
            return entry

    def _load(self, directory: Path) -> Entry | None:
        try:
            raw = json.loads((directory / "meta.json").read_text(encoding="utf-8"))
            known = {key: raw[key] for key in EntryMeta.__dataclass_fields__ if key in raw}
            meta = EntryMeta(**known)
            if meta.id != directory.name or meta.state not in STATES or meta.insertion not in INSERTIONS:
                return None
            return Entry(directory, meta)
        except (OSError, ValueError, TypeError, KeyError):
            return None

    def entries(self) -> list[Entry]:
        result = []
        for directory in self.root.iterdir():
            if directory.is_dir() and not directory.is_symlink():
                entry = self._load(directory)
                if entry:
                    result.append(entry)
        result.sort(key=lambda entry: (entry.meta.created, entry.id), reverse=True)
        return result

    def get(self, entry_id: str) -> Entry | None:
        directory = self.root / entry_id
        if directory.parent != self.root or not directory.is_dir() or directory.is_symlink():
            return None
        return self._load(directory)

    def delete(self, entry_id: str) -> None:
        entry = self.get(entry_id)
        if entry:
            shutil.rmtree(entry.directory)

    def prune(self, limit: int, keep: set[str] = frozenset()) -> int:
        """Deletes the oldest finished entries beyond ``limit``. Unfinished or protected entries are kept."""
        removed = 0
        finished = [e for e in self.entries() if e.meta.state not in ("recording", "transcribing")]
        for entry in finished[limit:]:
            if entry.id not in keep:
                shutil.rmtree(entry.directory, ignore_errors=True)
                removed += 1
        return removed

    def recover(self) -> list[Entry]:
        """At startup: entries left ``recording``/``transcribing`` by a crash become ``interrupted``."""
        recovered = []
        for entry in self.entries():
            if entry.meta.state in ("recording", "transcribing"):
                samples = repair_header(str(entry.audio_path)) if entry.audio_path.exists() else 0
                entry.update(state="interrupted", duration_ms=samples * 1000 // RATE,
                             message="Dictée interrompue (application fermée). Le son et le texte déjà reconnu sont conservés.")
                recovered.append(entry)
        return recovered

    def disk_usage(self) -> int:
        total = 0
        for path, _dirs, files in os.walk(self.root):
            for name in files:
                try:
                    total += os.path.getsize(os.path.join(path, name))
                except OSError:
                    pass
        return total
