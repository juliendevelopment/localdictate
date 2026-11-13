import tempfile
import time
import unittest
from pathlib import Path

from localdictate.controller import Controller, State
from localdictate.history import History
from localdictate.settings import Settings
from tests.fakes import CountingEngine, FakeCapture, FakePaster, Loop


class FakeMedia:
    def __init__(self):
        self.calls = []

    def pause(self):
        self.calls.append("pause")

    def resume(self):
        self.calls.append("resume")


class ReadyStore:
    def __init__(self, ready=True):
        self._ready = ready

    def ready(self):
        return self._ready

    def files(self):
        return None


class ControllerTest(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.history = History(Path(self.directory.name) / "history")
        self.loop = Loop()
        self.paster = FakePaster()
        self.notifications = []
        self.capture_options = {}
        self.media = FakeMedia()

    def tearDown(self):
        self.directory.cleanup()

    def controller(self, ready=True, engine=None, settings=None):
        engine = engine or CountingEngine()
        controller = Controller(
            settings or Settings(), self.history, ReadyStore(ready),
            capture_factory=lambda on_block, on_error: FakeCapture(on_block, on_error, **self.capture_options),
            engine_loader=lambda files: engine, paster=self.paster,
            notify=lambda title, body: self.notifications.append(title),
            dispatch=self.loop.dispatch, timer=self.loop.timer, save=lambda settings: None, media=self.media)
        controller.startup()
        return controller

    def test_press_dictate_press_pastes_and_keeps_recording(self):
        controller = self.controller()
        self.assertTrue(self.loop.pump(lambda: controller.snapshot.state == State.READY))
        controller.toggle()
        self.assertEqual(controller.snapshot.state, State.RECORDING)
        session = controller._session
        session["capture"].thread.join(5)
        controller.toggle()
        self.assertTrue(self.loop.pump(lambda: controller.snapshot.state == State.READY))
        self.assertEqual(self.paster.calls, [("mot1 mot2 mot3 mot4 mot5 mot6 mot7 mot8 mot9 mot10", True, "ctrl-v")])
        [entry] = self.history.entries()
        self.assertEqual((entry.meta.state, entry.meta.insertion), ("done", "pasted"))
        self.assertEqual(entry.text(), self.paster.calls[0][0])
        self.assertEqual(entry.meta.duration_ms, 1000)
        self.assertTrue(entry.has_audio())
        self.assertIn("Diagnostic", controller.snapshot.diagnostic)
        self.assertEqual(self.media.calls, ["pause", "resume"])

    def test_media_not_paused_when_disabled_and_resumed_after_failure(self):
        controller = self.controller(settings=Settings(pause_media=False))
        self.loop.pump(lambda: controller.snapshot.state == State.READY)
        controller.toggle()
        controller._session["capture"].thread.join(5)
        controller.cancel()
        self.loop.pump(lambda: controller.snapshot.state == State.READY)
        self.assertEqual(self.media.calls, ["resume"])
        self.media.calls.clear()
        self.capture_options = {"fail_start": True}
        controller.settings.pause_media = True
        controller.toggle()
        self.loop.pump(lambda: controller.snapshot.state == State.READY)
        self.assertEqual(self.media.calls, ["resume"])  # Never paused: the microphone did not open.

    def test_cancel_keeps_text_without_pasting(self):
        controller = self.controller()
        self.loop.pump(lambda: controller.snapshot.state == State.READY)
        controller.toggle()
        controller._session["capture"].thread.join(5)
        controller.cancel()
        self.assertTrue(self.loop.pump(lambda: controller.snapshot.state == State.READY))
        self.assertEqual(self.paster.calls, [])
        [entry] = self.history.entries()
        self.assertEqual(entry.meta.state, "cancelled")
        self.assertTrue(entry.text())

    def test_recording_starts_while_model_loads(self):
        controller = self.controller()
        self.assertEqual(controller.snapshot.state, State.LOADING)
        controller.toggle()
        self.assertEqual(controller.snapshot.state, State.RECORDING)
        controller._session["capture"].thread.join(5)
        controller.toggle()
        self.assertTrue(self.loop.pump(lambda: controller.snapshot.state == State.READY))
        self.assertEqual(len(self.paster.calls), 1)

    def test_no_model_does_not_open_microphone(self):
        controller = self.controller(ready=False)
        controller.toggle()
        self.assertEqual(controller.snapshot.state, State.NO_MODEL)
        self.assertEqual(self.history.entries(), [])
        self.assertIn("Modèle non préparé", self.notifications)

    def test_microphone_failure_reported_and_nothing_pasted(self):
        self.capture_options = {"fail_start": True}
        controller = self.controller()
        self.loop.pump(lambda: controller.snapshot.state == State.READY)
        controller.toggle()
        self.assertTrue(self.loop.pump(lambda: controller.snapshot.state == State.READY))
        self.assertIn("Micro inaccessible", controller.snapshot.message)
        self.assertEqual(self.paster.calls, [])
        self.assertEqual(self.history.entries()[0].meta.state, "failed")

    def test_recognition_failure_copies_partial_text_without_pasting(self):
        controller = self.controller(engine=CountingEngine(fail_at=4))
        self.loop.pump(lambda: controller.snapshot.state == State.READY)
        controller.toggle()
        controller._session["capture"].thread.join(5)
        self.assertTrue(self.loop.pump(lambda: controller.snapshot.state == State.READY))
        self.assertEqual(self.paster.calls, [("mot1 mot2 mot3", False, "ctrl-v")])
        self.assertEqual(self.history.entries()[0].meta.state, "failed")

    def test_silent_microphone_watchdog(self):
        self.capture_options = {"blocks": 0}
        controller = self.controller()
        self.loop.pump(lambda: controller.snapshot.state == State.READY)
        controller.toggle()
        controller._session["pipeline"].last_audio = time.monotonic() - 10
        tick = self.loop.timers[-1]
        self.assertFalse(tick())
        self.assertTrue(self.loop.pump(lambda: controller.snapshot.state == State.READY))
        self.assertIn("Aucun son", self.history.entries()[0].meta.message)

    def test_retranscribe_uses_saved_recording(self):
        controller = self.controller()
        self.loop.pump(lambda: controller.snapshot.state == State.READY)
        controller.toggle()
        controller._session["capture"].thread.join(5)
        controller.cancel()
        self.loop.pump(lambda: controller.snapshot.state == State.READY)
        [entry] = self.history.entries()
        results = []
        self.assertTrue(controller.retranscribe(entry.id, results.append))
        self.assertFalse(controller.retranscribe(entry.id))  # Busy.
        self.assertTrue(self.loop.pump(lambda: results))
        self.assertEqual(results, [None])
        self.assertEqual(self.history.get(entry.id).text(), "mot11 mot12 mot13 mot14 mot15 mot16 mot17 mot18 mot19 mot20")

    def test_history_is_pruned(self):
        controller = self.controller(settings=Settings(history_limit=2))
        self.loop.pump(lambda: controller.snapshot.state == State.READY)
        for _ in range(3):
            controller.toggle()
            controller._session["capture"].thread.join(5)
            controller.toggle()
            self.assertTrue(self.loop.pump(lambda: controller.snapshot.state == State.READY))
        self.assertEqual(len(self.history.entries()), 2)


if __name__ == "__main__":
    unittest.main()
