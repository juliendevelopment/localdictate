package be.arte.localdictate.meeting

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import java.io.File
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/** Recorder fixtures supply explicitly synthetic PCM; production supplies owned AudioRecord PCM16. */
interface MeetingRecorder {
    suspend fun read(emit: (ByteArray) -> Unit)
    fun stop()
}
data class CaptureResult(val samples: Long, val diagnostic: String)

/** Capture never waits for disk until overload has already closed the microphone. */
class MeetingCapturePipeline(
    private val recorder: MeetingRecorder,
    private val append: (ByteArray) -> AudioAppendResult,
    private val duration: (Long) -> Unit = {},
    private val capacity: Int = 32,
    private val maxSamples: Long = 115_200_000L,
) {
    init { require(capacity in 1..64 && maxSamples in 1..115_200_000L) }
    suspend fun run(): CaptureResult = supervisorScope {
        val queue = Channel<ByteArray>(capacity)
        val failure = AtomicReference("")
        val written = AtomicLong(0)
        val queued = AtomicLong(0)
        fun fail(message: String) { failure.compareAndSet("", message); recorder.stop() }
        val producer = launch(Dispatchers.IO) {
            try {
                recorder.read { raw ->
                    require(raw.size <= PcmRecording.MAX_APPEND_BYTES && raw.size % 2 == 0)
                    val bytes = minOf(raw.size.toLong(), maxSamples * 2 - queued.get()).toInt()
                    if (bytes > 0) {
                        val block = if (bytes == raw.size) raw else raw.copyOf(bytes)
                        if (!queue.trySend(block).isSuccess) {
                            fail("File d’écriture saturée : micro fermé, vidage des blocs acceptés.")
                            runBlocking { queue.send(block) }
                        }
                        queued.addAndGet(bytes.toLong())
                    }
                    if (queued.get() == maxSamples * 2) {
                        fail("Limite de 2 heures atteinte ; capture arrêtée.")
                    }
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { fail("Capture interrompue : microphone, permission ou stockage à vérifier.") }
            finally { recorder.stop(); queue.close() }
        }
        val writer = launch(Dispatchers.IO) {
            try {
                for (block in queue) {
                    val result = append(block)
                    written.addAndGet(result.acceptedBytes)
                    duration(written.get() * 1000 / 32_000)
                    if (result.limitReached) recorder.stop()
                    check(result.rejectedBytes == 0L) { "Unexpected capture budget mismatch" }
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) {
                fail("Écriture interrompue : audio synchronisé conservé. Des blocs en attente peuvent manquer ; reprise explicite requise.")
                queue.cancel()
            }
        }
        try { joinAll(producer, writer) }
        finally { recorder.stop(); queue.cancel() }
        val missing = (queued.get() - written.get()).coerceAtLeast(0)
        CaptureResult(written.get() / 2, failure.get() + if (missing > 0) " $missing octets acceptés en RAM non acquittés sur disque." else "")
    }
}

data class MeetingSpeech(val startSample: Long, val samples: FloatArray, val endSample: Long = startSample + samples.size)
interface MeetingDecoder : AutoCloseable {
    fun accept(samples: FloatArray): List<MeetingSpeech>
    fun finish(): List<MeetingSpeech>
    fun recognize(samples: FloatArray): String
}

/**
 * Joins VAD segments separated by short pauses into one decode, so a thought interrupted by a pause keeps its
 * context. Decoded alone, a fragment loses its punctuation and a lone hesitation is often heard as an English
 * word. The pause audio is dropped (the padded segment margins keep a short silence at each join); the block
 * spans from its first start to its last end. A segment longer than [maxSamples] stays alone.
 */
class SpeechMerger(
    private val maxGapSamples: Long = 3L * 16_000,
    private val maxSamples: Int = 20 * 16_000,
) {
    init { require(maxGapSamples >= 0 && maxSamples > 0) }
    private val parts = ArrayList<MeetingSpeech>()
    private var size = 0
    fun add(speech: MeetingSpeech): List<MeetingSpeech> {
        val last = parts.lastOrNull()
        val gap = if (last == null) 0L else speech.startSample - (last.startSample + last.samples.size)
        val result = if (last != null && (gap > maxGapSamples || size + speech.samples.size > maxSamples)) flush() else emptyList()
        parts += speech; size += speech.samples.size
        return result
    }
    fun finish(): List<MeetingSpeech> = flush()
    private fun flush(): List<MeetingSpeech> {
        if (parts.isEmpty()) return emptyList()
        val first = parts.first()
        val last = parts.last()
        val samples = if (parts.size == 1) first.samples else FloatArray(size).also { joined ->
            var offset = 0
            for (part in parts) { part.samples.copyInto(joined, offset); offset += part.samples.size }
        }
        // The decode window is the joined audio; the journal interval is the real span, pauses included.
        val block = MeetingSpeech(first.startSample, samples, last.startSample + last.samples.size)
        parts.clear(); size = 0
        return listOf(block)
    }
}

/** Reads the spool directly, one small block / merged VAD segments / decoder stream at a time. */
class MeetingTranscriber(private val merger: () -> SpeechMerger = { SpeechMerger() }) {
    suspend fun transcribe(audio: File, journal: TranscriptJournal, decoder: MeetingDecoder,
        cancelled: () -> Boolean, progress: (Long) -> Unit = {}) {
        SavedPcm(audio).use { pcm ->
            check(pcm.tornBytes == 0L) { "Explicit recovery required" }
            var readSamples = 0L
            var lastEnd = 0L
            val blocks = merger()
            fun checkCancelled() { if (cancelled()) throw CancellationException("Meeting cancelled") }
            fun commit(segments: List<MeetingSpeech>) {
                for (segment in segments) {
                    checkCancelled()
                    val start = segment.startSample
                    val end = segment.endSample
                    check(segment.samples.isNotEmpty() && segment.samples.size <= 22 * 16_000 && end >= start + segment.samples.size)
                    check(start >= lastEnd && end <= pcm.samples && end <= readSamples) { "Invalid VAD sample positions" }
                    val text = decoder.recognize(segment.samples).trim()
                    // A decode already in JNI is allowed to finish and commit before cancellation is observed.
                    if (text.isNotEmpty()) {
                        val startMs = start * 1000 / 16_000
                        val endMs = end * 1000 / 16_000
                        check(endMs > startMs)
                        journal.append(startMs, endMs, text)
                    }
                    lastEnd = end
                    checkCancelled()
                }
            }
            while (true) {
                currentCoroutineContext().ensureActive(); checkCancelled()
                val block = pcm.read() ?: break
                readSamples += block.size
                commit(decoder.accept(block).flatMap(blocks::add))
                progress(readSamples * 1000 / 16_000)
            }
            checkCancelled()
            // Includes final partial VAD window, limited to real samples.
            commit(decoder.finish().flatMap(blocks::add) + blocks.finish())
            progress(pcm.samples * 1000 / 16_000)
        }
    }
}
