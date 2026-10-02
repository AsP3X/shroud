package de.corespace.shroud.ui.conversation.composer

import de.corespace.shroud.core.links.LinkPreviewComposer
import de.corespace.shroud.core.links.LinkPreviewDraft
import de.corespace.shroud.core.net.wire.Icu4jTextUnitsRule
import de.corespace.shroud.core.net.wire.LinkPreview
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * The composer's link strip model (iOS `ChatLinkBarState`, `ChatLinkBar.swift:3-9, 114-154`;
 * `ConversationView.swift:1370-1376`; conversation-compose-media §6, §21.2).
 */
class ChatLinkBarStateTest {
    @get:Rule
    val textUnits = Icu4jTextUnitsRule()

    @Test
    fun `title falls back to the site name, then the host without www (CLB 147)`() {
        val withTitle = ChatLinkBarState.from(LinkPreview(url = "https://www.komoot.com/tour/1", siteName = "komoot", title = "Ridge walk"))
        assertEquals("Ridge walk", withTitle.stripTitle)
        val withSite = ChatLinkBarState.from(LinkPreview(url = "https://www.komoot.com/tour/1", siteName = "komoot"))
        assertEquals("komoot", withSite.stripTitle)
        val bare = ChatLinkBarState.from(LinkPreview(url = "https://www.komoot.com/tour/1"))
        assertEquals("komoot.com", bare.stripTitle)
    }

    @Test
    fun `the snippet is the description, else the link itself, muted (CLB 148-152)`() {
        val described = ChatLinkBarState.from(LinkPreview(url = "https://example.com/a", title = "A", summary = "About A"))
        assertEquals(ChatLinkBarState.Ready(title = "A", snippet = "About A", snippetIsLink = false), described)
        assertEquals("About A", described.stripSnippet)
        assertFalse(described.isSnippetMuted)

        val linkOnly = ChatLinkBarState.from(LinkPreview(url = "https://example.com/a", title = "A"))
        assertEquals(ChatLinkBarState.Ready(title = "A", snippet = "https://example.com/a", snippetIsLink = true), linkOnly)
        assertTrue(linkOnly.isSnippetMuted)
    }

    @Test
    fun `the iOS composer test's page - title over the bare link (LinkPreviewComposerTests draft)`() {
        // `LinkPreviewComposerTests.draft(large:)`: site "Example", title "A page", no description.
        val state = ChatLinkBarState.from(LinkPreview(url = "https://example.com", siteName = "Example", title = "A page"))
        assertEquals(ChatLinkBarState.Ready(title = "A page", snippet = "https://example.com", snippetIsLink = true), state)
        assertEquals("Link preview: A page, https://example.com", state.accessibilityText)
        // The same page without a title names its site; without either, its host.
        assertEquals("Example", ChatLinkBarState.from(LinkPreview(url = "https://example.com", siteName = "Example")).stripTitle)
        assertEquals("example.com", ChatLinkBarState.from(LinkPreview(url = "https://example.com")).stripTitle)
        // `testHostCaseIsTheSameLinkButPathCaseIsNot`'s other page: the host is lower-cased, the path is not shown.
        assertEquals("example.com", ChatLinkBarState.from(LinkPreview(url = "https://Example.com/Tour")).stripTitle)
    }

    @Test
    fun `loading shows the link under 'Loading preview…' (CLB 114-134)`() {
        val loading = ChatLinkBarState.Loading("komoot.com/tour/1398273")
        assertEquals("Loading preview…", loading.stripTitle)
        assertEquals("komoot.com/tour/1398273", loading.stripSnippet)
        assertTrue(loading.isSnippetMuted)
    }

    @Test
    fun `TalkBack labels (CLB 136-141)`() {
        assertEquals("Loading link preview for https://x.example/p", ChatLinkBarState.Loading("https://x.example/p").accessibilityText)
        assertEquals(
            "Link preview: Ridge walk, A day out",
            ChatLinkBarState.Ready(title = "Ridge walk", snippet = "A day out", snippetIsLink = false).accessibilityText,
        )
    }

    @Test
    fun `the strip follows the link composer's phase (CV 1370-1376)`() {
        assertNull(ChatLinkBarState.from(LinkPreviewComposer.Phase.Idle))
        assertEquals(ChatLinkBarState.Loading("https://example.com/a"), ChatLinkBarState.from(LinkPreviewComposer.Phase.Loading("https://example.com/a")))
        val draft = LinkPreviewDraft(
            preview = LinkPreview(url = "https://example.com/a", title = "A", summary = "About A"),
            largeImage = null,
            largeImageWidth = null,
            largeImageHeight = null,
            prefersLargeImage = false,
        )
        assertEquals(
            ChatLinkBarState.Ready(title = "A", snippet = "About A", snippetIsLink = false),
            ChatLinkBarState.from(LinkPreviewComposer.Phase.Ready(draft)),
        )
    }

    @Test
    fun `never prints the link or the page's texts`() {
        assertFalse(ChatLinkBarState.Loading("https://secret.example/x").toString().contains("secret"))
        assertFalse(ChatLinkBarState.Ready("Secret title", "secret text", false).toString().contains("ecret"))
    }

    @Test
    fun `link options - layout rows only for a loaded preview, image size only when both layouts exist (CLB 39-60)`() {
        fun titles(state: ChatLinkBarState, above: Boolean = false, canToggle: Boolean = false, large: Boolean = false) =
            linkMenuActions(state, above, canToggle, large, {}, {}, {}).map { it.title to it.destructive }

        assertEquals(listOf("Remove Preview" to true), titles(ChatLinkBarState.Loading("https://e.example")))
        val ready = ChatLinkBarState.Ready("A", "B", false)
        assertEquals(listOf("Show Above Text" to false, "Remove Preview" to true), titles(ready))
        assertEquals(listOf("Show Below Text" to false, "Remove Preview" to true), titles(ready, above = true))
        assertEquals(
            listOf("Show Above Text" to false, "Larger Image" to false, "Remove Preview" to true),
            titles(ready, canToggle = true),
        )
        assertEquals(
            listOf("Show Above Text" to false, "Smaller Image" to false, "Remove Preview" to true),
            titles(ready, canToggle = true, large = true),
        )
    }

    @Test
    fun `each option calls its own action`() {
        var above = 0
        var size = 0
        var remove = 0
        val actions = linkMenuActions(ChatLinkBarState.Ready("A", "B", false), false, true, false, { above++ }, { size++ }, { remove++ })
        actions.forEach { it.onClick() }
        assertEquals(listOf(1, 1, 1), listOf(above, size, remove))
    }
}
