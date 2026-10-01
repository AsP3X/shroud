package de.corespace.shroud.core.model

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
 * The derived bubble properties of plan §1.7.5: `ChatMessageReplyReferenceTests`
 * (`ios/shroudTests/MessageReplyTests.swift:172-228`) verbatim, plus `presentedKind`, the download
 * flags and the preview pick (`MessagingController.swift:313-343`, `:5889-5893`) mapped onto
 * [ChatMessage.hasFullMedia] (plan C8).
 */
class ChatMessageDerivedTest {
    @get:Rule
    val textUnits = Icu4jTextUnitsRule()

    private fun message(
        text: String = "Hey!",
        kind: ChatMessageKind = ChatMessageKind.Text,
        deleted: Boolean = false,
        receipt: ReceiptStatus = ReceiptStatus.Sent,
        pendingSync: Boolean = false,
        mediaObjectId: UUID? = null,
        hasFullMedia: Boolean = false,
        linkPreview: LinkPreview? = null,
        previewJpeg: Bytes? = null,
        posterJpeg: Bytes? = null,
    ) = ChatMessage(
        id = UUID.randomUUID(),
        peerUserId = UUID.randomUUID(),
        senderUserId = UUID.randomUUID(),
        text = text,
        createdAt = Instant.now(),
        isMine = true,
        deleted = deleted,
        receipt = receipt,
        kind = kind,
        pendingSync = pendingSync,
        mediaObjectId = mediaObjectId,
        hasFullMedia = hasFullMedia,
        linkPreview = linkPreview,
        previewJpeg = previewJpeg,
        posterJpeg = posterJpeg,
    )

    // --- MessageReplyTests.swift:172-228 ------------------------------------------------------

    @Test
    fun testQuotesTextMessage() { // :194-200
        val source = message(text = "Hey!")
        val quote = source.replyReference
        assertEquals(source.id, quote?.messageId)
        assertEquals(source.senderUserId, quote?.senderUserId)
        assertEquals(MessageReplyReference.Kind.Text, quote?.kind)
        assertEquals("Hey!", quote?.snippet)
    }

    @Test
    fun testMediaStandInLabelsAreNotSealedAsSnippets() { // :203-208
        assertEquals("", message(text = "Photo", kind = ChatMessageKind.Image).replyReference?.snippet)
        assertEquals("", message(text = "Video", kind = ChatMessageKind.Video).replyReference?.snippet)
        assertEquals("A caption", message(text = "A caption", kind = ChatMessageKind.Image).replyReference?.snippet)
        assertEquals("", message(text = "A transcript", kind = ChatMessageKind.Voice).replyReference?.snippet)
    }

    @Test
    fun testUnsendableMessagesCannotBeQuoted() { // :210-215
        assertNull(message(deleted = true).replyReference)
        assertNull(message(receipt = ReceiptStatus.Failed).replyReference)
        assertNull(message(receipt = ReceiptStatus.Sending).replyReference)
        assertNull(message(pendingSync = true).replyReference)
    }

    @Test
    fun testSnapshotStillQuotesAfterTheLiveCopyIsDeleted() { // :219-227
        val source = message(text = "Hey!")
        val snapshot = source.replyReference
        val deleted = source.copy(deleted = true)
        assertNull(deleted.replyReference)
        assertEquals("Hey!", snapshot?.snippet)
        assertEquals(source.id, snapshot?.messageId)
    }

    @Test
    fun replyKindsAndMediaPlaceholders() {
        assertEquals(MessageReplyReference.Kind.Text, message(kind = ChatMessageKind.Todo, text = "Milk").replyReference?.kind)
        assertEquals("Milk", message(kind = ChatMessageKind.Todo, text = "Milk").replyReference?.snippet)
        assertEquals(MessageReplyReference.Kind.Voice, message(kind = ChatMessageKind.Voice).replyReference?.kind)
        assertEquals("", message(text = "Media", kind = ChatMessageKind.Image).replyReference?.snippet)
        assertEquals("", message(text = "Media", kind = ChatMessageKind.Video).replyReference?.snippet)
        // Notes keep Sent, so a reply in Notes works; delivered and read quote too.
        assertTrue(message(receipt = ReceiptStatus.Read).canBeQuoted)
        assertTrue(message(receipt = ReceiptStatus.Delivered).canBeQuoted)
        // The snippet is clamped by the reference's initializer.
        assertEquals("a".repeat(119) + "…", message(text = "a".repeat(400)).replyReference?.snippet)
    }

    // --- presentedKind (MC:5889-5893) -----------------------------------------------------------

    @Test
    fun deletedMediaPresentsAsTheTextTombstone() {
        for (kind in ChatMessageKind.entries) {
            assertEquals(kind, message(kind = kind).presentedKind)
            assertEquals(ChatMessageKind.Text, message(kind = kind, deleted = true).presentedKind)
        }
    }

    // --- needs* (MC:313-330) --------------------------------------------------------------------

    @Test
    fun photosAndVideosNeedADownloadUntilTheirBytesAreHere() {
        val media = UUID.randomUUID()
        assertTrue(message(kind = ChatMessageKind.Image, mediaObjectId = media).needsMediaDownload)
        assertTrue(message(kind = ChatMessageKind.Video, mediaObjectId = media).needsMediaDownload)
        assertFalse(message(kind = ChatMessageKind.Image, mediaObjectId = media, hasFullMedia = true).needsMediaDownload)
        assertFalse(message(kind = ChatMessageKind.Video, mediaObjectId = media, hasFullMedia = true).needsMediaDownload)
        // Voice loads on play, text never downloads, and a tombstone or a bubble without a blob has nothing to fetch.
        assertFalse(message(kind = ChatMessageKind.Voice, mediaObjectId = media).needsMediaDownload)
        assertFalse(message(kind = ChatMessageKind.Text, mediaObjectId = media).needsMediaDownload)
        assertFalse(message(kind = ChatMessageKind.Image, mediaObjectId = media, deleted = true).needsMediaDownload)
        assertFalse(message(kind = ChatMessageKind.Image).needsMediaDownload)
    }

    @Test
    fun largeLinkImages() {
        val preview = LinkPreview(url = "https://example.com")
        val media = UUID.randomUUID()
        // Known by its blob until downloaded.
        val remote = message(linkPreview = preview, mediaObjectId = media)
        assertTrue(remote.hasLargeLinkImage)
        assertTrue(remote.needsLinkImageDownload)
        // The sender's copy has the bytes before the upload lands.
        val sending = message(linkPreview = preview, hasFullMedia = true)
        assertTrue(sending.hasLargeLinkImage)
        assertFalse(sending.needsLinkImageDownload)
        // No preview, no blob, not text, or deleted: no large layout or no download.
        assertFalse(message(mediaObjectId = media).hasLargeLinkImage)
        assertFalse(message(linkPreview = preview).hasLargeLinkImage)
        assertFalse(message(kind = ChatMessageKind.Image, linkPreview = preview, mediaObjectId = media).hasLargeLinkImage)
        assertTrue(message(linkPreview = preview, mediaObjectId = media, deleted = true).hasLargeLinkImage)
        assertFalse(message(linkPreview = preview, mediaObjectId = media, deleted = true).needsLinkImageDownload)
    }

    @Test
    fun displayPreviewPicksTheThumbnailThenThePoster() {
        val thumb = Bytes.of(byteArrayOf(1))
        val poster = Bytes.of(byteArrayOf(2))
        assertEquals(thumb, message(kind = ChatMessageKind.Video, previewJpeg = thumb, posterJpeg = poster).displayPreview)
        assertEquals(poster, message(kind = ChatMessageKind.Video, posterJpeg = poster).displayPreview)
        assertEquals(thumb, message(kind = ChatMessageKind.Image, previewJpeg = thumb, hasFullMedia = true).displayPreview)
        assertNull(message(kind = ChatMessageKind.Image, posterJpeg = poster).displayPreview)
    }

    @Test
    fun theModelNeverPrintsContent() {
        val printed = message(text = "secret words").toString()
        assertFalse(printed.contains("secret"))
    }
}
