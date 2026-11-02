package be.arte.localdictate.meeting

import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.util.zip.CRC32
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Synthetic, explicitly labelled test text; not a user transcription or an ASR fixture. */
class TranscriptJournalTest {
    @get:Rule val temporary = TemporaryFolder(File(System.getenv("TMPDIR") ?: System.getProperty("java.io.tmpdir")))
    private val allow = StorageGuard { _, _ -> }

    @Test fun unicodeAndLiteralTraversalTextRoundTripWithoutBecomingPaths() {
        val file = File(temporary.root, "unicode.journal")
        val texts = listOf("TEST synthétique : café, naïf, œuf 🤝.\nDeuxième ligne.", "SYNTHETIC TEST: ../../escape.txt is text, not a path. We won't write it.")
        TranscriptJournal.create(file, allow).use { journal ->
            assertEquals(TranscriptSegment(1L, 0L, 1_000L, texts[0]), journal.append(0L, 1_000L, texts[0]))
            journal.append(1_000L, 2_000L, texts[1])
            assertEquals(2L, journal.segmentCount)
        }
        TranscriptJournal.openReader(file).use { reader ->
            val segments = mutableListOf<TranscriptSegment>()
            val scan = reader.scan { segments.add(it) }.requireComplete()
            assertEquals(texts, segments.map { it.text })
            assertEquals(listOf(1L, 2L), segments.map { it.id })
            assertEquals("segment-2", segments[1].anchor)
            assertEquals(2L, scan.segmentCount)
            assertEquals(2_000L, scan.lastEndMs)
            assertEquals(file.length(), scan.validBytes)
            assertEquals(0L, scan.tailBytes)
            assertEquals(scan, reader.scan())
        }
        assertEquals(listOf("unicode.journal"), temporary.root.listFiles()!!.map { it.name })
    }

    @Test fun everyPossibleTruncationOfLastFrameRecoversOnlyTheVerifiedPrefix() {
        val source = File(temporary.root, "source.journal")
        var prefixBytes = 0L
        TranscriptJournal.create(source, allow).use {
            it.append(0L, 100L, "SYNTHETIC TEST: prefix remains exact.")
            prefixBytes = source.length()
            it.append(100L, 200L, "TEST synthétique : dernier enregistrement é 🤝.")
        }
        val bytes = source.readBytes() // Small fixture only, never production reading.
        val tailLength = bytes.size - prefixBytes.toInt()
        for (keptTailBytes in 0 until tailLength) {
            val file = File(temporary.root, "cut-$keptTailBytes.journal")
            file.writeBytes(bytes.copyOf(prefixBytes.toInt() + keptTailBytes))
            val before = file.length()
            TranscriptJournal.openReader(file).use { reader ->
                val segments = mutableListOf<TranscriptSegment>()
                val scan = reader.scan { segments.add(it) }
                assertEquals(1L, scan.segmentCount)
                assertEquals(listOf("SYNTHETIC TEST: prefix remains exact."), segments.map { it.text })
                assertEquals(keptTailBytes.toLong(), scan.tailBytes)
                assertEquals(keptTailBytes == 0, scan.isComplete)
            }
            assertEquals(before, file.length())
            val recovered = TranscriptJournal.recover(file, allow, repairTail = true)
            assertEquals(keptTailBytes.toLong(), recovered.scanBeforeRepair.tailBytes)
            recovered.journal.use {
                assertEquals(prefixBytes, file.length())
                assertEquals(2L, it.append(100L, 200L, "SYNTHETIC TEST: explicit new record.").id)
            }
            TranscriptJournal.openReader(file).use { assertEquals(2L, it.scan().requireComplete().segmentCount) }
        }
    }

    @Test fun corruptTailRequiresExplicitRepairAndNeverInventsOrReplacesWords() {
        val file = File(temporary.root, "checksum.journal")
        var prefix = 0L
        TranscriptJournal.create(file, allow).use {
            it.append(0L, 100L, "TEST : Nous ne supprimons pas la négation.")
            prefix = file.length()
            it.append(100L, 200L, "TEST : suffixe non fiable.")
        }
        RandomAccessFile(file, "rw").use { it.seek(it.length() - 1L); it.writeByte(123) }
        val before = file.readBytes()
        val failure = assertThrows(JournalTailException::class.java) { TranscriptJournal.recover(file, allow) }
        assertEquals(JournalTailIssue.CHECKSUM, failure.scan.tailIssue)
        assertArrayEquals(before, file.readBytes())
        val recovered = TranscriptJournal.recover(file, allow, repairTail = true)
        assertEquals(prefix, recovered.scanBeforeRepair.validBytes)
        assertEquals(before.size.toLong() - prefix, recovered.scanBeforeRepair.tailBytes)
        recovered.journal.close()
        TranscriptJournal.openReader(file).use { reader ->
            val texts = mutableListOf<String>()
            assertEquals(1L, reader.scan { texts.add(it.text) }.requireComplete().segmentCount)
            assertEquals(listOf("TEST : Nous ne supprimons pas la négation."), texts)
        }
    }

    @Test fun aBadLengthCannotAllocateAnUnboundedRecord() {
        val file = File(temporary.root, "bad-length.journal")
        TranscriptJournal.create(file, allow).close()
        RandomAccessFile(file, "rw").use { it.seek(8L); it.writeInt(Int.MAX_VALUE) }
        TranscriptJournal.openReader(file).use {
            val report = it.scan()
            assertEquals(JournalTailIssue.INVALID_LENGTH, report.tailIssue)
            assertEquals(0L, report.segmentCount)
            assertEquals(4L, report.tailBytes)
        }
    }

    @Test fun correctChecksumDoesNotAllowInvalidUtf8OrOutOfOrderFrames() {
        val unicode = File(temporary.root, "invalid-utf8.journal")
        TranscriptJournal.create(unicode, allow).use { it.append(0L, 100L, "TEST.") }
        rewriteSingleFrame(unicode) { payload -> payload[28] = 0xFF.toByte() }
        TranscriptJournal.openReader(unicode).use { assertEquals(JournalTailIssue.INVALID_UTF8, it.scan().tailIssue) }
        val ordering = File(temporary.root, "invalid-order.journal")
        TranscriptJournal.create(ordering, allow).use { it.append(0L, 100L, "TEST.") }
        rewriteSingleFrame(ordering) { payload -> ByteBuffer.wrap(payload).putLong(2L) }
        TranscriptJournal.openReader(ordering).use { assertEquals(JournalTailIssue.INVALID_RECORD, it.scan().tailIssue) }
    }

    @Test fun invalidInputsAndNonMonotonicOrOverlappingSegmentsDoNotMutateJournal() {
        val file = File(temporary.root, "validation.journal")
        TranscriptJournal.create(file, allow).use { journal ->
            assertThrows(IllegalArgumentException::class.java) { journal.append(-1L, 1L, "TEST") }
            assertThrows(IllegalArgumentException::class.java) { journal.append(0L, 0L, "TEST") }
            assertThrows(IllegalArgumentException::class.java) { journal.append(0L, 7_200_001L, "TEST") }
            assertThrows(IllegalArgumentException::class.java) { journal.append(0L, 1L, " \n\t") }
            assertThrows(IllegalArgumentException::class.java) { journal.append(0L, 1L, "x".repeat(16_385)) }
            assertThrows(CharacterCodingException::class.java) { journal.append(0L, 1L, "TEST \uD800") }
            assertEquals(8L, file.length())
            journal.append(100L, 200L, "TEST synthetic.")
            val size = file.length()
            assertThrows(IllegalArgumentException::class.java) { journal.append(150L, 250L, "TEST overlap.") }
            assertThrows(IllegalArgumentException::class.java) { journal.append(0L, 100L, "TEST reversal.") }
            assertEquals(size, file.length())
            assertEquals(2L, journal.append(200L, 7_200_000L, "TEST at the exact end.").id)
        }
    }

    @Test fun acknowledgedSegmentsSurviveStorageRefusalAndReopening() {
        val file = File(temporary.root, "io.journal")
        var refuse = false
        val guard = StorageGuard { _, _ -> if (refuse) throw IOException("synthetic full storage") }
        TranscriptJournal.create(file, guard).use {
            it.append(0L, 100L, "TEST: durable first record.")
            val size = file.length()
            refuse = true
            assertThrows(IOException::class.java) { it.append(100L, 200L, "TEST: not accepted.") }
            assertEquals(size, file.length())
            assertEquals(1L, it.segmentCount)
        }
        TranscriptJournal.recover(file, allow).journal.use {
            assertEquals(2L, it.append(100L, 200L, "TEST: explicit retry.").id)
        }
    }

    @Test fun readerConsumerFailuresNeverRepairOrDeleteAnything() {
        val file = File(temporary.root, "consumer.journal")
        TranscriptJournal.create(file, allow).use { it.append(0L, 100L, "TEST: preserve the transcript.") }
        val before = file.readBytes()
        TranscriptJournal.openReader(file).use { reader ->
            assertThrows(IOException::class.java) { reader.scan { throw IOException("synthetic downstream failure") } }
            assertEquals(1L, reader.scan().requireComplete().segmentCount)
        }
        assertArrayEquals(before, file.readBytes())
    }

    @Test fun exclusiveWritersNoOverwriteAndReaderLocksPreventConcurrentRecovery() {
        val file = File(temporary.root, "locking.journal")
        TranscriptJournal.create(file, allow).use {
            assertThrows(IOException::class.java) { TranscriptJournal.create(file, allow) }
            assertThrows(IOException::class.java) { TranscriptJournal.recover(file, allow) }
            assertThrows(IOException::class.java) { TranscriptJournal.openReader(file) }
        }
        TranscriptJournal.openReader(file).use { assertThrows(IOException::class.java) { TranscriptJournal.recover(file, allow) } }
    }

    @Test fun interruptedHeaderFailsClosedAndMissingRecoveryDoesNotCreateAFile() {
        val file = File(temporary.root, "header.journal").apply { writeText("LDT") }
        assertThrows(IOException::class.java) { TranscriptJournal.recover(file, allow, repairTail = true) }
        assertEquals("LDT", file.readText())
        val missing = File(temporary.root, "missing.journal")
        assertThrows(IOException::class.java) { TranscriptJournal.recover(missing, allow) }
        assertFalse(missing.exists())
    }

    private fun rewriteSingleFrame(file: File, mutation: (ByteArray) -> Unit) {
        RandomAccessFile(file, "rw").use {
            it.seek(8L)
            val length = it.readInt()
            val payload = ByteArray(length); it.readFully(payload)
            mutation(payload)
            it.seek(12L); it.write(payload)
            it.writeInt(CRC32().apply { update(payload) }.value.toInt())
        }
    }
}
