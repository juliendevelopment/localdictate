package be.arte.localdictate.session

import org.junit.Assert.*
import org.junit.Test

class DictationDiagnosticTest {
    @Test fun fullLineHasDelayPeakGainSegmentsAndDecodeSpeed() {
        val diagnostic = DictationDiagnostic(tapMs = 10_000)
        diagnostic.audio(originMs = 10_180, raw = floatArrayOf(0.01f, -0.028f), gain = 8f)
        diagnostic.audio(originMs = 10_180, raw = floatArrayOf(0.02f), gain = 6.2f)
        diagnostic.decoded(samples = 8 * 16_000, elapsedMs = 1_000)
        diagnostic.decoded(samples = 2 * 16_000, elapsedMs = 700)
        assertEquals("Diagnostic : micro 180 ms après le toucher • crête −31 dBFS • gain ×6,2 • " +
            "2 segments, 10,0 s • décodage 1,7 s (×0,17)", diagnostic.line())
    }
    @Test fun noAudioIsSaidPlainly() {
        assertEquals("Diagnostic : aucun son reçu du micro • 0 segment, 0,0 s", DictationDiagnostic(5).line())
    }
    @Test fun levelWithoutSegmentsPointsAtTheVad() {
        val diagnostic = DictationDiagnostic(tapMs = 1_000)
        diagnostic.audio(originMs = 900, raw = FloatArray(10), gain = 8f) // Buffered before the tap: never negative.
        assertEquals("Diagnostic : micro 0 ms après le toucher • crête nulle • gain ×8,0 • 0 segment, 0,0 s", diagnostic.line())
    }
    @Test fun fullScalePeakIsZeroDecibels() {
        val diagnostic = DictationDiagnostic(0)
        diagnostic.audio(0, floatArrayOf(1f), 1f)
        assertTrue(diagnostic.line().contains("crête 0 dBFS"))
    }
}
