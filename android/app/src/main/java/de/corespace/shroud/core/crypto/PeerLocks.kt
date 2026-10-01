package de.corespace.shroud.core.crypto

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import java.util.UUID
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/**
 * One coroutine [Mutex] per peer, serialising every multi-step decrypt/seal sequence for that
 * peer: plaintext-cache check → open/seal → ratchet save → cache save (plan §1.4, messaging-core
 * §23 item 2 and decision D4: crypto owns the lock, messaging wraps its sequences in it).
 *
 * iOS needs none of this: `MessageCrypto` and `MessageDecoder` run on the main actor, so a
 * ratchet load → mutate → save is atomic (`ios/shroud/Services/Messaging/MessageDecoder.swift:186-213`).
 * Android moves that work to `Dispatchers.Default`, so two decodes of one chat could otherwise
 * step the same ratchet twice and leave "[Unable to decrypt]" behind. The web does the same with
 * its promise chain `withPeerLock` (`web/src/messaging.ts:147-161`).
 *
 * Rules:
 * - Key = the peer's user id; Notes use our own user id (messaging-core §23).
 * - Waiters are served first come, first served (the [Mutex] is fair, like the web's chain).
 * - **Re-entrant for the same peer**: a `withPeer(samePeer)` nested inside [withPeer]'s block —
 *   directly, through `withContext`, or in a child coroutine the block starts and joins — runs
 *   its block directly instead of deadlocking (the web's chain would deadlock here). Children the
 *   block runs concurrently therefore share the critical section with it; the block owns that
 *   choice. A coroutine that merely inherited the context after the block returned (a scope that
 *   escaped it) gets no free pass: re-entry is granted only while the lock is still held.
 * - **One peer at a time:** a nested `withPeer(otherPeer)` on the same instance throws
 *   [IllegalStateException]. Two coroutines nesting A→B and B→A would deadlock; failing every
 *   nested call deterministically catches that in tests instead of in the field.
 * - Uploads and other network waits stay outside the lock (web `sendImage` note,
 *   messaging-core §23 item 2).
 * - Cancellation while waiting or inside the block always releases the lock; so does an
 *   exception thrown by the block (it is rethrown unchanged).
 *
 * Entries are dropped when no coroutine holds or waits for them, so the map stays as small as the
 * set of chats being worked on. One instance per process (built by the keys module), shared by
 * every engine that seals or opens for a peer.
 */
class PeerLocks {
    private class Entry {
        val mutex = Mutex()
        var users = 0 // guarded by `entries`
    }

    /** The owner token passed to the [Mutex]; compared by identity. */
    private class Holding(val owner: PeerLocks, val peer: UUID, val entry: Entry) {
        val isLive: Boolean get() = entry.mutex.holdsLock(this)
    }

    /** Which peer locks the current coroutine holds; what makes the lock re-entrant. */
    private class HeldPeers(val held: List<Holding>) : AbstractCoroutineContextElement(HeldPeers) {
        companion object Key : CoroutineContext.Key<HeldPeers>
    }

    private val entries = HashMap<UUID, Entry>()

    /** Runs [block] while holding [peer]'s lock (see the class rules) and returns its result. */
    suspend fun <T> withPeer(peer: UUID, block: suspend () -> T): T {
        val held = currentCoroutineContext()[HeldPeers]?.held.orEmpty().filter { it.isLive }
        val mine = held.filter { it.owner === this }
        if (mine.any { it.peer == peer }) return block()
        check(mine.isEmpty()) { "PeerLocks: this coroutine already holds another peer's lock; take one peer at a time" }

        val entry = synchronized(entries) { entries.getOrPut(peer) { Entry() }.also { it.users++ } }
        try {
            val holding = Holding(this, peer, entry)
            entry.mutex.lock(holding)
            try {
                return withContext(HeldPeers(held + holding)) { block() }
            } finally {
                entry.mutex.unlock(holding)
            }
        } finally {
            synchronized(entries) {
                entry.users--
                if (entry.users == 0 && entries[peer] === entry) entries.remove(peer)
            }
        }
    }

    /** Peers with a holder or waiter right now (tests: the map does not grow without bound). */
    internal val trackedPeerCount: Int
        get() = synchronized(entries) { entries.size }
}
