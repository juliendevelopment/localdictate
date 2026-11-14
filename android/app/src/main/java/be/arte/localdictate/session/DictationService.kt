package be.arte.localdictate.session

import android.Manifest
import android.app.*
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.os.Build
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import be.arte.localdictate.DictateApplication
import be.arte.localdictate.MainActivity
import be.arte.localdictate.R
import be.arte.localdictate.asr.*
import be.arte.localdictate.audio.MediaPauser
import be.arte.localdictate.audio.MicrophoneCapture
import be.arte.localdictate.audio.Pcm16
import be.arte.localdictate.audio.VoiceGain
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
    private var mediaPauser: MediaPauser? = null
    private var wakeLock: PowerManager.WakeLock? = null
    @Volatile private var bubble: FloatingControl? = null
    private var cancelRequested = false
    /** A bubble hidden by dragging it onto ✕ comes back when the phone is woken or unlocked. */
    private val wake = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) { bubble?.show() }
    }
    private var wakeRegistered = false
    private var attached = false
    private var disarming = false
    private var graceful = false
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == DISARM) { disarm(); return START_NOT_STICKY }
        if (intent?.action == SHOW) { if (attached) bubble?.show() else stopSelf(); return START_NOT_STICKY }
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
            bubble = FloatingControl(this, onStart = ::toggleCapture, onConfirm = ::stopCapture,
                onCancel = ::cancelCapture, onOpen = ::toggleCapture)
            val filter = IntentFilter().apply { addAction(Intent.ACTION_USER_PRESENT); addAction(Intent.ACTION_SCREEN_ON) }
            if (Build.VERSION.SDK_INT >= 33) registerReceiver(wake, filter, RECEIVER_NOT_EXPORTED) else registerReceiver(wake, filter)
            wakeRegistered = true
            var shownText: String? = null
            scope.launch { controller.state.collect { state ->
                bubble?.render(state)
                val text = state.error ?: when (state.phase) {
                    Phase.RECORDING -> "Enregistrement local — touchez ✓ pour insérer"
                    Phase.PROCESSING -> "Traitement local, microphone fermé"
                    else -> "Bulle armée, microphone fermé"
                }
                // Only when the text changes: the duration ticks many times per second while dictating.
                if (text != shownText) { shownText = text; manager.notify(1, notification(text)) }
            } }
            loadJob = scope.launch {
                try {
                    withContext(Dispatchers.Default) {
                        // Assign inside the block so cancellation cannot lose a returned native handle.
                        engine = LocalRecognizer(controller.models.modelFiles, controller.models.bpeVocab).also { it.load() }
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
        val tapMs = SystemClock.elapsedRealtime()
        cancelRequested = false
        val diagnostic = DictationDiagnostic(tapMs)
        val recorder = MicrophoneCapture(applicationContext)
        microphone = recorder
        controller.update { it.copy(phase = Phase.RECORDING, micLive = false, durationMs = 0, error = null, notice = null,
            diagnostic = null, message = "Ouverture du micro…") }
        // Undispatched: everything up to run.await() below runs now, on the main thread, in the tap's frame.
        sessionJob = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            var id: String? = null
            var detector: SileroSegmenter? = null
            var targetService: InsertionService? = null
            var completed = false
            try {
                val decoder = checkNotNull(engine)
                // Read once per dictation, from memory: an edit saved during a dictation applies to the next one.
                val vocabulary = controller.vocabulary
                // Silero loads on the segmentation thread while PCM waits in the queue.
                val segmenter = object : SpeechSegmenter {
                    private fun vad() = detector ?: decoder.segmenter().also { detector = it }
                    override fun accept(samples: FloatArray) = vad().accept(samples)
                    override fun finish() = vad().finish()
                }
                var live = false
                // Soft speech close to the microphone is raised before VAD and ASR; loud speech is left unchanged.
                val voiceGain = VoiceGain()
                val pipeline = DictationPipeline(segmenter,
                    capture = { emit -> recorder.read(onSilenced = { silenced ->
                        if (silenced) {
                            controller.update { it.copy(phase = Phase.PROCESSING, error = "Microphone rendu muet par Android : dictée à vérifier, copie manuelle requise.") }
                            recorder.stop()
                        }
                    }, emit = {
                        if (!live) { live = true; controller.update { state -> state.copy(micLive = true) } }
                        val samples = voiceGain.apply(it.samples)
                        diagnostic.audio(it.originMs, it.samples, voiceGain.gain)
                        bubble?.level(Pcm16.level(samples))
                        emit(samples)
                    }) },
                    stopCapture = recorder::stop,
                    recognize = { samples ->
                        val begin = SystemClock.elapsedRealtime()
                        decoder.recognize(samples, vocabulary).also { diagnostic.decoded(samples.size, SystemClock.elapsedRealtime() - begin) }
                    },
                    onText = { text ->
                        val record = withContext(Dispatchers.IO) { controller.journal.append(checkNotNull(id), text) }
                        controller.update { it.copy(transcript = record.text) }
                    },
                    onDraining = { controller.update { it.copy(phase = Phase.PROCESSING) } },
                    onError = { message -> controller.update { it.copy(phase = Phase.PROCESSING, error = message) } },
                    onDuration = { duration -> controller.update { it.copy(durationMs = duration) } },
                    // The journal is opened while the microphone starts; no text is appended before it exists.
                    start = { id = controller.journal.begin().id },
                )
                // The microphone opens now, on its own thread. The target, the media pause and the wake lock follow
                // while it starts, so the first words spoken right after the tap are captured.
                val run = async(start = CoroutineStart.UNDISPATCHED) { pipeline.run() }
                targetService = InsertionService.connected
                val hasTarget = runCatching { targetService?.snapshot() == true }.getOrDefault(false)
                val startRefusal = if (hasTarget) "" else targetService?.lastRefusal.orEmpty()
                mediaPauser = MediaPauser(applicationContext).also { it.pause() }
                wakeLock = getSystemService(PowerManager::class.java)
                    .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "LocalDictate:dictation").apply {
                        setReferenceCounted(false)
                        // Bound a stuck native call as well; no lock while merely armed.
                        acquire(30L * 60 * 1000)
                    }
                controller.update { if (it.phase != Phase.RECORDING || it.error != null) it else it.copy(
                    message = if (hasTarget) "Dictée locale. Les pauses ne terminent pas la session."
                        else "Aucun champ compatible : la dictée sera copiée dans le presse-papiers à l’arrêt.") }
                val failure = run.await()
                // The journal could not be opened: the microphone is closed and the error shown; nothing to keep.
                val sessionId = id ?: return@launch
                val interrupted = failure != null || controller.state.value.error != null
                val cancelled = cancelRequested
                val record = withContext(Dispatchers.IO) { controller.journal.finish(sessionId, interrupted) }
                completed = true
                // Saved before any insertion attempt: a dictated text is never only in the target field.
                val remembered = record.text.isNotBlank() && controller.remember(record.text)
                if (record.text.isNotBlank()) withContext(Dispatchers.IO) {
                    controller.count(record.text, controller.state.value.durationMs)
                }
                val service = targetService
                val message = when {
                    interrupted -> "Dictée interrompue ou surchargée : vérifiez le texte récupéré, aucun collage automatique."
                    record.text.isBlank() -> "Aucune parole reconnue. Vous pouvez réessayer."
                    cancelled -> "Dictée annulée : rien n’est inséré ni copié."
                    !hasTarget || service == null || service !== InsertionService.connected ->
                        "Pas de champ compatible au démarrage (${startRefusal.ifBlank { "service d’accessibilité non connecté" }})."
                    else -> {
                        val claimed = withContext(Dispatchers.IO) { controller.journal.claimInsertion(sessionId) }
                        if (claimed) service.insert(record.text) else "Insertion déjà tentée ou interrompue : vérifiez le champ."
                    }
                }
                if (message == InsertionService.OBSERVED) {
                    // Inserted and observed: nothing left to recover, so the next dictation can start.
                    withContext(NonCancellable + Dispatchers.IO) { controller.journal.clear() }
                    controller.update { it.copy(transcript = "", notice = null, message = "Texte inséré dans le champ.") }
                } else if (record.text.isNotBlank()) {
                    // Not confirmed: copy automatically so the text can be pasted by hand.
                    val copied = !cancelled && runCatching { PrivateClipboard(applicationContext).put(record.text) }.isSuccess
                    // The text is in the history (and the clipboard): free the bubble for the next dictation.
                    val released = remembered && !interrupted &&
                        runCatching { withContext(NonCancellable + Dispatchers.IO) { controller.journal.clear() } }.isSuccess
                    controller.update { it.copy(
                        transcript = if (released) "" else it.transcript,
                        notice = when {
                            cancelled -> "Annulée • texte gardé dans l’app"
                            copied -> "Non inséré • texte copié, collez-le"
                            else -> "Non inséré • texte dans l’app"
                        },
                        message = message + (if (copied) " Texte copié dans le presse-papiers : collez-le (appui long)." else "") +
                            (if (remembered) " Il reste dans les derniers textes dictés." else "")) }
                } else controller.update { it.copy(message = message) }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: OutOfMemoryError) { controller.update { it.copy(error = "Mémoire insuffisante, texte reconnu conservé.") } }
            catch (_: Throwable) { controller.update { it.copy(error = "Dictée interrompue : texte reconnu conservé, copie manuelle disponible.") } }
            finally {
                recorder.stop()
                mediaPauser?.resume(); mediaPauser = null
                wakeLock?.let { if (it.isHeld) it.release() }; wakeLock = null
                withContext(NonCancellable + Dispatchers.Default) { detector?.close() }
                targetService?.forget()
                microphone = null
                controller.update { it.copy(diagnostic = diagnostic.line()) }
                val opened = id
                if (!completed && opened != null) withContext(NonCancellable + Dispatchers.IO) {
                    runCatching { controller.journal.finish(opened, interrupted = true) }
                }
                if (disarming) { graceful = true; stopSelf() }
                else if (attached) controller.update { it.copy(phase = Phase.ARMED) }
            }
        }
    }
    fun showBubble() { bubble?.show() }
    /** ✕ on the bubble: stop listening and insert nothing; the text is still kept in the history. */
    fun cancelCapture() {
        if (controller.state.value.phase != Phase.RECORDING) return
        cancelRequested = true
        stopCapture()
    }
    fun stopCapture() {
        if (controller.state.value.phase == Phase.RECORDING) {
            microphone?.stop()
            mediaPauser?.resume()
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
        mediaPauser?.resume(); mediaPauser = null
        wakeLock?.let { if (it.isHeld) it.release() }; wakeLock = null
        bubble?.close()
        if (wakeRegistered) { runCatching { unregisterReceiver(wake) }; wakeRegistered = false }
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
            .setContentIntent(open)
            .addAction(Notification.Action.Builder(null, "Afficher la bulle", PendingIntent.getService(this, 2,
                Intent(this, DictationService::class.java).setAction(SHOW), PendingIntent.FLAG_IMMUTABLE)).build())
            .addAction(Notification.Action.Builder(null, "Arrêter et désarmer", stop).build()).build()
    }
    companion object {
        const val ARM = "be.arte.localdictate.ARM"
        const val DISARM = "be.arte.localdictate.DISARM"
        const val SHOW = "be.arte.localdictate.SHOW"
        internal const val CHANNEL = "dictation"
    }
}
