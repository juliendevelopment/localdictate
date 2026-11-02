package be.arte.localdictate.meeting

import java.io.File
import java.io.RandomAccessFile
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Every passage below is synthetic test material, not a user transcript or engine output. */
class LocalExtractiveSummarizerTest {
    @get:Rule val temporary = TemporaryFolder(File(System.getenv("TMPDIR") ?: System.getProperty("java.io.tmpdir")))
    private val allow = StorageGuard { _, _ -> }
    private val summarizer = LocalExtractiveSummarizer()

    @Test fun emptyJournalProducesAnHonestEmptyExtractWithoutInventedDecisions() {
        val file = File(temporary.root, "empty.journal")
        TranscriptJournal.create(file, allow).close()
        val result = summarizer.summarize(file, SummaryLanguage.FRENCH)
        assertEquals("Résumé extractif local", result.label)
        assertTrue(result.sentences.isEmpty())
        assertTrue(result.coverage.isEmpty())
        assertEquals(0L, result.sentencesExamined)
        assertEquals(0L, result.source.segmentCount)
        assertEquals(0, result.selectedCharacters)
        assertTrue(result.uncoveredPartIndexes.isEmpty())
    }

    @Test fun frenchSentencesAndNegationsRemainVerbatimWithExactSourceOffsetsAndTiming() {
        val file = File(temporary.root, "french.journal")
        val originals = listOf(
            TranscriptSegment(1L, 0L, 1_000L, "  TEST synthétique : Nous ne validons pas ce budget. Aucune décision n’a été prise.  "),
            TranscriptSegment(2L, 1_000L, 2_000L, "TEST synthétique : L’équipe n’enverra rien demain. Le café coûte cher, mais ce n’est pas une action. 🤝"),
        )
        write(file, originals)
        val result = summarizer.summarize(file, SummaryLanguage.FRENCH)
        assertTrue(result.sentences.any { it.text.contains("Nous ne validons pas ce budget.") })
        assertTrue(result.sentences.any { it.text.contains("Aucune décision n’a été prise.") })
        assertTrue(result.sentences.any { it.text.contains("L’équipe n’enverra rien demain.") })
        assertSourceExact(result, originals)
        assertEquals(SummaryLanguage.FRENCH, result.language)
        assertEquals(result, summarizer.summarize(file, SummaryLanguage.FRENCH))
    }

    @Test fun englishContractionsQuestionsAndNegationsAreNotTranslatedOrParaphrased() {
        val file = File(temporary.root, "english.journal")
        val originals = listOf(
            TranscriptSegment(1L, 10L, 1_000L, "SYNTHETIC TEST: We won't release on Friday. No owner has accepted an action."),
            TranscriptSegment(2L, 1_200L, 3_000L, "SYNTHETIC TEST: Should we reconsider the plan? We did not decide to cancel it."),
        )
        write(file, originals)
        val result = summarizer.summarize(file, SummaryLanguage.ENGLISH)
        assertTrue(result.sentences.any { it.text.contains("We won't release on Friday.") })
        assertTrue(result.sentences.any { it.text.contains("We did not decide to cancel it.") })
        assertSourceExact(result, originals)
        assertEquals(SummaryLanguage.ENGLISH, result.language)
    }

    @Test fun oneWholeSourceSentenceIsSelectedFromEachPartOfATwoHourMeeting() {
        val file = File(temporary.root, "four-parts.journal")
        val originals = listOf(
            TranscriptSegment(1L, 0L, 60_000L, "SYNTHETIC TEST: Opening topic is apples. Apples are discussed first."),
            TranscriptSegment(2L, 1_800_000L, 1_860_000L, "SYNTHETIC TEST: The second topic is trains. Trains remain delayed."),
            TranscriptSegment(3L, 3_600_000L, 3_660_000L, "SYNTHETIC TEST: The third topic is energy. Energy demand rises."),
            TranscriptSegment(4L, 5_400_000L, 7_200_000L, "SYNTHETIC TEST: The closing topic is rain. We do not approve a launch."),
        )
        write(file, originals)
        val options = ExtractiveSummaryOptions(4, 400, 100, 4)
        val result = summarizer.summarize(file, SummaryLanguage.ENGLISH, options)
        assertEquals(listOf(1L, 2L, 3L, 4L), result.sentences.map { it.segmentId })
        assertEquals(listOf(0L, 1_800_000L, 3_600_000L, 5_400_000L), result.sentences.map { it.segmentStartMs })
        assertEquals(7_200_000L, result.source.lastEndMs)
        assertTrue(result.coverage.all { it.selectedSentences == 1 })
        assertTrue(result.uncoveredPartIndexes.isEmpty())
        assertTrue(result.selectedCharacters <= 400)
        assertSourceExact(result, originals)
    }

    @Test fun completeTwoHourTranscriptIsScannedAfterTheOutputBudgetIsAlreadyFull() {
        val file = File(temporary.root, "full-meeting.journal")
        val originals = List(200) { index ->
            TranscriptSegment(index + 1L, index * 36_000L, (index + 1L) * 36_000L,
                "SYNTHETIC TEST $index: The topic is transport. We do not approve a purchase. This is a discussion only.")
        }
        write(file, originals)
        val result = summarizer.summarize(file, SummaryLanguage.ENGLISH, ExtractiveSummaryOptions(4, 400, 100, 4))
        assertEquals(200L, result.source.segmentCount)
        assertEquals(7_200_000L, result.source.lastEndMs)
        assertEquals(600L, result.sentencesExamined)
        assertEquals(600L, result.eligibleSentences)
        assertEquals(596L, result.omittedEligibleSentences)
        assertEquals(4, result.sentences.size)
        assertTrue(result.sentences.last().segmentId >= 151L)
        assertTrue(result.coverage.all { it.sourceSegments == 50L && it.selectedSentences == 1 })
        assertSourceExact(result, originals)
    }

    @Test fun oversizedSentencesAreNotCutAndUncoveredTimePartsAreReported() {
        val file = File(temporary.root, "oversized.journal")
        val longSentence = "TEST synthétique : " + "long ".repeat(100) + "fin."
        val originals = listOf(
            TranscriptSegment(1L, 0L, 100L, longSentence),
            TranscriptSegment(2L, 3_600_000L, 7_200_000L, "TEST synthétique : Nous ne confirmons rien."),
        )
        write(file, originals)
        val before = file.readBytes()
        val result = summarizer.summarize(file, SummaryLanguage.FRENCH, ExtractiveSummaryOptions(2, 128, 64, 2))
        assertEquals(2L, result.sentencesExamined)
        assertEquals(1L, result.oversizedSentences)
        assertEquals(1L, result.eligibleSentences)
        assertEquals(listOf(0), result.uncoveredPartIndexes)
        assertEquals(listOf("TEST synthétique : Nous ne confirmons rien."), result.sentences.map { it.text })
        assertSourceExact(result, originals)
        assertArrayEquals(before, file.readBytes())
    }

    @Test fun boundedLexicalRankingSelectsSourceContentRatherThanAlwaysTheOpeningSentence() {
        val file = File(temporary.root, "ranking.journal")
        val originals = listOf(TranscriptSegment(1L, 0L, 100L,
            "SYNTHETIC TEST: Hello. Transport transport transport needs review. Transport is delayed."))
        write(file, originals)
        val result = summarizer.summarize(file, SummaryLanguage.ENGLISH, ExtractiveSummaryOptions(1, 100, 100, 1))
        assertEquals(listOf("Transport transport transport needs review."), result.sentences.map { it.text })
        assertSourceExact(result, originals)
    }

    @Test fun allReturnedCharactersStayInsideCountAndCharacterBudgets() {
        val file = File(temporary.root, "budgets.journal")
        val originals = List(16) { index ->
            TranscriptSegment(index + 1L, index * 1_000L, (index + 1L) * 1_000L,
                "SYNTHETIC TEST $index: This sentence discusses different transport details.")
        }
        write(file, originals)
        val result = summarizer.summarize(file, SummaryLanguage.ENGLISH, ExtractiveSummaryOptions(6, 160, 80, 2))
        assertTrue(result.sentences.size <= 6)
        assertTrue(result.selectedCharacters <= 160)
        assertEquals(16L, result.source.segmentCount)
        assertTrue(result.omittedEligibleSentences > 0L)
        assertTrue(result.coverage.all { it.selectedSentences >= 1 })
        assertSourceExact(result, originals)
    }

    @Test fun summaryFailureDoesNotModifyDeleteOrPretendToRepairTheTranscript() {
        val file = File(temporary.root, "failure.journal")
        write(file, listOf(TranscriptSegment(1L, 0L, 100L, "SYNTHETIC TEST: Keep this source text.")))
        RandomAccessFile(file, "rw").use { it.seek(it.length()); it.writeByte(0) }
        val before = file.readBytes()
        val failure = assertThrows(JournalTailException::class.java) { summarizer.summarize(file, SummaryLanguage.ENGLISH) }
        assertEquals(1L, failure.scan.segmentCount)
        assertArrayEquals(before, file.readBytes())
        TranscriptJournal.openReader(file).use { reader ->
            val texts = mutableListOf<String>()
            reader.scan { texts.add(it.text) }
            assertEquals(listOf("SYNTHETIC TEST: Keep this source text."), texts)
        }
    }

    @Test fun veryShortMeetingUsesOnlyNonemptyTimePartitions() {
        val file = File(temporary.root, "one-millisecond.journal")
        write(file, listOf(TranscriptSegment(1L, 0L, 1L, "SYNTHETIC TEST: One brief phrase.")))
        val result = summarizer.summarize(file, SummaryLanguage.ENGLISH)
        assertEquals(1, result.coverage.size)
        assertEquals(0L, result.coverage.single().startMs)
        assertEquals(1L, result.coverage.single().endMs)
        assertEquals(1, result.coverage.single().selectedSentences)
    }

    @Test fun roundedTimePartitionsContainTheirSelectedSourceTimestamps() {
        val file = File(temporary.root, "rounded-parts.journal")
        val originals = listOf(
            TranscriptSegment(1L, 0L, 1L, "SYNTHETIC TEST: First topic."),
            TranscriptSegment(2L, 2L, 3L, "SYNTHETIC TEST: Second topic."),
            TranscriptSegment(3L, 5L, 6L, "SYNTHETIC TEST: Third topic."),
            TranscriptSegment(4L, 7L, 10L, "SYNTHETIC TEST: Fourth topic."),
        )
        write(file, originals)
        val result = summarizer.summarize(file, SummaryLanguage.ENGLISH, ExtractiveSummaryOptions(4, 400, 100, 4))
        assertEquals(listOf(1, 1, 1, 1), result.coverage.map { it.selectedSentences })
        assertTrue(result.uncoveredPartIndexes.isEmpty())
        for ((index, sentence) in result.sentences.withIndex()) {
            assertTrue(sentence.segmentStartMs >= result.coverage[index].startMs)
            assertTrue(sentence.segmentStartMs < result.coverage[index].endMs)
        }
    }

    @Test fun invalidOptionsRefuseUnboundedOrImpossibleRequests() {
        assertThrows(IllegalArgumentException::class.java) { ExtractiveSummaryOptions(maxSentences = 65) }
        assertThrows(IllegalArgumentException::class.java) { ExtractiveSummaryOptions(maxSentenceCharacters = 4_097) }
        assertThrows(IllegalArgumentException::class.java) { ExtractiveSummaryOptions(maxTotalCharacters = 65_537) }
        assertThrows(IllegalArgumentException::class.java) { ExtractiveSummaryOptions(4, 100, 100, 4) }
        assertThrows(IllegalArgumentException::class.java) { ExtractiveSummaryOptions(1, 100, 100, 2) }
        assertThrows(IllegalArgumentException::class.java) { ExtractiveSummaryOptions(0, 0, 0, 0) }
    }

    private fun write(file: File, segments: List<TranscriptSegment>) {
        TranscriptJournal.create(file, allow).use { journal ->
            for (segment in segments) journal.append(segment.startMs, segment.endMs, segment.text)
        }
    }

    private fun assertSourceExact(summary: ExtractiveSummary, originals: List<TranscriptSegment>) {
        val byId = originals.associateBy { it.id }
        for (sentence in summary.sentences) {
            val source = byId.getValue(sentence.segmentId)
            assertEquals(source.text.substring(sentence.startCharacter, sentence.endCharacterExclusive), sentence.text)
            assertEquals(source.startMs, sentence.segmentStartMs)
            assertEquals(source.endMs, sentence.segmentEndMs)
            assertEquals("segment-${source.id}", sentence.sourceAnchor)
        }
        val expectedOrder = summary.sentences.sortedWith(compareBy<SourceSentence> { it.segmentStartMs }.thenBy { it.segmentId }.thenBy { it.startCharacter })
        assertEquals(expectedOrder, summary.sentences)
    }
}
