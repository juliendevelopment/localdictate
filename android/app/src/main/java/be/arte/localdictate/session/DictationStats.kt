package be.arte.localdictate.session

import java.io.*
import java.nio.file.Files
import java.nio.file.StandardCopyOption.*
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.TemporalAdjusters

/** Totals for one week starting on [monday]. Counts only: no text is ever stored here. */
data class WeekStats(val monday: LocalDate, val words: Long, val durationMs: Long, val dictations: Int)

/** Weekly word count and dictated time, app-private and atomic, kept for the last [limit] weeks. */
class DictationStats(private val file: File, private val limit: Int = 52) {
    /** Newest week first. */
    @Synchronized fun read(): List<WeekStats> {
        if (!file.exists()) return emptyList()
        DataInputStream(file.inputStream().buffered()).use { input ->
            check(input.readInt() == 1) { "Format de statistiques inconnu" }
            val count = input.readInt()
            check(count in 0..limit) { "Statistiques invalides" }
            return List(count) {
                WeekStats(LocalDate.ofEpochDay(input.readLong()), input.readLong(), input.readLong(), input.readInt())
            }
        }
    }
    @Synchronized fun add(text: String, durationMs: Long, today: LocalDate = LocalDate.now()): List<WeekStats> {
        val monday = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
        val current = runCatching { read() }.getOrDefault(emptyList())
        val week = current.firstOrNull { it.monday == monday } ?: WeekStats(monday, 0, 0, 0)
        val updated = week.copy(words = week.words + countWords(text),
            durationMs = week.durationMs + durationMs.coerceAtLeast(0), dictations = week.dictations + 1)
        return (current.filter { it.monday != monday } + updated).sortedByDescending { it.monday }.take(limit).also(::write)
    }
    @Synchronized fun clear() { file.delete() }
    private fun write(weeks: List<WeekStats>) {
        check(file.parentFile!!.isDirectory || file.parentFile!!.mkdirs())
        val pending = File(file.parentFile, file.name + ".pending")
        FileOutputStream(pending).use { stream ->
            val output = DataOutputStream(stream)
            output.writeInt(1)
            output.writeInt(weeks.size)
            weeks.forEach { week ->
                output.writeLong(week.monday.toEpochDay()); output.writeLong(week.words)
                output.writeLong(week.durationMs); output.writeInt(week.dictations)
            }
            output.flush()
            stream.fd.sync()
        }
        Files.move(pending.toPath(), file.toPath(), ATOMIC_MOVE, REPLACE_EXISTING)
    }
    companion object {
        /** Words as runs of letters or digits, so punctuation alone does not count. */
        fun countWords(text: String): Int = Regex("[\\p{L}\\p{N}]+(['’-][\\p{L}\\p{N}]+)*").findAll(text).count()
    }
}
