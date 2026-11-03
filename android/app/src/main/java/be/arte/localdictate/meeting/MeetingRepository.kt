package be.arte.localdictate.meeting

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.util.Properties
import java.util.UUID

// Active states left on disk mean interruption on the next process launch, never an instruction to start.
enum class MeetingPhase { CREATED, RECORDING, RECORDED, TRANSCRIBING, SUMMARIZING, COMPLETE, INTERRUPTED, CANCELLED, ERROR }
data class MeetingSession(
    val id: String, val createdMs: Long, val phase: MeetingPhase = MeetingPhase.CREATED,
    val durationMs: Long = 0, val processedMs: Long = 0, val language: SummaryLanguage = SummaryLanguage.FRENCH,
    val journal: String = "", val diagnostic: String = "", val summary: String = "",
)
data class PlainTranscript(val text: String, val complete: Boolean)
data class TranscriptPage(val segments: List<TranscriptSegment>, val total: Long, val diagnostic: String)

/** UUID-only private namespace, explicit quotas; never evicts a session to make room. IO owner required. */
class MeetingRepository(val root: File, private val freeSpace: StorageGuard = ReservedSpaceGuard(64L * 1024 * 1024)) {
    companion object {
        const val MAX_SESSIONS = 20
        const val MAX_PLAIN_TEXT_CHARS = 200_000 // Well under the 1 MB binder limit of a clipboard transaction.
        const val MAX_BYTES = 2L * 1024 * 1024 * 1024
        private val active = setOf(MeetingPhase.CREATED, MeetingPhase.RECORDING, MeetingPhase.TRANSCRIBING, MeetingPhase.SUMMARIZING)
        fun validId(id: String) = runCatching { UUID.fromString(id).toString() == id }.getOrDefault(false)
    }
    init { require(root.isAbsolute); check(root.isDirectory || root.mkdirs()); check(!Files.isSymbolicLink(root.toPath())) }
    fun directory(id: String): File {
        require(validId(id))
        val file = File(root, id)
        check(Files.isDirectory(file.toPath(), NOFOLLOW_LINKS))
        check(file.canonicalFile.parentFile == root.canonicalFile)
        return file
    }
    fun audio(id: String) = File(directory(id), "audio.pcm")
    fun journal(session: MeetingSession): File {
        require(session.journal.matches(Regex("transcript-[a-f0-9-]{36}\\.dat")))
        return File(directory(session.id), session.journal)
    }
    fun summary(session: MeetingSession): File {
        require(session.summary.matches(Regex("summary-[a-f0-9-]{36}\\.md")))
        return File(directory(session.id), session.summary)
    }
    private fun usedBytes(): Long = root.walkTopDown().onEnter { !Files.isSymbolicLink(it.toPath()) }
        .filter { Files.isRegularFile(it.toPath(), NOFOLLOW_LINKS) }.sumOf { it.length().coerceAtMost(MAX_BYTES + 1) }
    val guard = StorageGuard { file, bytes ->
        require(bytes >= 0)
        val used = usedBytes()
        if (used > MAX_BYTES || bytes > MAX_BYTES - used) throw IOException("Meeting quota exhausted")
        freeSpace.requireSpace(file, bytes)
    }
    fun create(language: SummaryLanguage): MeetingSession {
        if (list().size >= MAX_SESSIONS) throw IOException("Session limit reached")
        guard.requireSpace(File(root, "reservation"), PcmRecording.MAX_PCM_BYTES + 1024 * 1024)
        val id = UUID.randomUUID().toString()
        check(File(root, id).mkdir())
        val session = MeetingSession(id, System.currentTimeMillis(), language = language)
        save(session)
        return session
    }
    fun save(session: MeetingSession) {
        val p = Properties().apply {
            setProperty("id", session.id); setProperty("created", session.createdMs.toString())
            setProperty("phase", session.phase.name); setProperty("duration", session.durationMs.toString())
            setProperty("processed", session.processedMs.toString()); setProperty("language", session.language.name)
            setProperty("journal", session.journal); setProperty("diagnostic", session.diagnostic.take(2000))
            setProperty("summary", session.summary)
        }
        atomicFile(File(directory(session.id), "session.properties")) { out -> p.store(out, null) }
    }
    fun read(id: String): MeetingSession {
        val file = File(directory(id), "session.properties")
        check(Files.isRegularFile(file.toPath(), NOFOLLOW_LINKS) && file.length() <= 16_384)
        val p = Properties().apply { file.inputStream().use { load(it) } }
        check(p.getProperty("id") == id)
        return MeetingSession(id, p.getProperty("created").toLong(), MeetingPhase.valueOf(p.getProperty("phase")),
            p.getProperty("duration").toLong().also { require(it in 0..PcmRecording.MAX_DURATION_MS) },
            p.getProperty("processed").toLong().also { require(it in 0..PcmRecording.MAX_DURATION_MS) },
            SummaryLanguage.valueOf(p.getProperty("language")), p.getProperty("journal", ""),
            p.getProperty("diagnostic", ""), p.getProperty("summary", ""))
    }
    fun list(activeId: String? = null): List<MeetingSession> = root.listFiles().orEmpty()
        .filter { validId(it.name) && Files.isDirectory(it.toPath(), NOFOLLOW_LINKS) }
        .map { folder -> runCatching { read(folder.name) }.getOrElse {
            MeetingSession(folder.name, folder.lastModified(), MeetingPhase.ERROR, diagnostic = "Métadonnées illisibles. Fichiers conservés ; export audio ou suppression explicites.")
        } }.map { if (it.id != activeId && it.phase in active) it.copy(phase = MeetingPhase.INTERRUPTED,
            diagnostic = it.diagnostic + " Session interrompue : aucun redémarrage automatique. Le dernier bloc non synchronisé peut manquer.") else it }
        .sortedByDescending { it.createdMs }
    fun newJournal(session: MeetingSession): MeetingSession = session.copy(
        journal = "transcript-${UUID.randomUUID()}.dat", summary = "", processedMs = 0)
    /** User authorized recovery only. Preserve the complete original before the sole destructive repair. */
    fun recoverAudio(id: String): String {
        val file = audio(id)
        val torn = SavedPcm(file).use { it.tornBytes }
        if (torn == 0L) return "Structure PCM vérifiée ; intégrité acoustique non certifiée."
        val backup = File(directory(id), "audio-original-${UUID.randomUUID()}.pcm")
        guard.requireSpace(backup, file.length())
        file.inputStream().use { input -> FileOutputStream(backup).use { out -> input.copyTo(out, 16_384); out.fd.sync() } }
        val discarded = PcmRecording.recover(file, guard).let { result -> result.recording.close(); result.discardedIncompleteBytes }
        return "Reprise autorisée : $discarded octet de demi-échantillon retiré. Original conservé : ${backup.name}."
    }
    fun page(session: MeetingSession, firstId: Long = 1, count: Int = 30): TranscriptPage {
        require(count in 1..40 && firstId >= 1)
        if (session.journal.isEmpty()) return TranscriptPage(emptyList(), 0, "Pas encore de transcription.")
        val items = ArrayList<TranscriptSegment>(count)
        val scan = TranscriptJournal.openReader(journal(session)).use { reader -> reader.scan {
            if (it.id >= firstId && items.size < count) items += it
        } }
        return TranscriptPage(items, scan.segmentCount,
            if (scan.isComplete) "" else "Journal partiel : ${scan.tailIssue}, ${scan.tailBytes} octets non vérifiés. Original conservé, aucune réparation automatique.")
    }
    /**
     * The verified journal as plain text for pasting elsewhere (an LLM, notes): no timestamps, anchors or summary.
     * Segments are joined with a space; a pause of [paragraphGapMs] or more starts a new paragraph.
     */
    fun plainText(session: MeetingSession, paragraphGapMs: Long = 8_000, maxChars: Int = MAX_PLAIN_TEXT_CHARS): PlainTranscript {
        if (session.journal.isEmpty()) return PlainTranscript("", complete = true)
        val text = StringBuilder()
        var lastEnd = -1L
        val scan = TranscriptJournal.openReader(journal(session)).use { reader -> reader.scan { segment ->
            if (lastEnd >= 0) text.append(if (segment.startMs - lastEnd >= paragraphGapMs) "\n\n" else " ")
            text.append(segment.text.trim())
            lastEnd = segment.endMs
            if (text.length > maxChars) throw IOException("Transcript too long for the clipboard")
        } }
        return PlainTranscript(text.toString(), scan.isComplete)
    }
    fun delete(id: String) {
        val directory = directory(id)
        // Never follow a symlink, including one inside a session directory.
        Files.walk(directory.toPath()).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach { Files.delete(it) } }
    }
    fun atomicFile(file: File, write: (FileOutputStream) -> Unit) {
        guard.requireSpace(file, 32_768)
        val temp = File(file.parentFile, ".write-${UUID.randomUUID()}")
        try {
            FileOutputStream(temp).use { out -> write(out); out.fd.sync() }
            Files.move(temp.toPath(), file.toPath(), ATOMIC_MOVE, REPLACE_EXISTING)
            // Persist the directory entry as well as file contents on Android's private filesystem.
            val parent = checkNotNull(file.parentFile) { "Parent directory required" }
            java.nio.channels.FileChannel.open(parent.toPath(), java.nio.file.StandardOpenOption.READ).use { it.force(true) }
        } finally { temp.delete() }
    }
}
