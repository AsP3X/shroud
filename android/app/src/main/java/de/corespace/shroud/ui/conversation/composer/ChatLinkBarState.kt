package de.corespace.shroud.ui.conversation.composer

import androidx.compose.runtime.Immutable
import de.corespace.shroud.core.links.LinkPreviewComposer
import de.corespace.shroud.core.net.wire.LinkPreview

/**
 * What the composer's link strip shows (iOS `ChatLinkBarState`, `ChatLinkBar.swift:3-9`;
 * conversation-compose-media §6).
 */
@Immutable
sealed interface ChatLinkBarState {
    /** Fetching the page — "Loading preview…" with the link underneath. */
    data class Loading(val url: String) : ChatLinkBarState {
        /** Never prints the link: the draft is content. */
        override fun toString(): String = "Loading"
    }

    /** The page title (or site name) over its description (or the link, muted). */
    data class Ready(val title: String, val snippet: String, val snippetIsLink: Boolean) : ChatLinkBarState {
        override fun toString(): String = "Ready(snippetIsLink=$snippetIsLink)"
    }

    /** The strip's title line (`ChatLinkBar.swift:114-119`). */
    val stripTitle: String
        get() = when (this) {
            is Loading -> LOADING_TITLE
            is Ready -> title
        }

    /** The strip's second line (`ChatLinkBar.swift:121-126`). */
    val stripSnippet: String
        get() = when (this) {
            is Loading -> url
            is Ready -> snippet
        }

    /** The link itself (loading, or a page without a description) reads as a stand-in: muted, middle-truncated (`:128-134`). */
    val isSnippetMuted: Boolean
        get() = when (this) {
            is Loading -> true
            is Ready -> snippetIsLink
        }

    /** TalkBack's label of the preview block (`ChatLinkBar.swift:136-141`). */
    val accessibilityText: String
        get() = when (this) {
            is Loading -> "Loading link preview for $url"
            is Ready -> "Link preview: $title, $snippet"
        }

    companion object {
        /** U+2026, as iOS (`ChatLinkBar.swift:116`). */
        const val LOADING_TITLE = "Loading preview…"

        /** A loaded preview: title → site name → host, over description → link (`ChatLinkBar.swift:144-154`). */
        fun from(preview: LinkPreview): ChatLinkBarState {
            val title = preview.title ?: preview.siteName ?: preview.displayHost
            val summary = preview.summary
            return if (summary != null) {
                Ready(title = title, snippet = summary, snippetIsLink = false)
            } else {
                Ready(title = title, snippet = preview.url, snippetIsLink = true)
            }
        }

        /** The strip for the link composer's phase; null while idle (`ConversationView.swift:1370-1376`). */
        fun from(phase: LinkPreviewComposer.Phase): ChatLinkBarState? = when (phase) {
            LinkPreviewComposer.Phase.Idle -> null
            is LinkPreviewComposer.Phase.Loading -> Loading(phase.url)
            is LinkPreviewComposer.Phase.Ready -> from(phase.draft.preview)
        }
    }
}
