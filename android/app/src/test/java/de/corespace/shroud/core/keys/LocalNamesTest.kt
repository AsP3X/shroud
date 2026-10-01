package de.corespace.shroud.core.keys

import de.corespace.shroud.core.crypto.CryptoError
import de.corespace.shroud.core.crypto.LocalHistoryCrypto
import de.corespace.shroud.core.crypto.hexToBytes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Keyed local file names (plan §1.5 and C10; web-parity §3.1, the web's `vaultName`,
 * `web/src/crypto/vault.ts:293-301`; crypto spec §6.3, §7.2).
 *
 * The pinned names are printed by `core/crypto/gen_local_history_vectors.mjs` (Node's OpenSSL,
 * independent of the BouncyCastle code under test) for the iOS test key `0x5A × 32`
 * (`ios/shroudTests/SealedTestKey.swift:8`).
 */
class LocalNamesTest {
    @Test
    fun namesMatchGeneratorVectors() {
        val names = LocalNames.derive(SEALED_TEST_KEY)
        assertEquals("5099ebed1c8bf720e879384183c613ef", names.name(LocalNames.Kind.RATCHET, PEER))
        assertEquals("5f13b24cd82a341db0171fce8b306974", names.name(LocalNames.Kind.THREAD, PEER))
        // Sender tags are keyed by the identity public key as lower-case hex (crypto spec §6.3).
        assertEquals("bd1b63ed7b582512bf24e91524c6699d", names.name(LocalNames.Kind.SENDER_TAG, ALICE_PUB))
        assertEquals("bd1b63ed7b582512bf24e91524c6699d", names.name(LocalNames.Kind.SENDER_TAG, hexToBytes(ALICE_PUB)))
    }

    @Test
    fun derivedKeyIsTheRecordNamesSubkey() {
        val subkey = LocalHistoryCrypto.subkey(SEALED_TEST_KEY, LocalHistoryCrypto.Context.RecordNames)
        val direct = LocalNames(subkey)
        val derived = LocalNames.derive(SEALED_TEST_KEY)
        for (kind in KINDS) assertEquals(kind, derived.name(kind, PEER), direct.name(kind, PEER))
    }

    @Test
    fun idsAreLowerCasedSoBothSpellingsNameOneFile() {
        val names = LocalNames.derive(SEALED_TEST_KEY)
        val upper = names.name(LocalNames.Kind.THREAD, PEER)
        assertEquals(upper, names.name(LocalNames.Kind.THREAD, PEER.lowercase()))
        // The UUID overload uses the wire form (lower-case), so it names the same file.
        assertEquals(upper, names.name(LocalNames.Kind.THREAD, UUID.fromString(PEER)))
        // Byte ids: upper- and lower-case hex of the same key are one name.
        assertEquals(names.name(LocalNames.Kind.SENDER_TAG, ALICE_PUB.uppercase()), names.name(LocalNames.Kind.SENDER_TAG, ALICE_PUB))
    }

    @Test
    fun everyNameIs32LowerCaseHexCharacters() {
        val names = LocalNames.derive(SEALED_TEST_KEY)
        repeat(50) {
            val name = names.name(LocalNames.Kind.MESSAGE, UUID.randomUUID())
            assertEquals(LocalNames.NAME_LENGTH, name.length)
            assertTrue(name, name.all { it in '0'..'9' || it in 'a'..'f' })
        }
    }

    @Test
    fun kindsSeparateTheSameId() {
        val names = LocalNames.derive(SEALED_TEST_KEY)
        val all = KINDS.map { names.name(it, PEER) }
        assertEquals(all.size, all.toSet().size)
    }

    @Test
    fun theKindAndIdAreJoinedUnambiguously() {
        val names = LocalNames.derive(SEALED_TEST_KEY)
        // "a:b" + ":c" must never collide with "a" + ":b:c": a kind cannot hold ':'.
        assertThrows(IllegalArgumentException::class.java) { names.name("a:b", "c") }
        assertThrows(IllegalArgumentException::class.java) { names.name("", "c") }
        // An id may contain ':' (only the kind is restricted).
        assertNotEquals(names.name("a", "b:c"), names.name("a", "bc"))
    }

    @Test
    fun anotherHistoryKeyGivesOtherNames() {
        val a = LocalNames.derive(SEALED_TEST_KEY)
        val b = LocalNames.derive(ByteArray(32) { 0x5B })
        for (kind in KINDS) assertNotEquals(kind, a.name(kind, PEER), b.name(kind, PEER))
    }

    @Test
    fun theInstanceKeepsItsOwnCopyOfTheKey() {
        val key = LocalHistoryCrypto.subkey(SEALED_TEST_KEY, LocalHistoryCrypto.Context.RecordNames)
        val names = LocalNames(key)
        val before = names.name(LocalNames.Kind.RATCHET, PEER)
        key.fill(0) // the caller zeroes its array
        assertEquals(before, names.name(LocalNames.Kind.RATCHET, PEER))
        assertEquals("5099ebed1c8bf720e879384183c613ef", before)
    }

    @Test
    fun deriveLeavesTheHistoryKeyUntouched() {
        val historyKey = SEALED_TEST_KEY.copyOf()
        LocalNames.derive(historyKey)
        assertTrue(historyKey.contentEquals(SEALED_TEST_KEY))
    }

    @Test
    fun theKeyMustBe32Bytes() {
        assertThrows(IllegalArgumentException::class.java) { LocalNames(ByteArray(31)) }
        assertThrows(IllegalArgumentException::class.java) { LocalNames(ByteArray(33)) }
        assertThrows(IllegalArgumentException::class.java) { LocalNames.derive(ByteArray(16)) }
    }

    @Test
    fun wipeLocksEveryLaterName() {
        val names = LocalNames.derive(SEALED_TEST_KEY)
        assertFalse(names.isWiped)
        names.wipe()
        assertTrue(names.isWiped)
        val error = assertThrows(CryptoError.Locked::class.java) { names.name(LocalNames.Kind.THREAD, PEER) }
        assertSame(CryptoError.Locked, error)
        assertThrows(CryptoError.Locked::class.java) { names.name(LocalNames.Kind.MEDIA, UUID.fromString(PEER)) }
        assertThrows(CryptoError.Locked::class.java) { names.name(LocalNames.Kind.SENDER_TAG, hexToBytes(ALICE_PUB)) }
        names.wipe() // idempotent
        assertTrue(names.isWiped)
    }

    @Test
    fun aWipeRacingNamesNeverYieldsAWrongName() {
        // crypto spec §1.6: a name computed under a half-zeroed key would point a write at the
        // wrong file. Every call either returns the right name or reports Locked.
        repeat(20) {
            val names = LocalNames.derive(SEALED_TEST_KEY)
            val pool = Executors.newFixedThreadPool(4)
            val start = CountDownLatch(1)
            val wrong = AtomicInteger()
            val locked = AtomicInteger()
            repeat(4) {
                pool.execute {
                    start.await()
                    repeat(200) {
                        try {
                            if (names.name(LocalNames.Kind.RATCHET, PEER) != RATCHET_NAME) wrong.incrementAndGet()
                        } catch (_: CryptoError.Locked) {
                            locked.incrementAndGet()
                        }
                    }
                }
            }
            start.countDown()
            Thread.sleep(1)
            names.wipe()
            pool.shutdown()
            assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS))
            assertEquals(0, wrong.get())
            assertThrows(CryptoError.Locked::class.java) { names.name(LocalNames.Kind.RATCHET, PEER) }
        }
    }

    @Test
    fun kindWordsAreTheOnesOfPlanSection15() {
        assertEquals(listOf("ratchet", "sender-tag", "user", "thread", "msg", "media"), KINDS)
    }

    private companion object {
        /** iOS `ios/shroudTests/SealedTestKey.swift:8`: 0x5A × 32. */
        val SEALED_TEST_KEY = ByteArray(32) { 0x5A }

        /** The device id of `ios/shroudTests/DeviceNameSealTests.swift`, reused as a peer id. */
        const val PEER = "0F8FAD5B-D9CB-469F-A165-70867728950E"

        const val RATCHET_NAME = "5099ebed1c8bf720e879384183c613ef"

        /** crypto spec §16.3: X25519 public of 0x11 × 32. */
        const val ALICE_PUB = "7b4e909bbe7ffe44c465a220037d608ee35897d31ef972f07f74892cb0f73f13"

        val KINDS = listOf(
            LocalNames.Kind.RATCHET,
            LocalNames.Kind.SENDER_TAG,
            LocalNames.Kind.USER,
            LocalNames.Kind.THREAD,
            LocalNames.Kind.MESSAGE,
            LocalNames.Kind.MEDIA,
        )
    }
}
