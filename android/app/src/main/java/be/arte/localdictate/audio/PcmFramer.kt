package be.arte.localdictate.audio

import kotlin.math.sqrt

/** PCM conversion and VAD framing are independent of Android and JNI. */
object Pcm16 {
    fun floats(samples: ShortArray, count: Int = samples.size): FloatArray {
        require(count in 0..samples.size)
        return FloatArray(count) { samples[it] / 32768f }
    }

    fun level(samples: FloatArray): Float {
        if (samples.isEmpty()) return 0f
        var energy = 0.0
        for (sample in samples) energy += sample.toDouble() * sample
        return sqrt(energy / samples.size).toFloat().coerceIn(0f, 1f)
    }
}

/** Keeps partial reads; only finish() pads the final incomplete frame. */
class PcmFramer(private val frameSize: Int = 512) {
    init { require(frameSize > 0) }
    private var buffer = FloatArray(frameSize)
    private var used = 0
    var sampleCount: Long = 0
        private set

    fun accept(samples: FloatArray, emit: (FloatArray) -> Unit) {
        sampleCount += samples.size
        var offset = 0
        while (offset < samples.size) {
            val count = minOf(frameSize - used, samples.size - offset)
            samples.copyInto(buffer, used, offset, offset + count)
            used += count
            offset += count
            if (used == frameSize) {
                val frame = buffer
                buffer = FloatArray(frameSize)
                used = 0
                emit(frame)
            }
        }
    }

    fun finish(emit: (FloatArray) -> Unit) {
        if (used == 0) return
        val frame = buffer
        buffer = FloatArray(frameSize)
        used = 0
        emit(frame)
    }
}

/** A clock anchored to captured samples, never to delayed VAD/ASR execution. */
class SampleClock(val originMs: Long, private val rate: Int = 16_000) {
    init { require(rate > 0) }
    fun at(sample: Long): Long = originMs + sample.coerceAtLeast(0) * 1000L / rate
    fun duration(samples: Int): Long = samples.toLong() * 1000L / rate
}
