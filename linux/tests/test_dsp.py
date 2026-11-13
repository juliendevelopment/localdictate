import unittest

import numpy as np

from localdictate.dsp import (InputChain, PcmFramer, SegmentPadder, VoiceGain, dbfs, float_to_pcm16,
                              pcm16_to_float, split_lengths)


class VoiceGainTest(unittest.TestCase):
    def test_soft_speech_is_raised_without_clipping_and_loud_speech_untouched(self):
        rate = 16_000
        t = np.arange(rate * 2) / rate
        soft = (0.02 * np.sin(2 * np.pi * 220 * t)).astype(np.float32)
        gain = VoiceGain()
        out = gain.apply(soft)
        self.assertGreater(np.max(np.abs(out[rate:])), 0.1)
        self.assertLessEqual(np.max(np.abs(out)), 0.5 + 1e-6)
        loud = (0.9 * np.sin(2 * np.pi * 220 * t)).astype(np.float32)
        out = VoiceGain().apply(loud)
        self.assertLessEqual(np.max(np.abs(out)), 0.9 + 1e-6)
        self.assertGreaterEqual(VoiceGain().lowest_gain, 1.0)

    def test_gain_never_below_one(self):
        gain = VoiceGain()
        gain.apply(np.ones(1600, dtype=np.float32))
        self.assertEqual(gain.gain, 1.0)

    def test_input_chain_manual_gain_and_clip(self):
        chain = InputChain(gain_db=20, auto_gain=False)
        out = chain.apply(np.array([0.01, 0.5, -0.5], dtype=np.float32))
        self.assertAlmostEqual(float(out[0]), 0.1, places=4)
        self.assertEqual(float(out[1]), 1.0)
        self.assertEqual(float(out[2]), -1.0)
        self.assertAlmostEqual(chain.lowest_gain, 10.0, places=4)


class PcmTest(unittest.TestCase):
    def test_round_trip(self):
        samples = np.array([0.0, 0.5, -0.5, -1.0], dtype=np.float32)
        self.assertTrue(np.allclose(pcm16_to_float(float_to_pcm16(samples)), samples, atol=1e-4))
        self.assertEqual(dbfs(0.0), -120.0)

    def test_framer_keeps_partial_reads(self):
        frames = []
        framer = PcmFramer(4)
        framer.accept(np.arange(3, dtype=np.float32), frames.append)
        framer.accept(np.arange(3, 7, dtype=np.float32), frames.append)
        self.assertEqual([f.tolist() for f in frames], [[0, 1, 2, 3]])
        framer.finish(frames.append)
        self.assertEqual(frames[-1].tolist(), [4, 5, 6, 0])
        self.assertEqual(framer.sample_count, 7)


class SegmentPadderTest(unittest.TestCase):
    def test_margins_never_overlap_previous_segment(self):
        padder = SegmentPadder(rate=10, before=3, after=3, capacity=100)
        padder.write(np.arange(50, dtype=np.float32))
        first = padder.widen(10, 5)
        self.assertEqual((first.start, first.samples.size), (7, 11))
        second = padder.widen(19, 5)
        self.assertEqual(second.start, 18)  # Not 16: the first segment already emitted up to 18.
        self.assertEqual(second.samples[0], 18)

    def test_after_margin_bounded_by_written_audio(self):
        padder = SegmentPadder(rate=10, before=0, after=10, capacity=100)
        padder.write(np.arange(20, dtype=np.float32))
        self.assertEqual(padder.widen(15, 5).samples.size, 5)

    def test_ring_wraps(self):
        padder = SegmentPadder(rate=10, before=0, after=0, capacity=8)
        padder.write(np.arange(13, dtype=np.float32))
        self.assertEqual(padder.widen(9, 4).samples.tolist(), [9, 10, 11, 12])


class SplitTest(unittest.TestCase):
    def test_short_segment_unchanged(self):
        self.assertEqual(split_lengths(np.zeros(40, dtype=np.float32), max_length=50, search=20, window=10), [40])

    def test_cut_at_quietest_window_contiguous(self):
        rng = np.random.default_rng(1)
        samples = rng.uniform(-0.5, 0.5, 23 * 16_000).astype(np.float32)
        quiet = 18 * 16_000
        samples[quiet:quiet + 1600] = 0
        lengths = split_lengths(samples)
        self.assertEqual(sum(lengths), samples.size)
        self.assertTrue(all(length <= 20 * 16_000 for length in lengths))
        self.assertEqual(lengths[0], quiet + 800)


if __name__ == "__main__":
    unittest.main()
