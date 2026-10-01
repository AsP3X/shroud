package de.corespace.shroud.core.calls.crypto

import de.corespace.shroud.core.crypto.B64
import de.corespace.shroud.core.crypto.CryptoError
import de.corespace.shroud.core.crypto.Entropy
import de.corespace.shroud.core.crypto.Primitives
import de.corespace.shroud.core.crypto.SystemEntropy
import de.corespace.shroud.core.crypto.lexLess
import de.corespace.shroud.core.crypto.utf8
import de.corespace.shroud.core.model.Ids
import java.nio.ByteBuffer
import java.util.UUID

/**
 * Seals a call's signals (SDP, ICE candidates, media state) between the two people in it — iOS
 * `CallCrypto` (`ios/shroud/Services/Calls/CallCrypto.swift:10-139`), web `web/src/calls/crypto.ts`;
 * wire format and vector in `docs/calls.md` (calls §2.3).
 *
 * The server relays signals it cannot read or change: were the SDP readable and writable, it
 * could swap in its own DTLS fingerprint and sit in the middle of the call. The key comes from
 * both identity keys, so only the two people can seal or open.
 *
 * Every key is a fresh 32-byte array the caller owns and may zero. Nothing here logs.
 */
object CallCrypto {
    /** Who placed the call; the SENDER's role picks the direction key (`CallCrypto.swift:11-16`). */
    enum class Role(val wire: String) {
        Caller("caller"),
        Callee("callee"),
        ;

        val other: Role get() = if (this == Caller) Callee else Caller
    }

    private val SECRET_SALT = utf8("shroud-call-v1")
    private const val SECRET_INFO = "shroud-call-secret-v1"
    private const val SIGNAL_INFO = "shroud-call-signal-v1"
    private const val FORWARD_INFO = "shroud-call-fs-v1"
    private const val FORWARD_SIGNAL_INFO = "shroud-call-fs-signal-v1"
    private const val PREFIX = "c1."
    private const val KEY_BYTES = 32

    /**
     * The pair's call secret from our identity private key (`CallCrypto.swift:27-43`):
     * `HKDF-SHA256(X25519(ours, theirs), salt "shroud-call-v1", info "shroud-call-secret-v1" ‖ lo ‖ hi)`,
     * the two public keys in UNSIGNED byte order. The same from either side and on each of their
     * devices. A malformed or low-order peer key → [CryptoError.InvalidPeerKey].
     */
    fun callSecret(ourPrivateKey: ByteArray, ourPublicKey: ByteArray, peerPublicKey: ByteArray): ByteArray {
        val shared = Primitives.x25519(ourPrivateKey, peerPublicKey)
        try {
            return callSecretFromShared(shared, ourPublicKey, peerPublicKey)
        } finally {
            shared.fill(0)
        }
    }

    /**
     * [callSecret] from an X25519 result computed elsewhere — `IdentityKeyMaterial.agreement`, so
     * the identity private key never leaves `CryptoController.withMaterial` (plan C29).
     */
    fun callSecretFromShared(shared: ByteArray, ourPublicKey: ByteArray, peerPublicKey: ByteArray): ByteArray {
        val (low, high) = ordered(ourPublicKey, peerPublicKey)
        return Primitives.hkdf(shared, SECRET_SALT, utf8(SECRET_INFO) + low + high, KEY_BYTES)
    }

    /** The key [role] seals its signals with in call [callId] (`CallCrypto.swift:46-48`). */
    fun signalKey(secret: ByteArray, callId: UUID, role: Role): ByteArray = directionKey(secret, callId, role, SIGNAL_INFO)

    /**
     * The per-call secret for signals after the first offer and answer (`CallCrypto.swift:55-74`):
     * `HKDF-SHA256(X25519(our ephemeral, theirs), salt: the identity call secret,
     * info "shroud-call-fs-v1" ‖ call id bytes ‖ lo ‖ hi)`. The identity secret salts it, so a
     * swapped ephemeral key does not mix in.
     */
    fun forwardSecret(
        identitySecret: ByteArray,
        ourEphemeralPrivate: ByteArray,
        ourEphemeralPublic: ByteArray,
        peerEphemeralPublic: ByteArray,
        callId: UUID,
    ): ByteArray {
        val shared = Primitives.x25519(ourEphemeralPrivate, peerEphemeralPublic)
        try {
            val (low, high) = ordered(ourEphemeralPublic, peerEphemeralPublic)
            return Primitives.hkdf(shared, identitySecret, utf8(FORWARD_INFO) + uuidBytes(callId) + low + high, KEY_BYTES)
        } finally {
            shared.fill(0)
        }
    }

    /** The key [role] seals its post-setup signals with (`CallCrypto.swift:77-79`). */
    fun forwardSignalKey(secret: ByteArray, callId: UUID, role: Role): ByteArray =
        directionKey(secret, callId, role, FORWARD_SIGNAL_INFO)

    /** `HKDF-SHA256(secret, salt = call id bytes, info = "<info>|<role>")` (`CallCrypto.swift:81-93`). */
    private fun directionKey(secret: ByteArray, callId: UUID, role: Role, info: String): ByteArray =
        Primitives.hkdf(secret, uuidBytes(callId), utf8("$info|${role.wire}"), KEY_BYTES)

    /**
     * `"c1." + base64(nonce ‖ ciphertext ‖ tag)`, bound to the call and the signal type
     * (`CallCrypto.swift:96-111`). [nonce] pins the vector in tests; real signals draw a fresh one.
     */
    fun seal(
        plaintext: ByteArray,
        key: ByteArray,
        callId: UUID,
        signalType: String,
        nonce: ByteArray? = null,
        entropy: Entropy = SystemEntropy,
    ): String {
        val iv = nonce ?: entropy.bytes(Primitives.GCM_NONCE_BYTES)
        return PREFIX + B64.encode(Primitives.aesGcmSeal(key, iv, plaintext, aad(callId, signalType)))
    }

    /**
     * Opens [seal]'s output (`CallCrypto.swift:113-129`). No `c1.` prefix, invalid (or unpadded)
     * base64, or fewer than 28 bytes → [CallCryptoException.BadPayload]; a wrong key, call, signal
     * type or any changed byte → [CallCryptoException.OpenFailed].
     */
    fun open(payload: String, key: ByteArray, callId: UUID, signalType: String): ByteArray {
        if (!payload.startsWith(PREFIX)) throw CallCryptoException.BadPayload
        val combined = B64.decodeStrict(payload.substring(PREFIX.length)) ?: throw CallCryptoException.BadPayload
        if (combined.size < Primitives.GCM_NONCE_BYTES + Primitives.GCM_TAG_BYTES) throw CallCryptoException.BadPayload
        return try {
            Primitives.aesGcmOpen(key, combined, aad(callId, signalType))
        } catch (_: CryptoError) {
            throw CallCryptoException.OpenFailed
        }
    }

    /** `"shroud-call-v1|" + lower-case call id + "|" + signal type` (`CallCrypto.swift:131-133`). */
    fun aad(callId: UUID, signalType: String): ByteArray = utf8("shroud-call-v1|${Ids.wire(callId)}|$signalType")

    /** RFC 4122 byte order: the 32 hex digits of the string form, in order (`CallCrypto.swift:136-138`). */
    fun uuidBytes(id: UUID): ByteArray =
        ByteBuffer.allocate(16).putLong(id.mostSignificantBits).putLong(id.leastSignificantBits).array()

    /** Unsigned byte-wise order (`Data.lexicographicallyPrecedes`); equal keys keep (ours, theirs). */
    private fun ordered(ours: ByteArray, theirs: ByteArray): Pair<ByteArray, ByteArray> =
        if (lexLess(ours, theirs)) ours to theirs else theirs to ours
}

/** Why a signal did not open (`CallCrypto.CryptoError`, `CallCrypto.swift:18-21`). */
sealed class CallCryptoException(message: String) : Exception(message, null, false, false) {
    /** Not `c1.`, not base64, or too short for nonce and tag. */
    object BadPayload : CallCryptoException("bad call signal payload")

    /** The key, call, signal type or bytes do not match. */
    object OpenFailed : CallCryptoException("call signal did not open")
}

/**
 * Both keys of one call: what this device seals with, and what it opens the other side's with
 * (`CallSignalKeys`, `CallCrypto.swift:142-156`).
 */
class CallSignalKeys private constructor(val send: ByteArray, val receive: ByteArray) {
    /** Zeroes both keys; the object is unusable afterwards. */
    fun wipe() {
        send.fill(0)
        receive.fill(0)
    }

    companion object {
        /** Setup directions from the identity call secret. */
        fun identity(secret: ByteArray, callId: UUID, role: CallCrypto.Role): CallSignalKeys =
            CallSignalKeys(CallCrypto.signalKey(secret, callId, role), CallCrypto.signalKey(secret, callId, role.other))

        /** Post-setup directions; [forwardSecret] is the forward secret, not the identity call secret. */
        fun forward(forwardSecret: ByteArray, callId: UUID, role: CallCrypto.Role): CallSignalKeys =
            CallSignalKeys(
                CallCrypto.forwardSignalKey(forwardSecret, callId, role),
                CallCrypto.forwardSignalKey(forwardSecret, callId, role.other),
            )
    }
}
