package be.arte.localdictate.meeting

import java.io.Closeable
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.channels.OverlappingFileLockException
import java.nio.file.LinkOption
import java.nio.file.StandardOpenOption

/** Must throw IOException before a write if the caller's private storage budget is exhausted. */
fun interface StorageGuard {
    @Throws(IOException::class)
    fun requireSpace(file: File, additionalBytes: Long)
}

/** A best-effort free-space check, not a disk reservation; write/force can still fail afterwards. */
class ReservedSpaceGuard(private val reserveBytes: Long = 16L * 1024L * 1024L) : StorageGuard {
    init { require(reserveBytes >= 0L) }

    override fun requireSpace(file: File, additionalBytes: Long) {
        require(additionalBytes >= 0L)
        val parent = file.absoluteFile.parentFile ?: throw IOException("Missing storage parent")
        val available = parent.usableSpace
        if (available < reserveBytes || additionalBytes > available - reserveBytes) {
            throw IOException("Insufficient private storage")
        }
    }
}

/** Paths come only from the application, never from transcript text or model output. */
internal class LockedMeetingFile private constructor(
    val channel: FileChannel,
    private val lock: FileLock,
) : Closeable {
    override fun close() {
        try { lock.release() } finally { channel.close() }
    }

    companion object {
        fun create(file: File): LockedMeetingFile = open(file, create = true, readOnly = false)
        fun existing(file: File, readOnly: Boolean = false): LockedMeetingFile =
            open(file, create = false, readOnly = readOnly)

        private fun open(file: File, create: Boolean, readOnly: Boolean): LockedMeetingFile {
            require(file.isAbsolute) { "An absolute app-private File is required" }
            require(file.parentFile?.isDirectory == true) { "The private parent must already exist" }
            val options = mutableSetOf<java.nio.file.OpenOption>(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)
            if (!readOnly) options += StandardOpenOption.WRITE
            if (create) options += StandardOpenOption.CREATE_NEW
            val channel = FileChannel.open(file.toPath(), options)
            try {
                val lock = try { channel.tryLock(0L, Long.MAX_VALUE, readOnly) }
                catch (failure: OverlappingFileLockException) { throw IOException("Meeting file is busy", failure) }
                if (lock == null) throw IOException("Meeting file is busy")
                return LockedMeetingFile(channel, lock)
            } catch (failure: Throwable) {
                channel.close()
                throw failure
            }
        }
    }
}

internal fun FileChannel.writeCompletely(buffer: ByteBuffer) {
    while (buffer.hasRemaining()) {
        if (write(buffer) <= 0) throw IOException("No progress writing meeting file")
    }
}

internal fun FileChannel.readCompletely(buffer: ByteBuffer) {
    while (buffer.hasRemaining()) {
        if (read(buffer) <= 0) throw IOException("Unexpected end of meeting file")
    }
}
