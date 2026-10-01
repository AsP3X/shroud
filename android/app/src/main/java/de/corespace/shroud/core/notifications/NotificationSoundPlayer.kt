package de.corespace.shroud.core.notifications

import android.app.NotificationManager
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.RingtoneManager
import android.media.SoundPool
import android.provider.Settings

/** Plays a notification sound once, the way a notification would (quiet in silent mode). */
fun interface SoundPlayer {
    fun play(sound: NotificationSound)
}

/**
 * In-app playback of the notification sounds (iOS `NotificationSound.play()`,
 * `ios/shroud/Services/Notifications/NotificationSound.swift:43-67`; notifications-push §5.5):
 * banners while the app is open and the sound picker's preview.
 *
 * - [NotificationSound.None] plays nothing.
 * - Nothing plays unless the ringer is on (`RINGER_MODE_NORMAL`) and Do Not Disturb lets everything
 *   through (`INTERRUPTION_FILTER_ALL`): iOS system sounds are "quiet in silent mode" (`:43`). A
 *   phone on vibrate plays nothing, the picker preview included, as on iOS.
 * - [NotificationSound.Standard] → the phone's notification sound through `Ringtone` (iOS plays its
 *   tri-tone, `:48-50`).
 * - The bundled tones → one [SoundPool] (two streams), each WAV loaded once and kept for the
 *   process (iOS registers each file once, `:57-67`). A tone not loaded yet plays as soon as it is.
 *
 * Main thread or any: [SoundPool] and `Ringtone` are thread-safe enough for fire-and-forget.
 */
class NotificationSoundPlayer(private val context: Context) : SoundPlayer {
    private val lock = Any()
    private var pool: SoundPool? = null
    private val loaded = HashMap<NotificationSound, Int>()
    private val ready = HashSet<Int>()
    private val playWhenReady = HashSet<Int>()

    override fun play(sound: NotificationSound) {
        if (sound == NotificationSound.None || !audible()) return
        val resId = sound.resId
        if (resId == null) {
            playDefault()
            return
        }
        synchronized(lock) {
            val pool = pool()
            val sampleId = loaded.getOrPut(sound) { pool.load(context, resId, 1) }
            if (sampleId in ready) pool.play(sampleId, 1f, 1f, 1, 0, 1f) else playWhenReady += sampleId
        }
    }

    private fun playDefault() {
        runCatching {
            RingtoneManager.getRingtone(context, Settings.System.DEFAULT_NOTIFICATION_URI)?.apply {
                audioAttributes = ATTRIBUTES
                play()
            }
        }
    }

    private fun pool(): SoundPool = pool ?: SoundPool.Builder()
        .setMaxStreams(2)
        .setAudioAttributes(ATTRIBUTES)
        .build()
        .also { created ->
            created.setOnLoadCompleteListener { soundPool, sampleId, status ->
                synchronized(lock) {
                    if (status != 0) return@synchronized
                    ready += sampleId
                    if (playWhenReady.remove(sampleId)) soundPool.play(sampleId, 1f, 1f, 1, 0, 1f)
                }
            }
            pool = created
        }

    /** The ringer is on and Do Not Disturb lets everything through. */
    private fun audible(): Boolean {
        val audio = context.getSystemService(AudioManager::class.java) ?: return false
        if (audio.ringerMode != AudioManager.RINGER_MODE_NORMAL) return false
        val notifications = context.getSystemService(NotificationManager::class.java) ?: return true
        return notifications.currentInterruptionFilter == NotificationManager.INTERRUPTION_FILTER_ALL ||
            notifications.currentInterruptionFilter == NotificationManager.INTERRUPTION_FILTER_UNKNOWN
    }

    private companion object {
        val ATTRIBUTES: AudioAttributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_NOTIFICATION_EVENT)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()
    }
}
