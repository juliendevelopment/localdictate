package be.arte.localdictate.audio

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import org.junit.Assert.*
import org.junit.Test

class VoiceGainTest {
    private fun tone(amplitude: Float, seconds: Float) =
        FloatArray((16_000 * seconds).toInt()) { amplitude * sin(2 * PI * 220 * it / 16_000).toFloat() }
    private fun peak(samples: FloatArray, from: Int = 0) = samples.drop(from).maxOf { abs(it) }

    @Test fun softSpeechIsAmplifiedUpToTheMaximum() {
        val out = VoiceGain().apply(tone(0.02f, 1f))
        assertEquals(0.16f, peak(out), 0.005f)
    }

    @Test fun moderateSpeechIsBroughtToTheTargetPeak() {
        val out = VoiceGain().apply(tone(0.1f, 1f))
        assertEquals(0.5f, peak(out), 0.01f)
    }

    @Test fun loudSpeechIsNeverAttenuatedNorClipped() {
        val input = tone(0.8f, 1f)
        val out = VoiceGain().apply(input)
        assertArrayEquals(input, out, 0f)
    }

    @Test fun aSuddenLoudWordAfterSilenceNeverClips() {
        val gain = VoiceGain()
        gain.apply(FloatArray(16_000))
        val out = gain.apply(tone(0.3f, 0.5f))
        assertTrue(peak(out) <= 0.5f + 1e-6f)
    }

    @Test fun gainComesBackSlowlyAfterLoudSpeech() {
        val gain = VoiceGain()
        gain.apply(tone(0.5f, 0.5f))
        assertEquals(1f, gain.gain, 0.001f)
        val soft = gain.apply(tone(0.02f, 0.5f))
        assertTrue("no pumping right after a loud word", peak(soft) < 0.05f)
        val later = gain.apply(tone(0.02f, 4f))
        assertEquals(0.16f, peak(later, later.size - 1600), 0.005f)
    }

    @Test fun arbitraryBlockSizesKeepEverySample() {
        val input = tone(0.05f, 0.3f)
        val gain = VoiceGain()
        val out = listOf(17, 1000, 3, 2000, input.size - 3020).runningFold(0, Int::plus).zipWithNext()
            .flatMap { (a, b) -> gain.apply(input.copyOfRange(a, b)).toList() }
        assertEquals(input.size, out.size)
    }
}
