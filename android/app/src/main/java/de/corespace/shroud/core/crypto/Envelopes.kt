package de.corespace.shroud.core.crypto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/*
 * Wire shapes of 1:1 message envelopes and the identity boxes inside them (iOS
 * `ios/shroud/Services/Crypto/MessageCrypto.swift:22-87`, web `web/src/crypto/messageCrypto.ts:17-33`,
 * `sealedBox.ts:16`; crypto spec §3, §4.1). The server stores `b64(envelope JSON)` as a message's
 * `ciphertext` (`MessageCrypto.toWire`).
 *
 * | `v` | contents | written by |
 * | --- | --- | --- |
 * | 1 | `ek`, `ct` at the top level: one untagged box to the peer | nothing any more; still read |
 * | 2 | `peer` box + `self` box | the higher user id until a ratchet session exists, `useRatchet = false`, reactions, notes to self |
 * | 3 | ratchet `dh`, `n`, `pn`, `ct` + `peer` + `self` boxes | everything else |
 *
 * Encoded with [CryptoJson] (nil optionals left out, as Swift's `encodeIfPresent`). Swift writes
 * keys in no fixed order and escapes `/` as `\/`; nothing here depends on either.
 */

/**
 * One identity box: ephemeral X25519 to the recipient, AES-256-GCM, and `t`, the sender tag
 * (`MessageCrypto.swift:24-32`). All three fields are standard Base64.
 *
 * @property ek the ephemeral X25519 public key (32 bytes).
 * @property ct AES-GCM `nonce (12) ‖ ciphertext ‖ tag (16)`.
 * @property t HMAC-SHA256 over `"shroud-box-tag-v1" ‖ ek ‖ ct` under the sender↔recipient identity
 *   key; null on boxes from builds before the tag (`:29-31`).
 */
@Serializable
data class SealedBox(val ek: String, val ct: String, val t: String? = null)

/**
 * v1 (`ek`, `ct` at the top level, peer only, never tagged) and v2 (`peer` + `self` boxes)
 * envelopes (`MessageCrypto.swift:39-51`).
 */
@Serializable
data class SealedEnvelope(
    val v: Int,
    val ek: String? = null,
    val ct: String? = null,
    val peer: SealedBox? = null,
    @SerialName("self") val selfBox: SealedBox? = null,
)

/**
 * v3: the Double Ratchet header and body next to the identity boxes for each side's other devices
 * (`MessageCrypto.swift:72-87`). [n] and [pn] are Swift `UInt32`s: anything outside
 * `0..4294967295` fails to decode, as `JSONDecoder` fails (crypto spec §1.4).
 */
@Serializable
data class RatchetEnvelope(
    val v: Int,
    val dh: String,
    val n: Long,
    val pn: Long,
    val ct: String,
    /** Recipient identity box: the peer's other devices open it without ratchet state. */
    val peer: SealedBox? = null,
    @SerialName("self") val selfBox: SealedBox? = null,
) {
    init {
        require(n in 0..UINT32_MAX && pn in 0..UINT32_MAX) { "ratchet counter out of range" }
    }
}

internal const val UINT32_MAX = 0xFFFF_FFFFL

/**
 * The box primitives under every envelope version (iOS `MessageCrypto.swift:490-645`, web
 * `sealedBox.ts`; crypto spec §3.2–3.4):
 *
 * ```
 * key = HKDF(ECDH(e, rPub), salt "shroud-v1", info "shroud-msg-v1" ‖ ek ‖ sPub ‖ rPub, 32)
 * ct  = AES-256-GCM(key, nonce, plaintext)                         (combined)
 * tk  = HKDF(ECDH(sPriv, rPub), salt "shroud-box-auth-v1", info "shroud-box-auth-v1" ‖ sPub ‖ rPub, 32)
 * t   = HMAC-SHA256(tk, "shroud-box-tag-v1" ‖ ek ‖ ct)             (raw bytes, not Base64)
 * ```
 *
 * The box key is ECDH(ephemeral, recipient) alone, so anyone holding the two public keys — the
 * server included — can build a box that opens; the tag is what names the sender. The ordered
 * public keys in the tag key's info make A→B differ from B→A, so a box cannot be reflected
 * (`:581-582`). Every derived key and shared secret is zeroed after use; nothing is logged.
 */
internal object IdentityBoxes {
    private val MSG_SALT = utf8("shroud-v1")
    private val MSG_INFO = utf8("shroud-msg-v1")
    private val AUTH_LABEL = utf8("shroud-box-auth-v1")
    private val TAG_LABEL = utf8("shroud-box-tag-v1")

    /**
     * Seals one box from the sender identity to [recipientPublic] (`sealBox`,
     * `MessageCrypto.swift:490-530`). Draws the 32-byte ephemeral private first, then the 12-byte
     * GCM nonce (crypto spec §1.3). A [recipientPublic] that is not 32 bytes, or a low-order point,
     * → [CryptoError.InvalidPeerKey] (`:496-500`).
     */
    fun seal(
        plaintext: ByteArray,
        senderPrivate: ByteArray,
        senderPublic: ByteArray,
        recipientPublic: ByteArray,
        entropy: Entropy,
    ): SealedBox {
        if (recipientPublic.size != Primitives.X25519_KEY_BYTES) throw CryptoError.InvalidPeerKey
        val ephemeral = entropy.bytes(Primitives.X25519_KEY_BYTES)
        try {
            val ek = Primitives.x25519Public(ephemeral)
            val key = messageKey(Primitives.x25519(ephemeral, recipientPublic), ek, senderPublic, recipientPublic)
            val ct = try {
                Primitives.aesGcmSeal(key, entropy.bytes(Primitives.GCM_NONCE_BYTES), plaintext)
            } finally {
                key.fill(0)
            }
            val tag = tag(senderPrivate, recipientPublic, senderPublic, recipientPublic, ek, ct)
            return SealedBox(ek = B64.encode(ek), ct = B64.encode(ct), t = B64.encode(tag))
        } finally {
            ephemeral.fill(0)
        }
    }

    /**
     * `true` when the tag proves [senderPublic] sealed the box, `false` for an untagged box
     * (`verifyBoxTag`, `MessageCrypto.swift:534-559`). A tag that does not verify, or a field that
     * is not strict Base64, → [CryptoError.UnauthenticatedSender]: a box that carries a tag is never
     * read without it. An invalid [senderPublic] → [CryptoError.InvalidPeerKey].
     */
    fun verifyTag(box: SealedBox, ourPrivate: ByteArray, senderPublic: ByteArray, recipientPublic: ByteArray): Boolean {
        val t = box.t ?: return false
        val tag = B64.decodeStrict(t)
        val ek = B64.decodeStrict(box.ek)
        val ct = B64.decodeStrict(box.ct)
        if (tag == null || ek == null || ct == null) throw CryptoError.UnauthenticatedSender
        // Our private with the sender's public is the sender's private with ours (`:545-550`).
        val expected = tag(ourPrivate, senderPublic, senderPublic, recipientPublic, ek, ct)
        // Constant time (`HMAC.isValidAuthenticationCode`, `:554-557`).
        if (!ctEquals(expected, tag)) throw CryptoError.UnauthenticatedSender
        return true
    }

    /**
     * Opens a box with our private key, without looking at its tag (`openBox`,
     * `MessageCrypto.swift:603-626`). Every failure — fields that are not strict Base64, an `ek`
     * that is not a valid key, a wrong key or tampered bytes — is [CryptoError.OpenFailed].
     */
    fun open(box: SealedBox, ourPrivate: ByteArray, senderPublic: ByteArray, recipientPublic: ByteArray): ByteArray {
        val ek = B64.decodeStrict(box.ek)
        val ct = B64.decodeStrict(box.ct)
        if (ek == null || ct == null || ek.size != Primitives.X25519_KEY_BYTES) throw CryptoError.OpenFailed
        val shared = try {
            Primitives.x25519(ourPrivate, ek)
        } catch (_: CryptoError) {
            throw CryptoError.OpenFailed
        }
        val key = messageKey(shared, ek, senderPublic, recipientPublic)
        try {
            return Primitives.aesGcmOpen(key, ct)
        } finally {
            key.fill(0)
        }
    }

    /** `deriveMessageKey` (`MessageCrypto.swift:628-645`). Zeroes [shared]. */
    private fun messageKey(shared: ByteArray, ek: ByteArray, senderPublic: ByteArray, recipientPublic: ByteArray): ByteArray {
        try {
            return Primitives.hkdf(shared, MSG_SALT, MSG_INFO + ek + senderPublic + recipientPublic, 32)
        } finally {
            shared.fill(0)
        }
    }

    /**
     * `boxTag` / `boxTagKey` (`MessageCrypto.swift:561-601`): the tag key comes from the static ECDH
     * of the two identity keys, the same from either end.
     */
    private fun tag(
        ourPrivate: ByteArray,
        theirPublic: ByteArray,
        senderPublic: ByteArray,
        recipientPublic: ByteArray,
        ek: ByteArray,
        ct: ByteArray,
    ): ByteArray {
        if (theirPublic.size != Primitives.X25519_KEY_BYTES) throw CryptoError.InvalidPeerKey
        val shared = Primitives.x25519(ourPrivate, theirPublic)
        val key = try {
            Primitives.hkdf(shared, AUTH_LABEL, AUTH_LABEL + senderPublic + recipientPublic, 32)
        } finally {
            shared.fill(0)
        }
        try {
            return Primitives.hmacSha256(key, TAG_LABEL + ek + ct)
        } finally {
            key.fill(0)
        }
    }
}
