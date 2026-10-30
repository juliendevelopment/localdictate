package be.arte.localdictate.session

import java.io.File
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class TranscriptJournalTest {
    @get:Rule val temporary = TemporaryFolder()
    private fun journal() = TranscriptJournal(File(temporary.root, "dictation/transcript.dat"))
    @Test fun recognizedTextSurvivesProcessRestartAndIsMarkedInterrupted() {
        val journal = journal()
        val id = journal.begin().id
        journal.append(id, "Bonjour")
        journal.append(id, "Hello.")
        val recovered = journal().read()
        assertEquals("Bonjour Hello.", recovered.text)
        assertTrue(recovered.interrupted)
        assertFalse(journal().claimInsertion(id))
    }
    @Test fun insertionClaimSurvivesRestartAndCannotBeDuplicated() {
        val journal = journal()
        val id = journal.begin().id
        journal.append(id, "Sélection originale")
        journal.finish(id, interrupted = false)
        assertTrue(journal.claimInsertion(id))
        assertFalse(journal().claimInsertion(id))
        assertTrue(journal().read().insertionAttempted)
        assertFalse(journal.claimInsertion("other-session"))
    }
    @Test fun previousTranscriptCannotBeSilentlyReplaced() {
        val journal = journal()
        journal.append(journal.begin().id, "À garder")
        assertThrows(IllegalStateException::class.java) { journal.begin() }
        assertEquals("À garder", journal.read().text)
        journal.clear()
        assertEquals("", journal.read().text)
        assertNotEquals("", journal.begin().id)
    }
    @Test fun interruptedCompletionAndEmptyTextNeverPaste() {
        val journal = journal()
        val id = journal.begin().id
        journal.finish(id, interrupted = false)
        assertFalse(journal.claimInsertion(id))
        journal.append(id, "Texte partiel")
        journal.finish(id, interrupted = true)
        assertFalse(journal.claimInsertion(id))
    }
    @Test fun stalePendingFileNeverReplacesAtomicRecoveryRecord() {
        val journal = journal()
        journal.append(journal.begin().id, "Conservé")
        File(temporary.root, "dictation/transcript.dat.pending").writeText("interrupted write")
        assertEquals("Conservé", journal().read().text)
    }
    @Test fun staleSessionCannotAppendOrFinish() {
        val journal = journal()
        journal.begin()
        assertThrows(IllegalStateException::class.java) { journal.append("old", "wrong") }
        assertThrows(IllegalStateException::class.java) { journal.finish("old", false) }
    }
    @Test fun preparationAndArmAreMutuallyExclusiveUntilOwnerReleases() {
        val gate = UseGate()
        assertTrue(gate.acquire(Use.PREPARATION))
        assertFalse(gate.acquire(Use.ARMED))
        assertFalse(gate.acquire(Use.PREPARATION))
        gate.release(Use.PREPARATION)
        assertTrue(gate.acquire(Use.ARMED))
        assertFalse(gate.acquire(Use.PREPARATION))
        gate.release(Use.ARMED)
        assertTrue(gate.acquire(Use.PREPARATION))
    }
}
