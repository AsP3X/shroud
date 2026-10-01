package de.corespace.shroud.core.contacts

import de.corespace.shroud.core.crypto.B64
import de.corespace.shroud.core.crypto.CryptoError
import de.corespace.shroud.core.crypto.IdentitySafetyNumber
import de.corespace.shroud.core.crypto.PeerLocks
import de.corespace.shroud.core.keys.PeerIdentityStore
import de.corespace.shroud.core.keys.PeerIdentityStore.PinRead
import de.corespace.shroud.core.keys.RatchetSessionRecords
import de.corespace.shroud.core.model.Bytes
import de.corespace.shroud.core.model.PeerIdentityChange
import de.corespace.shroud.core.model.PeerIdentityChangedException
import de.corespace.shroud.core.model.PeerIdentityEvent
import de.corespace.shroud.core.net.ApiError
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.getAndUpdate
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Trust on first use for contacts' identity keys, key changes, verification and safety numbers
 * (iOS `MessagingController.swift:4508-4585, 4629-4652`; contacts §4.8–4.9, messaging-core §20.4;
 * web `web/src/crypto/peerIdentity.ts`; plan C6, C16, P10b).
 *
 * - **Pin**: the first identity key seen for a peer, kept in [PeerIdentityStore]
 *   (`keys/peer-identity.v1`, W1-KEYS) and never overwritten by a fetch.
 * - **Change**: a later `GET /keys/identity/{peer}` that differs from the pin →
 *   [identityChanges]`[peer]` (memory only, recomputed every session because the pin stays the old
 *   key) and [PeerIdentityEvent.KeyChanged] (calls drop the peer's call secret on it, plan C29).
 *   Sends to the peer stop ([publicKeyForSending] throws) until the user trusts the new key.
 * - **Verified**: the user compared the safety number; stored next to the pin, cleared when a new key
 *   is accepted.
 * - **Rechecked**: peers whose server key was fetched once this session, so decrypting does not
 *   fetch per message (iOS `verifiedPeerIDs`).
 *
 * **Android contract (plan §1.7.4 note, W1 review):** while the pin record cannot be read (phone
 * locked with the chats unlocked, a transient Keystore error — [PinRead.Unavailable]) nothing is
 * trusted, sent or decrypted with a server key: [resolvePublicKey] and [publicKeyForSending] throw
 * [CryptoError.Locked] so the caller queues or retries after unlock. iOS reads that state as "not
 * pinned" (`PeerIdentityStore.swift:21-34`), which would let a server-chosen key through unchecked.
 *
 * Thread-safe; callable from any dispatcher (the decrypt path runs on `Dispatchers.Default` inside
 * `PeerLocks`, so this class never takes a peer lock around its own work). Store reads and writes of
 * the suspend paths run on [io]; the non-suspend members the screens call ([isSafetyVerified],
 * [safetyNumber], [confirmSafety], [acceptNewIdentity]) use the store on the caller's thread, as iOS
 * uses the Keychain on the main actor — the record is read once and then served from memory, and the
 * two writes are user actions. State is published through atomic `StateFlow` updates; work started
 * before [clearMemory] or [wipe] publishes nothing afterwards. Never logs ids or keys.
 *
 * @param token the session's bearer token, null when signed out.
 * @param localIdentityPublicKey this account's X25519 identity public key (a copy), null while the
 *   chats are locked.
 * @param scope where background re-checks run (the app scope).
 */
class PeerIdentityController(
    private val backend: ContactsBackend,
    private val store: PeerIdentityStore,
    private val ratchets: RatchetSessionRecords,
    private val peerLocks: PeerLocks,
    private val token: () -> String?,
    private val localIdentityPublicKey: () -> ByteArray?,
    private val scope: CoroutineScope,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : PeerIdentities, ContactsLifecycleListener {
    private val changes = MutableStateFlow<Map<UUID, PeerIdentityChange>>(emptyMap())
    override val identityChanges: StateFlow<Map<UUID, PeerIdentityChange>> = changes.asStateFlow()

    /** Peers verified this session (the store is the record; this is what screens observe change). */
    private val verified = MutableStateFlow<Set<UUID>>(emptySet())
    override val verifiedPeers: StateFlow<Set<UUID>> = verified.asStateFlow()

    private val mutableEvents = MutableSharedFlow<PeerIdentityEvent>(extraBufferCapacity = 64, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    override val events: SharedFlow<PeerIdentityEvent> = mutableEvents.asSharedFlow()

    /** iOS `verifiedPeerIDs` ("rechecked this session"). */
    private val rechecked: MutableSet<UUID> = ConcurrentHashMap.newKeySet()

    /** Bumped by [clearMemory]: results of work started before it are dropped. */
    private val generation = AtomicLong()

    /** Serialises "is there a pin? → pin this one" so two first uses cannot pin two keys. */
    private val pinLock = Any()

    /**
     * The key to decrypt [peer]'s messages with (`resolvePeerIdentityPublicKey`, `:4508-4519`): the
     * pin — re-checked against the server once per session in the background — or, when nothing is
     * pinned yet, the server's key, pinned now (TOFU).
     *
     * @throws CryptoError.Locked while the pin cannot be read, signed out with nothing pinned, or
     *   when the session ended while the first key was being fetched.
     * @throws ApiError when the first-use fetch fails.
     */
    override suspend fun resolvePublicKey(peer: UUID): ByteArray {
        when (val pin = readPin(peer)) {
            is PinRead.Pinned -> {
                val bearer = token()
                if (bearer != null && rechecked.add(peer)) scope.launch { verify(peer, bearer) }
                return pin.key
            }
            PinRead.Unavailable -> throw CryptoError.Locked
            PinRead.None -> {
                val bearer = token() ?: throw CryptoError.Locked
                val gen = generation.get()
                val fetched = fetchKey(peer, bearer)
                return pinFirstUse(peer, fetched, gen)
            }
        }
    }

    /**
     * The key to seal for [peer] with (`peerIdentityForSending`, `:4522-4528`): the server's key is
     * checked first, so a send never uses a superseded key and never switches to a new one silently.
     *
     * @throws PeerIdentityChangedException while a change waits for the user.
     * @throws CryptoError.Locked while the pin cannot be read, or signed out.
     */
    override suspend fun publicKeyForSending(peer: UUID): ByteArray {
        val bearer = token() ?: throw CryptoError.Locked
        verify(peer, bearer)
        if (changes.value[peer] != null) throw PeerIdentityChangedException()
        return resolvePublicKey(peer)
    }

    /**
     * The profile's re-check (`refreshPeerIdentity`, `:4567-4573`): compares with the server and pins
     * a first key when there is none yet. Never throws.
     */
    override suspend fun refresh(peer: UUID) {
        val bearer = token() ?: return
        verify(peer, bearer)
        if (readPin(peer) != PinRead.None) return
        try {
            resolvePublicKey(peer)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // As iOS's `try?`: the profile shows what it has.
        }
    }

    /** `identityChange(for:)` (`:4530-4532`). */
    override fun identityChange(peer: UUID): PeerIdentityChange? = changes.value[peer]

    /** `peerSafetyVerified` (`:4534-4536`): verified this session, or the stored flag. False while unreadable. */
    override fun isSafetyVerified(peer: UUID): Boolean =
        peer in verified.value || (store.pin(peer) as? PinRead.Pinned)?.verified == true

    /**
     * The safety number was compared, on the profile or the call screen (`confirmPeerSafety`,
     * `:4539-4543`). Needs a pin; a failed store write is dropped like iOS's `try?`, the session
     * still remembers it.
     */
    override fun confirmSafety(peer: UUID) {
        if (store.pin(peer) !is PinRead.Pinned) return
        try {
            store.setVerified(peer, true)
        } catch (_: CryptoError) {
            // Unreadable record: nothing written; the session set below still shows it.
        }
        verified.update { it + peer }
    }

    /**
     * The number to compare with the contact's own phone (`safetyNumber(for:)`, `:4545-4565`, P10b):
     * while a change waits for "Trust new key", the **new** key's number — the one the contact's
     * phone shows — else the pin's. Null while the chats are locked or nothing is known.
     */
    override fun safetyNumber(peer: UUID): String? {
        val local = localIdentityPublicKey() ?: return null
        val peerKey = safetyNumberKey(pinned = (store.pin(peer) as? PinRead.Pinned)?.key, change = changes.value[peer]) ?: return null
        return IdentitySafetyNumber.displayString(local, peerKey)
    }

    /**
     * "Trust new key" (`acceptNewPeerIdentity`, `:4576-4585`): pins the new key, clears the verified
     * flag, deletes the ratchet session built on the old key, clears the change and emits
     * [PeerIdentityEvent.KeyAccepted] (calls derive the call secret again on it). Nothing changes when
     * the record cannot be read now.
     *
     * The ratchet session is deleted at once and again under the peer's lock once any decrypt or
     * seal in flight for that peer has finished, so such a sequence cannot save the old session back.
     */
    override fun acceptNewIdentity(peer: UUID) {
        val change = changes.value[peer] ?: return
        try {
            store.save(peer, change.currentKey.toByteArray())
            store.setVerified(peer, false)
        } catch (_: CryptoError) {
            return
        }
        verified.update { it - peer }
        ratchets.delete(peer)
        scope.launch(io) { peerLocks.withPeer(peer) { ratchets.delete(peer) } }
        changes.update { it - peer }
        rechecked.add(peer)
        mutableEvents.tryEmit(PeerIdentityEvent.KeyAccepted(peer))
    }

    /** Chats locked (`stopActivity`, `:520-556`): forgets changes, session verifications and re-checks. */
    override fun clearMemory() {
        generation.incrementAndGet()
        changes.value = emptyMap()
        verified.value = emptySet()
        rechecked.clear()
    }

    /** Log Out / device removal (`clearLocalData`, `:558-564`): [clearMemory] and every pin and flag. */
    override fun wipe() {
        clearMemory()
        store.clear()
    }

    /** Follows the contacts engine: chats locked → [clearMemory]; Log Out / removal → [wipe]. */
    override fun onContactsStopped(wipe: Boolean) {
        if (wipe) wipe() else clearMemory()
    }

    /**
     * Compares the server's key with the pin (`verifyPeerIdentity`, `:4629-4641`). An unreachable
     * server invents nothing; an unreadable pin decides nothing (and the peer is re-checked later).
     */
    private suspend fun verify(peer: UUID, bearer: String) {
        val gen = generation.get()
        val fetched = try {
            fetchKey(peer, bearer)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            return
        }
        if (gen != generation.get()) return
        val pin = readPin(peer)
        if (pin == PinRead.Unavailable) {
            rechecked.remove(peer)
            return
        }
        rechecked.add(peer)
        if (pin is PinRead.Pinned && !pin.key.contentEquals(fetched)) recordChange(peer, pin.key, fetched, gen)
    }

    private fun recordChange(peer: UUID, pinned: ByteArray, fetched: ByteArray, gen: Long) {
        val change = PeerIdentityChange(previousKey = Bytes.of(pinned), currentKey = Bytes.of(fetched))
        if (gen != generation.get()) return
        val before = changes.getAndUpdate { if (it[peer] == change) it else it + (peer to change) }
        if (before[peer] != change) mutableEvents.tryEmit(PeerIdentityEvent.KeyChanged(peer))
    }

    /**
     * Pins [fetched] unless a pin appeared meanwhile (another first use won the race): then that pin
     * is the answer, and a different [fetched] is a change like any other. A fetch that outlived the
     * session ([clearMemory] / [wipe] meanwhile) pins nothing: a Log Out must not get a pin back.
     */
    private suspend fun pinFirstUse(peer: UUID, fetched: ByteArray, gen: Long): ByteArray {
        var pinnedNow = false
        val key = withContext(io) {
            synchronized(pinLock) {
                if (gen != generation.get()) throw CryptoError.Locked
                when (val again = store.pin(peer)) {
                    PinRead.Unavailable -> throw CryptoError.Locked
                    is PinRead.Pinned -> again.key
                    PinRead.None -> {
                        store.save(peer, fetched)
                        pinnedNow = true
                        fetched
                    }
                }
            }
        }
        if (!key.contentEquals(fetched)) {
            recordChange(peer, key, fetched, gen)
        } else if (gen == generation.get()) {
            rechecked.add(peer)
        }
        if (pinnedNow) mutableEvents.tryEmit(PeerIdentityEvent.Pinned(peer))
        return key
    }

    private suspend fun readPin(peer: UUID): PinRead = withContext(io) { store.pin(peer) }

    /** `fetchPeerIdentityKey` (`:4643-4652`): strict Base64 (`Data(base64Encoded:)`), else a decoding error. */
    private suspend fun fetchKey(peer: UUID, bearer: String): ByteArray {
        val text = backend.identityKey(bearer, peer)
        return B64.decodeStrict(text) ?: throw ApiError.Decoding("The identity key is not Base64.")
    }

    companion object {
        /** `safetyNumberKey(pinned:change:)` (`:4563-4565`): a pending change's new key, else the pin. */
        fun safetyNumberKey(pinned: ByteArray?, change: PeerIdentityChange?): ByteArray? =
            change?.currentKey?.toByteArray() ?: pinned
    }
}
