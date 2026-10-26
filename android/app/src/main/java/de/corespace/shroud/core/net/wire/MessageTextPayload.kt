package de.corespace.shroud.core.net.wire

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.util.UUID

/**
 * Plaintext of a `content_type = text` message — iOS `MessageTextPayload`
 * (`ios/shroud/Services/API/MessageReplyReference.swift:121-189`), web `reply.ts` (`textPayload` /
 * `parseTextPayload` / `textWire`).
 *
 * A message sent to someone is sealed as the envelope `{"c":<body>,"lp":{…},"re":{…},"t":"text","to":<id>}`:
 * `to` names the recipient ([SealedRecipient], security audit M7), `re` and `lp` are there when it
 * quotes a message or carries a link preview. Builds from before `to` sealed a plain message as raw
 * UTF-8, and every build since replies reads the envelope's `c` and ignores keys it does not know.
 * Anything that does not parse as that envelope reads back as raw text — which keeps old and new
 * builds talking in both directions.
 */
object MessageTextPayload {
    /** Discriminator of the JSON envelope (`{"t":"text", …}`). */
    const val KIND = "text"

    /**
     * Largest text plaintext sealed with a link preview: iOS `maxMediaPayloadPlaintextBytes`
     * (`MessagingController.swift:3252`), web `MAX_TEXT_PLAINTEXT_BYTES` — sealed twice and
     * base64-expanded, more overflows the server's envelope cap.
     */
    const val MAX_TEXT_PLAINTEXT_BYTES = 12 * 1024

    /**
     * The plaintext to seal (`:135-154`): [body] itself when there is nothing to attach, else the
     * envelope with keys sorted recursively (`c`, `lp`, `re`, `t`, `to`), so the same message always
     * yields the same bytes — the local cache skips a rewrite only when they match. [to] is the
     * recipient ([SealedRecipient]).
     */
    fun wire(body: String, replyTo: MessageReplyReference?, linkPreview: LinkPreview? = null, to: UUID? = null): String {
        if (replyTo == null && linkPreview == null && to == null) return body
        val fields = LinkedHashMap<String, JsonElement>()
        fields["c"] = JsonPrimitive(body)
        linkPreview?.let { fields["lp"] = it.wire() }
        replyTo?.let { fields["re"] = it.wireObject() }
        fields["t"] = JsonPrimitive(KIND)
        to?.let { fields[SealedRecipient.KEY] = SealedRecipient.wire(it) }
        return LenientJson.encode(LenientJson.sortedKeys(JsonObject(fields)))
    }

    /**
     * [wire] naming [to] as its recipient (`MessageTextPayload.bound`, iOS): an envelope gets `to`
     * added (or replaced) with everything else kept as it is; anything else — raw text, a Notes todo
     * marker — becomes the envelope with that text as `c`. What a receiver shows is unchanged: the
     * same [parse] body, quote and preview.
     */
    fun bound(plaintext: String, to: UUID): String {
        val trimmed = WireText.trimWhitespacesAndNewlines(plaintext)
        val envelope = if (trimmed.startsWith("{") && trimmed.endsWith("}")) LenientJson.parseObject(trimmed) else null
        if (envelope == null || LenientJson.string(envelope["t"]) != KIND || LenientJson.string(envelope["c"]) == null) {
            return wire(plaintext, null, null, to)
        }
        val fields = LinkedHashMap<String, JsonElement>(envelope)
        fields[SealedRecipient.KEY] = SealedRecipient.wire(to)
        return LenientJson.encode(LenientJson.sortedKeys(JsonObject(fields)))
    }

    /** What [parse] found; [toString] never prints the body. */
    data class Parsed(val body: String, val replyTo: MessageReplyReference?, val linkPreview: LinkPreview?) {
        override fun toString(): String =
            "MessageTextPayload.Parsed(body=${body.length} chars, reply=${replyTo != null}, preview=${linkPreview != null})"
    }

    /**
     * Splits sealed plaintext into body, quote and link preview (`:156-173`). Never throws: unless
     * the trimmed text is a JSON object with `"t":"text"` and a string `c`, the result is
     * [plaintext] verbatim with no quote or preview. The body is `c` untrimmed; `re` and `lp` are
     * each dropped on their own when broken (a broken `lp` keeps the message), and `lp` also when
     * its URL is not one of the body's links ([LinkPreview.ofText]). A person who types
     * `{"t":"text","c":"nice try"}` gets `nice try` — accepted on iOS
     * (`testJSONLookingTextIsNotMistakenForAReply`).
     */
    fun parse(plaintext: String): Parsed {
        val verbatim = Parsed(plaintext, null, null)
        // Cheap reject first: the overwhelming majority of messages are plain text.
        val trimmed = WireText.trimWhitespacesAndNewlines(plaintext)
        if (!trimmed.startsWith("{") || !trimmed.endsWith("}")) return verbatim
        val obj = LenientJson.parseObject(trimmed) ?: return verbatim
        if (LenientJson.string(obj["t"]) != KIND) return verbatim
        val body = LenientJson.string(obj["c"]) ?: return verbatim
        return Parsed(
            body = body,
            replyTo = (obj["re"] as? JsonObject)?.let(MessageReplyReference::parse),
            // Only a preview of one of the body's own links counts (security audit L14).
            linkPreview = LinkPreview.ofText((obj["lp"] as? JsonObject)?.let(LinkPreview::parse), body),
        )
    }

    /** [parse] of sealed bytes; bytes that are not UTF-8 read as an empty body (`:175-180`). */
    fun parse(plaintext: ByteArray): Parsed {
        val text = LenientJson.utf8OrNull(plaintext) ?: return Parsed("", null, null)
        return parse(text)
    }

    /**
     * True when [plaintext] is a reply / link-preview envelope rather than raw text (`:182-188`) —
     * used to re-read bubbles a build without reply support stored as raw JSON.
     */
    fun isEnvelope(plaintext: String): Boolean {
        val parsed = parse(plaintext)
        return parsed.replyTo != null || parsed.linkPreview != null
    }

    /** What [textWire] decided; [toString] never prints the wire. */
    data class TextWire(val wire: String, val sealedPreview: LinkPreview?) {
        override fun toString(): String = "MessageTextPayload.TextWire(${WireText.utf8Count(wire)} B, preview=${sealedPreview != null})"
    }

    /**
     * The plaintext of a text message with its link preview trimmed to what fits
     * (`MessagingController.textWire`, `MessagingController.swift:4814-4834`; web `reply.ts:textWire`):
     * the preview as it is, then without its thumbnail, then also without its description — the
     * first whose wire is at most [MAX_TEXT_PLAINTEXT_BYTES] UTF-8 bytes wins. When none fits the body
     * goes alone: a preview is never the reason a message can't be sent.
     */
    fun textWire(body: String, replyTo: MessageReplyReference?, linkPreview: LinkPreview?): TextWire {
        if (linkPreview == null) return TextWire(wire(body, replyTo), null)
        val withoutThumbnail = linkPreview.withoutThumbnail()
        for (candidate in listOf(linkPreview, withoutThumbnail, withoutThumbnail.withoutSummary())) {
            val wire = wire(body, replyTo, candidate)
            if (WireText.utf8Count(wire) <= MAX_TEXT_PLAINTEXT_BYTES) return TextWire(wire, candidate)
        }
        return TextWire(wire(body, replyTo), null)
    }
}
