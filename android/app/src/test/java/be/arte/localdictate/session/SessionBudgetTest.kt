package be.arte.localdictate.session

import org.junit.Assert.*
import org.junit.Test

class SessionBudgetTest {
    @Test fun dictationIsLimitedToFifteenMinutes() {
        assertEquals(900_000L, SessionMode.DICTATION.maxDurationMs)
    }
    @Test fun meetingIsLimitedToTwoHours() {
        assertEquals(7_200_000L, SessionMode.MEETING.maxDurationMs)
    }
    @Test fun onlyDictationCanInsertAtCursor() {
        assertTrue(SessionMode.DICTATION.allowsCursorInsertion)
        assertFalse(SessionMode.MEETING.allowsCursorInsertion)
    }
    @Test fun onlyMeetingRequestsSummary() {
        assertFalse(SessionMode.DICTATION.requestsSummary)
        assertTrue(SessionMode.MEETING.requestsSummary)
    }
    @Test fun initialBudgetMatchesTheMode() {
        for (mode in SessionMode.entries) {
            val budget = SessionBudget(mode)
            assertEquals(mode.maxDurationMs, budget.remainingMs(0))
            assertFalse(budget.isExhausted(0))
        }
    }
    @Test fun budgetStopsAtExactLimitWithoutGoingNegative() {
        for (mode in SessionMode.entries) {
            val budget = SessionBudget(mode)
            assertEquals(1L, budget.remainingMs(mode.maxDurationMs - 1))
            assertFalse(budget.isExhausted(mode.maxDurationMs - 1))
            assertTrue(budget.isExhausted(mode.maxDurationMs))
            assertEquals(0L, budget.remainingMs(mode.maxDurationMs + 1))
            assertEquals(0L, budget.remainingMs(Long.MAX_VALUE))
        }
    }
    @Test fun budgetsRemainIndependent() {
        assertTrue(SessionBudget(SessionMode.DICTATION).isExhausted(900_000L))
        assertEquals(6_300_000L, SessionBudget(SessionMode.MEETING).remainingMs(900_000L))
    }
    @Test fun negativeElapsedTimeIsRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            SessionBudget(SessionMode.DICTATION).remainingMs(-1)
        }
    }
}
