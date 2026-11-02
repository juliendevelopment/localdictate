package be.arte.localdictate.meeting

import java.io.Closeable
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.util.zip.CRC32

/** Segment timestamps refer to the audio, not a fabricated word/sentence alignment. */
data class TranscriptSegment(val id: Long, val startMs: Long, val endMs: Long, val text: String) {
    val anchor: String get() = "segment-$id"
}

enum class JournalTailIssue { INCOMPLETE_FRAME, INVALID_LENGTH, CHECKSUM, INVALID_RECORD, INVALID_UTF8 }

data class JournalScan(
    val segmentCount: Long,
    val textCharacters: Long,
    val lastEndMs: Long,
    val validBytes: Long,
    val tailBytes: Long,
    val tailIssue: JournalTailIssue?,
) {
    val isComplete: Boolean get() = tailIssue == null
    fun requireComplete(): JournalScan {
        if (!isComplete) throw JournalTailException(this)
        return this
    }
}

class JournalTailException(val scan: JournalScan) : IOException("Unverified transcript tail: ${scan.tailIssue}, ${scan.tailBytes} bytes")
data class JournalRecovery(val journal: TranscriptJournal, val scanBeforeRepair: JournalScan)

/**
 * Append-only, UTF-8, CRC-framed transcript. Every acknowledged segment has passed force(true).
 * Thread-confined/synchronous; no audio/text is logged. Storage failures never trigger deletion.
 * An I/O failure poisons the writer; recover explicitly instead of reusing it.
 */
class TranscriptJournal private constructor(
    val file: File,
    private val storage: StorageGuard,
    private val handle: LockedMeetingFile,
    initialCount: Long,
    initialEndMs: Long,
) : Closeable {
    var segmentCount: Long = initialCount
        private set
    private var lastEndMs = initialEndMs
    private var closed = false
    private var failed = false

    @Synchronized
    fun append(startMs: Long, endMs: Long, text: String): TranscriptSegment {
        check(!closed && !failed) { "Journal is closed or failed" }
        require(segmentCount < MAX_SEGMENTS) { "Journal segment limit reached; transcript was not truncated" }
        require(startMs >= lastEndMs && startMs >= 0L && endMs > startMs && endMs <= PcmRecording.MAX_DURATION_MS) {
            "Segments must be ordered, non-overlapping and within the two-hour audio interval"
        }
        require(text.length <= MAX_TEXT_CHARACTERS && text.isNotBlank()) { "Empty or oversized transcript segment" }
        val encoded = Charsets.UTF_8.newEncoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).encode(java.nio.CharBuffer.wrap(text))
        require(encoded.remaining() <= MAX_TEXT_BYTES)
        val payload = ByteBuffer.allocate(RECORD_METADATA_BYTES + encoded.remaining())
        payload.putLong(segmentCount + 1L).putLong(startMs).putLong(endMs).putInt(encoded.remaining()).put(encoded)
        val crc = CRC32().apply { update(payload.array()) }.value.toInt()
        val frame = ByteBuffer.allocate(payload.capacity() + 8)
        frame.putInt(payload.capacity()).put(payload.array()).putInt(crc).flip()
        storage.requireSpace(file, frame.remaining().toLong())
        try {
            handle.channel.position(handle.channel.size())
            handle.channel.writeCompletely(frame)
            handle.channel.force(true)
        } catch (failure: IOException) {
            failed = true
            throw failure
        }
        segmentCount += 1L
        lastEndMs = endMs
        return TranscriptSegment(segmentCount, startMs, endMs, text)
    }

    @Synchronized
    override fun close() {
        if (!closed) {
            closed = true
            handle.close()
        }
    }

    companion object {
        const val HEADER_BYTES = 8
        const val RECORD_METADATA_BYTES = 28
        const val MAX_TEXT_CHARACTERS = 16_384
        const val MAX_TEXT_BYTES = 65_536
        const val MAX_SEGMENTS = 100_000L
        private val MAGIC = "LDTXT001".toByteArray(Charsets.US_ASCII)

        fun create(file: File, storage: StorageGuard): TranscriptJournal {
            storage.requireSpace(file, HEADER_BYTES.toLong())
            val handle = LockedMeetingFile.create(file)
            try {
                handle.channel.writeCompletely(ByteBuffer.wrap(MAGIC))
                handle.channel.force(true)
                return TranscriptJournal(file, storage, handle, 0L, 0L)
            } catch (failure: Throwable) {
                handle.close()
                throw failure
            }
        }

        /**
         * Validate all records with bounded memory. By default, a damaged tail is reported, not modified.
         * repairTail=true explicitly discards the UNVERIFIED suffix from the first invalid frame onward.
         * The verified prefix is kept exactly; no scanning ahead, replacement words or guessed records.
         */
        fun recover(file: File, storage: StorageGuard, repairTail: Boolean = false): JournalRecovery {
            val handle = LockedMeetingFile.existing(file)
            try {
                validateHeader(handle)
                val scan = scan(handle, handle.channel.size()) { }
                if (!scan.isComplete) {
                    if (!repairTail) throw JournalTailException(scan)
                    handle.channel.truncate(scan.validBytes)
                    handle.channel.force(true)
                }
                return JournalRecovery(TranscriptJournal(file, storage, handle, scan.segmentCount, scan.lastEndMs), scan)
            } catch (failure: Throwable) {
                handle.close()
                throw failure
            }
        }

        /** Reader holds a shared lock across every scan, including the summary's two passes. */
        fun openReader(file: File): TranscriptReader {
            val handle = LockedMeetingFile.existing(file, readOnly = true)
            try {
                validateHeader(handle)
                return TranscriptReader(handle, handle.channel.size())
            } catch (failure: Throwable) {
                handle.close()
                throw failure
            }
        }

        private fun validateHeader(handle: LockedMeetingFile) {
            if (handle.channel.size() < HEADER_BYTES) throw IOException("Incomplete transcript header")
            handle.channel.position(0L)
            val buffer = ByteBuffer.allocate(HEADER_BYTES)
            handle.channel.readCompletely(buffer)
            if (!buffer.array().contentEquals(MAGIC)) throw IOException("Invalid transcript header")
        }

        internal fun scan(handle: LockedMeetingFile, snapshotBytes: Long, visit: (TranscriptSegment) -> Unit): JournalScan {
            val channel = handle.channel
            if (channel.size() != snapshotBytes) throw IOException("Transcript changed while reading")
            channel.position(HEADER_BYTES.toLong())
            var count = 0L
            var characters = 0L
            var endMs = 0L
            var verified = HEADER_BYTES.toLong()
            fun report(issue: JournalTailIssue? = null) =
                JournalScan(count, characters, endMs, verified, snapshotBytes - verified, issue)
            val word = ByteBuffer.allocate(4)
            while (channel.position() < snapshotBytes) {
                if (count == MAX_SEGMENTS) throw IOException("Journal segment limit exceeded")
                val frameStart = channel.position()
                if (snapshotBytes - frameStart < 4L) return report(JournalTailIssue.INCOMPLETE_FRAME)
                word.clear(); channel.readCompletely(word); word.flip()
                val length = word.int
                if (length !in (RECORD_METADATA_BYTES + 1)..(RECORD_METADATA_BYTES + MAX_TEXT_BYTES)) {
                    return report(JournalTailIssue.INVALID_LENGTH)
                }
                if (snapshotBytes - channel.position() < length.toLong() + 4L) {
                    return report(JournalTailIssue.INCOMPLETE_FRAME)
                }
                val payload = ByteBuffer.allocate(length)
                channel.readCompletely(payload)
                word.clear(); channel.readCompletely(word); word.flip()
                if (CRC32().apply { update(payload.array()) }.value.toInt() != word.int) {
                    return report(JournalTailIssue.CHECKSUM)
                }
                payload.flip()
                val id = payload.long
                val start = payload.long
                val end = payload.long
                val textBytes = payload.int
                if (id != count + 1L || start < endMs || start < 0L || end <= start || end > PcmRecording.MAX_DURATION_MS ||
                    textBytes != payload.remaining()) return report(JournalTailIssue.INVALID_RECORD)
                val text = try {
                    Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                        .onUnmappableCharacter(CodingErrorAction.REPORT).decode(payload).toString()
                } catch (_: CharacterCodingException) { return report(JournalTailIssue.INVALID_UTF8) }
                if (text.length > MAX_TEXT_CHARACTERS || text.isBlank()) return report(JournalTailIssue.INVALID_RECORD)
                val segment = TranscriptSegment(id, start, end, text)
                // A consumer exception is propagated, never converted into corruption or a repair request.
                visit(segment)
                count += 1L
                characters += text.length.toLong()
                endMs = end
                verified = channel.position()
            }
            if (channel.size() != snapshotBytes) throw IOException("Transcript changed while reading")
            return report()
        }
    }
}

/** Retains only the current bounded record, unless the CALLER deliberately retains callback values. */
class TranscriptReader internal constructor(
    private val handle: LockedMeetingFile,
    private val snapshotBytes: Long,
) : Closeable {
    private var closed = false
    private var scanning = false
    fun scan(visit: (TranscriptSegment) -> Unit = { }): JournalScan {
        check(!closed && !scanning) { "Reader is closed or scanning recursively" }
        scanning = true
        try { return TranscriptJournal.scan(handle, snapshotBytes, visit) }
        finally { scanning = false }
    }
    override fun close() {
        check(!scanning) { "Cannot close a reader from its scan callback" }
        if (!closed) { closed = true; handle.close() }
    }
}
