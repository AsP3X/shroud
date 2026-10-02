package de.corespace.shroud.ui.conversation.composer

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import de.corespace.shroud.core.model.Haptic
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Where a hold-to-record gesture sits (iOS `VoiceRecordingPhase`, `VoiceRecordingUI.swift:3-17`).
 *
 * Telegram's model: hold the mic to record, slide left past [VoiceRecordingThresholds.cancel] to
 * throw the take away, slide up past [VoiceRecordingThresholds.lock] to go hands-free. Releasing
 * while [Recording] sends; once [Locked] the finger is free and explicit buttons take over.
 */
sealed interface ComposerPhase {
    data object Idle : ComposerPhase

    /** Finger still down; [cancelProgress] and [lockProgress] run 0…1 toward each threshold. */
    data class Recording(val cancelProgress: Float, val lockProgress: Float) : ComposerPhase

    data object Locked : ComposerPhase
}

/** A take is on screen (recording bar or locked bar). */
val ComposerPhase.isActive: Boolean get() = this != ComposerPhase.Idle

val ComposerPhase.isLocked: Boolean get() = this == ComposerPhase.Locked

/** 0…1 toward the cancel threshold while the finger is down, else 0 (`ChatComposerView.swift:74-77`). */
val ComposerPhase.cancelProgress: Float get() = (this as? ComposerPhase.Recording)?.cancelProgress ?: 0f

/** 0…1 toward the lock threshold while the finger is down, else 0 (`ChatComposerView.swift:79-82`). */
val ComposerPhase.lockProgress: Float get() = (this as? ComposerPhase.Recording)?.lockProgress ?: 0f

/** Drag distances that trigger each outcome (`VoiceRecordingUI.swift:20-23`). */
object VoiceRecordingThresholds {
    const val CANCEL_DP = 110f
    const val LOCK_DP = 76f
    val cancel: Dp = CANCEL_DP.dp
    val lock: Dp = LOCK_DP.dp
}

/**
 * The hold-to-record gesture of the composer's mic — the state machine of iOS `ChatComposerView`
 * (`ChatComposerView.swift:57-67, 144-158, 303-396`; conversation-compose-media §3.6), pure so it is
 * unit-tested without Compose. The composable feeds it the finger in **window** space (the composer
 * moves under a still finger when a take starts — the field leaves, the keyboard drops — which in
 * local space would read as a slide up to lock, `:305-309`); the host only hears the three outcomes.
 *
 * - [pointer] on touch-down (0, 0) and on every move: idle → one start attempt per touch;
 *   recording → [updateDrag]; locked → ignored (`:313-325`).
 * - [pointerUp] on release **and** on a cancelled touch (a system dialog, such as the first
 *   microphone prompt, cancels it; iOS relies on `@GestureState`, `:150-158`).
 * - [startLocked] is the TalkBack path: assistive tech cannot hold and slide (`:353-366`).
 * - [recorderStopped]: the recorder ended the take itself (a call, the app leaving the foreground,
 *   another app taking the audio focus) — back to idle (`:145-148`).
 *
 * Main-confined. [scope] runs the start round-trip.
 */
class ComposerGesture(
    private val scope: CoroutineScope,
    private val host: Host,
) {
    /** What the gesture asks of the conversation (`ChatComposerView.swift:27-29`). */
    interface Host {
        /** Starts a take; false when it could not start (permission, busy) or was abandoned meanwhile. */
        suspend fun recordStart(): Boolean

        fun recordCancel()

        fun recordSend()

        fun haptic(haptic: Haptic)
    }

    private val mutablePhase = MutableStateFlow<ComposerPhase>(ComposerPhase.Idle)
    val phase: StateFlow<ComposerPhase> = mutablePhase.asStateFlow()

    /** `onRecordStart` is still awaiting (`ChatComposerView.swift:58-59`). */
    var isStarting: Boolean = false
        private set

    /** The finger lifted before the start resolved: whatever arrives is discarded (`:60-61`). */
    var abandonedDuringStart: Boolean = false
        private set

    /** A finger is on the mic; reset on release and on cancellation (`:62-64`). */
    var micHeld: Boolean = false
        private set

    /** One start attempt per touch: a failed start or a slide-to-cancel must not restart while the finger is down (`:65-67`). */
    var attemptedThisTouch: Boolean = false
        private set

    /**
     * The finger is down on the mic, [dxDp] / [dyDp] from where it went down (window space, dp).
     * Touch-down itself is `pointer(0f, 0f)` (iOS `DragGesture(minimumDistance: 0)` reports at once).
     */
    fun pointer(dxDp: Float, dyDp: Float) {
        micHeld = true
        when (mutablePhase.value) {
            ComposerPhase.Idle -> {
                if (attemptedThisTouch) return
                attemptedThisTouch = true
                beginRecording()
            }
            is ComposerPhase.Recording -> updateDrag(dxDp, dyDp)
            // The finger is irrelevant once locked; explicit buttons take over (`:321-323`).
            ComposerPhase.Locked -> Unit
        }
    }

    /** The finger left the mic — released, or the touch was cancelled (`ChatComposerView.swift:150-158`). */
    fun pointerUp() {
        if (!micHeld) return
        micHeld = false
        attemptedThisTouch = false
        if (isStarting) {
            abandonedDuringStart = true
        } else if (mutablePhase.value is ComposerPhase.Recording) {
            finish(send = mutablePhase.value.cancelProgress < 1f)
        }
    }

    /** TalkBack's activation: straight to the hands-free state (`ChatComposerView.swift:353-366`). */
    fun startLocked() {
        if (mutablePhase.value != ComposerPhase.Idle || isStarting) return
        isStarting = true
        abandonedDuringStart = false
        scope.launch {
            val started = try {
                host.recordStart()
            } finally {
                isStarting = false
            }
            if (started) mutablePhase.value = ComposerPhase.Locked
        }
    }

    /** The recorder stopped on its own while a take was on screen (`ChatComposerView.swift:145-148`). */
    fun recorderStopped() {
        if (!mutablePhase.value.isActive) return
        mutablePhase.value = ComposerPhase.Idle
    }

    /**
     * Ends the take on screen: [send] hands it to the host, otherwise it is discarded with the rigid
     * tick (`ChatComposerView.swift:387-396`). Also the locked bar's Discard / Send and Back during a
     * take (thread D9 = compose Q10).
     */
    fun finish(send: Boolean) {
        if (!mutablePhase.value.isActive) return
        mutablePhase.value = ComposerPhase.Idle
        if (send) {
            host.recordSend()
        } else {
            host.haptic(Haptic.Rigid)
            host.recordCancel()
        }
    }

    /** Back to idle without an outcome (the chat locked or closed and the host dropped the take itself). */
    fun reset() {
        mutablePhase.value = ComposerPhase.Idle
        micHeld = false
        attemptedThisTouch = false
        if (isStarting) abandonedDuringStart = true
    }

    private fun beginRecording() {
        if (isStarting) return
        isStarting = true
        abandonedDuringStart = false
        scope.launch {
            val started = try {
                host.recordStart()
            } finally {
                isStarting = false
            }
            if (!started) {
                mutablePhase.value = ComposerPhase.Idle
                return@launch
            }
            if (abandonedDuringStart) {
                // Released during the permission / microphone round-trip: a tap, not a message (`:340-346`).
                abandonedDuringStart = false
                host.recordCancel()
                mutablePhase.value = ComposerPhase.Idle
                return@launch
            }
            mutablePhase.value = ComposerPhase.Recording(cancelProgress = 0f, lockProgress = 0f)
        }
    }

    /** `updateDrag(translation:)` (`ChatComposerView.swift:368-385`): lock wins when both cross in one event. */
    private fun updateDrag(dxDp: Float, dyDp: Float) {
        val cancel = (-dxDp / VoiceRecordingThresholds.CANCEL_DP).coerceIn(0f, 1f)
        val lock = (-dyDp / VoiceRecordingThresholds.LOCK_DP).coerceIn(0f, 1f)
        if (lock >= 1f) {
            host.haptic(Haptic.LockEngaged)
            // The mic leaves composition with this touch still down; the next touch starts fresh (`:374-375`).
            attemptedThisTouch = false
            mutablePhase.value = ComposerPhase.Locked
            return
        }
        if (cancel >= 1f) {
            // Telegram cancels the moment the finger crosses, without waiting for the release (`:379-382`).
            finish(send = false)
            return
        }
        mutablePhase.value = ComposerPhase.Recording(cancelProgress = cancel, lockProgress = lock)
    }
}
