package be.arte.localdictate.session

import java.io.File
import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue

class DictationHistoryTest {
    private fun history(): DictationHistory =
        DictationHistory(File.createTempFile("history", ".dat").also { it.delete() })
    @Test fun keepsTheFiveLatestNewestFirstAndSkipsBlank() {
        val h = history()
        (1..7).forEach { h.add("texte $it") }
        h.add("   ")
        assertEquals(listOf("texte 7", "texte 6", "texte 5", "texte 4", "texte 3"), h.read())
    }
    @Test fun clearEmptiesTheHistory() {
        val h = history()
        h.add("bonjour")
        h.clear()
        assertTrue(h.read().isEmpty())
    }
}
