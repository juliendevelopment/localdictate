"""Main window (French UI): dictation state and shortcut, history with recordings, settings."""
from __future__ import annotations

import time

from gi.repository import Adw, Gdk, Gio, GLib, Gtk

from .app import autostart_path, set_autostart
from .controller import Snapshot, State
from .history import Entry
from .settings import PASTE_KEYS

STATE_LABELS = {"recording": "en cours", "transcribing": "transcription…", "done": "terminée",
                "cancelled": "annulée", "interrupted": "interrompue", "failed": "incomplète"}
INSERTION_LABELS = {"pasted": "collée", "copied": "copiée", "none": "non collée"}


def duration_label(ms: int) -> str:
    seconds = max(0, ms // 1000)
    if seconds < 60:
        return f"{seconds} s"
    minutes, seconds = divmod(seconds, 60)
    if minutes < 60:
        return f"{minutes} min {seconds:02d} s"
    hours, minutes = divmod(minutes, 60)
    return f"{hours} h {minutes:02d} min"


def size_label(size: int) -> str:
    return f"{size / 1_000_000:.1f} Mo".replace(".", ",")


class MainWindow(Adw.ApplicationWindow):
    def __init__(self, app):
        super().__init__(application=app, title="LocalDictate")
        self.app = app
        self.controller = app.controller
        self.set_default_size(560, 720)
        self.set_hide_on_close(True)
        self.player = None
        self.playing_id = None
        self.devices = []
        self._loading_devices = False

        stack = self.stack = Adw.ViewStack()
        stack.add_titled_with_icon(self._dictation_page(), "dictation", "Dictée", "audio-input-microphone-symbolic")
        self.history_page = Gtk.ScrolledWindow(vexpand=True)
        stack.add_titled_with_icon(self.history_page, "history", "Historique", "document-open-recent-symbolic")
        stack.add_titled_with_icon(self._settings_page(), "settings", "Réglages", "emblem-system-symbolic")

        header = Adw.HeaderBar()
        header.set_title_widget(Adw.ViewSwitcher(stack=stack, policy=Adw.ViewSwitcherPolicy.WIDE))
        menu = Gtk.MenuButton(icon_name="open-menu-symbolic")
        model = Gio_menu([("Quitter LocalDictate", "app.quit")])
        menu.set_menu_model(model)
        header.pack_end(menu)
        view = Adw.ToolbarView()
        view.add_top_bar(header)
        self.toasts = Adw.ToastOverlay(child=stack)
        view.set_content(self.toasts)
        self.set_content(view)

        app.tray_listeners.append(self._on_tray_availability)
        self._on_tray_availability(bool(app.tray and app.tray.available))
        self.controller.listeners.append(self._on_snapshot)
        self.controller.history_listeners.append(self._refresh_history)
        app.shortcut_listeners.append(self._on_shortcut_status)
        self._on_snapshot(self.controller.snapshot)
        self._on_shortcut_status(*app.shortcut_status)
        self._refresh_history()
        self.connect("close-request", self._on_close)

    def show_page(self, name: str) -> None:
        self.stack.set_visible_child_name(name)

    # Dictation page --------------------------------------------------------------------------------------------
    def _dictation_page(self) -> Gtk.Widget:
        page = Adw.PreferencesPage()
        state = Adw.PreferencesGroup(title="État")
        self.state_row = Adw.ActionRow(title="", subtitle="", use_markup=False)
        self.state_row.set_subtitle_lines(4)
        self.toggle_button = Gtk.Button(valign=Gtk.Align.CENTER)
        self.toggle_button.add_css_class("suggested-action")
        self.toggle_button.connect("clicked", lambda *_: self.controller.toggle())
        self.state_row.add_suffix(self.toggle_button)
        state.add(self.state_row)
        level_row = Adw.ActionRow(title="Niveau du micro")
        self.level_bar = Gtk.LevelBar(min_value=0, max_value=1, hexpand=True, valign=Gtk.Align.CENTER)
        self.level_bar.set_size_request(220, -1)
        level_row.add_suffix(self.level_bar)
        state.add(level_row)
        self.diagnostic_row = Adw.ActionRow(title="Dernière dictée", subtitle="—", use_markup=False)
        self.diagnostic_row.set_subtitle_selectable(True)
        state.add(self.diagnostic_row)
        page.add(state)

        shortcut = Adw.PreferencesGroup(
            title="Raccourci clavier",
            description="Un appui démarre la dictée, un second appui l’arrête et colle le texte à l’endroit du curseur. "
                        "Inutile de garder la touche enfoncée.")
        self.trigger_row = Adw.ActionRow(title="Démarrer / arrêter", subtitle="—", use_markup=False)
        self.configure_button = Gtk.Button(label="Modifier…", valign=Gtk.Align.CENTER)
        self.configure_button.connect("clicked", self._configure_shortcut)
        self.trigger_row.add_suffix(self.configure_button)
        shortcut.add(self.trigger_row)
        self.cancel_row = Adw.ActionRow(title="Annuler (texte gardé, non collé)", subtitle="non défini", use_markup=False)
        shortcut.add(self.cancel_row)
        self.preferred_row = Adw.EntryRow(title="Raccourci proposé au bureau (ex. CTRL+ALT+d, LOGO+h)",
                                          show_apply_button=True)
        self.preferred_row.set_text(self.controller.settings.preferred_trigger)
        self.preferred_row.connect("apply", self._apply_preferred)
        shortcut.add(self.preferred_row)
        command = Adw.ActionRow(title="Commande de secours", subtitle="localdictate --toggle")
        command.set_subtitle_selectable(True)
        copy = Gtk.Button(icon_name="edit-copy-symbolic", valign=Gtk.Align.CENTER, tooltip_text="Copier la commande")
        copy.connect("clicked", lambda *_: self._copy_text("localdictate --toggle"))
        command.add_suffix(copy)
        shortcut.add(command)
        self.shortcut_help = Gtk.Label(wrap=True, xalign=0, css_classes=["dim-label"], margin_top=6)
        self.shortcut_help.set_text(
            "Si le bureau ne propose pas de raccourci global, ajoutez un raccourci personnalisé "
            "(GNOME : Paramètres › Clavier › Raccourcis personnalisés ; KDE : Configuration › Raccourcis) "
            "qui lance la commande ci-dessus.")
        shortcut.add(self.shortcut_help)
        page.add(shortcut)

        model = Adw.PreferencesGroup(
            title="Modèle de reconnaissance",
            description="Parakeet TDT 0.6B v3 (NVIDIA, CC BY 4.0) et Silero VAD (MIT), exécutés sur cet ordinateur. "
                        "Seule la préparation utilise le réseau : environ 670 Mo téléchargés une seule fois.")
        self.model_row = Adw.ActionRow(title="Modèle", subtitle="", use_markup=False)
        self.model_row.set_subtitle_lines(3)
        self.prepare_button = Gtk.Button(label="Préparer", valign=Gtk.Align.CENTER)
        self.prepare_button.connect("clicked", self._prepare_clicked)
        self.model_row.add_suffix(self.prepare_button)
        model.add(self.model_row)
        self.progress = Gtk.ProgressBar(show_text=True, visible=False, margin_top=6)
        model.add(self.progress)
        page.add(model)
        return page

    def _on_snapshot(self, snapshot: Snapshot) -> None:
        state = snapshot.state
        title = state.value
        if state == State.RECORDING:
            title = f"Dictée en cours — {snapshot.seconds // 60}:{snapshot.seconds % 60:02d} (limite 15 min)"
        self.state_row.set_title(title)
        self.state_row.set_subtitle(snapshot.message or "")
        self.level_bar.set_value(min(1.0, snapshot.level) if state == State.RECORDING else 0.0)
        if snapshot.diagnostic:
            self.diagnostic_row.set_subtitle(snapshot.diagnostic)
        recording = state == State.RECORDING
        self.toggle_button.set_label("Arrêter et coller" if recording else "Démarrer")
        self.toggle_button.set_sensitive(state in (State.READY, State.LOADING, State.RECORDING))
        if recording:
            self.toggle_button.remove_css_class("suggested-action")
            self.toggle_button.add_css_class("destructive-action")
        else:
            self.toggle_button.remove_css_class("destructive-action")
            self.toggle_button.add_css_class("suggested-action")
        ready = self.controller.store.ready()
        preparing = state == State.PREPARING
        self.model_row.set_subtitle("Installé et vérifié." if ready else
                                    "Non préparé : la dictée est indisponible." if not preparing else "Préparation…")
        self.prepare_button.set_label("Annuler" if preparing else "Préparer")
        self.prepare_button.set_visible(preparing or not ready)
        progress = snapshot.progress
        self.progress.set_visible(progress is not None)
        if progress is not None:
            self.progress.set_text(progress.label)
            if progress.fraction is None:
                self.progress.pulse()
            else:
                self.progress.set_fraction(progress.fraction)

    def _prepare_clicked(self, *_):
        if self.controller.snapshot.state == State.PREPARING:
            self.controller.cancel_preparation()
        else:
            self.controller.prepare_models()

    def _on_shortcut_status(self, message: str, triggers: dict) -> None:
        toggle = triggers.get("toggle")
        self.trigger_row.set_subtitle(f"{toggle} — {message}" if toggle else (message or "—"))
        self.cancel_row.set_subtitle(triggers.get("cancel") or "non défini (à choisir dans le bureau)")
        shortcuts = self.app.shortcuts
        self.configure_button.set_visible(bool(shortcuts and shortcuts.session))

    def _configure_shortcut(self, *_):
        shortcuts = self.app.shortcuts
        if shortcuts is None:
            return
        if not shortcuts.configure():
            shortcuts.restart(self.app.shortcut_definitions())
            self._toast("Le bureau va proposer d’enregistrer le raccourci.")

    def _apply_preferred(self, row):
        value = row.get_text().strip()
        if not value:
            return
        self.controller.update_settings(preferred_trigger=value)
        if self.app.shortcuts is not None:
            self.app.shortcuts.restart(self.app.shortcut_definitions())
        self._toast("Raccourci proposé au bureau ; confirmez-le dans la fenêtre du système si elle s’ouvre.")

    # Settings page ---------------------------------------------------------------------------------------------
    def _settings_page(self) -> Gtk.Widget:
        settings = self.controller.settings
        page = Adw.PreferencesPage()

        mic = Adw.PreferencesGroup(title="Microphone",
                                   description="Réglez la sensibilité en parlant normalement : la barre doit monter "
                                               "nettement sans rester collée à droite.")
        self.device_row = Adw.ComboRow(title="Périphérique d’entrée")
        refresh = Gtk.Button(icon_name="view-refresh-symbolic", valign=Gtk.Align.CENTER, tooltip_text="Actualiser")
        refresh.connect("clicked", lambda *_: self._load_devices())
        self.device_row.add_suffix(refresh)
        self._load_devices()
        self.device_row.connect("notify::selected", self._device_changed)
        mic.add(self.device_row)

        self.gain_row = Adw.ActionRow(title="Sensibilité du micro (gain)", subtitle="")
        self.gain_scale = Gtk.Scale.new_with_range(Gtk.Orientation.HORIZONTAL, -12, 30, 1)
        self.gain_scale.set_hexpand(True)
        self.gain_scale.set_size_request(220, -1)
        self.gain_scale.set_value(settings.gain_db)
        self.gain_scale.add_mark(0, Gtk.PositionType.BOTTOM, None)
        self.gain_scale.connect("value-changed", self._gain_changed)
        self.gain_row.add_suffix(self.gain_scale)
        mic.add(self.gain_row)
        self._gain_changed(self.gain_scale, save=False)

        self.auto_gain_row = Adw.SwitchRow(title="Gain automatique pour la voix douce",
                                           subtitle="Remonte la parole faible jusqu’à +18 dB, sans saturer.")
        self.auto_gain_row.set_active(settings.auto_gain)
        self.auto_gain_row.connect("notify::active", self._auto_gain_changed)
        mic.add(self.auto_gain_row)

        self.vad_row = Adw.ActionRow(title="Détection de la voix", subtitle="")
        self.vad_scale = Gtk.Scale.new_with_range(Gtk.Orientation.HORIZONTAL, 0.1, 0.9, 0.05)
        self.vad_scale.set_inverted(True)  # Right = more sensitive = lower threshold.
        self.vad_scale.set_hexpand(True)
        self.vad_scale.set_size_request(220, -1)
        self.vad_scale.set_draw_value(False)
        self.vad_scale.set_value(settings.vad_threshold)
        self.vad_scale.add_mark(0.5, Gtk.PositionType.BOTTOM, None)
        self.vad_scale.connect("value-changed", self._vad_changed)
        self.vad_row.add_suffix(self.vad_scale)
        mic.add(self.vad_row)
        self._vad_changed(self.vad_scale, save=False)

        test_row = Adw.ActionRow(title="Tester le micro", subtitle="Niveau après réglages ; rien n’est enregistré.")
        self.test_level = Gtk.LevelBar(min_value=0, max_value=1, hexpand=True, valign=Gtk.Align.CENTER)
        self.test_level.set_size_request(140, -1)
        self.test_button = Gtk.ToggleButton(label="Tester", valign=Gtk.Align.CENTER)
        self.test_button.connect("toggled", self._test_toggled)
        test_row.add_suffix(self.test_level)
        test_row.add_suffix(self.test_button)
        mic.add(test_row)
        page.add(mic)

        paste = Adw.PreferencesGroup(
            title="Collage",
            description="À l’arrêt, le texte est copié dans le presse-papiers puis collé dans la fenêtre active. "
                        "S’il y a une sélection, elle est remplacée, comme avec un collage manuel. "
                        "Le texte reste toujours dans l’historique.")
        self.auto_paste_row = Adw.SwitchRow(title="Coller automatiquement")
        self.auto_paste_row.set_active(settings.auto_paste)
        self.auto_paste_row.connect("notify::active", lambda row, _p: self.controller.update_settings(auto_paste=row.get_active()))
        paste.add(self.auto_paste_row)
        self.keys_row = Adw.ComboRow(title="Touches de collage", model=Gtk.StringList.new(list(PASTE_KEYS.values())))
        self.keys_row.set_selected(list(PASTE_KEYS).index(settings.paste_keys))
        self.keys_row.connect("notify::selected", lambda row, _p: self.controller.update_settings(
            paste_keys=list(PASTE_KEYS)[row.get_selected()]))
        paste.add(self.keys_row)
        self.permission_row = Adw.ActionRow(
            use_markup=False, title="Autorisation du bureau",
            subtitle="Le bureau demande une fois l’accès au clavier virtuel et au presse-papiers.")
        self.permission_row.set_subtitle_lines(3)
        allow = Gtk.Button(label="Autoriser", valign=Gtk.Align.CENTER)
        allow.connect("clicked", self._allow_paste)
        self.permission_row.add_suffix(allow)
        paste.add(self.permission_row)
        page.add(paste)

        storage = Adw.PreferencesGroup(title="Historique",
                                       description="Chaque dictée garde son enregistrement (WAV, environ 1,9 Mo par "
                                                   "minute) et son texte, uniquement sur cet ordinateur.")
        self.limit_row = Adw.SpinRow.new_with_range(1, 1000, 1)
        self.limit_row.set_title("Dictées gardées")
        self.limit_row.set_value(settings.history_limit)
        self.limit_row.connect("notify::value", lambda row, _p: self.controller.update_settings(
            history_limit=int(row.get_value())))
        storage.add(self.limit_row)
        folder_row = Adw.ActionRow(title="Dossier des dictées", subtitle=str(self.controller.history.root),
                                   use_markup=False)
        folder_row.set_subtitle_selectable(True)
        open_folder = Gtk.Button(label="Ouvrir", valign=Gtk.Align.CENTER)
        open_folder.connect("clicked", lambda *_: self.app.open_folder())
        folder_row.add_suffix(open_folder)
        storage.add(folder_row)
        self.usage_row = Adw.ActionRow(title="Espace utilisé", subtitle="")
        clear = Gtk.Button(label="Tout effacer", valign=Gtk.Align.CENTER, css_classes=["destructive-action"])
        clear.connect("clicked", self._clear_history)
        self.usage_row.add_suffix(clear)
        storage.add(self.usage_row)
        page.add(storage)

        during = Adw.PreferencesGroup(title="Pendant la dictée")
        self.media_row = Adw.SwitchRow(
            title="Mettre la musique en pause",
            subtitle="Lecteurs compatibles MPRIS (Spotify, Rhythmbox, VLC, navigateurs…) ; reprise à l’arrêt du micro.")
        self.media_row.set_active(settings.pause_media)
        self.media_row.connect("notify::active", lambda row, _p: self.controller.update_settings(pause_media=row.get_active()))
        during.add(self.media_row)
        self.tray_row = Adw.SwitchRow(title="Icône de statut", subtitle="")
        self.tray_row.set_use_markup(False)
        self.tray_row.set_active(settings.show_tray)
        self.tray_row.connect("notify::active", self._tray_toggled)
        during.add(self.tray_row)
        page.add(during)

        start = Adw.PreferencesGroup(title="Démarrage")
        self.autostart_row = Adw.SwitchRow(title="Lancer à l’ouverture de session",
                                           subtitle="En arrière-plan, prêt pour le raccourci (modèle chargé en mémoire).")
        self.autostart_row.set_active(autostart_path().exists())
        self.autostart_row.connect("notify::active", lambda row, _p: set_autostart(row.get_active()))
        start.add(self.autostart_row)
        page.add(start)
        return page

    def _tray_toggled(self, row, _param) -> None:
        self.controller.update_settings(show_tray=row.get_active())
        self.app.set_tray(row.get_active())
        self._on_tray_availability(bool(self.app.tray and self.app.tray.available))

    def _on_tray_availability(self, available: bool) -> None:
        if not hasattr(self, "tray_row"):
            return
        if not self.tray_row.get_active():
            text = "Masquée."
        elif available:
            text = "Affichée dans la barre : au repos (bleu), écoute (rouge), transcription (orange)."
        else:
            text = ("Aucune zone de notification détectée. Sur GNOME, activez l’extension « AppIndicator and "
                    "KStatusNotifierItem Support » (Fedora : gnome-shell-extension-appindicator).")
        self.tray_row.set_subtitle(text)

    def _load_devices(self) -> None:
        from .capture import list_input_devices

        try:
            self.devices = list_input_devices()
        except Exception:  # noqa: BLE001
            self.devices = []
        labels = ["Micro par défaut du système"] + [device.label for device in self.devices]
        selected = 0
        current = self.controller.settings.input_device
        for index, device in enumerate(self.devices, start=1):
            if device.key == current:
                selected = index
        if current and not selected:
            labels.append(f"{self.controller.settings.input_device_label or current} (absent)")
            selected = len(labels) - 1
        self._loading_devices = True
        self.device_row.set_model(Gtk.StringList.new(labels))
        self.device_row.set_selected(selected)
        self._loading_devices = False

    def _device_changed(self, row, _param) -> None:
        if self._loading_devices:
            return
        index = row.get_selected()
        if index == 0:
            self.controller.update_settings(input_device=None, input_device_label=None)
        elif index - 1 < len(self.devices):
            device = self.devices[index - 1]
            self.controller.update_settings(input_device=device.key, input_device_label=device.label)
        self._restart_test()

    def _gain_changed(self, scale, save: bool = True) -> None:
        value = round(scale.get_value())
        self.gain_row.set_subtitle(f"{value:+d} dB" + (" (micro tel quel)" if value == 0 else ""))
        if save:
            self.controller.update_settings(gain_db=float(value))
            self._restart_test()

    def _auto_gain_changed(self, row, _param) -> None:
        self.controller.update_settings(auto_gain=row.get_active())
        self._restart_test()

    def _vad_changed(self, scale, save: bool = True) -> None:
        value = round(scale.get_value(), 2)
        hint = "plus sensible : voix douce, plus de bruit capté" if value < 0.45 else \
            "moins sensible : ignore mieux le bruit" if value > 0.55 else "normale"
        self.vad_row.set_subtitle(f"Seuil {value:.2f} — {hint}".replace(".", ","))
        if save:
            self.controller.update_settings(vad_threshold=value)

    def _test_toggled(self, button) -> None:
        if button.get_active():
            error = self.controller.start_level_test(self._test_level)
            if error:
                button.set_active(False)
                self._toast(error)
        else:
            self.controller.stop_level_test()
            self.test_level.set_value(0)

    def _test_level(self, level: float) -> None:
        if level < 0:
            self.test_button.set_active(False)
            self._toast("Le micro s’est arrêté.")
            return
        self.test_level.set_value(min(1.0, level))

    def _restart_test(self) -> None:
        if self.test_button.get_active():
            self.controller.stop_level_test()
            error = self.controller.start_level_test(self._test_level)
            if error:
                self.test_button.set_active(False)
                self._toast(error)

    def _allow_paste(self, *_):
        session = self.app.paste_session
        if session is None:
            self._toast("Pas de portail disponible.")
            return

        def done(ok: bool, message: str | None) -> None:
            if ok:
                parts = ["clavier" if session.keyboard else "clavier refusé",
                         "presse-papiers" if session.clipboard else "presse-papiers non fourni par le bureau"]
                self.permission_row.set_subtitle("Autorisé : " + ", ".join(parts) + ".")
            else:
                self.permission_row.set_subtitle(message or "Refusé.")

        session.ensure(done)

    def _clear_history(self, *_):
        dialog = Adw.MessageDialog(transient_for=self, heading="Effacer tout l’historique ?",
                                   body="Les enregistrements et les textes seront supprimés définitivement.")
        dialog.add_response("cancel", "Annuler")
        dialog.add_response("clear", "Tout effacer")
        dialog.set_response_appearance("clear", Adw.ResponseAppearance.DESTRUCTIVE)

        def response(_dialog, answer):
            if answer == "clear":
                self._stop_playback()
                busy = {e.id for e in self.controller.history.entries() if e.meta.state in ("recording", "transcribing")}
                for entry in self.controller.history.entries():
                    if entry.id not in busy:
                        self.controller.history.delete(entry.id)
                self._refresh_history()

        dialog.connect("response", response)
        dialog.present()

    # History page ----------------------------------------------------------------------------------------------
    def _refresh_history(self) -> None:
        history = self.controller.history
        entries = history.entries()
        self.usage_row.set_subtitle(f"{len(entries)} dictée(s), {size_label(history.disk_usage())}")
        box = Gtk.Box(orientation=Gtk.Orientation.VERTICAL, spacing=12, margin_top=12, margin_bottom=12,
                      margin_start=12, margin_end=12)
        if not entries:
            box.append(Adw.StatusPage(icon_name="audio-input-microphone-symbolic", title="Aucune dictée",
                                      description="Les dictées et leurs enregistrements apparaîtront ici."))
        else:
            group = Adw.PreferencesGroup()
            for entry in entries:
                group.add(self._entry_row(entry))
            box.append(group)
        clamp = Adw.Clamp(child=box, maximum_size=720)
        self.history_page.set_child(clamp)

    def _entry_row(self, entry: Entry) -> Gtk.Widget:
        meta = entry.meta
        text = entry.text()
        when = time.strftime("%d/%m/%Y %H:%M", time.localtime(meta.created))
        status = STATE_LABELS.get(meta.state, meta.state)
        if meta.state == "done":
            status = INSERTION_LABELS.get(meta.insertion, status)
        row = Adw.ExpanderRow(title=f"{when} · {duration_label(meta.duration_ms)} · {status}",
                              subtitle=(text[:120] + ("…" if len(text) > 120 else "")) or "(aucun texte)", use_markup=False)
        body = Gtk.Box(orientation=Gtk.Orientation.VERTICAL, spacing=8, margin_top=8, margin_bottom=8,
                       margin_start=12, margin_end=12)
        label = Gtk.Label(label=text or "(aucun texte reconnu)", wrap=True, xalign=0, selectable=True)
        body.append(label)
        if meta.message:
            body.append(Gtk.Label(label=meta.message, wrap=True, xalign=0, css_classes=["dim-label"]))
        buttons = Gtk.Box(spacing=6)
        busy = meta.state in ("recording", "transcribing")
        if entry.has_audio():
            play = Gtk.Button(label="Arrêter" if self.playing_id == entry.id else "Écouter")
            play.connect("clicked", lambda *_: self._toggle_playback(entry))
            play.set_sensitive(meta.state != "recording")
            buttons.append(play)
        folder = Gtk.Button(icon_name="folder-open-symbolic", tooltip_text="Ouvrir le dossier (son et texte)")
        folder.connect("clicked", lambda *_: self._open_uri(entry.directory.as_uri()))
        buttons.append(folder)
        copy = Gtk.Button(label="Copier")
        copy.set_sensitive(bool(text))
        copy.connect("clicked", lambda *_: self._copy_text(text))
        buttons.append(copy)
        if entry.has_audio():
            again = Gtk.Button(label="Retranscrire", tooltip_text="Avec les réglages actuels du micro")
            again.set_sensitive(not busy and not self.controller.busy and self.controller.engine is not None)
            again.connect("clicked", lambda *_: self._retranscribe(entry))
            buttons.append(again)
        delete = Gtk.Button(label="Supprimer", css_classes=["destructive-action"])
        delete.set_sensitive(not busy)
        delete.connect("clicked", lambda *_: self._delete(entry))
        buttons.append(delete)
        body.append(buttons)
        row.add_row(body)
        return row

    def _retranscribe(self, entry: Entry) -> None:
        def done(error):
            self._toast(error or "Retranscription terminée.")

        if not self.controller.retranscribe(entry.id, done):
            self._toast("Retranscription impossible maintenant.")

    def _delete(self, entry: Entry) -> None:
        if self.playing_id == entry.id:
            self._stop_playback()
        self.controller.history.delete(entry.id)
        self._refresh_history()

    def _toggle_playback(self, entry: Entry) -> None:
        if self.playing_id == entry.id:
            self._stop_playback()
            self._refresh_history()
            return
        self._stop_playback()
        from gi.repository import Gst

        player = Gst.ElementFactory.make("playbin", None)
        if player is None:
            self._toast("Lecture impossible : élément GStreamer playbin absent.")
            return
        player.set_property("uri", entry.audio_path.as_uri())
        bus = player.get_bus()
        bus.add_signal_watch()
        bus.connect("message::eos", lambda *_: (self._stop_playback(), self._refresh_history()))
        bus.connect("message::error", lambda *_: (self._stop_playback(), self._refresh_history()))
        player.set_state(Gst.State.PLAYING)
        self.player, self.playing_id = player, entry.id
        self._refresh_history()

    def _stop_playback(self) -> None:
        player, self.player, self.playing_id = self.player, None, None
        if player is not None:
            from gi.repository import Gst

            player.set_state(Gst.State.NULL)
            player.get_bus().remove_signal_watch()

    def _open_uri(self, uri: str) -> None:
        Gtk.FileLauncher.new(Gio.File.new_for_uri(uri)).launch(self, None, None, None)

    # Helpers ---------------------------------------------------------------------------------------------------
    def _copy_text(self, text: str) -> None:
        Gdk.Display.get_default().get_clipboard().set(text)
        self._toast("Copié dans le presse-papiers.")

    def _toast(self, message: str) -> None:
        self.toasts.add_toast(Adw.Toast(title=GLib.markup_escape_text(message), timeout=4))

    def _on_close(self, *_):
        self.test_button.set_active(False)
        self._stop_playback()
        return False


def Gio_menu(items):
    from gi.repository import Gio

    menu = Gio.Menu()
    for label, action in items:
        menu.append(label, action)
    return menu
