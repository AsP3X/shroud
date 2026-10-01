package de.corespace.shroud.core.crypto

import org.bouncycastle.crypto.digests.SHA256Digest
import org.bouncycastle.math.ec.rfc7748.X25519
import java.security.GeneralSecurityException
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * The CryptoKit operations the iOS app uses, on BouncyCastle and the JCA (crypto spec §1.2).
 * Pure JVM, so every caller is unit-testable; nothing here touches the Android Keystore.
 *
 * | iOS (CryptoKit) | here |
 * | --- | --- |
 * | `Curve25519.KeyAgreement.PrivateKey(rawRepresentation:).publicKey` | [x25519Public] |
 * | `sharedSecretFromKeyAgreement(with:)` | [x25519] |
 * | `HKDF<SHA256>.deriveKey`, `SharedSecret.hkdfDerivedSymmetricKey` | [hkdf] |
 * | `HMAC<SHA256>` | [hmacSha256] |
 * | `SHA256.hash` | [sha256] |
 * | `AES.GCM.seal(_:using:nonce:authenticating:)` → `.combined` | [aesGcmSeal] |
 * | `AES.GCM.open(SealedBox(combined:), using:authenticating:)` | [aesGcmOpen] |
 *
 * Never logs key material; callers own (and zero) the arrays they pass in. Keys must not outlive
 * a lock in RAM (plan §1.4, invariant 5), so the key-handling paths avoid library objects that copy
 * a key and cannot be wiped:
 * - [hkdf] and [hmacSha256] run HMAC on one plain `SHA256Digest` ([HmacSha256]) instead of
 *   BouncyCastle's `HMac`/`HKDFBytesGenerator`: those clone the key into `KeyParameter`s and
 *   `HKDFParameters` and memoise key-equivalent digest states that nobody can zero. Here every
 *   key-derived array (the padded key, the PRK, the expand blocks) is ours and zeroed in `finally`,
 *   and the digest's own state is reset; `SHA256Digest` clears its block buffer after each block.
 * - [x25519Public] and [x25519] call the static `rfc7748.X25519` functions on the caller's arrays,
 *   not `X25519PrivateKeyParameters`, which keeps an unwipeable copy of the private key.
 *
 * Accepted residuals: `rfc7748.X25519` decodes the scalar into a local `int[8]` it does not zero;
 * [aesGcmSeal]/[aesGcmOpen] go through the JCA, whose `SecretKeySpec` clones the key and whose
 * provider (Conscrypt on a device) expands its own key schedule, none of which can be destroyed
 * from here. These copies are unreachable garbage once the call returns and are overwritten as the
 * heap is reused; they are not kept by anything.
 */
object Primitives {
    const val X25519_KEY_BYTES = 32
    const val GCM_NONCE_BYTES = 12
    const val GCM_TAG_BYTES = 16

    /**
     * The X25519 public key of a 32-byte private key (clamping happens inside BouncyCastle, as
     * in CryptoKit and noble). Any other length → [CryptoError.InvalidPeerKey]: BouncyCastle
     * would silently read the first 32 bytes of a longer array (crypto spec §1.2).
     */
    fun x25519Public(privateKey: ByteArray): ByteArray {
        if (privateKey.size != X25519_KEY_BYTES) throw CryptoError.InvalidPeerKey
        return ByteArray(X25519_KEY_BYTES).also { X25519.generatePublicKey(privateKey, 0, it, 0) }
    }

    /**
     * Raw X25519 shared secret (iOS `sharedSecretFromKeyAgreement`, used by
     * `MessageCrypto.swift:502` and `:616`). Both keys must be exactly 32 bytes. A low-order
     * peer point (all-zero result) is refused like CryptoKit and noble refuse it. Every failure is
     * [CryptoError.InvalidPeerKey]; the open side maps it to [CryptoError.OpenFailed]
     * (`MessageCrypto.swift:496-500` vs `:609-614`).
     */
    fun x25519(privateKey: ByteArray, publicKey: ByteArray): ByteArray {
        if (privateKey.size != X25519_KEY_BYTES || publicKey.size != X25519_KEY_BYTES) {
            throw CryptoError.InvalidPeerKey
        }
        val out = ByteArray(X25519_KEY_BYTES)
        // False for the all-zero secret of a low-order point.
        val agreed = X25519.calculateAgreement(privateKey, 0, publicKey, 0, out, 0)
        // Defence in depth: never hand out an all-zero secret, whatever the library did.
        var acc = 0
        for (b in out) acc = acc or b.toInt()
        if (!agreed || acc == 0) {
            out.fill(0)
            throw CryptoError.InvalidPeerKey
        }
        return out
    }

    /**
     * HKDF-SHA256 (RFC 5869) with an explicit salt; an empty salt means HashLen zero bytes, as in
     * the RFC and CryptoKit. [length] ≤ 255 × 32.
     */
    fun hkdf(ikm: ByteArray, salt: ByteArray, info: ByteArray, length: Int): ByteArray {
        require(length in 1..255 * SHA256_BYTES) { "HKDF length out of range" }
        // Extract. An empty salt pads to the same 64 zero bytes as HashLen zeros (RFC 5869 §2.2).
        val prk = ByteArray(SHA256_BYTES)
        val extract = HmacSha256(salt)
        try {
            extract.update(ikm)
            extract.doFinal(prk)
        } finally {
            extract.wipe()
        }
        // Expand: T(i) = HMAC(PRK, T(i-1) ‖ info ‖ i).
        val okm = ByteArray(length)
        val block = ByteArray(SHA256_BYTES)
        val expand = HmacSha256(prk)
        try {
            var produced = 0
            var counter = 1
            while (produced < length) {
                if (counter > 1) expand.update(block)
                expand.update(info)
                expand.update(byteArrayOf(counter.toByte()))
                expand.doFinal(block)
                val n = minOf(SHA256_BYTES, length - produced)
                block.copyInto(okm, produced, 0, n)
                produced += n
                counter++
            }
        } finally {
            expand.wipe()
            prk.fill(0)
            block.fill(0)
        }
        return okm
    }

    /** HMAC-SHA256. Any key length works, the empty key included (CryptoKit allows it, JCA does not). */
    fun hmacSha256(key: ByteArray, data: ByteArray): ByteArray {
        val mac = HmacSha256(key)
        try {
            mac.update(data)
            return ByteArray(SHA256_BYTES).also { mac.doFinal(it) }
        } finally {
            mac.wipe()
        }
    }

    fun sha256(data: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(data)

    /**
     * AES-GCM with a 128-bit tag; returns CryptoKit's `combined` layout `nonce (12) ‖ ciphertext ‖
     * tag (16)`. The key is 32 bytes for every Shroud store and envelope (16 and 24 are accepted,
     * as CryptoKit accepts them). The caller draws [nonce] from its [Entropy] so golden vectors can
     * pin it; it must never repeat under one key. Empty [plaintext] is legal here (message and media
     * GCM); [LocalHistoryCrypto] adds its own rule. A bad key or nonce size →
     * [CryptoError.SealingFailed].
     */
    fun aesGcmSeal(key: ByteArray, nonce: ByteArray, plaintext: ByteArray, aad: ByteArray = ByteArray(0)): ByteArray {
        if (!isAesKeySize(key.size) || nonce.size != GCM_NONCE_BYTES) throw CryptoError.SealingFailed
        val sealed = try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(GCM_TAG_BYTES * 8, nonce))
            if (aad.isNotEmpty()) cipher.updateAAD(aad)
            cipher.doFinal(plaintext)
        } catch (_: GeneralSecurityException) {
            throw CryptoError.SealingFailed
        }
        return nonce + sealed
    }

    /**
     * Opens [aesGcmSeal]'s `combined` output. Shorter than 28 bytes (CryptoKit's
     * `SealedBox(combined:)` minimum), a wrong key, a wrong [aad] or any tampered byte →
     * [CryptoError.OpenFailed]; the JCA exception is not passed on (crypto spec §1.5).
     */
    fun aesGcmOpen(key: ByteArray, combined: ByteArray, aad: ByteArray = ByteArray(0)): ByteArray {
        if (!isAesKeySize(key.size) || combined.size < GCM_NONCE_BYTES + GCM_TAG_BYTES) throw CryptoError.OpenFailed
        return try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(
                Cipher.DECRYPT_MODE,
                SecretKeySpec(key, "AES"),
                GCMParameterSpec(GCM_TAG_BYTES * 8, combined, 0, GCM_NONCE_BYTES),
            )
            if (aad.isNotEmpty()) cipher.updateAAD(aad)
            cipher.doFinal(combined, GCM_NONCE_BYTES, combined.size - GCM_NONCE_BYTES)
        } catch (_: GeneralSecurityException) {
            throw CryptoError.OpenFailed
        } catch (_: IllegalArgumentException) {
            throw CryptoError.OpenFailed
        } catch (_: IllegalStateException) {
            throw CryptoError.OpenFailed
        }
    }

    private fun isAesKeySize(size: Int) = size == 16 || size == 24 || size == 32

    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val SHA256_BYTES = 32
    private const val SHA256_BLOCK = 64

    /**
     * HMAC-SHA256 (RFC 2104) on one `SHA256Digest`, holding the padded key only in arrays it owns.
     * [doFinal] leaves it ready for the next message under the same key; [wipe] zeroes the padded
     * key and scratch and resets the digest's key-dependent midstate. Not thread-safe; one per call.
     */
    private class HmacSha256(key: ByteArray) {
        private val digest = SHA256Digest()
        private val paddedKey = ByteArray(SHA256_BLOCK)
        private val pad = ByteArray(SHA256_BLOCK)
        private val inner = ByteArray(SHA256_BYTES)

        init {
            if (key.size > SHA256_BLOCK) {
                digest.update(key, 0, key.size)
                digest.doFinal(paddedKey, 0) // the rest stays zero
            } else {
                key.copyInto(paddedKey)
            }
            begin()
        }

        private fun begin() {
            for (i in 0 until SHA256_BLOCK) pad[i] = (paddedKey[i].toInt() xor 0x36).toByte()
            digest.update(pad, 0, SHA256_BLOCK)
        }

        fun update(data: ByteArray) = digest.update(data, 0, data.size)

        fun doFinal(out: ByteArray) {
            digest.doFinal(inner, 0)
            for (i in 0 until SHA256_BLOCK) pad[i] = (paddedKey[i].toInt() xor 0x5c).toByte()
            digest.update(pad, 0, SHA256_BLOCK)
            digest.update(inner, 0, SHA256_BYTES)
            digest.doFinal(out, 0)
            begin()
        }

        fun wipe() {
            paddedKey.fill(0)
            pad.fill(0)
            inner.fill(0)
            digest.reset()
        }
    }
}
