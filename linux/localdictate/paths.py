"""Private locations. Everything lives under the user's XDG directories with 0700 permissions."""
from __future__ import annotations

import os
from pathlib import Path

APP_ID = "be.arte.LocalDictate"


def _xdg(variable: str, default: str) -> Path:
    value = os.environ.get(variable)
    return Path(value) if value and os.path.isabs(value) else Path.home() / default


def private_dir(path: Path) -> Path:
    path.mkdir(parents=True, exist_ok=True, mode=0o700)
    os.chmod(path, 0o700)
    return path


def data_dir() -> Path:
    override = os.environ.get("LOCALDICTATE_DATA_DIR")
    return private_dir(Path(override) if override else _xdg("XDG_DATA_HOME", ".local/share") / "localdictate")


def config_dir() -> Path:
    override = os.environ.get("LOCALDICTATE_CONFIG_DIR")
    return private_dir(Path(override) if override else _xdg("XDG_CONFIG_HOME", ".config") / "localdictate")


def atomic_write(path: Path, data: bytes) -> None:
    """Write, sync, then rename over the target, so a reader sees the old or the new content, never half."""
    temporary = path.with_name(path.name + ".tmp")
    fd = os.open(temporary, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
    try:
        with os.fdopen(fd, "wb") as file:
            file.write(data)
            file.flush()
            os.fsync(file.fileno())
        os.replace(temporary, path)
    except BaseException:
        try:
            os.unlink(temporary)
        except FileNotFoundError:
            pass
        raise
    try:
        directory = os.open(path.parent, os.O_RDONLY)
        try:
            os.fsync(directory)
        finally:
            os.close(directory)
    except OSError:
        pass
