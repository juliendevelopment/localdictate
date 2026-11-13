"""Dictation controller: one press starts, the next press stops and pastes. Owns the engine and the history.

Threading: public methods run on the main loop. Capture blocks arrive on GStreamer's thread, recognition runs
on pipeline threads, and results come back through ``dispatch`` (GLib.idle_add in the app).
"""
from __future__ import annotations

import enum
import threading
import time
from dataclasses import dataclass
from typing import Callable

import numpy as np

from .dsp import RATE, InputChain
from .history import Entry, History
from .models import Cancelled, ModelStore, Progress, prepare
from .pipeline import DictationPipeline, Diagnostic, join_texts
from .settings import Settings, save_settings
from .wav import read_wav

NO_AUDIO_SECONDS = 5.0


class State(enum.Enum):
    NO_MODEL = "Modèle non préparé"
    PREPARING = "Préparation du modèle…"
    LOADING = "Chargement du modèle…"
    READY = "Prêt"
    RECORDING = "Dictée en cours"
    PROCESSING = "Transcription…"
    ERROR = "Erreur"


@dataclass
class Snapshot:
    state: State
    seconds: int = 0
    level: float = 0.0
    message: str | None = None
    diagnostic: str | None = None
    progress: Progress | None = None


class Controller:
    def __init__(self, settings: Settings, history: History, store: ModelStore, *,
                 capture_factory: Callable, engine_loader: Callable, paster,
                 notify: Callable[[str, str | None], None] = lambda title, body: None,
                 dispatch: Callable[..., object] | None = None,
                 timer: Callable[[float, Callable[[], bool]], object] | None = None,
                 save: Callable[[Settings], None] = save_settings, media=None):
        if dispatch is None or timer is None:
            from gi.repository import GLib  # Only for the defaults; tests inject their own loop.

            dispatch = dispatch or (lambda fn, *args: GLib.idle_add(lambda: (fn(*args), False)[1]))
            timer = timer or (lambda seconds, fn: GLib.timeout_add(int(seconds * 1000), fn))
        self.settings = settings
        self.history = history
        self.store = store
        self.capture_factory = capture_factory
        self.engine_loader = engine_loader
        self.paster = paster
        self.notify = notify
        self.dispatch = dispatch
        self.timer = timer
        self.save = save
        self.media = media  # MediaPauser or None.
        self.listeners: list[Callable[[Snapshot], None]] = []
        self.history_listeners: list[Callable[[], None]] = []
        self.snapshot = Snapshot(State.NO_MODEL)
        self.engine = None
        self.engine_ready = threading.Event()
        self.engine_lock = threading.Lock()  # Serializes native decoding (dictation vs re-transcription).
        self.busy = False  # Dictation, re-transcription or preparation.
        self._cancel_prepare: threading.Event | None = None
        self._session: dict | None = None
        self._test_capture = None

    # State -----------------------------------------------------------------------------------------------------
    def _publish(self, **changes) -> None:
        for key, value in changes.items():
            setattr(self.snapshot, key, value)
        for listener in list(self.listeners):
            listener(self.snapshot)

    def _history_changed(self) -> None:
        for listener in list(self.history_listeners):
            listener()

    def update_settings(self, **changes) -> None:
        for key, value in changes.items():
            setattr(self.settings, key, value)
        self.settings = self.settings.sanitized()
        self.save(self.settings)

    def startup(self) -> None:
        recovered = self.history.recover()
        if recovered:
            self.notify("Dictée interrompue récupérée",
                        "Le son et le texte déjà reconnu sont dans l’historique ; vous pouvez la retranscrire.")
            self._history_changed()
        if self.store.ready():
            self.load_engine()
        else:
            self._publish(state=State.NO_MODEL)

    def load_engine(self) -> None:
        self.engine_ready.clear()
        self._publish(state=State.LOADING, message=None)

        def work() -> None:
            try:
                engine = self.engine_loader(self.store.files())
            except Exception as error:  # noqa: BLE001
                self.engine = None
                self.engine_ready.set()
                self.dispatch(self._publish, state=State.ERROR,
                              message=f"Chargement du modèle impossible : {error}")
                return
            self.engine = engine
            self.engine_ready.set()
            self.dispatch(self._loaded)

        threading.Thread(target=work, name="ld-load", daemon=True).start()

    def _loaded(self) -> None:
        if self.snapshot.state == State.LOADING:
            self._publish(state=State.READY)

    # Shortcut actions ------------------------------------------------------------------------------------------
    def toggle(self) -> None:
        state = self.snapshot.state
        if state == State.RECORDING:
            self.stop(paste=True)
        elif state == State.PROCESSING:
            self.notify("Transcription en cours", "Le texte sera collé dès qu’il sera prêt.")
        elif state in (State.READY, State.LOADING) and not self.busy:
            self.start()
        elif state == State.NO_MODEL:
            self.notify("Modèle non préparé", "Ouvrez LocalDictate et préparez le modèle de reconnaissance.")
        elif self.busy:
            self.notify("LocalDictate est occupé", "Attendez la fin de l’opération en cours.")
        else:
            self.notify("Dictée indisponible", self.snapshot.message)

    def cancel(self) -> None:
        if self.snapshot.state == State.RECORDING:
            self.stop(paste=False)

    def start(self) -> None:
        if self.busy or self._session is not None:
            return
        self.stop_level_test()
        press = time.monotonic()
        settings = self.settings
        try:
            entry = self.history.create(settings.input_device_label)
            recorder = entry.open_audio()
        except OSError as error:
            self._publish(message=f"Dictée impossible : historique inaccessible ({error.strerror or error}).")
            self.notify("Dictée impossible", "Historique inaccessible (disque plein ?).")
            return
        session: dict = {"entry": entry, "press": press, "paste": True, "stopping": False, "limit": False}
        diagnostic = Diagnostic(press)

        def on_level(level: float) -> None:
            session["level"] = level

        def on_text(_text: str) -> None:
            entry.set_text(join_texts(pipeline.texts))

        def on_error(message: str) -> None:
            self.dispatch(self._pipeline_error, session, message)

        def on_capture_error(message: str) -> None:
            pipeline.fail(message)

        capture = self.capture_factory(lambda samples, origin: pipeline.feed(samples, origin), on_capture_error)
        pipeline = DictationPipeline(
            self._segmenter, self._recognize,
            chain=InputChain(settings.gain_db, settings.auto_gain, settings.max_auto_gain_db),
            stop_capture=capture.stop, on_text=on_text, on_error=on_error, on_level=on_level,
            recorder=recorder, diagnostic=diagnostic)
        session.update(capture=capture, pipeline=pipeline, diagnostic=diagnostic)
        self._session = session
        self.busy = True
        pipeline.start()
        try:
            capture.start(settings.input_device)  # Microphone opened right after the press.
        except Exception as error:  # noqa: BLE001
            pipeline.fail(f"Micro inaccessible : {error}")
            self._finish_capture(session)
            return
        if settings.pause_media and self.media is not None:
            self.media.pause()  # After the microphone start: asynchronous, never delays the capture.
        warning = None
        if settings.input_device and not getattr(capture, "device_found", True):
            warning = f"Micro « {settings.input_device_label} » introuvable : micro par défaut utilisé."
        self._publish(state=State.RECORDING, seconds=0, level=0.0, message=warning, diagnostic=None)
        self.notify("Dictée en cours", "Appuyez de nouveau sur le raccourci pour arrêter et coller.")
        self.timer(0.2, lambda: self._tick(session))

    def _tick(self, session: dict) -> bool:
        if self._session is not session or session["stopping"]:
            return False
        pipeline: DictationPipeline = session["pipeline"]
        if pipeline.exhausted:
            session["limit"] = True
            self.stop(paste=True)
            return False
        if pipeline.failure is not None:
            self._finish_capture(session)
            return False
        if time.monotonic() - pipeline.last_audio > NO_AUDIO_SECONDS:
            pipeline.fail("Aucun son reçu du micro depuis 5 s : vérifiez le micro choisi.")
            self._finish_capture(session)
            return False
        self._publish(seconds=pipeline.captured // RATE, level=session.get("level", 0.0))
        return True

    def _pipeline_error(self, session: dict, message: str) -> None:
        if self._session is session and not session["stopping"]:
            self._finish_capture(session)

    def stop(self, paste: bool = True) -> None:
        session = self._session
        if session is None or session["stopping"]:
            return
        session["paste"] = paste
        self._finish_capture(session)

    def _finish_capture(self, session: dict) -> None:
        if session["stopping"]:
            return
        session["stopping"] = True
        session["capture"].stop()
        if self.media is not None:
            self.media.resume()  # Microphone closed: only players paused by us, and still paused, restart.
        pipeline: DictationPipeline = session["pipeline"]
        pipeline.stop_capture()
        entry: Entry = session["entry"]
        entry.update(state="transcribing", duration_ms=pipeline.captured * 1000 // RATE)
        self._publish(state=State.PROCESSING, level=0.0, seconds=pipeline.captured // RATE)

        def wait() -> None:
            pipeline.wait()
            self.dispatch(self._finalize, session)

        threading.Thread(target=wait, name="ld-finish", daemon=True).start()

    def _finalize(self, session: dict) -> None:
        pipeline: DictationPipeline = session["pipeline"]
        entry: Entry = session["entry"]
        text = pipeline.text
        try:
            entry.set_text(text)
        except OSError:
            pass  # Text still delivered to the clipboard below.
        failure = pipeline.failure
        if failure:
            state = "failed"
        elif not session["paste"]:
            state = "cancelled"
        else:
            state = "done"
        message = failure
        if session["limit"]:
            message = "Limite de 15 minutes atteinte : la dictée a été arrêtée."
        entry.update(state=state, duration_ms=pipeline.captured * 1000 // RATE, message=message)
        diagnostic = session["diagnostic"].line()
        self._session = None
        self.busy = False
        ready_state = State.READY if self.engine is not None else self.snapshot.state
        if ready_state == State.PROCESSING:
            ready_state = State.ERROR
        self.history.prune(self.settings.history_limit, keep={entry.id})
        self._history_changed()
        if not text:
            self._publish(state=ready_state, message=message or "Aucune parole reconnue.", diagnostic=diagnostic)
            self.notify("Aucun texte reconnu", message or "Parlez plus près du micro ou augmentez la sensibilité.")
            return
        # Never paste automatically a text that may be incomplete or that the user cancelled.
        auto = self.settings.auto_paste and session["paste"] and not failure

        def delivered(insertion: str, delivery_message: str) -> None:
            entry.update(insertion=insertion)
            final = delivery_message if not message else f"{message} {delivery_message}"
            if not session["paste"]:
                final = "Dictée annulée : texte gardé dans l’historique" + (" et copié." if insertion == "copied" else ".")
            self._publish(state=ready_state, message=final, diagnostic=diagnostic)
            self.notify("Dictée terminée" if insertion == "pasted" else "Texte prêt", final)
            self._history_changed()

        if not session["paste"]:
            delivered("none", "")
            return
        self.paster.deliver(text, auto, self.settings.paste_keys, delivered)

    # Engine access from pipeline threads ------------------------------------------------------------------------
    def _segmenter(self):
        if not self.engine_ready.wait(180):
            raise RuntimeError("model loading timed out")
        if self.engine is None:
            raise RuntimeError("model unavailable")
        return self.engine.segmenter(self.settings.vad_threshold)

    def _recognize(self, samples: np.ndarray) -> str:
        with self.engine_lock:
            return self.engine.recognize(samples)

    # History actions -------------------------------------------------------------------------------------------
    def retranscribe(self, entry_id: str, on_done: Callable[[str | None], None] = lambda error: None) -> bool:
        entry = self.history.get(entry_id)
        if entry is None or self.busy or self.engine is None or not entry.has_audio():
            return False
        self.busy = True
        settings = self.settings
        previous = entry.meta.state
        entry.update(state="transcribing")
        self._history_changed()

        def work() -> None:
            error = None
            try:
                samples = read_wav(str(entry.audio_path))
                chain = InputChain(settings.gain_db, settings.auto_gain, settings.max_auto_gain_db)
                segmenter = self.engine.segmenter(settings.vad_threshold)
                texts: list[str] = []
                step = RATE // 10
                for offset in range(0, samples.size, step):
                    for segment in segmenter.accept(chain.apply(samples[offset:offset + step])):
                        texts.append(self._recognize(segment).strip())
                for segment in segmenter.finish():
                    texts.append(self._recognize(segment).strip())
                entry.set_text(join_texts(texts))
                entry.update(state="done" if previous != "cancelled" else "cancelled",
                             duration_ms=samples.size * 1000 // RATE, message="Retranscrit avec les réglages actuels.")
            except Exception as exc:  # noqa: BLE001
                error = f"Retranscription impossible : {exc}"
                try:
                    entry.update(state="failed", message=error)
                except OSError:
                    pass
            self.dispatch(self._retranscribed, error, on_done)

        threading.Thread(target=work, name="ld-retranscribe", daemon=True).start()
        return True

    def _retranscribed(self, error: str | None, on_done) -> None:
        self.busy = False
        self._history_changed()
        on_done(error)

    # Model preparation -----------------------------------------------------------------------------------------
    def prepare_models(self) -> None:
        if self.busy:
            return
        self.busy = True
        cancel = threading.Event()
        self._cancel_prepare = cancel
        self._publish(state=State.PREPARING, progress=Progress("Démarrage…", None), message=None)

        def work() -> None:
            try:
                prepare(self.store, cancel, lambda progress: self.dispatch(self._publish, progress=progress))
                self.dispatch(self._prepared, None)
            except Cancelled:
                self.dispatch(self._prepared, "Préparation annulée ; le téléchargement reprendra où il s’est arrêté.")
            except Exception as error:  # noqa: BLE001
                self.dispatch(self._prepared, f"Préparation impossible : {error}")

        threading.Thread(target=work, name="ld-prepare", daemon=True).start()

    def cancel_preparation(self) -> None:
        if self._cancel_prepare is not None:
            self._cancel_prepare.set()

    def _prepared(self, error: str | None) -> None:
        self.busy = False
        self._cancel_prepare = None
        self._publish(progress=None)
        if error is None and self.store.ready():
            self.load_engine()
        else:
            self._publish(state=State.NO_MODEL, message=error)

    # Microphone test (level meter in the settings) ---------------------------------------------------------------
    def start_level_test(self, on_level: Callable[[float], None]) -> str | None:
        if self._session is not None or self._test_capture is not None:
            return "Micro déjà utilisé par une dictée."
        chain = InputChain(self.settings.gain_db, self.settings.auto_gain, self.settings.max_auto_gain_db)

        def on_block(samples: np.ndarray, _origin: float) -> None:
            processed = chain.apply(samples)
            level = float(np.max(np.abs(processed))) if processed.size else 0.0
            self.dispatch(on_level, level)

        capture = self.capture_factory(on_block, lambda message: self.dispatch(on_level, -1.0))
        try:
            capture.start(self.settings.input_device)
        except Exception as error:  # noqa: BLE001
            return f"Micro inaccessible : {error}"
        self._test_capture = capture
        return None

    def stop_level_test(self) -> None:
        capture, self._test_capture = self._test_capture, None
        if capture is not None:
            capture.stop()
