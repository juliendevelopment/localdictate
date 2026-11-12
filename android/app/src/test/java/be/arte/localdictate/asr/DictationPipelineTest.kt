package be.arte.localdictate.asr

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
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
    /** Records every sample value it receives; [onAccept] can stall the segmentation thread. */
    private class RecordingSegmenter(private val onAccept: (Int) -> Unit = {}) : SpeechSegmenter {
        val received = mutableListOf<Float>()
        private var calls = 0
        override fun accept(samples: FloatArray): List<FloatArray> {
            onAccept(calls++)
            synchronized(received) { samples.forEach { received += it } }
            return emptyList()
        }
        override fun finish() = emptyList<FloatArray>()
    }
    @Test fun manyTinyBlocksSurviveAShortStall() = runBlocking {
        withTimeout(10_000) {
            // Non-blocking reads return a few milliseconds each: 400 blocks of 5 ms = 2 s of audio.
            val segmenter = RecordingSegmenter { call -> if (call == 0) Thread.sleep(300) }
            var next = 0
            val failure = DictationPipeline(segmenter, capture = { emit ->
                repeat(400) { emit(FloatArray(80) { (next++).toFloat() }) }
            }, stopCapture = {}, recognize = { "" }, onText = {}, onDraining = {},
                onError = { fail(it) }).run()
            assertNull(failure)
            assertEquals(32_000, segmenter.received.size)
            assertEquals((0 until 32_000).map { it.toFloat() }, segmenter.received)
        }
    }
    @Test fun stallLongerThanTheLimitStopsOnceAndDrainsEveryAcceptedSample() = runBlocking {
        withTimeout(10_000) {
            val stopped = AtomicBoolean(false)
            val release = CountDownLatch(1)
            val errors = mutableListOf<String>()
            val segmenter = RecordingSegmenter { call -> if (call == 0) release.await(5, TimeUnit.SECONDS) }
            var emitted = 0
            val run = async {
                DictationPipeline(segmenter, capture = { emit ->
                    while (!stopped.get()) { emit(FloatArray(80) { (emitted++).toFloat() }); delay(1) }
                }, stopCapture = { stopped.set(true) }, recognize = { "" }, onText = {}, onDraining = {},
                    onError = { synchronized(errors) { errors += it } }, maxPendingSamples = 1_600).run()
            }
            while (synchronized(errors) { errors.isEmpty() }) delay(5)
            assertTrue(stopped.get())
            release.countDown()
            assertNotNull(run.await())
            assertEquals(1, errors.size)
            assertTrue(errors.single().contains("saturée"))
            assertEquals((0 until emitted).map { it.toFloat() }, segmenter.received)
        }
    }
    @Test fun durationIsPublishedOncePerSecondAndExactlyAtTheEnd() = runBlocking {
        val durations = mutableListOf<Long>()
        DictationPipeline(RecordingSegmenter(), capture = { emit ->
            repeat(481) { emit(FloatArray(100)) } // 48,100 samples = 3006 ms in 5 ms blocks
        }, stopCapture = {}, recognize = { "" }, onText = {}, onDraining = {}, onError = { fail(it) },
            onDuration = { durations += it }).run()
        assertEquals(3006L, durations.last())
        assertEquals(listOf(6L, 1000L, 2000L, 3000L, 3006L), durations)
    }
    @Test fun fifteenMinuteBudgetStillStopsAtNineHundredSeconds() = runBlocking {
        val stopped = AtomicBoolean(false)
        val durations = mutableListOf<Long>()
        DictationPipeline(RecordingSegmenter(), capture = { emit ->
            while (!stopped.get()) emit(FloatArray(16_000))
        }, stopCapture = { stopped.set(true) }, recognize = { "" }, onText = {}, onDraining = {},
            onError = { fail(it) }, onDuration = { durations += it },
            maxPendingSamples = Long.MAX_VALUE).run() // Faster than real time: the queue limit is tested above.
        assertEquals(900_000L, durations.last())
        assertTrue(durations.size <= 902)
    }
    @Test fun failedStartStopsTheMicrophoneAndRecognizesNothing() = runBlocking {
        withTimeout(5_000) {
            val stopped = AtomicBoolean(false)
            val errors = mutableListOf<String>()
            val failure = DictationPipeline(FixtureSegmenter(), capture = { emit ->
                while (!stopped.get()) { emit(floatArrayOf(1f)); delay(1) }
            }, stopCapture = { stopped.set(true) }, recognize = { "mot" }, onText = { fail("No text without a journal") },
                onDraining = {}, onError = { synchronized(errors) { errors += it } },
                start = { error("Previous dictation not cleared") }).run()
            assertTrue(stopped.get())
            assertNotNull(failure)
            assertEquals(1, errors.size)
            assertTrue(errors.single().contains("rien n’est inséré"))
        }
    }
    @Test fun noTextBeforeTheJournalIsOpenAndNothingLostAfter() = runBlocking {
        withTimeout(5_000) {
            val opened = AtomicBoolean(false)
            val texts = mutableListOf<String>()
            val failure = DictationPipeline(FixtureSegmenter(), capture = { emit ->
                repeat(4) { emit(floatArrayOf(it.toFloat())) }
            }, stopCapture = {}, recognize = { it.single().toInt().toString() },
                onText = { assertTrue(opened.get()); texts += it }, onDraining = {}, onError = { fail(it) },
                start = { delay(200); opened.set(true) }).run()
            assertNull(failure)
            assertEquals(listOf("0", "1", "2", "3"), texts)
        }
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
                    maxPendingSamples = 1, segmentCapacity = 1).run()
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
                onText = { fail() }, onDraining = {}, onError = {}, maxPendingSamples = 2, segmentCapacity = 2).run()
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
