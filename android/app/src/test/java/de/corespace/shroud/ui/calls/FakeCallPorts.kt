package de.corespace.shroud.ui.calls

import androidx.activity.compose.setContent
import de.corespace.shroud.core.calls.ActiveCall
import de.corespace.shroud.core.calls.CallHistoryState
import de.corespace.shroud.core.calls.CallPermissionPrompt
import de.corespace.shroud.core.calls.CallPhase
import de.corespace.shroud.core.calls.CallUiState
import de.corespace.shroud.core.calls.RecentCall
import de.corespace.shroud.core.calls.ScreenCaptureGrant
import de.corespace.shroud.core.calls.ScreenShareQuality
import de.corespace.shroud.core.calls.ShareAction
import de.corespace.shroud.core.net.CallModality
import kotlinx.coroutines.flow.MutableStateFlow
import de.corespace.shroud.ui.components.ComposeHarness
import org.webrtc.EglBase
import java.time.Duration
import java.time.Instant
import java.util.UUID

/** A scripted [CallPorts]: state the tests set, and a log of what the screens called. */
internal class FakeCallPorts(
    active: ActiveCall? = null,
    history: CallHistoryState = CallHistoryState(),
) : CallPorts {
    override val ui = MutableStateFlow(CallUiState(active = active))
    override val history = MutableStateFlow(history)
    override val lastError = MutableStateFlow<String?>(null)
    override val isOnEarpiece = MutableStateFlow(true)
    var safetyNumber: String? = null
    val calls = mutableListOf<String>()

    override fun eglContext(): EglBase.Context? = null.also { calls += "eglContext" }
    override suspend fun startCall(peerUserId: UUID, peerUsername: String, modality: CallModality) {
        calls += "startCall:$peerUsername:${modality.wire}"
    }
    override suspend fun acceptIncoming() {
        calls += "acceptIncoming"
    }
    override suspend fun rejectIncoming() {
        calls += "rejectIncoming"
    }
    override suspend fun hangup() {
        calls += "hangup"
    }
    override suspend fun toggleMute() {
        calls += "toggleMute"
    }
    override suspend fun toggleVideo() {
        calls += "toggleVideo"
    }
    override fun toggleSpeaker() {
        calls += "toggleSpeaker"
    }
    override fun switchCamera() {
        calls += "switchCamera"
    }
    override fun toggleScreenShare(): ShareAction {
        calls += "toggleScreenShare"
        return ShareAction.None
    }
    override fun onScreenCaptureConsent(grant: ScreenCaptureGrant?) {
        calls += "consent:${grant != null}"
    }
    override fun setScreenShareQuality(quality: ScreenShareQuality) {
        calls += "quality:${quality.resolution.raw}:${quality.frameRate}"
    }
    override fun setCenterStage(on: Boolean) {
        calls += "centerStage:$on"
    }
    override fun setOwnView(width: Int, height: Int) {
        calls += "ownView:${width}x$height"
    }
    override suspend fun localAudioLevel(): Float? = null
    override fun safetyNumberForActiveCall(): String? = safetyNumber
    override fun confirmSafety() {
        calls += "confirmSafety"
    }
    override suspend fun refreshHistory() {
        calls += "refreshHistory"
    }
    override suspend fun loadOlderHistory() {
        calls += "loadOlderHistory"
    }
    override fun onCallScreenShown(callId: UUID) {
        calls += "shown:$callId"
    }
    override fun onCallScreenHidden() {
        calls += "hidden"
    }
    override var permissionPrompt: CallPermissionPrompt? = null
}

internal object CallFixtures {
    val anna: UUID = UUID.fromString("00000000-0000-4000-8000-00000000000b")
    val ben: UUID = UUID.fromString("00000000-0000-4000-8000-00000000000c")
    val callId: UUID = UUID.fromString("00000000-0000-4000-8000-0000000000c1")
    val now: Instant = Instant.parse("2026-09-30T09:41:00Z")

    fun call(
        phase: CallPhase = CallPhase.Active,
        modality: CallModality = CallModality.Voice,
        id: UUID = callId,
        name: String = "anna",
    ) = ActiveCall(
        id = id,
        peerUserId = anna,
        peerUsername = name,
        modality = modality,
        isOutgoing = true,
        phase = phase,
        isVideoEnabled = false,
        canVideo = true,
        remoteCameraOff = true,
        speakerOn = false,
        safetyVerified = true,
    )

    fun recent(
        id: Int,
        peer: UUID = anna,
        name: String = "anna",
        minutesAgo: Long = 0,
        status: String = "ended",
        outgoing: Boolean = true,
        connected: Boolean = true,
        seconds: Long? = 252,
        modality: CallModality = CallModality.Voice,
        deleted: Boolean = false,
    ) = RecentCall(
        id = UUID.fromString("00000000-0000-4000-8000-%012d".format(id)),
        peerUserId = peer,
        peerUsername = name,
        peerDeleted = deleted,
        modality = modality,
        isOutgoing = outgoing,
        status = status,
        connected = connected,
        duration = seconds?.let(Duration::ofSeconds),
        at = now.minus(Duration.ofMinutes(minutesAgo)),
    )
}

/**
 * Disposes the harness's composition and lets the main looper drain. Compose's UI dispatcher is
 * process-wide: a test that ends with work queued on it (a poll's or a clock's `delay` resuming)
 * would leave it waiting on a looper Robolectric resets, and the next test's recompositions and
 * launched coroutines would never run.
 */
internal fun ComposeHarness.close() {
    activity.setContent { }
    idle()
}
