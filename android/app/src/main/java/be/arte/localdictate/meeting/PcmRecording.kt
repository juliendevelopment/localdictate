package be.arte.localdictate.meeting

import java.io.Closeable
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Accepted and rejected bytes are explicit, including a buffer straddling the two-hour boundary. */
data class AudioAppendResult(val acceptedBytes: Long, val rejectedBytes: Long, val limitReached: Boolean)
data class AudioRecovery(val recording: PcmRecording, val discardedIncompleteBytes: Long)
data class WavRecording(val file: File, val sampleCount: Long, val pcmBytes: Long)

/**
 * Synchronous, thread-confined PCM16 little-endian / mono / 16 kHz spool.
 * Each successful append is forced to storage. close() keeps the spool, without exporting/deleting it.
 * No audio buffer is retained. A failed write poisons this handle: close and recover explicitly.
 */
class PcmRecording private constructor(
    val file: File,
    private val storage: StorageGuard,
    private val handle: LockedMeetingFile,
    val maxSamples: Long,
    initialBytes: Long,
) : Closeable {
    var pcmBytes: Long = initialBytes
        private set
    val sampleCount: Long get() = pcmBytes / BYTES_PER_SAMPLE
    val durationMs: Long get() = sampleCount * 1000L / SAMPLE_RATE_HZ
    val limitReached: Boolean get() = sampleCount == maxSamples
    private var closed = false
    private var failed = false
    private var sealed = false

    @Synchronized
    fun append(pcm: ByteArray, offset: Int = 0, length: Int = pcm.size - offset): AudioAppendResult {
        check(!closed && !failed && !sealed) { "Recording is closed, failed or exported" }
        require(offset >= 0 && length >= 0 && offset <= pcm.size && length <= pcm.size - offset)
        require(length <= MAX_APPEND_BYTES) { "Append small buffers, not the whole recording" }
        require(length % BYTES_PER_SAMPLE == 0) { "Incomplete PCM16 sample" }
        val accepted = minOf(length.toLong(), maxSamples * BYTES_PER_SAMPLE - pcmBytes)
        if (accepted > 0L) {
            // A rejected storage check has no effect and may be retried after explicit user action.
            storage.requireSpace(file, accepted)
            try {
                handle.channel.position(HEADER_BYTES + pcmBytes)
                handle.channel.writeCompletely(ByteBuffer.wrap(pcm, offset, accepted.toInt()))
                handle.channel.force(true)
                pcmBytes += accepted
            } catch (failure: IOException) {
                failed = true
                throw failure
            }
        }
        return AudioAppendResult(accepted, length.toLong() - accepted, limitReached)
    }

    /**
     * Writes a NEW canonical WAV in bounded chunks. Never replaces an existing destination.
     * On failure both the spool and any incomplete destination remain available; retry with a new File.
     * Export is a copy: budget disk space for spool + WAV. Successful export seals this handle.
     */
    @Synchronized
    fun finishWav(destination: File): WavRecording {
        check(!closed && !failed) { "Recording is closed or failed" }
        require(destination.isAbsolute)
        storage.requireSpace(destination, WAV_HEADER_BYTES + pcmBytes)
        LockedMeetingFile.create(destination).use { output ->
            output.channel.writeCompletely(wavHeader(pcmBytes))
            val buffer = ByteBuffer.allocate(COPY_BUFFER_BYTES)
            handle.channel.position(HEADER_BYTES.toLong())
            var remaining = pcmBytes
            while (remaining > 0L) {
                buffer.clear()
                buffer.limit(minOf(remaining, buffer.capacity().toLong()).toInt())
                handle.channel.readCompletely(buffer)
                buffer.flip()
                output.channel.writeCompletely(buffer)
                remaining -= buffer.limit().toLong()
            }
            output.channel.force(true)
        }
        sealed = true
        return WavRecording(destination, sampleCount, pcmBytes)
    }

    @Synchronized
    override fun close() {
        if (!closed) {
            closed = true
            handle.close()
        }
    }

    companion object {
        const val SAMPLE_RATE_HZ = 16_000
        const val BYTES_PER_SAMPLE = 2
        const val MAX_DURATION_MS = 7_200_000L
        const val MAX_PCM_BYTES = 230_400_000L
        const val MAX_APPEND_BYTES = 65_536
        const val HEADER_BYTES = 32
        const val WAV_HEADER_BYTES = 44
        const val COPY_BUFFER_BYTES = 16_384
        private val MAGIC = "LDPCM001".toByteArray(Charsets.US_ASCII)

        fun create(
            file: File,
            storage: StorageGuard,
            maxDurationMs: Long = MAX_DURATION_MS,
            sampleRateHz: Int = SAMPLE_RATE_HZ,
            channels: Int = 1,
            bitsPerSample: Int = 16,
        ): PcmRecording {
            require(sampleRateHz == SAMPLE_RATE_HZ && channels == 1 && bitsPerSample == 16) {
                "Only PCM16 little-endian, mono, 16 kHz is supported"
            }
            require(maxDurationMs in 1L..MAX_DURATION_MS)
            val maxSamples = maxDurationMs * SAMPLE_RATE_HZ / 1000L
            storage.requireSpace(file, HEADER_BYTES.toLong())
            val handle = LockedMeetingFile.create(file)
            try {
                val header = ByteBuffer.allocate(HEADER_BYTES).order(ByteOrder.LITTLE_ENDIAN)
                header.put(MAGIC).putInt(SAMPLE_RATE_HZ).putShort(1).putShort(16).putLong(maxSamples).putLong(0L)
                header.flip()
                handle.channel.writeCompletely(header)
                handle.channel.force(true)
                return PcmRecording(file, storage, handle, maxSamples, 0L)
            } catch (failure: Throwable) {
                handle.close()
                throw failure
            }
        }

        /**
         * Explicitly resumes an existing spool. Infers complete samples from file length, not RAM.
         * Only one torn final byte can be discarded (reported); invalid headers/oversize files fail closed.
         * Raw PCM has no per-buffer checksum: hardware corruption or unforced data cannot be certified.
         */
        fun recover(file: File, storage: StorageGuard): AudioRecovery {
            val handle = LockedMeetingFile.existing(file)
            try {
                val channel = handle.channel
                if (channel.size() < HEADER_BYTES) throw IOException("Incomplete PCM spool header")
                val header = ByteBuffer.allocate(HEADER_BYTES).order(ByteOrder.LITTLE_ENDIAN)
                channel.readCompletely(header)
                header.flip()
                val magic = ByteArray(MAGIC.size); header.get(magic)
                val sampleRate = header.int
                val channels = header.short.toInt()
                val bits = header.short.toInt()
                val maxSamples = header.long
                val reserved = header.long
                if (!magic.contentEquals(MAGIC) || sampleRate != SAMPLE_RATE_HZ || channels != 1 || bits != 16 ||
                    maxSamples !in 16L..115_200_000L || maxSamples % 16L != 0L || reserved != 0L) {
                    throw IOException("Invalid PCM spool header")
                }
                val bytes = channel.size() - HEADER_BYTES
                if (bytes > maxSamples * BYTES_PER_SAMPLE + 1L) throw IOException("PCM spool exceeds its duration limit")
                val discarded = bytes % BYTES_PER_SAMPLE
                val complete = bytes - discarded
                if (complete > maxSamples * BYTES_PER_SAMPLE) throw IOException("PCM spool exceeds its duration limit")
                if (discarded != 0L) {
                    channel.truncate(HEADER_BYTES + complete)
                    channel.force(true)
                }
                return AudioRecovery(PcmRecording(file, storage, handle, maxSamples, complete), discarded)
            } catch (failure: Throwable) {
                handle.close()
                throw failure
            }
        }

        private fun wavHeader(pcmBytes: Long): ByteBuffer {
            val header = ByteBuffer.allocate(WAV_HEADER_BYTES).order(ByteOrder.LITTLE_ENDIAN)
            header.put("RIFF".toByteArray(Charsets.US_ASCII)).putInt((36L + pcmBytes).toInt())
            header.put("WAVEfmt ".toByteArray(Charsets.US_ASCII)).putInt(16).putShort(1).putShort(1)
            header.putInt(SAMPLE_RATE_HZ).putInt(SAMPLE_RATE_HZ * BYTES_PER_SAMPLE)
            header.putShort(BYTES_PER_SAMPLE.toShort()).putShort(16)
            header.put("data".toByteArray(Charsets.US_ASCII)).putInt(pcmBytes.toInt())
            header.flip()
            return header
        }
    }
}
