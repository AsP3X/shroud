package de.corespace.shroud.core.net.wire

import de.corespace.shroud.core.model.Ids
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.util.UUID

/**
 * Plaintext sealed inside a `content_type = annotation` message: data one participant attaches to an
 * earlier message instead of sending a new one — iOS `MessageAnnotation`
 * (`ios/shroud/Services/API/MessageAnnotation.swift:11-63`), web `messaging.ts` (`Annotation`).
 *
 * The one use so far is a voice transcript made on the recipient's device and shared back:
 * `{"t":"transcript","r":"<lower-case voice message id>","c":"<text>"}`. The server relays it like any
 * message but never reorders, pushes or counts it; clients fold it into the message it points at.
 *
 * [toString] never prints the text.
 */
data class MessageAnnotation(
    /** Annotation type: [KIND_TRANSCRIPT]. */
    val t: String,
    /** Server id of the annotated message, lower case. */
    val r: String,
    /** Annotation body: the transcript text. */
    val c: String,
) {
    /** `{"t":…,"r":…,"c":…}` — iOS `JSONEncoder` field order, the web's too. */
    fun encoded(): ByteArray = LenientJson.encodeToBytes(
        JsonObject(linkedMapOf("t" to JsonPrimitive(t), "r" to JsonPrimitive(r), "c" to JsonPrimitive(c))),
    )

    override fun toString(): String = "MessageAnnotation(t=$t, text=${c.length} chars)"

    /** A shared transcript and the voice message it belongs to. [toString] never prints the text. */
    data class Transcript(val messageId: UUID, val text: String) {
        override fun toString(): String = "MessageAnnotation.Transcript(text=${text.length} chars)"
    }

    companion object {
        const val CONTENT_TYPE = "annotation"
        const val KIND_TRANSCRIPT = "transcript"

        /**
         * Largest transcript sealed or accepted, in UTF-8 bytes (`:21-27`): sealed twice and
         * base64-expanded, 16 KiB is ~44 KiB on the wire, inside the server's 64 KiB in any script. A
         * character cap is not enough: 8 000 CJK characters already brush the limit.
         */
        const val MAX_TRANSCRIPT_BYTES = 16 * 1024

        /**
         * Trims [text] (whitespace and newlines) and, when it is over [MAX_TRANSCRIPT_BYTES] UTF-8
         * bytes, keeps whole graphemes while they fit in `16384 − 3` bytes, trims again and appends
         * `…` (`:29-44`). Never splits a character.
         */
        fun clampTranscript(text: String): String {
            val trimmed = WireText.trimWhitespacesAndNewlines(text)
            if (WireText.utf8Count(trimmed) <= MAX_TRANSCRIPT_BYTES) return trimmed
            val budget = MAX_TRANSCRIPT_BYTES - WireText.utf8Count(WireText.ELLIPSIS)
            var used = 0
            val kept = StringBuilder()
            for (character in TextUnits.current.graphemes(trimmed)) {
                val size = WireText.utf8Count(character)
                if (used + size > budget) break
                used += size
                kept.append(character)
            }
            return WireText.trimWhitespacesAndNewlines(kept.toString()) + WireText.ELLIPSIS
        }

        /** The annotation sharing [text] as the transcript of [messageId] (`:46-48`): `r` lower case, `c` clamped. */
        fun transcript(text: String, messageId: UUID): MessageAnnotation =
            MessageAnnotation(t = KIND_TRANSCRIPT, r = Ids.wire(messageId), c = clampTranscript(text))

        /**
         * The shared transcript in [plaintext], or null (`:50-58`). Strict like iOS's `JSONDecoder`:
         * a JSON object whose `t`, `r` and `c` are all strings (missing or another type → null),
         * `t == "transcript"`, `r` exactly a UUID (any case, nothing around it); the text is
         * [clampTranscript]ed and must not end up empty.
         */
        fun parseTranscript(plaintext: ByteArray): Transcript? {
            val obj = LenientJson.parseObject(plaintext) ?: return null
            val t = LenientJson.string(obj["t"]) ?: return null
            val r = LenientJson.string(obj["r"]) ?: return null
            val c = LenientJson.string(obj["c"]) ?: return null
            if (t != KIND_TRANSCRIPT) return null
            val messageId = LenientJson.uuidExact(r) ?: return null
            val text = clampTranscript(c)
            return if (text.isEmpty()) null else Transcript(messageId, text)
        }

        /** [parseTranscript] of UTF-8 text (`:60-62`). */
        fun parseTranscript(plaintext: String): Transcript? = parseTranscript(plaintext.toByteArray(Charsets.UTF_8))
    }
}
