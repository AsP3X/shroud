package de.corespace.shroud.core.keys

import de.corespace.shroud.core.crypto.LocalHistoryCrypto
import de.corespace.shroud.core.crypto.hexToBytes
import de.corespace.shroud.core.storage.ScriptedSealer
import de.corespace.shroud.core.storage.SealResult
import de.corespace.shroud.core.storage.SealedDirectoryStore
import de.corespace.shroud.core.storage.StorageSeal
import de.corespace.shroud.testing.SealedTestKey
import de.corespace.shroud.testing.TempDirRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Sender-tag watermarks behind the `SenderTagWatermarks` seam (iOS
 * `ios/shroud/Services/Crypto/SenderTagStore.swift:36-93`; crypto spec §6). The tag policy itself
 * (`SenderTagTests.swift`) belongs to W1-CRYPTO's `MessageCrypto`; these pin the store rules it
 * relies on.
 */
class SenderTagStoreTest {
    @get:Rule
    val temp = TempDirRule()

    private val sealer = ScriptedSealer()
    private val seal = StorageSeal()
    private val state = SealedLocalState().apply { setHistoryKeyForTesting(SealedTestKey.bytes()) }
    private val dir: File get() = temp.noBackupFilesDir.resolve("keys/sender-tags")
    private val store by lazy { SenderTagStore(SealedDirectoryStore(dir, sealer), state, seal) }

    /** `alicePub` of crypto spec §16.3. */
    private val alicePub = hexToBytes("7b4e909bbe7ffe44c465a220037d608ee35897d31ef972f07f74892cb0f73f13")
    private val bobPub = hexToBytes("0faa684ed28867b97f4a6a2dee5df8ce974e76b7018e3f22a1c4cf2678570f20")

    /** `t0` of `SenderTagTests.swift:14`. */
    private val t0 = Instant.ofEpochSecond(1_788_000_000)

    @Test
    fun withoutTheHistoryKeyItIsLocked() {
        state.lock()
        assertEquals(Watermark.Locked, store.taggedSince(alicePub))
        store.noteTagged(alicePub, t0)
        state.setHistoryKeyForTesting(SealedTestKey.bytes())
        assertEquals("the locked write was dropped", Watermark.Untagged, store.taggedSince(alicePub))
    }

    @Test
    fun aSenderNeverSeenTaggingIsUntagged() {
        assertEquals(Watermark.Untagged, store.taggedSince(alicePub))
    }

    @Test
    fun theWatermarkOnlyMovesEarlier() {
        store.noteTagged(alicePub, t0)
        assertEquals(Watermark.Since(t0), store.taggedSince(alicePub))
        store.noteTagged(alicePub, t0.plusSeconds(60))
        assertEquals(Watermark.Since(t0), store.taggedSince(alicePub))
        store.noteTagged(alicePub, t0.minusSeconds(120))
        assertEquals(Watermark.Since(t0.minusSeconds(120)), store.taggedSince(alicePub))
        // Per sender identity key.
        assertEquals(Watermark.Untagged, store.taggedSince(bobPub))
    }

    @Test
    fun theServersMicrosecondsSurvive() {
        val sent = Instant.parse("2026-09-24T12:00:00.123456Z")
        store.noteTagged(alicePub, sent)
        assertEquals(Watermark.Since(sent), store.taggedSince(alicePub))
    }

    /** "A Keychain error is not 'never tagged'" (`SenderTagStore.swift:46-54`). */
    @Test
    fun anUnreadableRecordIsSinceTheBeginningAndNeverOverwritten() {
        store.noteTagged(alicePub, t0)
        sealer.readFailure = SealResult.DeviceLocked
        assertEquals(Watermark.Since(Instant.MIN), store.taggedSince(alicePub))
        sealer.readFailure = SealResult.Failed
        assertEquals(Watermark.Since(Instant.MIN), store.taggedSince(alicePub))
        store.noteTagged(alicePub, t0.minusSeconds(3600))
        sealer.readFailure = null
        assertEquals("not overwritten while unreadable", Watermark.Since(t0), store.taggedSince(alicePub))
    }

    @Test
    fun aRecordThatDoesNotOpenOrParseIsSinceTheBeginning() {
        val name = LocalNames.derive(SealedTestKey.bytes()).name(LocalNames.Kind.SENDER_TAG, alicePub)
        val records = SealedDirectoryStore(dir, sealer)
        records.write(name, byteArrayOf(1, 2, 3))
        assertEquals(Watermark.Since(Instant.MIN), store.taggedSince(alicePub))
        val notAnInstant = LocalHistoryCrypto.seal("1788000000.0".toByteArray(), SealedTestKey.bytes(), LocalHistoryCrypto.Context.SenderTagKeychain, SenderTagStore.aad(name))
        records.write(name, notAnInstant)
        assertEquals(Watermark.Since(Instant.MIN), store.taggedSince(alicePub))
        // Unreadable stays: noteTagged never moves Instant.MIN.
        store.noteTagged(alicePub, t0)
        assertEquals(Watermark.Since(Instant.MIN), store.taggedSince(alicePub))
    }

    @Test
    fun recordsAreNamedByTheKeyedHashOfTheIdentityKey() {
        store.noteTagged(alicePub, t0)
        val expected = LocalNames.derive(SealedTestKey.bytes()).name(LocalNames.Kind.SENDER_TAG, alicePub)
        assertEquals(listOf(expected), dir.list()!!.toList())
        // Not iOS's plain SHA-256 account (crypto spec §16.3, reference only).
        assertFalse(expected == "d19bf3f082782c87b783fe7134698aeff6e66d9f86afaf7cf9e9b8bf40bab3ff".take(32))
    }

    @Test
    fun aWipeInProgressOrAFailedWriteDropsTheNote() {
        seal.seal()
        store.noteTagged(alicePub, t0)
        seal.unseal()
        assertEquals(Watermark.Untagged, store.taggedSince(alicePub))
        sealer.sealFails = true
        store.noteTagged(alicePub, t0)
        sealer.sealFails = false
        assertEquals(Watermark.Untagged, store.taggedSince(alicePub))
    }

    @Test
    fun deleteAllWorksWhileLocked() {
        store.noteTagged(alicePub, t0)
        state.lock()
        store.deleteAll()
        assertFalse(dir.exists())
        state.setHistoryKeyForTesting(SealedTestKey.bytes())
        assertEquals(Watermark.Untagged, store.taggedSince(alicePub))
    }

    /** Racing notes keep the earliest (`SenderTagStore.swift:26-28`, the serialised read-modify-write). */
    @Test
    fun concurrentNotesKeepTheEarliest() {
        val pool = Executors.newFixedThreadPool(8)
        val start = CountDownLatch(1)
        val times = (0 until 64).map { t0.plusSeconds((it * 7919L) % 64) }
        val done = CountDownLatch(times.size)
        for (t in times) {
            pool.execute {
                start.await()
                store.noteTagged(alicePub, t)
                done.countDown()
            }
        }
        start.countDown()
        assertTrue(done.await(30, TimeUnit.SECONDS))
        pool.shutdown()
        assertEquals(Watermark.Since(times.min()), store.taggedSince(alicePub))
    }
}
