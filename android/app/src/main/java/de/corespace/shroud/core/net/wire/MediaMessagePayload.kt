package de.corespace.shroud.core.net.wire

import de.corespace.shroud.core.crypto.B64
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Plaintext sealed inside the message ciphertext for `content_type = media` — iOS
 * `MediaMessagePayload` (`ios/shroud/Services/API/MediaModels.swift:40-193`), web
 * `crypto/mediaPayload.ts`. The full blob stays on the server until it is fetched; a small JPEG
 * preview (`th`) rides here so the bubble can show something without fetching megabytes.
 *
 * Production decode/encode goes through [parse] / [encoded] only (`:119`): the reads are lenient by
 * hand, like iOS's `JSONSerialization` path, so a payload any client sealed always opens.
 *
 * [toString] never prints the blob key, caption, preview or waveform.
 */
data class MediaMessagePayload(
    /** [KIND_IMAGE], [KIND_VOICE], [KIND_VIDEO], [KIND_LINK] or [KIND_FILE]. */
    val t: String,
    val mime: String,
    /** Image/video width, `0` for voice. */
    val w: Int,
    /** Image/video height, `0` for voice. */
    val h: Int,
    /** Base64 AES-256 key of the uploaded blob. Secret. */
    val k: String,
    /** Caption (image, video), on-device transcript (voice) or the whole message text (link). */
    val c: String? = null,
    /** Duration in milliseconds (voice, video). */
    val d: Int? = null,
    /** Base64 amplitude envelope, one byte (0…255) per bar (voice only). */
    val wf: String? = null,
    /** Base64 JPEG preview for the bubble (image, video, link placeholder). */
    val th: String? = null,
    /** Full media plaintext size in bytes (for the download chip label). */
    val s: Long? = null,
    /** The message this one replies to; absent on payloads sealed before replies existed. */
    val re: MessageReplyReference? = null,
    /** Link preview of a [KIND_LINK] message (the blob is its large image). */
    val lp: LinkPreview? = null,
    /** A [KIND_FILE] message's name, cleaned by the sender (docs/file-sharing.md §1, §5); receivers clean it again. */
    val n: String? = null,
) {
    /**
     * `t == file` (docs/file-sharing.md §1): a file whatever its `mime` says. Every reader asks this
     * **before** [isVoice] / [isImage] / [isVideo], which sniff the MIME type of unknown kinds.
     */
    val isFile: Boolean get() = t == KIND_FILE

    /** `t == voice`, or an unknown `t` with an `audio/` MIME type (`:84-88`). */
    val isVoice: Boolean
        get() = when (t) {
            KIND_VOICE -> true
            KIND_IMAGE, KIND_VIDEO, KIND_LINK, KIND_FILE -> false
            else -> mime.startsWith("audio/")
        }

    /** `t == image`, or an unknown `t` with an `image/` MIME type (`:90-94`). */
    val isImage: Boolean
        get() = when (t) {
            KIND_IMAGE -> true
            KIND_VOICE, KIND_VIDEO, KIND_LINK, KIND_FILE -> false
            else -> mime.startsWith("image/")
        }

    /** `t == video`, or an unknown `t` with a `video/` MIME type (`:96-100`). */
    val isVideo: Boolean
        get() = when (t) {
            KIND_VIDEO -> true
            KIND_IMAGE, KIND_VOICE, KIND_LINK, KIND_FILE -> false
            else -> mime.startsWith("video/")
        }

    /**
     * A text message whose link preview has a large image (`:102-105`). `t: "link"` without a valid
     * `lp` is nothing at all: not a link, photo, voice note or video
     * (`testLinkPayloadWithoutPreviewIsNotALink`).
     */
    val isLink: Boolean get() = t == KIND_LINK && lp != null

    /** The decoded preview JPEG (`:107-111`): strict Base64 of a non-empty `th`, else null. A fresh copy per call. */
    val previewJpeg: ByteArray? get() = th?.takeIf { it.isNotEmpty() }?.let(B64::decodeStrict)

    /**
     * Stable object JSON (`:157-174`): `t`, `mime`, `w`, `h`, `k` always, the rest only when set; no
     * pretty printing. Key order is free (iOS writes an unordered dictionary); `re` and `lp` are
     * written as their sorted wire objects.
     */
    fun encoded(): ByteArray {
        val fields = LinkedHashMap<String, JsonElement>()
        fields["t"] = JsonPrimitive(t)
        fields["mime"] = JsonPrimitive(mime)
        fields["w"] = JsonPrimitive(w)
        fields["h"] = JsonPrimitive(h)
        fields["k"] = JsonPrimitive(k)
        c?.let { fields["c"] = JsonPrimitive(it) }
        d?.let { fields["d"] = JsonPrimitive(it) }
        wf?.let { fields["wf"] = JsonPrimitive(it) }
        th?.let { fields["th"] = JsonPrimitive(it) }
        s?.let { fields["s"] = JsonPrimitive(it) }
        re?.let { fields["re"] = it.wireObject() }
        lp?.let { fields["lp"] = it.wire() }
        n?.let { fields["n"] = JsonPrimitive(it) }
        return LenientJson.encodeToBytes(JsonObject(fields))
    }

    override fun toString(): String =
        "MediaMessagePayload(t=$t, mime=$mime, w=$w, h=$h, d=$d, s=$s, caption=${c != null}, preview=${th != null}, " +
            "waveform=${wf != null}, reply=${re != null}, link=${lp != null}, name=${n != null})"

    companion object {
        const val KIND_IMAGE = "image"
        const val KIND_VOICE = "voice"
        const val KIND_VIDEO = "video"

        /**
         * A text message whose link preview has a large image (`:75-82`): the image is far too big for
         * the envelope, so the message goes out as media and the image is its blob; `c` carries the
         * whole text, `lp` the preview. A build without link previews shows a photo with a caption.
         */
        const val KIND_LINK = "link"

        /**
         * A document sent as it is (docs/file-sharing.md §1): the blob is SHRF1, `n` its name, `s`
         * its size (required), `mime` the canonical type of the name's extension.
         */
        const val KIND_FILE = "file"

        private const val DEFAULT_MIME = "application/octet-stream"

        /**
         * Lenient parse of the sealed bytes (`:113-135`): a UTF-8 BOM is skipped; when the bytes are
         * not a JSON object as they are, they are decoded, trimmed of whitespace and newlines and,
         * if they then start with `{`, parsed once more. Not an object → null.
         */
        fun parse(bytes: ByteArray): MediaMessagePayload? {
            val json = if (bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()) {
                bytes.copyOfRange(3, bytes.size)
            } else {
                bytes
            }
            val text = LenientJson.utf8OrNull(json) ?: return null
            LenientJson.parseObject(text)?.let { return parse(it) }
            val trimmed = WireText.trimWhitespacesAndNewlines(text)
            if (!trimmed.startsWith("{")) return null
            return LenientJson.parseObject(trimmed)?.let(::parse)
        }

        /**
         * The payload in a parsed object (`:137-155`): null unless `t` and `k` are non-empty strings
         * after trimming. Strings are trimmed (blank → absent), `null` counts as absent, numbers may be
         * doubles (`1500.0` → 1500) or numeric strings, `mime` defaults to `application/octet-stream`,
         * `w`/`h` to 0; `re`/`lp` are read only when they are objects and dropped when broken; unknown
         * keys are ignored.
         */
        fun parse(obj: JsonObject): MediaMessagePayload? {
            val t = LenientJson.trimmedString(obj["t"]) ?: return null
            val k = LenientJson.trimmedString(obj["k"]) ?: return null
            return MediaMessagePayload(
                t = t,
                mime = LenientJson.trimmedString(obj["mime"]) ?: DEFAULT_MIME,
                w = LenientJson.int(obj["w"]) ?: 0,
                h = LenientJson.int(obj["h"]) ?: 0,
                k = k,
                c = LenientJson.trimmedString(obj["c"]),
                d = LenientJson.int(obj["d"]),
                wf = LenientJson.trimmedString(obj["wf"]),
                th = LenientJson.trimmedString(obj["th"]),
                s = LenientJson.long(obj["s"]),
                re = (obj["re"] as? JsonObject)?.let(MessageReplyReference::parse),
                lp = (obj["lp"] as? JsonObject)?.let(LinkPreview::parse),
                n = LenientJson.trimmedString(obj["n"]),
            )
        }
    }
}
