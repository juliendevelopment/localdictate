package be.arte.localdictate

import be.arte.localdictate.meeting.MeetingPhase
import org.junit.Assert.*
import org.junit.Test

class FormatTest {
    @Test fun durations() {
        assertEquals("0 s", formatDuration(0))
        assertEquals("45 s", formatDuration(45_999))
        assertEquals("1 min 00", formatDuration(60_000))
        assertEquals("12 min 05", formatDuration(725_000))
        assertEquals("15 min 00", formatDuration(900_000))
        assertEquals("1 h 03 min", formatDuration(3_784_000))
        assertEquals("2 h 00 min", formatDuration(7_200_000))
    }
    @Test fun clockPositions() {
        assertEquals("0:12", formatClock(12_400))
        assertEquals("4:07", formatClock(247_000))
        assertEquals("1:02:09", formatClock(3_729_000))
    }
    @Test fun everyMeetingPhaseIsShownInFrench() {
        val labels = MeetingPhase.entries.map(::meetingPhaseLabel)
        labels.zip(MeetingPhase.entries).forEach { (label, phase) -> assertFalse(label.contains(phase.name)) }
        assertEquals(MeetingPhase.entries.size, labels.toSet().size)
    }
}
