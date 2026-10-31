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
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import be.arte.localdictate.insertion.InsertionService
import be.arte.localdictate.session.*

data class Readiness(val microphone: Boolean = false, val overlay: Boolean = false,
    val accessibility: Boolean = false, val notifications: Boolean = false)

class DictationViewModel(application: Application) : ViewModel() {
    val controller = (application as DictateApplication).controller
    class Factory(private val application: Application) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = DictationViewModel(application) as T
    }
}

class MainActivity : ComponentActivity() {
    private var readiness by mutableStateOf(Readiness())
    private val controller get() = (application as DictateApplication).controller
    private val permissions = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        refreshPermissions() // Never arms or captures on a permission dialog return.
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val model: DictationViewModel = viewModel(factory = DictationViewModel.Factory(application))
            val state by model.controller.state.collectAsState()
            MaterialTheme {
                HomeScreen(state, readiness,
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
                    showBubble = { controller.service?.showBubble() })
            }
        }
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
}

@Composable
private fun HomeScreen(
    state: DictationState, ready: Readiness, prepare: () -> Unit, cancel: () -> Unit,
    microphone: () -> Unit, notifications: () -> Unit, overlay: () -> Unit, accessibility: () -> Unit,
    arm: () -> Unit, stop: () -> Unit, disarm: () -> Unit, copy: () -> Unit, clear: () -> Unit,
    copyText: (String) -> Unit, clearHistory: () -> Unit, showBubble: () -> Unit = {},
) {
    var meeting by rememberSaveable { mutableStateOf(false) }
    var disclosure by rememberSaveable { mutableStateOf(false) }
    var licenses by rememberSaveable { mutableStateOf(false) }
    var notices by rememberSaveable { mutableStateOf(false) }
    Surface(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("LocalDictate", style = MaterialTheme.typography.headlineLarge)
            Text("Dictée locale • français et anglais", style = MaterialTheme.typography.titleMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = { meeting = false }, modifier = Modifier.testTag("mode-dictation")) { Text("Dictée") }
                OutlinedButton(onClick = { meeting = true }, modifier = Modifier.testTag("mode-meeting")) { Text("Réunion") }
            }
            if (meeting) {
                Text("Jusqu’à 2 heures", style = MaterialTheme.typography.titleLarge)
                Text("Réunion : pas encore disponible. Une future version proposera transcription et résumé écrit locaux, séparés de la dictée. Aucun collage automatique dans une autre application.")
                Text("Aucun enregistrement ni résumé de réunion n’est implémenté.", modifier = Modifier.testTag("meeting-unavailable"))
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
                if (state.durationMs > 0) Text("Durée capturée : ${state.durationMs / 1000} s / 900 s")
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
    if (disclosure) AlertDialog(onDismissRequest = { disclosure = false },
        title = { Text("Accès d’accessibilité") },
        text = { Text("Android autorise un accès large au contenu des fenêtres. LocalDictate consulte seulement le champ ciblé au démarrage explicite d’une dictée, puis le revalide pour insérer à l’arrêt. Aucune lecture permanente, aucun historique d’écran, aucun champ de mot de passe. Tout reste local. Vous pouvez révoquer cet accès dans les paramètres.") },
        confirmButton = { TextButton(onClick = { disclosure = false; accessibility() }, modifier = Modifier.testTag("accessibility-settings")) { Text("Ouvrir les réglages") } },
        dismissButton = { TextButton(onClick = { disclosure = false }) { Text("Annuler") } })
    if (licenses) AlertDialog(onDismissRequest = { licenses = false }, title = { Text("Reconnaissance locale") },
        text = { Column(Modifier.verticalScroll(rememberScrollState())) {
            Text("Parakeet TDT 0.6B v3 — NVIDIA, CC BY 4.0. Conversion ONNX int8 distribuée par k2-fsa/sherpa-onnx. Détection automatique FR/EN, ponctuation et majuscules selon la fiche NVIDIA. Aucune traduction ni reformulation.")
            Text("sherpa-onnx 2.0.1 — Apache 2.0 ; Silero VAD — Silero Team, MIT ; ONNX Runtime natif — Microsoft, MIT ; Commons Compress — Apache 2.0.")
            TextButton(onClick = { licenses = false; notices = true }) { Text("Lire les notices complètes") }
            Text("La qualité, la mémoire et le délai sur Pixel restent à vérifier sur téléphone. L’armement garde un service microphone visible ; seul le toucher de la bulle ouvre AudioRecord.")
        } }, confirmButton = { TextButton(onClick = { licenses = false }) { Text("Fermer") } })
}

@Composable
private fun SetupRow(label: String, granted: Boolean, action: () -> Unit, tag: String) {
    OutlinedButton(onClick = action, modifier = Modifier.fillMaxWidth().testTag(tag)) {
        Text("$label : ${if (granted) "autorisé" else "à autoriser"}")
    }
}
