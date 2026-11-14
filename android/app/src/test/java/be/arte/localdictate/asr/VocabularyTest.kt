package be.arte.localdictate.asr

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VocabularyTest {
    @Test fun linesAreHotwordsOrReplacements() {
        val vocabulary = Vocabulary.parse("""
            # mes mots
            Parakeet

              Paseo
            para kit => Parakeet
            local dictée=>LocalDictate
        """.trimIndent())
        assertEquals(listOf("Parakeet", "Paseo", "LocalDictate"), vocabulary.hotwords)
        assertEquals(listOf(Vocabulary.Replacement("para kit", "Parakeet"),
            Vocabulary.Replacement("local dictée", "LocalDictate")), vocabulary.replacements)
        assertEquals("Parakeet/Paseo/LocalDictate", vocabulary.streamHotwords)
    }

    @Test fun replacementsMatchWholeWordsIgnoringCaseAndSpacing() {
        val vocabulary = Vocabulary.parse("para kit => Parakeet\npara => PARA\nkit => Kit")
        assertEquals("Le modèle Parakeet marche, Parakeet aussi.", vocabulary.apply("Le modèle para kit marche, Para   kit aussi."))
        // Never inside a longer word.
        assertEquals("parapluie et kitesurf", vocabulary.apply("parapluie et kitesurf"))
        assertEquals("PARA puis Kit.", vocabulary.apply("para puis kit."))
        assertEquals("rien à changer", vocabulary.apply("rien à changer"))
    }

    @Test fun replacementTextIsLiteralEvenWithRegexCharacters() {
        val vocabulary = Vocabulary.parse("c plus plus => C++ (\$1)\na.b => x")
        assertEquals("du C++ (\$1) et aXb", vocabulary.apply("du c plus plus et aXb"))
        assertEquals("x", vocabulary.apply("a.b"))
    }

    @Test fun unusableLinesAreSkippedAndSeparatorsNeverReachTheEngine() {
        val vocabulary = Vocabulary.parse("=> rien\nrien =>\n" + "x".repeat(61) + "\nTCP/IP\nmot :3\nmot => mot")
        assertEquals(listOf("TCP IP", "mot 3", "mot"), vocabulary.hotwords)
        assertTrue(vocabulary.replacements.isEmpty())
        assertNull(Vocabulary.parse("  \n# seul commentaire").streamHotwords)
        assertTrue(Vocabulary.parse("").isEmpty)
    }

    @Test fun entryCountIsBounded() {
        val vocabulary = Vocabulary.parse((1..400).joinToString("\n") { "mot$it" })
        assertEquals(Vocabulary.MAX_ENTRIES, vocabulary.hotwords.size)
    }

    @Test fun storeKeepsTheTextAsTypedAndSurvivesAMissingFile() {
        val file = File.createTempFile("vocabulary", ".txt").also { it.delete() }
        val store = VocabularyStore(File(file, "sub/vocabulary.txt"))
        assertEquals("", store.read())
        assertTrue(store.load().isEmpty)
        store.write("Parakeet\npara kit => Parakeet\n")
        assertEquals("Parakeet\npara kit => Parakeet\n", store.read())
        assertEquals(listOf("Parakeet"), store.load().hotwords)
    }
}
