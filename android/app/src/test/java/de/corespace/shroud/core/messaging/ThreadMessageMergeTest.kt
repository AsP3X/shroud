package de.corespace.shroud.core.messaging

import de.corespace.shroud.core.model.Bytes
import de.corespace.shroud.core.model.ChatMessage
import de.corespace.shroud.core.model.ChatMessageKind
import de.corespace.shroud.core.model.MessageReaction
import de.corespace.shroud.core.model.ReceiptStatus
import de.corespace.shroud.core.model.presentedKind
import de.corespace.shroud.core.net.wire.Icu4jTextUnitsRule
import de.corespace.shroud.core.net.wire.LinkPreview
import de.corespace.shroud.core.net.wire.MessageReplyReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.time.Instant
import java.util.UUID

/**
 * `ThreadMessageMergeTests` (`ios/shroudTests/LocalMessageStoreTests.swift:277-571`) and the folding
 * part of `MessageAnnotationTests` (`MessageAnnotationTests.swift:85-127`), with iOS's media bytes
 * mapped onto [ChatMessage.hasFullMedia] / [ChatMessage.posterJpeg] (plan C8).
 */
class ThreadMessageMergeTest {
    @get:Rule
    val textUnits = Icu4jTextUnitsRule()

    private val base: Instant = Instant.ofEpochSecond(1_800_000_000)

    private fun chatMessage(text: String, isMine: Boolean = false, createdAt: Instant = base): ChatMessage {
        val peer = UUID.randomUUID()
        return ChatMessage(
            id = UUID.randomUUID(),
            peerUserId = peer,
            senderUserId = if (isMine) UUID.randomUUID() else peer,
            text = text,
            createdAt = createdAt,
            isMine = isMine,
            deleted = false,
        )
    }

    /** What `MessageDecoder` makes of a `deleted_for_everyone` message (`LocalMessageStoreTests.swift:552-570`). */
    private fun serverTombstone(of: ChatMessage, isMedia: Boolean, receipt: ReceiptStatus = ReceiptStatus.Sent) = ChatMessage(
        id = of.id,
        peerUserId = of.peerUserId,
        senderUserId = of.senderUserId,
        text = "Message deleted",
        createdAt = of.createdAt,
        isMine = of.isMine,
        deleted = true,
        receipt = receipt,
        kind = if (isMedia) ChatMessageKind.Image else ChatMessageKind.Text,
    )

    @Test
    fun testPreferReadableKeepsPriorOnDecryptFailure() { // :279-300
        val id = UUID.randomUUID()
        val peer = UUID.randomUUID()
        val prior = ChatMessage(id = id, peerUserId = peer, senderUserId = peer, text = "hello from cache", createdAt = Instant.now(), isMine = false)
        val failed = prior.copy(text = "[Unable to decrypt]")
        assertEquals("hello from cache", ThreadMessageMerge.preferReadable(failed, listOf(prior)).text)
    }

    @Test
    fun aFailedDecodeFillsOnlyWhatThePriorLacks() {
        val prior = chatMessage("hello", isMine = true).copy(receipt = ReceiptStatus.Sent)
        val failed = prior.copy(text = "Media", createdAtWire = "2026-09-24T12:00:00.123456Z", receipt = ReceiptStatus.Read, hasFullMedia = true)
        val merged = ThreadMessageMerge.preferReadable(failed, prior)
        assertEquals("hello", merged.text)
        assertEquals("2026-09-24T12:00:00.123456Z", merged.createdAtWire)
        assertEquals(ReceiptStatus.Read, merged.receipt)
        assertTrue(merged.hasFullMedia)
    }

    /** An older page is not the whole thread (`:304-334`). */
    @Test
    fun testMergeThreadKeepsMessagesThePageDidNotInclude() {
        val peer = UUID.randomUUID()
        fun inbound(text: String, at: Long) = ChatMessage(
            id = UUID.randomUUID(), peerUserId = peer, senderUserId = peer, text = text,
            createdAt = Instant.ofEpochSecond(at), isMine = false,
        )
        val older = inbound("from the older page", 1_800_000_000)
        val placeholder = inbound("Media", 1_800_000_100)
        val saidMedia = inbound("Media", 1_800_000_200)
        val merged = ThreadMessageMerge.mergeThread(listOf(older), listOf(older, placeholder, saidMedia), emptyList())
        assertEquals(listOf(older.id, placeholder.id, saidMedia.id), merged.map { it.id })
    }

    @Test
    fun testMergeThreadKeepsPendingAndLocalOnly() { // :336-364
        val peer = UUID.randomUUID()
        val me = UUID.randomUUID()
        val now = Instant.now()
        val server = ChatMessage(UUID.randomUUID(), peer, peer, "from server", now.minusSeconds(10), isMine = false)
        val pending = ChatMessage(
            UUID.randomUUID(), peer, me, "queued offline", now, isMine = true,
            receipt = ReceiptStatus.Sending, pendingSync = true,
        )
        val merged = ThreadMessageMerge.mergeThread(listOf(server), listOf(server, pending), listOf(pending))
        assertEquals(listOf(server.id, pending.id), merged.map { it.id })
    }

    @Test
    fun mergeSortsByTimeAndCarriesReactions() {
        val peer = UUID.randomUUID()
        val reaction = MessageReaction(peer, listOf("🔥"), seq = 4)
        val a = ChatMessage(UUID.randomUUID(), peer, peer, "a", base.plusSeconds(5), isMine = false, reactions = listOf(reaction))
        val b = ChatMessage(UUID.randomUUID(), peer, peer, "b", base, isMine = false)
        val merged = ThreadMessageMerge.mergeThread(listOf(a.copy(reactions = emptyList()), b), listOf(a), emptyList())
        assertEquals(listOf(b.id, a.id), merged.map { it.id })
        // A fresh decode never carries reactions: the held ones stay until a page reconciles them.
        assertEquals(listOf(reaction), merged.last().reactions)
    }

    @Test
    fun aDecodeWithoutBytesKeepsAHydratedVideo() {
        val prior = chatMessage("Video").copy(kind = ChatMessageKind.Video, hasFullMedia = true, mediaObjectId = UUID.randomUUID(), durationMs = 9000)
        val decoded = prior.copy(kind = ChatMessageKind.Image, hasFullMedia = false, durationMs = null, text = "Photo")
        val merged = ThreadMessageMerge.preferReadable(decoded, prior)
        assertEquals(ChatMessageKind.Video, merged.kind)
        assertTrue(merged.hasFullMedia)
        assertEquals(9000, merged.durationMs)
    }

    // --- Tombstones from a history page (`:366-547`) -----------------------------------------

    @Test
    fun testTombstoneDropsTheQuoteAndLinkPreview() { // :368-396
        val prior = chatMessage("see https://example.com").copy(
            replyTo = MessageReplyReference(UUID.randomUUID(), UUID.randomUUID(), MessageReplyReference.Kind.Text, "where?"),
            linkPreview = LinkPreview(url = "https://example.com", title = "Example"),
            mediaObjectId = UUID.randomUUID(),
            hasFullMedia = true,
            previewJpeg = Bytes.of("blurred".toByteArray()),
            imageWidth = 1200,
            imageHeight = 630,
        )
        val merged = ThreadMessageMerge.preferReadable(serverTombstone(prior, isMedia = true), prior)
        assertEquals(ThreadMessageMerge.tombstone(prior), merged)
        assertEquals("Message deleted", merged.text)
        assertTrue(merged.deleted)
        // A link message is a text bubble, even though its picture made it "media" on the wire.
        assertEquals(ChatMessageKind.Text, merged.kind)
        assertNull(merged.replyTo)
        assertNull(merged.linkPreview)
        assertNull(merged.mediaObjectId)
        assertFalse(merged.hasFullMedia)
        assertNull(merged.previewJpeg)
        assertNull(merged.imageWidth)
    }

    @Test
    fun testVoiceTombstoneDropsTheTranscriptAndAudio() { // :398-419
        val prior = chatMessage("meet at noon").copy(
            kind = ChatMessageKind.Voice,
            mediaObjectId = UUID.randomUUID(),
            hasFullMedia = true,
            durationMs = 4200,
            voiceWaveform = Bytes.of(byteArrayOf(10, 200.toByte(), 30)),
            transcript = "meet at noon",
            replyTo = MessageReplyReference(UUID.randomUUID(), UUID.randomUUID(), MessageReplyReference.Kind.Text, "when?"),
        )
        val merged = ThreadMessageMerge.preferReadable(serverTombstone(prior, isMedia = true), prior)
        assertEquals(ThreadMessageMerge.tombstone(prior), merged)
        // Still drawn as a voice bubble: the server only knows it was "media".
        assertEquals(ChatMessageKind.Voice, merged.kind)
        assertNull(merged.transcript)
        assertFalse(merged.hasFullMedia)
        assertNull(merged.durationMs)
        assertNull(merged.voiceWaveform)
        assertNull(merged.replyTo)
    }

    /** The merge keeps a hydrated video from a decode without bytes; a tombstone must not get it back (`:421-440`). */
    @Test
    fun testVideoTombstoneKeepsNoVideo() {
        val prior = chatMessage("Video").copy(
            kind = ChatMessageKind.Video,
            mediaObjectId = UUID.randomUUID(),
            hasFullMedia = true,
            previewJpeg = Bytes.of("poster".toByteArray()),
            posterJpeg = Bytes.of("poster".toByteArray()),
            durationMs = 9000,
        )
        val merged = ThreadMessageMerge.preferReadable(serverTombstone(prior, isMedia = true), prior)
        assertEquals(ThreadMessageMerge.tombstone(prior), merged)
        assertEquals(ChatMessageKind.Video, merged.kind)
        assertFalse(merged.hasFullMedia)
        assertNull(merged.posterJpeg)
        assertNull(merged.previewJpeg)
        assertNull(merged.durationMs)
    }

    @Test
    fun testTombstoneKeepsTheHigherReceipt() { // :442-451
        var mine = chatMessage("hi", isMine = true).copy(receipt = ReceiptStatus.Read)
        val fromServer = serverTombstone(mine, isMedia = false, receipt = ReceiptStatus.Delivered)
        assertEquals(ReceiptStatus.Read, ThreadMessageMerge.preferReadable(fromServer, mine).receipt)

        mine = mine.copy(receipt = ReceiptStatus.Delivered)
        val readOnServer = serverTombstone(mine, isMedia = false, receipt = ReceiptStatus.Read)
        assertEquals(ReceiptStatus.Read, ThreadMessageMerge.preferReadable(readOnServer, mine).receipt)
    }

    @Test
    fun aTombstoneKeepsTheWireTimeOfThePage() {
        val prior = chatMessage("hi")
        val page = serverTombstone(prior, isMedia = false).copy(createdAtWire = "2026-09-24T12:00:00.123456Z")
        assertEquals("2026-09-24T12:00:00.123456Z", ThreadMessageMerge.preferReadable(page, prior).createdAtWire)
    }

    @Test
    fun testMergeThreadReplacesTheLiveMessageAndPurgesItOnce() { // :453-469
        val deleted = chatMessage("gone").copy(
            replyTo = MessageReplyReference(UUID.randomUUID(), UUID.randomUUID(), MessageReplyReference.Kind.Text, "q"),
        )
        val kept = chatMessage("still here", createdAt = deleted.createdAt.plusSeconds(1))
        val page = listOf(serverTombstone(deleted, isMedia = false), kept)

        val merged = ThreadMessageMerge.mergeThread(page, listOf(deleted, kept), emptyList())
        assertEquals(listOf(ThreadMessageMerge.tombstone(deleted), kept), merged)
        assertEquals(listOf(deleted.id), ThreadMessageMerge.tombstonesToPurge(page, listOf(deleted, kept)))

        // The next poll brings the same page: nothing changes, and nothing is purged again.
        assertEquals(merged, ThreadMessageMerge.mergeThread(page, merged, emptyList()))
        assertEquals(emptyList<UUID>(), ThreadMessageMerge.tombstonesToPurge(page, merged))
    }

    @Test
    fun testTombstonesToPurge() { // :471-484
        val live = chatMessage("live")
        val bare = ThreadMessageMerge.tombstone(chatMessage("bare"))
        // What an older build merged in: marked deleted, content still attached.
        val kept = ThreadMessageMerge.tombstone(chatMessage("kept")).copy(transcript = "kept")
        val unseen = chatMessage("unseen")
        val stillLive = chatMessage("not deleted")

        val page = listOf(live, bare, kept, unseen).map { serverTombstone(it, isMedia = false) } + stillLive
        val purge = ThreadMessageMerge.tombstonesToPurge(page, listOf(live, bare, kept, stillLive))
        assertEquals(listOf(live.id, kept.id, unseen.id), purge)
    }

    /** A tombstone this thread never held comes in bare, even with a media id (`:486-497`). */
    @Test
    fun testUnseenTombstoneIsBare() {
        val decoded = serverTombstone(chatMessage("x"), isMedia = true).copy(mediaObjectId = UUID.randomUUID())
        val merged = ThreadMessageMerge.preferReadable(decoded, prior = null)
        assertEquals(ThreadMessageMerge.tombstone(merged), merged)
        assertNull(merged.mediaObjectId)
        assertEquals(ChatMessageKind.Image, merged.kind)
    }

    @Test
    fun testDeletedMediaPresentsAsText() { // :499-517
        for (kind in ChatMessageKind.entries) {
            val message = chatMessage("x").copy(kind = kind)
            assertEquals("$kind", kind, message.presentedKind)
            assertEquals("$kind", ChatMessageKind.Text, ThreadMessageMerge.tombstone(message).presentedKind)
        }
        val unseen = serverTombstone(chatMessage("x"), isMedia = true)
        assertEquals(ChatMessageKind.Image, unseen.kind)
        assertEquals(ChatMessageKind.Text, unseen.presentedKind)
    }

    @Test
    fun testTombstoneKinds() { // :519-530
        val expected = mapOf(
            ChatMessageKind.Text to ChatMessageKind.Text,
            ChatMessageKind.Image to ChatMessageKind.Image,
            ChatMessageKind.Video to ChatMessageKind.Video,
            ChatMessageKind.Voice to ChatMessageKind.Voice,
            ChatMessageKind.Todo to ChatMessageKind.Text,
        )
        for ((kind, tombstoneKind) in expected) {
            assertEquals("$kind", tombstoneKind, ThreadMessageMerge.tombstone(chatMessage("x").copy(kind = kind)).kind)
        }
    }

    @Test
    fun failedDecryptTexts() {
        assertTrue(ThreadMessageMerge.isFailedDecryptText("[Unable to decrypt]"))
        assertTrue(ThreadMessageMerge.isFailedDecryptText("Media"))
        assertTrue(ThreadMessageMerge.isFailedDecryptText("[Binary message]"))
        assertFalse(ThreadMessageMerge.isFailedDecryptText("Photo"))
        assertFalse(ThreadMessageMerge.isFailedDecryptText("Message deleted"))
    }

    // --- Folding shared transcripts (`MessageAnnotationTests.swift:85-127`) --------------------

    private val voicePeer = UUID.randomUUID()

    private fun voiceNote(transcript: String? = null, deleted: Boolean = false) = ChatMessage(
        id = UUID.randomUUID(), peerUserId = voicePeer, senderUserId = voicePeer, text = "Voice message",
        createdAt = Instant.now(), isMine = false, deleted = deleted, kind = ChatMessageKind.Voice, transcript = transcript,
    )

    @Test
    fun fillsAVoiceNoteWithoutATranscript() {
        val note = voiceNote()
        assertEquals("Running late", ThreadMessageMerge.applySharedTranscripts(mapOf(note.id to "Running late"), listOf(note)).first().transcript)
    }

    /** The sender's sealed transcript (or one made on this device) always wins. */
    @Test
    fun neverReplacesAnExistingTranscript() {
        val note = voiceNote(transcript = "Sealed by the sender")
        assertEquals(
            "Sealed by the sender",
            ThreadMessageMerge.applySharedTranscripts(mapOf(note.id to "Shared later"), listOf(note)).first().transcript,
        )
    }

    @Test
    fun aBlankTranscriptIsAGap() {
        val note = voiceNote(transcript = "  \n")
        assertEquals("Shared", ThreadMessageMerge.applySharedTranscripts(mapOf(note.id to "Shared"), listOf(note)).first().transcript)
    }

    @Test
    fun ignoresTranscriptsForUnknownIds() {
        val note = voiceNote()
        val thread = listOf(note)
        val merged = ThreadMessageMerge.applySharedTranscripts(mapOf(UUID.randomUUID() to "no such note"), thread)
        assertEquals(thread, merged)
        assertSame(thread, merged)
        assertNull(merged.first().transcript)
    }

    @Test
    fun leavesTextAndDeletedMessagesAlone() {
        val deleted = voiceNote(deleted = true)
        val text = ChatMessage(UUID.randomUUID(), voicePeer, voicePeer, "hello", Instant.now(), isMine = false)
        val thread = listOf(deleted, text)
        val merged = ThreadMessageMerge.applySharedTranscripts(mapOf(deleted.id to "gone", text.id to "not a voice note"), thread)
        assertEquals(thread, merged)
    }
}
