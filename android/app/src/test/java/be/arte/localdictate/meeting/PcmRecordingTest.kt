package be.arte.localdictate.meeting

import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** All audio/text in this suite is synthetic test data, never an ASR result. */
class PcmRecordingTest {
    @get:Rule val temporary = TemporaryFolder(File(System.getenv("TMPDIR") ?: System.getProperty("java.io.tmpdir")))
    private val allow = StorageGuard { _, _ -> }

    @Test fun pcmIsAppendedAndExportedAsCanonicalLittleEndianWav() {
        val pcm = File(temporary.root, "audio.pcm")
        val wav = File(temporary.root, "audio.wav")
        PcmRecording.create(pcm, allow).use { recording ->
            assertEquals(4L, recording.append(byteArrayOf(1, 2, 3, 4)).acceptedBytes)
            assertEquals(2L, recording.sampleCount)
            recording.finishWav(wav)
        }
        RandomAccessFile(wav, "r").use { file ->
            val header = ByteArray(44); file.readFully(header)
            assertEquals("RIFF", String(header, 0, 4, Charsets.US_ASCII))
            assertEquals(40L, littleEndian(header, 4, 4))
            assertEquals("WAVEfmt ", String(header, 8, 8, Charsets.US_ASCII))
            assertEquals(16L, littleEndian(header, 16, 4))
            assertEquals(1L, littleEndian(header, 20, 2))
            assertEquals(1L, littleEndian(header, 22, 2))
            assertEquals(16_000L, littleEndian(header, 24, 4))
            assertEquals(32_000L, littleEndian(header, 28, 4))
            assertEquals(2L, littleEndian(header, 32, 2))
            assertEquals(16L, littleEndian(header, 34, 2))
            assertEquals("data", String(header, 36, 4, Charsets.US_ASCII))
            assertEquals(4L, littleEndian(header, 40, 4))
            val payload = ByteArray(4); file.readFully(payload)
            assertArrayEquals(byteArrayOf(1, 2, 3, 4), payload)
            assertEquals(48L, file.length())
        }
    }

    @Test fun exactTwoHourBoundaryUsesLongCountersAndStreamsWavWithSmallHeap() {
        val pcm = File(temporary.root, "two-hour-synthetic.pcm")
        PcmRecording.create(pcm, allow).close()
        // Sparse synthetic silence avoids a two-hour capture or a 230 MB in-memory fixture.
        RandomAccessFile(pcm, "rw").use { it.setLength(230_400_030L) }
        PcmRecording.recover(pcm, allow).recording.use { recording ->
            assertEquals(230_399_998L, recording.pcmBytes)
            assertEquals(115_199_999L, recording.sampleCount)
            val result = recording.append(byteArrayOf(11, 12, 13, 14))
            assertEquals(AudioAppendResult(2L, 2L, true), result)
            assertEquals(115_200_000L, recording.sampleCount)
            assertEquals(7_200_000L, recording.durationMs)
            assertEquals(AudioAppendResult(0L, 2L, true), recording.append(byteArrayOf(1, 2)))
            val wav = File(temporary.root, "two-hour-synthetic.wav")
            val exported = recording.finishWav(wav)
            assertEquals(230_400_000L, exported.pcmBytes)
            RandomAccessFile(wav, "r").use { input ->
                assertEquals(230_400_044L, input.length())
                input.seek(40L)
                val size = ByteArray(4); input.readFully(size)
                assertEquals(230_400_000L, littleEndian(size, 0, 4))
                input.seek(input.length() - 2L)
                assertEquals(11, input.readUnsignedByte())
                assertEquals(12, input.readUnsignedByte())
            }
            assertThrows(IllegalStateException::class.java) { recording.append(byteArrayOf(1, 2)) }
        }
    }

    @Test fun tornFinalSampleIsReportedAndCompleteSamplesCanBeResumed() {
        val pcm = File(temporary.root, "interrupted.pcm")
        PcmRecording.create(pcm, allow).use { it.append(byteArrayOf(1, 2, 3, 4)) }
        RandomAccessFile(pcm, "rw").use { it.seek(it.length()); it.writeByte(99) }
        val recovered = PcmRecording.recover(pcm, allow)
        assertEquals(1L, recovered.discardedIncompleteBytes)
        recovered.recording.use {
            assertEquals(4L, it.pcmBytes)
            it.append(byteArrayOf(5, 6))
            val wav = File(temporary.root, "recovered.wav")
            it.finishWav(wav)
            RandomAccessFile(wav, "r").use { input ->
                input.seek(44L)
                val bytes = ByteArray(6); input.readFully(bytes)
                assertArrayEquals(byteArrayOf(1, 2, 3, 4, 5, 6), bytes)
            }
        }
    }

    @Test fun invalidFormatsBoundsAndLargeBuffersAreRefusedBeforeWriting() {
        val pcm = File(temporary.root, "validation.pcm")
        assertThrows(IllegalArgumentException::class.java) { PcmRecording.create(pcm, allow, sampleRateHz = 48_000) }
        assertThrows(IllegalArgumentException::class.java) { PcmRecording.create(pcm, allow, channels = 2) }
        assertThrows(IllegalArgumentException::class.java) { PcmRecording.create(pcm, allow, bitsPerSample = 8) }
        assertThrows(IllegalArgumentException::class.java) { PcmRecording.create(pcm, allow, maxDurationMs = 0L) }
        assertThrows(IllegalArgumentException::class.java) { PcmRecording.create(pcm, allow, maxDurationMs = 7_200_001L) }
        assertFalse(pcm.exists())
        PcmRecording.create(pcm, allow, maxDurationMs = 1L).use {
            assertThrows(IllegalArgumentException::class.java) { it.append(ByteArray(1)) }
            assertThrows(IllegalArgumentException::class.java) { it.append(ByteArray(65_538)) }
            assertThrows(IllegalArgumentException::class.java) { it.append(ByteArray(2), -1, 2) }
            assertThrows(IllegalArgumentException::class.java) { it.append(ByteArray(2), 1, Int.MAX_VALUE) }
            assertThrows(IllegalArgumentException::class.java) { it.append(ByteArray(2), 0, -2) }
            assertEquals(0L, it.pcmBytes)
            assertEquals(AudioAppendResult(32L, 2L, true), it.append(ByteArray(34)))
        }
    }

    @Test fun existingRecordingsAndDestinationsAreNeverReplaced() {
        val pcm = File(temporary.root, "existing.pcm")
        PcmRecording.create(pcm, allow).use { recording ->
            recording.append(byteArrayOf(7, 8))
            assertThrows(IOException::class.java) { PcmRecording.create(pcm, allow) }
            assertThrows(IOException::class.java) { PcmRecording.recover(pcm, allow) }
            assertEquals(34L, pcm.length())
            val destination = File(temporary.root, "existing.wav").apply { writeText("KEEP") }
            assertThrows(IOException::class.java) { recording.finishWav(destination) }
            assertEquals("KEEP", destination.readText())
            recording.append(byteArrayOf(9, 10))
        }
    }

    @Test fun guardAndActualIoFailurePreserveTheSpool() {
        val pcm = File(temporary.root, "guard.pcm")
        var denyAppend = true
        val guard = StorageGuard { _, additional -> if (denyAppend && additional == 2L) throw IOException("synthetic storage refusal") }
        PcmRecording.create(pcm, guard).use { recording ->
            assertThrows(IOException::class.java) { recording.append(byteArrayOf(1, 2)) }
            assertEquals(0L, recording.pcmBytes)
            assertEquals(32L, pcm.length())
            denyAppend = false
            recording.append(byteArrayOf(1, 2))
            val directoryDestination = temporary.newFolder("directory.wav")
            assertThrows(IOException::class.java) { recording.finishWav(directoryDestination) }
            assertEquals(2L, recording.pcmBytes)
            assertEquals(34L, pcm.length())
            val wav = File(temporary.root, "after-io-error.wav")
            recording.finishWav(wav)
            assertEquals(46L, wav.length())
        }
    }

    @Test fun invalidAndOversizedRecoveryFilesFailClosed() {
        val bad = File(temporary.root, "bad.pcm").apply { writeBytes(ByteArray(32)) }
        assertThrows(IOException::class.java) { PcmRecording.recover(bad, allow) }
        assertEquals(32L, bad.length())
        val short = File(temporary.root, "short.pcm").apply { writeBytes(byteArrayOf(1)) }
        assertThrows(IOException::class.java) { PcmRecording.recover(short, allow) }
        val tooLong = File(temporary.root, "oversized.pcm")
        PcmRecording.create(tooLong, allow, maxDurationMs = 1L).close()
        RandomAccessFile(tooLong, "rw").use { it.setLength(66L) }
        assertThrows(IOException::class.java) { PcmRecording.recover(tooLong, allow) }
        assertEquals(66L, tooLong.length())
        val missing = File(temporary.root, "missing.pcm")
        assertThrows(IOException::class.java) { PcmRecording.recover(missing, allow) }
        assertFalse(missing.exists())
    }

    @Test fun buffersAreConsumedImmediatelyAndNotRetained() {
        val pcm = File(temporary.root, "reused-buffer.pcm")
        val buffer = ByteArray(65_536) { 3 }
        PcmRecording.create(pcm, allow).use { recording ->
            repeat(32) { recording.append(buffer) }
            buffer.fill(9)
            assertEquals(2_097_152L, recording.pcmBytes)
            val wav = File(temporary.root, "reused-buffer.wav")
            recording.finishWav(wav)
            RandomAccessFile(wav, "r").use { input ->
                input.seek(44L); assertEquals(3, input.readUnsignedByte())
                input.seek(input.length() - 1L); assertEquals(3, input.readUnsignedByte())
            }
        }
    }

    @Test fun emptyRecordingHasValidWavAndClosedHandleRefusesAppend() {
        val pcm = File(temporary.root, "empty.pcm")
        val recording = PcmRecording.create(pcm, allow)
        val wav = File(temporary.root, "empty.wav")
        recording.finishWav(wav)
        assertEquals(44L, wav.length())
        recording.close(); recording.close()
        assertThrows(IllegalStateException::class.java) { recording.append(byteArrayOf(1, 2)) }
    }

    private fun littleEndian(bytes: ByteArray, offset: Int, size: Int): Long =
        (0 until size).fold(0L) { value, i -> value or ((bytes[offset + i].toLong() and 255L) shl (8 * i)) }
}
