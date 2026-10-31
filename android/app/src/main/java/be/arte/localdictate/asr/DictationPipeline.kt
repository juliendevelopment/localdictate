package be.arte.localdictate.asr

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import java.util.concurrent.atomic.AtomicReference

/** Longest segment sent to the recognizer: VAD maximum (20 s) plus margins, with headroom. */
const val MAX_SEGMENT_SAMPLES = 25 * 16_000

/** Segmentation is replaceable in JVM fixtures; production uses only Silero. */
interface SpeechSegmenter {
    fun accept(samples: FloatArray): List<FloatArray>
    fun finish(): List<FloatArray>
}

class CaptureBudget(private val maxSamples: Long = 16_000L * 60 * 15) {
    var capturedSamples = 0L
        private set
    val exhausted get() = capturedSamples >= maxSamples
    fun accept(samples: FloatArray): FloatArray {
        val size = minOf(samples.size.toLong(), maxSamples - capturedSamples).toInt()
        capturedSamples += size
        return if (size == samples.size) samples else samples.copyOf(size)
    }
}

/** Two independent bounded FIFO queues. On overload stop capture, then drain, without silent loss. */
class DictationPipeline(
    private val segmenter: SpeechSegmenter,
    private val capture: suspend ((FloatArray) -> Unit) -> Unit,
    private val stopCapture: () -> Unit,
    private val recognize: suspend (FloatArray) -> String,
    private val onText: suspend (String) -> Unit,
    private val onDraining: () -> Unit,
    private val onError: (String) -> Unit,
    private val onDuration: (Long) -> Unit = {},
    private val pcmCapacity: Int = 64,
    private val segmentCapacity: Int = 8,
    private val budget: CaptureBudget = CaptureBudget(),
) {
    suspend fun run(): String? = supervisorScope {
        val pcm = Channel<FloatArray>(pcmCapacity)
        val segments = Channel<FloatArray>(segmentCapacity)
        val failure = AtomicReference<String?>(null)
        fun fail(message: String) {
            stopCapture()
            if (failure.compareAndSet(null, message)) onError(message)
        }
        val captureJob = launch(Dispatchers.IO) {
            try {
                capture { samples ->
                    val block = budget.accept(samples)
                    if (block.isNotEmpty() && !pcm.trySend(block).isSuccess) {
                        // The caller cannot suspend. Preserve this final block; stop AudioRecord first.
                        fail("Capture saturée : micro arrêté, traitement du son accepté.")
                        runBlocking { pcm.send(block) }
                    }
                    onDuration(budget.capturedSamples * 1000 / 16_000)
                    if (budget.exhausted) stopCapture()
                }
            } catch (error: CancellationException) { throw error }
            catch (_: Exception) { fail("Capture interrompue : vérifiez le microphone. Le texte reste disponible.") }
            finally { stopCapture(); pcm.close(); onDraining() }
        }
        val segmentationJob = launch(Dispatchers.Default) {
            suspend fun send(items: List<FloatArray>) {
                for (item in items) {
                    check(item.size <= MAX_SEGMENT_SAMPLES) { "Segment VAD trop long" }
                    if (!segments.trySend(item).isSuccess) {
                        fail("Transcription en retard : micro arrêté, vidage des segments acceptés.")
                        segments.send(item)
                    }
                }
            }
            try {
                for (block in pcm) send(segmenter.accept(block))
                send(segmenter.finish())
            } catch (error: CancellationException) { throw error }
            catch (_: Exception) {
                fail("Segmentation interrompue : une partie du son n’a pas été transcrite.")
                pcm.cancel()
            } finally { segments.close() }
        }
        val asrJob = launch(Dispatchers.Default) {
            try {
                for (segment in segments) {
                    val text = recognize(segment).trim()
                    if (text.isNotEmpty()) onText(text)
                }
            } catch (error: CancellationException) { throw error }
            catch (_: OutOfMemoryError) {
                fail("Mémoire insuffisante : transcription incomplète, texte déjà reconnu conservé.")
                pcm.cancel(); segments.cancel()
            } catch (_: Exception) {
                fail("Reconnaissance ou sauvegarde interrompue : transcription incomplète.")
                pcm.cancel(); segments.cancel()
            }
        }
        try { joinAll(captureJob, segmentationJob, asrJob) }
        finally { stopCapture(); pcm.cancel(); segments.cancel() }
        failure.get()
    }
}
