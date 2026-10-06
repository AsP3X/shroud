package de.corespace.shroud.core.crypto

import de.corespace.shroud.core.keys.IdentityKeyStore
import org.bouncycastle.math.ec.rfc8032.Ed25519
import java.security.SecureRandom

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
 * The signed prekey and one-time prekeys are random per establish (`IdentityKeyMaterial.swift:64-106`);
 * [restore] rebuilds the material of a stored identity with this device's prekeys
 * (`IdentityKeyStore.swift:292-307`).
 *
 * The private arrays are `internal` for the crypto layer (message crypto, call secrets, the
 * identity store) and must only be touched inside `CryptoController.withMaterial { }`, which holds
 * the read lock that [wipe] waits for (crypto spec §1.6). Never logged.
 *
 * Key-handling uses the copy-free primitives ([Primitives], BouncyCastle's static `rfc8032.Ed25519`)
 * so a wiped object leaves no unwipeable key copies behind (W0-C `Primitives` notes).
 */
class IdentityKeyMaterial private constructor(
    val userId: String,
    val registrationId: Int,
    /** Raw X25519 identity private key (message crypto, call secrets). */
    internal val agreementPrivateKey: ByteArray,
    val agreementPublic: ByteArray,
    /** Ed25519 signing seed. */
    internal val signingPrivateKey: ByteArray,
    val signingPublic: ByteArray,
    /** The 32-byte `historyKey` (HKDF info `shroud-history-aes`): every at-rest seal derives from it. */
    internal val historyKey: ByteArray,
    val signedPreKeyId: Int,
    internal val signedPreKeyPrivate: ByteArray,
    val signedPreKeyPublic: ByteArray,
    val signedPreKeySignature: ByteArray,
    val oneTimePreKeys: List<OneTimePreKey>,
) {
    class OneTimePreKey(val keyId: Int, internal val privateKey: ByteArray, val publicKey: ByteArray)

    /** The identity (agreement) public key — what the server publishes as `identity_key`. */
    val identityPublicKey: ByteArray get() = agreementPublic

    /**
     * X25519 of the identity private key with [peerPublic] (32 bytes) — the call-secret
     * agreement (plan C29). Low-order or malformed keys → [CryptoError.InvalidPeerKey]. The caller
     * owns and zeroes the result.
     */
    fun agreement(peerPublic: ByteArray): ByteArray = Primitives.x25519(agreementPrivateKey, peerPublic)

    /** Same account key as [words] would derive? (`matchesMnemonic`, `IdentityKeyMaterial.swift:108-118`). */
    fun matches(bip39: Bip39, words: List<String>): Boolean = phraseMatches(bip39, words, agreementPublic)

    /** Overwrites every private value. The object is unusable afterwards. */
    fun wipe() {
        agreementPrivateKey.fill(0)
        signingPrivateKey.fill(0)
        historyKey.fill(0)
        signedPreKeyPrivate.fill(0)
        oneTimePreKeys.forEach { it.privateKey.fill(0) }
    }

    companion object {
        private val SALT = "shroud-v1".toByteArray()

        /**
         * The 32-byte `historyKey` of [words] alone (HKDF `shroud-history-aes`, as [establish]
         * derives it), for opening device names before any identity exists here. The caller zeroes it.
         */
        fun historyKey(bip39: Bip39, words: List<String>): ByteArray {
            val seed = bip39.seed(words)
            try {
                return hkdf(seed, "shroud-history-aes", 32)
            } finally {
                seed.fill(0)
            }
        }

        /**
         * Would [words] derive the X25519 identity public key [publicKey]? (`matchesMnemonic`; web
         * `identity.ts`): the HKDF `shroud-identity-x25519` key's public half, compared in constant
         * time. Unreadable words are no match.
         */
        fun phraseMatches(bip39: Bip39, words: List<String>, publicKey: ByteArray): Boolean {
            val seed = try {
                bip39.seed(words)
            } catch (_: Exception) {
                return false
            }
            val agreementSeed = hkdf(seed, "shroud-identity-x25519", 32)
            try {
                return ctEquals(Primitives.x25519Public(agreementSeed), publicKey)
            } finally {
                seed.fill(0)
                agreementSeed.fill(0)
            }
        }

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
                val registrationRaw = hkdf(seed, "shroud-registration-id", 4)
                val registrationId = registrationId(registrationRaw)
                registrationRaw.fill(0)

                val spkPrivate = ByteArray(32).also(random::nextBytes)
                // key_id in 1…0xFFFFFF (`IdentityKeyMaterial.swift:86-87`).
                val spkId = ((random.nextInt().toLong() and 0xFFFFFFFFL) % 0xFFFFFF).toInt() + 1

                val otpks = (1..oneTimePreKeyCount).map { id ->
                    val priv = ByteArray(32).also(random::nextBytes)
                    OneTimePreKey(id, priv, x25519Public(priv))
                }
                return assemble(userId, registrationId, agreementPrivate, signingPrivate, historyKey, spkId, spkPrivate, otpks)
            } finally {
                seed.fill(0)
            }
        }

        /**
         * The material of a stored identity: the stored privates (copied — the caller may wipe
         * [stored]) with this device's signed prekey and one-time prekeys, OTPKs sorted by id, and
         * a copy of [historyKey] (`IdentityKeyMaterial(stored:historyKey:)`, `IdentityKeyStore.swift:292-307`).
         * Every public value and the signed-prekey signature are recomputed from the privates
         * (crypto spec §9.3; BouncyCastle's Ed25519 is deterministic, CryptoKit's randomised — both verify).
         */
        fun restore(stored: IdentityKeyStore.StoredIdentity, historyKey: ByteArray): IdentityKeyMaterial {
            require(historyKey.size == 32) { "the history key is 32 bytes" }
            val otpks = stored.oneTimePreKeys.entries
                .sortedBy { it.key }
                .map { (id, priv) -> OneTimePreKey(id, priv.copyOf(), x25519Public(priv)) }
            return assemble(
                userId = stored.userId,
                registrationId = stored.registrationId,
                agreementPrivate = stored.agreementPrivate.copyOf(),
                signingPrivate = stored.signingPrivate.copyOf(),
                historyKey = historyKey.copyOf(),
                spkId = stored.signedPreKeyId,
                spkPrivate = stored.signedPreKeyPrivate.copyOf(),
                otpks = otpks,
            )
        }

        private fun assemble(
            userId: String,
            registrationId: Int,
            agreementPrivate: ByteArray,
            signingPrivate: ByteArray,
            historyKey: ByteArray,
            spkId: Int,
            spkPrivate: ByteArray,
            otpks: List<OneTimePreKey>,
        ): IdentityKeyMaterial {
            val spkPublic = x25519Public(spkPrivate)
            return IdentityKeyMaterial(
                userId = userId,
                registrationId = registrationId,
                agreementPrivateKey = agreementPrivate,
                agreementPublic = x25519Public(agreementPrivate),
                signingPrivateKey = signingPrivate,
                signingPublic = ed25519Public(signingPrivate),
                historyKey = historyKey,
                signedPreKeyId = spkId,
                signedPreKeyPrivate = spkPrivate,
                signedPreKeyPublic = spkPublic,
                signedPreKeySignature = ed25519Sign(signingPrivate, spkPublic),
                oneTimePreKeys = otpks,
            )
        }

        /** HKDF-SHA256(ikm, salt "shroud-v1", info) (`IdentityKeyMaterial.swift:120-129`). */
        fun hkdf(ikm: ByteArray, info: String, length: Int): ByteArray = Primitives.hkdf(ikm, SALT, info.toByteArray(), length)

        fun registrationId(raw: ByteArray): Int {
            val value = ((raw[0].toLong() and 0xFF) shl 24) or ((raw[1].toLong() and 0xFF) shl 16) or
                ((raw[2].toLong() and 0xFF) shl 8) or (raw[3].toLong() and 0xFF)
            return (value % 16384).toInt()
        }

        fun x25519Public(privateKey: ByteArray): ByteArray = Primitives.x25519Public(privateKey)

        fun ed25519Public(seed: ByteArray): ByteArray {
            require(seed.size == Ed25519.SECRET_KEY_SIZE) { "an Ed25519 seed is 32 bytes" }
            return ByteArray(Ed25519.PUBLIC_KEY_SIZE).also { Ed25519.generatePublicKey(seed, 0, it, 0) }
        }

        fun ed25519Sign(seed: ByteArray, message: ByteArray): ByteArray {
            require(seed.size == Ed25519.SECRET_KEY_SIZE) { "an Ed25519 seed is 32 bytes" }
            return ByteArray(Ed25519.SIGNATURE_SIZE).also { Ed25519.sign(seed, 0, message, 0, message.size, it, 0) }
        }
    }
}
