import json
import os
import stat
import tempfile
import unittest
from pathlib import Path

import numpy as np

from localdictate.history import History
from localdictate.settings import Settings, load_settings, save_settings
from localdictate.wav import WavWriter, read_wav, repair_header


class WavTest(unittest.TestCase):
    def test_write_read_and_private_mode(self):
        with tempfile.TemporaryDirectory() as directory:
            path = os.path.join(directory, "a.wav")
            writer = WavWriter(path)
            writer.write(np.full(16_000, 0.25, dtype=np.float32))
            writer.close()
            self.assertEqual(stat.S_IMODE(os.stat(path).st_mode), 0o600)
            data = read_wav(path)
            self.assertEqual(data.size, 16_000)
            self.assertAlmostEqual(float(data[0]), 0.25, places=3)

    def test_crash_leaves_repairable_file(self):
        with tempfile.TemporaryDirectory() as directory:
            path = os.path.join(directory, "a.wav")
            writer = WavWriter(path, sync_seconds=3600)
            writer.write(np.zeros(800, dtype=np.float32))
            writer.file.flush()  # Simulated crash: header still says 0 samples.
            self.assertEqual(read_wav(path).size, 800)
            self.assertEqual(repair_header(path), 800)
            writer.file.close()


class HistoryTest(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.history = History(Path(self.directory.name) / "history")

    def tearDown(self):
        self.directory.cleanup()

    def test_entry_lifecycle(self):
        entry = self.history.create("Micro USB")
        writer = entry.open_audio()
        writer.write(np.zeros(1600, dtype=np.float32))
        writer.close()
        entry.set_text("Bonjour")
        entry.update(state="done", insertion="pasted", duration_ms=100)
        loaded = self.history.get(entry.id)
        self.assertEqual(loaded.text(), "Bonjour")
        self.assertEqual(loaded.meta.insertion, "pasted")
        self.assertTrue(loaded.has_audio())
        self.assertEqual(stat.S_IMODE(os.stat(entry.directory).st_mode), 0o700)
        with self.assertRaises(ValueError):
            entry.update(state="bogus")

    def test_prune_keeps_unfinished_and_protected(self):
        ids = []
        for index in range(5):
            entry = self.history.create()
            entry.meta.created = 1000 + index
            entry.update(state="done" if index else "recording")
            ids.append(entry.id)
        removed = self.history.prune(2, keep={ids[1]})
        remaining = {e.id for e in self.history.entries()}
        self.assertEqual(removed, 1)
        self.assertEqual(remaining, {ids[0], ids[1], ids[3], ids[4]})

    def test_recover_marks_interrupted_and_repairs_audio(self):
        entry = self.history.create()
        writer = entry.open_audio()
        writer.sync_seconds = 3600
        writer.write(np.zeros(3200, dtype=np.float32))
        writer.file.flush()
        entry.set_text("début")
        recovered = self.history.recover()
        self.assertEqual([e.id for e in recovered], [entry.id])
        loaded = self.history.get(entry.id)
        self.assertEqual(loaded.meta.state, "interrupted")
        self.assertEqual(loaded.meta.duration_ms, 200)
        self.assertEqual(loaded.text(), "début")
        writer.file.close()

    def test_get_rejects_traversal_and_corrupt_meta(self):
        self.assertIsNone(self.history.get("../x"))
        bad = self.history.root / "bad"
        bad.mkdir()
        (bad / "meta.json").write_text("{nope")
        self.assertEqual(self.history.entries(), [])


class SettingsTest(unittest.TestCase):
    def test_invalid_values_fall_back(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "settings.json"
            path.write_text(json.dumps({"gain_db": 99, "auto_gain": "yes", "paste_keys": "rm -rf",
                                        "history_limit": True, "input_device": 3, "vad_threshold": 0.3}))
            settings = load_settings(path)
            self.assertEqual(settings.gain_db, 30.0)
            self.assertTrue(settings.auto_gain)
            self.assertEqual(settings.paste_keys, "ctrl-v")
            self.assertEqual(settings.history_limit, 50)
            self.assertIsNone(settings.input_device)
            self.assertEqual(settings.vad_threshold, 0.3)

    def test_round_trip(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "settings.json"
            save_settings(Settings(gain_db=6, input_device="node.name=x", input_device_label="X"), path)
            loaded = load_settings(path)
            self.assertEqual((loaded.gain_db, loaded.input_device), (6.0, "node.name=x"))
            self.assertEqual(stat.S_IMODE(os.stat(path).st_mode), 0o600)
            self.assertEqual(load_settings(Path(directory) / "missing.json"), Settings())


if __name__ == "__main__":
    unittest.main()
