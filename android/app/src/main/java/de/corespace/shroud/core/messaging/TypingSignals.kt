package de.corespace.shroud.core.messaging

import de.corespace.shroud.core.model.ChatPeerActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * Typing and voice-recording indicators, both directions (`MessagingController.swift:2249-2389`;
 * messaging-core §17, web `typing.ts`). The timings are the contract with iOS and the web — change
 * them together: a sender says `true` when typing starts and again at most every [KEEPALIVE_MS]
 * while it goes on, then `false` after [IDLE_MS] without a keystroke, on send, when the draft
 * empties, and on leaving the chat. Recording uses the same keepalive and receiver expiry but no
 * idle timeout. A receiver drops an indicator [EXPIRY_MS] after the last `true`; a message from that
 * peer clears it at once.
 *
 * Nothing goes out while the account's typing indicators are off ([allowed], privacy
 * `send_typing`). Main-confined; the timers are jobs in [scope].
 *
 * @param elapsedMillis monotonic time for the keepalive (`SystemClock.elapsedRealtime` in the app).
 */
class TypingSignals(
    private val scope: CoroutineScope,
    private val sender: Sender,
    private val allowed: () -> Boolean,
    private val elapsedMillis: () -> Long,
) {
    /** Where the frames go (`RealtimeClient.sendTyping` / `sendRecording`; dropped while the socket is down). */
    interface Sender {
        fun sendTyping(peer: UUID, isTyping: Boolean)
        fun sendRecording(peer: UUID, isRecording: Boolean)
    }

    private val typingPeers = MutableStateFlow<Set<UUID>>(emptySet())
    private val recordingPeers = MutableStateFlow<Set<UUID>>(emptySet())
    private val activitiesFlow = MutableStateFlow<Map<UUID, ChatPeerActivity>>(emptyMap())
    private val typingExpiry = HashMap<UUID, Job>()
    private val recordingExpiry = HashMap<UUID, Job>()

    private var typingSentTo: UUID? = null
    private var typingSentAt: Long? = null
    private var typingIdle: Job? = null
    private var recordingSentTo: UUID? = null
    private var recordingSentAt: Long? = null
    private var recordingKeepalive: Job? = null

    /** Peers typing to us right now (`typingPeerIDs`). */
    val typing: StateFlow<Set<UUID>> = typingPeers.asStateFlow()

    /** Peers recording a voice note to us right now (`recordingPeerIDs`). */
    val recording: StateFlow<Set<UUID>> = recordingPeers.asStateFlow()

    /** Both as one map, recording winning over typing (`peerActivity(for:)`, `MessagingController.swift:2266-2270`). */
    val activities: StateFlow<Map<UUID, ChatPeerActivity>> = activitiesFlow.asStateFlow()

    fun peerActivity(userId: UUID): ChatPeerActivity? = activitiesFlow.value[userId]

    // ---- Outgoing ------------------------------------------------------------------------------

    /** The composer's draft for [peer] is (not) empty (`setTyping`, `MessagingController.swift:2274-2292`). */
    fun setTyping(peer: UUID, isTyping: Boolean) {
        if (!allowed()) return
        if (isTyping) stopRecording()
        typingSentTo?.let { sentTo -> if (sentTo != peer || !isTyping) stopTyping() }
        if (!isTyping) return
        typingSentTo = peer
        val now = elapsedMillis()
        if (typingSentAt?.let { now - it >= KEEPALIVE_MS } != false) {
            sender.sendTyping(peer, true)
            typingSentAt = now
        }
        typingIdle?.cancel()
        typingIdle = scope.launch {
            delay(IDLE_MS)
            typingIdle = null
            stopTyping()
        }
    }

    /** A voice-note take for [peer] runs or stopped (`setRecording`, `MessagingController.swift:2295-2321`). */
    fun setRecording(peer: UUID, isRecording: Boolean) {
        if (!allowed()) return
        if (!isRecording) {
            if (recordingSentTo == peer) stopRecording()
            return
        }
        recordingSentTo?.let { sentTo -> if (sentTo != peer) stopRecording() }
        stopTyping()
        val already = recordingSentTo == peer
        recordingSentTo = peer
        val now = elapsedMillis()
        if (!already || recordingSentAt?.let { now - it >= KEEPALIVE_MS } != false) {
            sender.sendRecording(peer, true)
            recordingSentAt = now
        }
        recordingKeepalive?.cancel()
        recordingKeepalive = scope.launch {
            while (isActive) {
                delay(KEEPALIVE_MS)
                val target = recordingSentTo ?: return@launch
                sender.sendRecording(target, true)
                recordingSentAt = elapsedMillis()
            }
        }
    }

    /** Says `false` to both if we last said `true` (sends, leaving, typing turned off). */
    fun stopOutgoing() {
        stopTyping()
        stopRecording()
    }

    /** `stopTyping`, `MessagingController.swift:2324-2332`. */
    private fun stopTyping() {
        typingIdle?.cancel()
        typingIdle = null
        typingSentTo?.let { sender.sendTyping(it, false) }
        typingSentTo = null
        typingSentAt = null
    }

    /** `stopRecording`, `MessagingController.swift:2334-2342`. */
    private fun stopRecording() {
        recordingKeepalive?.cancel()
        recordingKeepalive = null
        recordingSentTo?.let { sender.sendRecording(it, false) }
        recordingSentTo = null
        recordingSentAt = null
    }

    // ---- Incoming ------------------------------------------------------------------------------

    /** A peer started or stopped typing to us; "started" lapses unless refreshed (`setPeerTyping`, `:2345-2358`). */
    fun setPeerTyping(userId: UUID, isTyping: Boolean) {
        if (isTyping && userId in recordingPeers.value) setPeerRecording(userId, false)
        typingExpiry.remove(userId)?.cancel()
        if (isTyping) {
            if (userId !in typingPeers.value) typingPeers.value += userId
            typingExpiry[userId] = scope.launch {
                delay(EXPIRY_MS)
                typingExpiry.remove(userId)
                setPeerTyping(userId, false)
            }
        } else if (userId in typingPeers.value) {
            typingPeers.value -= userId
        }
        publish()
    }

    /** A peer started or stopped recording a voice note to us (`setPeerRecording`, `:2361-2374`). */
    fun setPeerRecording(userId: UUID, isRecording: Boolean) {
        if (isRecording && userId in typingPeers.value) setPeerTyping(userId, false)
        recordingExpiry.remove(userId)?.cancel()
        if (isRecording) {
            if (userId !in recordingPeers.value) recordingPeers.value += userId
            recordingExpiry[userId] = scope.launch {
                delay(EXPIRY_MS)
                recordingExpiry.remove(userId)
                setPeerRecording(userId, false)
            }
        } else if (userId in recordingPeers.value) {
            recordingPeers.value -= userId
        }
        publish()
    }

    /** Their message arrived, they went offline, or were blocked: both indicators go at once. */
    fun clearPeer(userId: UUID) {
        setPeerTyping(userId, false)
        setPeerRecording(userId, false)
    }

    /** Typing indicators were turned off: nothing goes out, nothing shows (`MessagingController.swift:2163-2170`). */
    fun clearIncoming() {
        for (peer in typingPeers.value + recordingPeers.value) clearPeer(peer)
    }

    /** Lock, stop, memory clear (`clearAllTyping`, `MessagingController.swift:2376-2385`). */
    fun clearAll() {
        stopOutgoing()
        typingExpiry.values.forEach { it.cancel() }
        typingExpiry.clear()
        typingPeers.value = emptySet()
        recordingExpiry.values.forEach { it.cancel() }
        recordingExpiry.clear()
        recordingPeers.value = emptySet()
        publish()
    }

    private fun publish() {
        val next = HashMap<UUID, ChatPeerActivity>()
        for (peer in typingPeers.value) next[peer] = ChatPeerActivity.Typing
        for (peer in recordingPeers.value) next[peer] = ChatPeerActivity.Recording
        if (next != activitiesFlow.value) activitiesFlow.value = next
    }

    companion object {
        /** `typingKeepalive`, `MessagingController.swift:2261`. */
        const val KEEPALIVE_MS = 3_000L

        /** `typingIdle`, `:2262`. */
        const val IDLE_MS = 3_000L

        /** `typingExpiry`, `:2263`. */
        const val EXPIRY_MS = 6_000L
    }
}
