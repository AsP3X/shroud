package de.corespace.shroud.e2e

import de.corespace.shroud.core.calls.CallAudioRoute
import de.corespace.shroud.core.calls.CallAudioRouteType
import de.corespace.shroud.core.calls.CallEndCause
import de.corespace.shroud.core.calls.CallMediaCallbacks
import de.corespace.shroud.core.calls.CallMediaEngine
import de.corespace.shroud.core.calls.CallSystem
import de.corespace.shroud.core.calls.IceCandidatePayload
import de.corespace.shroud.core.calls.ScreenCaptureGrant
import de.corespace.shroud.core.calls.ScreenShareQuality
import de.corespace.shroud.core.calls.Standard
import de.corespace.shroud.core.calls.signal.CallView
import de.corespace.shroud.core.net.IceServerDto
import kotlinx.coroutines.flow.MutableStateFlow
import org.webrtc.EglBase
import org.webrtc.VideoTrack
import java.util.Collections
import java.util.UUID

/**
 * The media side of a call for the engine e2e (W2-CALLS-CORE acceptance: call signalling with a
 * fake media engine). It writes and reads SDPs the way the scripted web peer's fake peer connection
 * does (`android/e2e/peer/fakeMedia.ts`, the web call selftest's fakes): one `m=<audio|video>
 * <direction>` line per section — microphone, camera, screen, screen sound, in the web's order — and
 * it "connects" once it holds the other side's description and at least one of its candidates. So the
 * real Android CallController and the real web CallController run the whole protocol between them —
 * ring, accept, the sealed offer and answer with their ephemeral keys, sealed candidates under the
 * per-call keys, media state, hangup — through the local server; only the media is fake.
 */
class E2eCallEngine : CallMediaEngine {
    @Volatile private var listener: CallMediaCallbacks? = null

    @Volatile private var hasLocalOffer = false

    @Volatile private var connected = false
    private val remoteCandidates = Collections.synchronizedList(ArrayList<IceCandidatePayload>())

    /** What the other side sent: offers we answered, answers we applied. */
    val receivedOffers: MutableList<String> = Collections.synchronizedList(ArrayList())
    val receivedAnswers: MutableList<String> = Collections.synchronizedList(ArrayList())

    @Volatile var starts = 0
        private set

    @Volatile var closes = 0
        private set

    @Volatile var micEnabled = true
        private set

    val isConnected: Boolean get() = connected

    override fun setCallbacks(callbacks: CallMediaCallbacks?) {
        listener = callbacks
    }

    override fun start(iceServers: List<IceServerDto>, video: Boolean, offering: Boolean, relayOnly: Boolean) {
        starts++
        hasLocalOffer = false
        hasRemoteDescription = false
        connected = false
        remoteCandidates.clear()
        isCameraOn = false
    }

    override suspend fun makeOffer(iceRestart: Boolean): String {
        hasLocalOffer = true
        candidates()
        return sdp("offer", SECTIONS.map { (kind, _) -> "m=$kind sendrecv" })
    }

    override suspend fun answer(offerSdp: String): String {
        receivedOffers += offerSdp
        hasRemoteDescription = true
        candidates()
        maybeConnect()
        // Each offered section answered from our side: what they send we receive and the other way round.
        return sdp("answer", sections(offerSdp).map { (kind, direction) -> "m=$kind ${reversed(direction)}" })
    }

    override suspend fun applyAnswer(sdp: String): Boolean {
        if (!hasLocalOffer) return false
        receivedAnswers += sdp
        hasLocalOffer = false
        hasRemoteDescription = true
        maybeConnect()
        return true
    }

    @Volatile override var hasRemoteDescription = false
        private set
    override val canOffer: Boolean get() = !hasLocalOffer
    override val canSendVideo: Boolean = true
    override val canSendScreen: Boolean = true

    override fun addRemoteCandidates(candidates: List<IceCandidatePayload>) {
        remoteCandidates += candidates.filter { it.candidate.isNotEmpty() }
        maybeConnect()
    }

    override fun setMicrophoneEnabled(enabled: Boolean) {
        micEnabled = enabled
    }

    override suspend fun localAudioLevel(): Float? = 0f

    /** No DTLS here: the controller's fingerprint check finds nothing to compare and lets the call be. */
    override suspend fun remoteCertificateFingerprint(): String? = null

    override fun startCamera(): Boolean {
        isCameraOn = true
        return true
    }

    override fun stopCamera() {
        isCameraOn = false
    }

    override fun switchCamera() = Unit
    override fun setPeerView(view: CallView?) = Unit
    override fun setCenterStage(on: Boolean) = Unit

    @Volatile override var isCameraOn = false
        private set
    override val canSwitchCamera: Boolean get() = isCameraOn
    override val usesFrontCamera: Boolean = true

    override fun awaitRemoteFrame() = Unit

    override fun awaitRemoteScreenFrame() = Unit

    override fun startScreen(grant: ScreenCaptureGrant): Boolean = false

    override fun stopScreen() = Unit

    override var screenQuality: ScreenShareQuality = ScreenShareQuality.Standard

    override fun preferRelay() = Unit

    override fun close() {
        closes++
        hasLocalOffer = false
        hasRemoteDescription = false
        connected = false
        remoteCandidates.clear()
        isCameraOn = false
    }

    override val localVideoTrack: VideoTrack? = null
    override val remoteVideoTrack: VideoTrack? = null
    override val remoteScreenTrack: VideoTrack? = null
    override val eglContext: EglBase.Context? = null

    private fun candidates() {
        val callbacks = listener ?: return
        for (n in 1..2) callbacks.onLocalCandidate(IceCandidatePayload("candidate:android-$n 1 udp 2122260223 10.0.2.$n 5000 typ host", "0", 0))
    }

    private fun maybeConnect() {
        if (connected || !hasRemoteDescription || remoteCandidates.isEmpty()) return
        connected = true
        listener?.onConnection("connected")
    }

    private fun sdp(type: String, lines: List<String>): String =
        "v=0\r\nfake-$type peer=android gen=0\r\n" + lines.joinToString("") { "$it\r\n" }

    private companion object {
        /** The web controller's sections, in its order (`controller.ts` `sectionOf`): mic, camera, screen, screen sound. */
        val SECTIONS = listOf("audio" to "sendrecv", "video" to "sendrecv", "video" to "sendrecv", "audio" to "sendrecv")
        val M_LINE = Regex("^m=(audio|video) (\\w+)$", RegexOption.MULTILINE)

        fun sections(sdp: String): List<Pair<String, String>> =
            M_LINE.findAll(sdp.replace("\r\n", "\n")).map { it.groupValues[1] to it.groupValues[2] }.toList()

        fun reversed(direction: String): String = when (direction) {
            "sendonly" -> "recvonly"
            "recvonly" -> "sendonly"
            "sendrecv" -> "sendrecv"
            else -> "inactive"
        }
    }
}

/** Telecom, the ringer and notifications stand-in: records what the controller reports. */
class E2eCallSystem : CallSystem {
    val log: MutableList<String> = Collections.synchronizedList(ArrayList())
    val ended: MutableList<Pair<UUID, CallEndCause>> = Collections.synchronizedList(ArrayList())
    override val isOnEarpiece = MutableStateFlow(true)
    private val phoneEarpiece = CallAudioRoute(CallAudioRouteType.Earpiece, "Earpiece", "earpiece")
    private val phoneSpeaker = CallAudioRoute(CallAudioRouteType.Speaker, "Speaker", "speaker")
    override val audioRoutes = MutableStateFlow(listOf(phoneEarpiece, phoneSpeaker))
    override val currentRoute = MutableStateFlow<CallAudioRoute?>(phoneEarpiece)

    override fun reportIncoming(callId: UUID, peerName: String, video: Boolean) {
        log += "incoming"
    }

    override fun reportOutgoing(callId: UUID, peerName: String, video: Boolean) {
        log += "outgoing"
    }

    override fun reportConnected(callId: UUID) {
        log += "connected"
    }

    override fun reportEnded(callId: UUID, cause: CallEndCause) {
        ended += callId to cause
    }

    override fun update(callId: UUID, peerName: String, video: Boolean) = Unit

    override fun answerFromApp(callId: UUID) {
        log += "answer"
    }

    override fun mediaStarted(callId: UUID, withCamera: Boolean) {
        log += "media"
    }

    override fun screenShareStarted(): Boolean = true

    override fun screenShareStopped() = Unit

    override fun setSpeaker(on: Boolean) {
        isOnEarpiece.value = !on
        currentRoute.value = if (on) phoneSpeaker else phoneEarpiece
    }

    override fun selectRoute(route: CallAudioRoute) {
        currentRoute.value = route
        isOnEarpiece.value = route.type == CallAudioRouteType.Earpiece
    }

    override fun postMissedCall(callId: UUID, peerUserId: UUID?, peerName: String?, video: Boolean) {
        log += "missed"
    }

    override fun clear() = Unit
}
