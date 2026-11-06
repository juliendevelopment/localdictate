package be.arte.localdictate.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager

/**
 * Pauses other apps' media while dictating by holding a transient audio focus, and lets them resume on release.
 * Players that honour audio focus pause on the loss and resume by themselves when the focus is abandoned, so media
 * that was not playing is never started. Players that ignore audio focus are not affected.
 */
class MediaPauser(context: Context) {
    private val manager = context.getSystemService(AudioManager::class.java)
    private var request: AudioFocusRequest? = null

    @Synchronized
    fun pause() {
        if (request != null) return
        val focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
            .setAudioAttributes(AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANT)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            .setOnAudioFocusChangeListener { }
            .build()
        if (runCatching { manager.requestAudioFocus(focus) }.getOrNull() == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) request = focus
    }

    @Synchronized
    fun resume() {
        val focus = request ?: return
        request = null
        runCatching { manager.abandonAudioFocusRequest(focus) }
    }
}
