package be.arte.localdictate.meeting

import android.Manifest
import android.app.*
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import be.arte.localdictate.DictateApplication
import be.arte.localdictate.MainActivity
import be.arte.localdictate.R
import be.arte.localdictate.asr.LocalRecognizer
import be.arte.localdictate.asr.SileroSegmenter
import be.arte.localdictate.audio.MicrophoneCapture
import kotlinx.coroutines.*

/** No boot receiver, no sticky restart, no capture in onCreate. One explicit launch token, one job. */
class MeetingService : Service() {
    private val controller get() = (application as DictateApplication).meetings
    private val dictation get() = (application as DictateApplication).controller
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null
    @Volatile private var microphone: MicrophoneCapture? = null
    @Volatile private var captureStage = false
    @Volatile private var stopRequested = false
    @Volatile private var processAfterStop = false
    @Volatile private var cancelled = false
    @Volatile private var destroyed = false
    @Volatile private var interruption = ""
    private var attached = false
    private var wakeLock: PowerManager.WakeLock? = null
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == STOP) {
            if (attached) stopOrCancel() else stopSelf()
            return START_NOT_STICKY
        }
        if (attached) return START_NOT_STICKY
        val request = if (intent?.action == START) controller.consume(intent.getStringExtra("token")) else null
        if (request == null) { stopSelf(); return START_NOT_STICKY }
        attached = true
        captureStage = request.id == null
        controller.service = this
        try {
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(NotificationChannel(CHANNEL, "Réunion locale", NotificationManager.IMPORTANCE_LOW))
            check(manager.areNotificationsEnabled() && manager.getNotificationChannel(CHANNEL)?.importance != NotificationManager.IMPORTANCE_NONE)
            if (request.id == null) check(checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED)
            // Must precede private IO, AudioRecord and all native model loading.
            promote(recording = request.id == null)
            job = scope.launch { execute(request) }
        } catch (_: Exception) {
            controller.update { it.copy(error = "Service réunion refusé : vérifiez les permissions depuis l’écran visible.") }
            stopSelf()
        }
        return START_NOT_STICKY
    }
    fun stopOrCancel() {
        if (captureStage) {
            stopRequested = true
            processAfterStop = true
            microphone?.stop()
            controller.update { it.copy(message = "Micro fermé ; vidage du son accepté avant transcription.") }
        } else {
            cancelled = true
            microphone?.stop()
            controller.update { it.copy(message = "Annulation demandée ; le décodage natif en cours termine sa sauvegarde. Audio et texte conservés.") }
        }
    }
    private fun wake(timeoutMs: Long) {
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "LocalDictate:meeting").apply {
            setReferenceCounted(false); acquire(timeoutMs)
        }
    }
    private suspend fun execute(request: MeetingLaunch) {
        val repository = controller.repository
        var session: MeetingSession? = null
        var engine: LocalRecognizer? = null
        var vad: SileroSegmenter? = null
        var timer: Job? = null
        fun persist(next: MeetingSession) {
            repository.save(next); session = next
            controller.update { it.copy(selected = next, phase = next.phase, durationMs = next.durationMs,
                processedMs = next.processedMs, message = next.diagnostic, page = null) }
        }
        try {
            check(!destroyed)
            if (request.id == null) {
                wake(PcmRecording.MAX_DURATION_MS + 30_000)
                persist(repository.create())
                val recorder = MicrophoneCapture(applicationContext)
                microphone = recorder
                if (stopRequested || destroyed) recorder.stop()
                persist(checkNotNull(session).copy(phase = MeetingPhase.RECORDING, diagnostic = "Enregistrement local ; transcription après arrêt. Accord des participants à votre charge."))
                timer = scope.launch {
                    delay(PcmRecording.MAX_DURATION_MS)
                    interruption = "Durée murale maximale atteinte ; micro arrêté."
                    recorder.stop()
                }
                var lastSaved = 0L
                val result = PcmRecording.create(repository.audio(checkNotNull(session).id), repository.guard).use { recording ->
                    MeetingCapturePipeline(object : MeetingRecorder {
                        override suspend fun read(emit: (ByteArray) -> Unit) = recorder.read(onSilenced = { silenced ->
                            if (silenced) { interruption = "Microphone rendu muet par Android ; capture partielle à vérifier."; recorder.stop() }
                        }, emit = { emit(it.pcm16) })
                        override fun stop() = recorder.stop()
                    }, append = { recording.append(it) }, duration = { ms ->
                        controller.update { it.copy(durationMs = ms) }
                        // Session metadata costs two syncs; the audio itself is synced about every second.
                        if (ms - lastSaved >= 10_000) {
                            persist(checkNotNull(session).copy(durationMs = ms)); lastSaved = ms
                        }
                    }).run()
                }
                microphone = null
                captureStage = false
                timer.cancel(); timer = null
                persist(checkNotNull(session).copy(phase = MeetingPhase.RECORDED, durationMs = result.samples * 1000 / 16_000,
                    diagnostic = listOf(result.diagnostic, interruption).filter { it.isNotBlank() }.joinToString(" ").ifBlank { "Audio privé sauvegardé." }))
                if (!stopRequested && !destroyed) alertStopped(listOf(result.diagnostic, interruption).filter { it.isNotBlank() }
                    .joinToString(" ").ifBlank { "Enregistrement arrêté." })
                if (!processAfterStop || destroyed || result.diagnostic.isNotBlank() || interruption.isNotBlank()) return
                // Capture has fully drained and closed before the microphone FGS type is removed.
                withContext(Dispatchers.Main) { promote(recording = false) }
            } else {
                persist(repository.read(request.id))
                val diagnostic = repository.recoverAudio(request.id) // Explicit UI action, original preserved before repair.
                val actualMs = SavedPcm(repository.audio(request.id)).use { it.samples * 1000 / 16_000 }
                // Each explicit run reports only its own outcome; earlier runs' messages are not repeated.
                persist(checkNotNull(session).copy(phase = MeetingPhase.RECORDED, durationMs = actualMs,
                    diagnostic = "Nouvelle transcription. $diagnostic"))
            }
            if (cancelled || destroyed) throw CancellationException()
            wake(PROCESS_TIMEOUT_MS)
            timer = scope.launch {
                delay(PROCESS_TIMEOUT_MS)
                interruption = "Délai de traitement maximal atteint (6 h). Relance explicite possible."
                cancelled = true
                stopSelf() // Do not keep a foreground service past its permitted budget.
            }
            check(dictation.models.isReady()) // No prepare/download from this service.
            persist(checkNotNull(session).copy(phase = MeetingPhase.TRANSCRIBING))
            notifyProgress("Transcription locale, micro fermé")
            engine = LocalRecognizer(dictation.models.modelFiles)
            engine.load()
            if (cancelled || destroyed) throw CancellationException()
            vad = engine.segmenter()
            val decoder = engine
            val detector = vad
            persist(repository.newJournal(checkNotNull(session)).copy(phase = MeetingPhase.TRANSCRIBING))
            var lastSaved = 0L
            TranscriptJournal.create(repository.journal(checkNotNull(session)), repository.guard).use { journal ->
                MeetingTranscriber().transcribe(repository.audio(checkNotNull(session).id), journal, object : MeetingDecoder {
                    override fun accept(samples: FloatArray) = detector.acceptTimed(samples).map { MeetingSpeech(it.startSample, it.samples) }
                    override fun finish() = detector.finishTimed().map { MeetingSpeech(it.startSample, it.samples) }
                    override fun recognize(samples: FloatArray) = decoder.recognize(samples)
                    override fun close() = Unit // Service owns both native handles through the finally below.
                }, cancelled = { cancelled || destroyed }, progress = { ms ->
                    controller.update { it.copy(processedMs = ms) }
                    if (ms - lastSaved >= 1000) {
                        persist(checkNotNull(session).copy(processedMs = ms)); lastSaved = ms
                        notifyProgress("Transcription : ${ms / 1000} / ${checkNotNull(session).durationMs / 1000} s")
                    }
                })
            }
            // Release the expensive ASR handles as soon as decoding ends.
            vad.close(); vad = null
            engine.close(); engine = null
            if (cancelled || destroyed) throw CancellationException()
            persist(checkNotNull(session).copy(phase = MeetingPhase.COMPLETE, processedMs = checkNotNull(session).durationMs,
                diagnostic = checkNotNull(session).diagnostic + " Transcription terminée."))
        } catch (_: CancellationException) {
            session?.let { runCatching { persist(it.copy(phase = MeetingPhase.CANCELLED,
                diagnostic = it.diagnostic + " Traitement interrompu/annulé. Audio et segments reconnus conservés. " + interruption)) } }
        } catch (_: OutOfMemoryError) {
            session?.let { runCatching { persist(it.copy(phase = MeetingPhase.ERROR, diagnostic = it.diagnostic + " Mémoire insuffisante ; audio/journal conservés.")) } }
            controller.update { it.copy(error = "Mémoire insuffisante ; données privées conservées.") }
        } catch (failure: Exception) {
            // The technical cause (our checks or IO, never transcript text) is what makes a phone report actionable.
            val cause = "${failure.javaClass.simpleName}: ${failure.message.orEmpty().take(120)}"
            session?.let { runCatching { persist(it.copy(phase = MeetingPhase.ERROR,
                diagnostic = it.diagnostic + " Échec vers ${it.processedMs / 1000} s ($cause). Audio et texte déjà transcrit conservés ; « Retranscrire » relance explicitement.")) } }
            controller.update { it.copy(error = "Réunion interrompue : audio et texte déjà transcrit conservés.") }
        } finally {
            timer?.cancel()
            microphone?.stop(); microphone = null
            runCatching { vad?.close() }; runCatching { engine?.close() }
            wakeLock?.let { if (it.isHeld) it.release() }; wakeLock = null
            // No release while load/decode/IO still owns native handles or private files.
            withContext(NonCancellable) { controller.ended() }
            attached = false
            withContext(NonCancellable + Dispatchers.Main) {
                stopForeground(STOP_FOREGROUND_REMOVE); stopSelf()
            }
            scope.cancel()
        }
    }
    private fun promote(recording: Boolean) {
        val type = if (recording) ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            else if (Build.VERSION.SDK_INT >= 35) ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING
            else ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        startForeground(NOTIFICATION, notification(if (recording) "Enregistrement réunion" else "Traitement audio local"), type)
    }
    /** A meeting is not watched: a capture that stops without the user's stop must be noticed (sound/vibration). */
    private fun alertStopped(reason: String) = runCatching {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(ALERT_CHANNEL, "Réunion arrêtée", NotificationManager.IMPORTANCE_HIGH))
        val open = PendingIntent.getActivity(this, 22, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        manager.notify(ALERT_NOTIFICATION, Notification.Builder(this, ALERT_CHANNEL).setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle("Enregistrement de réunion arrêté").setContentText(reason)
            .setStyle(Notification.BigTextStyle().bigText("$reason Le son capté jusqu’ici est conservé ; relancez un enregistrement pour la suite."))
            .setContentIntent(open).setAutoCancel(true).build())
    }
    private fun notifyProgress(message: String) = getSystemService(NotificationManager::class.java).notify(NOTIFICATION, notification(message))
    private fun notification(message: String): Notification {
        val open = PendingIntent.getActivity(this, 20, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, 21, Intent(this, MeetingService::class.java).setAction(STOP), PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, CHANNEL).setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle("LocalDictate — Réunion").setContentText(message).setOngoing(true).setOnlyAlertOnce(true)
            .setContentIntent(open).addAction(Notification.Action.Builder(null, "Arrêter / annuler", stop).build()).build()
    }
    override fun onTimeout(startId: Int, fgsType: Int) {
        interruption = "Délai foreground Android atteint ; fichiers conservés."
        cancelled = true; microphone?.stop(); stopSelf()
    }
    override fun onDestroy() {
        destroyed = true; cancelled = true
        microphone?.stop()
        wakeLock?.let { if (it.isHeld) it.release() }; wakeLock = null
        if (attached && job == null) {
            attached = false
            scope.launch { try { controller.ended() } finally { scope.cancel() } }
        } else if (!attached) scope.cancel()
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }
    companion object {
        const val START = "be.arte.localdictate.MEETING_START"
        const val STOP = "be.arte.localdictate.MEETING_STOP"
        const val CHANNEL = "meeting"
        const val NOTIFICATION = 20
        const val ALERT_CHANNEL = "meeting-alerts"
        const val ALERT_NOTIFICATION = 23
        const val PROCESS_TIMEOUT_MS = 6L * 60 * 60 * 1000
    }
}
