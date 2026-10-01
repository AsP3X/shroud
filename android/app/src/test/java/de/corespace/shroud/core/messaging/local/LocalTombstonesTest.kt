package de.corespace.shroud.core.messaging.local

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
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.time.Instant
import java.util.UUID

/**
 * The store's copy of `ThreadMessageMerge.tombstone(of:)` and `isFailedDecryptText`
 * (`ThreadMessageMerge.swift:5-7, 114-133`), pinned with the tombstone parts of iOS
 * `LocalMessageStoreTests.swift:377-532` so it cannot drift from W2-MSG-CORE's `ThreadMessageMerge`.
 */
class LocalTombstonesTest {
    @get:Rule
    val textUnits = Icu4jTextUnitsRule()

    /** `testTombstoneDropsTheQuoteAndLinkPreview` (`:377-404`), on `tombstone(of:)` itself. */
    @Test
    fun tombstoneDropsTheQuoteTheLinkPreviewAndTheLinkMedia() {
        val prior = chatMessage("see https://example.com").copy(
            replyTo = MessageReplyReference(UUID.randomUUID(), UUID.randomUUID(), MessageReplyReference.Kind.Text, "where?"),
            linkPreview = LinkPreview(url = "https://example.com", title = "Example"),
            mediaObjectId = UUID.randomUUID(),
            hasFullMedia = true,
            previewJpeg = Bytes.of("blurred".toByteArray()),
            imageWidth = 1200,
            imageHeight = 630,
        )

        val gone = LocalTombstones.tombstone(prior)

        assertEquals("Message deleted", gone.text)
        assertTrue(gone.deleted)
        // A link message is a text bubble, even though its picture made it "media" on the wire.
        assertEquals(ChatMessageKind.Text, gone.kind)
        assertNull(gone.replyTo)
        assertNull(gone.linkPreview)
        assertNull(gone.mediaObjectId)
        assertFalse(gone.hasFullMedia)
        assertNull(gone.previewJpeg)
        assertNull(gone.imageWidth)
        val stored = StoredMessage.from(gone)
        assertNull(stored.replyTo)
        assertNull(stored.linkPreview)
        assertNull(stored.mediaObjectId)
    }

    /** `testVoiceTombstoneDropsTheTranscriptAndAudio` (`:406-427`). */
    @Test
    fun voiceTombstoneDropsTheTranscriptAndAudio() {
        val prior = chatMessage("meet at noon").copy(
            kind = ChatMessageKind.Voice,
            mediaObjectId = UUID.randomUUID(),
            hasFullMedia = true,
            durationMs = 4200,
            voiceWaveform = Bytes.of(byteArrayOf(10, 200.toByte(), 30)),
            transcript = "meet at noon",
            replyTo = MessageReplyReference(UUID.randomUUID(), UUID.randomUUID(), MessageReplyReference.Kind.Text, "when?"),
            reactions = listOf(MessageReaction(UUID.randomUUID(), listOf("👍"), 3)),
        )

        val gone = LocalTombstones.tombstone(prior)

        // Still a voice bubble's row: the server only knows it was "media".
        assertEquals(ChatMessageKind.Voice, gone.kind)
        assertNull(gone.transcript)
        assertFalse(gone.hasFullMedia)
        assertNull(gone.durationMs)
        assertNull(gone.voiceWaveform)
        assertNull(gone.replyTo)
        assertTrue(gone.reactions.isEmpty())
        assertNull(StoredMessage.from(gone).transcript)
    }

    /** `testVideoTombstoneKeepsNoVideo` (`:431-448`). */
    @Test
    fun videoTombstoneKeepsNoVideo() {
        val prior = chatMessage("Video").copy(
            kind = ChatMessageKind.Video,
            mediaObjectId = UUID.randomUUID(),
            hasFullMedia = true,
            previewJpeg = Bytes.of("poster".toByteArray()),
            posterJpeg = Bytes.of("poster".toByteArray()),
            durationMs = 9000,
        )
        val gone = LocalTombstones.tombstone(prior)
        assertEquals(ChatMessageKind.Video, gone.kind)
        assertFalse(gone.hasFullMedia)
        assertNull(gone.posterJpeg)
        assertNull(gone.previewJpeg)
        assertNull(gone.durationMs)
    }

    @Test
    fun tombstoneKeepsWhoWhenAndTheReceiptAndIsIdempotent() {
        val prior = chatMessage("hi", isMine = true).copy(receipt = ReceiptStatus.Read, createdAtWire = "2026-09-24T12:00:00.123456Z", pendingSync = true, sendError = "x")
        val gone = LocalTombstones.tombstone(prior)
        assertEquals(prior.id, gone.id)
        assertEquals(prior.peerUserId, gone.peerUserId)
        assertEquals(prior.senderUserId, gone.senderUserId)
        assertEquals(prior.createdAt, gone.createdAt)
        assertEquals(prior.createdAtWire, gone.createdAtWire)
        assertTrue(gone.isMine)
        assertEquals(ReceiptStatus.Read, gone.receipt)
        assertFalse(gone.pendingSync)
        assertNull(gone.sendError)
        assertEquals(gone, LocalTombstones.tombstone(gone))
    }

    /** `testDeletedMediaPresentsAsText` (`:506-521`) and `testTombstoneKinds` (`:524-533`). */
    @Test
    fun tombstoneKindsAndTheyAllPresentAsText() {
        val expected = mapOf(
            ChatMessageKind.Text to ChatMessageKind.Text,
            ChatMessageKind.Image to ChatMessageKind.Image,
            ChatMessageKind.Video to ChatMessageKind.Video,
            ChatMessageKind.Voice to ChatMessageKind.Voice,
            ChatMessageKind.Todo to ChatMessageKind.Text,
        )
        for ((kind, tombstoneKind) in expected) {
            val message = chatMessage("x").copy(kind = kind)
            assertEquals(kind.name, kind, message.presentedKind)
            val gone = LocalTombstones.tombstone(message)
            assertEquals(kind.name, tombstoneKind, gone.kind)
            assertEquals(kind.name, ChatMessageKind.Text, gone.presentedKind)
        }
    }

    @Test
    fun failedDecryptTexts() {
        // `ThreadMessageMerge.swift:5-7`.
        for (text in listOf("[Unable to decrypt]", "Media", "[Binary message]")) assertTrue(text, LocalTombstones.isFailedDecryptText(text))
        for (text in listOf("media", "Message deleted", "Photo", "")) assertFalse(text, LocalTombstones.isFailedDecryptText(text))
    }

    /** `chatMessage` (`:535-549`). */
    private fun chatMessage(text: String, isMine: Boolean = false): ChatMessage {
        val peer = UUID.randomUUID()
        return ChatMessage(
            id = UUID.randomUUID(),
            peerUserId = peer,
            senderUserId = if (isMine) UUID.randomUUID() else peer,
            text = text,
            createdAt = Instant.ofEpochSecond(1_800_000_000),
            isMine = isMine,
            deleted = false,
        )
    }
}
