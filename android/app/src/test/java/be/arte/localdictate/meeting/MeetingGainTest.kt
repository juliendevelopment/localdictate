package be.arte.localdictate.meeting

import org.junit.Assert.*
import org.junit.Test

class MeetingGainTest {
    @Test fun storedValuesSnapToTheOfferedChoices() {
        assertEquals(0, MeetingGain.clamp(-5))
        assertEquals(6, MeetingGain.clamp(7))
        assertEquals(12, MeetingGain.clamp(11))
        assertEquals(18, MeetingGain.clamp(99))
        assertEquals("aucun", MeetingGain.label(0))
        assertEquals("+12 dB", MeetingGain.label(12))
    }
    @Test fun noGainKeepsRawSamples() = assertNull(MeetingGain.create(0))
    @Test fun gainRaisesSoftSpeechUpToItsCeilingWithoutClipping() {
        val soft = FloatArray(16_000) { if (it % 2 == 0) 0.01f else -0.01f }
        fun peak(db: Int) = MeetingGain.create(db)!!.apply(soft).maxOf { kotlin.math.abs(it) }
        assertEquals(0.02f, peak(6), 1e-4f)
        assertEquals(0.04f, peak(12), 1e-4f)
        assertEquals(0.08f, peak(18), 1e-4f)
        val loud = FloatArray(16_000) { if (it % 2 == 0) 0.9f else -0.9f }
        assertTrue(MeetingGain.create(18)!!.apply(loud).all { kotlin.math.abs(it) <= 0.9f + 1e-6f })
    }
}
