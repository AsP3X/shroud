package de.corespace.shroud.core.keys

import java.util.UUID

/**
 * The seam between the message crypto (W1-CRYPTO, `MessageCrypto`) and the sealed key-record store
 * that backs it (W1-KEYS, `RatchetSessionStore`), published by W0-C so both sides build in parallel
 * (plan §1.7.4). W1-CRYPTO tests run against an in-memory implementation
 * (`InMemoryRatchetSessionRecords` in its test sources).
 */

/**
 * Double Ratchet sessions, one record per peer (iOS `RatchetSessionStore`,
 * `ios/shroud/Services/Crypto/RatchetSessionStore.swift:5-57`; crypto spec §7).
 *
 * The store works on the encoded session (`DoubleRatchet.Session.encode`, a binary layout with no
 * `String` copies of the keys); `DoubleRatchet` owns its encoding. On disk every record is sealed with `LocalHistoryCrypto` context `RatchetKeychain`
 * under the history key, inside the WhenUnlocked Keystore sealer, at
 * `keys/ratchets/<LocalNames.name("ratchet", peer)>` (plan §1.5). Never stored in the clear:
 * "a lost ratchet step costs at most a skipped message key, a plaintext one leaks the chain"
 * (`RatchetSessionStore.swift:33-34`).
 *
 * Callers serialise load → mutate → save per peer (`PeerLocks` plus `MessageCrypto`'s own
 * per-peer monitor, plan §1.4); implementations only need each single call to be thread-safe.
 */
interface RatchetSessionRecords {
    /**
     * True while the history key is in memory. `MessageCrypto.seal` checks it first and throws
     * `CryptoError.Locked` rather than reading a locked store as "no session" — iOS would start a
     * fresh initiator session and fork the chain (crypto spec D5).
     */
    val isUnlocked: Boolean

    /**
     * The encoded session for [peerUserId], or null when there is none, the record does not open or
     * the store is locked (`RatchetSessionStore.swift:22-31`). The caller owns (and may zero) the
     * returned array.
     *
     * A record that **exists but cannot be read right now** — the phone is locked (the WhenUnlocked
     * sealer refuses, `RecordRead.DeviceLocked`) or a transient Keystore / I/O error
     * (`RecordRead.Failed`) — throws [de.corespace.shroud.core.crypto.CryptoError.Locked] instead
     * of reading as "no session". Android-only (iOS reads it as `nil`): a null here makes `seal`
     * start a fresh initiator session over the established one, which forks the ratchet for good
     * (crypto D5). `seal` lets the error through, so the send is queued and retried after unlock;
     * the v3 open falls back to the peer box and saves nothing.
     */
    fun load(peerUserId: UUID): ByteArray?

    /**
     * Stores [session] (encoded) for [peerUserId], replacing the previous session. Dropped silently
     * while locked (`RatchetSessionStore.swift:33-40`) or while `StorageSeal.isSealed` (a wipe is
     * running, crypto spec §14). Does not keep a reference to [session].
     */
    fun save(peerUserId: UUID, session: ByteArray)

    /** Forgets [peerUserId]'s session — the user accepted a changed identity key (`RatchetSessionStore.swift:42-49`). */
    fun delete(peerUserId: UUID)

    /** Forgets every session — sign-out (`RatchetSessionStore.swift:51-57`, `MessagingController.swift:556-564`). */
    fun deleteAll()
}
