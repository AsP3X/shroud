package de.corespace.shroud.core.calls

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import de.corespace.shroud.core.contacts.Contacts
import de.corespace.shroud.core.contacts.PeerIdentities
import de.corespace.shroud.core.net.CallDto
import de.corespace.shroud.core.net.CallModality
import de.corespace.shroud.core.net.IceServerDto
import de.corespace.shroud.core.net.ShroudApi
import de.corespace.shroud.core.realtime.RealtimeClient
import de.corespace.shroud.core.realtime.RealtimeEvent
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import java.util.UUID

// What CallController needs from the rest of the app, as narrow interfaces so the scripted
// two-sided tests can stand in for the server, the socket and contacts. Production
// implementations below forward to the one ShroudApi (plan C3), the one RealtimeClient (C4) and
// W2-CONTACTS' controllers (C6, C29).

/** The calls REST surface (iOS `CallsService`, `ios/shroud/Services/Calls/CallsService.swift:6-73`; calls §2.1). */
interface CallsBackend {
    suspend fun iceServers(token: String): List<IceServerDto>
    suspend fun createCall(token: String, peerUserId: UUID, modality: CallModality): CallDto
    suspend fun call(token: String, callId: UUID): CallDto

    /** Newest first; [before] is an ISO-8601 instant (iOS sends milliseconds, `CallsService.swift:35-48`). */
    suspend fun history(token: String, limit: Int, before: String?): List<CallDto>
    suspend fun accept(token: String, callId: UUID): CallDto
    suspend fun reject(token: String, callId: UUID): CallDto
    suspend fun hangup(token: String, callId: UUID): CallDto
    suspend fun heartbeat(token: String, callId: UUID): CallDto
    suspend fun signal(token: String, callId: UUID, signalType: String, payload: String)
}

/** [CallsBackend] over the app's one [ShroudApi] (plan C3: no second facade). */
class ShroudCallsBackend(private val api: ShroudApi) : CallsBackend {
    override suspend fun iceServers(token: String) = api.iceServers(token)
    override suspend fun createCall(token: String, peerUserId: UUID, modality: CallModality) = api.createCall(token, peerUserId, modality)
    override suspend fun call(token: String, callId: UUID) = api.call(token, callId)
    override suspend fun history(token: String, limit: Int, before: String?) = api.callHistory(token, limit, before)
    override suspend fun accept(token: String, callId: UUID) = api.acceptCall(token, callId)
    override suspend fun reject(token: String, callId: UUID) = api.rejectCall(token, callId)
    override suspend fun hangup(token: String, callId: UUID) = api.hangupCall(token, callId)
    override suspend fun heartbeat(token: String, callId: UUID) = api.callHeartbeat(token, callId)
    override suspend fun signal(token: String, callId: UUID, signalType: String, payload: String) =
        api.callSignal(token, callId, signalType, payload)
}

/**
 * The socket as a call needs it (iOS `RealtimeClient` `.call` holder, `RealtimeClient.swift:15-73`):
 * every event, and a hold that keeps `/ws` open while a call rings or runs — also with the chats
 * locked and the app in the background (calls §6.9).
 */
interface CallSocket {
    val events: Flow<RealtimeEvent>
    fun hold(token: String)
    fun release()
}

/** [CallSocket] over the process's one [RealtimeClient] (plan C4). */
class RealtimeCallSocket(private val client: RealtimeClient) : CallSocket {
    override val events: Flow<RealtimeEvent> get() = client.events
    override fun hold(token: String) = client.hold(RealtimeClient.Holder.Call, token)
    override fun release() = client.release(RealtimeClient.Holder.Call)
}

/**
 * What a call needs to know about the other person (calls §13.2 `CallPeerDirectory`; iOS asks
 * `MessagingController`, CC:1940-1944, 1999, 2004-2015, 2026-2028).
 */
interface CallPeerDirectory {
    /** The stored call secret (readable on a locked phone), or null (`CallSecretStore.secret(for:)`). */
    suspend fun storedSecret(peer: UUID): ByteArray?

    /**
     * Derives (and stores) the call secret from the pinned identity key. Throws
     * [CallSecretException] while the chats are locked, `PeerIdentityChangedException` while their
     * key change is unverified.
     */
    suspend fun deriveSecret(peer: UUID): ByteArray
    fun isSafetyVerified(peer: UUID): Boolean

    /** "12345 67890 …", or null without their key. */
    fun safetyNumber(peer: UUID): String?
    fun confirmSafety(peer: UUID)

    /** The contact's username, the caller-name fallback (CC:2024-2030). */
    fun contactUsername(peer: UUID): String?
}

/**
 * [CallPeerDirectory] over [CallSecrets] and W2-CONTACTS' controllers (plan C29); providers stay
 * null until wired. The stored secret is read on [io] (a Keystore open, the first time).
 */
class ContactsCallPeers(
    private val secrets: CallSecrets,
    private val peers: () -> PeerIdentities?,
    private val contacts: () -> Contacts?,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : CallPeerDirectory {
    override suspend fun storedSecret(peer: UUID): ByteArray? = withContext(io) { secrets.secret(peer) }
    override suspend fun deriveSecret(peer: UUID): ByteArray = secrets.derive(peer)
    override fun isSafetyVerified(peer: UUID): Boolean = peers()?.isSafetyVerified(peer) ?: false
    override fun safetyNumber(peer: UUID): String? = peers()?.safetyNumber(peer)
    override fun confirmSafety(peer: UUID) {
        peers()?.confirmSafety(peer)
    }
    override fun contactUsername(peer: UUID): String? = contacts()?.username(peer)
}

/**
 * Runtime permissions a call asks for (calls §6.8): the microphone right before placing or answering,
 * the camera on a video call start or Video (CC:1880-1885). Both answer at once when granted.
 */
interface CallPermissions {
    suspend fun microphone(): Boolean
    suspend fun camera(): Boolean
}

/** Shows the system permission dialog from one of our screens; registered by the call UI (W3-CALLS-UI). */
fun interface CallPermissionPrompt {
    /** True when [permission] was granted. */
    suspend fun request(permission: String): Boolean
}

/**
 * [CallPermissions] on the platform: granted → true at once; otherwise the registered [prompt] asks
 * (none registered → refused). A phone without any camera answers true for the camera, as iOS does
 * for the simulator's test pattern (CC:1882-1885): the engine then reports it off and the notice says so.
 */
class AndroidCallPermissions(private val context: Context) : CallPermissions {
    @Volatile
    var prompt: CallPermissionPrompt? = null

    override suspend fun microphone(): Boolean = ask(Manifest.permission.RECORD_AUDIO)

    override suspend fun camera(): Boolean {
        if (!context.packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY)) return true
        return ask(Manifest.permission.CAMERA)
    }

    private suspend fun ask(permission: String): Boolean {
        if (context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED) return true
        return prompt?.request(permission) ?: false
    }
}
