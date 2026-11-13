"""Delivers a finished dictation: clipboard first, then the paste keys in the focused window.

The text is already in the private history before this runs. Linux gives no reliable way to read the focused
field: a selection in the target is replaced by the paste (maintainer decision, 12 November 2026), and the
paste cannot be confirmed. When the keys cannot be sent the text stays in the clipboard for a manual paste.
"""
from __future__ import annotations

import os
import shutil
import subprocess
from typing import Callable

from gi.repository import Gdk, GLib

from .portals import PasteSession

Done = Callable[[str, str], None]  # (insertion: "pasted" | "copied" | "none", message)


def _wayland() -> bool:
    return bool(os.environ.get("WAYLAND_DISPLAY"))


def _desktop() -> str:
    return os.environ.get("XDG_CURRENT_DESKTOP", "").upper()


def _fallback_clipboard(text: str) -> bool:
    """Without the portal clipboard: wl-copy where the compositor allows it (KDE, wlroots), GTK on X11."""
    if _wayland():
        if "GNOME" in _desktop():
            return False  # GNOME only lets a focused window own the clipboard.
        tool = shutil.which("wl-copy")
        if not tool:
            return False
        try:
            subprocess.run([tool, "--type", "text/plain;charset=utf-8"], input=text.encode("utf-8"), timeout=5,
                           check=True, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
            return True
        except (OSError, subprocess.SubprocessError):
            return False
    display = Gdk.Display.get_default()
    if display is None:
        return False
    display.get_clipboard().set(text)
    return True


def _fallback_keys(sequence: str) -> bool:
    if _wayland():
        return False
    tool = shutil.which("xdotool")
    if not tool:
        return False
    keys = {"ctrl-v": "ctrl+v", "ctrl-shift-v": "ctrl+shift+v", "shift-insert": "shift+Insert"}[sequence]
    try:
        subprocess.run([tool, "key", "--clearmodifiers", keys], timeout=5, check=True)
        return True
    except (OSError, subprocess.SubprocessError):
        return False


class Paster:
    def __init__(self, session: PasteSession | None):
        self.session = session

    def deliver(self, text: str, auto_paste: bool, sequence: str, done: Done, key_delay_ms: int = 150) -> None:
        if not text:
            done("none", "Aucun texte reconnu.")
            return

        def with_session(ok: bool, message: str | None) -> None:
            if ok and self.session is not None and self.session.clipboard:
                self.session.set_clipboard(text, lambda copied: after_copy(copied, message))
            else:
                after_copy(_fallback_clipboard(text), message)

        def after_copy(copied: bool, session_message: str | None) -> None:
            if not copied:
                done("none", "Presse-papiers inaccessible : copiez le texte depuis l’historique."
                     + (f" ({session_message})" if session_message else ""))
                return
            if not auto_paste:
                done("copied", "Texte copié : collez-le avec Ctrl+V.")
                return
            # Let the shortcut keys be released before sending the paste keys.
            GLib.timeout_add(key_delay_ms, send_keys, session_message)

        def send_keys(session_message: str | None) -> bool:
            if self.session is not None and self.session.send_keys(sequence):
                done("pasted", "Texte collé dans la fenêtre active (il reste dans le presse-papiers).")
            elif _fallback_keys(sequence):
                done("pasted", "Texte collé dans la fenêtre active (il reste dans le presse-papiers).")
            else:
                reason = f" ({session_message})" if session_message else ""
                done("copied", "Texte copié, collage automatique impossible : collez avec Ctrl+V." + reason)
            return False

        if self.session is not None and (self.session.available or self.session.session):
            self.session.ensure(with_session)
        else:
            with_session(False, None)
