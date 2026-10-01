package de.corespace.shroud.core.net.wire

import de.corespace.shroud.core.crypto.B64
import de.corespace.shroud.core.model.Bytes
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * A link preview sealed **inside** a message's plaintext (`lp`) — iOS `LinkPreview`
 * (`ios/shroud/Services/Links/LinkPreview.swift:14-210`), web `links.ts` (`parseLinkPreview` /
 * `linkPreviewWire`). The sender's phone fetches the page and seals what it found; the recipient
 * never contacts the website. A small JPEG rides inline as `th`; a large image travels as the blob
 * of a `t: "link"` media message ([MediaMessagePayload.KIND_LINK]) and is not part of this object.
 *
 * Wire keys: `u` url, `n` site name, `ti` title, `d` description, `th` base64 JPEG, `w`/`h` image
 * size, `vd` video page, `ab` drawn above the text. Everything but `u` is optional.
 *
 * Built only through [invoke] (or [parse]), which cleans the three texts like the Swift initializer
 * (`LinkPreview.swift:69-89`); `copy` is private so nothing skips that. Home is `core/net/wire`
 * (plan C2); `core/links` detects, fetches, composes and opens.
 */
@ConsistentCopyVisibility
data class LinkPreview private constructor(
    /** The page the preview describes, as the sender wrote it (`http`/`https` only after [parse]). */
    val url: String,
    /** "komoot", "YouTube" — the accent-coloured first line. */
    val siteName: String?,
    val title: String?,
    val summary: String?,
    /** Inline JPEG for the small layout (≤ [MAX_THUMBNAIL_BYTES] after [parse]). */
    val thumbnail: Bytes?,
    /** Pixel size of the page image (large blob or thumbnail), for the aspect ratio. */
    val imageWidth: Int?,
    val imageHeight: Int?,
    /** The page is a video: the large image gets a play badge. */
    val isVideo: Boolean,
    /** Telegram's "Show above message": the block sits over the text instead of under it. */
    val showsAboveText: Boolean,
) {
    /** The URL to open, if it is still a web URL (`LinkPreview.swift:91-97`). */
    val openUrl: String? get() = url.takeIf { url.toHttpUrlOrNull() != null }

    /**
     * Host without `www.`, the fallback site name ("komoot.com"); the URL itself when it has no host
     * (`LinkPreview.swift:99-103`). OkHttp's host is already lower case (IDN as punycode).
     */
    val displayHost: String
        get() {
            val host = url.toHttpUrlOrNull()?.host?.lowercase() ?: return url
            return if (host.startsWith("www.")) host.substring(4) else host
        }

    /** First line of the block: the site's own name, else its host (`LinkPreview.swift:105-108`). */
    val displaySiteName: String get() = siteName ?: displayHost

    /** Image aspect (width / height) when the sender knew it (`LinkPreview.swift:110-114`). */
    val imageAspect: Float?
        get() {
            val w = imageWidth ?: return null
            val h = imageHeight ?: return null
            return if (w > 0 && h > 0) w.toFloat() / h.toFloat() else null
        }

    /** Nothing worth drawing: no words and no picture (`LinkPreview.swift:116-119`). */
    val isEmpty: Boolean get() = title == null && summary == null && thumbnail == null

    /** Copy without the inline thumbnail — the large layout carries its image as a blob (`:121-126`). */
    fun withoutThumbnail(): LinkPreview = copy(thumbnail = null)

    /** Copy without the description: the last thing the text budget drops before the whole preview (MC:4825). */
    fun withoutSummary(): LinkPreview = copy(summary = null)

    /**
     * The `lp` object to seal (`LinkPreview.swift:135-146`, web `linkPreviewWire`), keys sorted as
     * `MessageTextPayload` writes them. Absent texts, a missing thumbnail, non-positive sizes and
     * false flags are left out to keep the envelope small.
     */
    fun wire(): JsonObject {
        val fields = sortedMapOf<String, JsonPrimitive>("u" to JsonPrimitive(url))
        siteName?.let { fields["n"] = JsonPrimitive(it) }
        title?.let { fields["ti"] = JsonPrimitive(it) }
        summary?.let { fields["d"] = JsonPrimitive(it) }
        thumbnail?.let { fields["th"] = JsonPrimitive(B64.encode(it.toByteArray())) }
        imageWidth?.takeIf { it > 0 }?.let { fields["w"] = JsonPrimitive(it) }
        imageHeight?.takeIf { it > 0 }?.let { fields["h"] = JsonPrimitive(it) }
        if (isVideo) fields["vd"] = JsonPrimitive(true)
        if (showsAboveText) fields["ab"] = JsonPrimitive(true)
        return JsonObject(LinkedHashMap(fields))
    }

    /** Never prints the page's texts or URL: a preview is message content. */
    override fun toString(): String = "LinkPreview(thumbnail=${thumbnail?.size ?: 0} B, video=$isVideo, above=$showsAboveText)"

    companion object {
        // Limits (`LinkPreview.swift:45-52`): every byte is sealed three times and base64-expanded,
        // and the server caps an envelope at 64 KiB.
        const val MAX_SITE_NAME_CHARACTERS = 64
        const val MAX_TITLE_CHARACTERS = 200
        const val MAX_SUMMARY_CHARACTERS = 300
        const val MAX_URL_CHARACTERS = 2048

        /** Same budget as a photo's envelope thumbnail (`MediaCrypto.maxEnvelopePreviewBytes`). */
        const val MAX_THUMBNAIL_BYTES = 6 * 1024

        /**
         * The Swift memberwise initializer (`LinkPreview.swift:69-89`): [siteName], [title] and
         * [summary] are cleaned ([clean]); everything else is kept as given. Cleaning twice changes
         * nothing, so a stored preview can be rebuilt through here.
         */
        operator fun invoke(
            url: String,
            siteName: String? = null,
            title: String? = null,
            summary: String? = null,
            thumbnail: Bytes? = null,
            imageWidth: Int? = null,
            imageHeight: Int? = null,
            isVideo: Boolean = false,
            showsAboveText: Boolean = false,
        ): LinkPreview = LinkPreview(
            url = url,
            siteName = clean(siteName, MAX_SITE_NAME_CHARACTERS),
            title = clean(title, MAX_TITLE_CHARACTERS),
            summary = clean(summary, MAX_SUMMARY_CHARACTERS),
            thumbnail = thumbnail,
            imageWidth = imageWidth,
            imageHeight = imageHeight,
            isVideo = isVideo,
            showsAboveText = showsAboveText,
        )

        /**
         * Collapses runs of whitespace and cuts at a character boundary, marking the cut with "…"
         * (`LinkPreview.swift:179-199`): null when nothing is left; over [max] graphemes → the first
         * `max − 1`, trailing spaces trimmed, plus `…`.
         */
        fun clean(raw: String?, max: Int): String? {
            if (raw == null) return null
            val units = TextUnits.current
            val collapsed = WireText.collapseWhitespace(raw, units)
            if (collapsed.isEmpty()) return null
            return WireText.clampCharacters(collapsed, max, units)
        }

        /**
         * Lenient parse of the `lp` object (`LinkPreview.swift:148-177`): null unless `u` is an
         * `http`/`https` URL of at most [MAX_URL_CHARACTERS] characters. Texts are trimmed and
         * cleaned, a thumbnail that is not strict Base64 or is over [MAX_THUMBNAIL_BYTES] is dropped
         * (the preview stays), `w`/`h` are read only from numbers, `vd`/`ab` only from `true`, and
         * unknown keys are ignored — a newer sender never breaks an older reader.
         *
         * URL check: iOS asks `URL(string:)` for an `http`/`https` scheme, the web `new URL()`;
         * Android takes OkHttp's `toHttpUrlOrNull()` (media-voice-links §1.4), which only parses
         * `http`/`https` (any case) and needs a host, as the web does.
         */
        fun parse(obj: JsonObject): LinkPreview? {
            val rawUrl = LenientJson.trimmedString(obj["u"]) ?: return null
            if (rawUrl.length > MAX_URL_CHARACTERS && TextUnits.current.graphemeCount(rawUrl) > MAX_URL_CHARACTERS) return null
            if (rawUrl.toHttpUrlOrNull() == null) return null
            val thumbnail = LenientJson.trimmedString(obj["th"])
                ?.let(B64::decodeStrict)
                ?.takeIf { it.size <= MAX_THUMBNAIL_BYTES }
                ?.let(Bytes::adopt)
            return invoke(
                url = rawUrl,
                siteName = LenientJson.trimmedString(obj["n"]),
                title = LenientJson.trimmedString(obj["ti"]),
                summary = LenientJson.trimmedString(obj["d"]),
                thumbnail = thumbnail,
                imageWidth = LenientJson.number(obj["w"]),
                imageHeight = LenientJson.number(obj["h"]),
                isVideo = LenientJson.isTrue(obj["vd"]),
                showsAboveText = LenientJson.isTrue(obj["ab"]),
            )
        }
    }
}
