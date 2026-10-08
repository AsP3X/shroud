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
        // Byte ids (an identity public key) are named by their lower-case hex (crypto spec §6.3).
        assertEquals("bd1b63ed7b582512bf24e91524c6699d", names.name(BYTE_ID_KIND, ALICE_PUB))
        assertEquals("bd1b63ed7b582512bf24e91524c6699d", names.name(BYTE_ID_KIND, hexToBytes(ALICE_PUB)))
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
        assertEquals(names.name(BYTE_ID_KIND, ALICE_PUB.uppercase()), names.name(BYTE_ID_KIND, ALICE_PUB))
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
        assertThrows(CryptoError.Locked::class.java) { names.name(BYTE_ID_KIND, hexToBytes(ALICE_PUB)) }
        names.wipe() // idempotent
        assertTrue(names.isWiped)
    }

    /**
     * crypto spec §1.6: a name computed under a half-zeroed key would point a write at the wrong
     * file, so every call must either return the right name or report Locked.
     *
     * What this test guarantees is that the race is *exercised*: the wipe starts only after every
     * worker has produced a name, and each worker keeps naming until it sees Locked, so every round
     * has names succeeding right up to the wipe and names refused after it. It cannot reliably
     * catch a missing lock — BouncyCastle copies the key at `HMac.init`, so a half-zeroed key is
     * visible only during one 32-byte copy. The no-wrong-name property itself comes from the
     * read/write lock in [LocalNames] (reviewed), not from this test.
     */
    @Test
    fun aWipeRacingNamesNeverYieldsAWrongName() {
        val workers = 4
        var totalNamed = 0
        var totalLocked = 0
        repeat(20) {
            val names = LocalNames.derive(SEALED_TEST_KEY)
            val pool = Executors.newFixedThreadPool(workers)
            val start = CountDownLatch(1)
            val everyoneNamed = CountDownLatch(workers)
            val wrong = AtomicInteger()
            val named = AtomicInteger()
            val locked = AtomicInteger()
            repeat(workers) {
                pool.execute {
                    start.await()
                    var first = true
                    // Bounded so a broken wipe fails the test instead of hanging it.
                    for (i in 0 until 5_000_000) {
                        try {
                            if (names.name(LocalNames.Kind.RATCHET, PEER) != RATCHET_NAME) wrong.incrementAndGet()
                            named.incrementAndGet()
                        } catch (_: CryptoError.Locked) {
                            locked.incrementAndGet()
                            break
                        } finally {
                            if (first) {
                                first = false
                                everyoneNamed.countDown()
                            }
                        }
                    }
                }
            }
            start.countDown()
            assertTrue(everyoneNamed.await(10, TimeUnit.SECONDS))
            names.wipe()
            pool.shutdown()
            assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS))
            assertEquals(0, wrong.get())
            // Every worker named before the wipe and was refused after it.
            assertTrue(named.get() >= workers)
            assertEquals(workers, locked.get())
            totalNamed += named.get()
            totalLocked += locked.get()
            assertThrows(CryptoError.Locked::class.java) { names.name(LocalNames.Kind.RATCHET, PEER) }
        }
        assertTrue(totalNamed > 0)
        assertEquals(20 * workers, totalLocked)
    }

    @Test
    fun kindWordsAreTheOnesOfPlanSection15() {
        assertEquals(listOf("ratchet", "user", "thread", "msg", "media"), KINDS)
    }

    private companion object {
        /** iOS `ios/shroudTests/SealedTestKey.swift:8`: 0x5A × 32. */
        val SEALED_TEST_KEY = ByteArray(32) { 0x5A }

        /** The device id of `ios/shroudTests/DeviceNameSealTests.swift`, reused as a peer id. */
        const val PEER = "0F8FAD5B-D9CB-469F-A165-70867728950E"

        const val RATCHET_NAME = "5099ebed1c8bf720e879384183c613ef"

        /**
         * The kind of the byte-id vector: older builds named their sender-tag watermarks so
         * (`gen_local_history_vectors.mjs`); any stable word works for the byte overload.
         */
        const val BYTE_ID_KIND = "sender-tag"

        /** crypto spec §16.3: X25519 public of 0x11 × 32. */
        const val ALICE_PUB = "7b4e909bbe7ffe44c465a220037d608ee35897d31ef972f07f74892cb0f73f13"

        val KINDS = listOf(
            LocalNames.Kind.RATCHET,
            LocalNames.Kind.USER,
            LocalNames.Kind.THREAD,
            LocalNames.Kind.MESSAGE,
            LocalNames.Kind.MEDIA,
        )
    }
}
