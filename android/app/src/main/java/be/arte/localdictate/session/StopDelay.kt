package be.arte.localdictate.session

/**
 * Time the microphone stays open after ✓, so the last words spoken just before the tap are still captured.
 * Stored in the app's private preferences (excluded from backup and device transfer).
 */
object StopDelay {
    const val DEFAULT_MS = 500L
    const val MAX_MS = 2_000L
    const val STEP_MS = 100L
    /** Any stored or slider value, rounded to the step and kept in 0..[MAX_MS]. */
    fun clamp(ms: Long): Long = ((ms + STEP_MS / 2) / STEP_MS * STEP_MS).coerceIn(0, MAX_MS)
    /** French label, for example « 0,5 s » or « aucun ». */
    fun label(ms: Long): String = if (ms <= 0) "aucun" else "${ms / 1000},${ms % 1000 / 100} s"
}
