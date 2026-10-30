package be.arte.localdictate.asr

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.atomic.AtomicBoolean

class DictationPipelineTest {
    private class FixtureSegmenter : SpeechSegmenter {
        private var tail: FloatArray? = null
        override fun accept(samples: FloatArray): List<FloatArray> {
            val previous = tail
            tail = samples
            return listOfNotNull(previous)
        }
        override fun finish() = listOfNotNull(tail).also { tail = null }
    }
    @Test fun finalSegmentIsFlushedAndAcceptedAudioDrainedInOrder() = runBlocking {
        val texts = mutableListOf<String>()
        var draining = false
        val failure = DictationPipeline(FixtureSegmenter(), capture = { emit ->
            repeat(4) { emit(floatArrayOf(it.toFloat())) }
        }, stopCapture = {}, recognize = { it.single().toInt().toString() }, onText = { texts += it },
            onDraining = { draining = true }, onError = { fail(it) }).run()
        assertNull(failure)
        assertTrue(draining)
        assertEquals(listOf("0", "1", "2", "3"), texts)
    }
    @Test fun silenceSegmentsDoNotStopSession() = runBlocking {
        val stopped = AtomicBoolean(false)
        var count = 0
        val segmenter = object : SpeechSegmenter {
            override fun accept(samples: FloatArray) = emptyList<FloatArray>()
            override fun finish() = emptyList<FloatArray>()
        }
        DictationPipeline(segmenter, capture = { emit -> repeat(10) {
            assertFalse(stopped.get())
            emit(FloatArray(512)); count++
        } }, stopCapture = { stopped.set(true) }, recognize = { fail("Silence must not reach ASR"); "" },
            onText = {}, onDraining = {}, onError = { fail(it) }).run()
        assertEquals(10, count)
    }
    @Test fun captureLimitTrimsFinalReadExactlyAtFifteenMinutes() {
        val budget = CaptureBudget()
        val samples = FloatArray(16_000)
        repeat(899) { assertEquals(16_000, budget.accept(samples).size) }
        assertFalse(budget.exhausted)
        assertEquals(16_000, budget.accept(FloatArray(16_500)).size)
        assertTrue(budget.exhausted)
        assertEquals(14_400_000L, budget.capturedSamples)
        assertEquals(0, budget.accept(samples).size)
    }
    @Test fun durationLimitStopsAndDrainsLastPartialBlock() = runBlocking {
        val stopped = AtomicBoolean(false)
        val durations = mutableListOf<Long>()
        val sizes = mutableListOf<Int>()
        DictationPipeline(FixtureSegmenter(), capture = { emit ->
            while (!stopped.get()) emit(FloatArray(512))
        }, stopCapture = { stopped.set(true) }, recognize = { sizes += it.size; "mot" },
            onText = {}, onDraining = {}, onError = { fail(it) }, onDuration = { durations += it },
            budget = CaptureBudget(1000)).run()
        assertEquals(listOf(512, 488), sizes)
        assertEquals(62L, durations.last())
    }
    @Test fun overloadStopsMicrophoneButPreservesAllAcceptedSegments() = runBlocking {
        withTimeout(5_000) {
            val stopped = AtomicBoolean(false)
            val blocked = CompletableDeferred<Unit>()
            val errors = mutableListOf<String>()
            val output = mutableListOf<Int>()
            val run = async {
                DictationPipeline(FixtureSegmenter(), capture = { emit ->
                    var index = 0
                    while (!stopped.get()) emit(floatArrayOf((index++).toFloat()))
                }, stopCapture = { stopped.set(true) }, recognize = {
                    blocked.await(); it.single().toInt().toString()
                }, onText = { output += it.toInt() }, onDraining = {}, onError = { synchronized(errors) { errors += it } },
                    pcmCapacity = 1, segmentCapacity = 1).run()
            }
            while (synchronized(errors) { errors.isEmpty() }) delay(5)
            assertTrue(stopped.get())
            blocked.complete(Unit)
            assertNotNull(run.await())
            assertTrue(output.isNotEmpty())
            assertEquals((0 until output.size).toList(), output)
            assertEquals(1, errors.size)
        }
    }
    @Test fun recognitionFailureStopsProducerAndReportsIncompleteTranscript() = runBlocking {
        withTimeout(5_000) {
            val stopped = AtomicBoolean(false)
            val error = DictationPipeline(FixtureSegmenter(), capture = { emit ->
                while (!stopped.get()) { emit(floatArrayOf(1f)); delay(2) }
            }, stopCapture = { stopped.set(true) }, recognize = { error("Fixture decoder failed") },
                onText = { fail() }, onDraining = {}, onError = {}, pcmCapacity = 2, segmentCapacity = 2).run()
            assertTrue(stopped.get())
            assertTrue(error!!.contains("incomplète"))
        }
    }
    @Test fun cancellationClosesCaptureAndDoesNotPretendToFinish() = runBlocking {
        val stopped = AtomicBoolean(false)
        val entered = CompletableDeferred<Unit>()
        val job = launch {
            DictationPipeline(FixtureSegmenter(), capture = { entered.complete(Unit); awaitCancellation() },
                stopCapture = { stopped.set(true) }, recognize = { "" }, onText = {},
                onDraining = {}, onError = {}).run()
        }
        entered.await(); job.cancelAndJoin()
        assertTrue(stopped.get())
    }
}
