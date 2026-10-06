package de.corespace.shroud.ui.calls

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import de.corespace.shroud.AppContainer
import de.corespace.shroud.core.calls.CallHistoryState
import de.corespace.shroud.core.calls.CallPermissionPrompt
import de.corespace.shroud.core.calls.CallUiState
import de.corespace.shroud.core.calls.ScreenCaptureGrant
import de.corespace.shroud.core.calls.ScreenShareQuality
import de.corespace.shroud.core.calls.ShareAction
import de.corespace.shroud.core.net.CallModality
import de.corespace.shroud.ui.LocalAppContainer
import kotlinx.coroutines.flow.StateFlow
import org.webrtc.EglBase
import java.util.UUID

/**
 * What the call UI uses of core, as one port (R4): the K1 `calls.controller`, the K8 video
 * context (`callsMedia.engine.eglContext`), the route flag (`callsSystem.system.isOnEarpiece`), the
 * call screen hooks (`callsSystem.screenHooks`) and the permission prompt the call UI registers
 * (`calls.permissions.prompt`, `CallsModule`). [ContainerCallPorts] forwards every member 1:1;
 * tests and previews drive the screens with a fake, as production does until G9 attaches the
 * engine and the system (`CallController.attach` is never called from UI).
 *
 * Threading (R1): every member is called on the main thread; the suspend actions run detached in
 * the controller's scope, so a screen going away never cancels a call half placed.
 */
interface CallPorts {
    /** The call screen's state: the call, its tracks and flags (`CallController.ui`). */
    val ui: StateFlow<CallUiState>

    /** The Calls tab (`CallController.history`). */
    val history: StateFlow<CallHistoryState>

    /** The last failure of [startCall], shown as a toast once it returns (`CallController.lastError`). */
    val lastError: StateFlow<String?>

    /** Audio plays on the phone's earpiece (`CallSystem.isOnEarpiece`). */
    val isOnEarpiece: StateFlow<Boolean>

    /**
     * The EGL context the renderers share: the one the controller copied at attach, else the
     * engine's own (`callsMedia.engine.eglContext`). Read only while a track is on screen, so a
     * voice call never builds the engine.
     */
    fun eglContext(): EglBase.Context?

    suspend fun startCall(peerUserId: UUID, peerUsername: String, modality: CallModality)
    suspend fun acceptIncoming()
    suspend fun rejectIncoming()
    suspend fun hangup()
    suspend fun toggleMute()
    suspend fun toggleVideo()
    fun toggleSpeaker()
    fun switchCamera()
    fun toggleScreenShare(): ShareAction
    fun onScreenCaptureConsent(grant: ScreenCaptureGrant?)
    fun setScreenShareQuality(quality: ScreenShareQuality)

    /** Center Stage on or off (`CallController.setCenterStage`), kept for the next calls. */
    fun setCenterStage(on: Boolean)

    /**
     * The area their camera fills, in pixels (`CallController.setOwnView`): the call screen, or the
     * tile while it sits beside their screen. Their camera is cut to its shape. Called only when
     * it changes.
     */
    fun setOwnView(width: Int, height: Int)

    /** 0…1 from the sender's stats; null without media (`CallController.localAudioLevel`). */
    suspend fun localAudioLevel(): Float?
    fun safetyNumberForActiveCall(): String?
    fun confirmSafety()
    suspend fun refreshHistory()
    suspend fun loadOlderHistory()

    /** `CallScreenHooks.onCallScreenShown`: the call screen is in front (it may start the phoneCall service). */
    fun onCallScreenShown(callId: UUID)

    /** `CallScreenHooks.onCallScreenHidden`. */
    fun onCallScreenHidden()

    /** The prompt that shows the system permission dialog for the controller (`AndroidCallPermissions.prompt`). */
    var permissionPrompt: CallPermissionPrompt?
}

/** [CallPorts] over the process's [AppContainer]; every member forwards 1:1 (R4). */
class ContainerCallPorts(private val container: AppContainer) : CallPorts {
    private val controller get() = container.calls.controller

    override val ui: StateFlow<CallUiState> get() = controller.ui
    override val history: StateFlow<CallHistoryState> get() = controller.history
    override val lastError: StateFlow<String?> get() = controller.lastError
    override val isOnEarpiece: StateFlow<Boolean> get() = container.callsSystem.system.isOnEarpiece
    override fun eglContext(): EglBase.Context? = controller.ui.value.eglContext ?: container.callsMedia.engine.eglContext
    override suspend fun startCall(peerUserId: UUID, peerUsername: String, modality: CallModality) =
        controller.startCall(peerUserId, peerUsername, modality)
    override suspend fun acceptIncoming() = controller.acceptIncoming()
    override suspend fun rejectIncoming() = controller.rejectIncoming()
    override suspend fun hangup() = controller.hangup()
    override suspend fun toggleMute() = controller.toggleMute()
    override suspend fun toggleVideo() = controller.toggleVideo()
    override fun toggleSpeaker() = controller.toggleSpeaker()
    override fun switchCamera() = controller.switchCamera()
    override fun toggleScreenShare(): ShareAction = controller.toggleScreenShare()
    override fun onScreenCaptureConsent(grant: ScreenCaptureGrant?) = controller.onScreenCaptureConsent(grant)
    override fun setScreenShareQuality(quality: ScreenShareQuality) = controller.setScreenShareQuality(quality)
    override fun setCenterStage(on: Boolean) = controller.setCenterStage(on)
    override fun setOwnView(width: Int, height: Int) = controller.setOwnView(width, height)
    override suspend fun localAudioLevel(): Float? = controller.localAudioLevel()
    override fun safetyNumberForActiveCall(): String? = controller.safetyNumberForActiveCall()
    override fun confirmSafety() = controller.confirmSafety()
    override suspend fun refreshHistory() = controller.refreshHistory()
    override suspend fun loadOlderHistory() = controller.loadOlderHistory()
    override fun onCallScreenShown(callId: UUID) = container.callsSystem.screenHooks.onCallScreenShown(callId)
    override fun onCallScreenHidden() = container.callsSystem.screenHooks.onCallScreenHidden()
    override var permissionPrompt: CallPermissionPrompt?
        get() = container.calls.permissions.prompt
        set(value) {
            container.calls.permissions.prompt = value
        }
}

/**
 * The call UI's port. Unset in production, where [rememberCallPorts] builds a [ContainerCallPorts];
 * screen tests and previews provide a fake here.
 */
val LocalCallPorts: ProvidableCompositionLocal<CallPorts?> = staticCompositionLocalOf { null }

/** [LocalCallPorts], or the container's ports. */
@Composable
internal fun rememberCallPorts(): CallPorts {
    val provided = LocalCallPorts.current
    if (provided != null) return provided
    val container = LocalAppContainer.current
    return remember(container) { ContainerCallPorts(container) }
}
