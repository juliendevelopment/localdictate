package be.arte.localdictate.meeting

import be.arte.localdictate.audio.VoiceGain

/**
 * Automatic gain applied when a meeting is **transcribed**, never to the recorded audio: the private spool stays raw,
 * so « Retranscrire » can try another setting on the same recording. The value is the ceiling of [VoiceGain]
 * (the dictation's automatic gain: never attenuates, never clips). 0 dB keeps the raw audio, as before v0.3.11.
 */
object MeetingGain {
    val CHOICES_DB = listOf(0, 6, 12, 18)
    const val DEFAULT_DB = 0
    /** Any stored value, snapped to the nearest choice. */
    fun clamp(db: Int): Int = CHOICES_DB.minBy { kotlin.math.abs(it - db) }
    fun label(db: Int): String = if (clamp(db) == 0) "aucun" else "+${clamp(db)} dB"
    /** Null when no gain is wanted, so the raw samples go through untouched. */
    fun create(db: Int): VoiceGain? = when (clamp(db)) {
        0 -> null
        6 -> VoiceGain(maxGain = 2f)
        12 -> VoiceGain(maxGain = 4f)
        else -> VoiceGain(maxGain = 8f)
    }
}
