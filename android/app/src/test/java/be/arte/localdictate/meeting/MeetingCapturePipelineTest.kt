package be.arte.localdictate.meeting

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.atomic.AtomicBoolean

class MeetingCapturePipelineTest {
    /** Emits [blocks] blocks of [samples] samples as fast as possible, like non-blocking AudioRecord reads. */
    private class FakeRecorder(private val blocks: Int, private val samples: Int) : MeetingRecorder {
        val stopped = AtomicBoolean(false)
        var emitted = 0L
        override suspend fun read(emit: (ByteArray) -> Unit) {
            repeat(blocks) {
                if (stopped.get()) return
                emit(ByteArray(samples * 2) { 1 }); emitted += samples * 2L
            }
        }
        override fun stop() = stopped.set(true)
    }

    @Test fun tinyBlocksAreCommittedAboutOncePerSecondWithoutLoss() = runBlocking {
        // 10 s of audio in 5 ms blocks (80 samples), the size non-blocking reads return on a phone.
        val recorder = FakeRecorder(blocks = 2_000, samples = 80)
        val appends = mutableListOf<Int>()
        val result = MeetingCapturePipeline(recorder, append = { block ->
            appends += block.size
            AudioAppendResult(block.size.toLong(), 0, false)
        }).run()
        assertEquals("", result.diagnostic)
        assertEquals(160_000L, result.samples)
        assertEquals(320_000, appends.sum())
        assertTrue("one sync per second of audio, not per block: ${appends.size}", appends.size in 10..11)
    }

    @Test fun slowDiskShorterThanTheBufferDoesNotStopTheMicrophone() = runBlocking {
        // A 2 s stall on the first sync used to fill the 32-block queue and close the microphone.
        val recorder = FakeRecorder(blocks = 2_000, samples = 80)
        var first = true
        val result = MeetingCapturePipeline(recorder, append = { block ->
            if (first) { first = false; Thread.sleep(2_000) }
            AudioAppendResult(block.size.toLong(), 0, false)
        }).run()
        assertEquals("", result.diagnostic)
        assertEquals(320_000L, recorder.emitted) // The microphone was never closed early.
        assertEquals(160_000L, result.samples)
    }

    @Test fun diskStalledBeyondTheBufferStopsCaptureButDrainsAcceptedAudio() = runBlocking {
        val recorder = FakeRecorder(blocks = 2_000, samples = 80)
        var stalled = true
        val result = MeetingCapturePipeline(recorder, append = { block ->
            while (stalled && !recorder.stopped.get()) Thread.sleep(1)
            stalled = false
            AudioAppendResult(block.size.toLong(), 0, false)
        }, maxPendingBytes = 96_000).run()
        assertTrue(result.diagnostic, result.diagnostic.startsWith("Écriture disque bloquée"))
        assertTrue(recorder.stopped.get())
        assertEquals(recorder.emitted / 2, result.samples) // Everything accepted reached the disk.
    }

    @Test fun twoHourLimitStopsExactly() = runBlocking {
        val recorder = FakeRecorder(blocks = 1_000, samples = 80)
        val result = MeetingCapturePipeline(recorder, append = { AudioAppendResult(it.size.toLong(), 0, false) },
            maxSamples = 8_000).run()
        assertEquals(8_000L, result.samples)
        assertTrue(result.diagnostic.startsWith("Limite de 2 heures"))
    }
}
