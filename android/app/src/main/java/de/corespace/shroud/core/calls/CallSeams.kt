package de.corespace.shroud.core.calls

import android.content.Intent
import de.corespace.shroud.core.calls.signal.CallView
import de.corespace.shroud.core.net.IceServerDto
import de.corespace.shroud.core.notifications.NotificationKind
import kotlinx.coroutines.flow.StateFlow
import org.webrtc.EglBase
import org.webrtc.VideoTrack
import java.util.UUID

// The seams around the call controller (plan §1.7.11, calls §3–§6). Published by W1-INT with the
// final signatures; W2-CALLS-CORE's CallController drives a CallMediaEngine (W3-CALLS-MEDIA; fakes
// in W2) and a CallSystem (W3-CALLS-SYSTEM). Changing one is a contract change request.
//
// W2-CALLS-CORE (the owner in W2) added, before any implementation exists: CallMediaEngine
// canSendVideo / canSendScreen (the controller reads them, CallController.swift:662, 1736-1739),
// CallMediaCallbacks onScreenFirstFrame / onScreenCaptureEnded (the broadcast's `.firstFrame` and
// `.disconnected`, CallController.swift:1818-1829, now that the engine owns the MediaProjection),
// and a companion on ScreenShareQuality for the quality math (ScreenShareQualityMath.kt).

/** One ICE candidate as signalled (calls §4.3). */
data class IceCandidatePayload(val candidate: String, val sdpMid: String?, val sdpMLineIndex: Int?)

/**
 * Screen-share encoding the sharer picks (calls §7.1): [frameRate] is 15, 30 or 60. Labels, the
 * bitrate table, `wireSize` and the default (`ScreenShareQuality.Standard`, 1080p · 15 fps) live in
 * `ScreenShareQualityMath.kt`.
 */
data class ScreenShareQuality(val resolution: Resolution, val frameRate: Int) {
    enum class Resolution(val raw: String) {
        P720("720p"),
        P1080("1080p"),
        Source("source"),
    }

    /** Extended by `ScreenShareQualityMath.kt` (`Standard`, `FRAME_RATES`, `fromStored`). */
    companion object
}

/** The MediaProjection consent result, handed to the engine to start capture. */
data class ScreenCaptureGrant(val resultCode: Int, val data: Intent)

/** What the media engine reports back; called on the engine's threads (the controller hops to main). */
interface CallMediaCallbacks {
    fun onLocalCandidate(candidate: IceCandidatePayload)

    /** `PeerConnection.IceConnectionState` name in lower case ("connected", "failed", …). */
    fun onConnection(state: String)
    fun onRemoteVideo(track: VideoTrack?)
    fun onRemoteFrame()
    fun onLocalFrame()
    fun onCameraPaused(paused: Boolean)
    fun onRemoteScreen(track: VideoTrack?)
    fun onRemoteScreenFrame()

    /** The first frame of our screen went out after [CallMediaEngine.startScreen] (the broadcast's `.firstFrame`, CC:1818-1826). */
    fun onScreenFirstFrame()

    /**
     * Our screen capture stopped by itself — the system chip's Stop, the screen locked, another
     * projection took over, the shared app went away (the broadcast's `.disconnected`,
     * CC:1827-1828). Not called for a [CallMediaEngine.stopScreen] the controller asked for.
     */
    fun onScreenCaptureEnded()
}

/** WebRTC behind one interface (calls §5); W3-CALLS-MEDIA implements it, W2 tests use a fake. */
interface CallMediaEngine {
    fun setCallbacks(callbacks: CallMediaCallbacks?)
    fun start(iceServers: List<IceServerDto>, video: Boolean, offering: Boolean, relayOnly: Boolean)
    suspend fun makeOffer(iceRestart: Boolean): String
    suspend fun answer(offerSdp: String): String

    /** False when the answer did not apply (stale or glare). */
    suspend fun applyAnswer(sdp: String): Boolean
    val hasRemoteDescription: Boolean
    val canOffer: Boolean

    /** The camera section (first video) can send: not stopped, direction `sendrecv`/`sendonly` (ME:119-124). False with an older peer. */
    val canSendVideo: Boolean

    /** The screen section (second video) can send (ME:119-124). False with an older peer. */
    val canSendScreen: Boolean
    fun addRemoteCandidates(candidates: List<IceCandidatePayload>)
    fun setMicrophoneEnabled(enabled: Boolean)

    /** 0…1, from the sender's `media-source` stats (memory: *Call mic level source*). */
    suspend fun localAudioLevel(): Float?

    /** The DTLS fingerprint of the peer's certificate, for the call's safety check. */
    suspend fun remoteCertificateFingerprint(): String?
    fun startCamera(): Boolean
    fun stopCamera()
    fun switchCamera()

    /**
     * The size of the area the other side shows our camera in (their `media_state` `view`), or
     * null when they sent none: our camera goes out cut to that shape (docs/calls.md, "Framing
     * and Center Stage"). For the running call; a new [start] forgets it.
     */
    fun setPeerView(view: CallView?)

    /** Center Stage: our camera's cut follows the faces in it. Kept from call to call. */
    fun setCenterStage(on: Boolean)
    val isCameraOn: Boolean
    val canSwitchCamera: Boolean
    val usesFrontCamera: Boolean
    fun awaitRemoteFrame()
    fun awaitRemoteScreenFrame()
    fun startScreen(grant: ScreenCaptureGrant): Boolean
    fun stopScreen()
    var screenQuality: ScreenShareQuality
    fun preferRelay()
    fun close()
    val localVideoTrack: VideoTrack?
    val remoteVideoTrack: VideoTrack?
    val remoteScreenTrack: VideoTrack?
    val eglContext: EglBase.Context?
}

/** Why a call ended, as Telecom and the history need it. */
enum class CallEndCause { Local, Remote, Rejected, Missed, AnsweredElsewhere, Error }

/** A speaker, earpiece, wired headset, or Bluetooth device the call can play through. */
enum class CallAudioRouteType { Earpiece, Speaker, Wired, Bluetooth, Unknown }

/**
 * One audio output. [id] distinguishes two devices that share a [name]; pass the same value back to
 * [CallSystem.selectRoute]. Phone-only routes use `earpiece` and `speaker`.
 */
data class CallAudioRoute(val type: CallAudioRouteType, val name: String, val id: String)

/** Telecom, the foreground service, CallStyle notifications, ringer, audio routes, proximity (calls §6); W3-CALLS-SYSTEM. */
interface CallSystem {
    fun reportIncoming(callId: UUID, peerName: String, video: Boolean)
    fun reportOutgoing(callId: UUID, peerName: String, video: Boolean)
    fun reportConnected(callId: UUID)
    fun reportEnded(callId: UUID, cause: CallEndCause)
    fun update(callId: UUID, peerName: String, video: Boolean)
    fun answerFromApp(callId: UUID)
    fun mediaStarted(callId: UUID, withCamera: Boolean)
    /**
     * Takes the mediaProjection foreground-service type before capture starts.
     * False when that type is not running: capture must not start, or the system stops it at once.
     */
    fun screenShareStarted(): Boolean
    fun screenShareStopped()
    fun setSpeaker(on: Boolean)

    /** Earpiece and speaker when Telecom is not tracking the call; Telecom's endpoints when it is. */
    val audioRoutes: StateFlow<List<CallAudioRoute>>

    /** The route that is playing, or null before any route is known. */
    val currentRoute: StateFlow<CallAudioRoute?>

    /** Switches to [route]. Unknown routes are ignored. [setSpeaker] stays for the shade toggle. */
    fun selectRoute(route: CallAudioRoute)

    val isOnEarpiece: StateFlow<Boolean>
    fun postMissedCall(callId: UUID, peerUserId: UUID?, peerName: String?, video: Boolean)
    fun clear()
}

/**
 * A call event that arrived while the app may not be running, built by `PushDispatcher` (W3-PUSH)
 * from a UnifiedPush message or the background socket. The running app's socket keeps using
 * `CallRing` events. [toString] never prints the caller's name.
 */
data class CallPush(
    val kind: NotificationKind,
    val callId: UUID,
    val peerUserId: UUID?,
    val callerName: String?,
    val source: Source,
) {
    enum class Source { UnifiedPush, BackgroundSocket }

    override fun toString(): String = "CallPush(kind=$kind, callId=$callId, source=$source)"
}
