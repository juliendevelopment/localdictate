package be.arte.localdictate.meeting

import android.app.Application
import android.content.ClipData
import android.content.Intent
import androidx.core.content.FileProvider
import be.arte.localdictate.insertion.PrivateClipboard
import be.arte.localdictate.session.DictationController
import be.arte.localdictate.session.Use
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.io.File
import java.util.UUID

data class MeetingState(
    val busy: Boolean = false, val phase: MeetingPhase? = null,
    val sessions: List<MeetingSession> = emptyList(), val selected: MeetingSession? = null,
    val page: TranscriptPage? = null, val journals: List<String> = emptyList(), val durationMs: Long = 0,
    val processedMs: Long = 0, val message: String = "Aucun enregistrement automatique.", val error: String? = null,
)
data class MeetingLaunch(val token: String, val id: String?)

/** Application-scoped state. Only reserve() from an explicit resumed Activity can authorize a service. */
class MeetingController(private val app: Application, private val dictation: DictationController) {
    val repository = MeetingRepository(File(app.filesDir, "meetings"))
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutable = MutableStateFlow(MeetingState())
    val state = mutable.asStateFlow()
    private var pending: MeetingLaunch? = null
    var service: MeetingService? = null
        internal set
    init { scope.launch { refresh() } }
    fun update(change: (MeetingState) -> MeetingState) = mutable.update(change)
    private fun acquire(): Boolean {
        if (!dictation.gate.acquire(Use.MEETING)) return false
        dictation.update { it.copy(meetingBusy = true) }
        update { it.copy(busy = true, error = null) }
        return true
    }
    private fun release() {
        dictation.gate.release(Use.MEETING)
        dictation.update { it.copy(meetingBusy = false) }
        update { it.copy(busy = false, phase = null) }
    }
    @Synchronized fun reserve(id: String?): MeetingLaunch? {
        if (!dictation.models.isReady() || !acquire()) return null
        return MeetingLaunch(UUID.randomUUID().toString(), id).also { pending = it }
    }
    @Synchronized fun consume(token: String?): MeetingLaunch? {
        val request = pending ?: return null
        if (request.token != token) return null
        pending = null
        return request
    }
    @Synchronized fun launchFailed(token: String) {
        if (pending?.token != token) return
        pending = null
        update { it.copy(error = "Android a refusé le démarrage. Réessayez depuis cet écran visible.") }
        release()
    }
    suspend fun ended() {
        service = null
        try { refresh() } finally { release() }
    }
    suspend fun refresh(activeId: String? = null) {
        val sessions = repository.list(activeId)
        update { state -> state.copy(sessions = sessions, selected = sessions.find { it.id == state.selected?.id } ?: state.selected) }
    }
    fun select(id: String, firstSegment: Long = 1, journalName: String? = null) = operation {
        val stored = repository.list().first { it.id == id }
        val session = if (journalName != null && journalName != stored.journal) stored.copy(journal = journalName) else stored
        val journals = repository.directory(id).listFiles().orEmpty().map { it.name }
            .filter { it.matches(Regex("transcript-[a-f0-9-]{36}\\.dat")) }.sorted()
        val page = runCatching { repository.page(session, firstSegment) }.getOrElse {
            TranscriptPage(emptyList(), 0, "Journal illisible : original conservé. Une nouvelle transcription crée un autre journal.")
        }
        update { it.copy(selected = session, page = page, journals = journals, durationMs = session.durationMs, processedMs = session.processedMs) }
    }
    fun delete(id: String) = operation {
        repository.delete(id)
        update { it.copy(selected = null, page = null, message = "Réunion supprimée explicitement.") }
        refresh()
    }
    /** Plain transcript of the current journal, copied as sensitive content; the user pastes it. */
    fun copyTranscript(id: String) = operation {
        val session = state.value.selected?.takeIf { it.id == id } ?: repository.read(id)
        val transcript = runCatching { repository.plainText(session) }.getOrElse {
            update { it.copy(error = "Transcription trop longue ou illisible pour le presse-papiers : utilisez l’export Markdown.") }
            return@operation
        }
        if (transcript.text.isEmpty()) { update { it.copy(error = "Aucun texte transcrit à copier.") }; return@operation }
        withContext(Dispatchers.Main) { PrivateClipboard(app).put(transcript.text) }
        update { it.copy(error = null, message = "Transcription copiée (${transcript.text.length} caractères) comme contenu sensible. Collez-la vous-même." +
            if (transcript.complete) "" else " Journal partiel : seul le début vérifié est copié.") }
    }
    fun export(id: String, wav: Boolean, share: (Intent) -> Unit) = operation {
        val session = state.value.selected?.takeIf { it.id == id } ?: repository.read(id)
        val file = MeetingExport(File(app.cacheDir, "meeting-exports"), repository).create(session, wav)
        val uri = FileProvider.getUriForFile(app, "${app.packageName}.meeting.exports", file)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = if (wav) "audio/wav" else "text/markdown"
            putExtra(Intent.EXTRA_STREAM, uri)
            clipData = ClipData.newRawUri("Export réunion", uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        withContext(Dispatchers.Main) { share(Intent.createChooser(intent, "Exporter la réunion")) }
    }
    /** Serialize browsing/export/deletion too; processing keeps its gate through native cleanup. */
    private fun operation(block: suspend () -> Unit) {
        if (!acquire()) return
        scope.launch {
            try { block() }
            catch (_: Exception) { update { it.copy(error = "Opération impossible : vérifiez espace et intégrité. Les fichiers privés sont conservés.") } }
            finally { release() }
        }
    }
}
