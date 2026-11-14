package be.arte.localdictate.asr

import be.arte.localdictate.audio.PcmFramer
import be.arte.localdictate.audio.SegmentPadder
import be.arte.localdictate.audio.SpeechSplitter
import be.arte.localdictate.audio.TimedSpeech
import be.arte.localdictate.models.ModelFiles
import com.k2fsa.sherpa.onnx.*
import java.io.File

/**
 * Handles are owned by one service; decode is serialized by the pipeline's sole ASR consumer.
 *
 * Modified beam search (4 paths) instead of greedy search, so the user's hotwords can bias decoding per stream
 * without reloading the model. Measured on the host with this model and sherpa-onnx 2.0.1 (14 November 2026):
 * same text on the FR/EN samples without hotwords, about 15 % more decode time.
 */
class LocalRecognizer(private val files: ModelFiles, private val bpeVocab: File) : AutoCloseable {
    private var recognizer: OfflineRecognizer? = null
    fun load() {
        writeBpeVocab(File(files.parakeetDir, "tokens.txt"), bpeVocab)
        recognizer = OfflineRecognizer(assetManager = null, config = OfflineRecognizerConfig(
            featConfig = FeatureConfig(sampleRate = 16_000, featureDim = 128),
            modelConfig = OfflineModelConfig(
                transducer = OfflineTransducerModelConfig(
                    encoder = path("encoder.int8.onnx"), decoder = path("decoder.int8.onnx"),
                    joiner = path("joiner.int8.onnx")),
                tokens = path("tokens.txt"), modelType = "nemo_transducer", provider = "cpu",
                numThreads = 2, debug = false, modelingUnit = "bpe", bpeVocab = bpeVocab.absolutePath),
            decodingMethod = "modified_beam_search", maxActivePaths = 4,
            // Default strength: stronger values were seen forcing a hotword over unrelated speech.
            hotwordsScore = HOTWORDS_SCORE))
    }
    fun recognize(samples: FloatArray, vocabulary: Vocabulary = Vocabulary.EMPTY): String {
        val decoder = checkNotNull(recognizer)
        val stream = vocabulary.streamHotwords?.let { decoder.createStream(it) } ?: decoder.createStream()
        return try {
            stream.acceptWaveform(samples, 16_000)
            decoder.decode(stream)
            // Result is a JVM value object, without a native release API (javap checked).
            vocabulary.apply(decoder.getResult(stream).text)
        } finally { stream.release() }
    }
    fun segmenter(): SileroSegmenter = SileroSegmenter(files.vadFile)
    override fun close() { recognizer?.release(); recognizer = null }
    private fun path(name: String) = File(files.parakeetDir, name).absolutePath

    companion object {
        const val HOTWORDS_SCORE = 1.5f

        /**
         * The export ships no SentencePiece model, only `tokens.txt`. Hotwords are split into model pieces with this
         * derived vocabulary: one equal score per piece, so a word is cut into the fewest known pieces (for example
         * "Parakeet" → ▁Par ake et). Rewritten only when it differs; it holds no user data.
         */
        fun writeBpeVocab(tokens: File, target: File) {
            val content = tokens.readLines(Charsets.UTF_8).mapNotNull { line ->
                line.substringBeforeLast(' ', "").takeIf { it.isNotEmpty() }
            }.joinToString("") { "$it\t-1\n" }
            if (target.isFile && target.readText(Charsets.UTF_8) == content) return
            check(target.parentFile!!.isDirectory || target.parentFile!!.mkdirs())
            val pending = File(target.parentFile, target.name + ".pending")
            pending.writeText(content, Charsets.UTF_8)
            check(pending.renameTo(target)) { "bpe.vocab" }
        }
    }
}

class SileroSegmenter(file: File) : SpeechSegmenter, AutoCloseable {
    private val vad = Vad(assetManager = null, config = VadModelConfig(
        sileroVadModelConfig = SileroVadModelConfig(model = file.absolutePath,
            threshold = 0.5f, minSilenceDuration = 0.4f, minSpeechDuration = 0.25f,
            // Long enough that continuous speech is rarely cut inside a word; Parakeet decodes 20 s easily.
            windowSize = 512, maxSpeechDuration = 20f),
        sampleRate = 16_000, numThreads = 1, provider = "cpu", debug = false))
    private val framer = PcmFramer()
    private val padder = SegmentPadder()
    override fun accept(samples: FloatArray): List<FloatArray> = acceptTimed(samples).map { it.samples }
    fun acceptTimed(samples: FloatArray): List<TimedSpeech> {
        val result = mutableListOf<TimedSpeech>()
        padder.write(samples)
        framer.accept(samples) { vad.acceptWaveform(it); drain(result) }
        return result
    }
    override fun finish(): List<FloatArray> = finishTimed().map { it.samples }
    fun finishTimed(): List<TimedSpeech> {
        val result = mutableListOf<TimedSpeech>()
        framer.finish { vad.acceptWaveform(it); drain(result) }
        vad.flush()
        drain(result)
        return result
    }
    private fun drain(result: MutableList<TimedSpeech>) {
        while (!vad.empty()) {
            val speech = vad.front()
            vad.pop()
            val count = (minOf(speech.start + speech.samples.size, framer.sampleCount) - speech.start)
                .coerceAtLeast(0).toInt()
            // Real captured audio around the segment (never the zero padding of the last frame). Segments longer
            // than the VAD maximum do happen on continuous speech; they are split, never rejected.
            var offset = 0
            for (length in SpeechSplitter.lengths(speech.samples, count)) {
                padder.widenTimed(speech.start.toLong() + offset, length)?.let { result += it }
                offset += length
            }
        }
    }
    override fun close() = vad.release()
}
