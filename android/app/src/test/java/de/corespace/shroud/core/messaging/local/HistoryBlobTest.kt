package de.corespace.shroud.core.messaging.local

import de.corespace.shroud.core.crypto.B64
import de.corespace.shroud.core.crypto.CryptoError
import de.corespace.shroud.core.crypto.LocalHistoryCrypto
import de.corespace.shroud.core.crypto.LocalHistoryCrypto.Context
import de.corespace.shroud.core.crypto.ScriptedEntropy
import de.corespace.shroud.core.crypto.SystemEntropy
import de.corespace.shroud.core.crypto.utf8
import de.corespace.shroud.core.keys.LocalNames
import de.corespace.shroud.core.keys.SealedLocalState
import de.corespace.shroud.testing.SealedTestKey
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test
import java.util.UUID

/**
 * The store's records are `LocalHistoryCrypto` blobs sealed with a subkey derived once per unlock
 * (`LocalHistoryCrypto.swift:39-72`). The vectors are W0-C's (`LocalHistoryCryptoTest`, crypto spec
 * §16.3 and `gen_local_history_vectors.mjs`), copied byte for byte: key `0x5A × 32`, nonce `01…0c`.
 */
class HistoryBlobTest {
    private val subkey = LocalHistoryCrypto.subkey(SealedTestKey.bytes(), Context.MessagesSnapshot)

    @Test
    fun emptyAadBlobMatchesTheIosVector() {
        val sealed = HistoryBlob.seal(subkey, utf8(PLAINTEXT), ByteArray(0), ScriptedEntropy(NONCE))
        assertEquals(EMPTY_AAD_BLOB, B64.encode(sealed))
        assertArrayEquals(utf8(PLAINTEXT), HistoryBlob.open(subkey, B64.decodeStrict(EMPTY_AAD_BLOB)!!, ByteArray(0)))
    }

    @Test
    fun aadBlobMatchesTheGeneratorVector() {
        val sealed = HistoryBlob.seal(subkey, utf8(PLAINTEXT), utf8(AAD), ScriptedEntropy(NONCE))
        assertEquals(AAD_BLOB, B64.encode(sealed))
    }

    @Test
    fun interoperatesWithLocalHistoryCryptoBothWays() {
        val master = SystemEntropy.bytes(32)
        for (context in Context.entries) {
            val sub = LocalHistoryCrypto.subkey(master, context)
            val aad = utf8("shroud/plaintext/${context.name}.sealed")
            val ours = HistoryBlob.seal(sub, utf8("record"), aad)
            assertArrayEquals(utf8("record"), LocalHistoryCrypto.open(ours, master, context, aad))
            val theirs = LocalHistoryCrypto.seal(utf8("record"), master, context, aad)
            assertArrayEquals(utf8("record"), HistoryBlob.open(sub, theirs, aad))
        }
    }

    @Test
    fun refusesAnotherLocationAndTheEmptyBlob() {
        val sealed = B64.decodeStrict(AAD_BLOB)!!
        assertThrows(CryptoError.OpenFailed::class.java) { HistoryBlob.open(subkey, sealed, utf8("threads/5f13b24cd82a341db0171fce8b306975")) }
        assertThrows(CryptoError.OpenFailed::class.java) { HistoryBlob.open(subkey, sealed, ByteArray(0)) }
        // Empty plaintext seals to 33 bytes, which `open` never accepts (`LocalHistoryCrypto.swift:61`).
        val empty = HistoryBlob.seal(subkey, ByteArray(0), ByteArray(0))
        assertEquals(33, empty.size)
        assertThrows(CryptoError.OpenFailed::class.java) { HistoryBlob.open(subkey, empty, ByteArray(0)) }
    }

    @Test
    fun storeKeysSealUnderTheSubkeyOfTheUnlockedHistoryKey() {
        val state = SealedLocalState().also { it.unlock(SealedTestKey.bytes()) }
        val keys = LocalStoreKeys(state, ScriptedEntropy(NONCE))
        val sealed = keys.seal(Context.MessagesSnapshot, utf8(PLAINTEXT), AAD)!!
        assertEquals(AAD_BLOB, B64.encode(sealed))
        assertArrayEquals(utf8(PLAINTEXT), keys.open(Context.MessagesSnapshot, sealed, AAD))
        assertNull(keys.open(Context.PlaintextPayload, sealed, AAD))
        assertNull(keys.open(Context.MessagesSnapshot, sealed, "threads/other"))

        val id = UUID.fromString("0F8FAD5B-D9CB-469F-A165-70867728950E")
        assertEquals(LocalNames.derive(SealedTestKey.bytes()).name(LocalNames.Kind.THREAD, id), keys.name(LocalNames.Kind.THREAD, id))
        assertEquals("5f13b24cd82a341db0171fce8b306974", keys.name(LocalNames.Kind.THREAD, id))
    }

    @Test
    fun storeKeysAreUnusableWhileChatsAreLocked() {
        val state = SealedLocalState().also { it.unlock(SealedTestKey.bytes()) }
        val keys = LocalStoreKeys(state)
        val sealed = keys.seal(Context.MessagesSnapshot, utf8("x"), "a")!!
        val names = state.names()!!
        state.lock()
        assertNull(keys.name(LocalNames.Kind.USER, UUID.randomUUID()))
        assertNull(keys.seal(Context.MessagesSnapshot, utf8("x"), "a"))
        assertNull(keys.open(Context.MessagesSnapshot, sealed, "a"))
        // A names instance taken before the lock is wiped with it.
        assertThrows(CryptoError.Locked::class.java) { names.name(LocalNames.Kind.USER, UUID.randomUUID()) }
    }

    private companion object {
        /** `LocalHistoryCryptoTests.swift:8`. */
        const val PLAINTEXT = "secret chat body — notes & 🔒"

        /** 01 02 … 0c. */
        val NONCE = ByteArray(12) { (it + 1).toByte() }

        /** crypto spec §16.3 (empty AAD, the iOS format) — `LocalHistoryCryptoTest.EMPTY_AAD_BLOB`. */
        const val EMPTY_AAD_BLOB = "U0hSRDEBAgMEBQYHCAkKCwwW+T32+cn3NJvwEKRAbofV8YIKJKelkLtCD5VXex6JoW8P/+G2tQyxWEAAK732JoK3"

        /** The `LocalNames` "thread" name of `0F8FAD5B-D9CB-469F-A165-70867728950E` under the test key. */
        const val AAD = "threads/5f13b24cd82a341db0171fce8b306974"

        /** `gen_local_history_vectors.mjs` — `LocalHistoryCryptoTest.AAD_BLOB`. */
        const val AAD_BLOB = "U0hSRDEBAgMEBQYHCAkKCwwW+T32+cn3NJvwEKRAbofV8YIKJKelkLtCD5VXex6JoW89oslbUbwXQc4MGtftNVze"
    }
}
