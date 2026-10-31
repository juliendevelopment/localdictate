package be.arte.localdictate.audio

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class SegmentPadderTest {
    private fun ramp(from: Int, count: Int) = FloatArray(count) { (from + it).toFloat() }

    @Test fun widensBothSidesWithRealCapturedAudio() {
        val padder = SegmentPadder(rate = 10, before = 3, after = 2, capacity = 100)
        padder.write(ramp(0, 30))
        assertArrayEquals(ramp(7, 10), padder.widen(start = 10, length = 5), 0f)
    }

    @Test fun marginsNeverOverlapThePreviousSegmentSoNoWordIsDecodedTwice() {
        val padder = SegmentPadder(rate = 10, before = 4, after = 4, capacity = 100)
        padder.write(ramp(0, 40))
        assertArrayEquals(ramp(6, 13), padder.widen(start = 10, length = 5), 0f) // 6..18
        assertArrayEquals(ramp(19, 9), padder.widen(start = 21, length = 3), 0f) // starts after 18
    }

    @Test fun trailingMarginIsLimitedToWhatWasCaptured() {
        val padder = SegmentPadder(rate = 10, before = 0, after = 10, capacity = 100)
        padder.write(ramp(0, 12))
        assertEquals(2, padder.widen(start = 10, length = 2).size)
    }

    @Test fun ringWrapsAndNeverReturnsOverwrittenAudio() {
        val padder = SegmentPadder(rate = 10, before = 50, after = 0, capacity = 16)
        padder.write(ramp(0, 40))
        assertArrayEquals(ramp(24, 16), padder.widen(start = 30, length = 10), 0f)
    }
}
