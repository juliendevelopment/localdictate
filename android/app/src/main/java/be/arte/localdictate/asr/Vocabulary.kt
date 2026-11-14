package be.arte.localdictate.asr

import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING

/**
 * The user's own words, typed in the app, one entry per line:
 * - `Parakeet`: a **hotword**, favoured by the recognizer's beam search (sherpa-onnx contextual biasing). It helps
 *   when the spoken word is close to it; it never forces it.
 * - `para kit => Parakeet`: a **replacement**, applied to the recognized text (whole words, case ignored). Sure,
 *   but only for an error that comes back the same way.
 *
 * Blank lines and lines starting with `#` are ignored. Stays in the app's private files; nothing leaves the phone.
 */
data class Vocabulary(val hotwords: List<String> = emptyList(), val replacements: List<Replacement> = emptyList()) {
    data class Replacement(val heard: String, val wanted: String)

    val isEmpty get() = hotwords.isEmpty() && replacements.isEmpty()

    /** Per-stream hotwords for sherpa-onnx (`/` separates entries), or null when there are none. */
    val streamHotwords: String? get() = hotwords.takeIf { it.isNotEmpty() }?.joinToString("/")

    // Longest first, so "para kit" wins over "para"; letters and digits around a match keep it from cutting a word.
    private val pattern: Regex? = replacements.takeIf { it.isNotEmpty() }?.let { items ->
        val alternatives = items.map { it.heard }.distinct().sortedByDescending { it.length }
            .joinToString("|") { heard -> heard.split(' ').joinToString("\\s+") { Regex.escape(it) } }
        Regex("(?<![\\p{L}\\p{N}])(?:$alternatives)(?![\\p{L}\\p{N}])", setOf(RegexOption.IGNORE_CASE))
    }
    private val wanted = replacements.associate { it.heard.lowercase() to it.wanted }

    fun apply(text: String): String = pattern?.replace(text) { match ->
        wanted[match.value.split(Regex("\\s+")).joinToString(" ").lowercase()] ?: match.value
    } ?: text

    companion object {
        const val MAX_ENTRIES = 300
        const val MAX_ENTRY_CHARS = 60
        const val MAX_TEXT_CHARS = 20_000
        val EMPTY = Vocabulary()

        /** Lenient: an unusable line is skipped, never an error that would block a dictation. */
        fun parse(text: String): Vocabulary {
            val hotwords = mutableListOf<String>()
            val replacements = mutableListOf<Replacement>()
            for (raw in text.take(MAX_TEXT_CHARS).lineSequence()) {
                if (hotwords.size + replacements.size >= MAX_ENTRIES) break
                val line = raw.trim()
                if (line.isEmpty() || line.startsWith("#")) continue
                val arrow = line.indexOf("=>")
                if (arrow >= 0) {
                    val heard = clean(line.substring(0, arrow))
                    val wanted = clean(line.substring(arrow + 2))
                    if (heard == null || wanted == null) continue
                    if (heard != wanted) replacements += Replacement(heard, wanted)
                    // The wanted spelling is also a hotword: the recognizer may then produce it directly.
                    hotword(wanted)?.let { if (it !in hotwords) hotwords += it }
                } else {
                    hotword(line)?.let { if (it !in hotwords) hotwords += it }
                }
            }
            return Vocabulary(hotwords, replacements)
        }

        private fun clean(text: String): String? =
            text.trim().replace(Regex("\\s+"), " ").takeIf { it.isNotEmpty() && it.length <= MAX_ENTRY_CHARS }

        // `/` separates per-stream entries and ` :` introduces a score in sherpa-onnx: neither may reach it.
        private fun hotword(text: String): String? = clean(text.replace('/', ' ').replace(':', ' '))
    }
}

/** One small private text file, written atomically; the text is kept as typed so the user finds it unchanged. */
class VocabularyStore(private val file: File) {
    @Synchronized fun read(): String = if (file.isFile) file.readText(Charsets.UTF_8).take(Vocabulary.MAX_TEXT_CHARS) else ""
    @Synchronized fun write(text: String) {
        val content = text.take(Vocabulary.MAX_TEXT_CHARS)
        check(file.parentFile!!.isDirectory || file.parentFile!!.mkdirs())
        val pending = File(file.parentFile, file.name + ".pending")
        FileOutputStream(pending).use { stream ->
            stream.write(content.toByteArray(Charsets.UTF_8))
            stream.flush()
            stream.fd.sync()
        }
        Files.move(pending.toPath(), file.toPath(), ATOMIC_MOVE, REPLACE_EXISTING)
    }
    fun load(): Vocabulary = runCatching { Vocabulary.parse(read()) }.getOrDefault(Vocabulary.EMPTY)
}
