package be.arte.localdictate.session

import java.io.File
import java.time.LocalDate
import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue

class DictationStatsTest {
    private fun stats(limit: Int = 52): DictationStats =
        DictationStats(File.createTempFile("stats", ".dat").also { it.delete() }, limit)
    @Test fun countsWordsNotPunctuation() {
        assertEquals(6, DictationStats.countWords("Bonjour, c’est l'été ! Hello world-wide 2026 ?"))
        assertEquals(0, DictationStats.countWords("  … ! "))
    }
    @Test fun sumsDictationsOfTheSameWeekStartingMonday() {
        val s = stats()
        s.add("un deux trois", 10_000, LocalDate.of(2026, 10, 26)) // Monday
        s.add("quatre cinq", 5_000, LocalDate.of(2026, 11, 1))      // Sunday, same week
        s.add("six", 1_000, LocalDate.of(2026, 11, 2))              // next Monday
        assertEquals(listOf(
            WeekStats(LocalDate.of(2026, 11, 2), 1, 1_000, 1),
            WeekStats(LocalDate.of(2026, 10, 26), 5, 15_000, 2),
        ), s.read())
    }
    @Test fun keepsOnlyTheLatestWeeksAndClears() {
        val s = stats(limit = 3)
        (0L..4L).forEach { s.add("mot", 1_000, LocalDate.of(2026, 1, 5).plusWeeks(it)) }
        assertEquals(listOf(LocalDate.of(2026, 2, 2), LocalDate.of(2026, 1, 26), LocalDate.of(2026, 1, 19)),
            s.read().map { it.monday })
        s.clear()
        assertTrue(s.read().isEmpty())
    }
}
