package be.arte.localdictate.audio

import kotlin.math.abs
import kotlin.math.pow

/**
 * Software automatic gain for dictation. The VOICE_RECOGNITION source applies no gain, so speech spoken softly
 * reaches Silero and Parakeet at a very low level and is missed or misread.
 *
 * Peaks are brought towards [targetPeak], with a gain between 1 (loud speech is never attenuated, so what already
 * works is unchanged) and [maxGain]. The peak envelope follows a louder chunk instantly and decays slowly, and the
 * gain is computed from that envelope *before* it is applied to the chunk, so an output peak never exceeds
 * [targetPeak]: no clipping. The gain rises slowly to avoid pumping the noise between words.
 */
class VoiceGain(
    private val targetPeak: Float = 0.5f,
    private val maxGain: Float = 8f,
    chunkSamples: Int = 160,
    releaseSeconds: Float = 1f,
    riseDbPerSecond: Float = 6f,
    rate: Int = 16_000,
) {
    init { require(targetPeak in 0f..1f && maxGain >= 1f && chunkSamples > 0 && rate > 0) }
    private val chunk = chunkSamples
    private val release = 0.5f.pow(chunkSamples.toFloat() / rate / releaseSeconds)
    private val rise = 10f.pow(riseDbPerSecond * chunkSamples / rate / 20f)
    private var envelope = 0f
    var gain = maxGain
        private set

    fun apply(samples: FloatArray): FloatArray {
        val output = FloatArray(samples.size)
        var offset = 0
        while (offset < samples.size) {
            val end = minOf(offset + chunk, samples.size)
            var peak = 0f
            for (i in offset until end) peak = maxOf(peak, abs(samples[i]))
            envelope = maxOf(peak, envelope * release)
            val wanted = if (envelope > 0f) (targetPeak / envelope).coerceIn(1f, maxGain) else maxGain
            gain = if (wanted < gain) wanted else minOf(wanted, gain * rise)
            for (i in offset until end) output[i] = (samples[i] * gain).coerceIn(-1f, 1f)
            offset = end
        }
        return output
    }
}
