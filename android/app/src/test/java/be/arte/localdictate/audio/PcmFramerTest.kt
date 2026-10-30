package be.arte.localdictate.audio

import org.junit.Assert.*
import org.junit.Test

class PcmFramerTest {
    @Test fun pcmUsesSigned16BitScale() {
        assertArrayEquals(floatArrayOf(-1f, 0f, 32767f / 32768f),
            Pcm16.floats(shortArrayOf(Short.MIN_VALUE, 0, Short.MAX_VALUE)), 0f)
        assertEquals(0f, Pcm16.level(floatArrayOf()), 0f)
        assertEquals(1f, Pcm16.level(floatArrayOf(-1f, 1f)), 0f)
    }

    @Test fun arbitraryReadsPreserveEverySampleAndOnlyPadAtEnd() {
        val source = FloatArray(1607) { it.toFloat() }
        val frames = mutableListOf<FloatArray>()
        val framer = PcmFramer()
        var offset = 0
        for (size in listOf(17, 495, 3, 1000, 92)) {
            framer.accept(source.copyOfRange(offset, offset + size), frames::add)
            offset += size
        }
        assertEquals(3, frames.size)
        framer.finish(frames::add)
        assertEquals(4, frames.size)
        assertEquals(1607L, framer.sampleCount)
        val output = frames.flatMap { it.toList() }.toFloatArray()
        assertArrayEquals(source, output.copyOf(source.size), 0f)
        assertTrue(output.drop(source.size).all { it == 0f })
        framer.finish(frames::add)
        assertEquals(4, frames.size)
    }

    @Test fun segmentEndUsesCaptureSamplesNotWorkerClock() {
        val clock = SampleClock(10_000)
        assertEquals(11_032L, clock.at(16_512))
        assertEquals(1000L, clock.duration(16_000))
    }
}
