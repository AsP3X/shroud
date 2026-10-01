package de.corespace.shroud.core.keys

import java.time.Instant
import java.util.UUID

/**
 * Seams between the message crypto (W1-CRYPTO, `MessageCrypto`) and the sealed key-record stores
 * that back it (W1-KEYS, `RatchetSessionStore` and `SenderTagStore`), published by W0-C so both
 * sides build in parallel (plan §1.7.4). W1-CRYPTO tests run against in-memory implementations
 * (`InMemoryRatchetSessionRecords`, `InMemorySenderTagWatermarks` in its test sources).
 */

/**
 * Double Ratchet sessions, one record per peer (iOS `RatchetSessionStore`,
 * `ios/shroud/Services/Crypto/RatchetSessionStore.swift:5-57`; crypto spec §7).
 *
 * The store works on the session JSON (field names of crypto spec §5.4); `DoubleRatchet` owns its
 * encoding. On disk every record is sealed with `LocalHistoryCrypto` context `RatchetKeychain`
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
     * The session JSON for [peerUserId], or null when there is none, the record does not open or
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
     * Stores [sessionJson] for [peerUserId], replacing the previous session. Dropped silently while
     * locked (`RatchetSessionStore.swift:33-40`) or while `StorageSeal.isSealed` (a wipe is running,
     * crypto spec §14). Does not keep a reference to [sessionJson].
     */
    fun save(peerUserId: UUID, sessionJson: ByteArray)

    /** Forgets [peerUserId]'s session — the user accepted a changed identity key (`RatchetSessionStore.swift:42-49`). */
    fun delete(peerUserId: UUID)

    /** Forgets every session — sign-out (`RatchetSessionStore.swift:51-57`, `MessagingController.swift:556-564`). */
    fun deleteAll()
}

/**
 * When each sender was first seen tagging their identity boxes, as server time (iOS
 * `SenderTagStore`, `ios/shroud/Services/Crypto/SenderTagStore.swift:5-93`; crypto spec §6).
 * Once a contact's tagged boxes show up, their untagged ones from that time on are refused, so the
 * server cannot slip in a message "from" them.
 *
 * Keyed by the sender's identity **public key** (ours for self boxes), not by user id: a new phrase
 * is a new sender (`SenderTagStore.swift:10-11`). On disk: `keys/sender-tags/<LocalNames.name("sender-tag", key)>`,
 * the watermark sealed with `LocalHistoryCrypto` context `SenderTagKeychain` (plan §1.5).
 */
interface SenderTagWatermarks {
    /**
     * The watermark of [senderIdentityPublic] (`SenderTagStore.swift:36-56`):
     * [Watermark.Locked] without the history key; [Watermark.Untagged] when there is no record;
     * `Since(Instant.MIN)` when the record exists but cannot be read, opened or parsed ("a Keychain
     * error is not 'never tagged'", `:46-47`, `:52-53`); else `Since(stored instant)`.
     */
    fun taggedSince(senderIdentityPublic: ByteArray): Watermark

    /**
     * Records a verified tag seen on a box sent at [sentAt]. The watermark only ever moves
     * **earlier**: a stored `Since(existing)` with `existing <= sentAt` is kept, so an unreadable
     * record (`Instant.MIN`) is never overwritten. The read-modify-write is serialised inside the
     * implementation (`SenderTagStore.swift:26-28`, `:58-84`). Dropped while locked or while
     * `StorageSeal.isSealed`.
     */
    fun noteTagged(senderIdentityPublic: ByteArray, sentAt: Instant)

    /** Forgets every watermark — sign-out, with the ratchets (`SenderTagStore.swift:86-93`). */
    fun deleteAll()
}

/** A sender's tag watermark (iOS `SenderTagStore.Watermark`, `ios/shroud/Services/Crypto/SenderTagStore.swift:15-19`). */
sealed interface Watermark {
    /** No history key in memory: the policy cannot decide, so the caller fails closed. */
    data object Locked : Watermark

    /** Never saw a verified tag from this identity key. */
    data object Untagged : Watermark

    /** First verified tag at [at] (server time); `Instant.MIN` for a record that exists but cannot be read. */
    data class Since(val at: Instant) : Watermark
}
