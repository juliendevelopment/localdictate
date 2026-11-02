package be.arte.localdictate.meeting

import java.io.File
import java.text.BreakIterator
import java.util.Locale
import kotlin.math.ln
import kotlin.math.sqrt

enum class SummaryLanguage(val locale: Locale) { FRENCH(Locale.FRENCH), ENGLISH(Locale.ENGLISH) }

data class ExtractiveSummaryOptions(
    val maxSentences: Int = 12,
    val maxTotalCharacters: Int = 6_000,
    val maxSentenceCharacters: Int = 1_000,
    val coverageParts: Int = 4,
) {
    init {
        require(maxSentences in 1..64)
        require(maxTotalCharacters in 1..65_536)
        require(maxSentenceCharacters in 1..4_096)
        require(coverageParts in 1..16 && coverageParts <= maxSentences)
        // Reserve enough room for one whole sentence from every nonempty eligible time bucket.
        require(maxTotalCharacters >= coverageParts * maxSentenceCharacters) {
            "Character budget must allow one full sentence per coverage part"
        }
    }
}

data class SourceSentence(
    val segmentId: Long,
    val segmentStartMs: Long,
    val segmentEndMs: Long,
    val startCharacter: Int,
    val endCharacterExclusive: Int,
    val text: String,
) {
    /** In-document link, never a filesystem path derived from content. Offsets are UTF-16. */
    val sourceAnchor: String get() = "segment-$segmentId"
}

data class SummaryCoveragePart(
    val index: Int,
    val startMs: Long,
    val endMs: Long,
    val sourceSegments: Long,
    val eligibleSentences: Long,
    val selectedSentences: Int,
)

data class ExtractiveSummary(
    val language: SummaryLanguage,
    val sentences: List<SourceSentence>,
    val source: JournalScan,
    val sentencesExamined: Long,
    val oversizedSentences: Long,
    val eligibleSentences: Long,
    val coverage: List<SummaryCoveragePart>,
) {
    val label: String get() = "Résumé extractif local"
    val selectedCharacters: Int get() = sentences.sumOf { it.text.length }
    val omittedEligibleSentences: Long get() = eligibleSentences - sentences.size.toLong()
    val uncoveredPartIndexes: List<Int> get() = coverage.filter { it.sourceSegments > 0L && it.selectedSentences == 0 }.map { it.index }
}

/**
 * Non-generative, offline, deterministic extraction. Two complete streaming journal passes:
 * 1. bounded hashed term-frequency sketch and metadata; 2. sentence ranking within time buckets.
 * Keeps at most coverageParts * maxSentences candidates. Never loads the complete transcript.
 * Original substrings (including negations) are returned in source order, with segment-level timing.
 * No decisions/actions classification, paraphrase, translation, network or model is involved.
 */
class LocalExtractiveSummarizer {
    fun summarize(
        journal: File,
        language: SummaryLanguage,
        options: ExtractiveSummaryOptions = ExtractiveSummaryOptions(),
    ): ExtractiveSummary = TranscriptJournal.openReader(journal).use { reader ->
        val frequencies = LongArray(FREQUENCY_BINS)
        val stopWords = if (language == SummaryLanguage.FRENCH) FRENCH_STOP_WORDS else ENGLISH_STOP_WORDS
        val source = reader.scan { segment ->
            forEachTerm(segment.text, language.locale, stopWords) { term ->
                val index = term.hashCode() and (FREQUENCY_BINS - 1)
                frequencies[index] += 1L
            }
        }.requireComplete()
        if (source.segmentCount == 0L) {
            return@use ExtractiveSummary(language, emptyList(), source, 0L, 0L, 0L, emptyList())
        }
        val durationMs = source.lastEndMs
        val partCount = minOf(options.coverageParts.toLong(), durationMs).toInt()
        val pools = Array(partCount) { ArrayList<Candidate>(options.maxSentences) }
        val segmentsPerPart = LongArray(partCount)
        val eligiblePerPart = LongArray(partCount)
        val boundary = BreakIterator.getSentenceInstance(language.locale)
        var examined = 0L
        var oversized = 0L
        var eligible = 0L
        val secondPass = reader.scan { segment ->
            // Match the floor-rounded [duration*i/n, duration*(i+1)/n) ranges reported below.
            val part = (((segment.startMs + 1L) * partCount - 1L) / durationMs).toInt()
            segmentsPerPart[part] += 1L
            boundary.setText(segment.text)
            var begin = boundary.first()
            var end = boundary.next()
            while (end != BreakIterator.DONE) {
                var from = begin
                var until = end
                while (from < until && segment.text[from].isWhitespace()) from++
                while (until > from && segment.text[until - 1].isWhitespace()) until--
                if (until > from) {
                    examined += 1L
                    if (until - from > options.maxSentenceCharacters) {
                        // Do not cut a sentence or silently ignore the rest of the meeting.
                        oversized += 1L
                    } else {
                        eligible += 1L
                        eligiblePerPart[part] += 1L
                        val text = segment.text.substring(from, until)
                        var relevance = 0.0
                        var terms = 0L
                        forEachTerm(text, language.locale, stopWords) { term ->
                            relevance += ln(1.0 + frequencies[term.hashCode() and (FREQUENCY_BINS - 1)].toDouble())
                            terms += 1L
                        }
                        val score = if (terms == 0L) 0.0 else relevance / sqrt(terms.toDouble())
                        val candidate = Candidate(
                            SourceSentence(segment.id, segment.startMs, segment.endMs, from, until, text), part, score,
                        )
                        val pool = pools[part]
                        pool.add(candidate)
                        // Capacity is explicit and independent of meeting length or total sentence count.
                        if (pool.size > options.maxSentences) {
                            var worst = 0
                            for (index in 1 until pool.size) {
                                if (BEST_FIRST.compare(pool[index], pool[worst]) > 0) worst = index
                            }
                            pool.removeAt(worst)
                        }
                    }
                }
                begin = end
                end = boundary.next()
            }
        }.requireComplete()
        check(secondPass == source) { "Transcript changed between summary passes" }
        val selected = ArrayList<Candidate>(options.maxSentences)
        var characters = 0
        // Time diversity takes priority over global lexical rank: one candidate per eligible bucket.
        for (pool in pools) {
            val best = pool.minWithOrNull(BEST_FIRST) ?: continue
            selected.add(best)
            characters += best.sentence.text.length
        }
        val ranked = pools.flatMap { it }.sortedWith(BEST_FIRST)
        for (candidate in ranked) {
            if (selected.size == options.maxSentences) break
            if (candidate in selected || characters + candidate.sentence.text.length > options.maxTotalCharacters) continue
            if (selected.any { it.sentence.text == candidate.sentence.text }) continue
            selected.add(candidate)
            characters += candidate.sentence.text.length
        }
        val selectedPerPart = IntArray(partCount)
        selected.forEach { selectedPerPart[it.part]++ }
        val ordered = selected.map { it.sentence }.sortedWith(
            compareBy<SourceSentence> { it.segmentStartMs }.thenBy { it.segmentId }.thenBy { it.startCharacter },
        )
        ExtractiveSummary(
            language, ordered, source, examined, oversized, eligible,
            List(partCount) { index ->
                SummaryCoveragePart(
                    index, durationMs * index / partCount,
                    durationMs * (index + 1L) / partCount,
                    segmentsPerPart[index], eligiblePerPart[index], selectedPerPart[index],
                )
            },
        )
    }

    private data class Candidate(val sentence: SourceSentence, val part: Int, val score: Double)

    companion object {
        private const val FREQUENCY_BINS = 4_096
        private val TERM = Regex("[\\p{L}\\p{N}]+(?:['’][\\p{L}\\p{N}]+)*")
        private val BEST_FIRST = compareByDescending<Candidate> { it.score }
            .thenBy { it.sentence.segmentId }.thenBy { it.sentence.startCharacter }
        // Scoring only: these tokens are NEVER removed from the returned source sentences.
        private val FRENCH_STOP_WORDS = setOf("le", "la", "les", "un", "une", "des", "du", "de", "et", "en", "à", "au", "aux", "ce", "ces", "est", "sont", "nous", "vous", "il", "elle", "on", "pour", "avec", "dans", "que", "qui", "sur")
        private val ENGLISH_STOP_WORDS = setOf("the", "a", "an", "and", "of", "to", "in", "on", "for", "with", "is", "are", "was", "were", "we", "you", "it", "this", "that", "as", "at", "by", "be", "have", "has")

        private inline fun forEachTerm(text: String, locale: Locale, stopWords: Set<String>, visit: (String) -> Unit) {
            for (match in TERM.findAll(text)) {
                val term = match.value.lowercase(locale)
                if (term !in stopWords) visit(term)
            }
        }
    }
}
