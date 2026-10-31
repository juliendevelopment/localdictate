package be.arte.localdictate.session

import android.Manifest
import android.app.*
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import be.arte.localdictate.DictateApplication
import be.arte.localdictate.MainActivity
import be.arte.localdictate.R
import be.arte.localdictate.asr.*
import be.arte.localdictate.audio.MicrophoneCapture
import be.arte.localdictate.insertion.InsertionService
import be.arte.localdictate.insertion.PrivateClipboard
import kotlinx.coroutines.*

/** Created only from a resumed Activity; bubble actions call this existing service directly. */
class DictationService : Service() {
    private val controller get() = (application as DictateApplication).controller
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var loadJob: Job? = null
    private var sessionJob: Job? = null
    private var engine: LocalRecognizer? = null
    private var microphone: MicrophoneCapture? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var bubble: FloatingControl? = null
    private var attached = false
    private var disarming = false
    private var graceful = false
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == DISARM) { disarm(); return START_NOT_STICKY }
        if (intent?.action != ARM || attached || controller.state.value.phase != Phase.LOADING) {
            if (!attached) stopSelf()
            return START_NOT_STICKY
        }
        attached = true
        controller.service = this
        try {
            check(checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED)
            check(Settings.canDrawOverlays(this))
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(NotificationChannel(CHANNEL, "Contrôle de dictée", NotificationManager.IMPORTANCE_LOW))
            check(manager.areNotificationsEnabled() && manager.getNotificationChannel(CHANNEL)?.importance != NotificationManager.IMPORTANCE_NONE)
            // Promote before creating any native model or AudioRecord.
            if (Build.VERSION.SDK_INT >= 30) {
                startForeground(1, notification("Bulle armée, microphone fermé"), ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
            } else startForeground(1, notification("Bulle armée, microphone fermé"))
            bubble = FloatingControl(this, ::toggleCapture, ::disarm)
            scope.launch { controller.state.collect { state ->
                bubble?.render(state)
                manager.notify(1, notification(state.error ?: when (state.phase) {
                    Phase.RECORDING -> "Enregistrement local — touchez Arrêter et désarmer"
                    Phase.PROCESSING -> "Traitement local, microphone fermé"
                    else -> "Bulle armée, microphone fermé"
                }))
            } }
            loadJob = scope.launch {
                try {
                    withContext(Dispatchers.Default) {
                        // Assign inside the block so cancellation cannot lose a returned native handle.
                        engine = LocalRecognizer(controller.models.modelFiles).also { it.load() }
                    }
                    controller.update { it.copy(phase = Phase.ARMED, message = "Bulle prête. Touchez un champ, puis la bulle pour dicter. Micro fermé.") }
                } catch (_: CancellationException) { /* onDestroy releases handles after load has unwound */ }
                catch (_: OutOfMemoryError) { failLoad("Mémoire insuffisante pour Parakeet.") }
                catch (_: Throwable) { failLoad("Chargement natif impossible. Vérifiez les modèles et la compatibilité arm64.") }
            }
        } catch (_: Exception) {
            controller.update { it.copy(error = "Android a refusé le service ou la bulle. Réarmez depuis l’écran visible.") }
            stopSelf()
        }
        return START_NOT_STICKY
    }
    private fun failLoad(message: String) {
        controller.update { it.copy(error = message) }
        stopSelf()
    }
    fun toggleCapture() {
        when (controller.state.value.phase) {
            Phase.ARMED -> {
                if (controller.state.value.transcript.isNotEmpty() || controller.state.value.error != null) {
                    runCatching {
                        startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    }.onFailure { controller.update { it.copy(error = "Ouvrez LocalDictate depuis son icône pour récupérer le texte.") } }
                } else startCapture()
            }
            Phase.RECORDING -> stopCapture()
            else -> Unit
        }
    }
    private fun startCapture() {
        if (sessionJob?.isActive == true || disarming) return
        if (controller.state.value.transcript.isNotBlank()) {
            controller.update { it.copy(message = "Copiez puis désarmez et effacez le texte précédent avant une nouvelle dictée.") }
            return
        }
        val targetService = InsertionService.connected
        val hasTarget = runCatching { targetService?.snapshot() == true }.getOrDefault(false)
        val startRefusal = if (hasTarget) "" else targetService?.lastRefusal.orEmpty()
        val recorder = MicrophoneCapture(applicationContext)
        microphone = recorder
        controller.update { it.copy(phase = Phase.RECORDING, micLive = false, durationMs = 0, error = null, notice = null,
            message = if (hasTarget) "Dictée locale. Les pauses ne terminent pas la session."
                else "Aucun champ compatible : la dictée sera copiée dans le presse-papiers à l’arrêt.") }
        sessionJob = scope.launch {
            var id: String? = null
            var detector: SileroSegmenter? = null
            var completed = false
            try {
                wakeLock = getSystemService(PowerManager::class.java)
                    .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "LocalDictate:dictation").apply {
                        setReferenceCounted(false)
                        // Bound a stuck native call as well; no lock while merely armed.
                        acquire(30L * 60 * 1000)
                    }
                id = withContext(Dispatchers.IO) { controller.journal.begin().id }
                val sessionId = id
                val decoder = checkNotNull(engine)
                // The microphone opens now; Silero loads on the segmentation thread while PCM waits in the queue,
                // so the first words spoken right after the tap are kept.
                val segmenter = object : SpeechSegmenter {
                    private fun vad() = detector ?: decoder.segmenter().also { detector = it }
                    override fun accept(samples: FloatArray) = vad().accept(samples)
                    override fun finish() = vad().finish()
                }
                var live = false
                val failure = DictationPipeline(segmenter,
                    capture = { emit -> recorder.read(onSilenced = { silenced ->
                        if (silenced) {
                            controller.update { it.copy(phase = Phase.PROCESSING, error = "Microphone rendu muet par Android : dictée à vérifier, copie manuelle requise.") }
                            recorder.stop()
                        }
                    }, emit = {
                        if (!live) { live = true; controller.update { state -> state.copy(micLive = true) } }
                        emit(it.samples)
                    }) },
                    stopCapture = recorder::stop,
                    recognize = { decoder.recognize(it) },
                    onText = { text ->
                        val record = withContext(Dispatchers.IO) { controller.journal.append(sessionId, text) }
                        controller.update { it.copy(transcript = record.text) }
                    },
                    onDraining = { controller.update { it.copy(phase = Phase.PROCESSING) } },
                    onError = { message -> controller.update { it.copy(phase = Phase.PROCESSING, error = message) } },
                    onDuration = { duration -> controller.update { it.copy(durationMs = duration) } },
                ).run()
                val interrupted = failure != null || controller.state.value.error != null
                val record = withContext(Dispatchers.IO) { controller.journal.finish(sessionId, interrupted) }
                completed = true
                // Saved before any insertion attempt: a dictated text is never only in the target field.
                val remembered = record.text.isNotBlank() && controller.remember(record.text)
                val message = when {
                    interrupted -> "Dictée interrompue ou surchargée : vérifiez le texte récupéré, aucun collage automatique."
                    record.text.isBlank() -> "Aucune parole reconnue. Vous pouvez réessayer."
                    !hasTarget || targetService !== InsertionService.connected ->
                        "Pas de champ compatible au démarrage (${startRefusal.ifBlank { "service d’accessibilité non connecté" }})."
                    else -> {
                        val claimed = withContext(Dispatchers.IO) { controller.journal.claimInsertion(sessionId) }
                        if (claimed) targetService!!.insert(record.text) else "Insertion déjà tentée ou interrompue : vérifiez le champ."
                    }
                }
                if (message == InsertionService.OBSERVED) {
                    // Inserted and observed: nothing left to recover, so the next dictation can start.
                    withContext(NonCancellable + Dispatchers.IO) { controller.journal.clear() }
                    controller.update { it.copy(transcript = "", notice = null, message = "Texte inséré dans le champ.") }
                } else if (record.text.isNotBlank()) {
                    // Not confirmed: copy automatically so the text can be pasted by hand.
                    val copied = runCatching { PrivateClipboard(applicationContext).put(record.text) }.isSuccess
                    // The text is in the history (and the clipboard): free the bubble for the next dictation.
                    val released = remembered && !interrupted &&
                        runCatching { withContext(NonCancellable + Dispatchers.IO) { controller.journal.clear() } }.isSuccess
                    controller.update { it.copy(
                        transcript = if (released) "" else it.transcript,
                        notice = if (copied) "Non inséré • texte copié, collez-le" else "Non inséré • texte dans l’app",
                        message = message + (if (copied) " Texte copié dans le presse-papiers : collez-le (appui long)." else "") +
                            (if (remembered) " Il reste dans les derniers textes dictés." else "")) }
                } else controller.update { it.copy(message = message) }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: OutOfMemoryError) { controller.update { it.copy(error = "Mémoire insuffisante, texte reconnu conservé.") } }
            catch (_: Throwable) { controller.update { it.copy(error = "Dictée interrompue : texte reconnu conservé, copie manuelle disponible.") } }
            finally {
                recorder.stop()
                wakeLock?.let { if (it.isHeld) it.release() }; wakeLock = null
                withContext(NonCancellable + Dispatchers.Default) { detector?.close() }
                targetService?.forget()
                microphone = null
                if (!completed && id != null) withContext(NonCancellable + Dispatchers.IO) {
                    runCatching { controller.journal.finish(id, interrupted = true) }
                }
                if (disarming) { graceful = true; stopSelf() }
                else if (attached) controller.update { it.copy(phase = Phase.ARMED) }
            }
        }
    }
    fun stopCapture() {
        if (controller.state.value.phase == Phase.RECORDING) {
            microphone?.stop()
            controller.update { it.copy(phase = Phase.PROCESSING, message = "Micro fermé. Traitement des derniers segments avant insertion…") }
        }
    }
    fun disarm() {
        if (!attached) { stopSelf(); return }
        disarming = true
        bubble?.close(); bubble = null
        if (sessionJob?.isActive == true) stopCapture()
        else { graceful = true; stopSelf() }
    }
    override fun onDestroy() {
        microphone?.stop()
        wakeLock?.let { if (it.isHeld) it.release() }; wakeLock = null
        bubble?.close()
        val loading = loadJob
        val session = sessionJob
        scope.cancel()
        // Never release handles concurrently with blocking JNI load/decode.
        val owned = attached
        attached = false
        if (owned) {
            controller.service = null
            controller.update { it.copy(phase = Phase.PROCESSING) }
        }
        CoroutineScope(Dispatchers.Default).launch {
            loading?.join(); session?.join()
            runCatching { engine?.close() }
            if (owned) withContext(Dispatchers.Main) { controller.serviceEnded(interrupted = !graceful) }
        }
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }
    private fun notification(message: String): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, 1, Intent(this, DictationService::class.java).setAction(DISARM), PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, CHANNEL).setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle("LocalDictate").setContentText(message).setOngoing(true).setOnlyAlertOnce(true)
            .setContentIntent(open).addAction(Notification.Action.Builder(null, "Arrêter et désarmer", stop).build()).build()
    }
    companion object {
        const val ARM = "be.arte.localdictate.ARM"
        const val DISARM = "be.arte.localdictate.DISARM"
        internal const val CHANNEL = "dictation"
    }
}
