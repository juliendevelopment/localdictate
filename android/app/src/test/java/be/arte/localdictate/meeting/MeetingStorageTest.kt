package be.arte.localdictate.meeting

import java.io.File
import java.io.IOException
import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class MeetingStorageTest {
    @get:Rule val temporary = TemporaryFolder(File(System.getenv("TMPDIR") ?: System.getProperty("java.io.tmpdir")))
    private val allow = StorageGuard { _, _ -> }

    @Test fun invalidReservesAndRequestsCannotOverflowTheStorageGuard() {
        val file = File(temporary.root, "guard-test.pcm")
        assertThrows(IllegalArgumentException::class.java) { ReservedSpaceGuard(-1L) }
        assertThrows(IllegalArgumentException::class.java) { ReservedSpaceGuard(0L).requireSpace(file, -1L) }
        assertThrows(IOException::class.java) { ReservedSpaceGuard(Long.MAX_VALUE).requireSpace(file, 0L) }
        assertThrows(IOException::class.java) { ReservedSpaceGuard(0L).requireSpace(file, Long.MAX_VALUE) }
        assertFalse(file.exists())
    }

    @Test fun aStorageRefusalDuringCreationNeverCreatesAnArtifact() {
        val refused = StorageGuard { _, _ -> throw IOException("synthetic storage refusal") }
        val pcm = File(temporary.root, "refused.pcm")
        val journal = File(temporary.root, "refused.journal")
        assertThrows(IOException::class.java) { PcmRecording.create(pcm, refused) }
        assertThrows(IOException::class.java) { TranscriptJournal.create(journal, refused) }
        assertFalse(pcm.exists())
        assertFalse(journal.exists())
    }

    @Test fun relativePathsAndMissingParentsAreRefusedWithoutCreatingDirectories() {
        val relative = File("synthetic-relative-path.pcm")
        assertThrows(IllegalArgumentException::class.java) { PcmRecording.create(relative, allow) }
        assertThrows(IllegalArgumentException::class.java) { TranscriptJournal.create(relative, allow) }
        val missing = File(temporary.root, "not-created/audio.pcm")
        assertThrows(IllegalArgumentException::class.java) { PcmRecording.create(missing, allow) }
        assertFalse(checkNotNull(missing.parentFile).exists())
    }

    @Test fun aSymlinkCannotReplaceAnExistingRecordingOrRedirectRecovery() {
        val real = File(temporary.root, "real.pcm")
        PcmRecording.create(real, allow).use { it.append(byteArrayOf(1, 2)) }
        val link = File(temporary.root, "link.pcm")
        Files.createSymbolicLink(link.toPath(), real.toPath())
        assertThrows(IOException::class.java) { PcmRecording.create(link, allow) }
        assertThrows(IOException::class.java) { PcmRecording.recover(link, allow) }
        assertEquals(34L, real.length())
        PcmRecording.recover(real, allow).recording.use { assertEquals(2L, it.pcmBytes) }
    }

    @Test fun transcriptReaderAndRecoveryNeverFollowTheFinalSymlink() {
        val real = File(temporary.root, "real.journal")
        TranscriptJournal.create(real, allow).use { it.append(0L, 1L, "SYNTHETIC TEST.") }
        val before = real.readBytes()
        val link = File(temporary.root, "link.journal")
        Files.createSymbolicLink(link.toPath(), real.toPath())
        assertThrows(IOException::class.java) { TranscriptJournal.create(link, allow) }
        assertThrows(IOException::class.java) { TranscriptJournal.openReader(link) }
        assertThrows(IOException::class.java) { TranscriptJournal.recover(link, allow, repairTail = true) }
        assertArrayEquals(before, real.readBytes())
    }
}
