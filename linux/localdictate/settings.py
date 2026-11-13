"""User settings, stored as JSON in the private config directory. Unknown or invalid values fall back to defaults."""
from __future__ import annotations

import json
from dataclasses import asdict, dataclass, fields
from pathlib import Path

from .paths import atomic_write, config_dir

PASTE_KEYS = {
    "ctrl-v": "Ctrl+V",
    "ctrl-shift-v": "Ctrl+Maj+V (terminaux)",
    "shift-insert": "Maj+Inser",
}


@dataclass
class Settings:
    # Microphone: GStreamer device key (None = system default), manual gain and automatic gain.
    input_device: str | None = None
    input_device_label: str | None = None
    gain_db: float = 0.0
    auto_gain: bool = True
    max_auto_gain_db: float = 18.0
    # Silero speech probability threshold: lower = more sensitive (picks up softer speech, and more noise).
    vad_threshold: float = 0.5
    # Shortcut suggested to the desktop (XDG shortcuts specification syntax).
    preferred_trigger: str = "CTRL+ALT+d"
    auto_paste: bool = True
    paste_keys: str = "ctrl-v"
    # Paste restore token from the RemoteDesktop portal (not a secret: valid only for this app and user).
    paste_restore_token: str | None = None
    history_limit: int = 50
    # Pause MPRIS media players (music, podcasts, videos in browsers) while the microphone is open.
    pause_media: bool = True
    show_tray: bool = True
    start_in_background: bool = False

    def sanitized(self) -> "Settings":
        default = Settings()
        clean = Settings(**asdict(self))
        clean.gain_db = min(30.0, max(-12.0, float(clean.gain_db)))
        clean.max_auto_gain_db = min(30.0, max(0.0, float(clean.max_auto_gain_db)))
        clean.vad_threshold = min(0.9, max(0.1, float(clean.vad_threshold)))
        clean.history_limit = min(1000, max(1, int(clean.history_limit)))
        if clean.paste_keys not in PASTE_KEYS:
            clean.paste_keys = default.paste_keys
        if not isinstance(clean.preferred_trigger, str) or not clean.preferred_trigger.strip():
            clean.preferred_trigger = default.preferred_trigger
        return clean


def settings_path() -> Path:
    return config_dir() / "settings.json"


def load_settings(path: Path | None = None) -> Settings:
    path = path or settings_path()
    try:
        raw = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, ValueError):
        return Settings()
    if not isinstance(raw, dict):
        return Settings()
    defaults = Settings()
    values = {}
    for field in fields(Settings):
        value = raw.get(field.name, getattr(defaults, field.name))
        expected = type(getattr(defaults, field.name))
        if getattr(defaults, field.name) is None:
            values[field.name] = value if isinstance(value, str) else None
        elif expected is float and isinstance(value, (int, float)) and not isinstance(value, bool):
            values[field.name] = float(value)
        elif isinstance(value, expected) and not (expected is int and isinstance(value, bool)):
            values[field.name] = value
        else:
            values[field.name] = getattr(defaults, field.name)
    try:
        return Settings(**values).sanitized()
    except (TypeError, ValueError):
        return Settings()


def save_settings(settings: Settings, path: Path | None = None) -> None:
    path = path or settings_path()
    atomic_write(path, json.dumps(asdict(settings.sanitized()), indent=2, ensure_ascii=False).encode("utf-8"))
