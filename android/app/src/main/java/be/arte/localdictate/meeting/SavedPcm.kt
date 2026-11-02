package be.arte.localdictate.meeting

import java.io.Closeable
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Read-only validation; unlike recover(), never truncates a torn byte. */
class SavedPcm(file: File) : Closeable {
    private val handle = LockedMeetingFile.existing(file, readOnly = true)
    val samples: Long
    val tornBytes: Long
    init {
        try {
            val header = ByteBuffer.allocate(PcmRecording.HEADER_BYTES).order(ByteOrder.LITTLE_ENDIAN)
            handle.channel.readCompletely(header)
            header.flip()
            val magic = ByteArray(8); header.get(magic)
            val rate = header.int
            val channels = header.short.toInt()
            val bits = header.short.toInt()
            val maximum = header.long
            val reserved = header.long
            if (!magic.contentEquals("LDPCM001".toByteArray(Charsets.US_ASCII)) || rate != 16_000 ||
                channels != 1 || bits != 16 || maximum !in 16L..115_200_000L || maximum % 16 != 0L || reserved != 0L)
                throw IOException("Invalid PCM header")
            val bytes = handle.channel.size() - PcmRecording.HEADER_BYTES
            if (bytes < 0 || bytes > maximum * 2L + 1L) throw IOException("Invalid PCM size")
            tornBytes = bytes % 2
            samples = bytes / 2
            if (samples > maximum) throw IOException("PCM duration limit exceeded")
        } catch (failure: Throwable) { handle.close(); throw failure }
    }
    /** One owned block, at most 2048 samples; no whole-file allocation. */
    fun read(): FloatArray? {
        check(tornBytes == 0L) { "Explicit recovery required" }
        val remaining = PcmRecording.HEADER_BYTES + samples * 2 - handle.channel.position()
        if (remaining == 0L) return null
        val bytes = ByteBuffer.allocate(minOf(4096L, remaining).toInt()).order(ByteOrder.LITTLE_ENDIAN)
        handle.channel.readCompletely(bytes); bytes.flip()
        return FloatArray(bytes.remaining() / 2) { bytes.short / 32768f }
    }
    override fun close() = handle.close()
}
