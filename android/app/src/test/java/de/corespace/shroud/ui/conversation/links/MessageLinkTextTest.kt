package de.corespace.shroud.ui.conversation.links

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextDecoration
import de.corespace.shroud.core.model.ChatMessage
import de.corespace.shroud.core.model.ChatMessageKind
import de.corespace.shroud.core.net.wire.LinkPreview
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.util.UUID

/**
 * The `MessageLinkText` part of `ios/shroudTests/LinkDetectorTests.swift:71-92` (conversation-thread
 * §20.8); the detector vectors belong to the links area (`LinkDetectorTest`). Plus "Copy Link"
 * (`ConversationView.swift:1403-1412`) and the lock-time cache clear (§19).
 */
class MessageLinkTextTest {
    private val accentText = Color(0xFF4B49D8)

    @After
    fun clear() = MessageLinkText.clearCache()

    @Test
    fun messageLinkTextMarksLinks() {
        val attributed = MessageLinkText.annotated("see example.com now", isMine = false, linkColor = accentText)
        val links = attributed.getStringAnnotations(MessageLinkText.URL_TAG, 0, attributed.length)
        assertEquals(1, links.size)
        assertEquals("https://example.com", links.single().item)
        assertEquals("example.com", attributed.text.substring(links.single().start, links.single().end))
        // The run is styled in the link colour.
        val style = attributed.spanStyles.single()
        assertEquals(accentText, style.item.color)
        assertEquals(4, style.start)
        assertEquals(15, style.end)
    }

    @Test
    fun outgoingLinksAreUnderlined() {
        val outgoing = MessageLinkText.annotated("example.com", isMine = true, linkColor = Color.White)
        assertEquals(TextDecoration.Underline, outgoing.spanStyles.single().item.textDecoration)
        val incoming = MessageLinkText.annotated("example.com", isMine = false, linkColor = accentText)
        assertNull(incoming.spanStyles.single().item.textDecoration)
    }

    /** iOS: Differentiate Without Color (decision D12: no Android setting yet, the parameter stays). */
    @Test
    fun incomingLinksAreUnderlinedWhenAsked() {
        val incoming = MessageLinkText.annotated("example.com", isMine = false, linkColor = accentText, underlined = true)
        assertEquals(TextDecoration.Underline, incoming.spanStyles.single().item.textDecoration)
    }

    // ---- Android additions ----

    @Test
    fun textWithoutLinksIsPlain() {
        val plain = MessageLinkText.annotated("no links here", isMine = false, linkColor = accentText)
        assertTrue(plain.spanStyles.isEmpty())
        assertTrue(plain.getStringAnnotations(MessageLinkText.URL_TAG, 0, plain.length).isEmpty())
    }

    @Test
    fun aTapResolvesTheLinkUnderTheOffset() {
        val attributed = MessageLinkText.annotated("see example.com now", isMine = false, linkColor = accentText)
        assertEquals("https://example.com", MessageLinkText.urlAt(attributed, 4))
        assertEquals("https://example.com", MessageLinkText.urlAt(attributed, 14))
        assertNull(MessageLinkText.urlAt(attributed, 2))
        assertNull(MessageLinkText.urlAt(attributed, 16))
    }

    @Test
    fun theFirstLinkDrivesTheMenu() {
        assertEquals("https://example.com", MessageLinkText.firstLink("a example.com b other.org"))
        assertNull(MessageLinkText.firstLink("nothing"))
    }

    private fun text(body: String, kind: ChatMessageKind = ChatMessageKind.Text, deleted: Boolean = false, preview: LinkPreview? = null) =
        ChatMessage(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), body, Instant.EPOCH, isMine = false, deleted = deleted, kind = kind, linkPreview = preview)

    /** "Copy Link": the previewed page, else the first link; an e-mail address as typed (`:1403-1412`). */
    @Test
    fun copyLinkPrefersThePreviewThenTheFirstLink() {
        assertEquals("https://example.com", MessageLinkText.copyableLink(text("see example.com")))
        assertEquals("Bob@Example.com", MessageLinkText.copyableLink(text("mail Bob@Example.com")))
        assertNull(MessageLinkText.copyableLink(text("see example.com", deleted = true)))
        assertNull(MessageLinkText.copyableLink(text("see example.com", kind = ChatMessageKind.Image)))
        assertNull(MessageLinkText.copyableLink(text("no link")))
    }

    /** The remembered link ranges are message text: chats locking clears them (§19). */
    @Test
    fun theCacheIsBoundedAndClearedOnLock() {
        MessageLinkText.links("see example.com")
        assertEquals(1, MessageLinkText.cachedCount())
        MessageLinkText.clearCache()
        assertEquals(0, MessageLinkText.cachedCount())
        repeat(MessageLinkText.CACHE_LIMIT + 3) { MessageLinkText.links("text $it") }
        assertTrue(MessageLinkText.cachedCount() <= MessageLinkText.CACHE_LIMIT)
    }
}
