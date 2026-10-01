package de.corespace.shroud.core.messaging.local

import de.corespace.shroud.core.crypto.LocalHistoryCrypto
import de.corespace.shroud.core.keys.SealedLocalState
import de.corespace.shroud.core.model.NOTES_PEER_ID
import de.corespace.shroud.testing.TempDirRule
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.time.Instant
import java.time.ZonedDateTime
import java.util.UUID

/**
 * The roster + per-thread files and retention: iOS `LocalMessageStoreTests.swift:5-147` and the
 * store part of `LocalHistoryCryptoTests.swift:67-101`, plus the Android layout (keyed names,
 * path-bound AAD; plan §1.5, C10).
 */
class LocalMessageStoreTest {
    @get:Rule
    val temp = TempDirRule()

    private val fixture by lazy { StoreFixture(temp.noBackupFilesDir) }

    // ---- LocalMessageStoreTests ----

    /** `testPruneDropsPeerMessagesOlderThan90Days` (`:6-27`). */
    @Test
    fun pruneDropsPeerMessagesOlderThan90Days() {
        val store = fixture.store()
        val peer = UUID.randomUUID()
        val now = fixture.now
        val old = daysBefore(now, 91)
        val recent = daysBefore(now, 10)

        val oldMsg = sampleStored(UUID.randomUUID(), peer, old, pending = false)
        val recentMsg = sampleStored(UUID.randomUUID(), peer, recent, pending = false)
        val pendingOld = sampleStored(UUID.randomUUID(), peer, old, pending = true)

        val snapshot = LocalMessageStore.Snapshot(threads = mapOf(peer to listOf(oldMsg, recentMsg, pendingOld)))
        val (pruned, dropped) = store.prune(snapshot, now)
        val kept = pruned.threads[peer].orEmpty()

        assertEquals(setOf(oldMsg.id), dropped.toSet())
        assertEquals(setOf(recentMsg.id, pendingOld.id), kept.map { it.id }.toSet())
    }

    /** `testPruneKeepsNotesRegardlessOfAge` (`:29-46`). */
    @Test
    fun pruneKeepsNotesRegardlessOfAge() {
        val store = fixture.store()
        val now = fixture.now
        val note = sampleStored(UUID.randomUUID(), NOTES_PEER_ID, daysBefore(now, 400), pending = false)

        val (pruned, dropped) = store.prune(LocalMessageStore.Snapshot(threads = mapOf(NOTES_PEER_ID to listOf(note))), now)

        assertTrue(dropped.isEmpty())
        assertEquals(listOf(note.id), pruned.threads[NOTES_PEER_ID].orEmpty().map { it.id })
    }

    /** `testPerPeerSaveDoesNotRequireFullSnapshot` (`:48-94`). */
    @Test
    fun perPeerSaveDoesNotRequireFullSnapshot() {
        val store = fixture.store()
        val userId = UUID.randomUUID()
        val peerA = UUID.randomUUID()
        val peerB = UUID.randomUUID()
        val msgA = sampleStored(UUID.randomUUID(), peerA, fixture.now, pending = false)
        val msgB = sampleStored(UUID.randomUUID(), peerB, fixture.now, pending = false)

        store.saveThread(peerA, listOf(msgA), userId)
        store.saveThread(peerB, listOf(msgB), userId)
        store.saveRoster(Roster(conversations = listOf(StoredConversation(UUID.randomUUID(), peerA, "alice", fixture.now, fixture.now))), userId)

        // Update only peer A — peer B must remain.
        val msgA2 = sampleStored(UUID.randomUUID(), peerA, fixture.now, pending = false)
        store.saveThread(peerA, listOf(msgA, msgA2), userId)

        assertEquals(listOf(msgA.id, msgA2.id), store.loadThread(peerA, userId)?.map { it.id })
        assertEquals(listOf(msgB.id), store.loadThread(peerB, userId)?.map { it.id })

        val full = store.load(userId)
        assertEquals("alice", full?.conversations?.first()?.peerUsername)
        assertEquals(listOf(msgB.id), full?.threads?.get(peerB)?.map { it.id })

        store.clear(userId)
        assertFalse(store.userDirectory(userId)!!.exists())
    }

    /**
     * `testLegacySnapshotMigratesToRosterAndThreads` (`:96-126`): iOS writes the split layout and
     * re-loads it (the legacy migration itself has no Android counterpart).
     */
    @Test
    fun savedSnapshotReloadsAsRosterAndThreads() {
        val store = fixture.store()
        val userId = UUID.randomUUID()
        val peer = UUID.randomUUID()
        val msg = sampleStored(UUID.randomUUID(), peer, fixture.now, pending = false)
        val snapshot = LocalMessageStore.Snapshot(
            conversations = listOf(StoredConversation(UUID.randomUUID(), peer, "bob", fixture.now, fixture.now)),
            threads = mapOf(peer to listOf(msg)),
        )

        store.save(snapshot, userId)
        val loaded = store.load(userId)

        assertEquals(msg.id, loaded?.threads?.get(peer)?.first()?.id)
        assertEquals("bob", loaded?.conversations?.first()?.peerUsername)
        store.clear(userId)
    }

    // ---- LocalHistoryCryptoTests (store part) ----

    /** `testMessageStoreSavesSealedOnly` (`LocalHistoryCryptoTests.swift:67-101`). */
    @Test
    fun messageStoreSavesSealedOnly() {
        val store = fixture.store()
        val userId = UUID.randomUUID()
        store.save(
            LocalMessageStore.Snapshot(conversations = listOf(StoredConversation(UUID.randomUUID(), UUID.randomUUID(), "alice", fixture.now, fixture.now))),
            userId,
        )

        val onDisk = store.rosterLocation(userId)!!.file.readBytes()
        assertTrue(LocalHistoryCrypto.isSealedBlob(onDisk))
        assertFalse(String(onDisk, Charsets.ISO_8859_1).contains("alice"))
        assertFalse(java.io.File(store.userDirectory(userId), "snapshot.json").exists())

        // Wrong key cannot load conversations.
        val wrong = fixture.store(SealedLocalState().also { it.unlock(ByteArray(32) { 7 }) }).load(userId)
        assertTrue(wrong == null || wrong.conversations.isEmpty())
        // Correct key loads.
        assertEquals("alice", store.load(userId)?.conversations?.first()?.peerUsername)

        store.clear(userId)
    }

    // ---- Android: layout, AAD, retention of conversations, locked behaviour ----

    @Test
    fun aThreadFileMovedToAnotherPeersNameDoesNotOpen() {
        val store = fixture.store()
        val userId = UUID.randomUUID()
        val peerA = UUID.randomUUID()
        val peerB = UUID.randomUUID()
        store.saveRoster(Roster(), userId)
        store.saveThread(peerA, listOf(sampleStored(UUID.randomUUID(), peerA, fixture.now, false)), userId)
        store.saveThread(peerB, listOf(sampleStored(UUID.randomUUID(), peerB, fixture.now, false)), userId)

        // Swap the two peers' threads on disk (web-parity §3.2: the location is the AAD).
        val a = store.threadLocation(peerA, userId)!!.file
        val b = store.threadLocation(peerB, userId)!!.file
        val bytesA = a.readBytes()
        a.writeBytes(b.readBytes())
        b.writeBytes(bytesA)

        assertNull(store.loadThread(peerA, userId))
        assertNull(store.loadThread(peerB, userId))
        assertTrue(store.load(userId)!!.threads.isEmpty())
    }

    @Test
    fun aRosterCopiedIntoAnotherAccountDoesNotOpen() {
        val store = fixture.store()
        val alice = UUID.randomUUID()
        val mallory = UUID.randomUUID()
        store.saveRoster(Roster(contacts = listOf(CachedContact(UUID.randomUUID(), "bob", fixture.now))), alice)
        store.saveRoster(Roster(), mallory)
        store.rosterLocation(alice)!!.file.copyTo(store.rosterLocation(mallory)!!.file, overwrite = true)

        assertNull(store.loadRoster(mallory))
        assertEquals("bob", store.loadRoster(alice)?.contacts?.single()?.username)
    }

    @Test
    fun namesOnDiskAreKeyedHashesAndEveryFileIsSealed() {
        val store = fixture.store()
        val userId = UUID.randomUUID()
        val peer = UUID.randomUUID()
        store.save(
            LocalMessageStore.Snapshot(
                conversations = listOf(StoredConversation(UUID.randomUUID(), peer, "alice", fixture.now)),
                threads = mapOf(peer to listOf(sampleStored(UUID.randomUUID(), peer, fixture.now, false)), NOTES_PEER_ID to emptyList()),
                unreadByPeer = mapOf(peer.toString() to 2),
            ),
            userId,
        )
        store.saveReactionCursors(ReactionCursorsFile(byPeer = mapOf(peer.toString() to 9)), userId)

        val files = store.messagesDir.walkTopDown().filter { it.isFile }.toList()
        assertEquals(4, files.size) // roster, reactions, two threads
        for (file in files) {
            val relative = file.relativeTo(store.messagesDir).path
            assertTrue(relative, Regex("[0-9a-f]{32}/(roster|reactions)\\.sealed|[0-9a-f]{32}/threads/[0-9a-f]{32}\\.sealed").matches(relative))
            for (id in listOf(userId, peer, NOTES_PEER_ID)) assertFalse(relative.contains(id.toString(), ignoreCase = true))
            assertArrayEquals(relative, "SHRD1".toByteArray(), file.readBytes().copyOf(5))
        }
    }

    @Test
    fun conversationsStayWhileTheirPeerHasAThreadOrWasRecent() {
        // `prune`, `:406-413`.
        val store = fixture.store()
        val now = fixture.now
        val withThread = StoredConversation(UUID.randomUUID(), UUID.randomUUID(), "a", daysBefore(now, 400), daysBefore(now, 300))
        val recent = StoredConversation(UUID.randomUUID(), UUID.randomUUID(), "b", daysBefore(now, 400), daysBefore(now, 5))
        val stale = StoredConversation(UUID.randomUUID(), UUID.randomUUID(), "c", daysBefore(now, 400), daysBefore(now, 91))
        val newButEmpty = StoredConversation(UUID.randomUUID(), UUID.randomUUID(), "d", daysBefore(now, 3), null)
        val oldAndEmpty = StoredConversation(UUID.randomUUID(), UUID.randomUUID(), "e", daysBefore(now, 91), null)
        val snapshot = LocalMessageStore.Snapshot(
            conversations = listOf(withThread, recent, stale, newButEmpty, oldAndEmpty),
            threads = mapOf(withThread.peerId to listOf(sampleStored(UUID.randomUUID(), withThread.peerId, daysBefore(now, 1), false))),
        )

        val (pruned, _) = store.prune(snapshot, now)

        assertEquals(listOf("a", "b", "d"), pruned.conversations.map { it.peerUsername })
    }

    @Test
    fun aThreadLeftEmptyByThePruneGoes() {
        val store = fixture.store()
        val peer = UUID.randomUUID()
        val old = sampleStored(UUID.randomUUID(), peer, daysBefore(fixture.now, 120), false)
        val (pruned, dropped) = store.prune(LocalMessageStore.Snapshot(threads = mapOf(peer to listOf(old), NOTES_PEER_ID to emptyList())), fixture.now)
        assertFalse(pruned.threads.containsKey(peer))
        assertTrue(pruned.threads.containsKey(NOTES_PEER_ID))
        assertEquals(listOf(old.id), dropped)
    }

    @Test
    fun theCutoffIs90CalendarDaysInThePhonesTimeZone() {
        // Across the end of daylight saving time in Berlin: 90 calendar days (13:00 CET → 13:00 CEST),
        // one hour short of 90 × 24 h, which is what the same instant gives in UTC.
        val now = Instant.parse("2026-12-01T12:00:00Z")
        assertEquals(Instant.parse("2026-09-02T11:00:00Z"), fixture.store().cutoff(now))
        val utc = LocalMessageStore(temp.noBackupFilesDir, LocalStoreKeys(fixture.state), fixture.clock, fixture.storageSeal) { java.time.ZoneOffset.UTC }
        assertEquals(now.minusSeconds(90L * 86_400), utc.cutoff(now))
    }

    @Test
    fun reactionCursorsAreEmptyWhenAbsentAndNullWhenUnreadable() {
        val store = fixture.store()
        val userId = UUID.randomUUID()
        assertEquals(ReactionCursorsFile(), store.loadReactionCursors(userId))
        store.saveReactionCursors(ReactionCursorsFile(byPeer = mapOf("p" to 4)), userId)
        assertEquals(mapOf("p" to 4L), store.loadReactionCursors(userId)?.byPeer)
        store.reactionCursorsLocation(userId)!!.file.writeBytes("SHRD1 not a record at all, really".toByteArray())
        assertNull(store.loadReactionCursors(userId))
    }

    @Test
    fun nothingIsNamedReadOrWrittenWhileChatsAreLocked() {
        val store = fixture.store()
        val userId = UUID.randomUUID()
        store.saveRoster(Roster(contacts = listOf(CachedContact(UUID.randomUUID(), "bob", fixture.now))), userId)
        fixture.lock()

        assertNull(store.load(userId))
        assertNull(store.loadRoster(userId))
        assertNull(store.loadReactionCursors(userId))
        assertNull(store.sealThread(UUID.randomUUID(), emptyList(), userId))
        assertNull(store.threadCleanup(emptySet(), userId))

        // Clearing an account needs its name: while locked every account's store goes.
        store.clear(userId)
        assertFalse(store.messagesDir.exists())
    }

    @Test
    fun aWipeDropsEveryWrite() {
        val store = fixture.store()
        val userId = UUID.randomUUID()
        fixture.storageSeal.seal()
        store.saveRoster(Roster(), userId)
        store.saveThread(UUID.randomUUID(), emptyList(), userId)
        assertFalse(store.messagesDir.exists())
    }

    private fun daysBefore(now: Instant, days: Long): Instant = ZonedDateTime.ofInstant(now, fixture.zone).minusDays(days).toInstant()

    /** `sampleStored` (`LocalMessageStoreTests.swift:128-146`). */
    private fun sampleStored(id: UUID, peer: UUID, createdAt: Instant, pending: Boolean) = StoredMessage(
        id = id,
        peerUserId = peer,
        senderUserId = UUID.randomUUID(),
        text = "hello",
        createdAt = createdAt,
        isMine = true,
        deleted = false,
        receipt = "sent",
        kind = "text",
        pendingSync = if (pending) true else null,
    )
}
