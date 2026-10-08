package de.corespace.shroud.core.keys

import de.corespace.shroud.core.storage.RecordRead
import de.corespace.shroud.core.storage.ScriptedSealer
import de.corespace.shroud.core.storage.SealResult
import de.corespace.shroud.core.storage.SealedDirectoryStore
import de.corespace.shroud.core.storage.StorageSeal
import de.corespace.shroud.core.crypto.CryptoError
import de.corespace.shroud.core.crypto.CryptoFixtures
import de.corespace.shroud.core.crypto.MessageCrypto
import de.corespace.shroud.core.crypto.TestIdentity
import de.corespace.shroud.core.crypto.hex
import de.corespace.shroud.testing.SealedTestKey
import de.corespace.shroud.testing.TempDirRule
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.util.UUID

/**
 * Double Ratchet sessions behind the `RatchetSessionRecords` seam (iOS
 * `ios/shroud/Services/Crypto/RatchetSessionStore.swift:5-57`; crypto spec §7): sealed under the
 * history key, keyed names, never written while locked or during a wipe.
 */
class RatchetSessionStoreTest {
    @get:Rule
    val temp = TempDirRule()

    private val sealer = ScriptedSealer()
    private val seal = StorageSeal()
    private val state = SealedLocalState().apply { setHistoryKeyForTesting(SealedTestKey.bytes()) }
    private val dir: File get() = temp.noBackupFilesDir.resolve("keys/ratchets")
    private val store by lazy { RatchetSessionStore(SealedDirectoryStore(dir, sealer), state, seal) }
    private val alice = UUID.fromString("8f14e45f-ceea-467a-9575-3a6b7a1e6c0e")
    private val bob = UUID.fromString("11111111-2222-4333-8444-555555555555")
    private val session = """{"rootKey":"cm9vdA==","dhSelfPrivate":"c2VjcmV0","n":3,"pn":1}""".toByteArray()

    @Test
    fun aSessionRoundTripsPerPeer() {
        assertTrue(store.isUnlocked)
        assertNull(store.load(alice))
        store.save(alice, session)
        assertArrayEquals(session, store.load(alice))
        assertNull(store.load(bob))
        val newer = """{"n":4}""".toByteArray()
        store.save(alice, newer)
        assertArrayEquals(newer, store.load(alice))
    }

    @Test
    fun recordsAreNamedByTheKeyedHashNeverTheId() {
        store.save(alice, session)
        val expected = LocalNames.derive(SealedTestKey.bytes()).name(LocalNames.Kind.RATCHET, alice)
        assertEquals(listOf(expected), dir.list()!!.toList())
        assertFalse(expected.contains(alice.toString().take(8)))
        // Upper-case ids name the same file (Ids.wire is lower-case).
        assertArrayEquals(session, store.load(UUID.fromString(alice.toString().uppercase())))
    }

    @Test
    fun theSessionIsNeverOnDiskInTheClear() {
        store.save(alice, session)
        val inner = sealer.open(dir.listFiles()!!.single().readBytes())
        assertFalse(inner.hex().contains(session.hex()))
        assertTrue(de.corespace.shroud.core.crypto.LocalHistoryCrypto.isSealedBlob(inner))
    }

    @Test
    fun aRecordMovedToAnotherPeersNameDoesNotOpen() {
        store.save(alice, session)
        val names = LocalNames.derive(SealedTestKey.bytes())
        File(dir, names.name(LocalNames.Kind.RATCHET, alice)).renameTo(File(dir, names.name(LocalNames.Kind.RATCHET, bob)))
        assertNull(store.load(bob))
    }

    /** `RatchetSessionStore.swift:22-40`: nothing reads or writes while chats are locked. */
    @Test
    fun whileLockedLoadIsNullAndSaveIsDropped() {
        store.save(alice, session)
        state.lock()
        assertFalse(store.isUnlocked)
        assertNull(store.load(alice))
        store.save(bob, session)
        state.setHistoryKeyForTesting(SealedTestKey.bytes())
        assertNull(store.load(bob))
        assertArrayEquals(session, store.load(alice))
    }

    @Test
    fun aWipeInProgressDropsTheSave() {
        seal.seal()
        store.save(alice, session)
        assertEquals(0, sealer.sealCount)
        seal.unseal()
        assertNull(store.load(alice))
    }

    @Test
    fun aFailedWriteIsDroppedNotThrown() {
        sealer.sealFails = true
        store.save(alice, session)
        sealer.sealFails = false
        assertNull(store.load(alice))
    }

    /**
     * A record the phone cannot read right now is there: it must not read as "no session", or a
     * seal starts a fresh initiator session over it and forks the ratchet (crypto D5).
     */
    @Test
    fun aRecordThePhoneCannotReadNowThrowsLockedInsteadOfReadingAsNone() {
        store.save(alice, session)
        for (failure in listOf(SealResult.DeviceLocked, SealResult.Failed)) {
            sealer.readFailure = failure
            assertThrows(CryptoError.Locked::class.java) { store.load(alice) }
            // No record at all is still "no session", whatever the sealer would say.
            assertNull(store.load(bob))
        }
        sealer.readFailure = null
        assertArrayEquals(session, store.load(alice))
    }

    /** End to end through [MessageCrypto]: a locked phone never gets a fresh session written over the stored one. */
    @Test
    fun sealingWhileThePhoneIsLockedLeavesTheStoredSessionAlone() {
        val (ourUser, peerUser) = CryptoFixtures.sortedUserIds()
        val us = TestIdentity.random()
        val peer = TestIdentity.random()
        val crypto = MessageCrypto(store)
        crypto.seal("first".toByteArray(), peerUser, peer.public, us.private, us.public, ourUser)
        val name = LocalNames.derive(SealedTestKey.bytes()).name(LocalNames.Kind.RATCHET, peerUser)
        val stored = File(dir, name).readBytes()
        val seals = sealer.sealCount

        sealer.readFailure = SealResult.DeviceLocked
        sealer.sealFails = true // the WhenUnlocked sealer refuses writes while the phone is locked
        assertThrows(CryptoError.Locked::class.java) {
            crypto.seal("second".toByteArray(), peerUser, peer.public, us.private, us.public, ourUser)
        }
        sealer.readFailure = SealResult.Failed
        sealer.sealFails = false // a transient error while unlocked: a write would succeed
        assertThrows(CryptoError.Locked::class.java) {
            crypto.seal("second".toByteArray(), peerUser, peer.public, us.private, us.public, ourUser)
        }
        assertEquals(seals, sealer.sealCount)
        assertArrayEquals(stored, File(dir, name).readBytes())
    }

    @Test
    fun aRecordThatDoesNotOpenLoadsAsNoSession() {
        store.save(alice, session)
        // A record from another history key does not open.
        val otherState = SealedLocalState().apply { setHistoryKeyForTesting(ByteArray(32) { 1 }) }
        val name = LocalNames.derive(SealedTestKey.bytes()).name(LocalNames.Kind.RATCHET, alice)
        val bytes = (SealedDirectoryStore(dir, sealer).read(name) as RecordRead.Found).bytes
        val otherDir = temp.dir("other")
        val otherName = LocalNames.derive(ByteArray(32) { 1 }).name(LocalNames.Kind.RATCHET, alice)
        SealedDirectoryStore(otherDir, sealer).write(otherName, bytes)
        assertNull(RatchetSessionStore(SealedDirectoryStore(otherDir, sealer), otherState, seal).load(alice))
    }

    /** `:42-49`: the user accepted a changed key. */
    @Test
    fun deleteForgetsOnePeer() {
        store.save(alice, session)
        store.save(bob, session)
        store.delete(alice)
        assertNull(store.load(alice))
        assertArrayEquals(session, store.load(bob))
    }

    /** `:51-57`: sign-out, also while locked. */
    @Test
    fun deleteAllWorksWhileLocked() {
        store.save(alice, session)
        state.lock()
        store.deleteAll()
        assertFalse(dir.exists())
    }

    @Test
    fun theStoreKeepsNoReferenceToTheCallersArray() {
        val json = session.copyOf()
        store.save(alice, json)
        json.fill(0)
        val loaded = store.load(alice)!!
        assertArrayEquals(session, loaded)
        loaded.fill(0)
        assertArrayEquals(session, store.load(alice))
    }
}
