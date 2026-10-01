package de.corespace.shroud.core.crypto

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * CryptoKit-equivalent primitives (crypto spec §1.2). The Shroud vectors are crypto spec §16.3
 * (computed with the web client's @noble libraries, mirroring iOS); the RFC vectors pin the
 * algorithms themselves. All values were re-checked against Node's OpenSSL.
 */
class PrimitivesTest {
    // ---- X25519 ----

    @Test
    fun x25519PublicMatchesCryptoSpecVectors() {
        val alicePub = Primitives.x25519Public(ByteArray(32) { 0x11 })
        val bobPub = Primitives.x25519Public(ByteArray(32) { 0x22 })
        assertEquals(ALICE_PUB, alicePub.hex())
        assertEquals("e06Qm75//kTEZaIgA31gjuNYl9Me+XLwf3SJLLD3PxM=", B64.encode(alicePub))
        assertEquals(BOB_PUB, bobPub.hex())
        assertEquals("D6poTtKIZ7l/Smot7l34zpdOdrcBjj8iocTPJnhXDyA=", B64.encode(bobPub))
    }

    @Test
    fun x25519AgreementIsSymmetricAndMatchesCryptoSpec() {
        val alice = ByteArray(32) { 0x11 }
        val bob = ByteArray(32) { 0x22 }
        val ab = Primitives.x25519(alice, hexToBytes(BOB_PUB))
        val ba = Primitives.x25519(bob, hexToBytes(ALICE_PUB))
        assertEquals("9e004098efc091d4ec2663b4e9f5cfd4d7064571690b4bea97ab146ab9f35056", ab.hex())
        assertArrayEquals(ab, ba)
    }

    @Test
    fun x25519MatchesRfc7748Section6_1() {
        val alicePriv = hexToBytes("77076d0a7318a57d3c16c17251b26645df4c2f87ebc0992ab177fba51db92c2a")
        val bobPriv = hexToBytes("5dab087e624a8a4b79e17f8b83800ee66f3bb1292618b6fd1c2f8b27ff88e0eb")
        val alicePub = Primitives.x25519Public(alicePriv)
        val bobPub = Primitives.x25519Public(bobPriv)
        assertEquals("8520f0098930a754748b7ddcb43ef75a0dbf3a0d26381af4eba4a98eaa9b4e6a", alicePub.hex())
        assertEquals("de9edb7d7b7dc1b4d35b61c2ece435373f8343c85b78674dadfc7e146f882b4f", bobPub.hex())
        val shared = "4a5d9d5ba4ce2de1728e3bf480350f25e07e21c947d19e3376f09b3c1e161742"
        assertEquals(shared, Primitives.x25519(alicePriv, bobPub).hex())
        assertEquals(shared, Primitives.x25519(bobPriv, alicePub).hex())
    }

    @Test
    fun lowOrderPointsAreRefused() {
        // Points whose agreement is all zeros (libsodium's blocklist); CryptoKit and noble refuse them.
        val lowOrder = listOf(
            "0000000000000000000000000000000000000000000000000000000000000000",
            "0100000000000000000000000000000000000000000000000000000000000000",
            "e0eb7a7c3b41b8ae1656e3faf19fc46ada098deb9c32b1fd866205165f49b800",
            "5f9c95bca3508c24b1d0b1559c83ef5b04445cc4581c8e86d8224eddd09f1157",
            "ecffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff7f",
        )
        val priv = ByteArray(32) { 0x11 }
        for (point in lowOrder) {
            val error = assertThrows(CryptoError.InvalidPeerKey::class.java) { Primitives.x25519(priv, hexToBytes(point)) }
            assertSame(CryptoError.InvalidPeerKey, error)
        }
    }

    @Test
    fun keysMustBeExactly32Bytes() {
        val priv = ByteArray(32) { 0x11 }
        val pub = hexToBytes(BOB_PUB)
        // BouncyCastle alone would read the first 32 bytes of the longer array and succeed.
        assertThrows(CryptoError.InvalidPeerKey::class.java) { Primitives.x25519(priv, pub + byteArrayOf(0)) }
        assertThrows(CryptoError.InvalidPeerKey::class.java) { Primitives.x25519(priv, pub.copyOf(31)) }
        assertThrows(CryptoError.InvalidPeerKey::class.java) { Primitives.x25519(priv, ByteArray(0)) }
        assertThrows(CryptoError.InvalidPeerKey::class.java) { Primitives.x25519(priv + byteArrayOf(0), pub) }
        assertThrows(CryptoError.InvalidPeerKey::class.java) { Primitives.x25519(priv.copyOf(31), pub) }
        assertThrows(CryptoError.InvalidPeerKey::class.java) { Primitives.x25519Public(ByteArray(31)) }
        assertThrows(CryptoError.InvalidPeerKey::class.java) { Primitives.x25519Public(ByteArray(33)) }
    }

    // ---- HKDF, HMAC, SHA-256 ----

    @Test
    fun hkdfMatchesRfc5869TestCase1() {
        val okm = Primitives.hkdf(
            ikm = ByteArray(22) { 0x0b },
            salt = hexToBytes("000102030405060708090a0b0c"),
            info = hexToBytes("f0f1f2f3f4f5f6f7f8f9"),
            length = 42,
        )
        assertEquals("3cb25f25faacd57a90434f64d0362f2a2d2d0a90cf1a5a4c5db02d56ecc4c5bf34007208d5b887185865", okm.hex())
    }

    @Test
    fun hkdfWithEmptySaltAndInfoMatchesRfc5869TestCase3() {
        val okm = Primitives.hkdf(ByteArray(22) { 0x0b }, ByteArray(0), ByteArray(0), 42)
        assertEquals("8da4e775a563c18f715f802a063c5a31b8a11f5c5ee1879ec3454e5f3c738d2d9d201395faa4b61a96c8", okm.hex())
    }

    @Test
    fun hkdfRejectsOutOfRangeLengths() {
        assertThrows(IllegalArgumentException::class.java) { Primitives.hkdf(ByteArray(32), ByteArray(0), ByteArray(0), 0) }
        assertThrows(IllegalArgumentException::class.java) { Primitives.hkdf(ByteArray(32), ByteArray(0), ByteArray(0), 255 * 32 + 1) }
        assertEquals(255 * 32, Primitives.hkdf(ByteArray(32), ByteArray(0), ByteArray(0), 255 * 32).size)
    }

    @Test
    fun hmacSha256MatchesRfc4231() {
        assertEquals(
            "b0344c61d8db38535ca8afceaf0bf12b881dc200c9833da726e9376c2e32cff7",
            Primitives.hmacSha256(ByteArray(20) { 0x0b }, utf8("Hi There")).hex(),
        )
        assertEquals(
            "5bdcc146bf60754e6a042426089575c75a003f089d2739839dec58b964ec3843",
            Primitives.hmacSha256(utf8("Jefe"), utf8("what do ya want for nothing?")).hex(),
        )
    }

    @Test
    fun hmacSha256AcceptsAnEmptyKey() {
        assertEquals(
            "b613679a0814d9ec772f95d778c35fc5ff1697c493715653c6c712144292c5ad",
            Primitives.hmacSha256(ByteArray(0), ByteArray(0)).hex(),
        )
    }

    @Test
    fun sha256MatchesFips180() {
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", Primitives.sha256(utf8("abc")).hex())
        assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855", Primitives.sha256(ByteArray(0)).hex())
    }

    // ---- AES-GCM ----

    @Test
    fun aesGcmSealGivesCryptoKitCombinedLayout() {
        // crypto spec §16.3 media vector: key 0x01 × 32, nonce 0x02 × 12, "media bytes".
        val key = ByteArray(32) { 0x01 }
        val nonce = ByteArray(12) { 0x02 }
        val combined = Primitives.aesGcmSeal(key, nonce, utf8("media bytes"))
        assertEquals("AgICAgICAgICAgICarOtICt3o4Snqc8K7wU25kC+cfCidpttzp1L", B64.encode(combined))
        assertEquals(12 + 11 + 16, combined.size)
        assertArrayEquals(nonce, combined.copyOfRange(0, 12))
        assertArrayEquals(utf8("media bytes"), Primitives.aesGcmOpen(key, combined))
    }

    @Test
    fun aesGcmBindsAad() {
        val key = ByteArray(32) { 0x01 }
        val combined = Primitives.aesGcmSeal(key, ByteArray(12) { 0x02 }, utf8("media bytes"), aad = utf8("aad"))
        // Same nonce and key as above: the ciphertext is equal, only the tag changes.
        assertEquals("AgICAgICAgICAgICarOtICt3o4Snqc/1dWRF0ML8GatAb5GaVBN0", B64.encode(combined))
        assertArrayEquals(utf8("media bytes"), Primitives.aesGcmOpen(key, combined, utf8("aad")))
        assertThrows(CryptoError.OpenFailed::class.java) { Primitives.aesGcmOpen(key, combined) }
        assertThrows(CryptoError.OpenFailed::class.java) { Primitives.aesGcmOpen(key, combined, utf8("aae")) }
    }

    @Test
    fun aesGcmSealsEmptyPlaintext() {
        val key = ByteArray(32) { 7 }
        val combined = Primitives.aesGcmSeal(key, ByteArray(12), ByteArray(0))
        assertEquals(28, combined.size)
        assertArrayEquals(ByteArray(0), Primitives.aesGcmOpen(key, combined))
    }

    @Test
    fun aesGcmOpenFailsClosed() {
        val key = ByteArray(32) { 0x01 }
        val combined = Primitives.aesGcmSeal(key, ByteArray(12) { 0x02 }, utf8("media bytes"))
        for (i in combined.indices) {
            val tampered = combined.copyOf().also { it[i] = (it[i].toInt() xor 0x01).toByte() }
            assertThrows("byte $i", CryptoError.OpenFailed::class.java) { Primitives.aesGcmOpen(key, tampered) }
        }
        assertThrows(CryptoError.OpenFailed::class.java) { Primitives.aesGcmOpen(ByteArray(32) { 0x02 }, combined) }
        assertThrows(CryptoError.OpenFailed::class.java) { Primitives.aesGcmOpen(key, combined.copyOf(combined.size - 1)) }
        // Shorter than CryptoKit's SealedBox(combined:) minimum of 12 + 16.
        assertThrows(CryptoError.OpenFailed::class.java) { Primitives.aesGcmOpen(key, ByteArray(27)) }
        assertThrows(CryptoError.OpenFailed::class.java) { Primitives.aesGcmOpen(key, ByteArray(0)) }
        assertThrows(CryptoError.OpenFailed::class.java) { Primitives.aesGcmOpen(ByteArray(31), combined) }
    }

    @Test
    fun aesGcmSealRejectsBadKeyAndNonceSizes() {
        assertThrows(CryptoError.SealingFailed::class.java) { Primitives.aesGcmSeal(ByteArray(31), ByteArray(12), utf8("x")) }
        assertThrows(CryptoError.SealingFailed::class.java) { Primitives.aesGcmSeal(ByteArray(32), ByteArray(16), utf8("x")) }
        assertThrows(CryptoError.SealingFailed::class.java) { Primitives.aesGcmSeal(ByteArray(32), ByteArray(0), utf8("x")) }
    }

    @Test
    fun aesGcmAccepts128And192BitKeysLikeCryptoKit() {
        for (size in listOf(16, 24)) {
            val key = ByteArray(size) { 3 }
            val combined = Primitives.aesGcmSeal(key, ByteArray(12) { 4 }, utf8("short key"))
            assertArrayEquals(utf8("short key"), Primitives.aesGcmOpen(key, combined))
        }
    }

    private companion object {
        const val ALICE_PUB = "7b4e909bbe7ffe44c465a220037d608ee35897d31ef972f07f74892cb0f73f13"
        const val BOB_PUB = "0faa684ed28867b97f4a6a2dee5df8ce974e76b7018e3f22a1c4cf2678570f20"
    }
}
