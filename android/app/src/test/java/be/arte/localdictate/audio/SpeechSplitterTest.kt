package be.arte.localdictate.audio

import org.junit.Assert.*
import org.junit.Test

class SpeechSplitterTest {
    @Test fun segmentsWithinTheLimitAreKeptWhole() {
        assertEquals(listOf(16_000), SpeechSplitter.lengths(FloatArray(16_000) { 0.5f }))
        assertEquals(listOf(320_000), SpeechSplitter.lengths(FloatArray(320_000) { 0.5f }))
        assertEquals(emptyList<Int>(), SpeechSplitter.lengths(FloatArray(10), count = 0))
    }

    @Test fun phoneCallSegmentOf23SecondsIsCutAtItsQuietestPoint() {
        // 23 s of continuous speech, as Silero returned at 129.7 s of the first speakerphone recording,
        // with one quieter breath at 17.2 s.
        val samples = FloatArray(23 * 16_000) { 0.5f }
        for (i in 275_200 until 276_800) samples[i] = 0.01f
        val lengths = SpeechSplitter.lengths(samples)
        assertEquals(samples.size, lengths.sum())
        assertTrue(lengths.all { it <= 20 * 16_000 })
        assertEquals(276_000, lengths.first()) // Middle of the quiet window.
    }

    @Test fun veryLongSegmentsGiveContiguousPiecesUnderTheLimit() {
        val samples = FloatArray(65 * 16_000) { i -> if (i % 7 == 0) 0.2f else 0.6f }
        val lengths = SpeechSplitter.lengths(samples, count = 64 * 16_000)
        assertEquals(64 * 16_000, lengths.sum())
        assertTrue(lengths.all { it in 1..20 * 16_000 })
        assertTrue(lengths.dropLast(1).all { it >= 15 * 16_000 }) // Cuts only within the last 5 s of each window.
    }
}
