"""Pauses media players during a dictation, through MPRIS (org.mpris.MediaPlayer2.*) on the session bus.

Only players that were *Playing* are paused, and only those are resumed, and only if they are still paused
when the microphone closes (a player the user stopped or restarted meanwhile is left alone). All calls are
asynchronous: the microphone is never delayed by a slow player. Players that do not implement MPRIS keep
playing.
"""
from __future__ import annotations

from gi.repository import Gio, GLib

PREFIX = "org.mpris.MediaPlayer2."
PATH = "/org/mpris/MediaPlayer2"
PLAYER = "org.mpris.MediaPlayer2.Player"
TIMEOUT_MS = 2000


class MediaPauser:
    def __init__(self, connection: Gio.DBusConnection):
        self.connection = connection
        self.active = False
        self.paused: set[str] = set()

    def _call(self, name: str, interface: str, method: str, parameters, done=None) -> None:
        def finished(connection, result, _data=None):
            try:
                value = connection.call_finish(result)
            except GLib.Error:
                value = None
            if done is not None:
                done(value)

        self.connection.call(name, PATH, interface, method, parameters, None, Gio.DBusCallFlags.NO_AUTO_START,
                             TIMEOUT_MS, None, finished, None)

    def _status(self, name: str, done) -> None:
        self._call(name, "org.freedesktop.DBus.Properties", "Get", GLib.Variant("(ss)", (PLAYER, "PlaybackStatus")),
                   lambda value: done(value.unpack()[0] if value is not None else None))

    def pause(self) -> None:
        self.active = True

        def listed(connection, result, _data=None):
            try:
                names = connection.call_finish(result).unpack()[0]
            except GLib.Error:
                return
            for name in names:
                if name.startswith(PREFIX):
                    self._status(name, lambda status, name=name: self._pause_if_playing(name, status))

        self.connection.call("org.freedesktop.DBus", "/org/freedesktop/DBus", "org.freedesktop.DBus", "ListNames",
                             None, GLib.VariantType("(as)"), Gio.DBusCallFlags.NONE, TIMEOUT_MS, None, listed, None)

    def _pause_if_playing(self, name: str, status: str | None) -> None:
        if status != "Playing" or not self.active:
            return

        def paused(value) -> None:
            if value is None:
                return
            if self.active:
                self.paused.add(name)
            else:  # The dictation already ended: give the music back at once.
                self._call(name, PLAYER, "Play", None)

        self._call(name, PLAYER, "Pause", None, paused)

    def resume(self) -> None:
        self.active = False
        names, self.paused = self.paused, set()
        for name in names:
            self._status(name, lambda status, name=name: status == "Paused" and self._call(name, PLAYER, "Play", None))
