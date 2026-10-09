package de.corespace.shroud.core.realtime

import de.corespace.shroud.core.model.Ids
import de.corespace.shroud.core.net.ApiError
import de.corespace.shroud.core.net.AuthOutcomeListener
import de.corespace.shroud.core.net.ErrorCodes
import de.corespace.shroud.core.net.ServerConfiguration
import de.corespace.shroud.core.net.ShroudClientHeader
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import java.io.IOException
import java.util.EnumSet
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * The device's one socket to `/api/v1/ws` — iOS `RealtimeClient` (`Services/Realtime/RealtimeClient.swift`),
 * api-realtime §11.11, plan §1.7.3. Shared by messaging, calls and the opt-in background
 * connection: open while any [Holder] holds it (`RealtimeClient.swift:4-13`). The server keeps one
 * socket per device and a newer one evicts the older without a frame (`realtime/mod.rs:184-221`),
 * so there is exactly one instance per process, built by `RealtimeModule` (api-realtime §17.2).
 *
 * **Threading.** Every public member and all state belong to the main thread ([scope] is
 * `AppContainer.appScope`, `Dispatchers.Main.immediate`). OkHttp's callbacks never touch state:
 * they parse on the reader thread and queue a signal into one channel, which one coroutine on
 * [scope] drains in order. A signal of a socket that is no longer current (its generation was
 * bumped by a newer open or a deliberate close) is dropped, so a replaced socket's late frames,
 * closes and failures change nothing (`RealtimeClient.swift:283`, web `realtime.ts:135-142`).
 *
 * **Events** reach [events] in arrival order and are never dropped by the client: the pump
 * suspends while a subscriber's buffer is full. With no subscriber they go nowhere, as iOS drops
 * them while messaging is inactive (MessagingController.swift:434-442). The server can drop or
 * duplicate events (api-realtime §11.3), so consumers stay idempotent and keep their catch-up.
 *
 * **`auth.error`** (plan C31, api-realtime §11.8): `DEVICE_REMOVED` → stop and report
 * [AuthOutcomeListener.onDeviceRemoved] with this socket's token, once (the session wipes only if
 * the token is still current); `RATE_LIMITED` → reconnect with the attempt counter raised to
 * [RealtimeTiming.rateLimitedAttemptFloor] (≥ 30 s, the web retries, iOS stops); anything else
 * (`UNAUTHORIZED`) → stop without signing out — the REST 401 streak decides
 * (`RealtimeClient.swift:307-317`). A later [hold] with the same token tries again.
 *
 * **Focus** (`RealtimeClient.swift:47-52, 77-101`; api-realtime §11.9): the server counts a new
 * socket as in front — its device gets no pushes — until told otherwise, so the first frame after
 * every `auth.ok` is the focus (`:304`). The focus the client claims is the wish of [noteFocus],
 * but never `true` while the app is not in front or while only [Holder.Background] holds the
 * socket: a socket opened for a call or the background connection while the app is away must not
 * swallow the device's pushes. The wish starts from [isForeground] (decision §17-5) — at
 * construction and again whenever a hold arrives while nobody held the socket, so every fresh
 * start begins from the app's real phase (plan §1.7.3: "the first focus frame of a socket comes
 * from `isForeground()`"). iOS keeps the old wish there: leaving the app notes `false`, the chats
 * auto-lock and close the socket, and unlocking in front re-opens it (`MessagingController.start`
 * holds without noting focus, `MessagingController.swift:464-487`) still saying "away", so the
 * server pushes to a phone that is in use.
 *
 * **Background connection** (plan §1.4, §1.7.3; X1-SRV-UP): a socket opened while
 * [Holder.Background] holds it and nobody claims focus authenticates with `"background": true`,
 * so the server neither shows the user online nor treats the device as in front. When a
 * messaging or call hold arrives on the same socket and the app is in front, the client sends
 * `focus:true` at once; from then on the server counts it like any socket, until the next
 * `focus:false`. A socket that was opened in front and is kept for the background connection
 * when the app leaves declares itself one on the way out: while [Holder.Background] holds the
 * socket every `focus:false` frame carries `"background": true` (plan §1.4 "declares itself a
 * background socket"; X1-SRV-UP `RealtimeHub::update_focus`). When the background connection
 * lets go of a socket messaging or a call keeps, the next focus frame says `"background": false`,
 * so the socket counts like any other again. Servers without background sockets ignore the extra
 * key (`ws.rs` `ClientMessage`). `DEVICE_REMOVED` on such a socket
 * takes the same wipe path — that is how a removal reaches a phone without a push distributor.
 *
 * **Too old**: the upgrade request carries [ShroudClientHeader]. A server that no longer serves
 * this build refuses the upgrade with `426 UPDATE_REQUIRED`: [onUpdateRequired] runs (the version
 * check, which shows the blocking update screen) and the socket retries on the usual backoff, so
 * an update or a lowered minimum is picked up without a tight loop. No sign-out.
 *
 * Never logs: tokens, frames and events stay out of every log.
 *
 * @param baseUrl the REST base (`…/api/v1`), read at every open so a server change applies to the
 *   next socket (iOS reads `ServerConfigurationStore` in `openSocket`, `:169-170`).
 * @param json the app's API `Json` (`ignoreUnknownKeys`), for the DTOs inside events.
 * @param baseHttp the shared `OkHttpClient`; the socket's client derives from it (same pool) and
 *   adds the 25 s ping. OkHttp clears the read timeout after the upgrade.
 * @param authOutcomes where `DEVICE_REMOVED` is reported (the session's `SessionAuthBridge`).
 * @param isForeground whether one of our activities is started (`AppPhaseMonitor.isStarted`).
 * @param onUpdateRequired called on the main thread when the upgrade was refused with
 *   `426 UPDATE_REQUIRED`.
 * @param clientHeader the `X-Shroud-Client` value of the upgrade request.
 */
class RealtimeClient(
    private val baseUrl: () -> String,
    private val json: Json,
    baseHttp: OkHttpClient,
    private val scope: CoroutineScope,
    private val authOutcomes: () -> AuthOutcomeListener?,
    private val isForeground: () -> Boolean,
    private val timing: RealtimeTiming = RealtimeTiming(),
    private val onUpdateRequired: () -> Unit = {},
    private val clientHeader: String = ShroudClientHeader.value,
) {
    /** Who needs the socket open (`RealtimeClient.swift:14-18`, plus the background connection). */
    enum class Holder {
        /** The chats while unlocked (MessagingController.swift:476, 598-626). */
        Messaging,

        /** A call, for its whole length — also one answered on a locked phone (CallController.swift:1640-1646). */
        Call,

        /**
         * The opt-in background connection (W3-PUSH, decision record 2026-10-01). A socket held
         * only by it authenticates as a background socket and never claims focus.
         */
        Background,
    }

    /** `RealtimeClient.swift:20-25`; [Connected] carries the ids of `auth.ok`. */
    sealed interface ConnectionState {
        data object Disconnected : ConnectionState

        data object Connecting : ConnectionState

        /** `auth.ok` seen on the current socket. */
        data class Connected(val userId: UUID, val deviceId: UUID) : ConnectionState

        /** The socket dropped or was refused; [reason] is for diagnostics, never shown verbatim. */
        data class Failed(val reason: String) : ConnectionState
    }

    private val wsHttp: OkHttpClient = baseHttp.newBuilder()
        .pingInterval(timing.clientPing.inWholeMilliseconds, TimeUnit.MILLISECONDS)
        .build()

    private val mutableState = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)

    /** Where the socket is. Updated on the main thread only. */
    val state: StateFlow<ConnectionState> = mutableState.asStateFlow()

    /** `auth.ok` seen on the current socket: typing and the like are sent (`RealtimeClient.swift:28-32`). */
    val isConnected: Boolean get() = state.value is ConnectionState.Connected

    private val mutableEvents = MutableSharedFlow<RealtimeEvent>(extraBufferCapacity = EVENT_BUFFER)

    /** Every event, in order, never dropped by the client; no replay (api-realtime §11.11). */
    val events: SharedFlow<RealtimeEvent> = mutableEvents.asSharedFlow()

    /** OkHttp → main: every callback of every socket, in the order OkHttp delivered them. */
    private val inbound = Channel<Signal>(Channel.UNLIMITED)

    // Main-thread state (api-realtime §11.11 "Internal state").
    private var socket: WebSocket? = null
    private var generation = 0L
    private var token: String? = null
    private val holders: EnumSet<Holder> = EnumSet.noneOf(Holder::class.java)
    private var attempt = 0
    private var intentional = false
    private var wantsFocus = isForeground()
    private var sentFocus: Boolean? = null

    /**
     * The server counts the current socket as a background socket: its auth frame or a
     * `focus:false` frame said `"background": true`. The server keeps that until a frame says
     * `"background": false` or the socket closes (a `focus` frame without the field leaves it
     * alone, `realtime/mod.rs` `update_focus`); [deliverFocusNow] clears it once the background
     * connection lets go of a socket others keep.
     */
    private var sentBackground = false
    private var reconnectJob: Job? = null
    private var everConnected = false

    init {
        scope.launch {
            for (signal in inbound) handle(signal)
        }
    }

    /**
     * Opens the socket (if needed) for [holder] (`RealtimeClient.swift:59-63`). On a socket that
     * is already up, a holder that changes what the server should hear — a messaging or call hold
     * on a socket the background connection opened, while the app is in front — is told at once
     * (a no-op when nothing changed).
     */
    fun hold(holder: Holder, token: String) {
        // A fresh start (nobody held the socket) begins from the app's real phase, not a wish
        // left over from the last time the app was left (class KDoc, *Focus*).
        if (holders.isEmpty()) wantsFocus = isForeground()
        holders += holder
        connect(token)
        deliverFocusNow()
    }

    /**
     * [holder] is done with the socket; it closes once nobody holds it (`RealtimeClient.swift:65-71`).
     * While others still hold it, the server hears at once if that changed the focus — messaging
     * letting go of a socket the background connection keeps.
     */
    fun release(holder: Holder) {
        holders -= holder
        if (holders.isEmpty()) {
            everConnected = false
            disconnect(reconnect = false)
        } else {
            deliverFocusNow()
        }
    }

    /** `RealtimeClient.swift:73-75`. */
    fun isHeld(holder: Holder): Boolean = holder in holders

    /**
     * The app came to the front or left it (`RealtimeClient.swift:77-80`): only stores the wish;
     * [deliverFocus] tells the server, and every `auth.ok` does again.
     */
    fun noteFocus(focused: Boolean) {
        wantsFocus = focused
    }

    /**
     * Tells the server the focus, if the socket is up (`RealtimeClient.swift:82-101`). False when
     * not connected; true without a frame when the server already has this value; otherwise
     * whether the frame was queued. OkHttp writes queued frames before a later close frame, so a
     * focus frame is never overtaken by the close that follows it (api-realtime §11.9).
     */
    suspend fun deliverFocus(): Boolean = deliverFocusNow()

    /** `{"type":"typing","peer_user_id":…,"is_typing":…}`, only while connected (`RealtimeClient.swift:142-161`). */
    fun sendTyping(peerUserId: UUID, isTyping: Boolean) = sendPeerFlag("typing", peerUserId, "is_typing", isTyping)

    /** `{"type":"recording","peer_user_id":…,"is_recording":…}`, only while connected (`RealtimeClient.swift:146-148`). */
    fun sendRecording(peerUserId: UUID, isRecording: Boolean) =
        sendPeerFlag("recording", peerUserId, "is_recording", isRecording)

    /**
     * Connectivity came back (Android addition, api-realtime §11.7, decision §17-4): a held socket
     * that is down reconnects now with the backoff reset, instead of waiting out up to 30 s. Not
     * after `RATE_LIMITED`: a new network does not lower the account's socket count, and that
     * wait keeps its 30 s floor (plan C31).
     */
    fun onNetworkAvailable() {
        if (holders.isEmpty() || token == null || intentional) return
        val current = state.value
        if (current !is ConnectionState.Failed && current !is ConnectionState.Disconnected) return
        if (current == ConnectionState.Failed(RATE_LIMITED_REASON)) return
        reconnectJob?.cancel()
        reconnectJob = null
        attempt = 0
        openSocket()
    }

    /** Sign-out / wipe: forget holders and token, close without reconnecting (api-realtime §11.11). */
    fun shutdown() {
        holders.clear()
        everConnected = false
        disconnect(reconnect = false)
        token = null
    }

    // ---- Private (one function per iOS function, api-realtime §11.11 table) ----

    /** `RealtimeClient.swift:109-118`. */
    private fun connect(token: String) {
        intentional = false
        val current = state.value
        if (token == this.token && (current is ConnectionState.Connected || current == ConnectionState.Connecting)) return
        if (token != this.token) everConnected = false
        disconnect(reconnect = false)
        // `disconnect(reconnect = false)` marks the close as wanted; this socket's drops are not.
        intentional = false
        this.token = token
        openSocket()
    }

    /** `RealtimeClient.swift:120-140`; `reconnect = false` also stops the reconnect loop. */
    private fun disconnect(reconnect: Boolean) {
        if (!reconnect) {
            intentional = true
            reconnectJob?.cancel()
            reconnectJob = null
            attempt = 0
        }
        dropSocket(CLOSE_GOING_AWAY)
        mutableState.value = ConnectionState.Disconnected
    }

    /** Forgets the current socket; its later callbacks are stale. */
    private fun dropSocket(code: Int) {
        generation++
        socket?.close(code, null)
        socket = null
        sentFocus = null
        sentBackground = false
    }

    /**
     * `RealtimeClient.swift:165-205`. The auth frame goes out when OkHttp reports the socket open
     * ([sendAuth]), as the first text frame (the server waits 10 s for it, `ws.rs:61-177`).
     */
    private fun openSocket() {
        if (token == null) return
        when (val target = target(baseUrl())) {
            Target.Invalid -> {
                mutableState.value = ConnectionState.Failed(INVALID_URL)
                scheduleReconnect()
            }
            // Plain ws:// only to local hosts, as REST (api-realtime §11.1). Retrying cannot help.
            Target.PlainHttpRefused -> {
                dropSocket(CLOSE_GOING_AWAY)
                mutableState.value = ConnectionState.Failed(ServerConfiguration.PLAIN_HTTP_REFUSED)
            }
            is Target.Url -> {
                dropSocket(CLOSE_GOING_AWAY)
                mutableState.value = ConnectionState.Connecting
                val request = Request.Builder().url(target.url).header(ShroudClientHeader.NAME, clientHeader).build()
                socket = wsHttp.newWebSocket(request, Listener(generation))
            }
        }
    }

    /**
     * `RealtimeClient.swift:260-272`: nothing after a deliberate close or without a token; the wait
     * uses the attempt counter before the increment (1, 2, 4, 8, 16, 30, 30 … s), no jitter.
     */
    private fun scheduleReconnect() {
        if (intentional || token == null) return
        reconnectJob?.cancel()
        val wait = timing.backoff(attempt)
        attempt = minOf(attempt + 1, timing.maxAttempt)
        reconnectJob = scope.launch {
            delay(wait)
            if (reconnectJob === currentCoroutineContext()[Job]) reconnectJob = null
            if (!intentional) openSocket()
        }
    }

    private suspend fun handle(signal: Signal) {
        if (signal.generation != generation || signal.webSocket !== socket) return
        when (signal) {
            is Signal.Opened -> sendAuth(signal.webSocket)
            is Signal.Frame -> onFrame(signal.frame)
            is Signal.Ended -> {
                if (signal.updateRequired) onUpdateRequired()
                onEnded(signal.reason)
            }
        }
    }

    /**
     * `{"type":"auth","token":…}` (`RealtimeClient.swift:183-192`), plus `"background": true` for
     * a socket held for the background connection with nobody claiming focus (X1-SRV-UP). A send
     * that fails here is followed by OkHttp's failure callback, which reconnects.
     */
    private fun sendAuth(webSocket: WebSocket) {
        val token = token ?: return
        val background = declaresBackground(desiredFocus())
        val frame = buildJsonObject {
            put("type", "auth")
            put("token", token)
            if (background) put("background", true)
        }
        if (webSocket.send(frame.toString()) && background) sentBackground = true
    }

    /** `RealtimeClient.swift:292-336`. */
    private suspend fun onFrame(frame: RealtimeFrame) {
        when (frame) {
            is RealtimeFrame.AuthOk -> {
                // `:299-306`: connected, backoff reset, focus first, then tell the consumers.
                attempt = 0
                sentFocus = null
                mutableState.value = ConnectionState.Connected(frame.userId, frame.deviceId)
                deliverFocusNow()
                val isReconnect = everConnected
                everConnected = true
                mutableEvents.emit(RealtimeEvent.Connected(frame.userId, frame.deviceId, isReconnect))
            }
            is RealtimeFrame.AuthError -> onAuthError(frame.code, frame.reason)
            is RealtimeFrame.Event -> mutableEvents.emit(frame.event)
        }
    }

    /** Plan C31; api-realtime §11.8 (iOS `RealtimeClient.swift:307-317`, web `realtime.ts:125-132`). */
    private fun onAuthError(code: String?, reason: String?) {
        if (code == ErrorCodes.RATE_LIMITED) {
            // Too many sockets on this account: the session is fine. Retry no sooner than ~30 s.
            attempt = maxOf(attempt, timing.rateLimitedAttemptFloor)
            dropSocket(CLOSE_NORMAL)
            mutableState.value = ConnectionState.Failed(RATE_LIMITED_REASON)
            scheduleReconnect()
            return
        }
        val token = token
        intentional = true
        reconnectJob?.cancel()
        reconnectJob = null
        attempt = 0
        dropSocket(CLOSE_NORMAL)
        mutableState.value = ConnectionState.Failed(AUTH_FAILED_REASON)
        // The account removed this device while the socket was open: wipe now (the session checks
        // that the token is still its own). Nothing else signs out from here.
        if (code == ErrorCodes.DEVICE_REMOVED && token != null) {
            if (reason == ApiError.ACCOUNT_DELETED_REASON) authOutcomes()?.onAccountDeleted(token)
            else authOutcomes()?.onDeviceRemoved(token)
        }
    }

    /** The current socket closed or failed (`RealtimeClient.swift:246-257`). */
    private fun onEnded(reason: String) {
        socket = null
        sentFocus = null
        sentBackground = false
        mutableState.value = if (intentional) ConnectionState.Disconnected else ConnectionState.Failed(reason)
        if (!intentional && token != null && holders.isNotEmpty()) scheduleReconnect()
    }

    /**
     * The focus the server should hold for this device: the wish, but never "in front" while the
     * app is not, or while only the background connection holds the socket.
     */
    private fun desiredFocus(): Boolean =
        wantsFocus && holders.any { it != Holder.Background } && isForeground()

    /**
     * Whether a frame saying [focused] also declares a background socket: away while the
     * background connection holds the socket (plan §1.4). A `focus:true` alone puts it in front;
     * the server keeps the flag until told otherwise ([clearsBackground]).
     */
    private fun declaresBackground(focused: Boolean): Boolean = !focused && Holder.Background in holders

    /**
     * The background connection let go of a socket the server still counts as background, while
     * messaging or a call keeps it: the next focus frame says `"background": false`, so the socket
     * counts like any other again (away but online, as on iOS and the web) instead of making the
     * user look offline (X1-SRV-UP `update_focus`).
     */
    private fun clearsBackground(): Boolean = sentBackground && Holder.Background !in holders

    /**
     * `RealtimeClient.swift:82-101` on the desired focus: nothing when the server already has it
     * (and, away with the background connection on, already counts the socket as background; or,
     * with it off, no longer does).
     */
    private fun deliverFocusNow(): Boolean {
        val webSocket = socket
        if (state.value !is ConnectionState.Connected || webSocket == null) return false
        val focused = desiredFocus()
        val background = declaresBackground(focused)
        val clear = clearsBackground()
        if (sentFocus == focused && (!background || sentBackground) && !clear) return true
        val frame = buildJsonObject {
            put("type", "focus")
            put("focused", focused)
            if (background) put("background", true) else if (clear) put("background", false)
        }
        val sent = webSocket.send(frame.toString())
        if (sent && desiredFocus() == focused) sentFocus = focused
        if (sent && background) sentBackground = true
        if (sent && clear) sentBackground = false
        return sent
    }

    private fun sendPeerFlag(type: String, peerUserId: UUID, flag: String, value: Boolean) {
        val webSocket = socket
        if (state.value !is ConnectionState.Connected || webSocket == null) return
        val frame = buildJsonObject {
            put("type", type)
            put("peer_user_id", Ids.wire(peerUserId))
            put(flag, value)
        }
        webSocket.send(frame.toString())
    }

    /** What an OkHttp callback reports, tagged with the socket it belongs to. */
    private sealed interface Signal {
        val generation: Long
        val webSocket: WebSocket

        class Opened(override val generation: Long, override val webSocket: WebSocket) : Signal

        class Frame(override val generation: Long, override val webSocket: WebSocket, val frame: RealtimeFrame) : Signal

        /** [updateRequired]: the upgrade was refused with `426 UPDATE_REQUIRED`. */
        class Ended(
            override val generation: Long,
            override val webSocket: WebSocket,
            val reason: String,
            val updateRequired: Boolean = false,
        ) : Signal
    }

    /** Runs on OkHttp's threads: parses, queues, and never reads or writes client state. */
    private inner class Listener(private val generation: Long) : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            inbound.trySend(Signal.Opened(generation, webSocket))
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            val frame = RealtimeEventParser.parse(text, json) ?: return
            inbound.trySend(Signal.Frame(generation, webSocket, frame))
        }

        /** Binary frames are not part of the protocol (`RealtimeClient.swift:241-242`). */
        override fun onMessage(webSocket: WebSocket, bytes: ByteString) = Unit

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            webSocket.close(CLOSE_NORMAL, null)
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            inbound.trySend(Signal.Ended(generation, webSocket, CLOSED_REASON))
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            val updateRequired = response != null && isUpdateRequired(response)
            inbound.trySend(Signal.Ended(generation, webSocket, t.message ?: CLOSED_REASON, updateRequired))
        }

        /** A refused upgrade's `426 UPDATE_REQUIRED` envelope; OkHttp closes the response after this callback. */
        private fun isUpdateRequired(response: Response): Boolean {
            if (response.code != 426) return false
            val body = try {
                response.peekBody(MAX_ERROR_BODY).string()
            } catch (_: IOException) {
                return false
            } catch (_: IllegalStateException) {
                // The body was already consumed or closed.
                return false
            }
            return ApiError.from(response.code, body).isUpdateRequired
        }
    }

    /** Where a REST base sends the socket. */
    internal sealed interface Target {
        data class Url(val url: String) : Target

        /** Not a URL the client can open (`RealtimeClient.swift:171-175`): retried with backoff. */
        data object Invalid : Target

        /** Plain `ws://` to a public host: refused, not retried (api-realtime §11.1). */
        data object PlainHttpRefused : Target
    }

    companion object {
        /** `RealtimeClient.swift:171-175`. */
        const val INVALID_URL = "Invalid WebSocket URL"

        /** `RealtimeClient.swift:308`. */
        const val AUTH_FAILED_REASON = "WebSocket authentication failed"

        const val RATE_LIMITED_REASON = "Too many WebSocket connections for this account"

        const val CLOSED_REASON = "Connection closed"

        private const val CLOSE_NORMAL = 1000

        /** iOS `cancel(with: .goingAway)` (`RealtimeClient.swift:132`). */
        private const val CLOSE_GOING_AWAY = 1001

        private const val EVENT_BUFFER = 64

        /** Enough for an error envelope; a refused upgrade's body is never longer. */
        private const val MAX_ERROR_BODY = 4096L

        private val SCHEME = Regex("^([A-Za-z][A-Za-z0-9+.-]*):")

        /**
         * `ws(s)://…/ws` from the REST base (`RealtimeClient.swift:207-231`): `https` → `wss`,
         * `http` → `ws`, `ws`/`wss` kept, any other scheme → `ws`; trailing slashes stripped and
         * `/ws` appended unless the path already ends with it; query and fragment dropped. Null
         * when [apiBase] is not a URL. This is the mapping only; which hosts plain `ws://` may
         * reach is [target]'s rule.
         */
        fun webSocketUrl(apiBase: String): String? = socketHttpUrl(apiBase)?.let(::wsText)

        /**
         * [webSocketUrl] plus the cleartext rule REST keeps (`ApiClient.url`): plain `ws://` only
         * to a host `ServerConfiguration.isLocalNetworkHost` accepts — loopback, private and
         * link-local addresses, `.local`, unqualified names (the emulator's `10.0.2.2` included).
         */
        internal fun target(apiBase: String): Target {
            val url = socketHttpUrl(apiBase) ?: return Target.Invalid
            if (!url.isHttps && !ServerConfiguration.isLocalNetworkHost(url.host)) return Target.PlainHttpRefused
            return Target.Url(wsText(url))
        }

        /** The socket URL in OkHttp's `http(s)` form (OkHttp's `HttpUrl` has no `ws` schemes). */
        private fun socketHttpUrl(apiBase: String): HttpUrl? {
            val text = apiBase.trim()
            val scheme = SCHEME.find(text)?.groupValues?.get(1)?.lowercase() ?: return null
            val secure = scheme == "https" || scheme == "wss"
            val rest = text.substring(scheme.length)
            val parsed = ((if (secure) "https" else "http") + rest).toHttpUrlOrNull() ?: return null
            var path = parsed.encodedPath
            while (path.endsWith("/")) path = path.dropLast(1)
            if (!path.endsWith("/ws")) path += "/ws"
            return parsed.newBuilder().encodedPath(path).query(null).fragment(null).build()
        }

        private fun wsText(url: HttpUrl): String {
            val text = url.toString()
            return if (url.isHttps) "wss" + text.removePrefix("https") else "ws" + text.removePrefix("http")
        }
    }
}
