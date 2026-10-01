package de.corespace.shroud.core.net.wire

import de.corespace.shroud.core.model.Ids
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.util.UUID

/**
 * The message a reply quotes, sealed **inside** the reply's own plaintext — iOS
 * `MessageReplyReference` (`ios/shroud/Services/API/MessageReplyReference.swift:13-119`), web
 * `reply.ts` (`ReplyRef`). The quote never becomes server metadata. A short snippet travels with it
 * so the header still reads when the original has aged out or is not paged in yet.
 *
 * Wire keys are terse (every byte is sealed twice and base64-expanded): `id` the quoted message,
 * `u` its author, `k` its kind, `x` the snippet (left out when empty). Ids are written lower case.
 *
 * Built only through [invoke] (or [parse]), which clamps the snippet like the Swift initializer
 * (`:51-56`); `copy` is private so nothing skips that.
 */
@ConsistentCopyVisibility
data class MessageReplyReference private constructor(
    /** Server id of the quoted message. */
    val messageId: UUID,
    /** Author of the quoted message, so the header can say "You" or the peer's name. */
    val senderUserId: UUID,
    val kind: Kind,
    /** Caption / text of the quoted message, clamped to one readable line. */
    val snippet: String,
) {
    /** What kind of bubble is quoted — drives the stand-in label (`:15-30`). */
    enum class Kind(val wire: String) {
        Text("text"),
        Image("image"),
        Video("video"),
        Voice("voice"),
        ;

        /** Label shown in the quote when there is no text of its own; none for text. */
        val mediaLabel: String?
            get() = when (this) {
                Text -> null
                Image -> "Photo"
                Video -> "Video"
                Voice -> "Voice message"
            }

        companion object {
            /** Unknown or missing kinds read as [Text] (`:94`). */
            fun fromWire(raw: String?): Kind = entries.firstOrNull { it.wire == raw } ?: Text
        }
    }

    /**
     * The `re` object to seal (`:103-112`, web `replyRefWire`), keys sorted (`id`, `k`, `u`, `x`) as
     * `MessageTextPayload` writes them.
     */
    fun wireObject(): JsonObject {
        val fields = LinkedHashMap<String, JsonPrimitive>()
        fields["id"] = JsonPrimitive(Ids.wire(messageId))
        fields["k"] = JsonPrimitive(kind.wire)
        fields["u"] = JsonPrimitive(Ids.wire(senderUserId))
        if (snippet.isNotEmpty()) fields["x"] = JsonPrimitive(snippet)
        return JsonObject(fields)
    }

    /** Never prints the snippet: it is message content. */
    override fun toString(): String = "MessageReplyReference(kind=$kind, snippet=${snippet.length} chars)"

    companion object {
        /** One line of quoted text is all the header ever draws (`:47-49`). */
        const val MAX_SNIPPET_CHARACTERS = 120

        /** The Swift initializer (`:51-56`): the snippet is always clamped ([clampSnippet]). */
        operator fun invoke(messageId: UUID, senderUserId: UUID, kind: Kind, snippet: String): MessageReplyReference =
            MessageReplyReference(messageId, senderUserId, kind, clampSnippet(snippet))

        /**
         * Collapses whitespace and cuts at a character boundary, marking the cut with "…" (`:58-80`):
         * every run of whitespace graphemes becomes one space (none leading or trailing); more than
         * [MAX_SNIPPET_CHARACTERS] graphemes → the first 119, trailing spaces trimmed, plus `…`.
         * Graphemes, not code points (the web counts code points): an emoji ZWJ sequence is never split.
         */
        fun clampSnippet(raw: String): String {
            val units = TextUnits.current
            return WireText.clampCharacters(WireText.collapseWhitespace(raw, units), MAX_SNIPPET_CHARACTERS, units)
        }

        /**
         * Lenient parse of the `re` object (`:84-101`): null unless `id` and `u` are UUIDs (trimmed
         * strings); an unknown kind falls back to [Kind.Text]; a missing snippet is empty. The snippet
         * is clamped again.
         */
        fun parse(obj: JsonObject): MessageReplyReference? {
            val messageId = LenientJson.trimmedString(obj["id"])?.let(LenientJson::uuidExact) ?: return null
            val senderUserId = LenientJson.trimmedString(obj["u"])?.let(LenientJson::uuidExact) ?: return null
            return invoke(
                messageId = messageId,
                senderUserId = senderUserId,
                kind = Kind.fromWire(LenientJson.trimmedString(obj["k"])),
                snippet = LenientJson.trimmedString(obj["x"]) ?: "",
            )
        }
    }
}
