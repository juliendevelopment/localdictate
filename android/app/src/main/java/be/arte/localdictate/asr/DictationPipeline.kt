package be.arte.localdictate.asr

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/** Longest segment sent to the recognizer: split VAD maximum (20 s, see SpeechSplitter) plus margins, with headroom. */
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

/**
 * Two independent bounded FIFO queues. On overload stop capture, then drain, without silent loss.
 *
 * The PCM queue is bounded by audio duration, not by block count: the microphone is polled without blocking and
 * a block often holds a few milliseconds, so 64 blocks were well under a second of margin (the defect fixed for
 * meetings in v0.3.4). The segment queue is bounded by content (a segment is up to 25 s).
 */
class DictationPipeline(
    private val segmenter: SpeechSegmenter,
    private val capture: suspend ((FloatArray) -> Unit) -> Unit,
    private val stopCapture: () -> Unit,
    private val recognize: suspend (FloatArray) -> String,
    private val onText: suspend (String) -> Unit,
    private val onDraining: () -> Unit,
    private val onError: (String) -> Unit,
    private val onDuration: (Long) -> Unit = {},
    private val maxPendingSamples: Long = 5L * 16_000,
    private val segmentCapacity: Int = 8,
    private val budget: CaptureBudget = CaptureBudget(),
    /**
     * Runs alongside the capture (the session journal is opened here while the microphone starts). No text is
     * passed to [onText] before it succeeds; if it fails, the microphone is stopped and nothing is recognized.
     */
    private val start: suspend () -> Unit = {},
) {
    init { require(maxPendingSamples > 0 && segmentCapacity > 0) }
    suspend fun run(): String? = supervisorScope {
        // Unlimited in blocks, bounded in samples by the producer below.
        val pcm = Channel<FloatArray>(Channel.UNLIMITED)
        val pending = AtomicLong(0)
        val segments = Channel<FloatArray>(segmentCapacity)
        val failure = AtomicReference<String?>(null)
        fun fail(message: String) {
            stopCapture()
            if (failure.compareAndSet(null, message)) onError(message)
        }
        val started = async(Dispatchers.IO) {
            try { start(); true }
            catch (error: CancellationException) { throw error }
            catch (_: Exception) {
                fail("Démarrage de la dictée impossible : micro fermé, rien n’est inséré. Ouvrez LocalDictate.")
                pcm.cancel(); segments.cancel()
                false
            }
        }
        val captureJob = launch(Dispatchers.IO) {
            var reportedSecond = -1L
            try {
                capture { samples ->
                    val block = budget.accept(samples)
                    if (block.isNotEmpty()) {
                        // Stop AudioRecord first, then keep the block that crossed the limit: accepted audio is drained.
                        if (pending.addAndGet(block.size.toLong()) > maxPendingSamples)
                            fail("Capture saturée : micro arrêté, traitement du son accepté.")
                        pcm.trySend(block) // Never fails while open: the channel is unlimited.
                    }
                    // Once per displayed second: every change re-renders the bubble and the screen.
                    val second = budget.capturedSamples / 16_000
                    if (second != reportedSecond) { reportedSecond = second; onDuration(budget.capturedSamples * 1000 / 16_000) }
                    if (budget.exhausted) stopCapture()
                }
            } catch (error: CancellationException) { throw error }
            catch (_: Exception) { fail("Capture interrompue : vérifiez le microphone. Le texte reste disponible.") }
            finally {
                stopCapture(); pcm.close()
                onDuration(budget.capturedSamples * 1000 / 16_000) // Exact final value (statistics).
                onDraining()
            }
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
                for (block in pcm) { pending.addAndGet(-block.size.toLong()); send(segmenter.accept(block)) }
                send(segmenter.finish())
            } catch (error: CancellationException) { throw error }
            catch (_: Exception) {
                fail("Segmentation interrompue : une partie du son n’a pas été transcrite.")
                pcm.cancel()
            } finally { segments.close() }
        }
        val asrJob = launch(Dispatchers.Default) {
            try {
                if (!started.await()) return@launch
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
        try { joinAll(started, captureJob, segmentationJob, asrJob) }
        finally { stopCapture(); pcm.cancel(); segments.cancel() }
        failure.get()
    }
}
