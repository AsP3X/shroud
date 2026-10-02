package de.corespace.shroud.ui.conversation.links

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextDecoration
import de.corespace.shroud.core.links.DetectedLink
import de.corespace.shroud.core.links.LinkDetector
import de.corespace.shroud.core.model.ChatMessage
import de.corespace.shroud.core.model.ChatMessageKind

/**
 * Message text with its links marked, styled the way Telegram styles them — iOS `MessageLinkText`
 * (`MessageLinkText.swift:3-49`; conversation-thread §6.1). A link is drawn in the link colour and
 * underlined only when that colour is the text's own: incoming bubbles show `accentText` links without
 * an underline, outgoing (accent) bubbles white underlined links. iOS also underlines incoming links
 * under Differentiate Without Color; Android has no such setting (decision D12), so callers pass
 * `underlined = false` until the app grows its own switch.
 *
 * Each link run carries a [URL_TAG] string annotation with the URL to open. The bubble draws the text
 * itself (decision D3) and hit-tests the annotations, so every tap goes through the screen's
 * `openLink` — the menu guard and the tap claim run (the reason the spec asks for
 * `LinkAnnotation.Clickable` rather than `.Url`).
 *
 * Detection is [LinkDetector] (shared rules with iOS and the web), remembered per string: a thread
 * redraws the same strings. The cache holds message text, so chats locking clears it ([clearCache],
 * called by `BubbleMemory`; iOS keeps it — the spec's §19 asks for the clear).
 */
object MessageLinkText {
    /** Tag of the string annotation that holds a link run's URL. */
    const val URL_TAG = "url"

    /** iOS clears the whole cache at 256 strings (`MessageLinkText.swift:37, 47`). */
    const val CACHE_LIMIT = 256

    private val cache = HashMap<String, List<DetectedLink>>()

    /**
     * [text] with a link run for every detected link: [linkColor] (white on ours, `accentText` on
     * theirs), underlined when [isMine] or [underlined] (`MessageLinkText.swift:16-31`).
     */
    fun annotated(text: String, isMine: Boolean, linkColor: Color, underlined: Boolean = false): AnnotatedString {
        val links = links(text)
        if (links.isEmpty()) return AnnotatedString(text)
        val style = SpanStyle(
            color = linkColor,
            textDecoration = if (isMine || underlined) TextDecoration.Underline else null,
        )
        return buildAnnotatedString {
            append(text)
            for (link in links) {
                val end = link.start + link.length
                if (link.start < 0 || end > text.length) continue
                addStyle(style, link.start, end)
                addStringAnnotation(URL_TAG, link.url, link.start, end)
            }
        }
    }

    /** Links in [text], remembered for the strings a thread keeps redrawing (`:33-40`). */
    @Synchronized
    fun links(text: String): List<DetectedLink> {
        cache[text]?.let { return it }
        val found = LinkDetector.links(text)
        if (cache.size >= CACHE_LIMIT) cache.clear()
        cache[text] = found
        return found
    }

    /** The first link in [text] — drives the "Copy Link" menu row (`:42-45`). */
    fun firstLink(text: String): String? = links(text).firstOrNull()?.url

    /**
     * What "Copy Link" copies (`ConversationView.swift:1403-1412`; conversation-thread §6.3): text
     * messages only, not deleted; the previewed page if any; else the first link — an e-mail address
     * as typed, a web link as its URL.
     */
    fun copyableLink(message: ChatMessage): String? {
        if (message.kind != ChatMessageKind.Text || message.deleted) return null
        message.linkPreview?.url?.let { return it }
        val link = links(message.text).firstOrNull() ?: return null
        if (link.isEmail) return message.text.substring(link.start, link.start + link.length)
        return link.url
    }

    /** The URL of the link run at [offset] in an [annotated] string, if any. */
    fun urlAt(text: AnnotatedString, offset: Int): String? =
        text.getStringAnnotations(URL_TAG, offset, offset + 1).firstOrNull()?.item

    /** Chats locked: the remembered strings are message text and leave memory (conversation-thread §19). */
    @Synchronized
    fun clearCache() = cache.clear()

    /** For tests: how many strings are remembered. */
    @Synchronized
    internal fun cachedCount(): Int = cache.size
}
