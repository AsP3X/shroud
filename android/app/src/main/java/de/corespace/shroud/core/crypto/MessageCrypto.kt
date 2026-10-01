package de.corespace.shroud.core.crypto

import de.corespace.shroud.core.keys.RatchetSessionRecords
import de.corespace.shroud.core.keys.SenderTagWatermarks
import de.corespace.shroud.core.keys.Watermark
import de.corespace.shroud.core.model.Ids
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationStrategy
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** Who is opening an envelope (iOS `MessageCrypto.OpenAs`, `ios/shroud/Services/Crypto/MessageCrypto.swift:62-66`). */
enum class OpenAs {
    /** A peer's message to us: the `peer` box (or the ratchet body). */
    Recipient,

    /** Our own message, read back on any of our devices: the `self` box. */
    Sender,
}

/**
 * Message sealing for 1:1 chats (iOS `ios/shroud/Services/Crypto/MessageCrypto.swift`, web
 * `web/src/crypto/messageCrypto.ts`; crypto spec §3–§4; wire shapes in `Envelopes.kt`):
 *
 * - **v1** one untagged box to the peer (legacy, read only);
 * - **v2** a `peer` box and a `self` box, no session state;
 * - **v3** a Double Ratchet body for the peer plus both identity boxes, so the sender's other
 *   devices (self box) and the recipient's other devices (peer box) read without ratchet state.
 *
 * Every box carries a sender tag (`IdentityBoxes`). Untagged boxes — v1, and builds from before the
 * tag — are read under the per-sender watermark policy of [SenderTagWatermarks]
 * (`MessageCrypto.swift:443-486`): once a sender's tagged boxes were seen, their untagged ones from
 * that server time on are refused, so the server cannot slip in a message "from" them.
 *
 * Android differences, none visible on the wire:
 * - **Locked ratchets (crypto D5, web parity).** [seal] throws [CryptoError.Locked] while
 *   [RatchetSessionRecords.isUnlocked] is false, and when the session record exists but the phone
 *   cannot read it now (locked phone, transient Keystore error: [RatchetSessionRecords.load]
 *   throws). iOS reads both as "no session", starts a fresh initiator session over the established
 *   one and forks the ratchet. A v3 open in that state reads the peer box and saves nothing.
 * - **Per-peer monitor (plan §1.4).** iOS runs on the main actor, so a ratchet load → mutate → save
 *   is atomic (`ios/shroud/Services/Messaging/MessageDecoder.swift:186-213`). Here callers run on
 *   `Dispatchers.Default`, so every ratchet read-modify-write holds a monitor per peer, on top of
 *   the messaging engines' `PeerLocks`. Two concurrent seals never reuse a chain step, two
 *   concurrent opens never lose one.
 * - **Copies, not values.** Swift's value semantics keep a failed attempt from persisting
 *   anything; here every attempt runs on a [DoubleRatchet.Session.deepCopy] that is saved only on
 *   success (web `messageCrypto.ts:324-328`).
 * - **Keys are checked before any state changes**, and a v3 seal saves its ratchet step only once
 *   both boxes are sealed (iOS saves first, `:173-174`, and can keep a step for a seal that then
 *   throws).
 * - Library errors never escape: a JSON or GCM failure is [CryptoError.OpenFailed]; ratchet
 *   failures are [RatchetError]s (they only ever lead to the peer-box fallback).
 *
 * The functions block (CPU only) and are thread-safe; call them off the main thread. Our private
 * key is passed per call and never retained or zeroed; sessions, shared secrets and derived keys are
 * zeroed after use. Never logs keys, ids or plaintext.
 */
class MessageCrypto internal constructor(
    private val ratchets: RatchetSessionRecords,
    private val senderTags: SenderTagWatermarks,
    private val entropy: Entropy,
    /** [LEGACY_BOX_CUTOFF] in production; tests pass their own to exercise the rule. */
    private val legacyBoxCutoff: Instant?,
) {
    constructor(
        ratchets: RatchetSessionRecords,
        senderTags: SenderTagWatermarks,
        entropy: Entropy = SystemEntropy,
    ) : this(ratchets, senderTags, entropy, LEGACY_BOX_CUTOFF)

    /** One monitor per peer user id, guarding that peer's ratchet load → mutate → save. */
    private val peerMonitors = ConcurrentHashMap<UUID, Any>()

    private inline fun <T> withPeerMonitor(peerUserId: UUID, block: () -> T): T =
        synchronized(peerMonitors.computeIfAbsent(peerUserId) { Any() }, block)

    // ---- seal ----

    /**
     * Dual-seal **v2**: a box to [peerPublic], then a box to ourselves (`seal(plaintext:toPeerIdentityPublicKey:…)`,
     * `MessageCrypto.swift:92-118`). No session state. Used for reactions (opened with
     * [openTagged]), `useRatchet = false`, and by [seal] for the higher user id before a session
     * exists. Draws peer ephemeral, peer nonce, self ephemeral, self nonce. Keys that are not
     * 32 bytes → [CryptoError.InvalidPeerKey].
     *
     * Not refused while locked (the web refuses, `messageCrypto.ts:208-216`): it reads and writes no
     * store, and the caller only holds our private key while chats are unlocked.
     *
     * @return the envelope JSON; [toWire] turns it into the server's `ciphertext`.
     */
    fun sealV2(plaintext: ByteArray, peerPublic: ByteArray, ourPrivate: ByteArray, ourPublic: ByteArray): ByteArray {
        requireKeys(peerPublic, ourPrivate, ourPublic)
        val peer = IdentityBoxes.seal(plaintext, ourPrivate, ourPublic, peerPublic, entropy)
        val self = IdentityBoxes.seal(plaintext, ourPrivate, ourPublic, ourPublic, entropy)
        return encode(SealedEnvelope.serializer(), SealedEnvelope(v = V2, peer = peer, selfBox = self))
    }

    /**
     * Seals a 1:1 message for [peerUserId] (`MessageCrypto.swift:120-199`):
     *
     * 1. `useRatchet = false` → [sealV2].
     * 2. While the ratchet store is locked, or the stored session cannot be read now →
     *    [CryptoError.Locked] (D5): the caller queues the send and retries after unlock.
     * 3. **v3** when a session exists or we are the deterministic initiator,
     *    `Ids.precedes(ourUserId, peerUserId)` (the lower lower-case id, `:150-156`); otherwise
     *    **v2**, so both sides can write first without poisoning ratchet state (dual initiator). The
     *    higher id switches to v3 once it has received, because receiving creates its session.
     *    Notes to self (equal ids) never have a session and are always v2.
     * 4. v3: the stored session (its peer key replaced if [peerPublic] changed, `:361-366`) or
     *    `DoubleRatchet.initiateAsSender`; encrypt; self box, then peer box (`:176-187`); save.
     *
     * Draws: (initiator DH private) ratchet nonce, self ephemeral, self nonce, peer ephemeral, peer
     * nonce. Keys that are not 32 bytes → [CryptoError.InvalidPeerKey], before any state changes.
     */
    fun seal(
        plaintext: ByteArray,
        peerUserId: UUID,
        peerPublic: ByteArray,
        ourPrivate: ByteArray,
        ourPublic: ByteArray,
        ourUserId: UUID,
        useRatchet: Boolean = true,
    ): ByteArray {
        if (!useRatchet) return sealV2(plaintext, peerPublic, ourPrivate, ourPublic)
        requireKeys(peerPublic, ourPrivate, ourPublic)
        if (!ratchets.isUnlocked) throw CryptoError.Locked

        val v3 = withPeerMonitor(peerUserId) {
            val existing = loadSession(peerUserId)
            // A lock that landed during the load made it read "none": refuse rather than fork.
            if (!ratchets.isUnlocked) {
                existing?.wipe()
                throw CryptoError.Locked
            }
            if (existing == null && !Ids.precedes(ourUserId, peerUserId)) return@withPeerMonitor null

            val session = existing?.also {
                if (!it.peerIdentityPublic.contentEquals(peerPublic)) it.peerIdentityPublic = peerPublic.copyOf()
            } ?: DoubleRatchet.initiateAsSender(ourPrivate, peerPublic, entropy)
            try {
                val body = DoubleRatchet.encrypt(plaintext, session, entropy)
                val self = IdentityBoxes.seal(plaintext, ourPrivate, ourPublic, ourPublic, entropy)
                val peer = IdentityBoxes.seal(plaintext, ourPrivate, ourPublic, peerPublic, entropy)
                saveSession(peerUserId, session)
                RatchetEnvelope(v = V3, dh = body.dh, n = body.n, pn = body.pn, ct = body.ct, peer = peer, selfBox = self)
            } finally {
                session.wipe()
            }
        } ?: return sealV2(plaintext, peerPublic, ourPrivate, ourPublic)

        return encode(RatchetEnvelope.serializer(), v3)
    }

    // ---- open ----

    /**
     * Opens any supported envelope (`open(envelopeData:peerUserID:…)`, `MessageCrypto.swift:254-315`).
     *
     * A v3 envelope opened as [OpenAs.Recipient] is read by the ratchet session of [peerUserId]
     * first (forward secrecy). If that fails — typically because a sibling device of ours sent and
     * the peer ratcheted to its DH key — the `peer` box is opened instead, under the untagged-box
     * policy, and the local session is left as it was. A good ratchet read still marks the sender
     * as tagging when the peer box's tag verifies (a bad tag next to a good body proves nothing).
     * Everything else goes to [openLegacy].
     *
     * Callers (messaging, `MessageDecoder.swift:186-213`): a peer's message → `Recipient`,
     * [peerUserId] = the sender, [senderPublic] = their pinned identity key; our own message or a
     * note to self → `Sender`, [senderPublic] = our public key. [sentAt] is the message's server
     * `created_at`, the time the watermark policy judges untagged boxes by.
     *
     * @throws CryptoError.UnauthenticatedSender for a tag that does not verify or an untagged box
     *   the policy refuses — the one error callers tell apart; anything else renders as
     *   "[Unable to decrypt]" (`MessageDecoder.swift:283`).
     */
    fun open(
        envelope: ByteArray,
        peerUserId: UUID,
        ourPrivate: ByteArray,
        ourPublic: ByteArray,
        senderPublic: ByteArray,
        role: OpenAs = OpenAs.Recipient,
        sentAt: Instant,
    ): ByteArray {
        val version = peekVersion(envelope) ?: throw CryptoError.OpenFailed
        if (version != V3 || role != OpenAs.Recipient) {
            return openLegacy(envelope, ourPrivate, ourPublic, senderPublic, role, sentAt)
        }

        val v3 = decode(RatchetEnvelope.serializer(), envelope)
        val plain = try {
            openRatchetV3Recipient(v3, peerUserId, ourPrivate, senderPublic)
        } catch (e: Exception) {
            val box = v3.peer ?: throw e
            return openIdentityBox(box, ourPrivate, senderPublic, ourPublic, sentAt)
        }
        val box = v3.peer
        if (box != null && runCatching { IdentityBoxes.verifyTag(box, ourPrivate, senderPublic, ourPublic) }.getOrDefault(false)) {
            senderTags.noteTagged(senderPublic, sentAt)
        }
        return plain
    }

    /**
     * Opens v1 and v2 envelopes, and the self box of a v3 envelope, without ratchet state
     * (`open(envelopeData:with:…)`, `MessageCrypto.swift:203-252`). Our own boxes ([OpenAs.Sender])
     * are sealed from and to our identity. A v3 envelope as [OpenAs.Recipient] needs the ratchet
     * and therefore [open] → [CryptoError.UnsupportedVersion] here; v1 only ever went peer-ward.
     */
    fun openLegacy(
        envelope: ByteArray,
        ourPrivate: ByteArray,
        ourPublic: ByteArray,
        senderPublic: ByteArray,
        role: OpenAs = OpenAs.Recipient,
        sentAt: Instant,
    ): ByteArray {
        val boxSender = if (role == OpenAs.Sender) ourPublic else senderPublic

        if (peekVersion(envelope) == V3) {
            if (role != OpenAs.Sender) throw CryptoError.UnsupportedVersion
            val box = decode(RatchetEnvelope.serializer(), envelope).selfBox ?: throw CryptoError.OpenFailed
            return openIdentityBox(box, ourPrivate, boxSender, ourPublic, sentAt)
        }

        val sealed = decode(SealedEnvelope.serializer(), envelope)
        val box = when (sealed.v) {
            V1 -> {
                // v1 never carried a tag (`:239-244`), so the watermark policy decides.
                val ek = sealed.ek
                val ct = sealed.ct
                if (role != OpenAs.Recipient || ek == null || ct == null) throw CryptoError.OpenFailed
                SealedBox(ek = ek, ct = ct)
            }
            V2 -> (if (role == OpenAs.Recipient) sealed.peer else sealed.selfBox) ?: throw CryptoError.OpenFailed
            else -> throw CryptoError.UnsupportedVersion
        }
        return openIdentityBox(box, ourPrivate, boxSender, ourPublic, sentAt)
    }

    /**
     * Opens a v2 envelope whose box must carry a sender tag that verifies — for records that never
     * existed untagged: reactions (`openTagged`, `MessageCrypto.swift:317-351`; web
     * `openTaggedEnvelope`, `messageCrypto.ts:236-253`). Accepting an untagged or v1 box, as history
     * must, would let the server build one from public keys alone. Touches no ratchet and no
     * watermark (no store write per record).
     *
     * @throws CryptoError.UnsupportedVersion for anything but v2, [CryptoError.UnauthenticatedSender]
     *   for an untagged box or one whose tag does not verify.
     */
    fun openTagged(envelope: ByteArray, ourPrivate: ByteArray, ourPublic: ByteArray, senderPublic: ByteArray, role: OpenAs): ByteArray {
        val sealed = decode(SealedEnvelope.serializer(), envelope)
        if (sealed.v != V2) throw CryptoError.UnsupportedVersion
        val sender = if (role == OpenAs.Sender) ourPublic else senderPublic
        val box = (if (role == OpenAs.Recipient) sealed.peer else sealed.selfBox) ?: throw CryptoError.OpenFailed
        if (!IdentityBoxes.verifyTag(box, ourPrivate, sender, ourPublic)) throw CryptoError.UnauthenticatedSender
        return IdentityBoxes.open(box, ourPrivate, sender, ourPublic)
    }

    // ---- ratchet ----

    /**
     * `openRatchetV3Recipient` (`MessageCrypto.swift:374-436`). An established session (a receiving
     * chain, or ever used) is the only candidate: never fall back to a fresh receiver once a chain
     * exists, that desyncs the ratchet (`:386-395`). Without one: an unused initiator session
     * (dual-initiator recovery) is tried after a fresh receiver; otherwise a fresh receiver alone.
     * Each attempt runs on a copy, saved only on success.
     */
    private fun openRatchetV3Recipient(v3: RatchetEnvelope, peerUserId: UUID, ourPrivate: ByteArray, senderPublic: ByteArray): ByteArray {
        if (v3.v != V3) throw CryptoError.UnsupportedVersion
        val message = DoubleRatchet.Message(v = v3.v, dh = v3.dh, n = v3.n, pn = v3.pn, ct = v3.ct)

        return withPeerMonitor(peerUserId) {
            val existing = loadSession(peerUserId)
            val candidates = ArrayList<DoubleRatchet.Session>(2)
            try {
                if (existing != null && (existing.recvChainKey != null || existing.touched)) {
                    candidates += existing
                } else {
                    if (existing != null) {
                        val unusedInitiator = existing.sendChainKey != null &&
                            existing.recvChainKey == null &&
                            existing.dhRecvPublic?.contentEquals(existing.peerIdentityPublic) == true
                        if (unusedInitiator) candidates += DoubleRatchet.prepareAsReceiver(ourPrivate, senderPublic)
                        candidates += existing
                    } else {
                        candidates += DoubleRatchet.prepareAsReceiver(ourPrivate, senderPublic)
                    }
                }
                var last: Exception = CryptoError.OpenFailed
                for (candidate in candidates) {
                    val attempt = candidate.deepCopy()
                    try {
                        val plain = DoubleRatchet.decrypt(message, attempt, entropy)
                        saveSession(peerUserId, attempt)
                        return@withPeerMonitor plain
                    } catch (e: Exception) {
                        last = e
                    } finally {
                        attempt.wipe()
                    }
                }
                throw last
            } finally {
                existing?.wipe()
                candidates.forEach { it.wipe() }
            }
        }
    }

    private fun loadSession(peerUserId: UUID): DoubleRatchet.Session? {
        val stored = ratchets.load(peerUserId) ?: return null
        try {
            return DoubleRatchet.Session.decode(stored)
        } finally {
            stored.fill(0)
        }
    }

    private fun saveSession(peerUserId: UUID, session: DoubleRatchet.Session) {
        val encoded = session.encode()
        try {
            ratchets.save(peerUserId, encoded)
        } finally {
            encoded.fill(0)
        }
    }

    // ---- sender-tag policy ----

    /**
     * Opens an identity box, then applies the untagged-box policy (`openIdentityBox`,
     * `MessageCrypto.swift:445-486`; crypto spec §3.5). Order matters — the tag first:
     *
     * - a tag that verifies moves the sender's watermark back to [sentAt] once the box opened;
     * - an untagged box opens only before [legacyBoxCutoff] and before the sender's watermark
     *   (history from before they upgraded); a locked watermark store fails closed.
     */
    private fun openIdentityBox(box: SealedBox, ourPrivate: ByteArray, senderPublic: ByteArray, recipientPublic: ByteArray, sentAt: Instant): ByteArray {
        val tagged = IdentityBoxes.verifyTag(box, ourPrivate, senderPublic, recipientPublic)
        if (!tagged) {
            legacyBoxCutoff?.let { if (!sentAt.isBefore(it)) throw CryptoError.UnauthenticatedSender }
            when (val watermark = senderTags.taggedSince(senderPublic)) {
                Watermark.Untagged -> Unit // transition: the sender is on a build that cannot tag
                Watermark.Locked -> throw CryptoError.UnauthenticatedSender
                is Watermark.Since -> if (!sentAt.isBefore(watermark.at)) throw CryptoError.UnauthenticatedSender
            }
        }
        val plain = IdentityBoxes.open(box, ourPrivate, senderPublic, recipientPublic)
        if (tagged) senderTags.noteTagged(senderPublic, sentAt)
        return plain
    }

    // ---- JSON ----

    @Serializable
    private class VersionPeek(val v: Int)

    /** `peekEnvelopeVersion` (`MessageCrypto.swift:438-441`): the `v` field, or null. */
    private fun peekVersion(envelope: ByteArray): Int? = try {
        CryptoJson.decodeFromString(VersionPeek.serializer(), String(envelope, Charsets.UTF_8)).v
    } catch (_: IllegalArgumentException) {
        null
    }

    private fun <T> decode(deserializer: DeserializationStrategy<T>, envelope: ByteArray): T = try {
        CryptoJson.decodeFromString(deserializer, String(envelope, Charsets.UTF_8))
    } catch (_: IllegalArgumentException) { // SerializationException and the UInt32 range check
        throw CryptoError.OpenFailed
    }

    private fun <T> encode(serializer: SerializationStrategy<T>, value: T): ByteArray =
        utf8(CryptoJson.encodeToString(serializer, value))

    private fun requireKeys(vararg keys: ByteArray) {
        if (keys.any { it.size != Primitives.X25519_KEY_BYTES }) throw CryptoError.InvalidPeerKey
    }

    companion object {
        private const val V1 = 1
        private const val V2 = 2
        private const val V3 = DoubleRatchet.ENVELOPE_VERSION

        /**
         * Untagged boxes from everyone are refused at or after this server time. Null until the
         * builds that cannot tag are gone (`MessageCrypto.swift:34-36`, web `LEGACY_BOX_CUTOFF`,
         * `messageCrypto.ts:90`); set it in all three clients at once (crypto spec §21).
         */
        val LEGACY_BOX_CUTOFF: Instant? = null

        /** Server limit of a message envelope, decoded (`server/…/routes/messages.rs:28`; crypto spec §4.6). */
        const val MAX_ENVELOPE_BYTES = 64 * 1024

        /** Server limit of a reaction envelope, decoded (`routes/reactions.rs:51`). */
        const val MAX_REACTION_ENVELOPE_BYTES = 4 * 1024

        /** Client cap for a media payload envelope (`MessagingController.swift:3248`, web `mediaPayload.ts:76`). */
        const val MAX_SEALED_MEDIA_ENVELOPE_BYTES = 60 * 1024

        /** Client cap for a media payload's plaintext (`MessagingController.swift:3252`, web `mediaPayload.ts:78`). */
        const val MAX_MEDIA_PAYLOAD_PLAINTEXT_BYTES = 12 * 1024

        /** The server's `ciphertext`: standard Base64 of the envelope JSON (web `envelopeToWireB64`). */
        fun toWire(envelope: ByteArray): String = B64.encode(envelope)

        /** Inverse of [toWire], with Swift `Data(base64Encoded:)` strictness; null on malformed input. */
        fun fromWire(ciphertext: String): ByteArray? = B64.decodeStrict(ciphertext)
    }
}
