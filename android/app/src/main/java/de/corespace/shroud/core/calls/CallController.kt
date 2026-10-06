package de.corespace.shroud.core.calls

import de.corespace.shroud.core.auth.Session
import de.corespace.shroud.core.auth.SessionController
import de.corespace.shroud.core.calls.crypto.CallCrypto
import de.corespace.shroud.core.calls.crypto.CallCryptoException
import de.corespace.shroud.core.calls.crypto.CallSignalKeys
import de.corespace.shroud.core.calls.media.FrameSize
import de.corespace.shroud.core.calls.media.shapeChanged
import de.corespace.shroud.core.calls.signal.CallEndReason
import de.corespace.shroud.core.calls.signal.CallMediaOrder
import de.corespace.shroud.core.calls.signal.CallSdp
import de.corespace.shroud.core.calls.signal.CallSignal
import de.corespace.shroud.core.calls.signal.CallView
import de.corespace.shroud.core.crypto.CryptoError
import de.corespace.shroud.core.crypto.Entropy
import de.corespace.shroud.core.crypto.Primitives
import de.corespace.shroud.core.crypto.SystemEntropy
import de.corespace.shroud.core.model.AppClock
import de.corespace.shroud.core.model.Bytes
import de.corespace.shroud.core.model.Ids
import de.corespace.shroud.core.model.PeerIdentityChangedException
import de.corespace.shroud.core.net.ApiError
import de.corespace.shroud.core.net.CALL_PROTOCOL
import de.corespace.shroud.core.net.CallDto
import de.corespace.shroud.core.net.CallModality
import de.corespace.shroud.core.net.CallSignalType
import de.corespace.shroud.core.net.CallStatus
import de.corespace.shroud.core.net.ErrorCodes
import de.corespace.shroud.core.net.IceServerDto
import de.corespace.shroud.core.notifications.NotificationKind
import de.corespace.shroud.core.realtime.ActiveCallProbe
import de.corespace.shroud.core.realtime.RealtimeEvent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.webrtc.VideoTrack
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.UUID

/**
 * 1:1 voice and video calls: sealed signalling, the media engine and the system's call UI — iOS
 * `CallController` (`ios/shroud/Services/Calls/CallController.swift`, "CC" below), protocol 2 of
 * `docs/calls.md`; calls §3, §4, §19. The caller offers (ICE restarts included); signals are sealed
 * with [CallCrypto] under the pair's call secret, then under a per-call forward secret.
 *
 * One per process (`CallsModule`). Main-confined like iOS's `@MainActor`: every public member and
 * all state belong to [scope]'s main thread; engine callbacks hop there. Network goes through
 * [backend] (IO inside `ApiClient`). Each call has a [Machine] that is replaced wholesale when the
 * call ends; every continuation after a suspension checks [current] (CC:1929-1931). Timers are
 * coroutines in the machine's own scope, cancelled when the call finishes (CC:1663-1674).
 *
 * Public actions run detached in [scope] and the caller awaits them, so a screen that goes away
 * (the conversation giving way to the call screen) never cancels a call half placed — iOS runs
 * them in unstructured `Task`s.
 *
 * The media engine (W3-CALLS-MEDIA) and the system integration (W3-CALLS-SYSTEM) are [attach]ed
 * later; without an engine no call can be placed or answered (rings still show), without a system
 * the call runs in-app only, as iOS without CallKit (CC:710-716).
 *
 * Never logs: names, keys, SDPs and payloads stay out of every log.
 *
 * @param session the signed-in session (token, our ids).
 * @param peers call secrets, safety numbers and the roster's names (C29).
 * @param onCallEnded called when the call screen closes (`active` became null): the foreground
 *   coordinator steps away if the app is in the background (`RootView.swift:246-254`).
 * @param deviceNoun "phone" or "tablet" for the share notice (P14).
 * @param screenCaptureSupported MediaProjection exists on this device.
 */
class CallController(
    private val scope: CoroutineScope,
    private val backend: CallsBackend,
    private val socket: CallSocket,
    private val session: StateFlow<Session?>,
    private val peers: CallPeerDirectory,
    private val preferences: CallPreferences,
    private val permissions: CallPermissions,
    private val clock: AppClock,
    private val entropy: Entropy = SystemEntropy,
    private val onCallEnded: () -> Unit = {},
    private val deviceNoun: () -> String = { "phone" },
    private val screenCaptureSupported: () -> Boolean = { true },
) : ActiveCallProbe {
    private val uiState = MutableStateFlow(
        CallUiState(screenShareQuality = preferences.screenShareQuality.value, centerStage = preferences.centerStage.value),
    )
    private val historyState = MutableStateFlow(CallHistoryState())
    private val errorState = MutableStateFlow<String?>(null)
    private val mediaStarting = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    /** The call screen: the call and its media (calls §3.2). */
    val ui: StateFlow<CallUiState> = uiState.asStateFlow()

    /** The Calls tab (calls §4.18). */
    val history: StateFlow<CallHistoryState> = historyState.asStateFlow()

    /** The last failure a screen shows as a toast after `startCall` returns (CC:69, 609-610). */
    val lastError: StateFlow<String?> = errorState.asStateFlow()

    /**
     * A call is about to take the microphone: a playing or recording voice note stops
     * (`shroudCallMediaStarting` + `VoicePlaybackCoordinator.stop()`, CC:1946-1949).
     */
    val callMediaStarting: SharedFlow<Unit> = mediaStarting.asSharedFlow()

    private var engine: CallMediaEngine? = null
    private var system: CallSystem? = null
    private var machine: Machine? = null
    private var generation = 0
    private val finished = ArrayDeque<UUID>()
    private var ignoreKitEnd = false
    private var dismissJob: Job? = null

    // History (CC:124-138).
    private var historyRows: List<RecentCall> = emptyList()
    private var endedHere: List<RecentCall> = emptyList()
    private val connectedHere = HashMap<UUID, Boolean>()
    private var historyCursor: Instant? = null
    private var historyRequest = 0
    private var historyApplied = 0
    private var historyEpoch = 0

    private val callbacks = EngineCallbacks()

    /**
     * The size of the area that shows their camera on our screen, in pixels ([setOwnView]): it goes
     * out with every `media_state` (`view`) so their camera is cut to it. Kept from call to call.
     */
    private var ownView: CallView? = null

    init {
        // The call keeps the socket when the chats lock or the app backgrounds, and opens it for a
        // ring that woke a locked phone (CC:241-246).
        scope.launch { socket.events.collect { handleRealtime(it) } }
        // A push can ring before the session is known; the socket needs its token (CC:310-316).
        scope.launch {
            var last: String? = null
            session.collect { current ->
                val token = current?.token
                if (token != null && token != last) {
                    if (machine != null) holdSocket()
                    launch { refreshHistory() }
                }
                last = token
            }
        }
        scope.launch { preferences.screenShareQuality.collect { q -> uiState.update { it.copy(screenShareQuality = q) } } }
        scope.launch {
            preferences.centerStage.collect { on ->
                uiState.update { it.copy(centerStage = on) }
                engine?.setCenterStage(on)
            }
        }
    }

    // ---- Public state ----

    /** The call on screen (CC:68). */
    private var active: ActiveCall?
        get() = uiState.value.active
        set(value) {
            val before = uiState.value.active
            uiState.update { it.copy(active = value) }
            if (before != null && value == null) onCallEnded()
        }

    private inline fun editActive(transform: (ActiveCall) -> ActiveCall) {
        val call = active ?: return
        active = transform(call)
    }

    /**
     * True while this phone is ringing, connecting, or in a call (CC:510-520): the shell must not
     * route to Welcome or the lock screen, nor sign out, meanwhile (calls §3.2).
     */
    val isInCall: Boolean
        get() {
            val m = machine
            if (m != null && !m.ended) return true
            return when (active?.phase) {
                CallPhase.OutgoingRinging, CallPhase.IncomingRinging, CallPhase.Connecting, CallPhase.Active -> true
                else -> false
            }
        }

    /** A call screen is up, the ending one included: the socket stays in the background (`RootView.swift:332-344`). */
    override val hasActiveCall: Boolean get() = active != null

    /**
     * Wires the media engine and the system integration (W3-INT; W2 tests pass fakes). The engine
     * reports back through this controller from then on.
     *
     * Does not read [CallMediaEngine.eglContext]. That read loads the native WebRTC library, so a
     * process started by a push, boot, or the call screen would load it before anyone places a call.
     * [publishEngineContext] runs immediately before each [CallMediaEngine.start].
     */
    fun attach(engine: CallMediaEngine, system: CallSystem) {
        this.engine?.setCallbacks(null)
        this.engine = engine
        this.system = system
        engine.setCallbacks(callbacks)
        engine.screenQuality = uiState.value.screenShareQuality
        engine.setCenterStage(uiState.value.centerStage)
        uiState.update { it.copy(eglContext = null) }
    }

    // ---- History (CC:318-425) ----

    /** Loads the newest page of the history again; older pages already loaded stay (CC:320-356). */
    suspend fun refreshHistory() {
        val current = session.value ?: return
        val me = Ids.parse(current.userId) ?: return
        val myDevice = Ids.parse(current.deviceId)
        historyRequest += 1
        val request = historyRequest
        val epoch = historyEpoch
        try {
            val page = backend.history(current.token, CallHistory.PAGE_SIZE, null)
            // A newer load already answered, or the account signed out meanwhile.
            if (epoch != historyEpoch || request <= historyApplied) return
            historyApplied = request
            val rows = page.mapNotNull { CallHistory.recentCall(it, me, myDevice, connectedHere[it.id], ::localCallName) }
            val full = page.size >= CallHistory.PAGE_SIZE
            val oldest = page.lastOrNull()?.createdAt
            val cursor = historyCursor
            if (full && oldest != null && cursor != null && cursor < oldest) {
                // Keep the older pages the list has scrolled into.
                val ids = rows.mapTo(HashSet()) { it.id }
                historyRows = rows + historyRows.filter { it.id !in ids && it.at < oldest }
            } else {
                historyRows = rows
                historyCursor = oldest
                historyState.update { it.copy(hasMore = full) }
            }
            historyState.update { it.copy(error = null, hasLoaded = true) }
            publishRecent()
        } catch (e: CancellationException) {
            // Leaving the tab cancels its load: that is no failure.
            throw e
        } catch (e: Exception) {
            if (epoch != historyEpoch || request <= historyApplied) return
            // Calls on screen stay; an empty list says the load failed rather than "No calls yet".
            val message = if (historyState.value.recent.isEmpty()) SessionController.userMessage(e) else null
            historyState.update { it.copy(hasLoaded = true, error = message) }
        }
    }

    /** Loads the next older page, when the list scrolled to its end (CC:358-390). */
    suspend fun loadOlderHistory() {
        val state = historyState.value
        val before = historyCursor ?: return
        if (!state.hasMore || state.loadingOlder) return
        val current = session.value ?: return
        val me = Ids.parse(current.userId) ?: return
        val myDevice = Ids.parse(current.deviceId)
        val epoch = historyEpoch
        historyState.update { it.copy(loadingOlder = true, olderFailed = false) }
        try {
            // Dates travel in milliseconds, `created_at` in microseconds: 1 ms later still takes
            // every call from the boundary millisecond, and the ids below drop the ones listed.
            val page = try {
                backend.history(current.token, CallHistory.PAGE_SIZE, formatBefore(before))
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                if (epoch == historyEpoch && historyCursor == before) historyState.update { it.copy(olderFailed = true) }
                return
            }
            if (epoch != historyEpoch || historyCursor != before) return
            val ids = historyRows.mapTo(HashSet()) { it.id }
            historyRows = historyRows + page.mapNotNull { call ->
                if (call.id in ids) null else CallHistory.recentCall(call, me, myDevice, connectedHere[call.id], ::localCallName)
            }
            historyCursor = page.lastOrNull()?.createdAt ?: before
            historyState.update { it.copy(hasMore = page.size >= CallHistory.PAGE_SIZE) }
            publishRecent()
        } finally {
            if (epoch == historyEpoch) historyState.update { it.copy(loadingOlder = false) }
        }
    }

    /** `recent` from the server's rows and the calls that ended here since (CC:419-425). */
    private fun publishRecent() {
        val listed = historyRows.mapTo(HashSet()) { it.id }
        endedHere = endedHere.filter { it.id !in listed }
        val rows = (endedHere + historyRows).sortedByDescending { it.at }
        historyState.update { it.copy(recent = rows) }
    }

    // ---- Realtime (CC:427-455) ----

    private fun handleRealtime(event: RealtimeEvent) {
        if (event is RealtimeEvent.Connected) {
            // Calls may have come and gone while the socket was down.
            scope.launch { refreshHistory() }
            val m = machine
            if (m != null && m.serverId != null) m.scope.launch { reconcile(m) }
            return
        }
        val isCallEvent = event is RealtimeEvent.CallRing || event is RealtimeEvent.CallAccepted ||
            event is RealtimeEvent.CallEnded || event is RealtimeEvent.CallSignal
        if (!isCallEvent) return
        // The outgoing call's id is not known yet. Rings for other people are not this call.
        val m = machine
        if (m != null && m.dialing && m.serverId == null && event !is RealtimeEvent.CallRing) {
            m.earlyEvents += event
            return
        }
        if (event is RealtimeEvent.CallRing) handleRing(event.call)
        if (event is RealtimeEvent.CallAccepted) handleAccepted(event.call)
        if (event is RealtimeEvent.CallEnded) {
            handleEnded(event.call)
            // Every call of ours ends up here, also those of our other devices.
            scope.launch { refreshHistory() }
        }
        if (event is RealtimeEvent.CallSignal) handleSignal(event)
    }

    // ---- Push (CC:457-548; Android: CallPush from UnifiedPush or the background socket) ----

    /** True when a push or the socket already ended this call: a later ring must not start it again (CC:457-461). */
    fun callWasFinished(callId: UUID): Boolean = callId in finished

    /**
     * A call push (plan §1.7.10 `PushDispatcher`, calls REVISION 2026-10-01): `call`/`video_call`
     * ring (`handleVoipPush`, CC:463-508), `call_ended` stops a ring (`endFromVoipPush`, CC:522-548),
     * `missed_call` is shown unless this phone showed that call itself (calls §6.3). Android needs
     * no PushKit report-and-end: a push for a call that is over, or that comes during another
     * call, shows nothing (calls §4.5).
     */
    fun handleCallPush(push: CallPush) {
        if (session.value == null) return
        when (push.kind) {
            NotificationKind.Call, NotificationKind.VideoCall -> ringFromPush(push)
            NotificationKind.CallEnded -> endFromPush(push.callId)
            NotificationKind.MissedCall -> {
                if (callWasFinished(push.callId) || machine?.serverId == push.callId) return
                system?.postMissedCall(push.callId, push.peerUserId, push.callerName, video = false)
            }
            else -> Unit
        }
    }

    private fun ringFromPush(push: CallPush) {
        val id = push.callId
        val modality = if (push.kind == NotificationKind.VideoCall) CallModality.Video else CallModality.Voice
        // Opened names are trimmed and cut to 64 characters (calls §2.7, NotificationPayload.swift:66-95).
        val name = push.callerName?.trim()?.take(MAX_PUSH_NAME)?.takeIf { it.isNotEmpty() } ?: CallTexts.INCOMING_CALL_PLACEHOLDER
        if (callWasFinished(id)) return
        val m = machine
        // Another call is in progress: this one is not shown (CC:485-489).
        if (m != null && m.serverId != id) return
        if (m != null) {
            applyCallerName(name, id, modality == CallModality.Video)
            return
        }
        if (!beginIncoming(id, push.peerUserId ?: UUID.randomUUID(), name, modality, peerDeviceId = null, reportKit = true)) return
        scope.launch { confirmStillRinging(id) }
    }

    private fun endFromPush(id: UUID) {
        val m = machine
        val phase = active?.phase
        // This phone answered, or is already talking: the server check hangs up only if it is over.
        if (m != null && m.serverId == id && (m.accepting || phase == CallPhase.Connecting || phase == CallPhase.Active)) {
            active?.let { system?.update(id, it.peerUsername, it.modality == CallModality.Video) }
            scope.launch { confirmStillRinging(id) }
            return
        }
        rememberFinished(id)
        // Stops a ring or a notification the system may hold for it; nothing else to show.
        system?.reportEnded(id, CallEndCause.Remote)
        if (m != null && m.serverId == id) scope.launch { confirmStillRinging(id) }
    }

    // ---- Actions (CC:550-698) ----

    /**
     * Places a call (CC:552-625; calls §4.4, §19). Shows [lastError] when it cannot start; the
     * screen shows it as a toast once this returns. Android asks for the microphone first (§6.8).
     */
    suspend fun startCall(peerUserId: UUID, peerUsername: String, modality: CallModality) = detached {
        doStartCall(peerUserId, peerUsername, modality)
    }

    private suspend fun doStartCall(peerUserId: UUID, peerUsername: String, modality: CallModality) {
        // A call this phone still has open, including one whose screen already closed, is ended
        // first. Otherwise every later call is refused.
        if (machine != null || (active != null && active?.phase != CallPhase.Ending)) userEnd(fromKit = false)
        if (active?.phase == CallPhase.Ending) active = null
        if (machine != null || active != null) {
            errorState.value = CallTexts.ALREADY_IN_CALL
            return
        }
        if (token() == null) {
            errorState.value = CallTexts.NOT_SIGNED_IN
            return
        }
        val engine = engine ?: run {
            errorState.value = CallTexts.COULD_NOT_START
            return
        }
        if (!permissions.microphone()) {
            errorState.value = CallTexts.MIC_DENIED_CALL
            return
        }
        // A ring may have arrived while the system asked.
        if (machine != null || active != null) {
            errorState.value = CallTexts.ALREADY_IN_CALL
            return
        }
        errorState.value = null
        holdSocket()
        generation += 1
        val m = Machine(generation, CallCrypto.Role.Caller, scope).apply { dialing = true }
        machine = m
        active = makeCall(UUID.randomUUID(), peerUserId, peerUsername, modality, outgoing = true, CallPhase.OutgoingRinging)
        takeMicrophone()
        if (modality == CallModality.Video) system?.setSpeaker(true)

        try {
            val secret = callSecret(peerUserId)
            if (!current(m)) return secret.fill(0)
            val token = token() ?: throw CallSecretException.ChatsLocked
            val ice = backend.iceServers(token)
            if (!current(m)) return
            val relayOnly = relayPolicy(ice, preferences.alwaysRelayCalls)
            val camera = if (modality == CallModality.Video) permissions.camera() else false
            if (!current(m)) return
            publishEngineContext(engine)
            engine.start(ice, video = camera, offering = true, relayOnly = relayOnly)
            publishLocalPreview(modality, m)
            // The offer is prepared while it rings (docs/calls.md).
            m.offerTask = m.scope.async { engine.makeOffer(iceRestart = false) }
            val created = backend.createCall(token, peerUserId, modality)
            if (!current(m)) {
                scope.launch { quietly { backend.hangup(token, created.id) } }
                return
            }
            m.serverId = created.id
            m.identitySecret = secret
            m.keys = CallSignalKeys.identity(secret, created.id, CallCrypto.Role.Caller)
            m.dialing = false
            editActive { it.copy(id = created.id) }
            startHeartbeat(m)
            arm(m, RING_OUT_MS, CallTexts.NO_ANSWER, Notify.Hangup, "missed", KitClose.Report(CallEndCause.Missed))
            system?.reportOutgoing(created.id, peerUsername, modality == CallModality.Video)
            system?.mediaStarted(created.id, engine.isCameraOn)
            replayEarly(m)
            watchOffer(m)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (!current(m)) return
            val message = CallTexts.callErrorText(e, peerUsername)
            errorState.value = message
            finish(m, message, if (m.serverId == null) null else Notify.Hangup, "ended", KitClose.Report(CallEndCause.Error), ERROR_VISIBLE_MS)
        }
    }

    /** Answers the ring on screen (CC:627-629). */
    suspend fun acceptIncoming() = detached { answer(fromKit = false) }

    /** Declines the ring on screen (CC:631-633). */
    suspend fun rejectIncoming() = detached { userEnd(fromKit = false) }

    /** Hangs up, or cancels our own ring (CC:635-637). */
    suspend fun hangup() = detached { userEnd(fromKit = false) }

    /** The microphone's level for the speaking indicator, 0…1; null with no media (CC:639-642). */
    suspend fun localAudioLevel(): Float? = engine?.localAudioLevel()

    suspend fun toggleMute() = detached {
        val call = active ?: return@detached
        setMuted(!call.isMuted)
    }

    /**
     * Video on or off: a voice call becomes a video call and back, from either side, without a new
     * offer (CC:649-683). Only our own camera; theirs is theirs to switch.
     */
    suspend fun toggleVideo() = detached {
        val m = machine ?: return@detached
        val engine = engine ?: return@detached
        val call = active ?: return@detached
        if (!current(m) || m.cameraBusy) return@detached
        if (call.phase != CallPhase.OutgoingRinging && call.phase != CallPhase.Connecting && call.phase != CallPhase.Active) return@detached
        if (call.isVideoEnabled) {
            engine.stopCamera()
            uiState.update { it.copy(localVideoLive = false, canSwitchCamera = false) }
            setVideoEnabled(false, m)
            return@detached
        }
        if (!engine.canSendVideo) {
            note(CallTexts.VIDEO_UNAVAILABLE, m)
            return@detached
        }
        m.cameraBusy = true
        val allowed = permissions.camera()
        m.cameraBusy = false
        if (!current(m) || active?.isVideoEnabled != false || active?.phase == CallPhase.Ending) return@detached
        if (!allowed) {
            note(CallTexts.CAMERA_DENIED, m)
            return@detached
        }
        if (!engine.startCamera()) {
            note(CallTexts.CAMERA_BUSY, m)
            return@detached
        }
        copyCamera(engine)
        m.cameraPaused = false
        m.pausedByBackground = false
        setVideoEnabled(true, m)
    }

    /** Speaker on or off by hand; video no longer moves it (CC:685-692). */
    fun toggleSpeaker() {
        val call = active ?: return
        if (call.phase == CallPhase.Ending) return
        val on = !call.speakerOn
        active = call.copy(speakerOn = on)
        machine?.speakerForVideo = false
        system?.setSpeaker(on)
    }

    /** Front ↔ back camera (CC:694-698). */
    fun switchCamera() {
        val engine = engine ?: return
        if (!uiState.value.canSwitchCamera || active?.isVideoEnabled != true) return
        engine.switchCamera()
        uiState.update { it.copy(usesFrontCamera = engine.usesFrontCamera) }
    }

    /** The safety number for the call on screen, when this phone has the contact's key (CC:2003-2007). */
    fun safetyNumberForActiveCall(): String? = active?.let { peers.safetyNumber(it.peerUserId) }

    /** The number on the call screen was compared (CC:2009-2015). */
    fun confirmSafety() {
        val call = active ?: return
        if (call.phase == CallPhase.Ending) return
        peers.confirmSafety(call.peerUserId)
        active = call.copy(safetyVerified = true)
    }

    // ---- System call UI (CC:2106-2144) ----

    /** Telecom answered the call (a headset, a watch, the system UI): answer it here (CC:2115-2118). */
    fun telecomAnswer(callId: UUID) {
        if (machine?.serverId != callId) return
        scope.launch { answer(fromKit = true) }
    }

    /** Telecom ended or declined the call, or its scope went away (CC:2120-2124, 2140-2143). */
    fun telecomEnd(callId: UUID) {
        if (ignoreKitEnd || machine?.serverId != callId) return
        scope.launch { userEnd(fromKit = true) }
    }

    /** Telecom muted or unmuted the call (`CXSetMutedCallAction`, CC:2126-2129; calls §6.2). */
    fun telecomMute(callId: UUID, muted: Boolean) {
        if (machine?.serverId != callId) return
        setMuted(muted)
    }

    /**
     * One of our screens came to the front or none is (calls §4.16, D3): the camera pauses in the
     * background — they see our face, not a frozen frame — and runs again on return.
     */
    fun onAppVisible(visible: Boolean) {
        val m = machine ?: return
        val engine = engine ?: return
        val call = active ?: return
        if (!current(m)) return
        if (!visible) {
            if (call.isVideoEnabled && engine.isCameraOn && !m.cameraPaused) {
                engine.stopCamera()
                m.cameraPaused = true
                m.pausedByBackground = true
                uiState.update { it.copy(localVideoLive = false) }
                sendMedia(m)
            }
            return
        }
        if (!m.pausedByBackground) return
        m.pausedByBackground = false
        m.cameraPaused = false
        if (!call.isVideoEnabled || call.phase == CallPhase.Ending) return
        if (engine.startCamera()) {
            copyCamera(engine)
            sendMedia(m)
        } else {
            uiState.update { it.copy(canSwitchCamera = false) }
            setVideoEnabled(false, m)
            note(CallTexts.CAMERA_BUSY, m)
        }
    }

    private fun setMuted(muted: Boolean) {
        val call = active ?: return
        if (call.phase == CallPhase.IncomingRinging || call.phase == CallPhase.Ending) return
        active = call.copy(isMuted = muted)
        engine?.setMicrophoneEnabled(!muted)
        machine?.let { sendMedia(it) }
        // core-telecom 1.0 has no mute request: Telecom is not told (calls §6.2).
    }

    // ---- Incoming (CC:728-925) ----

    private fun beginIncoming(
        id: UUID,
        peerUserId: UUID,
        peerUsername: String,
        modality: CallModality,
        peerDeviceId: UUID?,
        reportKit: Boolean,
    ): Boolean {
        if (active?.phase == CallPhase.Ending) active = null
        if (machine != null || active != null) return false
        generation += 1
        val m = Machine(generation, CallCrypto.Role.Callee, scope)
        m.serverId = id
        m.peerDeviceId = peerDeviceId
        machine = m
        active = makeCall(id, peerUserId, peerUsername, modality, outgoing = false, CallPhase.IncomingRinging)
        if (reportKit) system?.reportIncoming(id, peerUsername, modality == CallModality.Video)
        arm(m, RING_IN_MS, CallTexts.MISSED_CALL, null, "missed", KitClose.Report(CallEndCause.Missed))
        holdSocket()
        m.ringCheck = m.scope.launch {
            while (current(m)) {
                delay(RING_CHECK_MS)
                if (!current(m)) return@launch
                reconcile(m)
            }
        }
        return true
    }

    /** `call.ring` (CC:769-798). */
    private fun handleRing(call: CallDto) {
        val me = session.value?.let { Ids.parse(it.userId) } ?: return
        if (call.callProtocol != CALL_PROTOCOL || call.callStatus != CallStatus.Ringing) return
        if (call.calleeUserId != me || call.callerUserId == me) return
        if (callWasFinished(call.id)) return
        val name = callerName(call)
        val m = machine
        if (m != null && m.serverId == call.id) {
            if (m.peerDeviceId == null) m.peerDeviceId = call.callerDeviceId
            applyCallerName(name, call.id, call.callModality == CallModality.Video)
            editActive { it.copy(peerUserId = call.callerUserId, modality = call.callModality) }
            return
        }
        beginIncoming(call.id, call.callerUserId, name, call.callModality, call.callerDeviceId, reportKit = true)
    }

    /** `GET /calls/{id}` after a push ring or end: still ringing, answered elsewhere, or over (CC:800-829). */
    private suspend fun confirmStillRinging(id: UUID) {
        val m = machine ?: return
        if (m.serverId != id) return
        val token = token() ?: return
        val call = try {
            backend.call(token, id)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            return
        }
        if (!current(m)) return
        if (call.callProtocol == CALL_PROTOCOL && call.callStatus == CallStatus.Ringing) {
            m.peerDeviceId = call.callerDeviceId
            applyCallerName(callerName(call), call.id, call.callModality == CallModality.Video)
            editActive { it.copy(peerUserId = call.callerUserId, modality = call.callModality) }
            return
        }
        // Still ringing here: another of our devices answered. A call this phone already accepted
        // is `active` on the server too, and must keep going.
        if (call.callStatus == CallStatus.Active && active?.phase == CallPhase.IncomingRinging) {
            finish(m, CallTexts.ANSWERED_ELSEWHERE, null, "answered_elsewhere", KitClose.Report(CallEndCause.AnsweredElsewhere))
            return
        }
        if (call.callStatus == CallStatus.Active) return
        val text = CallEndReason.from(call.status, call.endedReason, isOutgoing = false)?.announcement
        finish(m, text, null, CallHistory.recentStatus(call.status, call.endedReason), KitClose.Report(kitReason(call.status, call.endedReason)))
    }

    /**
     * Answers the ring (CC:831-925; calls §4.6). From the app Telecom is told first and the answer
     * goes on here (core-telecom calls `onAnswer` only for answers it started itself).
     */
    private suspend fun answer(fromKit: Boolean) {
        val m = machine ?: return
        if (m.role != CallCrypto.Role.Callee || active?.phase != CallPhase.IncomingRinging) return
        val id = m.serverId ?: return
        val engine = engine ?: run {
            errorState.value = CallTexts.COULD_NOT_START
            return
        }
        if (!fromKit) system?.answerFromApp(id)
        if (!current(m) || m.accepting) return
        m.accepting = true
        // Android: no microphone, no answer; the ring goes on (calls §6.8, D7).
        if (!permissions.microphone()) {
            if (!current(m)) return
            m.accepting = false
            editActive { it.copy(notice = CallTexts.MIC_DENIED_ANSWER) }
            errorState.value = CallTexts.MIC_DENIED_ANSWER
            return
        }
        if (!current(m) || active?.phase != CallPhase.IncomingRinging) {
            m.accepting = false
            return
        }
        editActive { it.copy(phase = CallPhase.Connecting, notice = null) }
        takeMicrophone()
        val video = active?.modality == CallModality.Video
        if (active?.speakerOn == true) system?.setSpeaker(true)
        try {
            val peer = active?.peerUserId ?: return
            val secret = callSecret(peer)
            if (!current(m)) return secret.fill(0)
            val token = token() ?: return secret.fill(0)
            m.identitySecret = secret
            m.keys = CallSignalKeys.identity(secret, id, CallCrypto.Role.Callee)
            val ice = try {
                backend.iceServers(token)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                emptyList()
            }
            if (!current(m)) return
            val relayOnly = relayPolicy(ice, preferences.alwaysRelayCalls)
            val camera = if (video) permissions.camera() else false
            if (!current(m)) return
            publishEngineContext(engine)
            engine.start(ice, video = camera, offering = false, relayOnly = relayOnly)
            publishLocalPreview(active?.modality ?: CallModality.Voice, m)
            system?.mediaStarted(id, engine.isCameraOn)
            try {
                backend.accept(token, id)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (!current(m)) return
                val info = try {
                    backend.call(token, id)
                } catch (inner: CancellationException) {
                    throw inner
                } catch (_: Exception) {
                    null
                }
                if (info != null) {
                    val mine = session.value?.let { Ids.parse(it.deviceId) }
                    if (info.callStatus == CallStatus.Active && mine != null && info.calleeDeviceId != mine) {
                        finish(m, CallTexts.ANSWERED_ELSEWHERE, null, "answered_elsewhere", KitClose.Report(CallEndCause.AnsweredElsewhere))
                        return
                    }
                    if (!info.isLive) {
                        val text = CallEndReason.from(info.status, info.endedReason, isOutgoing = false)?.announcement
                        finish(m, text, null, CallHistory.recentStatus(info.status, info.endedReason), KitClose.Report(CallEndCause.Remote))
                        return
                    }
                }
                throw e
            }
            if (!current(m)) {
                scope.launch { quietly { backend.hangup(token, id) } }
                return
            }
            m.accepting = false
            m.ringLimit?.cancel()
            m.ringCheck?.cancel()
            startHeartbeat(m)
            arm(m, CONNECT_MS, CallTexts.COULD_NOT_CONNECT, Notify.Hangup, "ended", KitClose.Report(CallEndCause.Error))
            drainSignals(m)
        } catch (e: CancellationException) {
            throw e
        } catch (e: CallSecretException) {
            // The ring goes on: unlock and try again (CC:895-905).
            if (!current(m)) return
            m.accepting = false
            m.keys?.wipe()
            m.keys = null
            m.identitySecret?.fill(0)
            m.identitySecret = null
            editActive { it.copy(phase = CallPhase.IncomingRinging, notice = e.message) }
            errorState.value = e.message
        } catch (e: CallRelayUnavailableException) {
            // Nothing was accepted: end it here only, so the ring goes on on the other devices (CC:906-911).
            if (!current(m)) return
            val message = CallTexts.callErrorText(e, active?.peerUsername ?: "them")
            errorState.value = message
            finish(m, message, null, "ended", KitClose.Report(CallEndCause.Error), ERROR_VISIBLE_MS)
        } catch (e: PeerIdentityChangedException) {
            // End it here without telling the server, so the ring continues on their other devices
            // while this one asks for the safety number (CC:912-918).
            if (!current(m)) return
            val message = CallTexts.callErrorText(e, active?.peerUsername ?: "them")
            errorState.value = message
            finish(m, message, null, "ended", KitClose.Report(CallEndCause.Error), ERROR_VISIBLE_MS)
        } catch (e: Exception) {
            if (!current(m)) return
            val message = CallTexts.callErrorText(e, active?.peerUsername ?: "them")
            errorState.value = message
            finish(m, message, Notify.Hangup, "ended", KitClose.Report(CallEndCause.Error), ERROR_VISIBLE_MS)
        }
    }

    // ---- Socket events for a running call (CC:927-998) ----

    private fun handleAccepted(call: CallDto) {
        val m = machine ?: return
        if (m.serverId != call.id) return
        if (m.role == CallCrypto.Role.Caller) {
            callerAnswered(m, call)
        } else {
            val mine = session.value?.let { Ids.parse(it.deviceId) } ?: return
            if (call.calleeDeviceId != mine) {
                finish(m, CallTexts.ANSWERED_ELSEWHERE, null, "answered_elsewhere", KitClose.Report(CallEndCause.AnsweredElsewhere))
            }
        }
    }

    private fun handleEnded(call: CallDto) {
        val m = machine ?: return
        if (m.serverId != call.id) return
        val outgoing = active?.isOutgoing ?: (m.role == CallCrypto.Role.Caller)
        val text = CallEndReason.from(call.status, call.endedReason, outgoing)?.announcement
        finish(m, text, null, CallHistory.recentStatus(call.status, call.endedReason), KitClose.Report(kitReason(call.status, call.endedReason)))
    }

    private fun handleSignal(event: RealtimeEvent.CallSignal) {
        val m = machine ?: return
        if (m.serverId != event.callId || event.payload.isEmpty()) return
        val peer = active?.peerUserId
        if (event.fromUserId == null || peer == null || event.fromUserId != peer) return
        val fromDevice = event.fromDeviceId.lowercase()
        val peerDevice = m.peerDeviceId
        if (peerDevice != null && fromDevice.isNotEmpty() && fromDevice != Ids.wire(peerDevice)) return
        val inbound = Inbound(fromDevice, event.signalType, event.payload)
        if (m.keys == null) {
            m.earlySignals += inbound
            return
        }
        enqueueReceive(m, inbound)
    }

    private fun callerAnswered(m: Machine, call: CallDto) {
        if (active?.phase != CallPhase.OutgoingRinging) return
        m.peerDeviceId = call.calleeDeviceId
        m.ringLimit?.cancel()
        editActive { it.copy(phase = CallPhase.Connecting) }
        arm(m, CONNECT_MS, CallTexts.COULD_NOT_CONNECT, Notify.Hangup, "ended", KitClose.Report(CallEndCause.Error))
        m.scope.launch { sendOffer(m) }
    }

    private fun replayEarly(m: Machine) {
        val events = m.earlyEvents.toList()
        m.earlyEvents.clear()
        for (event in events) if (current(m)) handleRealtime(event)
    }

    // ---- Media and signals (CC:1000-1344) ----

    private fun watchOffer(m: Machine) {
        val task = m.offerTask ?: return
        m.scope.launch {
            try {
                task.await()
            } catch (e: Exception) {
                if (!current(m) || m.serverId == null) return@launch
                val phase = active?.phase
                if (phase != CallPhase.OutgoingRinging && phase != CallPhase.Connecting) return@launch
                errorState.value = CallTexts.OFFER_FAILED
                finish(m, CallTexts.OFFER_FAILED, Notify.Hangup, "ended", KitClose.Report(CallEndCause.Error), ERROR_VISIBLE_MS)
            }
        }
    }

    private suspend fun sendOffer(m: Machine) {
        try {
            val sdp = m.offerTask?.await()?.takeIf { it.isNotEmpty() } ?: throw IllegalStateException("No offer.")
            if (!current(m)) return
            ensureEphemeral(m)
            send(CallSignal.Offer(CallSdp.withoutCandidates(sdp), restart = false, ephemeral = m.ephPublic?.let { Bytes.of(it) }), m)
            markNegotiated(m)
        } catch (e: Exception) {
            if (!current(m)) return
            finish(m, CallTexts.COULD_NOT_CONNECT, Notify.Hangup, "ended", KitClose.Report(CallEndCause.Error))
        }
    }

    /** Opens, parses, dedups and acts on one signal (CC:1037-1092; calls §4.8). Runs on the machine's inbox. */
    private suspend fun receive(m: Machine, item: Inbound) {
        if (!current(m) || m.keys == null) return
        val id = m.serverId ?: return
        val key = openKey(item.type, m) ?: run {
            m.pendingSecure += item
            return
        }
        val opened = try {
            CallCrypto.open(item.payload, key, id, item.type)
        } catch (_: CallCryptoException) {
            return
        }
        val parsed = try {
            CallSignal.parse(opened, item.type)
        } catch (_: CallSignal.ParseException) {
            return
        }
        if (!m.seen.getOrPut(item.from) { HashSet() }.add(parsed.n)) return
        when (val signal = parsed.signal) {
            is CallSignal.Offer -> if (m.role == CallCrypto.Role.Callee) {
                answerOffer(signal.sdp, if (signal.restart) null else signal.ephemeral, signal.restart, m)
            }
            is CallSignal.Answer -> if (m.role == CallCrypto.Role.Caller) takeAnswer(signal.sdp, signal.ephemeral, m)
            is CallSignal.Candidates -> addRemote(signal.candidates, m)
            CallSignal.RestartRequest -> if (m.role == CallCrypto.Role.Caller) restartIce(m)
            is CallSignal.Media -> applyMedia(signal, parsed.n, item.from, m)
        }
    }

    /** Their `media_state` (CC:1065-1091): the latest wins; the server's kept copy can arrive after a newer one. */
    private fun applyMedia(signal: CallSignal.Media, n: Int, from: String, m: Machine) {
        if (!m.mediaOrder.isNewer(n, from)) return
        val call = active ?: return
        val engine = engine
        val cameraWasOn = !call.remoteCameraOff
        val screenWasOn = call.remoteSharingScreen
        // An app that knows screens always says whether it shares one; an older one never does.
        if (signal.screen != null) m.peerShowsScreens = true
        val updated = call.copy(remoteMicMuted = !signal.mic, remoteCameraOff = !signal.camera, remoteSharingScreen = signal.screen == true)
        active = updated
        // Our camera goes out in the shape they show it in; none said is their whole picture. A
        // shape within a few percent of the one in use keeps it (no restart of the cut).
        if (shapeChanged(m.peerView?.size(), signal.view?.size())) {
            m.peerView = signal.view
            engine?.setPeerView(signal.view)
        }
        if (signal.camera != cameraWasOn) {
            // Their picture shows again from its first new frame, never a stale one.
            uiState.update { it.copy(remoteVideoLive = false) }
            if (signal.camera) engine?.awaitRemoteFrame()
        }
        if (updated.remoteSharingScreen != screenWasOn) {
            uiState.update { it.copy(remoteScreenLive = false) }
            if (updated.remoteSharingScreen) {
                engine?.awaitRemoteScreenFrame()
                // Their screen is to be looked at, not listened to at the ear.
                preferSpeaker(m)
            }
        }
        refreshCanVideo()
        videoChanged(m)
    }

    private suspend fun answerOffer(sdp: String, ephemeral: Bytes?, restart: Boolean, m: Machine) {
        val engine = engine ?: return
        try {
            if (!restart) {
                ensureEphemeral(m)
                engageForward(ephemeral, m)
                m.gotSetup = true
            }
            noteFingerprint(sdp, m)
            val answer = engine.answer(sdp)
            if (!current(m)) return
            flushRemote(m)
            val ours = if (m.sealedAnswer) null else m.ephPublic?.let { Bytes.of(it) }
            send(CallSignal.Answer(CallSdp.withoutCandidates(answer), ours), m)
            if (!m.negotiated) markNegotiated(m) else releaseHeld(m)
            refreshCanVideo()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            if (!current(m) || m.negotiated) return
            finish(m, CallTexts.COULD_NOT_CONNECT, Notify.Hangup, "ended", KitClose.Report(CallEndCause.Error))
        }
    }

    private suspend fun takeAnswer(sdp: String, ephemeral: Bytes?, m: Machine) {
        val engine = engine ?: return
        if (!current(m)) return
        if (!m.gotSetup) {
            engageForward(ephemeral, m)
            m.gotSetup = true
        }
        noteFingerprint(sdp, m)
        val applied = try {
            engine.applyAnswer(sdp)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            false
        }
        if (!applied || !current(m)) return
        flushRemote(m)
        releaseHeld(m)
        refreshCanVideo()
    }

    /** The DTLS certificate must be the one the sealed SDP named (CC:1136-1159; calls §4.10). */
    private fun verifyFingerprint(m: Machine) {
        if (m.fingerprintChecked) return
        val expected = m.expectedFingerprint ?: return
        val engine = engine ?: return
        m.scope.launch {
            var got = engine.remoteCertificateFingerprint()
            if (got == null) {
                delay(FINGERPRINT_RETRY_MS)
                if (!current(m)) return@launch
                got = engine.remoteCertificateFingerprint()
            }
            if (!current(m) || got == null) return@launch
            m.fingerprintChecked = true
            if (!CallSdp.matches(expected, got)) {
                finish(m, CallTexts.NOT_VERIFIED, Notify.Hangup, "ended", KitClose.Report(CallEndCause.Error), ERROR_VISIBLE_MS)
            }
        }
    }

    /** Identity key for the first offer and answer; the per-call key after that (CC:1161-1166). */
    private fun openKey(type: String, m: Machine): ByteArray? {
        val setup = type == CallSignalType.SDP_OFFER || type == CallSignalType.SDP_ANSWER
        if (setup && !m.gotSetup) return m.keys?.receive
        return m.forward?.receive
    }

    /**
     * Derives the post-setup keys (CC:1168-1189). An older peer sends no ephemeral key and the
     * identity keys continue; a derivation that fails leaves [Machine.forward] unset, so the
     * addresses never go out under the long-term key.
     */
    private fun engageForward(theirKey: Bytes?, m: Machine) {
        if (m.forward != null) return
        val id = m.serverId ?: return
        val identity = m.keys ?: return
        if (theirKey != null) {
            val ours = m.ephPrivate ?: return
            val pub = m.ephPublic ?: return
            val secret = m.identitySecret ?: return
            val forward = try {
                CallCrypto.forwardSecret(secret, ours, pub, theirKey.toByteArray(), id)
            } catch (_: CryptoError) {
                return
            }
            m.forward = CallSignalKeys.forward(forward, id, m.role)
            forward.fill(0)
        } else {
            m.forward = identity
        }
        m.ephPrivate?.fill(0)
        m.ephPrivate = null
        m.identitySecret?.fill(0)
        m.identitySecret = null
    }

    private fun ensureEphemeral(m: Machine) {
        if (m.ephPrivate != null) return
        val private = entropy.bytes(Primitives.X25519_KEY_BYTES)
        m.ephPrivate = private
        m.ephPublic = Primitives.x25519Public(private)
    }

    private fun noteFingerprint(sdp: String, m: Machine) {
        val fingerprint = CallSdp.fingerprint(sdp) ?: return
        if (fingerprint != m.expectedFingerprint) m.fingerprintChecked = false
        m.expectedFingerprint = fingerprint
    }

    /** Sends what waited for the forward keys, and reopens what arrived before them (CC:1197-1212). */
    private fun releaseHeld(m: Machine) {
        flushGathered(m)
        val waiting = m.pendingSend.toList()
        m.pendingSend.clear()
        for (signal in waiting) if (current(m)) send(signal, m)
        val inbound = m.pendingSecure.toList()
        m.pendingSecure.clear()
        for (item in inbound) if (current(m)) enqueueReceive(m, item)
    }

    private fun addRemote(candidates: List<IceCandidatePayload>, m: Machine) {
        val engine = engine ?: return
        if (!current(m)) return
        if (!engine.hasRemoteDescription) {
            m.pendingRemote += candidates
            return
        }
        engine.addRemoteCandidates(candidates)
    }

    private fun flushRemote(m: Machine) {
        val waiting = m.pendingRemote.toList()
        m.pendingRemote.clear()
        if (waiting.isNotEmpty()) engine?.addRemoteCandidates(waiting)
    }

    /** A local candidate: batched for 100 ms once negotiated (CC:1230-1239). */
    private fun gathered(candidate: IceCandidatePayload) {
        val m = machine ?: return
        if (!current(m) || candidate.candidate.isEmpty()) return
        m.gathered += candidate
        if (!m.negotiated || m.batchTimer != null) return
        m.batchTimer = m.scope.launch {
            delay(ICE_BATCH_MS)
            if (!current(m)) return@launch
            m.batchTimer = null
            flushGathered(m)
        }
    }

    /** At most 20 candidates per signal, under the forward key (CC:1241-1251). */
    private fun flushGathered(m: Machine) {
        m.batchTimer?.cancel()
        m.batchTimer = null
        if (!m.negotiated || m.forward == null || !current(m)) return
        while (m.gathered.isNotEmpty()) {
            val count = minOf(ICE_BATCH_MAX, m.gathered.size)
            val batch = m.gathered.take(count)
            repeat(count) { m.gathered.removeAt(0) }
            send(CallSignal.Candidates(batch), m)
        }
    }

    private fun markNegotiated(m: Machine) {
        m.negotiated = true
        flushGathered(m)
        sendMedia(m)
        // iOS starts listening for broadcasts here (CC:1257); on Android sharing is possible from now on.
    }

    /**
     * What we send now (CC:1260-1266). A camera the system paused counts as off: they see our face,
     * not a still. `screen` always goes along: it also tells them this app can show theirs; so
     * does `view`, the size of the area their camera fills here ([setOwnView]).
     */
    private fun sendMedia(m: Machine) {
        if (!m.negotiated) return
        val call = active ?: return
        val camera = call.isVideoEnabled && engine?.isCameraOn == true && !m.cameraPaused
        m.viewTask?.cancel()
        m.viewTask = null
        m.sentView = ownView
        m.lastMediaAt = clock.elapsedMillis()
        send(CallSignal.Media(mic = !call.isMuted, camera = camera, screen = call.isSharingScreen, view = ownView), m)
    }

    /**
     * The call screen measured the area their camera fills, in pixels (docs/calls.md, "Framing and
     * Center Stage"): every `media_state` carries it from now on, and a shape more than 3 % off the
     * one they were last sent ([shapeChanged]: a rotation) goes out in a new one at once, at most
     * every [VIEW_GAP_MS]; changes in between wait and go out as one. A side of 0 or beyond
     * [CallView.MAX] is no measurement (a layout pass before the screen has a size) and is ignored:
     * the last view stays, so no `media_state` ever goes out without one once we had it.
     */
    fun setOwnView(width: Int, height: Int) {
        if (width !in 1..CallView.MAX || height !in 1..CallView.MAX) return
        val view = CallView(width, height)
        if (view == ownView) return
        ownView = view
        val m = machine ?: return
        if (!current(m) || !m.negotiated || m.viewTask != null) return
        if (!shapeChanged(m.sentView?.size(), view.size())) return
        val last = m.lastMediaAt
        val wait = if (last == null) 0L else VIEW_GAP_MS - (clock.elapsedMillis() - last)
        if (wait <= 0) {
            sendMedia(m)
            return
        }
        m.viewTask = m.scope.launch {
            delay(wait)
            m.viewTask = null
            if (current(m) && shapeChanged(m.sentView?.size(), ownView?.size())) sendMedia(m)
        }
    }

    /** Center Stage on or off, kept for the next calls too; the camera follows at once. */
    fun setCenterStage(on: Boolean) {
        if (uiState.value.centerStage == on) return
        preferences.setCenterStage(on)
        uiState.update { it.copy(centerStage = on) }
        engine?.setCenterStage(on)
    }

    /** Numbers, seals and queues one signal in order (CC:1268-1302). */
    private fun send(signal: CallSignal, m: Machine) {
        if (m.serverId == null) return
        val needsForward = when (signal) {
            is CallSignal.Offer -> signal.restart
            is CallSignal.Answer -> m.sealedAnswer
            else -> true
        }
        if (needsForward && m.forward == null) {
            m.pendingSend += signal
            return
        }
        // The first offer and the first answer stay under the identity keys; later signals use the per-call key.
        val key = when (signal) {
            is CallSignal.Offer -> if (signal.restart) m.forward?.send else m.keys?.send
            is CallSignal.Answer -> if (m.sealedAnswer) m.forward?.send else m.keys?.send
            else -> m.forward?.send
        } ?: return
        if (signal is CallSignal.Answer) m.sealedAnswer = true
        m.sent += 1
        val n = m.sent
        m.outbox.trySend { deliver(signal, n, key, m) }
    }

    /** `POST /calls/{id}/signal`, three tries (CC:1304-1331; calls §4.9). */
    private suspend fun deliver(signal: CallSignal, n: Int, key: ByteArray, m: Machine) {
        val id = m.serverId ?: return
        val token = token() ?: return
        if (!current(m)) return
        val payload = try {
            CallCrypto.seal(CallSignal.plaintext(signal, n), key, id, signal.signalType, entropy = entropy)
        } catch (_: CryptoError) {
            return
        }
        for (attempt in 0 until DELIVER_ATTEMPTS) {
            if (!current(m)) return
            try {
                backend.signal(token, id, signal.signalType, payload)
                return
            } catch (e: CancellationException) {
                throw e
            } catch (e: ApiError.Server) {
                if (e.code == ErrorCodes.CALL_ENDED) {
                    reconcile(m, fallback = CallTexts.CALL_ENDED)
                    return
                }
                val retry = attempt < DELIVER_ATTEMPTS - 1 && (e.code == ErrorCodes.CALL_NOT_ANSWERED || e.status >= 500 || e.status == 429)
                if (!retry) return
            } catch (_: ApiError.Transport) {
                if (attempt >= DELIVER_ATTEMPTS - 1) return
            } catch (_: Exception) {
                return
            }
            delay(if (attempt == 0) 500 else 1_500)
        }
    }

    /** Signals that arrived before the answer had keys, in order (CC:1333-1344). */
    private fun drainSignals(m: Machine) {
        val queued = m.earlySignals.toList()
        m.earlySignals.clear()
        for (item in queued) if (current(m)) enqueueReceive(m, item)
    }

    private fun enqueueReceive(m: Machine, item: Inbound) {
        m.inbox.trySend { receive(m, item) }
    }

    // ---- Connection (CC:1346-1457; calls §4.11) ----

    private fun linkChanged(state: String, m: Machine) {
        when (state) {
            "connected", "completed" -> {
                m.linkBroken = false
                verifyFingerprint(m)
                m.graceTimer?.cancel()
                m.graceTimer = null
                m.reconnectTimer?.cancel()
                m.reconnectTimer = null
                m.restartTimer?.cancel()
                m.restartTimer = null
                m.connectTimer?.cancel()
                val call = active
                if (call?.phase == CallPhase.Connecting) {
                    active = call.copy(
                        phase = CallPhase.Active,
                        startedAt = call.startedAt ?: clock.now(),
                        reconnecting = false,
                        connectionState = "connected",
                    )
                    val id = m.serverId
                    if (!m.reportedConnected && id != null) {
                        m.reportedConnected = true
                        system?.reportConnected(id)
                    }
                } else if (call != null && call.reconnecting) {
                    active = call.copy(reconnecting = false, connectionState = "connected")
                }
            }
            "disconnected" -> {
                m.linkBroken = true
                troubled(m)
                if (m.graceTimer != null) return
                m.graceTimer = m.scope.launch {
                    delay(GRACE_MS)
                    if (!current(m)) return@launch
                    m.graceTimer = null
                    if (m.linkBroken) restartIce(m)
                }
            }
            "failed" -> {
                m.linkBroken = true
                m.wantRelay = true
                troubled(m)
                restartIce(m)
            }
            "new", "connecting", "checking" -> {
                val call = active ?: return
                if (call.phase != CallPhase.Ending) active = call.copy(connectionState = if (state == "checking") "connecting" else state)
            }
            "closed" -> {
                // `engine.close()` runs after the call is marked ended, so this is a connection
                // that shut itself while the call was still up.
                val phase = active?.phase
                if (phase != CallPhase.Connecting && phase != CallPhase.Active) return
                finish(m, CallTexts.CONNECTION_LOST, Notify.Hangup, "ended", KitClose.Report(CallEndCause.Remote))
            }
        }
    }

    /** The link broke while active: "Reconnecting", and 30 s to recover (CC:1404-1416). */
    private fun troubled(m: Machine) {
        val call = active ?: return
        if (call.phase != CallPhase.Active) return
        if (!call.reconnecting) active = call.copy(reconnecting = true, connectionState = "disconnected")
        if (m.reconnectTimer != null) return
        m.reconnectTimer = m.scope.launch {
            delay(RECONNECT_LIMIT_MS)
            if (!current(m) || !m.linkBroken) return@launch
            finish(m, CallTexts.CONNECTION_LOST, Notify.Hangup, "ended", KitClose.Report(CallEndCause.Remote))
        }
    }

    /** The caller offers an ICE restart, the callee asks for one; 10 s apart at most (CC:1418-1457). */
    private fun restartIce(m: Machine) {
        val engine = engine ?: return
        if (!current(m) || !m.negotiated) return
        val phase = active?.phase
        if (phase != CallPhase.Connecting && phase != CallPhase.Active) return
        // Before the 10 s gate, so the offer that follows the wait gathers only relay candidates.
        if (m.wantRelay) engine.preferRelay()
        val now = clock.elapsedMillis()
        val wait = m.lastRestart + RESTART_GAP_MS - now
        if (m.lastRestart != NEVER && wait > 0) {
            if (m.restartTimer != null) return
            m.restartTimer = m.scope.launch {
                delay(wait)
                if (!current(m)) return@launch
                m.restartTimer = null
                if (m.linkBroken) restartIce(m)
            }
            return
        }
        m.lastRestart = now
        if (m.role == CallCrypto.Role.Callee) {
            send(CallSignal.RestartRequest, m)
            return
        }
        if (!engine.canOffer) {
            if (m.restartTimer != null) return
            m.restartTimer = m.scope.launch {
                delay(RESTART_RECHECK_MS)
                if (!current(m)) return@launch
                m.restartTimer = null
                if (m.linkBroken) restartIce(m)
            }
            return
        }
        m.scope.launch {
            if (!current(m)) return@launch
            try {
                val sdp = engine.makeOffer(iceRestart = true)
                if (!current(m)) return@launch
                send(CallSignal.Offer(CallSdp.withoutCandidates(sdp), restart = true, ephemeral = null), m)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // The next failed or disconnected report tries again.
            }
        }
    }

    // ---- Server liveness (CC:1459-1525) ----

    private fun startHeartbeat(m: Machine) {
        if (m.heartbeat != null) return
        m.heartbeat = m.scope.launch {
            while (current(m)) {
                delay(HEARTBEAT_MS)
                if (!current(m)) return@launch
                beat(m)
            }
        }
    }

    private suspend fun beat(m: Machine) {
        val id = m.serverId ?: return
        val token = token() ?: return
        try {
            val info = backend.heartbeat(token, id)
            if (!current(m)) return
            applyServerStatus(info, m)
        } catch (e: CancellationException) {
            throw e
        } catch (e: ApiError) {
            if (e.isNotFound) finish(m, CallTexts.CALL_ENDED, null, "ended", KitClose.Report(CallEndCause.Remote))
        } catch (_: Exception) {
            // A missed heartbeat is not an ended call. The 45 s server limit still applies.
        }
    }

    private suspend fun reconcile(m: Machine, fallback: String? = null) {
        val id = m.serverId ?: return
        val token = token() ?: return
        try {
            val info = backend.call(token, id)
            if (!current(m)) return
            applyServerStatus(info, m)
        } catch (e: CancellationException) {
            throw e
        } catch (e: ApiError) {
            // As iOS: an API answer ends the call only when the server no longer knows it; a
            // transport error is no answer, and the heartbeat and the 45 s server limit decide
            // (CC:1493-1496; calls §4.12 says otherwise, the code wins).
            if (e.isNotFound) finish(m, fallback ?: CallTexts.CALL_ENDED, null, "ended", KitClose.Report(CallEndCause.Remote))
        } catch (_: Exception) {
            if (fallback != null) finish(m, fallback, null, "ended", KitClose.Report(CallEndCause.Remote))
        }
    }

    private fun applyServerStatus(info: CallDto, m: Machine) {
        if (!current(m) || m.serverId != info.id) return
        if (info.callStatus == CallStatus.Ringing) return
        if (info.callStatus == CallStatus.Active) {
            if (m.role == CallCrypto.Role.Caller) {
                callerAnswered(m, info)
            } else if (active?.phase == CallPhase.IncomingRinging) {
                finish(m, CallTexts.ANSWERED_ELSEWHERE, null, "answered_elsewhere", KitClose.Report(CallEndCause.AnsweredElsewhere))
                return
            }
            catchUpMedia(info, m)
            return
        }
        val outgoing = active?.isOutgoing ?: (m.role == CallCrypto.Role.Caller)
        val text = CallEndReason.from(info.status, info.endedReason, outgoing)?.announcement
        finish(m, text, null, CallHistory.recentStatus(info.status, info.endedReason), KitClose.Report(kitReason(info.status, info.endedReason)))
    }

    /**
     * The other device's latest media state as the server kept it (CC:1866-1878): opened and checked
     * like any signal; one taken already, or older, changes nothing.
     */
    private fun catchUpMedia(info: CallDto, m: Machine) {
        val kept = info.peerMediaState ?: return
        val peerDevice = m.peerDeviceId ?: return
        if (kept.fromDeviceId != peerDevice) return
        enqueueReceive(m, Inbound(Ids.wire(kept.fromDeviceId), CallSignalType.MEDIA_STATE, kept.payload))
    }

    // ---- Ending (CC:1527-1686) ----

    private fun userEnd(fromKit: Boolean) {
        val m = machine ?: return
        val call = active ?: return
        if (call.phase == CallPhase.Ending) return
        if (call.phase == CallPhase.IncomingRinging) {
            finish(m, null, Notify.Reject, "rejected", if (fromKit) KitClose.None else KitClose.RequestEnd(CallEndCause.Rejected))
            return
        }
        val ringingOut = call.phase == CallPhase.OutgoingRinging
        finish(
            m,
            if (ringingOut) null else CallTexts.CALL_ENDED,
            Notify.Hangup,
            if (ringingOut) "cancelled" else "ended",
            if (fromKit) KitClose.None else KitClose.RequestEnd(CallEndCause.Local),
        )
    }

    /**
     * Ends [m] (CC:1551-1626) — the order matters: socket released, media torn down, the server
     * told, the system's call closed, the history row added, then the ending screen for [visibleMs]
     * (none without [text]).
     */
    private fun finish(m: Machine, text: String?, notify: Notify?, status: String, close: KitClose, visibleMs: Long = ENDING_VISIBLE_MS) {
        if (!current(m)) return
        m.ended = true
        releaseSocket()
        val ended = m.generation
        val serverId = m.serverId
        if (serverId != null) rememberFinished(serverId)
        m.scope.cancel()
        teardownMedia(m)
        val token = token()
        if (serverId != null && notify != null && token != null) {
            scope.launch {
                quietly {
                    when (notify) {
                        Notify.Hangup -> backend.hangup(token, serverId)
                        Notify.Reject -> backend.reject(token, serverId)
                    }
                }
                // The server lists the call once it has ended there.
                refreshHistory()
            }
        } else if (serverId != null) {
            scope.launch { refreshHistory() }
        }
        if (serverId != null) closeKit(serverId, close)
        val call = active
        if (call != null && serverId != null) {
            val now = clock.now()
            connectedHere[serverId] = call.startedAt != null
            endedHere = listOf(
                RecentCall(
                    id = serverId,
                    peerUserId = call.peerUserId,
                    peerUsername = call.peerUsername,
                    modality = call.modality,
                    isOutgoing = call.isOutgoing,
                    status = status,
                    connected = call.startedAt != null,
                    duration = call.startedAt?.let { Duration.between(it, now) },
                    at = now,
                ),
            ) + endedHere.filter { it.id != serverId }
            // The server's row, if a reload already had it, gives way until the next reload.
            historyRows = historyRows.filter { it.id != serverId }
            publishRecent()
        }
        machine = null
        m.wipeKeys()
        if (text == null) {
            active = null
            return
        }
        editActive { it.copy(phase = CallPhase.Ending, endedText = text, reconnecting = false, notice = null) }
        dismissJob?.cancel()
        dismissJob = scope.launch {
            delay(visibleMs)
            if (generation == ended && active?.phase == CallPhase.Ending) active = null
        }
    }

    /**
     * Drops the call screen and the history (Log Out, removal, forced sign-out; CC:277-308). A call
     * this phone had joined is hung up; the system's call ends.
     */
    fun clearLocalState() {
        val m = machine
        if (m != null && !m.ended) {
            val phase = active?.phase
            val joined = m.role == CallCrypto.Role.Caller || m.accepting || phase == CallPhase.Connecting || phase == CallPhase.Active
            val id = m.serverId
            val token = token()
            if (joined && id != null && token != null) scope.launch { quietly { backend.hangup(token, id) } }
            if (id != null) system?.reportEnded(id, CallEndCause.Remote)
        }
        releaseSocket()
        if (m != null) {
            m.ended = true
            m.scope.cancel()
            teardownMedia(m)
            m.wipeKeys()
        }
        machine = null
        generation += 1
        dismissJob?.cancel()
        active = null
        errorState.value = null
        historyRows = emptyList()
        endedHere = emptyList()
        connectedHere.clear()
        historyCursor = null
        historyEpoch += 1
        historyState.value = CallHistoryState()
        finished.clear()
        system?.clear()
    }

    private fun holdSocket() {
        val token = token() ?: return
        socket.hold(token)
    }

    private fun releaseSocket() = socket.release()

    private fun closeKit(id: UUID, close: KitClose) {
        when (close) {
            KitClose.None -> Unit
            is KitClose.RequestEnd -> {
                ignoreKitEnd = true
                try {
                    system?.reportEnded(id, close.cause)
                } finally {
                    ignoreKitEnd = false
                }
            }
            is KitClose.Report -> system?.reportEnded(id, close.cause)
        }
    }

    /** Stops our screen, closes the engine, drops the tracks (CC:1676-1686). */
    private fun teardownMedia(m: Machine?) {
        if (m != null && m.capturing) {
            m.capturing = false
            system?.screenShareStopped()
        }
        engine?.close()
        uiState.update {
            it.copy(
                localVideoTrack = null,
                remoteVideoTrack = null,
                remoteVideoLive = false,
                localVideoLive = false,
                canSwitchCamera = false,
                remoteScreenTrack = null,
                remoteScreenLive = false,
            )
        }
    }

    // ---- Switching between voice and video (CC:1688-1741) ----

    /** Our camera went on or off: they are told, and the sound and the system follow (CC:1690-1700). */
    private fun setVideoEnabled(on: Boolean, m: Machine) {
        val call = active ?: return
        active = call.copy(isVideoEnabled = on, notice = null)
        sendMedia(m)
        // The phone is away from the ear now: the sound leaves the earpiece for the speaker.
        if (on) preferSpeaker(m)
        videoChanged(m)
    }

    /** A camera or a screen to look at: the sound leaves the earpiece (CC:1702-1708). Headsets stay. */
    private fun preferSpeaker(m: Machine) {
        val call = active ?: return
        if (call.speakerOn || system?.isOnEarpiece?.value != true) return
        m.speakerForVideo = true
        setSpeaker(true)
    }

    /**
     * A camera or a screen changed (CC:1710-1723): the system shows a video call while any picture
     * is on, and once no video is left the sound goes back to the earpiece, if video moved it.
     */
    private fun videoChanged(m: Machine) {
        val call = active ?: return
        if (call.phase == CallPhase.Ending) return
        val hasVideo = call.hasVideo
        val id = m.serverId
        if (id != null && m.reportedVideo != hasVideo) {
            m.reportedVideo = hasVideo
            system?.update(id, call.peerUsername, hasVideo)
        }
        if (!hasVideo && m.speakerForVideo && call.speakerOn) {
            m.speakerForVideo = false
            setSpeaker(false)
        }
    }

    private fun setSpeaker(on: Boolean) {
        val call = active ?: return
        active = call.copy(speakerOn = on)
        system?.setSpeaker(on)
    }

    /** The answer settled whether our video can go out; their `media_state`, whether they can show our screen (CC:1732-1741). */
    private fun refreshCanVideo() {
        val call = active ?: return
        val engine = engine ?: return
        val canShare = engine.canSendScreen && machine?.peerShowsScreens == true
        if (call.canVideo == engine.canSendVideo && call.canShareScreen == canShare) return
        active = call.copy(canVideo = engine.canSendVideo, canShareScreen = canShare)
    }

    // ---- Sharing the screen (CC:1743-1864; calls §4.17, §7) ----

    /**
     * What Share does (CC:1745-1776): stops a running share, says why it cannot, or asks the screen
     * to launch the MediaProjection consent ([ShareAction.RequestConsent]; every share asks again).
     */
    fun toggleScreenShare(): ShareAction {
        val m = machine ?: return ShareAction.None
        val call = active ?: return ShareAction.None
        if (!current(m)) return ShareAction.None
        if (call.phase != CallPhase.Active && call.phase != CallPhase.Connecting) {
            // Share shows, dimmed, while our call rings out: the tap says why it does nothing.
            if (call.phase == CallPhase.OutgoingRinging) note(shareUnavailableText(call, m), m)
            return ShareAction.None
        }
        if (call.isSharingScreen || m.capturing) {
            broadcastEnded(m)
            return ShareAction.None
        }
        if (!call.canShareScreen || !sharingReady(m)) {
            note(shareUnavailableText(call, m), m)
            return ShareAction.None
        }
        return ShareAction.RequestConsent
    }

    /**
     * The MediaProjection consent came back (the broadcast's `.connected`, CC:1801-1817). Null —
     * refused or cancelled — says nothing (web `screenErrorText` for a closed picker).
     */
    fun onScreenCaptureConsent(grant: ScreenCaptureGrant?) {
        if (grant == null) return
        val m = machine ?: return
        val engine = engine ?: return
        val call = active ?: return
        if (!current(m) || call.phase == CallPhase.Ending || m.capturing) return
        if (!call.canShareScreen) {
            note(shareUnavailableText(call, m), m)
            return
        }
        // The foreground service takes the mediaProjection type before the projection exists (calls §7.2).
        // Without that type the system stops the projection at once.
        val system = this.system
        if (system != null && !system.screenShareStarted()) {
            note(CallTexts.SHARE_FAILED, m)
            return
        }
        if (!engine.startScreen(grant)) {
            system?.screenShareStopped()
            note(CallTexts.SHARE_FAILED, m)
            return
        }
        m.capturing = true
        active = call.copy(screenShareStarting = true)
    }

    /**
     * The resolution and frame rate for our screen, kept for the next share too; while we share,
     * the encoder and the capture take it at once, with no new offer (CC:1778-1786).
     */
    fun setScreenShareQuality(q: ScreenShareQuality) {
        if (q == uiState.value.screenShareQuality || q.frameRate !in ScreenShareQuality.FRAME_RATES) return
        preferences.setScreenShareQuality(q)
        uiState.update { it.copy(screenShareQuality = q) }
        engine?.screenQuality = q
    }

    private fun screenFirstFrame() {
        val m = machine ?: return
        val call = active ?: return
        if (!current(m) || !m.capturing || call.isSharingScreen) return
        active = call.copy(isSharingScreen = true, screenShareStarting = false, notice = null)
        sendMedia(m)
        preferSpeaker(m)
        videoChanged(m)
    }

    private fun screenCaptureEnded() {
        val m = machine ?: return
        if (!current(m) || !m.capturing) return
        broadcastEnded(m)
        if (active?.phase != CallPhase.Ending) note(CallTexts.SCREEN_NO_LONGER_SHARED, m)
    }

    /** The share is over: nothing more goes out, and they are told (CC:1832-1845). */
    private fun broadcastEnded(m: Machine) {
        val wasCapturing = m.capturing
        m.capturing = false
        engine?.stopScreen()
        if (wasCapturing) system?.screenShareStopped()
        val call = active ?: return
        if (!call.isSharingScreen && !call.screenShareStarting) return
        val wasShared = call.isSharingScreen
        active = call.copy(isSharingScreen = false, screenShareStarting = false)
        if (!wasShared) return
        sendMedia(m)
        videoChanged(m)
    }

    /** iOS: a broadcast receiver listens from the answer on (CC:1257, 1790-1799). */
    private fun sharingReady(m: Machine) = m.negotiated && screenCaptureSupported()

    /** Why Share cannot be used in this call right now (CC:1847-1855). */
    private fun shareUnavailableText(call: ActiveCall, m: Machine): String {
        if (!sharingReady(m) && call.phase == CallPhase.Active && call.canShareScreen) {
            return CallTexts.shareUnavailableOnDevice(deviceNoun())
        }
        // Their app says whether it shows screens as the call connects.
        if (call.phase != CallPhase.Active) return CallTexts.SHARE_AFTER_CONNECT
        return CallTexts.SHARE_PEER_UPDATE
    }

    // ---- Helpers (CC:1880-2103) ----

    /** A passing line under the name, 6 s (CC:1887-1899). */
    private fun note(text: String, m: Machine) {
        editActive { it.copy(notice = text) }
        m.noticeTask?.cancel()
        m.noticeTask = m.scope.launch {
            delay(NOTICE_MS)
            if (!current(m)) return@launch
            if (active?.notice == text) editActive { it.copy(notice = null) }
        }
    }

    /** A timer that ends the call; ≥ 60 s is the ring limit, shorter the connect limit (CC:1901-1925). */
    private fun arm(m: Machine, afterMs: Long, text: String, notify: Notify?, status: String, close: KitClose) {
        val job = m.scope.launch {
            delay(afterMs)
            if (current(m)) finish(m, text, notify, status, close)
        }
        if (afterMs >= 60_000) {
            m.ringLimit?.cancel()
            m.ringLimit = job
        } else {
            m.connectTimer?.cancel()
            m.connectTimer = job
        }
    }

    private fun current(m: Machine): Boolean = machine === m && !m.ended

    private fun token(): String? = session.value?.token

    /** The stored secret first (a locked phone can answer), else derived now (CC:1940-1944). */
    private suspend fun callSecret(peer: UUID): ByteArray = peers.storedSecret(peer) ?: peers.deriveSecret(peer)

    private fun takeMicrophone() {
        mediaStarting.tryEmit(Unit)
    }

    private fun copyCamera(engine: CallMediaEngine) {
        uiState.update {
            it.copy(localVideoTrack = engine.localVideoTrack, canSwitchCamera = engine.canSwitchCamera, usesFrontCamera = engine.usesFrontCamera)
        }
    }

    /** Hands the call screen the shared EGL context. This is the first read that loads WebRTC. */
    private fun publishEngineContext(engine: CallMediaEngine) {
        uiState.update { it.copy(eglContext = engine.eglContext) }
    }

    /** The engine's first word on our camera (CC:1951-1966). */
    private fun publishLocalPreview(modality: CallModality, m: Machine) {
        val engine = engine ?: return
        copyCamera(engine)
        val call = active ?: return
        active = call.copy(isVideoEnabled = engine.isCameraOn, canVideo = engine.canSendVideo)
        // A video call whose camera is refused or missing goes on with sound; Video can try again.
        if (modality == CallModality.Video && !engine.isCameraOn) note(CallTexts.CAMERA_OFF_ON_START, m)
        // Video put the sound on the speaker; it goes back to the earpiece with the video.
        if (modality == CallModality.Video && call.speakerOn) m.speakerForVideo = true
        m.reportedVideo = modality == CallModality.Video
    }

    private fun makeCall(id: UUID, peerUserId: UUID, peerUsername: String, modality: CallModality, outgoing: Boolean, phase: CallPhase) =
        ActiveCall(
            id = id,
            peerUserId = peerUserId,
            peerUsername = peerUsername,
            modality = modality,
            isOutgoing = outgoing,
            phase = phase,
            isVideoEnabled = modality == CallModality.Video,
            // Until they say otherwise: a video call's other side sends video, a voice call's not.
            remoteCameraOff = modality != CallModality.Video,
            speakerOn = modality == CallModality.Video,
            safetyVerified = peers.isSafetyVerified(peerUserId),
        )

    /** A real name replaces the push's placeholder (CC:2017-2022). */
    private fun applyCallerName(name: String, callId: UUID, video: Boolean) {
        if (name.isEmpty() || name == CallTexts.INCOMING_CALL_PLACEHOLDER) return
        val call = active ?: return
        active = call.copy(peerUsername = name)
        system?.update(callId, name, video)
    }

    /** A name this phone already opened for a contact. */
    private fun localCallName(peer: UUID): String? = peers.contactUsername(peer)

    /** The call's caller name, else the roster's, else "Contact" (CC:2024-2030). */
    private fun callerName(call: CallDto): String {
        peers.contactUsername(call.callerUserId)?.takeIf { it.isNotEmpty() && it != "Contact" }?.let { return it }
        return "Contact"
    }

    /** The last 20 call ids that ended here (CC:2032-2037). */
    private fun rememberFinished(id: UUID) {
        if (id in finished) return
        finished.addLast(id)
        if (finished.size > FINISHED_MEMORY) finished.removeFirst()
    }

    /** How the system files a call the server ended (`kitReason`, CC:2092-2097). */
    private fun kitReason(status: String, reason: String?): CallEndCause = when {
        status == "missed" && reason != "declined" -> CallEndCause.Missed
        status == "rejected" || reason == "declined" -> CallEndCause.Rejected
        status == "cancelled" -> CallEndCause.Missed
        else -> CallEndCause.Remote
    }

    /** Runs [block] in [scope] and waits for it: a cancelled caller never stops a call half done. */
    private suspend fun <T> detached(block: suspend () -> T): T = scope.async { block() }.await()

    private suspend fun quietly(block: suspend () -> Unit) {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // Fire and forget, as iOS's `try?` (CC:1570-1576).
        }
    }

    /** Engine → controller, on the main thread (calls §5; `CallController.swift:247-274`). */
    private inner class EngineCallbacks : CallMediaCallbacks {
        override fun onLocalCandidate(candidate: IceCandidatePayload) = onMain { gathered(candidate) }

        override fun onConnection(state: String) = onMain {
            val m = machine ?: return@onMain
            if (current(m)) linkChanged(state, m)
        }

        override fun onRemoteVideo(track: VideoTrack?) = onMain { uiState.update { it.copy(remoteVideoTrack = track) } }

        override fun onRemoteFrame() = onMain { uiState.update { it.copy(remoteVideoLive = true) } }

        override fun onLocalFrame() = onMain { uiState.update { it.copy(localVideoLive = true) } }

        override fun onCameraPaused(paused: Boolean) = onMain {
            val m = machine ?: return@onMain
            if (!current(m)) return@onMain
            m.cameraPaused = paused
            // They see our face rather than the last frame, frozen, while the camera is away.
            if (active?.isVideoEnabled == true) sendMedia(m)
        }

        override fun onRemoteScreen(track: VideoTrack?) = onMain { uiState.update { it.copy(remoteScreenTrack = track) } }

        override fun onRemoteScreenFrame() = onMain { uiState.update { it.copy(remoteScreenLive = true) } }

        override fun onScreenFirstFrame() = onMain { screenFirstFrame() }

        override fun onScreenCaptureEnded() = onMain { screenCaptureEnded() }

        private fun onMain(block: () -> Unit) {
            scope.launch { block() }
        }
    }

    private enum class Notify { Hangup, Reject }

    /** How the system's call closes (`KitClose`, CC:231-235). */
    private sealed interface KitClose {
        data object None : KitClose

        /** We end it: Telecom hears a local end (a declined ring: rejected). */
        data class RequestEnd(val cause: CallEndCause) : KitClose

        data class Report(val cause: CallEndCause) : KitClose
    }

    /** A sealed signal as it arrived: the sending device (lower case, or ""), its type, its payload. */
    private data class Inbound(val from: String, val type: String, val payload: String) {
        override fun toString(): String = "Inbound(type=$type)"
    }

    /** One live call's signalling, replaced wholesale when the call ends (CC:157-224; calls §4.1). */
    private class Machine(val generation: Int, val role: CallCrypto.Role, parent: CoroutineScope) {
        /** Timers, the inbox and the outbox; cancelled by `finish`. */
        val scope = CoroutineScope(parent.coroutineContext + SupervisorJob(parent.coroutineContext[Job]))
        var serverId: UUID? = null
        var peerDeviceId: UUID? = null
        var keys: CallSignalKeys? = null

        /** The identity call secret, kept only until the per-call key is derived. */
        var identitySecret: ByteArray? = null
        var ephPrivate: ByteArray? = null
        var ephPublic: ByteArray? = null

        /** Post-setup signals; the identity keys when the peer sent no ephemeral key. */
        var forward: CallSignalKeys? = null
        var gotSetup = false
        var sealedAnswer = false
        var expectedFingerprint: String? = null
        var fingerprintChecked = false
        val pendingSend = ArrayList<CallSignal>()
        val pendingSecure = ArrayList<Inbound>()
        var negotiated = false
        var sent = 0
        val seen = HashMap<String, MutableSet<Int>>()
        val gathered = ArrayList<IceCandidatePayload>()
        val pendingRemote = ArrayList<IceCandidatePayload>()
        val earlySignals = ArrayList<Inbound>()
        val earlyEvents = ArrayList<RealtimeEvent>()
        var offerTask: Deferred<String>? = null

        /** Serial queues: each job runs after the previous one (CC:972-977, 1284-1289). */
        val inbox = serialQueue()
        val outbox = serialQueue()
        var heartbeat: Job? = null
        var ringLimit: Job? = null
        var ringCheck: Job? = null
        var connectTimer: Job? = null
        var graceTimer: Job? = null
        var reconnectTimer: Job? = null
        var restartTimer: Job? = null
        var batchTimer: Job? = null
        var noticeTask: Job? = null
        var lastRestart = NEVER
        var accepting = false
        var dialing = false
        var ended = false
        var linkBroken = false

        /** The link failed: a restart delayed by the 10 s gate still uses the relay. */
        var wantRelay = false
        var reportedConnected = false
        val mediaOrder = CallMediaOrder()

        /** The system paused our camera: they are told it is off until it runs again. */
        var cameraPaused = false

        /** Android: the camera stopped because no screen of ours is visible (calls D3). */
        var pausedByBackground = false

        /** Waiting for camera access after Video was pressed. */
        var cameraBusy = false

        /** Video moved the sound to the speaker; it goes back to the earpiece with the video. */
        var speakerForVideo = false

        /** Their app can show a screen: its `media_state` carries `screen`. */
        var peerShowsScreens = false

        /** The view their camera is cut to, from their `media_state` (null: their whole picture). */
        var peerView: CallView? = null

        /** The view our last `media_state` carried, and when it went ([AppClock.elapsedMillis]). */
        var sentView: CallView? = null
        var lastMediaAt: Long? = null

        /** A new view waiting for [VIEW_GAP_MS] since the last `media_state`. */
        var viewTask: Job? = null

        /** Our screen capture runs and is on its section (iOS `broadcasting`); its first frame may not be out yet. */
        var capturing = false

        /** What the system was last told: a video call or not. */
        var reportedVideo: Boolean? = null

        private fun serialQueue(): Channel<suspend () -> Unit> {
            val queue = Channel<suspend () -> Unit>(Channel.UNLIMITED)
            scope.launch {
                for (job in queue) {
                    try {
                        job()
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        // One signal's failure never stops the queue.
                    }
                }
            }
            return queue
        }

        fun wipeKeys() {
            keys?.wipe()
            forward?.wipe()
            identitySecret?.fill(0)
            ephPrivate?.fill(0)
            keys = null
            forward = null
            identitySecret = null
            ephPrivate = null
        }
    }

    companion object {
        /** Outgoing ring limit, "No answer" (CC:614). */
        const val RING_OUT_MS = 75_000L

        /** Incoming ring limit, "Missed call", no server call (CC:757). */
        const val RING_IN_MS = 70_000L

        /** `GET /calls/{id}` while ringing (CC:759-765). */
        const val RING_CHECK_MS = 10_000L

        /** From the answer to media, both sides: "Couldn't connect" (CC:893, 988). */
        const val CONNECT_MS = 30_000L

        /** A disconnected link waits this long before an ICE restart (CC:1381-1385). */
        const val GRACE_MS = 4_000L

        /** A broken link while active ends the call after this (CC:1412-1415). */
        const val RECONNECT_LIMIT_MS = 30_000L

        /** Restarts at most this often (CC:1423); a caller that cannot offer yet re-checks after [RESTART_RECHECK_MS]. */
        const val RESTART_GAP_MS = 10_000L
        const val RESTART_RECHECK_MS = 1_000L

        const val HEARTBEAT_MS = 10_000L
        const val ICE_BATCH_MS = 100L
        const val ICE_BATCH_MAX = 20
        const val DELIVER_ATTEMPTS = 3
        const val FINGERPRINT_RETRY_MS = 400L
        const val NOTICE_MS = 6_000L

        /** A new shape of our view goes out at most this often (docs/calls.md, "Framing and Center Stage"). */
        const val VIEW_GAP_MS = 500L

        const val ENDING_VISIBLE_MS = 2_000L
        const val ERROR_VISIBLE_MS = 4_000L
        const val FINISHED_MEMORY = 20
        private const val MAX_PUSH_NAME = 64

        private const val NEVER = Long.MIN_VALUE

        private val BEFORE_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC)

        /** The server handed out a TURN relay (`CallMediaEngine.offersRelay`, ME:159-166). */
        fun offersRelay(servers: List<IceServerDto>): Boolean = servers.any { server ->
            server.urls.any { url -> url.lowercase().let { it.startsWith("turn:") || it.startsWith("turns:") } }
        }

        /**
         * Whether this call must stay on the relay ("Always relay calls"); throws
         * [CallRelayUnavailableException] when it must but the server offers none, since the only
         * other way to connect would show this phone's address (CC:2051-2061).
         */
        fun relayPolicy(servers: List<IceServerDto>, alwaysRelay: Boolean): Boolean {
            if (!alwaysRelay) return false
            if (!offersRelay(servers)) throw CallRelayUnavailableException()
            return true
        }

        /** The older-page cursor: 1 ms after the oldest call, in milliseconds (CC:370-376). */
        fun formatBefore(cursor: Instant): String = BEFORE_FORMAT.format(cursor.plusMillis(1).truncatedTo(ChronoUnit.MILLIS))
    }
}

/** A `view` as the framing geometry reads it. */
private fun CallView.size(): FrameSize = FrameSize(w, h)
