import threading
import time
import unittest

import numpy as np

from localdictate.dsp import InputChain
from localdictate.pipeline import DictationPipeline, Diagnostic
from tests.fakes import ChunkSegmenter, CountingEngine


def make(engine=None, segmenter=None, **kwargs):
    engine = engine or CountingEngine()
    stopped = threading.Event()
    texts, errors = [], []
    pipeline = DictationPipeline(
        segmenter or (lambda: ChunkSegmenter()), engine.recognize, chain=InputChain(0, False),
        stop_capture=stopped.set, on_text=texts.append, on_error=errors.append, **kwargs)
    pipeline.start()
    return pipeline, stopped, texts, errors


class PipelineTest(unittest.TestCase):
    def test_all_accepted_audio_is_recognized_in_order(self):
        pipeline, stopped, texts, errors = make(diagnostic=Diagnostic(time.monotonic()))
        for _ in range(10):
            pipeline.feed(np.full(800, 0.1, dtype=np.float32))
        pipeline.stop_capture()
        self.assertTrue(pipeline.wait(5))
        self.assertEqual(texts, [f"mot{i}" for i in range(1, 6)])
        self.assertEqual(pipeline.text, "mot1 mot2 mot3 mot4 mot5")
        self.assertIsNone(pipeline.failure)
        self.assertTrue(stopped.is_set())
        self.assertIn("5 segments", pipeline.diagnostic.line())

    def test_limit_cuts_last_block_and_stops_capture(self):
        pipeline, stopped, texts, _ = make(max_samples=2000)
        pipeline.feed(np.zeros(1600, dtype=np.float32))
        pipeline.feed(np.zeros(1600, dtype=np.float32))
        pipeline.feed(np.zeros(1600, dtype=np.float32))
        self.assertTrue(pipeline.wait(5))
        self.assertTrue(stopped.is_set())
        self.assertEqual(pipeline.captured, 2000)
        self.assertEqual(len(texts), 2)  # 1600 + final 400.

    def test_model_still_loading_queues_audio(self):
        ready = threading.Event()

        def segmenter():
            ready.wait(5)
            return ChunkSegmenter()

        pipeline, _stopped, texts, _ = make(segmenter=segmenter)
        for _ in range(4):
            pipeline.feed(np.zeros(1600, dtype=np.float32))
        time.sleep(0.05)
        self.assertEqual(texts, [])
        ready.set()
        pipeline.stop_capture()
        self.assertTrue(pipeline.wait(5))
        self.assertEqual(len(texts), 4)

    def test_saturation_keeps_crossing_block_and_reports(self):
        ready = threading.Event()

        def segmenter():
            ready.wait(5)
            return ChunkSegmenter()

        pipeline, stopped, texts, errors = make(segmenter=segmenter, max_pending=3000)
        pipeline.feed(np.zeros(1600, dtype=np.float32))
        pipeline.feed(np.zeros(1600, dtype=np.float32))  # Crosses the limit: kept, capture stopped.
        pipeline.feed(np.zeros(1600, dtype=np.float32))  # After the stop: ignored.
        ready.set()
        self.assertTrue(pipeline.wait(5))
        self.assertTrue(stopped.is_set())
        self.assertEqual(len(texts), 2)
        self.assertIn("saturée", errors[0])

    def test_burst_of_segments_waits_instead_of_failing(self):
        pipeline, stopped, texts, errors = make(engine=CountingEngine(delay=0.01), segment_capacity=2)
        for _ in range(20):
            pipeline.feed(np.zeros(1600, dtype=np.float32))
        self.assertFalse(stopped.is_set())
        pipeline.stop_capture()
        self.assertTrue(pipeline.wait(10))
        self.assertEqual((len(texts), errors, pipeline.failure), (20, [], None))

    def test_recognizer_failure_keeps_previous_text(self):
        pipeline, stopped, texts, errors = make(engine=CountingEngine(fail_at=3))
        for _ in range(6):
            pipeline.feed(np.zeros(1600, dtype=np.float32))
        pipeline.stop_capture()
        self.assertTrue(pipeline.wait(5))
        self.assertEqual(texts, ["mot1", "mot2"])
        self.assertIn("Reconnaissance", pipeline.failure)
        self.assertTrue(stopped.is_set())

    def test_segmenter_failure_is_reported(self):
        def segmenter():
            raise RuntimeError("no model")

        pipeline, stopped, texts, errors = make(segmenter=segmenter)
        pipeline.feed(np.zeros(1600, dtype=np.float32))
        self.assertTrue(pipeline.wait(5))
        self.assertIn("Segmentation", errors[0])
        self.assertTrue(stopped.is_set())

    def test_concurrent_feed_and_stop_loses_nothing_queued(self):
        pipeline, _stopped, texts, _ = make(segmenter=lambda: ChunkSegmenter(160))

        def producer():
            for _ in range(2000):
                pipeline.feed(np.zeros(160, dtype=np.float32))

        thread = threading.Thread(target=producer)
        thread.start()
        time.sleep(0.01)
        pipeline.stop_capture()
        thread.join()
        self.assertTrue(pipeline.wait(5))
        self.assertEqual(len(texts), pipeline.captured // 160)


if __name__ == "__main__":
    unittest.main()
