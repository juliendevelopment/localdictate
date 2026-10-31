package be.arte.localdictate.session

import android.app.Application
import be.arte.localdictate.insertion.PrivateClipboard
import be.arte.localdictate.models.ModelManager
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.io.File

enum class Phase { OFF, LOADING, ARMED, RECORDING, PROCESSING }
enum class Use { PREPARATION, ARMED, RECOVERY }

/** Mutual exclusion includes cancellation/IO unwinding, loading, capture and final paste. */
class UseGate {
    private var owner: Use? = null
    @Synchronized fun acquire(use: Use): Boolean {
        if (owner != null) return false
        owner = use
        return true
    }
    @Synchronized fun release(use: Use) { check(owner == use); owner = null }
}
data class DictationState(
    val phase: Phase = Phase.OFF,
    val preparing: Boolean = false,
    val recovering: Boolean = false,
    val modelsReady: Boolean = false,
    val preparation: String = "Modèles non préparés — environ 488 Mo à télécharger, 1,5 Go libres requis.",
    val progress: Float? = null,
    val transcript: String = "",
    val message: String = "Aucune écoute automatique. Armez la bulle depuis cet écran, puis touchez-la dans le champ cible.",
    val durationMs: Long = 0,
    val error: String? = null,
    val history: List<String> = emptyList(),
    /** Short outcome shown under the bubble after a dictation that was not inserted. */
    val notice: String? = null,
)

class DictationController(private val app: Application) {
    val models = ModelManager(app)
    val journal = TranscriptJournal(File(app.filesDir, "dictation/transcript.dat"))
    val historyStore = DictationHistory(File(app.filesDir, "dictation/history.dat"))
    private val gate = UseGate()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var preparationJob: Job? = null
    var service: DictationService? = null
        internal set
    private val mutable = MutableStateFlow(DictationState(modelsReady = models.isReady()))
    val state = mutable.asStateFlow()
    init {
        runCatching { historyStore.read() }.onSuccess { items -> update { it.copy(history = items) } }
        runCatching { journal.read() }.onSuccess { record ->
            update { it.copy(transcript = record.text, message = when {
                record.interrupted -> "Dictée interrompue : texte reconnu récupéré. Le son en attente est perdu. Aucun collage automatique après reprise."
                record.insertionAttempted -> "Texte récupéré. Une insertion a déjà été tentée : vérifiez le champ avant de copier."
                record.text.isNotEmpty() -> "Transcription locale récupérée. Copiez puis effacez pour une nouvelle dictée."
                else -> it.message
            }) }
        }.onFailure { update { state -> state.copy(error = "Récupération illisible : effacez explicitement pour recommencer.") } }
    }
    fun update(change: (DictationState) -> DictationState) = mutable.update(change)
    fun prepare() {
        if (!gate.acquire(Use.PREPARATION)) return
        update { it.copy(preparing = true, error = null) }
        preparationJob = scope.launch {
            try {
                models.prepare { progress -> update { it.copy(preparation = progress.label,
                    progress = progress.fraction, modelsReady = progress.ready, error = progress.error) } }
            } catch (_: CancellationException) {
                update { it.copy(preparation = "Préparation annulée. Réessayer reprend les octets déjà téléchargés.") }
            } catch (_: Exception) {
                update { it.copy(error = it.error ?: "Préparation échouée. Vérifiez réseau et stockage, puis réessayez.") }
            } finally {
                gate.release(Use.PREPARATION)
                update { it.copy(preparing = false, modelsReady = models.isReady()) }
            }
        }
    }
    fun cancelPreparation() { preparationJob?.cancel() }
    fun reserveArm(): Boolean {
        if (!models.isReady() || !gate.acquire(Use.ARMED)) return false
        update { it.copy(phase = Phase.LOADING, error = null, message = "Chargement local des modèles, micro fermé.") }
        return true
    }
    fun armFailed() {
        gate.release(Use.ARMED)
        update { it.copy(phase = Phase.OFF, error = "Armement refusé. Revenez à cet écran visible et vérifiez les autorisations.") }
    }
    fun serviceEnded(interrupted: Boolean) {
        service = null
        gate.release(Use.ARMED)
        update { it.copy(phase = Phase.OFF, message = if (interrupted)
            "Service interrompu : texte reconnu conservé, son en attente perdu. Réarmez explicitement."
            else it.message + " Bulle désarmée.") }
    }
    fun clear() {
        if (state.value.phase != Phase.OFF || !gate.acquire(Use.RECOVERY)) return
        update { it.copy(recovering = true) }
        scope.launch(Dispatchers.IO) {
            try {
                journal.clear()
                update { it.copy(transcript = "", error = null, durationMs = 0, message = "Texte local effacé. Vous pouvez armer une nouvelle dictée.") }
            } catch (_: Exception) { update { it.copy(error = "Impossible d’effacer le texte local.") } }
            finally {
                gate.release(Use.RECOVERY)
                update { it.copy(recovering = false) }
            }
        }
    }
    fun remember(text: String): Boolean =
        runCatching { historyStore.add(text) }.onSuccess { items -> update { it.copy(history = items) } }.isSuccess
    fun clearHistory() {
        runCatching { historyStore.clear() }.onSuccess { update { it.copy(history = emptyList()) } }
    }
    fun copyText(text: String) {
        if (text.isEmpty()) return
        runCatching { PrivateClipboard(app).put(text) }.onSuccess {
            update { it.copy(message = "Texte copié comme contenu sensible. Collez-le vous-même.") }
        }.onFailure { update { state -> state.copy(error = "Copie refusée par Android.") } }
    }
    fun copy() {
        val text = state.value.transcript
        if (text.isEmpty()) return
        runCatching { PrivateClipboard(app).put(text) }.onSuccess {
            update { it.copy(message = "Texte copié comme contenu sensible. Collez-le vous-même et vérifiez les doublons. Dernier résultat : " +
                it.message.substringAfter("Dernier résultat : ").takeUnless { m -> m.startsWith("Texte copié") }.orEmpty()) }
        }.onFailure { update { state -> state.copy(error = "Copie refusée par Android. Transcription conservée ici.") } }
    }
}
