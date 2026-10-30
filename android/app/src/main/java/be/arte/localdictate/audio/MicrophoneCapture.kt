package be.arte.localdictate.audio

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.*
import android.os.SystemClock
import android.os.Build
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive

internal data class CapturedPcm(val samples: FloatArray, val originMs: Long)

/** stop() releases native recording immediately, even while the producer waits on a full queue. */
internal class MicrophoneCapture(private val context: Context) {
    private val stopped = AtomicBoolean(false)
    private val lock = Any()
    private var recorder: AudioRecord? = null
    private var callback: AudioManager.AudioRecordingCallback? = null
    fun stop() {
        stopped.set(true)
        synchronized(lock) { releaseLocked() }
    }
    private fun releaseLocked() {
        val record = recorder ?: return
        recorder = null
        callback?.let { runCatching { record.unregisterAudioRecordingCallback(it) } }
        callback = null
        runCatching { record.stop() }
        runCatching { record.release() }
    }
    @SuppressLint("MissingPermission") // Runtime check on every capture; the owner is an existing microphone FGS.
    suspend fun read(onSilenced: (Boolean) -> Unit, emit: (CapturedPcm) -> Unit) {
        if (stopped.get()) return
        check(context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            "Autorisation microphone requise."
        }
        val minimum = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        check(minimum > 0) { "Format PCM16 mono 16 kHz indisponible." }
        try {
            synchronized(lock) {
                if (stopped.get()) return
                val record = AudioRecord.Builder().setAudioSource(MediaRecorder.AudioSource.VOICE_RECOGNITION)
                    .setAudioFormat(AudioFormat.Builder().setSampleRate(RATE).setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
                    .setBufferSizeInBytes(maxOf(minimum * 2, RATE / 2))
                    .apply { if (Build.VERSION.SDK_INT >= 30) setPrivacySensitive(true) }.build()
                recorder = record
                check(record.state == AudioRecord.STATE_INITIALIZED) { "Initialisation micro impossible." }
                context.getSystemService(AudioManager::class.java).getDevices(AudioManager.GET_DEVICES_INPUTS)
                    .firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_MIC }?.let {
                        check(record.setPreferredDevice(it)) { "Microphone interne : routage refusé." }
                    }
                val listener = object : AudioManager.AudioRecordingCallback() {
                    override fun onRecordingConfigChanged(configs: MutableList<AudioRecordingConfiguration>) {
                        if (stopped.get()) return
                        val silenced = synchronized(lock) {
                            if (recorder !== record) return
                            record.activeRecordingConfiguration?.isClientSilenced == true
                        }
                        onSilenced(silenced)
                    }
                }
                callback = listener
                record.registerAudioRecordingCallback(context.mainExecutor, listener)
                record.startRecording()
                check(record.recordingState == AudioRecord.RECORDSTATE_RECORDING) { "Démarrage micro refusé." }
                onSilenced(record.activeRecordingConfiguration?.isClientSilenced == true)
            }
            val buffer = ShortArray(1024)
            var origin: Long? = null
            var lastData = SystemClock.elapsedRealtime()
            while (!stopped.get()) {
                currentCoroutineContext().ensureActive()
                val count = synchronized(lock) {
                    recorder?.read(buffer, 0, buffer.size, AudioRecord.READ_NON_BLOCKING) ?: 0
                }
                if (stopped.get()) break
                check(count >= 0) { "Erreur de capture AudioRecord." }
                if (count == 0) {
                    check(SystemClock.elapsedRealtime() - lastData < 5_000) { "Aucune donnée micro depuis 5 secondes." }
                    delay(5)
                    continue
                }
                lastData = SystemClock.elapsedRealtime()
                if (origin == null) origin = lastData - count * 1000L / RATE
                emit(CapturedPcm(Pcm16.floats(buffer, count), origin))
            }
        } finally { stop() }
    }
    companion object { const val RATE = 16_000 }
}
