package de.corespace.shroud.core.calls

import de.corespace.shroud.core.net.CallModality
import org.webrtc.EglBase
import org.webrtc.VideoTrack
import java.time.Instant
import java.util.UUID

/** Where the call on screen is (`CallController.Phase`, `ios/shroud/Services/Calls/CallController.swift:23-30`). */
enum class CallPhase { Idle, OutgoingRinging, IncomingRinging, Connecting, Active, Ending }

/**
 * The call screen's call (`CallController.ActiveCall`, `CallController.swift:32-65`; calls §3.1).
 * [toString] leaves the name and the notice out, so a log line never carries them.
 *
 * @property id the server call id; an outgoing call shows a local id until `POST /calls` answers (CC:573-580, 609-612).
 * @property modality how the call was placed (the ring says so). Whether it is voice or video now is
 *   up to the two cameras: [isVideoEnabled] and [remoteCameraOff].
 * @property isVideoEnabled our camera is on and sent; either side switches its own at any time.
 * @property canVideo this call can carry our video; false only with an older app on the other side.
 * @property connectionState "new", "connecting", "connected", "disconnected".
 * @property startedAt when media first connected (the timer counts from it).
 * @property endedText the line the ending screen shows.
 * @property isSharingScreen our screen goes out (its first frame went out), next to the camera.
 * @property screenShareStarting capture started, its first frame not out yet: Share already stops it.
 * @property canShareScreen this call can carry our screen and their app can show it.
 * @property remoteSharingScreen they say they share their screen (`media_state`).
 * @property notice a passing line under the name (6 s).
 * @property safetyVerified the safety number has been compared on this phone; the call connects either way.
 */
data class ActiveCall(
    val id: UUID,
    val peerUserId: UUID,
    val peerUsername: String,
    val modality: CallModality,
    val isOutgoing: Boolean,
    val phase: CallPhase,
    val isMuted: Boolean = false,
    val isVideoEnabled: Boolean,
    val canVideo: Boolean = false,
    val connectionState: String = "new",
    val startedAt: Instant? = null,
    val endedText: String? = null,
    val reconnecting: Boolean = false,
    val remoteMicMuted: Boolean = false,
    val remoteCameraOff: Boolean,
    val isSharingScreen: Boolean = false,
    val screenShareStarting: Boolean = false,
    val canShareScreen: Boolean = false,
    val remoteSharingScreen: Boolean = false,
    val notice: String? = null,
    val speakerOn: Boolean,
    val safetyVerified: Boolean,
) {
    /** A camera or a screen is on, ours or theirs (`CallController.swift:1714`). */
    val hasVideo: Boolean get() = isVideoEnabled || !remoteCameraOff || isSharingScreen || remoteSharingScreen

    override fun toString(): String = "ActiveCall(id=$id, phase=$phase, modality=$modality, outgoing=$isOutgoing)"
}

/**
 * Everything the call screen draws (calls §3.2): the call and the media around it. One flow, so a
 * renderer follows one source; the Calls tab reads [CallHistoryState] instead and does not
 * recompose on every call change.
 *
 * @property remoteVideoLive their camera's frames arrive since it was last switched on: their picture shows.
 * @property localVideoLive our camera's frames arrive since it was switched on: our own picture shows.
 * @property remoteScreenLive their screen's frames arrive since they started sharing.
 * @property screenShareQuality the resolution and frame rate our screen goes out at, in every call (persisted).
 * @property centerStage our camera's cut follows the faces in it, in every call (persisted; docs/calls.md
 *   "Framing and Center Stage").
 * @property eglContext the engine's EGL context the renderers share (W3-CALLS-MEDIA).
 */
data class CallUiState(
    val active: ActiveCall? = null,
    val localVideoTrack: VideoTrack? = null,
    val remoteVideoTrack: VideoTrack? = null,
    val remoteVideoLive: Boolean = false,
    val localVideoLive: Boolean = false,
    val usesFrontCamera: Boolean = true,
    val canSwitchCamera: Boolean = false,
    val remoteScreenTrack: VideoTrack? = null,
    val remoteScreenLive: Boolean = false,
    val screenShareQuality: ScreenShareQuality = ScreenShareQuality.Standard,
    val centerStage: Boolean = true,
    val eglContext: EglBase.Context? = null,
)

/**
 * The Calls tab (`CallController.swift:70-82`; calls §4.18).
 *
 * @property recent every call of this account, newest first; a call that just ended here shows at once.
 * @property hasLoaded the first load since sign-in came back, with calls or with an error.
 * @property error why the history could not load, while there is nothing to show; null otherwise.
 * @property hasMore the server has older calls than the ones loaded (the last page came back full).
 * @property olderFailed the last older page failed: the list end offers to try again.
 */
data class CallHistoryState(
    val recent: List<RecentCall> = emptyList(),
    val hasLoaded: Boolean = false,
    val error: String? = null,
    val hasMore: Boolean = false,
    val loadingOlder: Boolean = false,
    val olderFailed: Boolean = false,
)

/** What Share asks the screen to do (`toggleScreenShare`, calls §4.17). */
enum class ShareAction {
    /** Nothing: it stopped a share, or a notice says why not. */
    None,

    /** Launch `MediaProjectionManager.createScreenCaptureIntent()` and hand the result to `onScreenCaptureConsent`. */
    RequestConsent,
}

/** "Always relay calls" is on, and the server handed out no TURN relay (`CallRelayUnavailable`, CC:2146-2147). */
class CallRelayUnavailableException : Exception("No relay for an always-relayed call.")
