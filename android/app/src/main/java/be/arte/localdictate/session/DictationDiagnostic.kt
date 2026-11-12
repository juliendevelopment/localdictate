package be.arte.localdictate.session

import java.util.Locale
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.roundToInt

/**
 * Numbers only, never content: they tell whether a dictation loses speech at the capture (late first audio, low
 * peak), in the VAD (level but few or no segments) or in the recognizer (segments but wrong text). Kept in memory
 * for the current dictation and shown under « Durée capturée »; never persisted or logged.
 *
 * Times are `SystemClock.elapsedRealtime()` values, the clock of [tapMs] and of the captured audio origin.
 */
class DictationDiagnostic(private val tapMs: Long) {
    private var firstAudioMs: Long? = null
    private var peak = 0f
    private var lowestGain: Float? = null
    private var segments = 0
    private var speechSamples = 0L
    private var decodeMs = 0L

    /** One microphone block: [originMs] is the estimated time of the first captured sample, [raw] is before gain. */
    @Synchronized fun audio(originMs: Long, raw: FloatArray, gain: Float) {
        if (firstAudioMs == null) firstAudioMs = originMs
        for (sample in raw) peak = maxOf(peak, abs(sample))
        lowestGain = minOf(lowestGain ?: gain, gain)
    }

    /** One segment sent to the recognizer. */
    @Synchronized fun decoded(samples: Int, elapsedMs: Long) {
        segments++
        speechSamples += samples
        decodeMs += elapsedMs
    }

    @Synchronized fun line(): String {
        val parts = mutableListOf<String>()
        val first = firstAudioMs
        if (first == null) parts += "aucun son reçu du micro"
        else {
            parts += "micro ${(first - tapMs).coerceAtLeast(0)} ms après le toucher"
            parts += if (peak <= 0f) "crête nulle" else {
                val db = (-20 * log10(peak.toDouble())).roundToInt()
                if (db <= 0) "crête 0 dBFS" else "crête −$db dBFS"
            }
            lowestGain?.let { parts += "gain ×${decimal(it.toDouble(), 1)}" }
        }
        val speechMs = speechSamples * 1000 / 16_000
        parts += "$segments segment${if (segments > 1) "s" else ""}, ${decimal(speechMs / 1000.0, 1)} s"
        if (segments > 0 && speechMs > 0) {
            parts += "décodage ${decimal(decodeMs / 1000.0, 1)} s (×${decimal(decodeMs.toDouble() / speechMs, 2)})"
        }
        return "Diagnostic : " + parts.joinToString(" • ")
    }

    private fun decimal(value: Double, digits: Int) = String.format(Locale.FRENCH, "%.${digits}f", value)
}
