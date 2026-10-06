package de.corespace.shroud.core.calls.media

import android.content.Context
import android.util.Log
import de.corespace.shroud.core.calls.CallMediaCallbacks
import de.corespace.shroud.core.calls.IceCandidatePayload
import de.corespace.shroud.core.calls.ScreenCaptureGrant
import de.corespace.shroud.core.calls.ScreenShareQuality
import de.corespace.shroud.core.calls.Standard
import de.corespace.shroud.core.calls.signal.CallSdp
import de.corespace.shroud.core.net.IceServerDto
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import org.webrtc.AudioSource
import org.webrtc.AudioTrack
import org.webrtc.DataChannel
import org.webrtc.EglBase
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.MediaStreamTrack
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RtpParameters
import org.webrtc.RtpReceiver
import org.webrtc.RtpSender
import org.webrtc.RtpTransceiver
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import org.webrtc.VideoFrame
import org.webrtc.VideoSink
import org.webrtc.VideoSource
import org.webrtc.VideoTrack
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import de.corespace.shroud.core.calls.CallMediaEngine as Engine

/**
 * One call's WebRTC: peer connection, microphone, camera and screen (calls §5, §7; iOS
 * `CallMediaEngine`).
 *
 * The controller calls this from the main thread. WebRTC delivers candidates, connection changes
 * and frames on its own threads; those callbacks are forwarded as they arrive and dropped when
 * they belong to a peer connection [close] already replaced. The controller hops to main. This
 * class does not, so a callback is not posted twice. After [close] a late callback does nothing.
 *
 * The camera is not watched here for the app going to the background. `CallsModule` collects the
 * app phase and `CallController.onAppVisible(false)` calls [stopCamera], which is the pause: the
 * peer sees our face instead of a frozen frame, and the camera starts again on return. A system
 * interruption while we are up (another app took the camera) still reports [CallMediaCallbacks.onCameraPaused].
 *
 * Our camera goes out at a rung of a ladder from 180p to 1080p that follows the link
 * ([CameraQuality]; docs/calls.md, "Camera quality"; iOS `CallVideoQuality`). While the link is
 * connected, its sender's stats are read every 2 s on the main thread and the rung moves with them.
 * The camera measures its own frames ([CallCamera.captureLong]), so the ladder's ceiling and the
 * encoder's shrink follow what the camera actually delivers, another camera after a switch too.
 *
 * Nothing here is logged at info, and a failure never includes an SDP or a candidate address.
 */
class CallMediaEngine(context: Context) : Engine {
    private val appContext = context.applicationContext

    /**
     * Loaded on the first [start] or [eglContext] read. Construction and [de.corespace.shroud.core.calls.CallController.attach]
     * must not touch it: a push, boot, or [de.corespace.shroud.ui.calls.CallActivity] would otherwise
     * load `jingle_peerconnection_so` in a process that is not in a call, and Robolectric has no such library.
     */
    private val runtime by lazy { WebRtcRuntime.acquire(appContext) }
    private val factory: PeerConnectionFactory get() = runtime.factory

    @Volatile private var callbacks: CallMediaCallbacks? = null
    @Volatile private var peer: PeerConnection? = null

    /** Identifies the connection [start] just built. A replaced connection's callbacks no longer match. */
    @Volatile private var liveToken: Any? = null
    private var liveConfig: PeerConnection.RTCConfiguration? = null
    private var hasTurn = false
    private var triedRelay = false
    private var relayOnly = false

    private var audioSource: AudioSource? = null
    private var audioTrack: AudioTrack? = null
    private var cameraSource: VideoSource? = null
    private var screenVideoSource: VideoSource? = null
    private var camera: CallCamera? = null
    private var screenCapture: ScreenCaptureSource? = null

    private var screenTrack: VideoTrack? = null
    private var cameraOn = false
    private var screenOn = false

    private var peerLink = Link.NEW
    private var iceLink = Link.NEW

    /** The link as last published is connected. Written on WebRTC's thread, read on main. */
    @Volatile private var linkUp = false

    /** Where the camera-quality loop runs: main, like every other engine call. Never cancelled itself. */
    private val mainScope by lazy { MainScope() }

    /** How sharp our camera goes out this call. A new call starts a new one ([start], [close]). */
    private var quality = CameraQuality()

    /** Reads the camera's stats every [QUALITY_SAMPLE_MS] while the link is connected. */
    private var qualityJob: Job? = null

    private val remoteFrames = FrameWatch { callbacks?.onRemoteFrame() }
    private val localFrames = FrameWatch { callbacks?.onLocalFrame() }
    private val remoteScreenFrames = FrameWatch { callbacks?.onRemoteScreenFrame() }

    private var localVideo: VideoTrack? = null
    private var remoteVideo: VideoTrack? = null
    private var remoteScreen: VideoTrack? = null

    /**
     * The last [PeerConnection.getTransceivers] result. A second call disposes those wrappers,
     * and disposing a receiver disposes the [VideoTrack] the call screen is drawing. Later lookups
     * reuse this list. It is dropped when the remote description changes, then read once.
     */
    private var sectionCache: List<Pair<MediaSection, RtpTransceiver>>? = null
    /** True once [sectionCache] was read for the remote description now applied. */
    private var sectionsMatchRemote = false

    override var screenQuality: ScreenShareQuality = ScreenShareQuality.Standard
        set(value) {
            if (field == value) return
            field = value
            // The screen source stays a screencast for the whole share, at every frame rate.
            screenVideoSource?.setIsScreencast(true)
            screenCapture?.applyQuality(value)
            tuneSenders()
        }

    override fun setCallbacks(callbacks: CallMediaCallbacks?) {
        this.callbacks = callbacks
    }

    override fun start(iceServers: List<IceServerDto>, video: Boolean, offering: Boolean, relayOnly: Boolean) {
        close()
        peerLink = Link.NEW
        iceLink = Link.NEW
        val built = iceServers.mapNotNull(::iceServer)
        hasTurn = offersRelay(iceServers)
        this.relayOnly = iceTransportChoice(relayOnly, hasTurn) == IceTransportChoice.RELAY
        triedRelay = this.relayOnly
        val config = peerConfig(built, this.relayOnly)
        liveConfig = config
        val token = Any()
        liveToken = token
        val connection = factory.createPeerConnection(config, Observer(token)) ?: run {
            Log.w(TAG, "Peer connection was not created")
            liveToken = null
            return
        }
        peer = connection
        quality = CameraQuality()
        // The bandwidth estimate starts at 1 Mbps instead of WebRTC's 300 kbps, so the camera is
        // sharp from the first seconds (iOS `setBweMinBitrateBps`). Only here: mid-call it would
        // reset the estimate.
        try {
            connection.setBitrate(null, START_BITRATE_BPS, null)
        } catch (_: RuntimeException) {
        }

        val audioConstraints = MediaConstraints()
        listOf("googEchoCancellation", "googNoiseSuppression", "googAutoGainControl", "googHighpassFilter").forEach { key ->
            audioConstraints.optional.add(MediaConstraints.KeyValuePair(key, "true"))
        }
        val audioSource = factory.createAudioSource(audioConstraints)
        val audio = factory.createAudioTrack("shroud-audio", audioSource)
        this.audioSource = audioSource
        audioTrack = audio
        connection.addTrack(audio, listOf(STREAM))

        remoteFrames.arm()
        if (video) {
            val track = makeVideoTrack()
            if (track != null) {
                connection.addTrack(track, listOf(STREAM))
                startCapture(track)
            } else if (offering) {
                addSendRecv(connection, MediaStreamTrack.MediaType.MEDIA_TYPE_VIDEO, listOf(STREAM))
            }
        } else if (offering) {
            addSendRecv(connection, MediaStreamTrack.MediaType.MEDIA_TYPE_VIDEO, listOf(STREAM))
        }
        if (offering) {
            addSendRecv(connection, MediaStreamTrack.MediaType.MEDIA_TYPE_VIDEO, listOf(SCREEN_STREAM))
            addSendRecv(connection, MediaStreamTrack.MediaType.MEDIA_TYPE_AUDIO, listOf(SCREEN_STREAM))
        }
        tuneSenders()
    }

    override suspend fun makeOffer(iceRestart: Boolean): String {
        val connection = peer ?: throw CallMediaException("The call's media is not ready.")
        val constraints = MediaConstraints()
        if (iceRestart) constraints.mandatory.add(MediaConstraints.KeyValuePair("IceRestart", "true"))
        val sdp = createDescription { connection.createOffer(it, constraints) }
        val tuned = CallSdp.tuned(sdp)
        if (!applyDescription { connection.setLocalDescription(it, SessionDescription(SessionDescription.Type.OFFER, tuned)) }) {
            throw CallMediaException("The local description could not be set.")
        }
        tuneSenders()
        return tuned
    }

    override suspend fun answer(offerSdp: String): String {
        val connection = peer ?: throw CallMediaException("The call's media is not ready.")
        // onAddTrack runs inside setRemoteDescription and reads the transceivers once. Clearing
        // the flag first makes that read happen; the refresh below then keeps the same wrappers.
        sectionsMatchRemote = false
        if (!applyDescription { connection.setRemoteDescription(it, SessionDescription(SessionDescription.Type.OFFER, offerSdp)) }) {
            sectionsMatchRemote = sectionCache != null
            throw CallMediaException("The offer could not be applied.")
        }
        adoptOfferedSections(connection)
        val sdp = createDescription { connection.createAnswer(it, MediaConstraints()) }
        val tuned = CallSdp.tuned(sdp)
        if (!applyDescription { connection.setLocalDescription(it, SessionDescription(SessionDescription.Type.ANSWER, tuned)) }) {
            throw CallMediaException("The local description could not be set.")
        }
        tuneSenders()
        refreshRemoteMedia()
        return tuned
    }

    override suspend fun applyAnswer(sdp: String): Boolean {
        val connection = peer ?: return false
        if (connection.signalingState() != PeerConnection.SignalingState.HAVE_LOCAL_OFFER) return false
        sectionsMatchRemote = false
        if (!applyDescription { connection.setRemoteDescription(it, SessionDescription(SessionDescription.Type.ANSWER, sdp)) }) {
            sectionsMatchRemote = sectionCache != null
            return false
        }
        tuneSenders()
        refreshRemoteMedia()
        return true
    }

    override val hasRemoteDescription: Boolean
        get() = peer?.remoteDescription != null

    override val canOffer: Boolean
        get() = peer?.signalingState() == PeerConnection.SignalingState.STABLE

    override val canSendVideo: Boolean
        get() = sends(sectionTransceiver(MediaSection.CAMERA))

    override val canSendScreen: Boolean
        get() = sends(sectionTransceiver(MediaSection.SCREEN))

    override fun addRemoteCandidates(candidates: List<IceCandidatePayload>) {
        val connection = peer ?: return
        for (payload in candidates) {
            if (payload.candidate.isEmpty()) continue
            if (payload.sdpMid == null && payload.sdpMLineIndex == null) continue
            val ice = IceCandidate(payload.sdpMid ?: "", payload.sdpMLineIndex ?: 0, payload.candidate)
            try {
                connection.addIceCandidate(ice)
            } catch (_: RuntimeException) {
            }
        }
    }

    override fun setMicrophoneEnabled(enabled: Boolean) {
        audioTrack?.setEnabled(enabled)
    }

    override suspend fun localAudioLevel(): Float? {
        val connection = peer ?: return null
        val sender = microphoneSender() ?: return null
        return suspendCancellableCoroutine { cont ->
            val finished = AtomicBoolean(false)
            cont.invokeOnCancellation { finished.set(true) }
            try {
                connection.getStats(sender) { report ->
                    if (!finished.compareAndSet(false, true) || !cont.isActive) return@getStats
                    val rows = report.statsMap.values.map { StatRow(it.id, it.type, it.members) }
                    cont.resume(audioLevel(rows))
                }
            } catch (_: RuntimeException) {
                if (finished.compareAndSet(false, true) && cont.isActive) cont.resume(null)
            }
        }
    }

    override suspend fun remoteCertificateFingerprint(): String? {
        val connection = peer ?: return null
        return suspendCancellableCoroutine { cont ->
            val finished = AtomicBoolean(false)
            cont.invokeOnCancellation { finished.set(true) }
            try {
                connection.getStats { report ->
                    if (!finished.compareAndSet(false, true) || !cont.isActive) return@getStats
                    val rows = report.statsMap.values.map { StatRow(it.id, it.type, it.members) }
                    cont.resume(remoteFingerprint(rows))
                }
            } catch (_: RuntimeException) {
                if (finished.compareAndSet(false, true) && cont.isActive) cont.resume(null)
            }
        }
    }

    override fun startCamera(): Boolean {
        val connection = peer ?: return false
        val video = sectionTransceiver(MediaSection.CAMERA) ?: return false
        if (!sends(video)) return false
        val track = localVideo ?: makeVideoTrack() ?: return false
        try {
            video.sender.setTrack(track, false)
        } catch (_: RuntimeException) {
            return false
        }
        if (!startCapture(track)) return false
        tuneSenders()
        return true
    }

    override fun stopCamera() {
        if (!cameraOn) return
        cameraOn = false
        localFrames.disarm()
        try {
            sectionTransceiver(MediaSection.CAMERA)?.sender?.setTrack(null, false)
        } catch (_: RuntimeException) {
        }
        localVideo?.setEnabled(false)
        camera?.stop()
    }

    override fun switchCamera() {
        if (!cameraOn) return
        camera?.switchCamera()
    }

    override val isCameraOn: Boolean get() = cameraOn

    override val canSwitchCamera: Boolean get() = camera != null && cameraOn

    override val usesFrontCamera: Boolean get() = camera?.usesFrontCamera ?: true

    override fun awaitRemoteFrame() {
        remoteFrames.arm()
    }

    override fun awaitRemoteScreenFrame() {
        remoteScreenFrames.arm()
    }

    override fun startScreen(grant: ScreenCaptureGrant): Boolean {
        val connection = peer ?: return false
        val screen = sectionTransceiver(MediaSection.SCREEN) ?: return false
        if (!sends(screen)) return false
        val track = screenTrack ?: makeScreenTrack()
        val observer = screenVideoSource?.capturerObserver ?: return false
        track.setEnabled(true)
        try {
            screen.sender.setTrack(track, false)
        } catch (_: RuntimeException) {
            return false
        }
        val capture = screenCapture ?: ScreenCaptureSource(
            appContext,
            observer,
            onFirstFrame = { callbacks?.onScreenFirstFrame() },
            onEnded = { onScreenCaptureEnded() },
            onFormat = { width, height, fps ->
                try {
                    screenVideoSource?.adaptOutputFormat(width, height, fps)
                } catch (_: RuntimeException) {
                }
            },
        ).also { screenCapture = it }
        if (!capture.start(grant, screenQuality)) {
            try {
                screen.sender.setTrack(null, false)
            } catch (_: RuntimeException) {
            }
            track.setEnabled(false)
            return false
        }
        screenOn = true
        tuneSenders()
        return true
    }

    override fun stopScreen() {
        val wasOn = screenOn
        screenOn = false
        screenCapture?.stop()
        if (!wasOn) return
        try {
            sectionTransceiver(MediaSection.SCREEN)?.sender?.setTrack(null, false)
        } catch (_: RuntimeException) {
        }
        screenTrack?.setEnabled(false)
        tuneSenders()
    }

    override fun preferRelay() {
        if (relayOnly || triedRelay || !hasTurn) return
        val connection = peer ?: return
        val config = liveConfig ?: return
        if (config.iceTransportsType == PeerConnection.IceTransportsType.RELAY) {
            triedRelay = true
            return
        }
        // The certificate the connection already has. A fresh configuration has none, and WebRTC
        // then refuses the update (ME:343-354; the web client changes the live configuration too).
        val certificate = connection.certificate
        if (certificate != null) config.certificate = certificate
        config.iceTransportsType = PeerConnection.IceTransportsType.RELAY
        if (!connection.setConfiguration(config)) return
        triedRelay = true
    }

    override fun close() {
        stopQualityWatch()
        quality = CameraQuality()
        linkUp = false
        screenCapture?.stop()
        screenCapture = null
        camera?.close()
        camera = null
        cameraOn = false
        screenOn = false
        remoteFrames.disarm()
        localFrames.disarm()
        remoteScreenFrames.disarm()
        localVideo?.let { runCatching { it.removeSink(localFrames) } }
        remoteVideo?.let { runCatching { it.removeSink(remoteFrames) } }
        remoteScreen?.let { runCatching { it.removeSink(remoteScreenFrames) } }
        val hadRemote = remoteVideo != null
        val hadScreen = remoteScreen != null
        screenTrack = null
        // Drop the connection before closing it. "closed" and late candidates must not land on the next call.
        val connection = peer
        peer = null
        liveToken = null
        liveConfig = null
        runCatching { connection?.dispose() }
        runCatching { cameraSource?.dispose() }
        runCatching { screenVideoSource?.dispose() }
        runCatching { audioSource?.dispose() }
        cameraSource = null
        screenVideoSource = null
        audioSource = null
        audioTrack = null
        localVideo = null
        remoteVideo = null
        remoteScreen = null
        sectionCache = null
        sectionsMatchRemote = false
        hasTurn = false
        triedRelay = false
        relayOnly = false
        peerLink = Link.CLOSED
        iceLink = Link.CLOSED
        if (hadRemote) callbacks?.onRemoteVideo(null)
        if (hadScreen) callbacks?.onRemoteScreen(null)
    }

    override val localVideoTrack: VideoTrack? get() = localVideo
    override val remoteVideoTrack: VideoTrack? get() = remoteVideo
    override val remoteScreenTrack: VideoTrack? get() = remoteScreen
    override val eglContext: EglBase.Context? get() = runtime.egl.eglBaseContext

    private fun makeVideoTrack(): VideoTrack? {
        if (!CallCamera.isAvailable(appContext)) return null
        val source = factory.createVideoSource(false)
        source.adaptOutputFormat(CAMERA_CAPTURE_WIDTH, CAMERA_CAPTURE_HEIGHT, CAMERA_CAPTURE_FPS)
        val camera = CallCamera(
            appContext,
            runtime.egl.eglBaseContext,
            source,
            onPaused = { paused -> callbacks?.onCameraPaused(paused) },
            onCaptureSize = { onCaptureSize() },
        )
        if (!camera.isAvailable) {
            camera.close()
            runCatching { source.dispose() }
            return null
        }
        val track = factory.createVideoTrack(CAMERA_TRACK_ID, source)
        track.addSink(localFrames)
        cameraSource = source
        this.camera = camera
        localVideo = track
        return track
    }

    private fun startCapture(track: VideoTrack): Boolean {
        track.setEnabled(true)
        val running = camera?.start() == true
        cameraOn = running
        if (running) localFrames.arm() else track.setEnabled(false)
        return running
    }

    private fun makeScreenTrack(): VideoTrack {
        val source = screenVideoSource ?: factory.createVideoSource(true).also { screenVideoSource = it }
        source.setIsScreencast(true)
        val track = factory.createVideoTrack(SCREEN_TRACK_ID, source)
        screenTrack = track
        return track
    }

    /**
     * The projection stopped on its own, reported on the capture's thread; the work runs on main
     * like every other engine call. [stopScreen] does not come through here.
     */
    private fun onScreenCaptureEnded() {
        val token = liveToken ?: return
        mainScope.launch {
            if (liveToken !== token || peer == null || !screenOn) return@launch
            screenOn = false
            try {
                sectionTransceiver(MediaSection.SCREEN)?.sender?.setTrack(null, false)
            } catch (_: RuntimeException) {
            }
            screenTrack?.setEnabled(false)
            tuneSenders()
            callbacks?.onScreenCaptureEnded()
        }
    }

    private fun addSendRecv(
        connection: PeerConnection,
        type: MediaStreamTrack.MediaType,
        streams: List<String>,
    ): RtpTransceiver? = connection.addTransceiver(
        type,
        RtpTransceiver.RtpTransceiverInit(RtpTransceiver.RtpTransceiverDirection.SEND_RECV, streams),
    )

    /**
     * The live Java wrapper for [section]. [PeerConnection.getTransceivers] disposes the previous
     * wrappers, and that disposes their tracks, so the list is cached in [sections].
     */
    private fun sectionTransceiver(section: MediaSection): RtpTransceiver? {
        val connection = peer ?: return null
        return try {
            sections(connection).firstOrNull { it.first == section }?.second
        } catch (_: RuntimeException) {
            null
        }
    }

    /**
     * The cached microphone sender. [PeerConnection.getSenders] disposes the wrappers it returned
     * last time, including one whose [PeerConnection.getStats] is still in flight.
     */
    private fun microphoneSender(): RtpSender? = try {
        sectionTransceiver(MediaSection.MIC)?.sender
    } catch (_: RuntimeException) {
        null
    }

    private fun adoptOfferedSections(connection: PeerConnection) {
        for ((section, transceiver) in sections(connection)) {
            if (transceiver.isStopped) continue
            when (section) {
                MediaSection.CAMERA, MediaSection.SCREEN, MediaSection.SCREEN_SOUND -> bothWays(transceiver)
                MediaSection.MIC -> Unit
            }
        }
    }

    private fun bothWays(transceiver: RtpTransceiver): RtpTransceiver {
        val next = when (transceiver.direction) {
            RtpTransceiver.RtpTransceiverDirection.RECV_ONLY -> RtpTransceiver.RtpTransceiverDirection.SEND_RECV
            RtpTransceiver.RtpTransceiverDirection.INACTIVE -> RtpTransceiver.RtpTransceiverDirection.SEND_ONLY
            else -> null
        }
        if (next != null) {
            try {
                transceiver.setDirection(next)
            } catch (_: RuntimeException) {
            }
        }
        return transceiver
    }

    private fun sends(transceiver: RtpTransceiver?): Boolean {
        if (transceiver == null) return false
        return try {
            val negotiated = try {
                transceiver.currentDirection?.wire()
            } catch (_: RuntimeException) {
                null
            }
            canSend(
                configured = transceiver.direction.wire(),
                negotiated = negotiated,
                hasRemoteDescription = peer?.remoteDescription != null,
                stopped = transceiver.isStopped,
            )
        } catch (_: RuntimeException) {
            false
        }
    }

    private fun sections(connection: PeerConnection): List<Pair<MediaSection, RtpTransceiver>> =
        sectionCache ?: loadSections(connection)

    /** One [PeerConnection.getTransceivers] call. A second call would dispose [sectionCache]. */
    private fun loadSections(connection: PeerConnection): List<Pair<MediaSection, RtpTransceiver>> {
        var audio = 0
        var video = 0
        val out = ArrayList<Pair<MediaSection, RtpTransceiver>>()
        val transceivers = try {
            connection.transceivers
        } catch (_: RuntimeException) {
            return sectionCache ?: emptyList()
        }
        for (transceiver in transceivers) {
            when (transceiver.mediaType) {
                MediaStreamTrack.MediaType.MEDIA_TYPE_AUDIO -> {
                    sectionAt(audio, video = false)?.let { out += it to transceiver }
                    audio++
                }
                MediaStreamTrack.MediaType.MEDIA_TYPE_VIDEO -> {
                    sectionAt(video, video = true)?.let { out += it to transceiver }
                    video++
                }
                else -> Unit
            }
        }
        sectionCache = out
        return out
    }

    /**
     * Reads both remote tracks from one transceiver list. [onAddTrack] and the description that
     * caused it both call this; the second call keeps the list the first one cached.
     */
    private fun refreshRemoteMedia() {
        val connection = peer ?: return
        if (!sectionsMatchRemote) sectionCache = null
        refreshRemoteVideo()
        refreshRemoteScreen()
        if (sectionCache != null) sectionsMatchRemote = true
    }

    /**
     * The cached transceiver senders. [PeerConnection.getSenders] disposes the previous wrappers,
     * so a quality change's [RtpSender.setParameters] would land on a sender that is already gone
     * and the screen would stay at the rate from the first tune.
     */
    private fun tuneSenders() {
        val connection = peer ?: return
        val captureLong = camera?.captureLong ?: 0
        quality.setCapture(captureLong)
        val sections = try {
            sections(connection)
        } catch (_: RuntimeException) {
            return
        }
        for ((_, transceiver) in sections) {
            val sender = try {
                transceiver.sender
            } catch (_: RuntimeException) {
                continue
            }
            val track = try {
                sender.track()
            } catch (_: RuntimeException) {
                null
            } ?: continue
            val id = trackId(track) ?: continue
            val kind = trackKind(track) ?: continue
            val tune = senderTune(id, kind, screenOn, screenQuality, quality.rung, captureLong) ?: continue
            val applied = applyTune(sender, tune) || applyTune(sender, tune)
            if (!applied && id == SCREEN_TRACK_ID) {
                Log.w(TAG, "Screen sender did not take the new quality")
            }
        }
    }

    /**
     * False when the sender has no encoding yet, or [RtpSender.setParameters] refuses the update.
     * The caller tries once more, which reads the parameters again.
     */
    private fun applyTune(sender: RtpSender, tune: SenderTune): Boolean {
        return try {
            val parameters = sender.parameters
            val encoding = parameters.encodings.firstOrNull() ?: return false
            encoding.maxBitrateBps = tune.maxBitrateBps
            encoding.maxFramerate = tune.maxFramerate
            if (tune.scaleResolutionDownBy != null) encoding.scaleResolutionDownBy = tune.scaleResolutionDownBy
            encoding.networkPriority = tune.networkPriority
            encoding.bitratePriority = tune.bitratePriority
            tune.degradation?.let { parameters.degradationPreference = degradation(it) }
            sender.setParameters(parameters)
        } catch (_: RuntimeException) {
            false
        }
    }

    /**
     * On main: the camera-quality loop runs while [token]'s link is connected, and stops while it
     * is down (a reconnect reads nothing; the ladder counts afresh after it).
     */
    private fun followLink(token: Any) {
        mainScope.launch {
            if (liveToken !== token) return@launch
            if (linkUp) watchQuality(token) else stopQualityWatch()
        }
    }

    /**
     * Every 2 s: the camera's stats move it along the ladder, and the encoder takes a new rung at
     * once (web `watchQuality`). One read at a time: the next tick waits for the last.
     */
    private fun watchQuality(token: Any) {
        if (qualityJob?.isActive == true) return
        qualityJob = mainScope.launch {
            while (isActive && liveToken === token) {
                delay(QUALITY_SAMPLE_MS)
                if (liveToken !== token) break
                sampleQuality(token)
            }
        }
    }

    /**
     * The link dropped, or the call is over: no readings until it is connected again, and those
     * count afresh. Only a running loop pauses the ladder, so the states before the first connect
     * leave its opening settle as it is (iOS `stopWatchingQuality`).
     */
    private fun stopQualityWatch() {
        val job = qualityJob ?: return
        job.cancel()
        qualityJob = null
        quality.pause()
    }

    /**
     * Our camera's frames came at a new size (the first ones, another camera), reported on the
     * camera's thread: a new ceiling and a new shrink, on main, whatever the link or the screen.
     */
    private fun onCaptureSize() {
        val token = liveToken ?: return
        mainScope.launch {
            if (liveToken === token) tuneSenders()
        }
    }

    /**
     * One reading (web `sampleQuality`). Not while the camera is off, paused by the system (another
     * app took it), goes out as a tile, or the link is reconnecting: a camera that sends nothing
     * reads as a clean link, so those pause the count instead.
     */
    private suspend fun sampleQuality(token: Any) {
        val connection = peer ?: return
        val sender = try {
            sectionTransceiver(MediaSection.CAMERA)?.sender
        } catch (_: RuntimeException) {
            null
        }
        val track = try {
            sender?.track()
        } catch (_: RuntimeException) {
            null
        }
        if (sender == null || track == null || !cameraOn || camera?.isPaused == true || screenOn || !linkUp) {
            quality.pause()
            return
        }
        val rows = cameraStats(connection, sender) ?: return
        if (liveToken !== token || !cameraOn || camera?.isPaused == true || screenOn) return
        val sample = readCameraSample(rows) ?: return
        if (quality.sample(sample)) tuneSenders()
    }

    /** The camera sender's stats: its outbound-rtp, what the other side reports for it, the link. */
    private suspend fun cameraStats(connection: PeerConnection, sender: RtpSender): List<StatRow>? =
        suspendCancellableCoroutine { cont ->
            val finished = AtomicBoolean(false)
            cont.invokeOnCancellation { finished.set(true) }
            try {
                connection.getStats(sender) { report ->
                    if (!finished.compareAndSet(false, true) || !cont.isActive) return@getStats
                    cont.resume(report.statsMap.values.map { StatRow(it.id, it.type, it.members) })
                }
            } catch (_: RuntimeException) {
                if (finished.compareAndSet(false, true) && cont.isActive) cont.resume(null)
            }
        }

    private fun refreshRemoteVideo() {
        val connection = peer ?: return
        val track = try {
            sections(connection).firstOrNull { it.first == MediaSection.CAMERA }?.second?.receiver?.track() as? VideoTrack
        } catch (_: RuntimeException) {
            return
        }
        remoteVideo = retarget(remoteVideo, track, remoteFrames) { callbacks?.onRemoteVideo(it) }
    }

    private fun refreshRemoteScreen() {
        val connection = peer ?: return
        val track = try {
            sections(connection).firstOrNull { it.first == MediaSection.SCREEN }?.second?.receiver?.track() as? VideoTrack
        } catch (_: RuntimeException) {
            return
        }
        remoteScreen = retarget(remoteScreen, track, remoteScreenFrames) { callbacks?.onRemoteScreen(it) }
    }

    /**
     * Points [sink] at [next] when it is a different live track. A disposed track is ignored:
     * [VideoTrack.id] throws once the native track is gone, and a throw on the signaling thread
     * aborts the process. Returns the track now held.
     */
    private fun retarget(held: VideoTrack?, next: VideoTrack?, sink: VideoSink, publish: (VideoTrack?) -> Unit): VideoTrack? {
        val incoming = next?.takeUnless { it.isDisposed || trackId(it) == null }
        val current = held?.takeUnless { it.isDisposed }
        if (incoming == null && current == null) {
            // The screen may still hold the disposed track. Tell it to let go once.
            if (held != null) publish(null)
            return null
        }
        if (incoming != null && current != null && trackId(incoming) == trackId(current)) return held
        if (current != null && incoming !== current) runCatching { current.removeSink(sink) }
        if (incoming != null) runCatching { incoming.addSink(sink) }
        publish(incoming)
        return incoming
    }

    private fun trackId(track: MediaStreamTrack?): String? {
        if (track == null) return null
        return try {
            track.id()
        } catch (_: RuntimeException) {
            null
        }
    }

    private fun trackKind(track: MediaStreamTrack): String? = try {
        track.kind()
    } catch (_: RuntimeException) {
        null
    }

    private fun publishLink() {
        if (peer == null) return
        val state = when (peerLink) {
            Link.CONNECTED, Link.DISCONNECTED, Link.FAILED, Link.CLOSED -> peerLink
            else -> when (iceLink) {
                Link.CONNECTED, Link.DISCONNECTED, Link.FAILED -> iceLink
                else -> if (peerLink == Link.CONNECTING) Link.CONNECTING else iceLink
            }
        }
        linkUp = state == Link.CONNECTED
        callbacks?.onConnection(
            when (state) {
                Link.NEW -> "new"
                Link.CONNECTING -> "connecting"
                Link.CONNECTED -> "connected"
                Link.DISCONNECTED -> "disconnected"
                Link.FAILED -> "failed"
                Link.CLOSED -> "closed"
            },
        )
    }

    private fun notePeer(state: PeerConnection.PeerConnectionState) {
        peerLink = when (state) {
            PeerConnection.PeerConnectionState.NEW -> Link.NEW
            PeerConnection.PeerConnectionState.CONNECTING -> Link.CONNECTING
            PeerConnection.PeerConnectionState.CONNECTED -> Link.CONNECTED
            PeerConnection.PeerConnectionState.DISCONNECTED -> Link.DISCONNECTED
            PeerConnection.PeerConnectionState.FAILED -> Link.FAILED
            PeerConnection.PeerConnectionState.CLOSED -> Link.CLOSED
        }
        publishLink()
    }

    private fun noteIce(state: PeerConnection.IceConnectionState) {
        iceLink = when (state) {
            PeerConnection.IceConnectionState.NEW -> Link.NEW
            PeerConnection.IceConnectionState.CHECKING -> Link.CONNECTING
            PeerConnection.IceConnectionState.CONNECTED, PeerConnection.IceConnectionState.COMPLETED -> Link.CONNECTED
            PeerConnection.IceConnectionState.DISCONNECTED -> Link.DISCONNECTED
            PeerConnection.IceConnectionState.FAILED -> Link.FAILED
            PeerConnection.IceConnectionState.CLOSED -> Link.CLOSED
        }
        publishLink()
    }

    /**
     * One observer per connection. Compared by identity with [peer], which [close] clears before
     * `dispose`, so a replaced connection cannot report into the next call.
     */
    private inner class Observer(private val token: Any) : PeerConnection.Observer {
        override fun onSignalingChange(state: PeerConnection.SignalingState) = Unit
        override fun onIceConnectionReceivingChange(receiving: Boolean) = Unit
        override fun onIceGatheringChange(state: PeerConnection.IceGatheringState) = Unit
        override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>?) = Unit
        override fun onAddStream(stream: MediaStream?) = Unit
        override fun onRemoveStream(stream: MediaStream?) = Unit
        override fun onDataChannel(channel: DataChannel?) = Unit
        override fun onRenegotiationNeeded() = Unit

        override fun onIceConnectionChange(state: PeerConnection.IceConnectionState) {
            if (!sameConnection()) return
            try {
                noteIce(state)
            } catch (_: RuntimeException) {
            }
            followLink(token)
        }

        override fun onConnectionChange(state: PeerConnection.PeerConnectionState) {
            if (!sameConnection()) return
            try {
                notePeer(state)
            } catch (_: RuntimeException) {
            }
            followLink(token)
        }

        override fun onIceCandidate(candidate: IceCandidate) {
            if (!sameConnection()) return
            try {
                callbacks?.onLocalCandidate(
                    IceCandidatePayload(candidate.sdp, candidate.sdpMid, candidate.sdpMLineIndex),
                )
            } catch (_: RuntimeException) {
            }
        }

        override fun onAddTrack(receiver: RtpReceiver?, streams: Array<out MediaStream>?) {
            if (!sameConnection()) return
            try {
                // The receiver argument's track is disposed with that wrapper. Read the cached
                // transceiver list instead, once per remote description.
                refreshRemoteMedia()
            } catch (_: RuntimeException) {
            }
        }

        /** WebRTC aborts the process when a callback throws. Track ids throw once the native track is gone. */

        private fun sameConnection(): Boolean = liveToken === token
    }

    private enum class Link { NEW, CONNECTING, CONNECTED, DISCONNECTED, FAILED, CLOSED }

    /** The first frame after [arm], on the thread WebRTC delivers frames on. */
    private class FrameWatch(private val fire: () -> Unit) : VideoSink {
        private val armed = AtomicBoolean(false)

        fun arm() {
            armed.set(true)
        }

        fun disarm() {
            armed.set(false)
        }

        override fun onFrame(frame: VideoFrame) {
            if (!armed.compareAndSet(true, false)) return
            try {
                fire()
            } catch (_: RuntimeException) {
            }
        }
    }

    companion object {
        private const val TAG = "CallMedia"
        private const val STREAM = "shroud"
        private const val SCREEN_STREAM = "shroud-screen"

        /** Where the bandwidth estimate starts (docs/calls.md, "Camera quality"). */
        private const val START_BITRATE_BPS = 1_000_000

        private fun iceServer(server: IceServerDto): PeerConnection.IceServer? {
            val urls = server.urls.filter { it.isNotEmpty() }
            if (urls.isEmpty()) return null
            val builder = PeerConnection.IceServer.builder(urls)
            if (!server.username.isNullOrEmpty()) builder.setUsername(server.username)
            if (!server.credential.isNullOrEmpty()) builder.setPassword(server.credential)
            return builder.createIceServer()
        }

        private fun peerConfig(servers: List<PeerConnection.IceServer>, relay: Boolean) =
            PeerConnection.RTCConfiguration(servers).apply {
                sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
                bundlePolicy = PeerConnection.BundlePolicy.MAXBUNDLE
                rtcpMuxPolicy = PeerConnection.RtcpMuxPolicy.REQUIRE
                continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
                iceCandidatePoolSize = 1
                iceTransportsType = if (relay) PeerConnection.IceTransportsType.RELAY else PeerConnection.IceTransportsType.ALL
            }

        private fun degradation(name: String): RtpParameters.DegradationPreference = when (name) {
            DEGRADE_MAINTAIN_RESOLUTION -> RtpParameters.DegradationPreference.MAINTAIN_RESOLUTION
            else -> RtpParameters.DegradationPreference.BALANCED
        }

        private suspend fun createDescription(start: (SdpObserver) -> Unit): String = suspendCancellableCoroutine { cont ->
            start(object : SdpObserver {
                override fun onCreateSuccess(description: SessionDescription?) {
                    val text = description?.description
                    if (!cont.isActive) return
                    if (text.isNullOrEmpty()) cont.resumeWith(Result.failure(CallMediaException("No description.")))
                    else cont.resume(text)
                }

                override fun onCreateFailure(error: String?) {
                    if (cont.isActive) cont.resumeWith(Result.failure(CallMediaException("The description could not be created.")))
                }

                override fun onSetSuccess() = Unit
                override fun onSetFailure(error: String?) = Unit
            })
        }

        private suspend fun applyDescription(start: (SdpObserver) -> Unit): Boolean = suspendCancellableCoroutine { cont ->
            start(object : SdpObserver {
                override fun onSetSuccess() {
                    if (cont.isActive) cont.resume(true)
                }

                override fun onSetFailure(error: String?) {
                    if (cont.isActive) cont.resume(false)
                }

                override fun onCreateSuccess(description: SessionDescription?) = Unit
                override fun onCreateFailure(error: String?) {
                    if (cont.isActive) cont.resume(false)
                }
            })
        }
    }
}

/** A media failure with no SDP and no address in its message. */
class CallMediaException(message: String) : Exception(message)

private fun RtpTransceiver.RtpTransceiverDirection.wire(): String = when (this) {
    RtpTransceiver.RtpTransceiverDirection.SEND_RECV -> "sendrecv"
    RtpTransceiver.RtpTransceiverDirection.SEND_ONLY -> "sendonly"
    RtpTransceiver.RtpTransceiverDirection.RECV_ONLY -> "recvonly"
    RtpTransceiver.RtpTransceiverDirection.INACTIVE, RtpTransceiver.RtpTransceiverDirection.STOPPED -> "inactive"
}
