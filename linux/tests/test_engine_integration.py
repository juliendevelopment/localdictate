"""Runs the real sherpa-onnx engine. Skipped unless LOCALDICTATE_TEST_MODELS points to a directory holding
``sherpa-onnx-nemo-parakeet-tdt-0.6b-v3-int8/`` (with its test_wavs) and ``silero_vad.onnx``. Host evidence only:
it proves the Python binding and the pipeline, not a microphone, a desktop or a paste."""
import os
import threading
import unittest
import wave
from pathlib import Path

import numpy as np

from localdictate.dsp import InputChain
from localdictate.engine import ModelFiles
from localdictate.models import MEMBERS, PARAKEET, VAD, regular_file_with_size
from localdictate.pipeline import DictationPipeline

ROOT = os.environ.get("LOCALDICTATE_TEST_MODELS")


def load(path: Path) -> np.ndarray:
    with wave.open(str(path)) as file:
        rate = file.getframerate()
        data = np.frombuffer(file.readframes(file.getnframes()), dtype="<i2").astype(np.float32) / 32768
    positions = np.arange(0, data.size, rate / 16_000)
    return np.interp(positions, np.arange(data.size), data).astype(np.float32)


@unittest.skipUnless(ROOT, "LOCALDICTATE_TEST_MODELS not set")
class EngineIntegrationTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        from localdictate.engine import Recognizer

        root = Path(ROOT)
        files = ModelFiles(root / PARAKEET, root / VAD.name)
        for asset in MEMBERS:
            assert regular_file_with_size(files.parakeet_dir / asset.name, asset.size), asset.name
        cls.recognizer = Recognizer(files)
        cls.wavs = files.parakeet_dir / "test_wavs"

    def test_pipeline_french_pause_english(self):
        audio = np.concatenate([np.zeros(8000, np.float32), load(self.wavs / "fr.wav"), np.zeros(16_000, np.float32),
                                load(self.wavs / "en.wav"), np.zeros(8000, np.float32)])
        stopped = threading.Event()
        pipeline = DictationPipeline(lambda: self.recognizer.segmenter(0.5), self.recognizer.recognize,
                                     chain=InputChain(0, True), stop_capture=stopped.set, on_text=lambda t: None)
        pipeline.start()
        for offset in range(0, audio.size, 1600):
            pipeline.feed(audio[offset:offset + 1600])
        pipeline.stop_capture()
        self.assertTrue(pipeline.wait(120))
        self.assertIsNone(pipeline.failure)
        text = pipeline.text.lower()
        print("\n[engine] " + pipeline.text)
        self.assertIn("votre pays", text)
        self.assertIn("your country", text)
        self.assertGreaterEqual(len(pipeline.texts), 2)  # The pause split the speech, it did not end the session.

    def test_soft_speech_with_auto_gain(self):
        audio = load(self.wavs / "fr.wav") * 0.05  # About −26 dB.
        segmenter = self.recognizer.segmenter(0.5)
        chain = InputChain(0, True)
        texts = []
        for offset in range(0, audio.size, 1600):
            for segment in segmenter.accept(chain.apply(audio[offset:offset + 1600])):
                texts.append(self.recognizer.recognize(segment))
        for segment in segmenter.finish():
            texts.append(self.recognizer.recognize(segment))
        self.assertIn("pays", " ".join(texts).lower())


if __name__ == "__main__":
    unittest.main()
