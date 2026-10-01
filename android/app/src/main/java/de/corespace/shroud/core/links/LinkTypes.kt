package de.corespace.shroud.core.links

import de.corespace.shroud.core.model.Bytes
import de.corespace.shroud.core.net.wire.LinkPreview

// Link types (00-plan §1.7.9; media-voice-links §10). Published by W1-INT, owned by W2-LINKS, which
// detects ([LinkDetector]), fetches ([LinkPreviewFetcher]), composes ([LinkPreviewComposer]) and
// opens ([LinkOpener]). The sealed `lp` model itself is `core/net/wire/LinkPreview` (plan C2).

/**
 * A link or e-mail address found in text (iOS `DetectedLink`, `LinkDetector.swift:3-12`).
 * [start] and [length] are UTF-16 offsets — the same on iOS (`NSRange`) and the web. [url] is what a
 * tap opens, kept as built: the URL as typed, `https://` + a bare host, or `mailto:` + the address.
 * E-mail addresses are tappable but never get a preview.
 */
data class DetectedLink(val start: Int, val length: Int, val url: String, val isEmail: Boolean)

/**
 * What the composer holds for the link in the draft (iOS `LinkPreviewDraft`,
 * `LinkPreviewFetcher.swift:7-20`): the preview plus a large image if the page has one.
 * `preview.thumbnail` is the square inline thumb when the page has a usable image.
 */
data class LinkPreviewDraft(
    val preview: LinkPreview,
    /** JPEG for the large layout; null when the page has no image worth showing big. */
    val largeImage: Bytes?,
    val largeImageWidth: Int?,
    val largeImageHeight: Int?,
    /** The layout Telegram would pick: a big picture for videos and wide card images. */
    val prefersLargeImage: Boolean,
) {
    /** Either picture exists (`LinkPreviewFetcher.swift:19`). */
    val hasImage: Boolean get() = preview.thumbnail != null || largeImage != null
}

/**
 * What goes out with a text message (iOS `LinkPreviewAttachment`, `LinkPreviewFetcher.swift:22-31`):
 * the preview with its square thumbnail (the small layout, and the large one's fallback when the
 * upload fails) and, when the sender chose the large layout, the large image — uploaded as the
 * blob of a `t: "link"` media message.
 */
data class LinkPreviewAttachment(
    val preview: LinkPreview,
    val largeImage: Bytes?,
    val largeImageWidth: Int?,
    val largeImageHeight: Int?,
)
