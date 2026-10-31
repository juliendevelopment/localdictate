package be.arte.localdictate.asr

import be.arte.localdictate.audio.PcmFramer
import be.arte.localdictate.audio.SegmentPadder
import be.arte.localdictate.models.ModelFiles
import com.k2fsa.sherpa.onnx.*
import java.io.File

/** Handles are owned by one service; decode is serialized by the pipeline's sole ASR consumer. */
class LocalRecognizer(private val files: ModelFiles) : AutoCloseable {
    private var recognizer: OfflineRecognizer? = null
    fun load() {
        recognizer = OfflineRecognizer(assetManager = null, config = OfflineRecognizerConfig(
            featConfig = FeatureConfig(sampleRate = 16_000, featureDim = 128),
            modelConfig = OfflineModelConfig(
                transducer = OfflineTransducerModelConfig(
                    encoder = path("encoder.int8.onnx"), decoder = path("decoder.int8.onnx"),
                    joiner = path("joiner.int8.onnx")),
                tokens = path("tokens.txt"), modelType = "nemo_transducer", provider = "cpu",
                numThreads = 2, debug = false), decodingMethod = "greedy_search"))
    }
    fun recognize(samples: FloatArray): String {
        val decoder = checkNotNull(recognizer)
        val stream = decoder.createStream()
        return try {
            stream.acceptWaveform(samples, 16_000)
            decoder.decode(stream)
            // Result is a JVM value object, without a native release API (javap checked).
            decoder.getResult(stream).text
        } finally { stream.release() }
    }
    fun segmenter(): SileroSegmenter = SileroSegmenter(files.vadFile)
    override fun close() { recognizer?.release(); recognizer = null }
    private fun path(name: String) = File(files.parakeetDir, name).absolutePath
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
    override fun accept(samples: FloatArray): List<FloatArray> {
        val result = mutableListOf<FloatArray>()
        padder.write(samples)
        framer.accept(samples) { vad.acceptWaveform(it); drain(result) }
        return result
    }
    override fun finish(): List<FloatArray> {
        val result = mutableListOf<FloatArray>()
        framer.finish { vad.acceptWaveform(it); drain(result) }
        vad.flush()
        drain(result)
        return result
    }
    private fun drain(result: MutableList<FloatArray>) {
        while (!vad.empty()) {
            val speech = vad.front()
            vad.pop()
            val count = (minOf(speech.start + speech.samples.size, framer.sampleCount) - speech.start)
                .coerceAtLeast(0).toInt()
            check(count <= MAX_SEGMENT_SAMPLES) { "Segment VAD trop long" }
            // Real captured audio around the segment (never the zero padding of the last frame).
            if (count > 0) padder.widen(speech.start, count).takeIf { it.isNotEmpty() }?.let { result += it }
        }
    }
    override fun close() = vad.release()
}
