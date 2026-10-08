package de.corespace.shroud.core.crypto

import de.corespace.shroud.core.crypto.LocalHistoryCrypto.Context
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * At-rest sealing (iOS `ios/shroud/Services/Messaging/LocalHistoryCrypto.swift`; crypto spec §2).
 *
 * The first four tests are iOS `ios/shroudTests/LocalHistoryCryptoTests.swift:6-65`; the vectors
 * are crypto spec §16.3 (the seven iOS subkeys and the empty-AAD blob) plus the Android-only rows
 * (the `RecordNames` subkey and an AAD-bound blob, plan C10). All of them are printed by
 * `gen_local_history_vectors.mjs` next to this file, which uses Node's OpenSSL — an implementation
 * independent of the BouncyCastle/JCA code under test.
 */
class LocalHistoryCryptoTest {
    // ---- iOS LocalHistoryCryptoTests ----

    /** `LocalHistoryCryptoTests.swift:6-25`. */
    @Test
    fun roundTripSealOpen() {
        val master = SystemEntropy.bytes(32)
        val plain = utf8(PLAINTEXT)
        val sealed = LocalHistoryCrypto.seal(plain, master, Context.MessagesSnapshot)
        assertTrue(LocalHistoryCrypto.isSealedBlob(sealed))
        assertFalse(sealed.contentEquals(plain))
        assertFalse(String(sealed, Charsets.UTF_8).contains("secret chat"))
        assertArrayEquals(plain, LocalHistoryCrypto.open(sealed, master, Context.MessagesSnapshot))
    }

    /** `LocalHistoryCryptoTests.swift:27-38`. */
    @Test
    fun wrongKeyFailsClosed() {
        val a = SystemEntropy.bytes(32)
        val b = SystemEntropy.bytes(32)
        val sealed = LocalHistoryCrypto.seal(utf8("hello"), a, Context.MediaFile)
        assertThrows(CryptoError.OpenFailed::class.java) { LocalHistoryCrypto.open(sealed, b, Context.MediaFile) }
    }

    /** `LocalHistoryCryptoTests.swift:40-51`. */
    @Test
    fun contextSeparation() {
        val master = SystemEntropy.bytes(32)
        val sealed = LocalHistoryCrypto.seal(utf8("payload"), master, Context.PlaintextPayload)
        assertThrows(CryptoError.OpenFailed::class.java) { LocalHistoryCrypto.open(sealed, master, Context.MediaFile) }
    }

    /** `LocalHistoryCryptoTests.swift:53-65`: flip a ciphertext byte at `size − 5`. */
    @Test
    fun tamperDetected() {
        val master = SystemEntropy.bytes(32)
        val sealed = LocalHistoryCrypto.seal(utf8("intact"), master, Context.MessagesSnapshot)
        sealed[sealed.size - 5] = (sealed[sealed.size - 5].toInt() xor 0xFF).toByte()
        assertThrows(CryptoError.OpenFailed::class.java) { LocalHistoryCrypto.open(sealed, master, Context.MessagesSnapshot) }
    }

    // ---- vectors ----

    @Test
    fun subkeysMatchIosVectors() {
        val expected = mapOf(
            Context.MessagesSnapshot to "3cc3691c932245d555fd872600279763e2edab269cb58eeb478cad7de49c1be3",
            Context.MediaFile to "8b284b151210ae88a43d5e9fcd6b3268ca10615314db8b2e03033427bb052827",
            Context.PlaintextPayload to "f6ff84bee12e2944e80007d3edf8b6b291dbe1e3c8d100359860f123b763b8be",
            Context.IdentityKeychain to "216b4b9b5a89aff4e74200c06ec37ccb6be02296dceef779a2484b35a932bac4",
            Context.RatchetKeychain to "51a7b6121993146d8c17b1ff5e8d622bd334da0a4ec338330bc3586c92adff8e",
            Context.LanguageStats to "4b0bb41ee29f0db367070ce6c0b927ab0e69501573a22abc575afcde24c2e608",
        )
        for ((context, hex) in expected) {
            assertEquals(context.name, hex, LocalHistoryCrypto.subkey(SEALED_TEST_KEY, context).hex())
        }
    }

    @Test
    fun recordNamesSubkeyMatchesGeneratorVector() {
        // Android-only context (plan §1.5): the key LocalNames hashes file names with.
        assertEquals(
            "1081ee9ac4a235fce5fde14dfbd6899f7fa9e583201d0097a9699a898454201b",
            LocalHistoryCrypto.subkey(SEALED_TEST_KEY, Context.RecordNames).hex(),
        )
    }

    @Test
    fun contextInfoStringsAreTheIosOnesPlusRecordNames() {
        // LocalHistoryCrypto.swift:21-33, in order; RecordNames is Android's addition (crypto spec §2.1).
        assertEquals(
            listOf(
                "shroud-local-messages-v1",
                "shroud-local-media-v1",
                "shroud-local-plaintext-v1",
                "shroud-keychain-identity-v1",
                "shroud-keychain-ratchet-v1",
                "shroud-local-language-stats-v1",
                "shroud-local-names-v1",
            ),
            Context.entries.map { it.info },
        )
    }

    @Test
    fun blobMatchesIosVector() {
        val entropy = ScriptedEntropy(NONCE)
        val sealed = LocalHistoryCrypto.seal(utf8(PLAINTEXT), SEALED_TEST_KEY, Context.MessagesSnapshot, entropy = entropy)
        assertEquals(EMPTY_AAD_BLOB, B64.encode(sealed))
        assertEquals(0, entropy.remaining) // exactly one 12-byte draw
        assertArrayEquals(utf8(PLAINTEXT), LocalHistoryCrypto.open(B64.decodeStrict(EMPTY_AAD_BLOB)!!, SEALED_TEST_KEY, Context.MessagesSnapshot))
    }

    @Test
    fun blobLayoutIsMagicNonceCiphertextTag() {
        val sealed = B64.decodeStrict(EMPTY_AAD_BLOB)!!
        assertArrayEquals(utf8("SHRD1"), sealed.copyOfRange(0, 5))
        assertArrayEquals(NONCE, sealed.copyOfRange(5, 17))
        assertEquals(5 + 12 + utf8(PLAINTEXT).size + 16, sealed.size)
    }

    @Test
    fun aadBoundBlobMatchesGeneratorVector() {
        val sealed = LocalHistoryCrypto.seal(utf8(PLAINTEXT), SEALED_TEST_KEY, Context.MessagesSnapshot, aad = utf8(AAD), entropy = ScriptedEntropy(NONCE))
        assertEquals(AAD_BLOB, B64.encode(sealed))
        assertArrayEquals(utf8(PLAINTEXT), LocalHistoryCrypto.open(sealed, SEALED_TEST_KEY, Context.MessagesSnapshot, aad = utf8(AAD)))
    }

    @Test
    fun aadMustMatchToOpen() {
        val bound = B64.decodeStrict(AAD_BLOB)!!
        val unbound = B64.decodeStrict(EMPTY_AAD_BLOB)!!
        // Moved to another name, or read without the name: refused.
        assertThrows(CryptoError.OpenFailed::class.java) { LocalHistoryCrypto.open(bound, SEALED_TEST_KEY, Context.MessagesSnapshot) }
        assertThrows(CryptoError.OpenFailed::class.java) {
            LocalHistoryCrypto.open(bound, SEALED_TEST_KEY, Context.MessagesSnapshot, aad = utf8("threads/5f13b24cd82a341db0171fce8b306975"))
        }
        // An iOS-style (empty AAD) blob does not open under a name either.
        assertThrows(CryptoError.OpenFailed::class.java) { LocalHistoryCrypto.open(unbound, SEALED_TEST_KEY, Context.MessagesSnapshot, aad = utf8(AAD)) }
        // Passing the empty AAD explicitly is the iOS format.
        assertArrayEquals(utf8(PLAINTEXT), LocalHistoryCrypto.open(unbound, SEALED_TEST_KEY, Context.MessagesSnapshot, aad = ByteArray(0)))
    }

    // ---- format rules ----

    @Test
    fun openRequiresStrictlyMoreThan33Bytes() {
        // iOS seals empty data to exactly 33 bytes but `open` needs `count > 5 + 12 + 16`
        // (LocalHistoryCrypto.swift:61): such a blob never opens, although its GCM is valid.
        val empty = LocalHistoryCrypto.seal(ByteArray(0), SEALED_TEST_KEY, Context.MessagesSnapshot, entropy = ScriptedEntropy(NONCE))
        assertEquals(33, empty.size)
        assertTrue(LocalHistoryCrypto.isSealedBlob(empty))
        val subkey = LocalHistoryCrypto.subkey(SEALED_TEST_KEY, Context.MessagesSnapshot)
        assertArrayEquals(ByteArray(0), Primitives.aesGcmOpen(subkey, empty.copyOfRange(5, empty.size)))
        assertThrows(CryptoError.OpenFailed::class.java) { LocalHistoryCrypto.open(empty, SEALED_TEST_KEY, Context.MessagesSnapshot) }

        // One byte of plaintext (34 bytes) opens.
        val one = LocalHistoryCrypto.seal(byteArrayOf(0x2a), SEALED_TEST_KEY, Context.MessagesSnapshot)
        assertEquals(34, one.size)
        assertArrayEquals(byteArrayOf(0x2a), LocalHistoryCrypto.open(one, SEALED_TEST_KEY, Context.MessagesSnapshot))
    }

    @Test
    fun openRefusesWrongMagicAndShortBlobs() {
        val sealed = B64.decodeStrict(EMPTY_AAD_BLOB)!!
        val keystoreMagic = sealed.copyOf().also { it[3] = 'K'.code.toByte() } // "SHRK1", KeystoreSealer's magic
        assertThrows(CryptoError.OpenFailed::class.java) { LocalHistoryCrypto.open(keystoreMagic, SEALED_TEST_KEY, Context.MessagesSnapshot) }
        val noMagic = sealed.copyOfRange(5, sealed.size)
        assertThrows(CryptoError.OpenFailed::class.java) { LocalHistoryCrypto.open(noMagic, SEALED_TEST_KEY, Context.MessagesSnapshot) }
        for (size in 0..33) {
            assertThrows("size $size", CryptoError.OpenFailed::class.java) {
                LocalHistoryCrypto.open(sealed.copyOf(size), SEALED_TEST_KEY, Context.MessagesSnapshot)
            }
        }
    }

    @Test
    fun everyByteIsAuthenticated() {
        val sealed = B64.decodeStrict(EMPTY_AAD_BLOB)!!
        for (i in sealed.indices) {
            val tampered = sealed.copyOf().also { it[i] = (it[i].toInt() xor 0x80).toByte() }
            assertThrows("byte $i", CryptoError.OpenFailed::class.java) { LocalHistoryCrypto.open(tampered, SEALED_TEST_KEY, Context.MessagesSnapshot) }
        }
        assertThrows(CryptoError.OpenFailed::class.java) { LocalHistoryCrypto.open(sealed + byteArrayOf(0), SEALED_TEST_KEY, Context.MessagesSnapshot) }
    }

    @Test
    fun isSealedBlobChecksLengthAndMagicOnly() {
        // LocalHistoryCrypto.swift:75-77: `count > 5 && prefix == "SHRD1"`.
        assertFalse(LocalHistoryCrypto.isSealedBlob(ByteArray(0)))
        assertFalse(LocalHistoryCrypto.isSealedBlob(utf8("SHRD")))
        assertFalse(LocalHistoryCrypto.isSealedBlob(utf8("SHRD1")))
        assertTrue(LocalHistoryCrypto.isSealedBlob(utf8("SHRD1x")))
        assertFalse(LocalHistoryCrypto.isSealedBlob(utf8("SHRK1xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx")))
        assertFalse(LocalHistoryCrypto.isSealedBlob(utf8("{\"conversations\":[]}")))
    }

    @Test
    fun everySealDrawsAFreshNonce() {
        val a = LocalHistoryCrypto.seal(utf8("same"), SEALED_TEST_KEY, Context.RatchetKeychain)
        val b = LocalHistoryCrypto.seal(utf8("same"), SEALED_TEST_KEY, Context.RatchetKeychain)
        assertNotEquals(a.hex(), b.hex())
        assertArrayEquals(utf8("same"), LocalHistoryCrypto.open(a, SEALED_TEST_KEY, Context.RatchetKeychain))
        assertArrayEquals(utf8("same"), LocalHistoryCrypto.open(b, SEALED_TEST_KEY, Context.RatchetKeychain))
    }

    @Test
    fun everyContextOpensOnlyItsOwnBlobs() {
        for (sealedAs in Context.entries) {
            val sealed = LocalHistoryCrypto.seal(utf8("record"), SEALED_TEST_KEY, sealedAs)
            for (openedAs in Context.entries) {
                if (openedAs == sealedAs) {
                    assertArrayEquals(utf8("record"), LocalHistoryCrypto.open(sealed, SEALED_TEST_KEY, openedAs))
                } else {
                    assertThrows("$sealedAs as $openedAs", CryptoError.OpenFailed::class.java) {
                        LocalHistoryCrypto.open(sealed, SEALED_TEST_KEY, openedAs)
                    }
                }
            }
        }
    }

    @Test
    fun theMasterKeyIsNotModified() {
        val master = SEALED_TEST_KEY.copyOf()
        val sealed = LocalHistoryCrypto.seal(utf8("x"), master, Context.MediaFile)
        LocalHistoryCrypto.open(sealed, master, Context.MediaFile)
        LocalHistoryCrypto.subkey(master, Context.MediaFile)
        assertArrayEquals(SEALED_TEST_KEY, master)
    }

    @Test
    fun theMasterKeyMustBe32Bytes() {
        assertThrows(IllegalArgumentException::class.java) { LocalHistoryCrypto.subkey(ByteArray(31), Context.MediaFile) }
        assertThrows(IllegalArgumentException::class.java) { LocalHistoryCrypto.seal(utf8("x"), ByteArray(16), Context.MediaFile) }
        assertThrows(IllegalArgumentException::class.java) { LocalHistoryCrypto.subkey(ByteArray(33), Context.MediaFile) }
    }

    private companion object {
        /** iOS `ios/shroudTests/SealedTestKey.swift:8`: 0x5A × 32. */
        val SEALED_TEST_KEY = ByteArray(32) { 0x5A }

        /** `LocalHistoryCryptoTests.swift:8`. */
        const val PLAINTEXT = "secret chat body — notes & 🔒"

        /** 01 02 … 0c. */
        val NONCE = ByteArray(12) { (it + 1).toByte() }

        /** crypto spec §16.3 (empty AAD, the iOS format). */
        const val EMPTY_AAD_BLOB = "U0hSRDEBAgMEBQYHCAkKCwwW+T32+cn3NJvwEKRAbofV8YIKJKelkLtCD5VXex6JoW8P/+G2tQyxWEAAK732JoK3"

        /** The logical name of a thread file (`LocalNames` "thread" of `0F8FAD5B-D9CB-469F-A165-70867728950E`). */
        const val AAD = "threads/5f13b24cd82a341db0171fce8b306974"

        /** gen_local_history_vectors.mjs: same key, nonce and plaintext, AAD = [AAD]. */
        const val AAD_BLOB = "U0hSRDEBAgMEBQYHCAkKCwwW+T32+cn3NJvwEKRAbofV8YIKJKelkLtCD5VXex6JoW89oslbUbwXQc4MGtftNVze"
    }
}
