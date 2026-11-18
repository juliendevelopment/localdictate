package be.arte.localdictate

import android.Manifest
import android.app.Application
import android.app.NotificationManager
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import be.arte.localdictate.insertion.InsertionService
import be.arte.localdictate.meeting.*
import be.arte.localdictate.session.*

data class Readiness(val microphone: Boolean = false, val overlay: Boolean = false,
    val accessibility: Boolean = false, val notifications: Boolean = false)

class DictationViewModel(application: Application) : ViewModel() {
    val controller = (application as DictateApplication).controller
    val meetings = (application as DictateApplication).meetings
    class Factory(private val application: Application) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = DictationViewModel(application) as T
    }
}

class MainActivity : ComponentActivity() {
    private var readiness by mutableStateOf(Readiness())
    /** Bumped when the meeting notification is tapped: the screen switches to the « Réunion » tab. */
    private var meetingRequest by mutableIntStateOf(0)
    private val controller get() = (application as DictateApplication).controller
    private val meetings get() = (application as DictateApplication).meetings
    private val permissions = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        refreshPermissions() // Never arms or captures on a permission dialog return.
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) handleOpen(intent)
        setContent {
            val model: DictationViewModel = viewModel(factory = DictationViewModel.Factory(application))
            val state by model.controller.state.collectAsState()
            val meetingState by model.meetings.state.collectAsState()
            val meetingLevels by model.meetings.levels.collectAsState()
            MaterialTheme {
                HomeScreen(state, meetingState, readiness,
                    prepare = model.controller::prepare, cancel = model.controller::cancelPreparation,
                    microphone = { permissions.launch(Manifest.permission.RECORD_AUDIO) },
                    notifications = { if (Build.VERSION.SDK_INT >= 33 &&
                        checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
                            permissions.launch(Manifest.permission.POST_NOTIFICATIONS)
                        else openSettings(Settings.ACTION_APP_NOTIFICATION_SETTINGS, packageExtra = true) },
                    overlay = { openSettings(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, packageUri = true) },
                    accessibility = { openSettings(Settings.ACTION_ACCESSIBILITY_SETTINGS) },
                    arm = ::arm, stop = { controller.service?.stopCapture() },
                    disarm = { controller.service?.disarm() }, copy = model.controller::copy, clear = model.controller::clear,
                    copyText = model.controller::copyText, clearHistory = model.controller::clearHistory,
                    clearStats = model.controller::clearStats,
                    saveVocabulary = model.controller::saveVocabulary,
                    saveStopDelay = model.controller::saveStopDelay,
                    showBubble = { controller.service?.showBubble() },
                    startMeeting = ::startMeeting,
                    stopMeeting = ::stopMeeting,
                    processMeeting = ::processMeeting,
                    selectMeeting = model.meetings::select,
                    unselectMeeting = { model.meetings.update { it.copy(selected = null, page = null) } },
                    deleteMeeting = model.meetings::delete,
                    copyMeeting = model.meetings::copyTranscript,
                    exportMeeting = { id, wav -> model.meetings.export(id, wav) { startActivity(it) } },
                    meetingLevels = meetingLevels, saveMeetingGain = model.meetings::saveGain,
                    saveMeetingChain = model.meetings::saveChain, meetingRequest = meetingRequest)
            }
        }
    }
    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); handleOpen(intent) }
    private fun handleOpen(intent: Intent?) {
        if (intent?.getBooleanExtra(EXTRA_MEETING, false) == true) meetingRequest++
    }
    companion object {
        private const val EXTRA_MEETING = "be.arte.localdictate.OPEN_MEETING"
        /** Notification target: existing activity brought to the front on the « Réunion » tab. */
        fun openMeeting(context: android.content.Context): Intent = Intent(context, MainActivity::class.java)
            .putExtra(EXTRA_MEETING, true)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
    }
    override fun onResume() { super.onResume(); refreshPermissions() }
    private fun refreshPermissions() {
        readiness = Readiness(
            checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED,
            Settings.canDrawOverlays(this), InsertionService.connected != null,
            getSystemService(NotificationManager::class.java).let { manager ->
                manager.areNotificationsEnabled() && manager.getNotificationChannel(DictationService.CHANNEL)?.importance != NotificationManager.IMPORTANCE_NONE
            })
    }
    private fun openSettings(action: String, packageUri: Boolean = false, packageExtra: Boolean = false) {
        runCatching {
            startActivity(Intent(action).apply {
                if (packageUri) data = Uri.parse("package:$packageName")
                if (packageExtra) putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
            })
        }.onFailure { controller.update { state -> state.copy(error = "Réglage indisponible : ouvrez les paramètres Android de LocalDictate.") } }
    }
    private fun arm() {
        refreshPermissions()
        if (!lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) || !readiness.microphone ||
            !readiness.overlay || !readiness.notifications || !readiness.accessibility) return
        if (!controller.reserveArm()) return
        try { startForegroundService(Intent(this, DictationService::class.java).setAction(DictationService.ARM)) }
        catch (_: Exception) { controller.armFailed() }
    }
    private fun startMeeting() {
        refreshPermissions()
        if (!lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) || !readiness.microphone || !readiness.notifications) return
        val launch = meetings.reserve(id = null) ?: return
        try {
            startForegroundService(Intent(this, MeetingService::class.java).apply {
                action = MeetingService.START
                putExtra("token", launch.token)
            })
        } catch (_: Exception) {
            meetings.launchFailed(launch.token)
        }
    }
    private fun processMeeting(sessionId: String) {
        refreshPermissions()
        if (!lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) || !readiness.notifications) return
        val launch = meetings.reserve(id = sessionId) ?: return
        try {
            startForegroundService(Intent(this, MeetingService::class.java).apply {
                action = MeetingService.START
                putExtra("token", launch.token)
            })
        } catch (_: Exception) {
            meetings.launchFailed(launch.token)
        }
    }
    private fun stopMeeting() {
        startService(Intent(this, MeetingService::class.java).setAction(MeetingService.STOP))
    }
}

@Composable
private fun HomeScreen(
    state: DictationState, meetingState: MeetingState, ready: Readiness, prepare: () -> Unit, cancel: () -> Unit,
    microphone: () -> Unit, notifications: () -> Unit, overlay: () -> Unit, accessibility: () -> Unit,
    arm: () -> Unit, stop: () -> Unit, disarm: () -> Unit, copy: () -> Unit, clear: () -> Unit,
    copyText: (String) -> Unit, clearHistory: () -> Unit, clearStats: () -> Unit = {}, showBubble: () -> Unit = {},
    saveVocabulary: (String) -> Unit = {}, saveStopDelay: (Long) -> Unit = {},
    startMeeting: () -> Unit, stopMeeting: () -> Unit,
    processMeeting: (String) -> Unit, selectMeeting: (String) -> Unit,
    unselectMeeting: () -> Unit, deleteMeeting: (String) -> Unit, copyMeeting: (String) -> Unit,
    exportMeeting: (String, Boolean) -> Unit,
    meetingLevels: List<Float> = emptyList(), saveMeetingGain: (Int) -> Unit = {},
    saveMeetingChain: (Boolean) -> Unit = {}, meetingRequest: Int = 0,
) {
    var meeting by rememberSaveable { mutableStateOf(false) }
    var disclosure by rememberSaveable { mutableStateOf(false) }
    var deleting by rememberSaveable { mutableStateOf<String?>(null) }
    var licenses by rememberSaveable { mutableStateOf(false) }
    var notices by rememberSaveable { mutableStateOf(false) }
    val scroll = rememberScrollState()
    // Each mode opens at its top, where its main button is (the other mode's scroll position means nothing here).
    LaunchedEffect(meeting) { scroll.scrollTo(0) }
    LaunchedEffect(meetingRequest) { if (meetingRequest > 0) meeting = true }
    Surface(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().verticalScroll(scroll).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("LocalDictate", style = MaterialTheme.typography.headlineLarge)
            Text("Dictée locale • français et anglais", style = MaterialTheme.typography.titleMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                ModeButton("Dictée", selected = !meeting, tag = "mode-dictation") { meeting = false }
                ModeButton("Réunion", selected = meeting, tag = "mode-meeting") { meeting = true }
            }
            if (meeting) {
                Text("Jusqu’à 2 heures", style = MaterialTheme.typography.titleLarge)
                Text("Enregistrement direct sur disque jusqu’à 2 heures, puis transcription locale quand vous touchez « Transcrire », à copier ou exporter en Markdown pour la donner à l’outil de votre choix (un LLM pour le résumé, par exemple). Langue détectée automatiquement (français, anglais, néerlandais…). Les pauses courtes sont regroupées pour garder le contexte des phrases. Aucun collage automatique dans une autre application. Accord des participants à votre charge.")
                if (meetingState.busy || meetingState.phase != null) {
                    Text("État : " + when (val phase = meetingState.phase) {
                        null -> "occupé"
                        MeetingPhase.TRANSCRIBING -> "transcription locale (${formatDuration(meetingState.processedMs)} / ${formatDuration(meetingState.durationMs)})"
                        else -> meetingPhaseLabel(phase)
                    }, modifier = Modifier.testTag("meeting-state"))
                    if (meetingState.durationMs > 0) Text("Durée : ${formatDuration(meetingState.durationMs)} / 2 h")
                    Text(meetingState.message, modifier = Modifier.testTag("meeting-message"))
                    if (meetingState.phase == MeetingPhase.RECORDING) {
                        MeetingLevelBars(meetingLevels)
                        Button(onClick = stopMeeting, modifier = Modifier.fillMaxWidth().testTag("meeting-stop")) {
                            Text("Arrêter l’enregistrement")
                        }
                    } else if (meetingState.phase == MeetingPhase.TRANSCRIBING) {
                        OutlinedButton(onClick = stopMeeting, modifier = Modifier.fillMaxWidth().testTag("meeting-cancel")) {
                            Text("Annuler le traitement")
                        }
                    }
                } else if (meetingState.selected != null) {
                    val session = meetingState.selected
                    Text("Réunion sélectionnée : ${session.id.take(8)}", style = MaterialTheme.typography.titleMedium)
                    Text("État : ${meetingPhaseLabel(session.phase)} • Durée : ${formatDuration(session.durationMs)}")
                    if (session.diagnostic.isNotBlank()) Text(session.diagnostic)
                    if (session.journal.isNotEmpty()) {
                        Button(onClick = { copyMeeting(session.id) }, modifier = Modifier.fillMaxWidth().testTag("meeting-copy")) {
                            Text("Copier la transcription (texte brut)")
                        }
                    }
                    if (session.phase in listOf(MeetingPhase.RECORDED, MeetingPhase.INTERRUPTED, MeetingPhase.CANCELLED,
                            MeetingPhase.ERROR, MeetingPhase.COMPLETE)) {
                        OutlinedButton(onClick = { processMeeting(session.id) },
                            enabled = state.modelsReady && ready.notifications,
                            modifier = Modifier.fillMaxWidth().testTag("meeting-process")) {
                            // A new run writes a new journal; the previous one stays on disk.
                            Text(if (session.journal.isEmpty()) "Transcrire" else "Retranscrire (nouveau journal)")
                        }
                    }
                    Text(meetingState.message, modifier = Modifier.testTag("meeting-selected-message"))
                    meetingState.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    if (meetingState.page != null && meetingState.page.segments.isNotEmpty()) {
                        HorizontalDivider()
                        Text("Transcription (${meetingState.page.total} segments)", style = MaterialTheme.typography.titleMedium)
                        meetingState.page.segments.forEach { segment ->
                            Text("[${formatClock(segment.startMs)}–${formatClock(segment.endMs)}] ${segment.text}")
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { exportMeeting(session.id, false) }, modifier = Modifier.testTag("meeting-export-md")) {
                            Text("Export Markdown")
                        }
                        OutlinedButton(onClick = { exportMeeting(session.id, true) }, modifier = Modifier.testTag("meeting-export-wav")) {
                            Text("Export WAV")
                        }
                        TextButton(onClick = { deleting = session.id }, modifier = Modifier.testTag("meeting-delete")) {
                            Text("Supprimer")
                        }
                    }
                    TextButton(onClick = unselectMeeting, modifier = Modifier.testTag("meeting-back")) {
                        Text("Retour à la liste")
                    }
                } else {
                    Button(onClick = { startMeeting() },
                        enabled = !state.preparing && !state.recovering &&
                            state.phase == Phase.OFF && state.modelsReady &&
                            ready.microphone && ready.notifications,
                        modifier = Modifier.fillMaxWidth().testTag("meeting-start")) {
                        Text("Démarrer un enregistrement")
                    }
                    if (!state.modelsReady) {
                        Text("Préparez d’abord les modèles dans l’onglet Dictée.", color = MaterialTheme.colorScheme.error)
                    }
                    meetingState.error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("meeting-error")) }

                    HorizontalDivider()
                    Text("Réunions enregistrées (${meetingState.sessions.size})", style = MaterialTheme.typography.titleMedium)
                    if (meetingState.sessions.isEmpty()) {
                        Text("Aucune réunion enregistrée.", modifier = Modifier.testTag("meeting-empty"))
                    } else {
                        meetingState.sessions.forEach { s ->
                            Card(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Text("Réunion du ${java.time.Instant.ofEpochMilli(s.createdMs).atZone(java.time.ZoneId.systemDefault()).toLocalDateTime().format(java.time.format.DateTimeFormatter.ofPattern("d MMM yyyy HH:mm"))}")
                                    Text("Durée : ${formatDuration(s.durationMs)} • État : ${meetingPhaseLabel(s.phase)}")
                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        OutlinedButton(onClick = { selectMeeting(s.id) }, modifier = Modifier.testTag("meeting-open-${s.id}")) {
                                            Text("Consulter")
                                        }
                                        TextButton(onClick = { deleting = s.id }, modifier = Modifier.testTag("meeting-delete-${s.id}")) {
                                            Text("Supprimer")
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            } else {
                Text("Jusqu’à 15 minutes", style = MaterialTheme.typography.titleLarge)
                Text("Les pauses ne terminent pas la dictée. Armez ici, placez le curseur dans une autre app, touchez la bulle puis parlez. ✓ insère au curseur, ✕ annule (le texte reste dans les derniers textes). Glissez la bulle sur le ✕ en bas pour la masquer : elle revient au réveil du téléphone.")
                Text("État : " + when (state.phase) {
                    Phase.OFF -> "désarmé"
                    Phase.LOADING -> "chargement local, micro fermé"
                    Phase.ARMED -> "bulle prête, micro fermé"
                    Phase.RECORDING -> "enregistrement local"
                    Phase.PROCESSING -> "micro fermé, traitement et insertion"
                }, modifier = Modifier.testTag("session-state"))
                if (state.durationMs > 0) Text("Durée capturée : ${formatDuration(state.durationMs)} / 15 min")
                state.diagnostic?.let { Text(it, style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("session-diagnostic")) }
                Text(state.message, modifier = Modifier.testTag("session-message"))
                state.error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("session-error")) }
                if (state.phase == Phase.OFF) {
                    Button(onClick = arm, enabled = !state.preparing && !state.recovering && state.modelsReady && ready.microphone &&
                        ready.overlay && ready.accessibility && ready.notifications && state.transcript.isBlank(),
                        modifier = Modifier.fillMaxWidth().testTag("arm")) { Text("Armer la bulle — micro fermé") }
                } else {
                    if (state.phase == Phase.RECORDING) Button(onClick = stop, modifier = Modifier.testTag("stop")) { Text("Arrêter et traiter") }
                    OutlinedButton(onClick = disarm, modifier = Modifier.testTag("disarm")) { Text("Arrêter et désarmer") }
                    if (state.phase == Phase.ARMED) OutlinedButton(onClick = showBubble, modifier = Modifier.testTag("show-bubble")) { Text("Afficher la bulle") }
                }
                HorizontalDivider()
                Text("Préparation", style = MaterialTheme.typography.titleMedium)
                Text(if (state.modelsReady) "Modèles prêts : Parakeet + Silero" else state.preparation,
                    modifier = Modifier.testTag("models-status"))
                state.progress?.let { LinearProgressIndicator(progress = { it }, modifier = Modifier.fillMaxWidth()) }
                if (state.preparing) OutlinedButton(onClick = cancel, modifier = Modifier.testTag("cancel-preparation")) { Text("Annuler la préparation") }
                else OutlinedButton(onClick = prepare, enabled = state.phase == Phase.OFF && !state.recovering && !state.modelsReady,
                    modifier = Modifier.testTag("prepare")) { Text("Préparer / réessayer / reprendre") }
                Text("Le réseau sert uniquement à cette préparation explicite. Aucun audio ni texte n’est envoyé. Les modèles sont vérifiés par SHA-256, puis installés dans le stockage privé.")
                SetupRow("Microphone", ready.microphone, microphone, "setup-microphone")
                SetupRow("Bulle au-dessus des apps", ready.overlay, overlay, "setup-overlay")
                SetupRow("Notifications visibles", ready.notifications, notifications, "setup-notifications")
                SetupRow("Insertion par accessibilité", ready.accessibility, { disclosure = true }, "setup-accessibility")
                Text("Insertion directe au curseur sur Android 13+ (API 33), avec Gboard conservé : rien n’est remplacé, un mot en cours de frappe reste intact, jamais dans un mot de passe. Si l’insertion n’est pas confirmée (autre champ, texte sélectionné, éditeur incompatible), le texte est copié automatiquement dans le presse-papiers, ce qui remplace le clip précédent, et la bulle reste prête. Chaque dictée est gardée dans les 5 derniers textes. Le marquage sensible masque l’aperçu, sans protéger l’accès.")
            }
            if (state.transcript.isNotEmpty() || state.error != null) {
                HorizontalDivider()
                Text("Transcription conservée sur ce téléphone", style = MaterialTheme.typography.titleMedium)
                SelectionContainer { Text(state.transcript, modifier = Modifier.testTag("transcript")) }
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button(onClick = copy, modifier = Modifier.testTag("copy")) { Text("Copier") }
                    OutlinedButton(onClick = clear, enabled = state.phase == Phase.OFF && !state.preparing && !state.recovering,
                        modifier = Modifier.testTag("clear")) { Text("Effacer") }
                }
            }
            if (state.history.isNotEmpty() && !meeting) {
                HorizontalDivider()
                Text("Derniers textes dictés (${state.history.size}/5)", style = MaterialTheme.typography.titleMedium)
                state.history.forEachIndexed { index, item ->
                    SelectionContainer { Text(item, modifier = Modifier.testTag("history-$index")) }
                    OutlinedButton(onClick = { copyText(item) }, modifier = Modifier.testTag("history-copy-$index")) { Text("Copier") }
                }
                TextButton(onClick = clearHistory, modifier = Modifier.testTag("history-clear")) { Text("Effacer l’historique") }
            }
            if (state.stats.isNotEmpty() && !meeting) {
                HorizontalDivider()
                Text("Statistiques par semaine", style = MaterialTheme.typography.titleMedium)
                state.stats.take(8).forEachIndexed { index, week ->
                    Text(weekLine(week), modifier = Modifier.testTag("stats-$index"))
                }
                Text("Comptées sur ce téléphone uniquement, sans garder les textes.")
                TextButton(onClick = clearStats, modifier = Modifier.testTag("stats-clear")) { Text("Effacer les statistiques") }
            }
            if (meeting && !meetingState.busy) {
                HorizontalDivider()
                MeetingGainSection(meetingState.gainDb, saveMeetingGain)
                MeetingChainSection(meetingState.chainRecordings, saveMeetingChain)
            }
            if (!meeting) {
                HorizontalDivider()
                VocabularySection(state.vocabulary, saveVocabulary)
                HorizontalDivider()
                StopDelaySection(state.stopDelayMs, saveStopDelay)
            }
            Text("Pas de cloud, pas d’analytics. Aucun enregistrement automatique. Le texte reconnu reste ici jusqu’à Effacer ; le son n’est pas sauvegardé.")
            TextButton(onClick = { licenses = true }, modifier = Modifier.testTag("licenses")) { Text("Modèles, licences et limites") }
        }
    }
    if (notices) {
        val context = LocalContext.current
        val content = remember(context) { context.assets.open("THIRD_PARTY_NOTICES.txt").bufferedReader().use { it.readText() } }
        AlertDialog(onDismissRequest = { notices = false }, title = { Text("Notices des composants tiers") },
            text = { SelectionContainer { Text(content, modifier = Modifier.verticalScroll(rememberScrollState())) } },
            confirmButton = { TextButton(onClick = { notices = false }) { Text("Fermer") } })
    }
    deleting?.let { id ->
        AlertDialog(onDismissRequest = { deleting = null }, title = { Text("Supprimer cette réunion ?") },
            text = { Text("L’audio et les transcriptions de cette réunion seront effacés du téléphone. Cette action est définitive ; exportez ou copiez d’abord ce que vous voulez garder.") },
            confirmButton = { TextButton(onClick = { deleting = null; deleteMeeting(id) }, modifier = Modifier.testTag("meeting-delete-confirm")) { Text("Supprimer") } },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("Annuler") } })
    }
    if (disclosure) AlertDialog(onDismissRequest = { disclosure = false },
        title = { Text("Accès d’accessibilité") },
        text = { Text("Android autorise un accès large au contenu des fenêtres. LocalDictate consulte seulement le champ ciblé au démarrage explicite d’une dictée, puis le revalide pour insérer à l’arrêt. Aucune lecture permanente, aucun historique d’écran, aucun champ de mot de passe. Tout reste local. Vous pouvez révoquer cet accès dans les paramètres.") },
        confirmButton = { TextButton(onClick = { disclosure = false; accessibility() }, modifier = Modifier.testTag("accessibility-settings")) { Text("Ouvrir les réglages") } },
        dismissButton = { TextButton(onClick = { disclosure = false }) { Text("Annuler") } })
    if (licenses) AlertDialog(onDismissRequest = { licenses = false }, title = { Text("Reconnaissance locale") },
        text = { Column(Modifier.verticalScroll(rememberScrollState())) {
            Text("Parakeet TDT 0.6B v3 — NVIDIA, CC BY 4.0. Conversion ONNX int8 distribuée par k2-fsa/sherpa-onnx. Détection automatique FR/EN, ponctuation et majuscules selon la fiche NVIDIA. Aucune traduction ni reformulation.")
            Text("sherpa-onnx 2.0.1 — Apache 2.0 ; Silero VAD — Silero Team, MIT ; ONNX Runtime natif — Microsoft, MIT.")
            TextButton(onClick = { licenses = false; notices = true }) { Text("Lire les notices complètes") }
            Text("La qualité, la mémoire et le délai sur Pixel restent à vérifier sur téléphone. L’armement garde un service microphone visible ; seul le toucher de la bulle ouvre AudioRecord.")
        } }, confirmButton = { TextButton(onClick = { licenses = false }) { Text("Fermer") } })
}

private val weekStart = java.time.format.DateTimeFormatter.ofPattern("d MMM", java.util.Locale.FRENCH)

internal fun weekLine(week: WeekStats): String {
    val seconds = week.durationMs / 1000
    val duration = when {
        seconds < 60 -> "$seconds s"
        seconds < 3600 -> "${seconds / 60} min"
        else -> "${seconds / 3600} h ${(seconds / 60 % 60).toString().padStart(2, '0')}"
    }
    val words = java.text.NumberFormat.getIntegerInstance(java.util.Locale.FRENCH).format(week.words)
    return "Semaine du ${week.monday.format(weekStart)} : $words mots • $duration • " +
        "${week.dictations} dictée${if (week.dictations > 1) "s" else ""}"
}

/** The user's words: hotwords for the recognizer and replacements of the recognized text (see Vocabulary). */
@Composable
private fun VocabularySection(saved: String, save: (String) -> Unit) {
    var text by rememberSaveable(saved) { mutableStateOf(saved) }
    Text("Mes mots", style = MaterialTheme.typography.titleMedium)
    Text("Un mot ou un nom par ligne pour aider la reconnaissance (exemple : Parakeet). Pour corriger une erreur qui revient toujours : « entendu => voulu » (exemple : para kit => Parakeet). S’applique à la dictée et aux transcriptions de réunion. Reste sur ce téléphone.")
    OutlinedTextField(value = text, onValueChange = { text = it.take(be.arte.localdictate.asr.Vocabulary.MAX_TEXT_CHARS) },
        modifier = Modifier.fillMaxWidth().heightIn(min = 120.dp).testTag("vocabulary"),
        placeholder = { Text("Parakeet\npara kit => Parakeet") })
    Button(onClick = { save(text) }, enabled = text != saved, modifier = Modifier.testTag("vocabulary-save")) {
        Text("Enregistrer les mots")
    }
}

/** Live microphone level while a meeting records, drawn like the bubble's bars (newest on the right). */
@Composable
private fun MeetingLevelBars(levels: List<Float>) {
    val background = Color(0xFF6E4BA8)
    val ink = Color.White
    Canvas(Modifier.fillMaxWidth().height(64.dp).testTag("meeting-level")) {
        drawRoundRect(background, cornerRadius = CornerRadius(size.height / 2f))
        if (levels.isEmpty()) return@Canvas
        val barWidth = 3.dp.toPx()
        val gap = 4.dp.toPx()
        val fits = ((size.width - 32.dp.toPx() + gap) / (barWidth + gap)).toInt().coerceAtLeast(1)
        val shown = levels.takeLast(fits)
        var x = (size.width - (shown.size * barWidth + (shown.size - 1) * gap)) / 2f
        val middle = size.height / 2f
        val maxHalf = size.height * 0.36f
        shown.forEach { level ->
            // Same scale as the bubble: speech RMS is small, a square root spreads soft and loud voices.
            val half = maxOf(barWidth / 2f, kotlin.math.sqrt((level * 6f).coerceIn(0f, 1f)) * maxHalf)
            drawRoundRect(ink, topLeft = Offset(x, middle - half), size = Size(barWidth, half * 2f),
                cornerRadius = CornerRadius(barWidth / 2f))
            x += barWidth + gap
        }
    }
}

/** Ceiling of the automatic gain used when a meeting is transcribed (see MeetingGain). */
@Composable
private fun MeetingGainSection(saved: Int, save: (Int) -> Unit) {
    Text("Réglages réunion", style = MaterialTheme.typography.titleMedium)
    Text("Gain automatique à la transcription : ${MeetingGain.label(saved)}", modifier = Modifier.testTag("meeting-gain-label"))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        MeetingGain.CHOICES_DB.forEach { db ->
            ModeButton(if (db == 0) "Aucun" else "+$db", selected = db == saved, tag = "meeting-gain-$db") { save(db) }
        }
    }
    Text("Monte les voix lointaines ou faibles (un professeur au fond de la salle) sans jamais saturer. Le son enregistré n’est pas modifié : vous pouvez retranscrire la même réunion avec un autre réglage.")
}

/** Optional chaining of 2-hour recordings (off by default; see MeetingService). */
@Composable
private fun MeetingChainSection(enabled: Boolean, save: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text("Enchaîner les enregistrements", Modifier.weight(1f))
        Switch(checked = enabled, onCheckedChange = save, modifier = Modifier.testTag("meeting-chain"))
    }
    Text("À la limite de 2 heures, l’enregistrement est clôturé et un nouveau démarre aussitôt (une fraction de seconde peut manquer entre les deux). Chaque partie se transcrit à part. S’arrête si l’espace ou le nombre de réunions (20) est atteint. Désactivé par défaut.")
}

/** Microphone kept open after ✓ so the last words are not cut (see StopDelay). */
@Composable
private fun StopDelaySection(saved: Long, save: (Long) -> Unit) {
    var value by remember(saved) { mutableFloatStateOf(saved.toFloat()) }
    Text("Réglages", style = MaterialTheme.typography.titleMedium)
    Text("Délai avant l’arrêt du micro : ${StopDelay.label(StopDelay.clamp(value.toLong()))}",
        modifier = Modifier.testTag("stop-delay-label"))
    Slider(value = value, onValueChange = { value = it },
        onValueChangeFinished = { save(StopDelay.clamp(value.toLong())) },
        valueRange = 0f..StopDelay.MAX_MS.toFloat(),
        steps = (StopDelay.MAX_MS / StopDelay.STEP_MS).toInt() - 1,
        modifier = Modifier.fillMaxWidth().testTag("stop-delay"))
    Text("Après ✓, le micro reste ouvert ce temps-là pour ne pas couper les derniers mots (0,5 s par défaut). ✕ arrête tout de suite.")
}

@Composable
private fun SetupRow(label: String, granted: Boolean, action: () -> Unit, tag: String) {
    OutlinedButton(onClick = action, modifier = Modifier.fillMaxWidth().testTag(tag)) {
        Text("$label : ${if (granted) "autorisé" else "à autoriser"}")
    }
}

/** The selected mode is the filled button, the other one outlined. */
@Composable
private fun ModeButton(label: String, selected: Boolean, tag: String, onClick: () -> Unit) {
    val modifier = Modifier.testTag(tag).semantics { this.selected = selected }
    // One composable for both looks: swapping Button/OutlinedButton dropped the focused node on click, and focus
    // jumped to another button lower on the screen, scrolling the page away from its top.
    OutlinedButton(onClick = onClick, modifier = modifier,
        colors = if (selected) ButtonDefaults.buttonColors() else ButtonDefaults.outlinedButtonColors(),
        border = if (selected) null else ButtonDefaults.outlinedButtonBorder()) { Text(label) }
}
