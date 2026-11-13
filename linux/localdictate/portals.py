"""Clients for the XDG desktop portals used by LocalDictate, over GDBus.

- ``org.freedesktop.host.portal.Registry``: tells the portal our application ID when not sandboxed.
- ``GlobalShortcuts``: start/stop shortcut, chosen by the user in the desktop's own dialog.
- ``RemoteDesktop`` (keyboard only) + ``Clipboard``: put the text in the clipboard and send the paste keys. The
  user consents once; the restore token avoids asking again. No screen is shared and nothing is read: the
  clipboard is never read by the app.
"""
from __future__ import annotations

import os
import secrets
from typing import Callable

from gi.repository import Gio, GLib

BUS_NAME = "org.freedesktop.portal.Desktop"
OBJECT_PATH = "/org/freedesktop/portal/desktop"
REQUEST = "org.freedesktop.portal.Request"
SESSION = "org.freedesktop.portal.Session"
SHORTCUTS = "org.freedesktop.portal.GlobalShortcuts"
REMOTE = "org.freedesktop.portal.RemoteDesktop"
CLIPBOARD = "org.freedesktop.portal.Clipboard"
REGISTRY = "org.freedesktop.host.portal.Registry"

KEYBOARD = 1
PERSIST_UNTIL_REVOKED = 2
KEYSYMS = {"ctrl": 0xFFE3, "shift": 0xFFE1, "v": 0x0076, "insert": 0xFF63}
PASTE_SEQUENCES = {"ctrl-v": (["ctrl"], "v"), "ctrl-shift-v": (["ctrl", "shift"], "v"), "shift-insert": (["shift"], "insert")}
TEXT_MIME_TYPES = ["text/plain;charset=utf-8", "text/plain", "UTF8_STRING", "STRING", "TEXT"]

Results = Callable[[int, dict], None]


def _vardict(values: dict) -> dict:
    out = {}
    for key, value in values.items():
        if isinstance(value, GLib.Variant):
            out[key] = value
        elif isinstance(value, bool):
            out[key] = GLib.Variant("b", value)
        elif isinstance(value, int):
            out[key] = GLib.Variant("u", value)
        elif isinstance(value, str):
            out[key] = GLib.Variant("s", value)
        elif isinstance(value, list):
            out[key] = GLib.Variant("as", value)
        else:
            raise TypeError(key)
    return out


class Portal:
    def __init__(self, connection: Gio.DBusConnection, bus_name: str = BUS_NAME):
        self.connection = connection
        self.bus_name = bus_name
        self.sender = connection.get_unique_name().lstrip(":").replace(".", "_")
        self._versions: dict[str, int] = {}

    @staticmethod
    def token() -> str:
        return "localdictate_" + secrets.token_hex(6)

    def version(self, interface: str) -> int:
        if interface not in self._versions:
            self._versions[interface] = self._read_version(interface)
        return self._versions[interface]

    def _read_version(self, interface: str) -> int:
        try:
            result = self.connection.call_sync(
                self.bus_name, OBJECT_PATH, "org.freedesktop.DBus.Properties", "Get",
                GLib.Variant("(ss)", (interface, "version")), GLib.VariantType("(v)"), Gio.DBusCallFlags.NONE, 3000, None)
            return int(result.unpack()[0])
        except GLib.Error:
            return 0

    def register(self, app_id: str) -> bool:
        """Must run before any other portal call on this connection; harmless if unsupported."""
        try:
            self.connection.call_sync(self.bus_name, OBJECT_PATH, REGISTRY, "Register",
                                      GLib.Variant("(sa{sv})", (app_id, {})), None, Gio.DBusCallFlags.NONE, 3000, None)
            return True
        except GLib.Error:
            return False

    def call(self, interface: str, method: str, parameters: GLib.Variant | None,
             done: Callable[[GLib.Variant | None, str | None], None] = lambda r, e: None,
             fd_list: bool = False) -> None:
        def finished(connection, result, _data=None):
            try:
                if fd_list:
                    value, fds = connection.call_with_unix_fd_list_finish(result)
                    done((value, fds), None)
                else:
                    done(connection.call_finish(result), None)
            except GLib.Error as error:
                done(None, error.message)

        if fd_list:
            self.connection.call_with_unix_fd_list(self.bus_name, OBJECT_PATH, interface, method, parameters, None,
                                                   Gio.DBusCallFlags.NONE, -1, None, None, finished, None)
        else:
            self.connection.call(self.bus_name, OBJECT_PATH, interface, method, parameters, None,
                                 Gio.DBusCallFlags.NONE, -1, None, finished, None)

    def request(self, interface: str, method: str, build: Callable[[str], GLib.Variant], done: Results) -> None:
        """Calls a method returning a Request handle and waits for its Response signal."""
        token = self.token()
        path = f"/org/freedesktop/portal/desktop/request/{self.sender}/{token}"
        state = {"subscription": 0, "finished": False}

        def finish(code: int, results: dict) -> None:
            if state["finished"]:
                return
            state["finished"] = True
            if state["subscription"]:
                self.connection.signal_unsubscribe(state["subscription"])
            done(code, results)

        def on_response(_conn, _sender, _path, _iface, _signal, parameters, _data=None):
            code, results = parameters.unpack()
            finish(int(code), results)

        state["subscription"] = self.connection.signal_subscribe(
            self.bus_name, REQUEST, "Response", path, None, Gio.DBusSignalFlags.NONE,
            on_response, None)

        def called(result, error):
            if error is not None:
                finish(2, {"error": error})

        self.call(interface, method, build(token), called)

    def subscribe(self, interface: str, signal: str, handler: Callable[[tuple], None]) -> int:
        def on_signal(_conn, _sender, _path, _iface, _signal, parameters, _data=None):
            handler(parameters.unpack())

        return self.connection.signal_subscribe(self.bus_name, interface, signal, OBJECT_PATH, None,
                                                Gio.DBusSignalFlags.NONE, on_signal, None)

    def close_session(self, handle: str) -> None:
        self.connection.call(self.bus_name, handle, SESSION, "Close", None, None, Gio.DBusCallFlags.NONE, -1, None,
                             None, None)


def describe_response(code: int, results: dict) -> str:
    if code == 1:
        return "refusé ou annulé"
    if "error" in results:
        return str(results["error"])
    return "échec"


class GlobalShortcuts:
    """Binds the dictation shortcuts. The desktop shows its own dialog to choose or confirm the keys."""

    def __init__(self, portal: Portal, on_activated: Callable[[str], None],
                 on_status: Callable[[str, dict[str, str]], None]):
        self.portal = portal
        self.on_activated = on_activated
        self.on_status = on_status
        self.session: str | None = None
        self.triggers: dict[str, str] = {}
        self._subscriptions: list[int] = []

    @property
    def available(self) -> bool:
        return self.portal.version(SHORTCUTS) >= 1

    @property
    def configurable(self) -> bool:
        return self.portal.version(SHORTCUTS) >= 2

    def start(self, shortcuts: list[tuple[str, str, str | None]]) -> None:
        if not self.available:
            self.on_status("Raccourci global indisponible sur ce bureau : utilisez la commande ci-dessous.", {})
            return
        if not self._subscriptions:
            self._subscriptions.append(self.portal.subscribe(SHORTCUTS, "Activated", self._activated))
            self._subscriptions.append(self.portal.subscribe(SHORTCUTS, "ShortcutsChanged", self._changed))

        def created(code: int, results: dict) -> None:
            if code != 0 or "session_handle" not in results:
                self.on_status(f"Session de raccourcis impossible : {describe_response(code, results)}.", {})
                return
            self.session = str(results["session_handle"])
            self._bind(shortcuts)

        session_token = self.portal.token()
        self.portal.request(SHORTCUTS, "CreateSession", lambda token: GLib.Variant(
            "(a{sv})", (_vardict({"handle_token": token, "session_handle_token": session_token}),)), created)

    def _bind(self, shortcuts: list[tuple[str, str, str | None]]) -> None:
        items = []
        for shortcut_id, description, trigger in shortcuts:
            options = {"description": GLib.Variant("s", description)}
            if trigger:
                options["preferred_trigger"] = GLib.Variant("s", trigger)
            items.append((shortcut_id, options))

        def bound(code: int, results: dict) -> None:
            if code != 0:
                self.on_status(f"Raccourci non enregistré : {describe_response(code, results)}.", self.triggers)
                return
            self._store(results.get("shortcuts", []))
            self.on_status("Raccourci actif.", self.triggers)

        self.portal.request(SHORTCUTS, "BindShortcuts", lambda token: GLib.Variant(
            "(oa(sa{sv})sa{sv})", (self.session, items, "", _vardict({"handle_token": token}))), bound)

    def _store(self, shortcuts) -> None:
        self.triggers = {str(item[0]): str(item[1].get("trigger_description", "")) for item in shortcuts}

    def _activated(self, args: tuple) -> None:
        session, shortcut_id = args[0], args[1]
        if session == self.session:
            self.on_activated(str(shortcut_id))

    def _changed(self, args: tuple) -> None:
        if args[0] == self.session:
            self._store(args[1])
            self.on_status("Raccourci modifié.", self.triggers)

    def configure(self) -> bool:
        if self.session is None or not self.configurable:
            return False
        self.portal.call(SHORTCUTS, "ConfigureShortcuts",
                         GLib.Variant("(osa{sv})", (self.session, "", {})))
        return True

    def restart(self, shortcuts: list[tuple[str, str, str | None]]) -> None:
        if self.session:
            self.portal.close_session(self.session)
            self.session = None
        self.start(shortcuts)


class PasteSession:
    """Keyboard-only RemoteDesktop session with clipboard access, kept open while the app runs.

    The clipboard owner is this session: closing it would empty the clipboard, so it stays open and the last
    dictation remains pasteable by hand. Restore tokens are single-use; each Start returns the next one.
    """

    def __init__(self, portal: Portal, restore_token: Callable[[], str | None], save_token: Callable[[str | None], None]):
        self.portal = portal
        self.restore_token = restore_token
        self.save_token = save_token
        self.session: str | None = None
        self.clipboard = False
        self.keyboard = False
        self._starting: list[Callable[[bool, str | None], None]] = []
        self._text: bytes = b""
        self._subscriptions: list[int] = []

    @property
    def available(self) -> bool:
        return self.portal.version(REMOTE) >= 1

    @property
    def clipboard_supported(self) -> bool:
        return self.portal.version(CLIPBOARD) >= 1

    def ensure(self, done: Callable[[bool, str | None], None]) -> None:
        if self.session is not None:
            done(True, None)
            return
        self._starting.append(done)
        if len(self._starting) > 1:
            return
        if not self.available:
            self._finish(False, "Le bureau ne propose pas le portail RemoteDesktop.")
            return
        if not self._subscriptions:
            self._subscriptions.append(self.portal.subscribe(CLIPBOARD, "SelectionTransfer", self._transfer))
            self._subscriptions.append(self.portal.connection.signal_subscribe(
                self.portal.bus_name, SESSION, "Closed", None, None, Gio.DBusSignalFlags.NONE, self._closed, None))
        session_token = self.portal.token()
        self.portal.request(REMOTE, "CreateSession", lambda token: GLib.Variant(
            "(a{sv})", (_vardict({"handle_token": token, "session_handle_token": session_token}),)), self._created)

    def _finish(self, ok: bool, message: str | None) -> None:
        waiting, self._starting = self._starting, []
        for done in waiting:
            done(ok, message)

    def _created(self, code: int, results: dict) -> None:
        if code != 0 or "session_handle" not in results:
            self._finish(False, f"Session de collage impossible : {describe_response(code, results)}.")
            return
        handle = str(results["session_handle"])
        options = {"handle_token": None, "types": KEYBOARD, "persist_mode": PERSIST_UNTIL_REVOKED}
        token = self.restore_token()
        if token:
            options["restore_token"] = token

        def selected(code: int, results: dict) -> None:
            if code != 0:
                self._finish(False, f"Clavier virtuel refusé : {describe_response(code, results)}.")
                return
            if self.clipboard_supported:
                self.portal.call(CLIPBOARD, "RequestClipboard", GLib.Variant("(oa{sv})", (handle, {})),
                                 lambda _r, _e: self._start(handle))
            else:
                self._start(handle)

        def build(request_token: str) -> GLib.Variant:
            options["handle_token"] = request_token
            return GLib.Variant("(oa{sv})", (handle, _vardict(options)))

        self.portal.request(REMOTE, "SelectDevices", build, selected)

    def _start(self, handle: str) -> None:
        def started(code: int, results: dict) -> None:
            if code != 0:
                self.save_token(None)
                self._finish(False, f"Autorisation de collage refusée : {describe_response(code, results)}.")
                return
            self.session = handle
            self.keyboard = bool(int(results.get("devices", 0)) & KEYBOARD)
            self.clipboard = bool(results.get("clipboard_enabled", False))
            if "restore_token" in results:
                self.save_token(str(results["restore_token"]))
            self._finish(True, None)

        self.portal.request(REMOTE, "Start", lambda token: GLib.Variant(
            "(osa{sv})", (handle, "", _vardict({"handle_token": token}))), started)

    def _closed(self, _conn, _sender, path, _iface, _signal, _parameters, _data=None) -> None:
        if path == self.session:
            self.session = None
            self.clipboard = self.keyboard = False

    def set_clipboard(self, text: str, done: Callable[[bool], None]) -> None:
        if not (self.session and self.clipboard):
            done(False)
            return
        self._text = text.encode("utf-8")
        self.portal.call(CLIPBOARD, "SetSelection", GLib.Variant(
            "(oa{sv})", (self.session, _vardict({"mime_types": TEXT_MIME_TYPES}))),
            lambda result, error: done(error is None))

    def _transfer(self, args: tuple) -> None:
        session, _mime, serial = args
        if session != self.session:
            return
        data = self._text

        def written(result, error) -> None:
            success = False
            if error is None and result is not None:
                value, fds = result
                index = value.unpack()[0]
                fd = fds.get(index) if fds is not None else -1
                if fd >= 0:
                    try:
                        view = memoryview(data)
                        while view:
                            count = os.write(fd, view)
                            view = view[count:]
                        success = True
                    except OSError:
                        success = False
                    finally:
                        os.close(fd)
            self.portal.call(CLIPBOARD, "SelectionWriteDone", GLib.Variant("(oub)", (session, serial, success)))

        self.portal.call(CLIPBOARD, "SelectionWrite", GLib.Variant("(ou)", (session, serial)), written, fd_list=True)

    def send_keys(self, sequence: str) -> bool:
        if not (self.session and self.keyboard) or sequence not in PASTE_SEQUENCES:
            return False
        modifiers, key = PASTE_SEQUENCES[sequence]
        events = [(KEYSYMS[m], 1) for m in modifiers] + [(KEYSYMS[key], 1), (KEYSYMS[key], 0)] + \
                 [(KEYSYMS[m], 0) for m in reversed(modifiers)]
        for keysym, state in events:
            # Messages from one connection are delivered in order.
            self.portal.call(REMOTE, "NotifyKeyboardKeysym",
                             GLib.Variant("(oa{sv}iu)", (self.session, {}, keysym, state)))
        return True

    def close(self) -> None:
        if self.session:
            self.portal.close_session(self.session)
            self.session = None
