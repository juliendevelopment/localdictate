package be.arte.localdictate.meeting

import org.junit.Assert.*
import org.junit.Test

class MeetingStopCauseTest {
    @Test fun eachOriginHasItsOwnDiagnostic() {
        val texts = MeetingStopCause.entries.map { cause -> FirstStopCause().also { it.record(cause) }.describe() }
        assertEquals(MeetingStopCause.entries.size, texts.toSet().size)
        texts.forEach { assertTrue(it.isNotBlank()) }
        val unknown = FirstStopCause().describe()
        assertFalse(unknown in texts)
        assertTrue(unknown.contains("sans cause enregistrée"))
    }
    @Test fun firstCauseIsKept() {
        // A timeout calls stopSelf(), which then destroys the service: the timeout must remain the cause.
        val cause = FirstStopCause()
        cause.record(MeetingStopCause.FOREGROUND_TIMEOUT)
        cause.record(MeetingStopCause.SERVICE_DESTROYED)
        assertEquals(MeetingStopCause.FOREGROUND_TIMEOUT, cause.value)
        assertEquals(MeetingStopCause.FOREGROUND_TIMEOUT.message, cause.describe())
    }
    @Test fun userStopFromTheAppAndFromTheNotificationAreDistinct() {
        assertNotEquals(MeetingStopCause.USER_APP.message, MeetingStopCause.USER_NOTIFICATION.message)
    }
}
