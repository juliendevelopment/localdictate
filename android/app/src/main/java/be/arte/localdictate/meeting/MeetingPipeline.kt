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

data class MeetingSpeech(val startSample: Long, val samples: FloatArray)
interface MeetingDecoder : AutoCloseable {
    fun accept(samples: FloatArray): List<MeetingSpeech>
    fun finish(): List<MeetingSpeech>
    fun recognize(samples: FloatArray): String
}

/** Reads the spool directly, one small block / bounded VAD segment / decoder stream at a time. */
class MeetingTranscriber {
    suspend fun transcribe(audio: File, journal: TranscriptJournal, decoder: MeetingDecoder,
        cancelled: () -> Boolean, progress: (Long) -> Unit = {}) {
        SavedPcm(audio).use { pcm ->
            check(pcm.tornBytes == 0L) { "Explicit recovery required" }
            var readSamples = 0L
            var lastEnd = 0L
            fun checkCancelled() { if (cancelled()) throw CancellationException("Meeting cancelled") }
            fun commit(segments: List<MeetingSpeech>) {
                for (segment in segments) {
                    checkCancelled()
                    val start = segment.startSample
                    val end = start + segment.samples.size.toLong()
                    check(segment.samples.isNotEmpty() && segment.samples.size <= 22 * 16_000)
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
                commit(decoder.accept(block))
                progress(readSamples * 1000 / 16_000)
            }
            checkCancelled()
            commit(decoder.finish()) // Includes final partial VAD window, limited to real samples.
            progress(pcm.samples * 1000 / 16_000)
        }
    }
}

fun writeSummary(summary: ExtractiveSummary, out: Appendable) {
    out.append("# Résumé extractif local\n\nLangue de sélection : ${summary.language.name}. Aucune traduction.\n")
    out.append("Passages originaux, sans reformulation ni classification des décisions/actions. Les temps sont ceux des segments, pas des mots.\n\n")
    for (sentence in summary.sentences) {
        out.append("- [${sentence.segmentStartMs / 1000}–${sentence.segmentEndMs / 1000} s, ${sentence.sourceAnchor}]")
            .append("(#${sentence.sourceAnchor}) : ").append(sentence.text).append('\n')
    }
    out.append("\n${summary.source.segmentCount} segments, ${summary.sentencesExamined} phrases examinées ; ")
        .append("${summary.omittedEligibleSentences} phrases admissibles omises ; ${summary.oversizedSentences} phrases trop longues exclues.\n")
    for (part in summary.coverage) out.append("Partie ${part.index + 1} : ${part.startMs / 1000}–${part.endMs / 1000} s, ")
        .append("${part.sourceSegments} segments, ${part.selectedSentences} extraits / ${part.eligibleSentences} phrases admissibles.\n")
    out.append("Parties avec texte sans extrait : ${summary.uncoveredPartIndexes.map { it + 1 }}. Consultez la transcription complète pour le contexte.\n")
}
