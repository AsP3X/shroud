package de.corespace.shroud.core.calls

import de.corespace.shroud.core.calls.crypto.CallCrypto
import de.corespace.shroud.core.contacts.Contacts
import de.corespace.shroud.core.contacts.PeerIdentities
import de.corespace.shroud.core.crypto.B64
import de.corespace.shroud.core.crypto.CryptoError
import de.corespace.shroud.core.model.Ids
import de.corespace.shroud.core.model.PeerIdentityEvent
import de.corespace.shroud.core.storage.RecordRead
import de.corespace.shroud.core.storage.SealedFile
import de.corespace.shroud.core.storage.StorageSeal
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import java.util.UUID

/**
 * Deriving a call secret needs the identity keys, which need the chats unlocked
 * (`CallSecretError.chatsLocked`, `ios/shroud/Services/Calls/CallSecretStore.swift:5-14`).
 */
class CallSecretException private constructor(message: String) : Exception(message, null, false, false) {
    companion object {
        const val CHATS_LOCKED_TEXT = "Open Shroud and unlock your chats to connect this call."

        /** The only case (`CallSecretStore.swift:7`). */
        val ChatsLocked: CallSecretException get() = CallSecretException(CHATS_LOCKED_TEXT)
    }
}

/**
 * Each contact's call secret, kept where a locked phone can read it (iOS `CallSecretStore`,
 * `CallSecretStore.swift:16-101`; calls §11). [secret] is null unless exactly 32 bytes are stored;
 * [save] is a no-op when the same secret is already there. Callers own the arrays they get and give.
 */
interface CallSecretStore {
    fun secret(peer: UUID): ByteArray?
    fun save(peer: UUID, secret: ByteArray)
    fun delete(peer: UUID)

    /** Every secret, the file and its Keystore key (the wipe). */
    fun deleteAll()
}

/** Secrets in memory only, for tests (`useInMemoryStorageForTesting`, `CallSecretStore.swift:31-34`). */
class InMemoryCallSecretStore : CallSecretStore {
    private val secrets = HashMap<UUID, ByteArray>()

    @Synchronized
    override fun secret(peer: UUID): ByteArray? = secrets[peer]?.takeIf { it.size == SECRET_BYTES }?.copyOf()

    @Synchronized
    override fun save(peer: UUID, secret: ByteArray) {
        secrets[peer] = secret.copyOf()
    }

    @Synchronized
    override fun delete(peer: UUID) {
        secrets.remove(peer)?.fill(0)
    }

    @Synchronized
    override fun deleteAll() {
        secrets.values.forEach { it.fill(0) }
        secrets.clear()
    }

    /** What is stored, for assertions. */
    @get:Synchronized
    val peers: Set<UUID> get() = secrets.keys.toSet()
}

/**
 * `noBackupFilesDir/call-secrets.sealed` (plan §1.5): JSON `{"<peer id lower case>": "<base64 32 B>"}`
 * sealed by the AFU Keystore key `shroud.call-secrets.v1` — no user authentication and no
 * `setUnlockedDeviceRequired`, so a ring on a locked phone can be answered after the first unlock
 * since boot, as iOS keeps them `AfterFirstUnlockThisDeviceOnly` (`CallSecretStore.swift:72-78`;
 * P3a). The secrets open call signals and nothing else; they never leave the device
 * (invariant 13) and are derived, not the identity key (invariant 2).
 *
 * The map is read once and kept in memory; a read the phone refuses (locked before the first
 * unlock, a transient Keystore error) is not cached and answers "no secret". Every write rewrites
 * the small file and is dropped while [seal] is set (a wipe runs). [deleteAll] removes the file
 * and the Keystore key ([deleteKey]). Thread-safe; blocking — callers keep writes off the main
 * thread where they can. Never logs.
 */
class SealedCallSecretStore(
    private val file: SealedFile,
    private val seal: StorageSeal,
    private val deleteKey: () -> Unit,
) : CallSecretStore {
    private var cache: MutableMap<String, String>? = null

    @Synchronized
    override fun secret(peer: UUID): ByteArray? {
        val encoded = load()?.get(Ids.wire(peer)) ?: return null
        return B64.decodeStrict(encoded)?.takeIf { it.size == SECRET_BYTES }
    }

    @Synchronized
    override fun save(peer: UUID, secret: ByteArray) {
        if (seal.isSealed || secret.size != SECRET_BYTES) return
        val map = load() ?: return
        val key = Ids.wire(peer)
        val encoded = B64.encode(secret)
        if (map[key] == encoded) return
        val next = HashMap(map).apply { put(key, encoded) }
        if (write(next)) cache = next
    }

    @Synchronized
    override fun delete(peer: UUID) {
        if (seal.isSealed) return
        val map = load() ?: return
        val key = Ids.wire(peer)
        if (key !in map) return
        val next = HashMap(map).apply { remove(key) }
        if (write(next)) cache = next
    }

    @Synchronized
    override fun deleteAll() {
        cache = HashMap()
        file.delete()
        try {
            deleteKey()
        } catch (_: Exception) {
            // The Keystore refused; the wipe's own key step removes every `shroud.` alias anyway.
        }
    }

    /**
     * The stored map; null when it exists but cannot be read now (so nothing overwrites it). A
     * non-empty map in memory whose file is gone was wiped behind this store's back (a wipe path
     * that missed `deleteAll`): it is dropped, never written back (review W2).
     */
    private fun load(): MutableMap<String, String>? {
        cache?.let { held ->
            if (held.isEmpty() || file.exists()) return held
            cache = null
        }
        val loaded: MutableMap<String, String> = when (val read = file.readClassified()) {
            is RecordRead.Found -> try {
                HashMap(json.decodeFromString(MAP, String(read.bytes, Charsets.UTF_8)))
            } catch (_: SerializationException) {
                HashMap()
            } catch (_: IllegalArgumentException) {
                HashMap()
            } finally {
                read.bytes.fill(0)
            }
            RecordRead.NotFound -> HashMap()
            RecordRead.DeviceLocked, RecordRead.Failed -> return null
        }
        cache = loaded
        return loaded
    }

    private fun write(map: Map<String, String>): Boolean = try {
        if (map.isEmpty()) file.delete() else file.write(json.encodeToString(MAP, map).toByteArray(Charsets.UTF_8))
        true
    } catch (_: Exception) {
        false
    }

    private companion object {
        val json = Json
        val MAP = MapSerializer(String.serializer(), String.serializer())
    }
}

private const val SECRET_BYTES = 32

/**
 * Call secrets for every contact (plan C29; iOS `MessagingController.callSecret(for:)` and
 * `refreshCallSecrets()`, `MessagingController.swift:4591-4628`, which Android moves out of
 * messaging into the calls package).
 *
 * Human: the identity keys need the fingerprint or the screen lock, and a call rings on the lock
 * screen: answering it must open the caller's sealed offer without the chats being unlocked. So
 * while the chats are unlocked the app derives each contact's call secret and keeps it in
 * [store], readable after the first unlock. A contact whose key changed gets none until the new
 * key is accepted (calls wait for the safety number, like messages).
 *
 * @param isUnlocked the identity keys are in memory (`CryptoController.isUnlocked`).
 * @param secretWith the call secret with a peer's identity public key, computed inside
 *   `CryptoController.withMaterial` (`IdentityKeyMaterial.agreement`) so the private key never
 *   leaves it; null while locked.
 * @param peers / [contacts] the W2-CONTACTS controllers (null until wired).
 * @param io where store writes run.
 */
class CallSecrets(
    private val store: CallSecretStore,
    private val isUnlocked: () -> Boolean,
    private val secretWith: (peerPublic: ByteArray) -> ByteArray?,
    private val peers: () -> PeerIdentities?,
    private val contacts: () -> Contacts?,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {
    /** The stored secret for [peer] (`CallSecretStore.secret(for:)`), or null. */
    fun secret(peer: UUID): ByteArray? = store.secret(peer)

    /**
     * [peer]'s call secret from the pinned identity key, saved for the lock screen too
     * (`MessagingController.callSecret(for:)`, `MessagingController.swift:4591-4603`). Throws
     * [CallSecretException] (`ChatsLocked`) while the chats are locked or the pin cannot be read
     * now, and `PeerIdentityChangedException` while their key change is unverified.
     */
    suspend fun derive(peer: UUID): ByteArray {
        if (!isUnlocked()) throw CallSecretException.ChatsLocked
        val identities = peers() ?: throw CallSecretException.ChatsLocked
        val peerKey = try {
            identities.publicKeyForSending(peer)
        } catch (_: CryptoError.Locked) {
            throw CallSecretException.ChatsLocked
        }
        val secret = secretWith(peerKey) ?: throw CallSecretException.ChatsLocked
        withContext(io) { store.save(peer, secret) }
        return secret
    }

    /**
     * Derives each contact's call secret while the chats are unlocked, so a call from any of them
     * can be answered on a locked phone (`refreshCallSecrets`, `MessagingController.swift:4608-4628`).
     * A contact whose key changed loses theirs; one whose key cannot be resolved now is skipped.
     */
    suspend fun refreshAll() {
        if (!isUnlocked()) return
        val identities = peers() ?: return
        val roster = contacts()?.contacts?.value ?: return
        for (peer in roster.map { it.userId }.distinct()) {
            if (!isUnlocked()) return
            if (identities.identityChange(peer) != null) {
                withContext(io) { store.delete(peer) }
                continue
            }
            val key = try {
                identities.resolvePublicKey(peer)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (_: Exception) {
                null
            }
            if (identities.identityChange(peer) != null) {
                withContext(io) { store.delete(peer) }
                continue
            }
            val secret = key?.let { candidate ->
                try {
                    secretWith(candidate)
                } catch (_: CryptoError) {
                    null
                }
            } ?: continue
            withContext(io) { store.save(peer, secret) }
            secret.fill(0)
        }
    }

    /** Drops [peer]'s secret (their key changed, `MessagingController.swift:4630-4637`). */
    fun forget(peer: UUID) = store.delete(peer)

    /** Every secret, the file and its key (Log Out / removal wipe). */
    fun deleteAll() = store.deleteAll()

    /**
     * Keeps the secrets current (plan C29): every roster change and every unlock re-derives them
     * (`MessagingController.swift:485, 826`); a key change forgets the peer's
     * (`:4630-4637`), an accepted new key derives it again (`:4584`). Collects in [scope] until it
     * is cancelled; providers that are still null are skipped.
     */
    fun start(scope: CoroutineScope, unlocked: Flow<Boolean>): Job = scope.launch {
        launch {
            // An unlock (not the state at start): the roster is hydrated by then or follows.
            unlocked.distinctUntilChanged().drop(1).collect { open -> if (open) refreshAll() }
        }
        contacts()?.let { roster -> launch { roster.rosterChanges.collect { refreshAll() } } }
        peers()?.let { identities ->
            launch {
                identities.events.collect { event ->
                    when (event) {
                        is PeerIdentityEvent.KeyChanged -> withContext(io) { store.delete(event.peer) }
                        is PeerIdentityEvent.KeyAccepted -> try {
                            derive(event.peer).fill(0)
                        } catch (e: kotlinx.coroutines.CancellationException) {
                            throw e
                        } catch (_: Exception) {
                            // Locked or unreachable: the next unlock or roster change derives it.
                        }
                        is PeerIdentityEvent.Pinned -> Unit
                    }
                }
            }
        }
    }

    companion object {
        /** The production [secretWith]: X25519 with our identity key, then the call-secret HKDF (CRY:27-43). */
        fun secretFromShared(shared: ByteArray, ourPublic: ByteArray, peerPublic: ByteArray): ByteArray = try {
            CallCrypto.callSecretFromShared(shared, ourPublic, peerPublic)
        } finally {
            shared.fill(0)
        }
    }
}
