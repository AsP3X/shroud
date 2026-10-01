package de.corespace.shroud.core.messaging.local

import de.corespace.shroud.core.model.ChatMessage
import de.corespace.shroud.core.model.ChatMessageKind
import de.corespace.shroud.core.net.wire.Icu4jTextUnitsRule
import de.corespace.shroud.core.net.wire.MessageReplyReference
import de.corespace.shroud.core.net.wire.MessageTextPayload
import de.corespace.shroud.testing.TempDirRule
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.util.UUID

/**
 * iOS `MessagingLocalPersistTests.swift`: a save that would write what the files already hold must
 * leave them alone (modification times), a per-thread save must cache the same plaintext the full
 * save does, and hydrate scrubs tombstones that kept their content.
 */
class MessagingLocalPersistTest {
    @get:Rule
    val temp = TempDirRule()

    @get:Rule
    val textUnits = Icu4jTextUnitsRule()

    private val fixture by lazy { StoreFixture(temp.noBackupFilesDir) }
    private val peerA = UUID.randomUUID()
    private val peerB = UUID.randomUUID()
    private val messageIds = ArrayList<UUID>()

    /** `testAnUnchangedSaveWritesNothing` (`:23-35`). */
    @Test
    fun anUnchangedSaveWritesNothing() {
        val repository = fixture.repository()
        val threads = mapOf(peerA to messages(3, peerA), peerB to messages(3, peerB))
        fixture.persist(repository, threads)
        val files = listOf(fixture.threadFile(peerA), fixture.threadFile(peerB), fixture.rosterFile) + messageIds.map(fixture::plaintextFile)
        fixture.backdate(*files.toTypedArray())

        fixture.persist(repository, threads)

        for (file in files) assertEquals(file.name, StoreFixture.OLD_MILLIS, file.lastModified())
    }

    /** `testAChangedThreadIsTheOnlyFileRewritten` (`:37-51`). */
    @Test
    fun aChangedThreadIsTheOnlyFileRewritten() {
        val repository = fixture.repository()
        val threads = mutableMapOf(peerA to messages(2, peerA), peerB to messages(2, peerB))
        fixture.persist(repository, threads)
        fixture.backdate(fixture.threadFile(peerA), fixture.threadFile(peerB), fixture.rosterFile)

        threads[peerA] = threads.getValue(peerA) + message(peerA, "new")
        fixture.persist(repository, threads)

        assertNotEquals(StoreFixture.OLD_MILLIS, fixture.threadFile(peerA).lastModified())
        assertEquals(StoreFixture.OLD_MILLIS, fixture.threadFile(peerB).lastModified())
        assertEquals(StoreFixture.OLD_MILLIS, fixture.rosterFile.lastModified())
        val reloaded = fixture.repository().hydrate(fixture.userId)
        assertEquals("new", reloaded.threads[peerA]?.last()?.text)
    }

    /** `testADroppedPeerLosesItsThreadFile` (`:53-61`). */
    @Test
    fun aDroppedPeerLosesItsThreadFile() {
        val repository = fixture.repository()
        fixture.persist(repository, mapOf(peerA to messages(1, peerA), peerB to messages(1, peerB)))

        fixture.persist(repository, mapOf(peerA to messages(1, peerA)))

        assertFalse(fixture.threadFile(peerB).exists())
        assertTrue(fixture.threadFile(peerA).exists())
    }

    /**
     * `testANewKeyForgetsWhatWasWritten` (`:63-75`): a save after the files changed under a fresh
     * unlock still lands — the record of what was written is forgotten with the key.
     */
    @Test
    fun aNewKeyForgetsWhatWasWritten() {
        val repository = fixture.repository()
        val threads = mapOf(peerA to messages(1, peerA))
        fixture.persist(repository, threads)
        assertTrue(fixture.threadFile(peerA).delete())

        fixture.unlock() // iOS `setHistoryKey(key)`: the same key again
        fixture.persist(repository, threads)

        assertTrue(fixture.threadFile(peerA).exists())
    }

    /** `testThreadSaveKeepsALinkImagePayload` (`:77-88`): the link message's cache holds its media payload (and the key to its picture). */
    @Test
    fun threadSaveKeepsALinkImagePayload() {
        val repository = fixture.repository()
        val link = message(peerA, "https://example.com").copy(mediaObjectId = UUID.randomUUID())
        val payload = "{\"t\":\"link\",\"k\":\"blob-key\"}".toByteArray()
        repository.savePlaintext(link.id, link.senderUserId, payload)

        fixture.persistThread(repository, peerA, listOf(link))

        assertArrayEquals(payload, repository.plaintext(link.id, link.senderUserId))
    }

    /** `testThreadSaveCachesTheReplyQuote` (`:90-101`): a reply's cache holds its quote, as the full save writes it. */
    @Test
    fun threadSaveCachesTheReplyQuote() {
        val repository = fixture.repository()
        val reply = message(peerA, "yes").copy(
            replyTo = MessageReplyReference(messageId = UUID.randomUUID(), senderUserId = peerA, kind = MessageReplyReference.Kind.Text, snippet = "ok?"),
        )
        val wire = MessageTextPayload.wire(reply.text, reply.replyTo, null)
        assertNotEquals(reply.text, wire)

        fixture.persistThread(repository, peerA, listOf(reply))

        assertEquals(wire, repository.plaintext(reply.id, reply.senderUserId)?.toString(Charsets.UTF_8))
    }

    /**
     * `testHydrateScrubsATombstoneThatKeptContent` (`:103-133`): older builds merged a missed delete
     * in with the message's content still on the tombstone, and never purged its caches. Reading the
     * store scrubs both and rewrites the thread file.
     */
    @Test
    fun hydrateScrubsATombstoneThatKeptContent() {
        val repository = fixture.repository()
        val tombstone = LocalTombstones.tombstone(message(peerA, "the secret")).copy(
            kind = ChatMessageKind.Voice,
            transcript = "the secret",
            replyTo = MessageReplyReference(messageId = UUID.randomUUID(), senderUserId = peerA, kind = MessageReplyReference.Kind.Text, snippet = "?"),
        )
        val live = message(peerA, "still here")
        repository.savePlaintext(tombstone.id, tombstone.senderUserId, "{\"t\":\"voice\",\"c\":\"the secret\"}".toByteArray())
        fixture.media.put(tombstone.id, "m4a".toByteArray())
        fixture.persistThread(repository, peerA, listOf(tombstone, live))

        val hydrated = fixture.repository().hydrate(fixture.userId)

        val thread = hydrated.threads.getValue(peerA)
        assertEquals(listOf(tombstone.id, live.id), thread.map { it.id })
        // Bare: nothing on it beyond what a tombstone keeps.
        assertEquals(LocalTombstones.tombstone(thread[0]), thread[0])
        assertEquals(ChatMessageKind.Voice, thread[0].kind)
        assertNull(thread[0].transcript)
        assertFalse(thread[0].hasFullMedia)
        assertEquals("still here", thread[1].text)
        val fresh = fixture.repository()
        assertNull(fresh.plaintext(tombstone.id, tombstone.senderUserId))
        assertFalse(fixture.media.has(tombstone.id))
        val rows = fixture.store().loadThread(peerA, fixture.userId)!!
        assertNull(rows[0].transcript)
        assertNull(rows[0].replyTo)
        assertEquals("still here", rows[1].text)
    }

    /** `testHydrateLeavesABareTombstoneAlone` (`:135-148`): a bare tombstone has nothing to scrub. */
    @Test
    fun hydrateLeavesABareTombstoneAlone() {
        val repository = fixture.repository()
        val tombstone = LocalTombstones.tombstone(message(peerA, "gone"))
        fixture.persistThread(repository, peerA, listOf(tombstone))
        fixture.backdate(fixture.threadFile(peerA))

        val hydrated = fixture.repository().hydrate(fixture.userId)

        val thread = hydrated.threads.getValue(peerA)
        assertEquals(listOf(tombstone.id), thread.map { it.id })
        assertEquals(LocalTombstones.tombstone(thread[0]), thread[0])
        assertEquals(StoreFixture.OLD_MILLIS, fixture.threadFile(peerA).lastModified())
    }

    // ---- Helpers (`:150-174`) ----

    private fun message(peer: UUID, text: String): ChatMessage = fixture.message(peer, text, ids = messageIds)

    private fun messages(count: Int, peer: UUID): List<ChatMessage> = (0 until count).map { message(peer, "message $it") }
}
