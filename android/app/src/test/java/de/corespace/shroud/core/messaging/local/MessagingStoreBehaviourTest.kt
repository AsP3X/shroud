package de.corespace.shroud.core.messaging.local

import de.corespace.shroud.core.crypto.B64
import de.corespace.shroud.core.messaging.CachedConversation
import de.corespace.shroud.core.messaging.MessagingSnapshot
import de.corespace.shroud.core.messaging.RosterSnapshot
import de.corespace.shroud.core.model.Bytes
import de.corespace.shroud.core.model.ChatMessage
import de.corespace.shroud.core.model.ChatMessageKind
import de.corespace.shroud.core.model.MessageReaction
import de.corespace.shroud.core.model.NOTES_PEER_ID
import de.corespace.shroud.core.model.ReceiptStatus
import de.corespace.shroud.core.net.ContactItemDto
import de.corespace.shroud.core.net.ContactRequestDto
import de.corespace.shroud.core.net.UserCardDto
import de.corespace.shroud.core.net.wire.Icu4jTextUnitsRule
import de.corespace.shroud.core.net.wire.LinkPreview
import de.corespace.shroud.core.net.wire.MessageReplyReference
import de.corespace.shroud.testing.TempDirRule
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.time.ZonedDateTime
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * The Android side of the store (plan §1.4, §1.5, C10; messaging-core §22.3, §23.3, D5): the chat
 * lock, the serial writer, the keyed layout on disk, the annotation index, the reaction cursors,
 * hydrate's envelope previews and media presence, and clearing.
 */
class MessagingStoreBehaviourTest {
    @get:Rule
    val temp = TempDirRule()

    @get:Rule
    val textUnits = Icu4jTextUnitsRule()

    private val fixture by lazy { StoreFixture(temp.noBackupFilesDir) }
    private val peer = UUID.randomUUID()

    // ---- Locked and wiping ----

    @Test
    fun lockedHydrateIsEmptyWithTheNotesKey() {
        val repository = fixture.repository()
        fixture.persist(repository, mapOf(peer to listOf(fixture.message(peer, "hi"))))
        fixture.lock()

        val hydrated = repository.hydrate(fixture.userId)

        assertEquals(mapOf(NOTES_PEER_ID to emptyList<Any>()), hydrated.threads)
        assertEquals(RosterSnapshot(emptyList(), emptyList(), emptyList(), emptyMap()), hydrated.roster)
    }

    @Test
    fun anEmptyStoreHydratesWithTheNotesKey() {
        val hydrated = fixture.repository().hydrate(fixture.userId)
        assertEquals(setOf(NOTES_PEER_ID), hydrated.threads.keys)
    }

    @Test
    fun aNewAccountsFirstSaveWritesItsRosterEvenWhenEmpty() {
        // Notes only, no contacts or chats yet. Threads are read only after a roster
        // (`LocalMessageStore.swift:237-240`), so the empty roster must reach the disk too.
        val repository = fixture.repository()
        repository.hydrate(fixture.userId)
        val note = fixture.message(NOTES_PEER_ID, "remember the milk")

        fixture.persistThread(repository, NOTES_PEER_ID, listOf(note))

        assertTrue(fixture.rosterFile.exists())
        assertEquals(listOf(note.id), fixture.repository().hydrate(fixture.userId).threads[NOTES_PEER_ID]?.map { it.id })
    }

    @Test
    fun nothingIsWrittenWhileLockedOrWhileAWipeRuns() {
        val repository = fixture.repository()
        fixture.lock()
        assertEquals(emptySet<UUID>(), fixture.persist(repository, mapOf(peer to listOf(fixture.message(peer, "hi")))))
        repository.savePlaintext(UUID.randomUUID(), fixture.userId, "x".toByteArray())
        assertNull(repository.reactionCursors(fixture.userId))
        repository.saveReactionCursors(fixture.userId, mapOf(peer to 3))
        assertFalse(fixture.shroudDir.exists())

        fixture.unlock()
        fixture.storageSeal.seal()
        fixture.persist(repository, mapOf(peer to listOf(fixture.message(peer, "hi"))))
        fixture.persistThread(repository, peer, listOf(fixture.message(peer, "hi")))
        repository.savePlaintext(UUID.randomUUID(), fixture.userId, "x".toByteArray())
        repository.saveReactionCursors(fixture.userId, mapOf(peer to 3))
        repository.noteAnnotation(UUID.randomUUID(), UUID.randomUUID())
        assertFalse(fixture.shroudDir.exists())
    }

    @Test
    fun aChatLockDropsThePlaintextKeptInMemory() {
        val repository = fixture.repository()
        val id = UUID.randomUUID()
        repository.savePlaintext(id, fixture.userId, "secret".toByteArray())
        assertEquals("secret", repository.plaintext(id, fixture.userId)?.toString(Charsets.UTF_8))

        fixture.lock()
        assertNull(repository.plaintext(id, fixture.userId))
        // Same key again, file gone: only a plaintext still in memory could answer now.
        assertTrue(fixture.plaintextFile(id).delete())
        fixture.unlock()
        assertNull(repository.plaintext(id, fixture.userId))
    }

    /** Review W2 (invariant 12): message ids are server-chosen; a cached plaintext answers only for its sender. */
    @Test
    fun aCachedPlaintextIsBoundToItsSender() {
        val repository = fixture.repository()
        val id = UUID.randomUUID()
        val alice = UUID.randomUUID()
        val bob = UUID.randomUUID()
        repository.savePlaintext(id, alice, "from alice".toByteArray())
        assertNull(repository.plaintext(id, bob))
        assertEquals("from alice", repository.plaintext(id, alice)?.toString(Charsets.UTF_8))

        // From disk too: the sender is part of the record's AAD.
        val fresh = fixture.repository()
        assertNull(fresh.plaintext(id, bob))
        assertEquals("from alice", fresh.plaintext(id, alice)?.toString(Charsets.UTF_8))
    }

    @Test
    fun lockSensitiveMemoryDropsThePlaintextKeptInMemory() {
        val repository = fixture.repository()
        val id = UUID.randomUUID()
        repository.savePlaintext(id, fixture.userId, "secret".toByteArray())
        repository.lockSensitiveMemory()
        assertTrue(fixture.plaintextFile(id).delete())
        assertNull(repository.plaintext(id, fixture.userId))
    }

    // ---- Serial writer (messaging-core §23.3) ----

    @Test
    fun writesWaitInTheQueueCoalescedPerFileUntilFlush() {
        val repository = fixture.pausedWriter()
        val first = fixture.message(peer, "one")
        fixture.persist(repository, mapOf(peer to listOf(first)))
        val second = fixture.message(peer, "two")
        fixture.persistThread(repository, peer, listOf(first, second))
        assertFalse(fixture.threadFile(peer).exists())
        assertFalse(fixture.rosterFile.exists())
        // The plaintext cache is written at once: a ratchet key is one-shot.
        assertTrue(fixture.plaintextFile(second.id).exists())

        fixture.flush(repository)

        // Roster, the thread (two saves, one write) and the thread clean-up.
        assertEquals(3, repository.writer.completedWrites.get())
        assertEquals(listOf("one", "two"), fixture.repository().hydrate(fixture.userId).threads[peer]?.map { it.text })
    }

    @Test
    fun aLaterSaveOfAFileRunsAfterAnEarlierCleanUp() {
        val repository = fixture.pausedWriter()
        val other = UUID.randomUUID()
        fixture.persist(repository, mapOf(peer to listOf(fixture.message(peer, "a"))))
        // The peer leaves the snapshot (its file is cleaned up) and then comes back in a thread save.
        fixture.persist(repository, mapOf(other to listOf(fixture.message(other, "b"))))
        fixture.persistThread(repository, peer, listOf(fixture.message(peer, "back")))

        fixture.flush(repository)

        assertTrue(fixture.threadFile(peer).exists())
        assertTrue(fixture.threadFile(other).exists())
    }

    @Test
    fun lockSensitiveMemoryFlushesBeforeTheKeyGoes() {
        val repository = fixture.pausedWriter()
        fixture.persist(repository, mapOf(peer to listOf(fixture.message(peer, "kept"))))
        assertFalse(fixture.threadFile(peer).exists())

        repository.lockSensitiveMemory()
        fixture.lock()

        assertTrue(fixture.threadFile(peer).exists())
        fixture.unlock()
        assertEquals("kept", fixture.repository().hydrate(fixture.userId).threads[peer]?.single()?.text)
    }

    @Test
    fun aLockBeforeTheWriterRunsLosesNothing() {
        // Writes are sealed before they are queued: the writer needs no key.
        val repository = fixture.pausedWriter()
        fixture.persist(repository, mapOf(peer to listOf(fixture.message(peer, "kept"))))
        fixture.lock()
        fixture.flush(repository)
        fixture.unlock()
        assertEquals("kept", fixture.repository().hydrate(fixture.userId).threads[peer]?.single()?.text)
    }

    @Test
    fun savesFromManyThreadsOnTheRealWriterEndInTheLastState() {
        // The production writer (one IO thread) against eight callers saving their own peers at once.
        val repository = fixture.repository(writerScope = CoroutineScope(Dispatchers.IO.limitedParallelism(1)))
        val peers = List(8) { UUID.randomUUID() }
        val last = ConcurrentHashMap<UUID, List<UUID>>()
        val workers = peers.map { p ->
            Thread {
                var thread = emptyList<ChatMessage>()
                repeat(25) { n ->
                    val message = fixture.message(p, "message $n")
                    thread = thread + message
                    repository.savePlaintext(message.id, message.senderUserId, "sealed $n".toByteArray())
                    fixture.persistThread(repository, p, thread)
                    if (n % 5 == 0) repository.removeCaches(listOf(message.id))
                }
                last[p] = thread.map { it.id }
            }
        }
        workers.forEach(Thread::start)
        workers.forEach(Thread::join)
        fixture.flush(repository)

        val hydrated = fixture.repository().hydrate(fixture.userId)
        for (p in peers) assertEquals(last[p], hydrated.threads[p]?.map { it.id })
    }

    @Test
    fun hydrateAndCursorReadsSeeQueuedWrites() {
        val repository = fixture.pausedWriter()
        repository.saveReactionCursors(fixture.userId, mapOf(peer to 41))
        assertEquals(mapOf(peer to 41L), repository.reactionCursors(fixture.userId))
        fixture.persist(repository, mapOf(peer to listOf(fixture.message(peer, "queued"))))
        assertEquals("queued", repository.hydrate(fixture.userId).threads[peer]?.single()?.text)
    }

    // ---- Layout on disk (acceptance: hashed names, every file SHRD1) ----

    @Test
    fun everyFileUnderShroudHasAKeyedNameAndIsSealed() {
        val repository = fixture.repository()
        val voice = fixture.message(peer, "Voice message").copy(kind = ChatMessageKind.Voice)
        val note = fixture.message(NOTES_PEER_ID, "remember")
        val annotation = UUID.randomUUID()
        fixture.persist(
            repository,
            mapOf(peer to listOf(fixture.message(peer, "hello"), voice), NOTES_PEER_ID to listOf(note)),
            RosterSnapshot(
                conversations = listOf(CachedConversation(UUID.randomUUID(), peer, "alice", fixture.now, fixture.now, 4, 1)),
                contacts = listOf(ContactItemDto(peer, "alice", fixture.now)),
                incomingRequests = emptyList(),
                unreadByPeer = mapOf(peer to 2),
            ),
        )
        repository.savePlaintext(annotation, fixture.userId, "{\"t\":\"transcript\"}".toByteArray())
        repository.noteAnnotation(voice.id, annotation)
        repository.saveReactionCursors(fixture.userId, mapOf(peer to 7))

        val files = fixture.shroudDir.walkTopDown().filter { it.isFile }.toList()
        assertTrue(files.size >= 8)
        val hex = "[0-9a-f]{32}"
        val shape = Regex("messages/$hex/(roster|reactions|annotations)\\.sealed|messages/$hex/threads/$hex\\.sealed|plaintext/$hex\\.sealed")
        val ids = listOf(fixture.userId, peer, NOTES_PEER_ID, voice.id, note.id, annotation)
        for (file in files) {
            val relative = file.relativeTo(fixture.shroudDir).path
            assertTrue(relative, shape.matches(relative))
            for (id in ids) assertFalse(relative.contains(id.toString(), ignoreCase = true))
            assertArrayEquals(relative, "SHRD1".toByteArray(), file.readBytes().copyOf(5))
            assertFalse(relative, String(file.readBytes(), Charsets.ISO_8859_1).contains("alice"))
        }
    }

    // ---- Annotation index (D5) ----

    @Test
    fun purgingAVoiceMessagePurgesItsTranscriptAnnotations() {
        val repository = fixture.repository()
        repository.hydrate(fixture.userId)
        val voice = UUID.randomUUID()
        val annotation = UUID.randomUUID()
        repository.savePlaintext(annotation, fixture.userId, "{\"t\":\"transcript\",\"c\":\"Bis gleich!\"}".toByteArray())
        repository.noteAnnotation(voice, annotation)
        assertEquals(setOf(annotation), repository.annotationsFor(voice))
        // The index outlives the process.
        assertEquals(setOf(annotation), fixture.repository().also { it.hydrate(fixture.userId) }.annotationsFor(voice))

        repository.removeCaches(listOf(voice))

        assertNull(repository.plaintext(annotation, fixture.userId))
        assertFalse(fixture.plaintextFile(annotation).exists())
        assertEquals(emptySet<UUID>(), repository.annotationsFor(voice))
        assertEquals(emptySet<UUID>(), fixture.repository().also { it.hydrate(fixture.userId) }.annotationsFor(voice))
    }

    @Test
    fun aPurgedAnnotationLeavesItsTarget() {
        val repository = fixture.repository()
        repository.hydrate(fixture.userId)
        val voice = UUID.randomUUID()
        val first = UUID.randomUUID()
        val second = UUID.randomUUID()
        repository.noteAnnotation(voice, first)
        repository.noteAnnotation(voice, second)

        repository.removeCaches(listOf(first))

        assertEquals(setOf(second), repository.annotationsFor(voice))
    }

    @Test
    fun anAnnotationNotedBeforeTheAccountIsKnownIsKept() {
        val repository = fixture.repository()
        val voice = UUID.randomUUID()
        val annotation = UUID.randomUUID()
        repository.noteAnnotation(voice, annotation)
        assertEquals(setOf(annotation), repository.annotationsFor(voice))

        repository.hydrate(fixture.userId)

        assertTrue(fixture.annotationsFile.exists())
        assertEquals(setOf(annotation), fixture.repository().also { it.hydrate(fixture.userId) }.annotationsFor(voice))
    }

    @Test
    fun hydrateScrubPurgesTheTombstonesAnnotationsToo() {
        val repository = fixture.repository()
        repository.hydrate(fixture.userId)
        val voice = fixture.message(peer, "Voice message").copy(kind = ChatMessageKind.Voice, transcript = "hello there")
        val annotation = UUID.randomUUID()
        repository.savePlaintext(annotation, fixture.userId, "{\"t\":\"transcript\"}".toByteArray())
        repository.noteAnnotation(voice.id, annotation)
        fixture.persistThread(repository, peer, listOf(voice.copy(deleted = true)))

        fixture.repository().hydrate(fixture.userId)

        assertFalse(fixture.plaintextFile(annotation).exists())
    }

    @Test
    fun aPurgeWhileChatsAreLockedRunsAfterTheNextUnlock() {
        val repository = fixture.repository()
        repository.hydrate(fixture.userId)
        val id = UUID.randomUUID()
        repository.savePlaintext(id, fixture.userId, "secret".toByteArray())
        fixture.media.put(id, "jpeg".toByteArray())
        fixture.lock()

        repository.removeCaches(listOf(id))
        assertTrue(fixture.plaintextFile(id).exists()) // its keyed name is unknown while locked
        assertFalse(fixture.media.has(id))

        fixture.unlock()
        repository.hydrate(fixture.userId)
        assertFalse(fixture.plaintextFile(id).exists())
    }

    // ---- Reaction cursors (`:362-384`) ----

    @Test
    fun reactionCursorsRoundTripAndFailClosed() {
        val repository = fixture.repository()
        assertEquals(emptyMap<UUID, Long>(), repository.reactionCursors(fixture.userId))
        val other = UUID.randomUUID()
        repository.saveReactionCursors(fixture.userId, mapOf(peer to 12, other to 7))
        assertEquals(mapOf(peer to 12L, other to 7L), fixture.repository().reactionCursors(fixture.userId))

        // A file that does not open is null, never an empty map a later save would overwrite every cursor with.
        fixture.reactionsFile.writeBytes(junkBlob())
        assertNull(repository.reactionCursors(fixture.userId))
    }

    // ---- Hydrate: roster, unread, media presence and envelope previews ----

    @Test
    fun hydrateReturnsTheRosterAndOnlyPositiveUnread() {
        val repository = fixture.repository()
        val other = UUID.randomUUID()
        val from = UUID.randomUUID()
        val conversation = CachedConversation(UUID.randomUUID(), peer, "alice", fixture.now, fixture.now, 9, 2)
        val contact = ContactItemDto(peer, "alice", fixture.now)
        val request = ContactRequestDto(UUID.randomUUID(), from, fixture.userId, "pending", fixture.now, null, UserCardDto(from, "carol", null))
        repository.persist(
            fixture.userId,
            MessagingSnapshot(RosterSnapshot(listOf(conversation), listOf(contact), listOf(request), mapOf(peer to 3, other to 0)), mapOf(peer to listOf(fixture.message(peer, "hi")))),
        )

        val hydrated = fixture.repository().hydrate(fixture.userId)

        assertEquals(RosterSnapshot(listOf(conversation), listOf(contact), listOf(request), mapOf(peer to 3)), hydrated.roster)
    }

    @Test
    fun hydrateRestoresMediaPresenceAndTheEnvelopePreview() {
        // `attachEnvelopePreview` (`MessagingLocalRepository.swift:87-103`) and `toChatMessage` (`LocalMessageStore.swift:139-160`).
        val repository = fixture.repository()
        val thumb = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte())
        val image = fixture.message(peer, "Photo").copy(kind = ChatMessageKind.Image, mediaObjectId = UUID.randomUUID(), imageWidth = 4, imageHeight = 3)
        val video = fixture.message(peer, "Video").copy(kind = ChatMessageKind.Video, mediaObjectId = UUID.randomUUID(), durationMs = 1200)
        val link = fixture.message(peer, "https://example.com/a").copy(mediaObjectId = UUID.randomUUID(), linkPreview = LinkPreview("https://example.com/a", title = "A"))
        repository.savePlaintext(image.id, image.senderUserId, payload("image", thumb, 1500))
        repository.savePlaintext(video.id, video.senderUserId, payload("video", thumb, 2_500_000))
        repository.savePlaintext(link.id, link.senderUserId, payload("link", thumb, 900))
        fixture.media.put(image.id, "jpeg".toByteArray())
        fixture.persistThread(repository, peer, listOf(image, video, link))

        val thread = fixture.repository().hydrate(fixture.userId).threads.getValue(peer)

        val (i, v, l) = thread
        assertTrue(i.hasFullMedia)
        assertEquals(Bytes.of(thumb), i.previewJpeg)
        assertEquals(1500L, i.mediaByteCount)
        assertNull(i.posterJpeg)
        assertFalse(v.hasFullMedia)
        assertEquals(Bytes.of(thumb), v.previewJpeg)
        assertEquals(Bytes.of(thumb), v.posterJpeg) // a video keeps the preview as its poster
        assertEquals(2_500_000L, v.mediaByteCount)
        assertEquals(Bytes.of(thumb), l.previewJpeg)
        assertEquals(900L, l.mediaByteCount)
        // The link message's media payload (with its blob key) was not replaced by its text wire.
        assertTrue(repository.plaintext(link.id, link.senderUserId)!!.toString(Charsets.UTF_8).contains("\"k\":\"blob-key\""))
    }

    // ---- Written-state skip across a cold start (`MessagingLocalRepository.swift:165-176`) ----

    /**
     * Hydrate records what it read as what the files hold, so the controller's first save after an
     * unlock — the hydrated state handed straight back — rewrites nothing: not the roster, not a
     * thread, not a cached plaintext. Every stored field takes part (quote, link preview, reactions,
     * waveform, microsecond dates, the roster's DTO bridges), so a field that does not survive the
     * row round trip shows up here as a rewritten file.
     */
    @Test
    fun aSaveOfTheHydratedStateWritesNothing() {
        val repository = fixture.repository()
        val me = fixture.userId
        val from = UUID.randomUUID()
        val at = fixture.now.minusSeconds(3_600).plusNanos(123_456_000)
        val reply = fixture.message(peer, "yes", createdAt = at).copy(
            createdAtWire = "2026-09-21T13:13:20.123456Z",
            replyTo = MessageReplyReference(UUID.randomUUID(), me, MessageReplyReference.Kind.Text, "ok?"),
            reactions = listOf(MessageReaction(me, listOf("🔥", "👍"), 42), MessageReaction(peer, emptyList(), 7)),
            receipt = ReceiptStatus.Read,
        )
        val link = fixture.message(peer, "https://komoot.com/tour/1398273").copy(
            linkPreview = LinkPreview(
                url = "https://komoot.com/tour/1398273",
                siteName = "komoot",
                title = "Herzogstand – Heimgarten ridge walk",
                summary = "Intermediate hike · 13.6 km",
                thumbnail = Bytes.of(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte(), 0x00, 0x10, 0x4A, 0x46)),
                imageWidth = 1200,
                imageHeight = 630,
            ),
        )
        val voice = fixture.message(peer, "Voice message").copy(
            kind = ChatMessageKind.Voice,
            mediaObjectId = UUID.randomUUID(),
            durationMs = 2_300,
            voiceWaveform = Bytes.of(byteArrayOf(0, 127, 128.toByte(), 255.toByte())),
            transcript = "Bis gleich!",
        )
        val pending = fixture.message(peer, "on its way").copy(isMine = true, senderUserId = me, receipt = ReceiptStatus.Sending, pendingSync = true)
        val gone = LocalTombstones.tombstone(fixture.message(peer, "gone"))
        val note = fixture.message(NOTES_PEER_ID, "[todo:0]Buy milk").copy(kind = ChatMessageKind.Todo, todoDone = false)
        repository.savePlaintext(voice.id, voice.senderUserId, "{\"t\":\"voice\",\"mime\":\"audio/mp4\",\"k\":\"blob-key\"}".toByteArray())
        fixture.persist(
            repository,
            mapOf(peer to listOf(reply, link, voice, pending, gone), NOTES_PEER_ID to listOf(note)),
            RosterSnapshot(
                conversations = listOf(CachedConversation(UUID.randomUUID(), peer, "alice", fixture.now.minusSeconds(86_400), at, 9, 2)),
                contacts = listOf(ContactItemDto(peer, "alice", fixture.now.minusSeconds(86_400))),
                incomingRequests = listOf(ContactRequestDto(UUID.randomUUID(), from, me, "pending", at, null, UserCardDto(from, "carol", null))),
                unreadByPeer = mapOf(peer to 3),
            ),
        )
        val files = fixture.shroudDir.walkTopDown().filter { it.isFile }.toList()
        assertEquals(7, files.size) // roster, two threads, three text plaintexts and the voice payload
        fixture.backdate(*files.toTypedArray())

        val fresh = fixture.repository()
        val hydrated = fresh.hydrate(fixture.userId)
        fresh.persist(fixture.userId, MessagingSnapshot(hydrated.roster, hydrated.threads))
        fixture.persistThread(fresh, peer, hydrated.threads.getValue(peer), hydrated.roster)

        assertEquals(listOf(reply.id, link.id, voice.id, pending.id, gone.id), hydrated.threads[peer]?.map { it.id })
        assertEquals(reply, hydrated.threads.getValue(peer)[0])
        for (file in files) assertEquals(file.relativeTo(fixture.shroudDir).path, StoreFixture.OLD_MILLIS, file.lastModified())
    }

    // ---- Retention through the repository ----

    @Test
    fun persistReturnsAndPurgesWhatTheRetentionDrops() {
        val repository = fixture.repository()
        val old = fixture.message(peer, "old", createdAt = daysAgo(91))
        val pendingOld = fixture.message(peer, "pending", createdAt = daysAgo(91)).copy(pendingSync = true)
        val recent = fixture.message(peer, "recent", createdAt = daysAgo(1))
        val ancientNote = fixture.message(NOTES_PEER_ID, "note", createdAt = daysAgo(400))
        repository.savePlaintext(old.id, old.senderUserId, "old".toByteArray())
        fixture.media.put(old.id, "m4a".toByteArray())

        val dropped = fixture.persist(repository, mapOf(peer to listOf(old, pendingOld, recent), NOTES_PEER_ID to listOf(ancientNote)))

        assertEquals(setOf(old.id), dropped)
        assertFalse(fixture.plaintextFile(old.id).exists())
        assertFalse(fixture.media.has(old.id))
        val hydrated = fixture.repository().hydrate(fixture.userId)
        assertEquals(listOf(pendingOld.id, recent.id), hydrated.threads[peer]?.map { it.id })
        assertEquals(listOf(ancientNote.id), hydrated.threads[NOTES_PEER_ID]?.map { it.id })
    }

    @Test
    fun persistThreadPrunesThatPeerAloneAndReturnsTheDroppedIds() {
        val repository = fixture.repository()
        val old = fixture.message(peer, "old", createdAt = daysAgo(100))
        val recent = fixture.message(peer, "recent")
        repository.savePlaintext(old.id, old.senderUserId, "old".toByteArray())

        val dropped = fixture.persistThread(repository, peer, listOf(old, recent))

        assertEquals(setOf(old.id), dropped)
        // Not re-cached after the prune (iOS re-caches the pruned text, `MessagingLocalRepository.swift:256-261`).
        assertFalse(fixture.plaintextFile(old.id).exists())
        assertEquals(setOf(old.id), fixture.persistThread(repository, peer, listOf(old, recent)))
        val note = fixture.message(NOTES_PEER_ID, "note", createdAt = daysAgo(400))
        assertEquals(emptySet<UUID>(), fixture.persistThread(repository, NOTES_PEER_ID, listOf(note)))
    }

    // ---- Clear (`:386-404`) ----

    @Test
    fun clearDeletesTheAccountThePlaintextAndTheMedia() {
        val repository = fixture.repository()
        fixture.persist(repository, mapOf(peer to listOf(fixture.message(peer, "hi"))))
        assertTrue(fixture.userDir.exists())
        fixture.media.put(UUID.randomUUID(), "jpeg".toByteArray())
        val otherAccount = fixture.store()
        val stranger = UUID.randomUUID()
        otherAccount.saveRoster(Roster(), stranger)

        repository.clear(fixture.userId)

        assertFalse(fixture.userDir.exists())
        assertFalse(File(fixture.shroudDir, "plaintext").exists())
        assertEquals(1, fixture.media.clearedAll)
        assertTrue(otherAccount.rosterLocation(stranger)!!.file.exists())
        assertEquals(setOf(NOTES_PEER_ID), repository.hydrate(fixture.userId).threads.keys)

        repository.clear(null)
        assertFalse(File(fixture.shroudDir, "messages").exists())
    }

    @Test
    fun clearDropsTheQueuedWrites() {
        val repository = fixture.pausedWriter()
        fixture.persist(repository, mapOf(peer to listOf(fixture.message(peer, "hi"))))

        repository.clear(fixture.userId)
        fixture.flush(repository)

        assertFalse(fixture.userDir.exists())
        assertEquals(0, repository.writer.completedWrites.get())
    }

    private fun daysAgo(days: Long) = ZonedDateTime.ofInstant(fixture.now, fixture.zone).minusDays(days).toInstant()

    private fun payload(kind: String, thumb: ByteArray, size: Long): ByteArray =
        ("{\"t\":\"$kind\",\"mime\":\"image/jpeg\",\"w\":4,\"h\":3,\"k\":\"blob-key\",\"th\":\"${B64.encode(thumb)}\",\"s\":$size" +
            (if (kind == "link") ",\"lp\":{\"u\":\"https://example.com/a\"}" else "") + "}").toByteArray()

    /** Has the magic but is no record of this key and location. */
    private fun junkBlob(): ByteArray = "SHRD1".toByteArray() + ByteArray(64) { it.toByte() }
}
