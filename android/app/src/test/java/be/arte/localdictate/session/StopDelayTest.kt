package be.arte.localdictate.session

import org.junit.Assert.assertEquals
import org.junit.Test

class StopDelayTest {
    @Test fun valuesAreRoundedToTheStepAndBounded() {
        assertEquals(500L, StopDelay.DEFAULT_MS)
        assertEquals(0L, StopDelay.clamp(-300))
        assertEquals(0L, StopDelay.clamp(40))
        assertEquals(500L, StopDelay.clamp(461))
        assertEquals(1_300L, StopDelay.clamp(1_349))
        assertEquals(2_000L, StopDelay.clamp(60_000))
    }
    @Test fun labelIsFrench() {
        assertEquals("aucun", StopDelay.label(0))
        assertEquals("0,5 s", StopDelay.label(500))
        assertEquals("1,2 s", StopDelay.label(1_200))
        assertEquals("2,0 s", StopDelay.label(2_000))
    }
}
