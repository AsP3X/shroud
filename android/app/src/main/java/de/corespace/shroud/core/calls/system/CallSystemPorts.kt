package de.corespace.shroud.core.calls.system

import android.app.Notification
import android.content.Intent
import de.corespace.shroud.core.calls.CallAudioRoute
import de.corespace.shroud.core.calls.CallEndCause
import java.util.UUID

/** Where a call notification is posted. Tag null is the untagged id (the live call slot). */
internal interface CallShade {
    fun ensureChannels()
    fun post(tag: String?, id: Int, notification: Notification)
    fun cancel(tag: String?, id: Int)
}

/** Starts [CallService]. Tests substitute a lambda that can refuse. */
internal fun interface CallForegroundStarter {
    fun start(intent: Intent)
}

/** The incoming ringtone and its vibration. Silent and Do Not Disturb play nothing. */
internal interface CallRinger {
    fun start()
    fun stop()
}

/**
 * In-call audio when Telecom is not tracking the call. [start] and [setSpeaker] return whether
 * the route landed on the earpiece. [stop] restores the mode from before the call.
 */
internal interface CallAudio {
    fun start(speaker: Boolean): Boolean
    fun setSpeaker(on: Boolean): Boolean
    fun stop()
}

/** Screen off near the ear. A missing proximity lock is a no-op. */
internal interface ProximitySensor {
    fun acquire()
    fun release()
}

/** What Telecom tells the call system. Default bodies so a fake can ignore what it does not drive. */
internal interface CallTelecomListener {
    fun onEarpiece(callId: UUID, earpiece: Boolean) {}
    fun onAnswer(callId: UUID) {}
    fun onEnded(callId: UUID, cause: CallEndCause) {}
    fun onMute(callId: UUID, muted: Boolean) {}

    /** `addCall` failed before the call existed. The in-app call continues. */
    fun onUnavailable(callId: UUID) {}

    /** Telecom's current outputs. Empty until the first endpoint list arrives. */
    fun onAudioRoutes(callId: UUID, routes: List<CallAudioRoute>, current: CallAudioRoute?) {}
}

/**
 * Self-managed Telecom (core-telecom). [tracksCall] is true only after [add] while Telecom
 * accepted the registration. [add] with Telecom unavailable returns without throwing.
 */
internal interface CallTelecom {
    var listener: CallTelecomListener
    val tracksCall: Boolean
    fun register()
    fun add(callId: UUID, name: String, video: Boolean, outgoing: Boolean)
    fun answer(video: Boolean)
    fun setActive()
    fun setSpeaker(on: Boolean)
    fun selectRoute(route: CallAudioRoute)
    fun disconnect(cause: CallEndCause)
    fun clear()
}

/** Hops into [de.corespace.shroud.core.calls.CallController]. The controller is main-confined. */
internal interface CallSystemCallbacks {
    fun onAnswer(callId: UUID)
    fun onEnd(callId: UUID)
    fun onMute(callId: UUID, muted: Boolean)

    /** Shade Speaker. The controller toggles; this must not also call [de.corespace.shroud.core.calls.CallSystem.setSpeaker]. */
    fun onToggleSpeaker()
}
