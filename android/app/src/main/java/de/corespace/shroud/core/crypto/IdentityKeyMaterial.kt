package de.corespace.shroud.core.crypto

import org.bouncycastle.crypto.digests.SHA256Digest
import org.bouncycastle.crypto.generators.HKDFBytesGenerator
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.params.HKDFParameters
import org.bouncycastle.crypto.params.X25519PrivateKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer
import java.security.SecureRandom
import java.util.Arrays

/**
 * The account's keys, derived from the phrase (`IdentityKeyMaterial.swift`,
 * `web/src/crypto/identity.ts`). Every value below is HKDF-SHA256(seed, salt "shroud-v1", info):
 *
 * | info | bytes | use |
 * | --- | --- | --- |
 * | shroud-identity-x25519 | 32 | identity (agreement) private key |
 * | shroud-identity-ed25519 | 32 | signing private seed |
 * | shroud-history-aes | 32 | `historyKey` (at-rest sealing) |
 * | shroud-registration-id | 4 | big-endian u32 % 16384 |
 *
 * The signed prekey and one-time prekeys are random per establish.
 */
class IdentityKeyMaterial private constructor(
    val userId: String,
    val registrationId: Int,
    private val agreementPrivate: ByteArray,
    val agreementPublic: ByteArray,
    private val signingPrivate: ByteArray,
    val signingPublic: ByteArray,
    private val historyKeyBytes: ByteArray,
    val signedPreKeyId: Int,
    private val signedPreKeyPrivate: ByteArray,
    val signedPreKeyPublic: ByteArray,
    val signedPreKeySignature: ByteArray,
    val oneTimePreKeys: List<OneTimePreKey>,
) {
    class OneTimePreKey(val keyId: Int, internal val privateKey: ByteArray, val publicKey: ByteArray)

    /** Same account key as [words] would derive? (`matchesMnemonic`). */
    fun matches(bip39: Bip39, words: List<String>): Boolean = runCatching {
        val seed = bip39.seed(words)
        Arrays.equals(x25519Public(hkdf(seed, "shroud-identity-x25519", 32)), agreementPublic)
    }.getOrDefault(false)

    /** Overwrites every private value. The object is unusable afterwards. */
    fun wipe() {
        agreementPrivate.fill(0)
        signingPrivate.fill(0)
        historyKeyBytes.fill(0)
        signedPreKeyPrivate.fill(0)
        oneTimePreKeys.forEach { it.privateKey.fill(0) }
    }

    companion object {
        private val SALT = "shroud-v1".toByteArray()

        fun establish(
            bip39: Bip39,
            words: List<String>,
            userId: String,
            oneTimePreKeyCount: Int = 100,
            random: SecureRandom = SecureRandom(),
        ): IdentityKeyMaterial {
            val seed = bip39.seed(words)
            try {
                val agreementPrivate = hkdf(seed, "shroud-identity-x25519", 32)
                val signingPrivate = hkdf(seed, "shroud-identity-ed25519", 32)
                val historyKey = hkdf(seed, "shroud-history-aes", 32)
                val registrationId = registrationId(hkdf(seed, "shroud-registration-id", 4))

                val spkPrivate = ByteArray(32).also(random::nextBytes)
                val spkPublic = x25519Public(spkPrivate)
                val spkId = ((random.nextInt().toLong() and 0xFFFFFFFFL) % 0xFFFFFF).toInt() + 1

                val otpks = (1..oneTimePreKeyCount).map { id ->
                    val priv = ByteArray(32).also(random::nextBytes)
                    OneTimePreKey(id, priv, x25519Public(priv))
                }
                return IdentityKeyMaterial(
                    userId = userId,
                    registrationId = registrationId,
                    agreementPrivate = agreementPrivate,
                    agreementPublic = x25519Public(agreementPrivate),
                    signingPrivate = signingPrivate,
                    signingPublic = Ed25519PrivateKeyParameters(signingPrivate, 0).generatePublicKey().encoded,
                    historyKeyBytes = historyKey,
                    signedPreKeyId = spkId,
                    signedPreKeyPrivate = spkPrivate,
                    signedPreKeyPublic = spkPublic,
                    signedPreKeySignature = ed25519Sign(signingPrivate, spkPublic),
                    oneTimePreKeys = otpks,
                )
            } finally {
                seed.fill(0)
            }
        }

        fun hkdf(ikm: ByteArray, info: String, length: Int): ByteArray {
            val generator = HKDFBytesGenerator(SHA256Digest())
            generator.init(HKDFParameters(ikm, SALT, info.toByteArray()))
            return ByteArray(length).also { generator.generateBytes(it, 0, length) }
        }

        fun registrationId(raw: ByteArray): Int {
            val value = ((raw[0].toLong() and 0xFF) shl 24) or ((raw[1].toLong() and 0xFF) shl 16) or
                ((raw[2].toLong() and 0xFF) shl 8) or (raw[3].toLong() and 0xFF)
            return (value % 16384).toInt()
        }

        fun x25519Public(privateKey: ByteArray): ByteArray =
            X25519PrivateKeyParameters(privateKey, 0).generatePublicKey().encoded

        fun ed25519Sign(seed: ByteArray, message: ByteArray): ByteArray {
            val signer = Ed25519Signer()
            signer.init(true, Ed25519PrivateKeyParameters(seed, 0))
            signer.update(message, 0, message.size)
            return signer.generateSignature()
        }
    }
}
