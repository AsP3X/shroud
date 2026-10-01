package de.corespace.shroud.core.voice

import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * The app's audio routing for voice notes — iOS `ChatAudioSession` on Android
 * (`ios/shroud/Services/Voice/ChatAudioSession.swift:12-28`; media-voice-links §8.5):
 *
 * | iOS config | Use | Android |
 * |---|---|---|
 * | `voiceRecord` (`.playAndRecord`, `.defaultToSpeaker`) | recording | [requestRecording]: `AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE` |
 * | `mixedPlayback` (`.playback`, `.mixWithOthers`) | after recording (`VoiceRecorder.swift:194`) | [abandonRecording] |
 * | `spokenPlayback` (`.playback`, `.spokenAudio`) | voice notes | ExoPlayer `USAGE_MEDIA`/`CONTENT_TYPE_SPEECH`, `handleAudioFocus` ([ExoVoicePlayer]) |
 * | `moviePlayback` | chat video | `ChatVideoPlayer` (W2-VIDEO) |
 * | `voiceCall` | calls | calls area (Telecom) |
 *
 * The iOS actor exists to keep `setCategory`/`setActive` off the main thread; Android focus calls
 * are cheap and run on the main thread.
 *
 * It also carries [callMediaStarting], iOS's `.shroudCallMediaStarting` notification
 * (`CallController.swift:1946-1949`, consumed by `ConversationView.swift:380-383`): a call taking the
 * microphone cancels a take and stops voice playback. The calls area reports it through
 * [notifyCallMediaStarting] (wired by W2-INT, `VoiceModule.bindCallMediaStarting`).
 *
 * Main-confined.
 */
class AudioFocusCoordinator(private val port: FocusPort) {
    /** The platform focus API; [AndroidFocusPort] on a device, a fake in tests. */
    interface FocusPort {
        /**
         * Requests exclusive transient focus for a recording. [onLoss] runs on the main thread when a
         * call or another app takes focus away for good or for a while (not for a duck request).
         */
        fun request(onLoss: () -> Unit): Boolean

        /** Gives focus back; a no-op when none is held. */
        fun abandon()
    }

    private var holding = false
    private var generation = 0
    private val mediaStarting = MutableSharedFlow<Unit>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    /** True while a recording holds focus. */
    val isHoldingRecordingFocus: Boolean get() = holding

    /**
     * Takes focus for a recording (iOS `activate(.voiceRecord)`, `VoiceRecorder.swift:80`). False when
     * the system refused (a phone call is in progress): the take cannot start. [onLost] runs when focus
     * is lost while held — the recorder cancels the take, as iOS does when a call's media starts.
     */
    fun requestRecording(onLost: () -> Unit = {}): Boolean {
        abandonRecording()
        val mine = generation
        holding = port.request {
            // A loss queued for an earlier request must not end this one.
            if (holding && generation == mine) {
                abandonRecording()
                onLost()
            }
        }
        return holding
    }

    /** Ends a recording's focus so other audio may resume (iOS `activate(.mixedPlayback)`, `VoiceRecorder.swift:194`). */
    fun abandonRecording() {
        generation++
        if (!holding) return
        holding = false
        port.abandon()
    }

    /** iOS `.shroudCallMediaStarting`: a call is about to use the microphone. Collected by [de.corespace.shroud.di.VoiceModule]. */
    val callMediaStarting: SharedFlow<Unit> = mediaStarting.asSharedFlow()

    /** Reports a call's media starting (calls area, through `VoiceModule.bindCallMediaStarting`). */
    fun notifyCallMediaStarting() {
        mediaStarting.tryEmit(Unit)
    }
}

/**
 * [AudioFocusCoordinator.FocusPort] over [AudioManager]: one `AudioFocusRequest`
 * (`AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE`, `USAGE_MEDIA` / `CONTENT_TYPE_SPEECH`, media-voice-links
 * §8.1) per recording, listener on the main looper. `AUDIOFOCUS_LOSS` and `AUDIOFOCUS_LOSS_TRANSIENT`
 * (a ringing call) count as lost; a duck request does not.
 */
class AndroidFocusPort(private val audioManager: AudioManager) : AudioFocusCoordinator.FocusPort {
    private val mainHandler = Handler(Looper.getMainLooper())
    private var current: AudioFocusRequest? = null

    override fun request(onLoss: () -> Unit): Boolean {
        abandon()
        val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
            )
            .setAcceptsDelayedFocusGain(false)
            .setWillPauseWhenDucked(false)
            .setOnAudioFocusChangeListener({ change ->
                if (change == AudioManager.AUDIOFOCUS_LOSS || change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) onLoss()
            }, mainHandler)
            .build()
        val granted = audioManager.requestAudioFocus(request) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        current = if (granted) request else null
        return granted
    }

    override fun abandon() {
        val request = current ?: return
        current = null
        audioManager.abandonAudioFocusRequest(request)
    }
}
