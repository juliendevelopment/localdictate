package be.arte.localdictate.session

/** Product limits are independent from capture and model runtimes. */
enum class SessionMode(
    val maxDurationMs: Long,
    val allowsCursorInsertion: Boolean,
    val requestsSummary: Boolean,
) {
    DICTATION(15L * 60L * 1_000L, true, false),
    MEETING(2L * 60L * 60L * 1_000L, false, true),
}

class SessionBudget(private val mode: SessionMode) {
    /** Callers provide monotonic elapsed time, never wall-clock timestamps. */
    fun remainingMs(elapsedMs: Long): Long {
        require(elapsedMs >= 0) { "Elapsed time must be non-negative" }
        return if (elapsedMs >= mode.maxDurationMs) 0 else mode.maxDurationMs - elapsedMs
    }

    fun isExhausted(elapsedMs: Long): Boolean = remainingMs(elapsedMs) == 0L
}
