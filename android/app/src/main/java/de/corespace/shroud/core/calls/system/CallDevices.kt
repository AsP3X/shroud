package de.corespace.shroud.core.calls.system

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.Ringtone
import android.media.RingtoneManager
import android.media.ToneGenerator
import android.os.Build
import android.os.PowerManager
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.app.ForegroundServiceStartNotAllowedException
import androidx.annotation.RequiresApi

/**
 * Platform pieces behind [AndroidCallSystem]. Each one swallows a missing device so a call
 * still rings from the notification. API 31+ types are referenced only from nested objects,
 * so an API 30 process does not load them.
 */
internal class NotificationManagerShade(private val context: Context) : CallShade {
    private val manager: NotificationManager?
        get() = context.getSystemService(NotificationManager::class.java)

    override fun ensureChannels() {
        val manager = manager ?: return
        try {
            if (manager.getNotificationChannel(CallChannels.INCOMING) == null) {
                manager.createNotificationChannel(channel(CallChannels.INCOMING, "Incoming calls", NotificationManager.IMPORTANCE_HIGH))
            }
            if (manager.getNotificationChannel(CallChannels.ONGOING) == null) {
                manager.createNotificationChannel(channel(CallChannels.ONGOING, "Ongoing calls", NotificationManager.IMPORTANCE_DEFAULT))
            }
        } catch (_: Exception) {
        }
    }

    override fun post(tag: String?, id: Int, notification: Notification) {
        val manager = manager ?: return
        try {
            if (tag == null) manager.notify(id, notification) else manager.notify(tag, id, notification)
        } catch (_: SecurityException) {
        } catch (_: IllegalArgumentException) {
            // API 35+ throws when CallStyle is posted with no foreground service and no
            // full-screen intent. Swallowing keeps the process up; the service posts it.
        }
    }

    override fun cancel(tag: String?, id: Int) {
        val manager = manager ?: return
        if (tag == null) manager.cancel(id) else manager.cancel(tag, id)
    }

    private fun channel(id: String, name: String, importance: Int): NotificationChannel =
        NotificationChannel(id, name, importance).apply {
            description = name
            setSound(null, null)
            enableVibration(false)
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        }
}

internal class AndroidForegroundStarter(private val context: Context) : CallForegroundStarter {
    override fun start(intent: Intent) {
        context.startForegroundService(intent)
    }
}

/**
 * Tries [CallForegroundStarter.start]. A refusal leaves the caller ringing from the notification.
 * [ForegroundServiceStartNotAllowedException] is caught only on API 31+, where the class exists.
 */
internal object ForegroundStart {
    fun tryStart(starter: CallForegroundStarter, intent: Intent): Boolean =
        if (Build.VERSION.SDK_INT >= 31) Api31.start(starter, intent) else legacy(starter, intent)

    private fun legacy(starter: CallForegroundStarter, intent: Intent): Boolean =
        try {
            starter.start(intent)
            true
        } catch (_: SecurityException) {
            false
        }

    @RequiresApi(31)
    private object Api31 {
        @RequiresApi(31)
        fun start(starter: CallForegroundStarter, intent: Intent): Boolean =
            try {
                starter.start(intent)
                true
            } catch (_: ForegroundServiceStartNotAllowedException) {
                false
            } catch (_: SecurityException) {
                false
            }
    }
}

internal class PlatformCallRinger(private val context: Context) : CallRinger {
    private var ringtone: Ringtone? = null
    private var tone: ToneGenerator? = null
    private var vibrating = false

    override fun start() {
        stop()
        try {
            val audio = context.getSystemService(AudioManager::class.java)
            val notifications = context.getSystemService(NotificationManager::class.java)
            if (notifications != null && notifications.currentInterruptionFilter != NotificationManager.INTERRUPTION_FILTER_ALL) return
            when (audio?.ringerMode ?: AudioManager.RINGER_MODE_NORMAL) {
                AudioManager.RINGER_MODE_SILENT -> return
                AudioManager.RINGER_MODE_VIBRATE -> startVibration()
                else -> {
                    startSound()
                    startVibration()
                }
            }
        } catch (_: Exception) {
        }
    }

    override fun stop() {
        try {
            ringtone?.stop()
            tone?.stopTone()
            tone?.release()
            if (vibrating) vibrator()?.cancel()
        } catch (_: Exception) {
        } finally {
            ringtone = null
            tone = null
            vibrating = false
        }
    }

    private fun startSound() {
        val uri = RingtoneManager.getActualDefaultRingtoneUri(context, RingtoneManager.TYPE_RINGTONE)
        val playing = uri?.let { RingtoneManager.getRingtone(context, it) }
        if (playing != null) {
            playing.audioAttributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()
            playing.isLooping = true
            playing.play()
            ringtone = playing
            return
        }
        val generated = ToneGenerator(AudioManager.STREAM_RING, 80)
        generated.startTone(ToneGenerator.TONE_SUP_RINGTONE)
        tone = generated
    }

    private fun startVibration() {
        val vibrator = vibrator() ?: return
        if (!vibrator.hasVibrator()) return
        val effect = VibrationEffect.createWaveform(longArrayOf(0, 1_000, 1_000), 0)
        if (Build.VERSION.SDK_INT >= 33) Api33.vibrate(vibrator, effect) else {
            @Suppress("DEPRECATION")
            vibrator.vibrate(
                effect,
                AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE).build(),
            )
        }
        vibrating = true
    }

    private fun vibrator(): Vibrator? =
        try {
            if (Build.VERSION.SDK_INT >= 31) Api31.vibrator(context) else {
                @Suppress("DEPRECATION")
                context.getSystemService(Vibrator::class.java)
            }
        } catch (_: Exception) {
            null
        }

    @RequiresApi(31)
    private object Api31 {
        @RequiresApi(31)
        fun vibrator(context: Context): Vibrator? =
            context.getSystemService(VibratorManager::class.java)?.defaultVibrator
    }

    @RequiresApi(33)
    private object Api33 {
        @RequiresApi(33)
        fun vibrate(vibrator: Vibrator, effect: VibrationEffect) {
            vibrator.vibrate(
                effect,
                android.os.VibrationAttributes.createForUsage(android.os.VibrationAttributes.USAGE_RINGTONE),
            )
        }
    }
}

internal class PlatformCallAudio(context: Context) : CallAudio {
    private val audio: AudioManager? = context.getSystemService(AudioManager::class.java)
    private var focus: AudioFocusRequest? = null

    override fun start(speaker: Boolean): Boolean {
        val audio = audio ?: return !speaker
        return try {
            audio.mode = AudioManager.MODE_IN_COMMUNICATION
            val attributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()
            val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                .setAudioAttributes(attributes)
                .build()
            audio.requestAudioFocus(request)
            focus = request
            setSpeaker(speaker)
        } catch (_: Exception) {
            !speaker
        }
    }

    override fun setSpeaker(on: Boolean): Boolean {
        val audio = audio ?: return !on
        return try {
            if (Build.VERSION.SDK_INT >= 31) Api31.route(audio, on) else legacy(audio, on)
        } catch (_: Exception) {
            !on
        }
    }

    override fun stop() {
        val audio = audio ?: return
        try {
            if (Build.VERSION.SDK_INT >= 31) Api31.clear(audio) else {
                @Suppress("DEPRECATION")
                audio.isSpeakerphoneOn = false
            }
            focus?.let { audio.abandonAudioFocusRequest(it) }
            audio.mode = AudioManager.MODE_NORMAL
        } catch (_: Exception) {
        } finally {
            focus = null
        }
    }

    @Suppress("DEPRECATION")
    private fun legacy(audio: AudioManager, speaker: Boolean): Boolean {
        audio.isSpeakerphoneOn = speaker
        if (speaker) return false
        val headset = audio.isWiredHeadsetOn || audio.isBluetoothA2dpOn || audio.isBluetoothScoOn
        return !headset
    }

    @RequiresApi(31)
    private object Api31 {
        @RequiresApi(31)
        fun route(audio: AudioManager, speaker: Boolean): Boolean {
            val devices = audio.availableCommunicationDevices
            val target = if (speaker) {
                devices.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
            } else {
                devices.firstOrNull { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO || it.type == AudioDeviceInfo.TYPE_BLE_HEADSET }
                    ?: devices.firstOrNull {
                        it.type == AudioDeviceInfo.TYPE_WIRED_HEADSET ||
                            it.type == AudioDeviceInfo.TYPE_WIRED_HEADPHONES ||
                            it.type == AudioDeviceInfo.TYPE_USB_HEADSET
                    }
                    ?: devices.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_EARPIECE }
            }
            if (target != null) audio.setCommunicationDevice(target)
            return audio.communicationDevice?.type == AudioDeviceInfo.TYPE_BUILTIN_EARPIECE
        }

        @RequiresApi(31)
        fun clear(audio: AudioManager) {
            audio.clearCommunicationDevice()
        }
    }
}

internal class PlatformProximity(context: Context) : ProximitySensor {
    private val lock: PowerManager.WakeLock? = try {
        val power = context.getSystemService(PowerManager::class.java)
        if (power != null && power.isWakeLockLevelSupported(PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK)) {
            power.newWakeLock(PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK, "shroud:call-proximity").apply {
                setReferenceCounted(false)
            }
        } else {
            null
        }
    } catch (_: Exception) {
        null
    }

    override fun acquire() {
        val lock = lock ?: return
        try {
            if (!lock.isHeld) lock.acquire()
        } catch (_: Exception) {
        }
    }

    override fun release() {
        val lock = lock ?: return
        try {
            if (lock.isHeld) lock.release(PowerManager.RELEASE_FLAG_WAIT_FOR_NO_PROXIMITY)
        } catch (_: Exception) {
        }
    }
}
