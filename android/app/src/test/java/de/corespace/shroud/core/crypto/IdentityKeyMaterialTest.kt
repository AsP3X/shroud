package de.corespace.shroud.core.crypto

import de.corespace.shroud.core.keys.IdentityKeyStore
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

/**
 * Golden values for "abandon ×11 about". Computed with the web client's own libraries
 * (@noble/curves and @noble/hashes 2.x, as `web/src/crypto/identity.ts` uses them) and
 * independently in Python against RFC 7748 / RFC 8032; the iOS derivation is the same HKDF
 * recipe (`IdentityKeyMaterial.swift`). A mismatch here means Android cannot read the account.
 */
class IdentityKeyMaterialTest {
    private val bip39 = TestWordlist.bip39
    private val words = List(11) { "abandon" } + "about"

    @Test
    fun derivesTheSameKeysAsIosAndWeb() {
        val m = IdentityKeyMaterial.establish(bip39, words, "user", oneTimePreKeyCount = 3)
        assertEquals("993b9a5fc9f54c1a5aee30b3160d87c9870b3e3079656ed827c614b6599a6b24", m.agreementPublic.hex())
        assertEquals("mTuaX8n1TBpa7jCzFg2HyYcLPjB5ZW7YJ8YUtlmaayQ=", B64.encode(m.agreementPublic))
        assertEquals("5f71ef7f350fba4457721d0ff360069e2d856df8b269c32c1fe2e5247e9ee0e7", m.signingPublic.hex())
        assertEquals(8525, m.registrationId)
    }

    @Test
    fun hkdfOutputsMatch() {
        val seed = bip39.seed(words)
        assertEquals("8711f97d9c7962303dafc40a42eeff8328b64b234849d352de8798595f23afee", IdentityKeyMaterial.hkdf(seed, "shroud-identity-x25519", 32).hex())
        assertEquals("09359413c4ece0055ea4aa7d1bfc65b6bc552cb442028254d2f5110ca560a7d5", IdentityKeyMaterial.hkdf(seed, "shroud-identity-ed25519", 32).hex())
        assertEquals("34f1514472295a0c83f56c5ce19d91f9637ddce8d02dcbb2b77123b451afb8d6", IdentityKeyMaterial.hkdf(seed, "shroud-history-aes", 32).hex())
        assertEquals("beafa14d", IdentityKeyMaterial.hkdf(seed, "shroud-registration-id", 4).hex())
    }

    @Test
    fun ed25519SignatureMatchesNoble() {
        val seed = IdentityKeyMaterial.hkdf(bip39.seed(words), "shroud-identity-ed25519", 32)
        val signature = IdentityKeyMaterial.ed25519Sign(seed, ByteArray(32) { 7 })
        assertEquals(
            "6803596c918df434b2d1e0fb4482b8197e2fcfeada8db10c417ac1a8eda1f03948106564d0f71e1bb95c4ed0de233732f9fbb5ce8d7389afe1ade8f675cb0604",
            signature.hex(),
        )
    }

    @Test
    fun samePhraseSameIdentityFreshPreKeys() {
        val a = IdentityKeyMaterial.establish(bip39, words, "u", oneTimePreKeyCount = 5)
        val b = IdentityKeyMaterial.establish(bip39, words, "u", oneTimePreKeyCount = 5)
        assertTrue(a.agreementPublic.contentEquals(b.agreementPublic))
        assertTrue(a.signingPublic.contentEquals(b.signingPublic))
        assertEquals(a.registrationId, b.registrationId)
        assertFalse(a.signedPreKeyPublic.contentEquals(b.signedPreKeyPublic))
        assertTrue(a.matches(bip39, words))
        assertFalse(a.matches(bip39, List(12) { "ability" }))
    }

    @Test
    fun bundleRequestIsWhatTheServerAccepts() {
        val m = IdentityKeyMaterial.establish(bip39, bip39.generate(), "u", oneTimePreKeyCount = 100)
        val request = CryptoController.bundleRequest(m)
        assertTrue(request.registrationId in 0..16383)
        assertTrue(request.signedPreKey.keyId in 1..0xFFFFFF)
        assertEquals(100, request.oneTimePreKeys.size)
        assertEquals((1..100).toList(), request.oneTimePreKeys.map { it.keyId })
        val decoder = Base64.getDecoder()
        assertEquals(32, decoder.decode(request.identityKey).size)
        assertEquals(32, decoder.decode(request.signedPreKey.publicKey).size)
        val signature = decoder.decode(request.signedPreKey.signature)
        assertEquals(64, signature.size)
        // The signature is Ed25519 over the raw signed-prekey public key.
        val verifier = Ed25519Signer()
        verifier.init(false, Ed25519PublicKeyParameters(m.signingPublic, 0))
        val spk = decoder.decode(request.signedPreKey.publicKey)
        verifier.update(spk, 0, spk.size)
        assertTrue(verifier.verifySignature(signature))
    }

    /**
     * `IdentityKeyMaterial(stored:historyKey:)` (`IdentityKeyStore.swift:292-307`; crypto spec
     * §9.3): the stored privates with this device's prekeys, OTPKs sorted by id, every public value
     * and the signed-prekey signature recomputed.
     */
    @Test
    fun restoreRebuildsTheSameIdentityWithTheStoredPreKeys() {
        val m = IdentityKeyMaterial.establish(bip39, words, "u", oneTimePreKeyCount = 4)
        val stored = IdentityKeyStore.StoredIdentity(
            userId = "u",
            registrationId = m.registrationId,
            agreementPrivate = m.agreementPrivateKey.copyOf(),
            signingPrivate = m.signingPrivateKey.copyOf(),
            signedPreKeyId = m.signedPreKeyId,
            signedPreKeyPrivate = m.signedPreKeyPrivate.copyOf(),
            oneTimePreKeys = m.oneTimePreKeys.reversed().associate { it.keyId to it.privateKey.copyOf() },
        )
        val restored = IdentityKeyMaterial.restore(stored, m.historyKey)
        stored.wipe() // restore keeps its own copies
        assertTrue(restored.agreementPublic.contentEquals(m.agreementPublic))
        assertTrue(restored.identityPublicKey.contentEquals(m.agreementPublic))
        assertTrue(restored.signingPublic.contentEquals(m.signingPublic))
        assertTrue(restored.signedPreKeyPublic.contentEquals(m.signedPreKeyPublic))
        assertTrue(restored.historyKey.contentEquals(m.historyKey))
        assertEquals(m.signedPreKeyId, restored.signedPreKeyId)
        assertEquals(m.registrationId, restored.registrationId)
        assertEquals(listOf(1, 2, 3, 4), restored.oneTimePreKeys.map { it.keyId })
        for ((a, b) in m.oneTimePreKeys.zip(restored.oneTimePreKeys)) assertTrue(a.publicKey.contentEquals(b.publicKey))
        assertTrue(restored.matches(bip39, words))
        // BouncyCastle's Ed25519 is deterministic: the recomputed signature equals the original.
        assertTrue(restored.signedPreKeySignature.contentEquals(m.signedPreKeySignature))
    }

    /** Call secrets agree with the identity key (plan C29): X25519 both ways gives one secret. */
    @Test
    fun agreementIsX25519WithTheIdentityKey() {
        val m = IdentityKeyMaterial.establish(bip39, words, "u", oneTimePreKeyCount = 0)
        val peerPrivate = ByteArray(32) { 0x22 }
        val peerPublic = Primitives.x25519Public(peerPrivate)
        val ours = m.agreement(peerPublic)
        assertTrue(ours.contentEquals(Primitives.x25519(peerPrivate, m.identityPublicKey)))
        assertTrue(runCatching { m.agreement(ByteArray(32)) }.exceptionOrNull() === CryptoError.InvalidPeerKey)
    }

    @Test
    fun wipeZeroesEveryPrivateValue() {
        val m = IdentityKeyMaterial.establish(bip39, words, "u", oneTimePreKeyCount = 2)
        m.wipe()
        for (secret in listOf(m.agreementPrivateKey, m.signingPrivateKey, m.historyKey, m.signedPreKeyPrivate) + m.oneTimePreKeys.map { it.privateKey }) {
            assertTrue(secret.all { it == 0.toByte() })
        }
    }
}
