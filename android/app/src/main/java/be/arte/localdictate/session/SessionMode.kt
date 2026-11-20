package be.arte.localdictate.session

/** Product limits are independent from capture and model runtimes. */
enum class SessionMode(
    val maxDurationMs: Long,
    val allowsCursorInsertion: Boolean,
) {
    DICTATION(15L * 60L * 1_000L, true),
    MEETING(2L * 60L * 60L * 1_000L, false),
}

class SessionBudget(private val mode: SessionMode) {
    /** Callers provide monotonic elapsed time, never wall-clock timestamps. */
    fun remainingMs(elapsedMs: Long): Long {
        require(elapsedMs >= 0) { "Elapsed time must be non-negative" }
        return if (elapsedMs >= mode.maxDurationMs) 0 else mode.maxDurationMs - elapsedMs
    }

    fun isExhausted(elapsedMs: Long): Boolean = remainingMs(elapsedMs) == 0L
}

/**
 * The bubble turns red during the last minute of a dictation; at the limit the capture stops by itself and the text
 * follows the same path as a tap on ✓ (history, then insertion or clipboard). Durations are captured audio, in ms.
 */
object DictationLimit {
    const val WARNING_MS = 14L * 60L * 1_000L
    val MAX_MS = SessionMode.DICTATION.maxDurationMs

    fun warns(durationMs: Long): Boolean = durationMs in WARNING_MS until MAX_MS
    fun reached(durationMs: Long): Boolean = durationMs >= MAX_MS
    fun remainingMs(durationMs: Long): Long = SessionBudget(SessionMode.DICTATION).remainingMs(durationMs.coerceAtLeast(0))
}
