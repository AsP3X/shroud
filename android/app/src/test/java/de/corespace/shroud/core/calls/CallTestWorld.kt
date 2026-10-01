package de.corespace.shroud.core.calls

import de.corespace.shroud.core.auth.Session
import de.corespace.shroud.core.calls.crypto.CallCrypto
import de.corespace.shroud.core.crypto.hexToBytes
import de.corespace.shroud.core.model.AppClock
import de.corespace.shroud.core.model.Ids
import de.corespace.shroud.core.net.ApiError
import de.corespace.shroud.core.net.CallDto
import de.corespace.shroud.core.net.CallModality
import de.corespace.shroud.core.net.ErrorCodes
import de.corespace.shroud.core.net.IceServerDto
import de.corespace.shroud.core.net.PeerMediaStateDto
import de.corespace.shroud.core.net.wire.ApiTime
import de.corespace.shroud.core.realtime.RealtimeEvent
import de.corespace.shroud.core.storage.SecurityPreferences
import de.corespace.shroud.core.storage.StorageSeal
import de.corespace.shroud.testing.FakeSharedPreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import org.webrtc.EglBase
import org.webrtc.VideoTrack
import java.time.Instant
import java.util.UUID

// A scripted two-sided world for CallControllerTest (calls §12; the web's controller.selftest.ts
// is the model): a fake server with the calls rules of `routes/calls.rs` (calls §2.1), one socket,
// engine, system, permission set and peer directory per device, and a clock on the test scheduler.

/** The docs/calls.md vector identities: alice ↔ bob share this call secret. */
internal object Vector {
    val alicePrivate = hexToBytes("ef80f1878a337c4c39eeb6578cf9af4c38bc681ed61ec43084d00d1e1657723d")
    val alicePublic = hexToBytes("8b29cac884916cb7098ba9d90b61ef21d88596315cd1d04799a1962012e0b467")
    val bobPublic = hexToBytes("389c6f5486c58d1c61ee17df7793440e1e5e4b7822cae03fc92c9d2a4920e338")
    val secret: ByteArray get() = CallCrypto.callSecret(alicePrivate, alicePublic, bobPublic)
}

@OptIn(ExperimentalCoroutinesApi::class)
internal class SchedulerClock(private val scheduler: TestCoroutineScheduler) : AppClock {
    override fun nowMillis(): Long = START + scheduler.currentTime
    override fun elapsedMillis(): Long = scheduler.currentTime

    companion object {
        const val START = 1_790_000_000_000L
    }
}

internal class FakeSocket : CallSocket {
    val flow = MutableSharedFlow<RealtimeEvent>(extraBufferCapacity = 1024)
    override val events: SharedFlow<RealtimeEvent> = flow
    var held = false
    var holds = 0

    override fun hold(token: String) {
        held = true
        holds++
    }

    override fun release() {
        held = false
    }

    fun emit(event: RealtimeEvent) = check(flow.tryEmit(event)) { "socket buffer full" }
}

/** A WebRTC stand-in: SDPs with a fingerprint line and candidates; connects once both sides have each other's. */
internal class FakeEngine(private val name: String) : CallMediaEngine {
    var listener: CallMediaCallbacks? = null
    var peer: FakeEngine? = null
    var autoConnect = true
    var cameraAvailable = true
    val fingerprint = "AA:BB:CC:" + name.uppercase()
    var remoteFingerprint: String? = null
    var started = false
    var startedOffering: Boolean? = null
    var startedRelayOnly: Boolean? = null
    var hasLocalOffer = false
    var connected = false
    var offers = 0
    var restartOffers = 0
    var answers = 0
    var preferRelays = 0
    var closes = 0
    var micEnabled = true
    var awaitRemoteFrames = 0
    var awaitRemoteScreenFrames = 0
    var screenOn = false
    val receivedOffers = ArrayList<String>()
    val receivedAnswers = ArrayList<String>()
    val remoteCandidates = ArrayList<IceCandidatePayload>()

    override var hasRemoteDescription = false
    override val canOffer: Boolean get() = !hasLocalOffer
    override var canSendVideo = true
    override var canSendScreen = true
    override var isCameraOn = false
    override val canSwitchCamera: Boolean get() = isCameraOn
    override val usesFrontCamera: Boolean = true
    override var screenQuality: ScreenShareQuality = ScreenShareQuality.Standard
    override val localVideoTrack: VideoTrack? = null
    override val remoteVideoTrack: VideoTrack? = null
    override val remoteScreenTrack: VideoTrack? = null
    override val eglContext: EglBase.Context? = null

    override fun setCallbacks(callbacks: CallMediaCallbacks?) {
        listener = callbacks
    }

    override fun start(iceServers: List<IceServerDto>, video: Boolean, offering: Boolean, relayOnly: Boolean) {
        started = true
        startedOffering = offering
        startedRelayOnly = relayOnly
        isCameraOn = video && cameraAvailable
        hasLocalOffer = false
        hasRemoteDescription = false
        connected = false
        remoteCandidates.clear()
    }

    override suspend fun makeOffer(iceRestart: Boolean): String {
        offers++
        if (iceRestart) restartOffers++
        hasLocalOffer = true
        candidates()
        return sdp("offer$offers")
    }

    override suspend fun answer(offerSdp: String): String {
        receivedOffers += offerSdp
        hasRemoteDescription = true
        answers++
        candidates()
        maybeConnect()
        return sdp("answer$answers")
    }

    override suspend fun applyAnswer(sdp: String): Boolean {
        if (!hasLocalOffer) return false
        receivedAnswers += sdp
        hasLocalOffer = false
        hasRemoteDescription = true
        maybeConnect()
        return true
    }

    override fun addRemoteCandidates(candidates: List<IceCandidatePayload>) {
        remoteCandidates += candidates
        maybeConnect()
    }

    override fun setMicrophoneEnabled(enabled: Boolean) {
        micEnabled = enabled
    }

    override suspend fun localAudioLevel(): Float? = if (started) 0.2f else null
    override suspend fun remoteCertificateFingerprint(): String? = remoteFingerprint ?: peer?.fingerprint

    override fun startCamera(): Boolean {
        if (!cameraAvailable) return false
        isCameraOn = true
        return true
    }

    override fun stopCamera() {
        isCameraOn = false
    }

    override fun switchCamera() = Unit

    override fun awaitRemoteFrame() {
        awaitRemoteFrames++
    }

    override fun awaitRemoteScreenFrame() {
        awaitRemoteScreenFrames++
    }

    override fun startScreen(grant: ScreenCaptureGrant): Boolean {
        screenOn = true
        return true
    }

    override fun stopScreen() {
        screenOn = false
    }

    override fun preferRelay() {
        preferRelays++
    }

    override fun close() {
        closes++
        started = false
        isCameraOn = false
        hasLocalOffer = false
        hasRemoteDescription = false
        connected = false
        screenOn = false
    }

    /** What WebRTC reports for the link (the engine's `publishLink`). */
    fun report(state: String) = listener?.onConnection(state)

    private fun sdp(kind: String) =
        "v=0\r\no=- $name $kind\r\na=fingerprint:sha-256 $fingerprint\r\na=candidate:1 1 udp 1 10.9.9.9 9 typ host\r\n"

    private fun candidates() {
        listener?.onLocalCandidate(IceCandidatePayload("candidate:$name-a 1 udp 1 10.0.0.1 9 typ host", "0", 0))
        listener?.onLocalCandidate(IceCandidatePayload("candidate:$name-b 1 udp 1 10.0.0.2 9 typ host", "0", 0))
        listener?.onLocalCandidate(IceCandidatePayload("", "0", 0))
    }

    private fun maybeConnect() {
        val other = peer ?: return
        if (!autoConnect || connected) return
        if (hasRemoteDescription && other.hasRemoteDescription && remoteCandidates.isNotEmpty() && other.remoteCandidates.isNotEmpty()) {
            connected = true
            other.connected = true
            listener?.onConnection("connected")
            other.listener?.onConnection("connected")
        }
    }
}

internal class FakeSystem : CallSystem {
    val log = ArrayList<String>()
    val ended = ArrayList<Pair<UUID, CallEndCause>>()
    val missed = ArrayList<UUID>()
    var speakerOn = false
    override val isOnEarpiece = MutableStateFlow(true)

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

    override fun update(callId: UUID, peerName: String, video: Boolean) {
        log += "update:$peerName:$video"
    }

    override fun answerFromApp(callId: UUID) {
        log += "answer"
    }

    override fun mediaStarted(callId: UUID, withCamera: Boolean) {
        log += "media:$withCamera"
    }

    override fun screenShareStarted() {
        log += "screen-on"
    }

    override fun screenShareStopped() {
        log += "screen-off"
    }

    override fun setSpeaker(on: Boolean) {
        speakerOn = on
        isOnEarpiece.value = !on
    }

    override fun postMissedCall(callId: UUID, peerUserId: UUID?, peerName: String?, video: Boolean) {
        missed += callId
    }

    override fun clear() {
        log += "clear"
    }
}

internal class FakePermissions : CallPermissions {
    var microphone = true
    var camera = true
    override suspend fun microphone(): Boolean = microphone
    override suspend fun camera(): Boolean = camera
}

internal class FakePeers(private val names: Map<UUID, String>) : CallPeerDirectory {
    var stored = true
    var deriveDelayMs = 0L
    var deriveError: Exception? = null
    val confirmed = ArrayList<UUID>()

    override fun storedSecret(peer: UUID): ByteArray? = if (stored) Vector.secret else null

    override suspend fun deriveSecret(peer: UUID): ByteArray {
        if (deriveDelayMs > 0) delay(deriveDelayMs)
        deriveError?.let { throw it }
        return Vector.secret
    }

    override fun isSafetyVerified(peer: UUID): Boolean = peer in confirmed
    override fun safetyNumber(peer: UUID): String = "12345 67890"
    override fun confirmSafety(peer: UUID) {
        confirmed += peer
    }

    override fun contactUsername(peer: UUID): String? = names[peer]
}

/** One recorded `POST /calls/{id}/signal`. */
internal data class SentSignal(val callId: UUID, val from: UUID, val type: String, val payload: String)

@OptIn(ExperimentalCoroutinesApi::class)
internal class CallWorld(val scheduler: TestCoroutineScheduler, eager: Boolean = false) {
    val clock = SchedulerClock(scheduler)
    val scope = CoroutineScope(SupervisorJob() + if (eager) UnconfinedTestDispatcher(scheduler) else StandardTestDispatcher(scheduler))
    val devices = ArrayList<Device>()
    val calls = LinkedHashMap<UUID, ServerCall>()
    val signals = ArrayList<SentSignal>()
    val requests = ArrayList<String>()
    var duplicateEvents = false
    var deliverRings = true
    var dropSignalType: String? = null
    var createDelayMs = 0L
    var createError: ApiError? = null

    /** `GET /calls/{id}` fails with a transport error. */
    var offline = false
    var iceServers: List<IceServerDto> = listOf(IceServerDto(listOf("stun:stun.example:3478")))

    val aliceId: UUID = UUID.fromString("00000000-0000-0000-0000-00000000000a")
    val bobId: UUID = UUID.fromString("00000000-0000-0000-0000-00000000000b")
    private val names = mapOf(aliceId to "alice", bobId to "bob")

    fun device(name: String, userId: UUID): Device = Device(name, userId, names.getValue(userId)).also { devices += it }

    fun settle() = scheduler.runCurrent()

    fun advance(ms: Long) {
        scheduler.advanceTimeBy(ms)
        scheduler.runCurrent()
    }

    fun close() = scope.cancel()

    fun devicesOf(user: UUID) = devices.filter { it.userId == user }

    /** Devices whose socket hears nothing (a gap). */
    val muted = HashSet<Device>()

    fun deliver(to: Device, event: RealtimeEvent) {
        if (to in muted) return
        to.socket.emit(event)
        if (duplicateEvents) to.socket.emit(event)
    }

    /** Ends a live call on the server without telling anyone (a socket gap). */
    fun endSilently(id: UUID, status: String, reason: String) {
        val call = calls.getValue(id)
        call.status = status
        call.reason = reason
        call.endedAt = clock.now()
    }

    inner class Device(val name: String, val userId: UUID, val username: String) {
        val deviceId: UUID = UUID.randomUUID()
        val token = "token-$name"
        val session = MutableStateFlow<Session?>(Session(token, Ids.wire(userId), username, null, Ids.wire(deviceId)))
        val socket = FakeSocket()
        val engine = FakeEngine(name)
        val system = FakeSystem()
        val permissions = FakePermissions()
        val peers = FakePeers(names)
        val security = SecurityPreferences(FakeSharedPreferences(), StorageSeal())
        val preferences = CallPreferences(FakeSharedPreferences(), StorageSeal(), security)
        var callScreensClosed = 0
        val controller = CallController(
            scope = scope,
            backend = Backend(this),
            socket = socket,
            session = session,
            peers = peers,
            preferences = preferences,
            permissions = permissions,
            clock = clock,
            onCallEnded = { callScreensClosed++ },
        ).also { it.attach(engine, system) }

        val call: ActiveCall? get() = controller.ui.value.active
        val phase: CallPhase? get() = call?.phase
    }

    inner class ServerCall(val id: UUID, val caller: Device, val calleeUser: UUID, val modality: CallModality, val createdAt: Instant) {
        var status = "ringing"
        var reason: String? = null
        var calleeDevice: Device? = null
        var answeredAt: Instant? = null
        var endedAt: Instant? = null
        val media = HashMap<UUID, String>()

        val isLive get() = status == "ringing" || status == "active"

        fun dto(viewer: Device?): CallDto {
            val other = when (viewer) {
                caller -> calleeDevice
                calleeDevice -> caller
                else -> null
            }
            val kept = if (status == "active" && other != null) media[other.deviceId]?.let { PeerMediaStateDto(other.deviceId, it) } else null
            return CallDto(
                id = id,
                callerUserId = caller.userId,
                callerDeviceId = caller.deviceId,
                callerUsername = caller.username,
                calleeUserId = calleeUser,
                calleeDeviceId = calleeDevice?.deviceId,
                calleeUsername = names[calleeUser],
                modality = modality.wire,
                status = status,
                endedReason = reason,
                callProtocol = 2,
                createdAtWire = ApiTime.format(createdAt),
                answeredAt = answeredAt,
                endedAt = endedAt,
                peerMediaState = kept,
            )
        }

        fun participants(): List<Device> = devices.filter { it.userId == caller.userId || it.userId == calleeUser }
    }

    private fun notFound() = ApiError.Server(ErrorCodes.NOT_FOUND, "Call not found.", 404)

    private inner class Backend(private val me: Device) : CallsBackend {
        override suspend fun iceServers(token: String): List<IceServerDto> = iceServers

        override suspend fun createCall(token: String, peerUserId: UUID, modality: CallModality): CallDto {
            requests += "create:${me.name}"
            createError?.let { throw it }
            val call = ServerCall(UUID.randomUUID(), me, peerUserId, modality, clock.now())
            calls[call.id] = call
            if (deliverRings) {
                for (device in devices) {
                    if (device === me) continue
                    if (device.userId == peerUserId || device.userId == me.userId) deliver(device, RealtimeEvent.CallRing(call.dto(device)))
                }
            }
            if (createDelayMs > 0) delay(createDelayMs)
            return call.dto(me)
        }

        override suspend fun call(token: String, callId: UUID): CallDto {
            requests += "get:${me.name}"
            if (offline) throw ApiError.Transport("offline")
            return (calls[callId] ?: throw notFound()).dto(me)
        }

        override suspend fun history(token: String, limit: Int, before: String?): List<CallDto> =
            calls.values.filter { !it.isLive && (it.caller.userId == me.userId || it.calleeUser == me.userId) }
                .sortedByDescending { it.createdAt }
                .map { it.dto(me) }

        override suspend fun accept(token: String, callId: UUID): CallDto {
            requests += "accept:${me.name}"
            val call = calls[callId] ?: throw notFound()
            if (call.calleeUser != me.userId) throw ApiError.Server(ErrorCodes.FORBIDDEN, "Only the callee can accept this call.", 403)
            if (call.status != "ringing") throw ApiError.Server(ErrorCodes.VALIDATION_ERROR, "The call is no longer ringing.", 400)
            call.status = "active"
            call.calleeDevice = me
            call.answeredAt = clock.now()
            for (device in call.participants()) if (device !== me) deliver(device, RealtimeEvent.CallAccepted(call.dto(device)))
            return call.dto(me)
        }

        override suspend fun reject(token: String, callId: UUID): CallDto {
            requests += "reject:${me.name}"
            val call = calls[callId] ?: throw notFound()
            if (call.status == "ringing") end(call, "rejected", "rejected")
            return call.dto(me)
        }

        override suspend fun hangup(token: String, callId: UUID): CallDto {
            requests += "hangup:${me.name}"
            val call = calls[callId] ?: throw notFound()
            when {
                call.status == "ringing" && me === call.caller -> end(call, "cancelled", "cancelled")
                call.status == "ringing" -> end(call, "missed", "declined")
                call.status == "active" -> end(call, "ended", "hangup")
            }
            return call.dto(me)
        }

        private fun end(call: ServerCall, status: String, reason: String) {
            call.status = status
            call.reason = reason
            call.endedAt = clock.now()
            for (device in call.participants()) if (device !== me) deliver(device, RealtimeEvent.CallEnded(call.dto(device)))
        }

        override suspend fun heartbeat(token: String, callId: UUID): CallDto {
            requests += "heartbeat:${me.name}"
            return (calls[callId] ?: throw notFound()).dto(me)
        }

        override suspend fun signal(token: String, callId: UUID, signalType: String, payload: String) {
            val call = calls[callId] ?: throw notFound()
            if (call.status == "ringing") throw ApiError.Server(ErrorCodes.CALL_NOT_ANSWERED, "The call has not been answered.", 409)
            if (call.status != "active") throw ApiError.Server(ErrorCodes.CALL_ENDED, "The call has ended.", 409)
            val target = when (me) {
                call.caller -> call.calleeDevice
                call.calleeDevice -> call.caller
                else -> throw ApiError.Server(ErrorCodes.FORBIDDEN, "Only the device that answered the call can signal.", 403)
            } ?: return
            signals += SentSignal(callId, me.deviceId, signalType, payload)
            if (signalType == "media_state") call.media[me.deviceId] = payload
            if (signalType == dropSignalType) return
            deliver(target, RealtimeEvent.CallSignal(callId, me.userId, Ids.wire(me.deviceId), signalType, payload))
        }
    }
}
