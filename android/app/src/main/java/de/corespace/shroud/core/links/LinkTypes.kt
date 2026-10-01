package de.corespace.shroud.core.links

import de.corespace.shroud.core.model.Bytes
import de.corespace.shroud.core.net.wire.LinkPreview

// Link types (media-voice-links §9–§12). Published by W1-INT; W2-LINKS detects, fetches, composes
// and opens. The sealed `lp` model itself is `core/net/wire/LinkPreview` (plan C2).

/** A link or e-mail address found in text; [start] and [length] are UTF-16 offsets. */
data class DetectedLink(val start: Int, val length: Int, val url: String, val isEmail: Boolean)

/** What the composer shows while the user types: the preview plus a large image if the page has one. */
data class LinkPreviewDraft(
    val preview: LinkPreview,
    val largeImage: Bytes?,
    val largeImageWidth: Int?,
    val largeImageHeight: Int?,
    /** The page's image is big enough for Telegram's large layout. */
    val prefersLargeImage: Boolean,
)

/** What goes out with a text message; a large image travels as its own `t: "link"` blob. */
data class LinkPreviewAttachment(
    val preview: LinkPreview,
    val largeImage: Bytes?,
    val largeImageWidth: Int?,
    val largeImageHeight: Int?,
)
