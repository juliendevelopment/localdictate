"""GTK application: single instance, runs in the background, controlled by a global shortcut or the CLI."""
from __future__ import annotations

import os
import sys
from pathlib import Path

import gi

gi.require_version("Gtk", "4.0")
gi.require_version("Adw", "1")
from gi.repository import Adw, Gio, GLib  # noqa: E402

from . import __version__  # noqa: E402
from .history import History  # noqa: E402
from .models import ModelStore  # noqa: E402
from .paths import APP_ID, data_dir  # noqa: E402
from .portals import GlobalShortcuts, PasteSession, Portal  # noqa: E402
from .settings import load_settings, save_settings  # noqa: E402

SHORTCUT_TOGGLE = "toggle"
SHORTCUT_CANCEL = "cancel"


def autostart_path() -> Path:
    base = os.environ.get("XDG_CONFIG_HOME") or str(Path.home() / ".config")
    return Path(base) / "autostart" / f"{APP_ID}.desktop"


def set_autostart(enabled: bool) -> None:
    path = autostart_path()
    if enabled:
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text("[Desktop Entry]\nType=Application\nName=LocalDictate\nExec=localdictate --background\n"
                        f"Icon={APP_ID}\nX-GNOME-Autostart-enabled=true\nNoDisplay=true\n", encoding="utf-8")
    else:
        path.unlink(missing_ok=True)


class Application(Adw.Application):
    def __init__(self):
        super().__init__(application_id=APP_ID, flags=Gio.ApplicationFlags.HANDLES_COMMAND_LINE)
        self.add_main_option("toggle", ord("t"), GLib.OptionFlags.NONE, GLib.OptionArg.NONE,
                             "Démarrer ou arrêter la dictée (à lier à un raccourci)", None)
        self.add_main_option("cancel", ord("c"), GLib.OptionFlags.NONE, GLib.OptionArg.NONE,
                             "Annuler la dictée en cours (texte gardé, non collé)", None)
        self.add_main_option("background", ord("b"), GLib.OptionFlags.NONE, GLib.OptionArg.NONE,
                             "Démarrer sans ouvrir la fenêtre", None)
        self.add_main_option("quit", ord("q"), GLib.OptionFlags.NONE, GLib.OptionArg.NONE,
                             "Quitter LocalDictate", None)
        self.add_main_option("version", 0, GLib.OptionFlags.NONE, GLib.OptionArg.NONE, "Afficher la version", None)
        self.controller = None
        self.window = None
        self.shortcuts: GlobalShortcuts | None = None
        self.paste_session: PasteSession | None = None
        self.shortcut_status = ("", {})
        self.shortcut_listeners = []
        self.tray = None
        self.tray_listeners = []
        self._first = True

    # Lifecycle -------------------------------------------------------------------------------------------------
    def do_startup(self) -> None:
        # The portal registry must be told our application ID before any portal call on this connection, and GTK
        # itself reads the settings portal during startup.
        connection = self.get_dbus_connection()
        self.portal = Portal(connection) if connection is not None else None
        if self.portal is not None:
            self.portal.register(APP_ID)
        Adw.Application.do_startup(self)
        self.hold()  # Keep running in the background without a window.

        from .capture import Capture
        from .controller import Controller
        from .engine import Recognizer
        from .paste import Paster

        self.settings = load_settings()
        root = data_dir()
        store = ModelStore(root / "models")
        history = History(root / "history")
        if self.portal is not None:
            self.paste_session = PasteSession(
                self.portal, lambda: self.settings.paste_restore_token, self._save_restore_token)
        media = None
        if connection is not None:
            from .media import MediaPauser

            media = MediaPauser(connection)
        self.controller = Controller(
            self.settings, history, store,
            capture_factory=lambda on_block, on_error: Capture(on_block, on_error),
            engine_loader=Recognizer, paster=Paster(self.paste_session), notify=self.notify, media=media)
        for name, callback in (("toggle", lambda *a: self.controller.toggle()),
                               ("cancel", lambda *a: self.controller.cancel()),
                               ("show", lambda *a: self.show_window()),
                               ("settings", lambda *a: self.show_window("settings")),
                               ("folder", lambda *a: self.open_folder()),
                               ("quit", lambda *a: self.quit_app())):
            action = Gio.SimpleAction.new(name, None)
            action.connect("activate", callback)
            self.add_action(action)
        self.controller.listeners.append(self._update_tray)
        if connection is not None and self.settings.show_tray:
            self.set_tray(True)
        self.controller.startup()
        if self.portal is not None:
            self.shortcuts = GlobalShortcuts(self.portal, self._shortcut_activated, self._shortcut_status)
            self.shortcuts.start(self.shortcut_definitions())
        else:
            self._shortcut_status("Pas de bus de session : utilisez la commande ci-dessous.", {})

    # Status icon -----------------------------------------------------------------------------------------------
    def set_tray(self, enabled: bool) -> None:
        connection = self.get_dbus_connection()
        if enabled and self.tray is None and connection is not None:
            from .tray import Tray

            icons = os.environ.get("XDG_DATA_HOME") or str(Path.home() / ".local/share")
            self.tray = Tray(connection, toggle=self.controller.toggle, cancel=self.controller.cancel,
                             show=self.show_window, settings=lambda: self.show_window("settings"),
                             folder=self.open_folder, quit=self.quit_app,
                             on_availability=self._tray_availability, icon_theme_path=str(Path(icons) / "icons"))
            self.tray.start()
            self._update_tray(self.controller.snapshot)
        elif not enabled and self.tray is not None:
            self.tray.stop()
            self.tray = None
            self._tray_availability(False)

    def _tray_availability(self, available: bool) -> None:
        for listener in list(self.tray_listeners):
            listener(available)

    def _update_tray(self, snapshot) -> None:
        if self.tray is None:
            return
        from .controller import State

        state = snapshot.state
        if state == State.RECORDING:
            kind, detail = "listening", f"{snapshot.seconds // 60}:{snapshot.seconds % 60:02d}"
        elif state == State.PROCESSING:
            kind, detail = "processing", ""
        elif state in (State.READY,):
            kind, detail = "idle", "prêt"
        elif state in (State.LOADING, State.PREPARING):
            kind, detail = "idle", state.value.lower()
        else:
            kind, detail = "unavailable", state.value.lower()
        if (kind, detail) != getattr(self, "_tray_shown", None):
            self._tray_shown = (kind, detail)
            self.tray.update(kind, detail, state in (State.READY, State.LOADING, State.RECORDING))

    def open_folder(self) -> None:
        """Opens the private folder holding one subfolder per dictation (audio.wav, transcript.txt)."""
        uri = self.controller.history.root.as_uri()
        try:
            Gio.AppInfo.launch_default_for_uri(uri, None)
        except GLib.Error as error:
            self.notify("Dossier inaccessible", f"{self.controller.history.root} ({error.message})")

    def shortcut_definitions(self):
        return [(SHORTCUT_TOGGLE, "Démarrer / arrêter la dictée et coller le texte", self.settings.preferred_trigger),
                (SHORTCUT_CANCEL, "Annuler la dictée (texte gardé dans l’historique, non collé)", None)]

    def _save_restore_token(self, token: str | None) -> None:
        self.controller.update_settings(paste_restore_token=token)

    def _shortcut_activated(self, shortcut_id: str) -> None:
        if shortcut_id == SHORTCUT_TOGGLE:
            self.controller.toggle()
        elif shortcut_id == SHORTCUT_CANCEL:
            self.controller.cancel()

    def _shortcut_status(self, message: str, triggers: dict) -> None:
        self.shortcut_status = (message, triggers)
        for listener in list(self.shortcut_listeners):
            listener(message, triggers)

    def do_handle_local_options(self, options: GLib.VariantDict) -> int:
        if options.contains("version"):
            print(f"LocalDictate {__version__}")
            return 0
        return -1  # Continue: forwarded to the running instance if there is one.

    def do_command_line(self, command_line: Gio.ApplicationCommandLine) -> int:
        options = command_line.get_options_dict().end().unpack()
        first, self._first = self._first, False
        if options.get("quit"):
            self.quit_app()
        elif options.get("toggle"):
            self.controller.toggle()
        elif options.get("cancel"):
            self.controller.cancel()
        elif not (first and (options.get("background") or self.settings.start_in_background)):
            self.show_window()
        return 0

    def show_window(self, page: str | None = None) -> None:
        if self.window is None:
            from .window import MainWindow

            self.window = MainWindow(self)
        if page:
            self.window.show_page(page)
        self.window.present()

    def quit_app(self) -> None:
        if self.controller is not None and self.controller.snapshot.state.name == "RECORDING":
            self.controller.stop(paste=False)
        if self.paste_session is not None:
            self.paste_session.close()
        if self.tray is not None:
            self.tray.stop()
        self.release()
        self.quit()

    def notify(self, title: str, body: str | None) -> None:
        notification = Gio.Notification.new(title)
        if body:
            notification.set_body(body)
        notification.set_default_action("app.show")
        self.send_notification("dictation", notification)


def main(argv: list[str] | None = None) -> int:
    return Application().run(sys.argv if argv is None else argv)
