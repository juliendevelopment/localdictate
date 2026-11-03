package be.arte.localdictate.meeting

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class SpeechMergerTest {
    @get:Rule val temporary = TemporaryFolder()
    private fun speech(startS: Double, lengthS: Double, value: Float = 0.1f) =
        MeetingSpeech((startS * 16_000).toLong(), FloatArray((lengthS * 16_000).toInt()) { value })

    @Test fun shortPausesAreJoinedIntoOneBlockSpanningTheRealInterval() {
        val merger = SpeechMerger()
        assertTrue(merger.add(speech(144.0, 2.0, 0.1f)).isEmpty())
        // 4 s pause > 3 s: "Au niveau" is flushed alone, the next part at finish.
        assertEquals(144L * 16_000, merger.add(speech(150.0, 6.0, 0.2f)).single().startSample)
        assertEquals(150L * 16_000, merger.finish().single().startSample)
    }

    @Test fun joinsUnderThreeSecondsAndKeepsAudioOrder() {
        val merger = SpeechMerger()
        val first = speech(123.0, 1.0, 0.1f)
        val second = speech(125.0, 4.0, 0.2f)
        assertTrue(merger.add(first).isEmpty())
        assertTrue(merger.add(second).isEmpty())
        val block = merger.finish().single()
        assertEquals(first.startSample, block.startSample)
        assertEquals(second.startSample + second.samples.size, block.endSample)
        // Pause audio is dropped: the decode window is both segments back to back.
        assertEquals(first.samples.size + second.samples.size, block.samples.size)
        assertEquals(0.1f, block.samples[first.samples.size - 1])
        assertEquals(0.2f, block.samples[first.samples.size])
    }

    @Test fun longPauseOrFullWindowStartsANewBlock() {
        val merger = SpeechMerger()
        assertTrue(merger.add(speech(0.0, 2.0)).isEmpty())
        val flushed = merger.add(speech(6.0, 2.0))
        assertEquals(1, flushed.size)
        assertEquals(2L * 16_000, flushed[0].endSample)

        val windowed = SpeechMerger()
        assertTrue(windowed.add(speech(0.0, 12.0)).isEmpty())
        assertEquals(12 * 16_000, windowed.add(speech(13.0, 10.0)).single().samples.size)
        assertEquals(10 * 16_000, windowed.finish().single().samples.size)
        assertTrue(windowed.finish().isEmpty())
    }

    @Test fun oversizedSegmentStaysAlone() {
        val merger = SpeechMerger()
        assertTrue(merger.add(speech(0.0, 21.0)).isEmpty())
        assertEquals(21 * 16_000, merger.add(speech(21.5, 1.0)).single().samples.size)
        assertEquals(16_000, merger.finish().single().samples.size)
    }

    @Test fun transcriberDecodesMergedBlocksOnceWithTheirSpan() = runBlocking {
        val audio = File(temporary.root, "audio.pcm")
        PcmRecording.create(audio, StorageGuard { _, _ -> }).use { recording ->
            repeat(100) { recording.append(ByteArray(3200)) } // 10 s
        }
        val decoded = mutableListOf<Int>()
        val journalFile = File(temporary.root, "journal.dat")
        TranscriptJournal.create(journalFile, StorageGuard { _, _ -> }).use { journal ->
            MeetingTranscriber().transcribe(audio, journal, object : MeetingDecoder {
                // Segments are reported once the whole file is read, as positions must not exceed the audio read.
                override fun accept(samples: FloatArray) = emptyList<MeetingSpeech>()
                override fun finish() = listOf(MeetingSpeech(0, FloatArray(8_000)), MeetingSpeech(16_000, FloatArray(16_000)),
                    MeetingSpeech(6 * 16_000, FloatArray(16_000)))
                override fun recognize(samples: FloatArray): String { decoded += samples.size; return "bloc ${decoded.size}" }
                override fun close() = Unit
            }, cancelled = { false })
        }
        assertEquals(listOf(24_000, 16_000), decoded)
        val segments = mutableListOf<TranscriptSegment>()
        TranscriptJournal.openReader(journalFile).use { it.scan { s -> segments += s } }
        assertEquals(listOf(0L to 2_000L, 6_000L to 7_000L), segments.map { it.startMs to it.endMs })
    }
}
