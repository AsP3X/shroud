package de.corespace.shroud.core.crypto

import org.bouncycastle.crypto.digests.SHA256Digest
import org.bouncycastle.crypto.generators.HKDFBytesGenerator
import org.bouncycastle.crypto.macs.HMac
import org.bouncycastle.crypto.params.HKDFParameters
import org.bouncycastle.crypto.params.KeyParameter
import org.bouncycastle.crypto.params.X25519PrivateKeyParameters
import org.bouncycastle.crypto.params.X25519PublicKeyParameters
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
 * Never logs or keeps key material; callers own (and zero) the arrays they pass in.
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
        return X25519PrivateKeyParameters(privateKey, 0).generatePublicKey().encoded
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
        try {
            X25519PrivateKeyParameters(privateKey, 0)
                .generateSecret(X25519PublicKeyParameters(publicKey, 0), out, 0)
        } catch (_: IllegalStateException) {
            // BouncyCastle: "X25519 agreement failed" — the all-zero secret of a low-order point.
            throw CryptoError.InvalidPeerKey
        }
        // Defence in depth: never hand out an all-zero secret, whatever the library did.
        var acc = 0
        for (b in out) acc = acc or b.toInt()
        if (acc == 0) throw CryptoError.InvalidPeerKey
        return out
    }

    /**
     * HKDF-SHA256 (RFC 5869) with an explicit salt; an empty salt means HashLen zero bytes, as in
     * the RFC and CryptoKit. [length] ≤ 255 × 32.
     */
    fun hkdf(ikm: ByteArray, salt: ByteArray, info: ByteArray, length: Int): ByteArray {
        require(length in 1..255 * 32) { "HKDF length out of range" }
        val generator = HKDFBytesGenerator(SHA256Digest())
        generator.init(HKDFParameters(ikm, salt, info))
        return ByteArray(length).also { generator.generateBytes(it, 0, length) }
    }

    /** HMAC-SHA256. Any key length works, the empty key included (CryptoKit allows it, JCA does not). */
    fun hmacSha256(key: ByteArray, data: ByteArray): ByteArray {
        val mac = HMac(SHA256Digest())
        mac.init(KeyParameter(key))
        mac.update(data, 0, data.size)
        return ByteArray(mac.macSize).also { mac.doFinal(it, 0) }
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
}
