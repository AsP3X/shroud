package de.corespace.shroud.core.net.wire

import de.corespace.shroud.core.model.Ids
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.util.UUID

/**
 * Plaintext sealed inside a reaction record (`PUT /messages/{id}/reaction`) — iOS
 * `MessageReactionPayload` (`ios/shroud/Services/API/MessageReaction.swift:11-59`), web `reactions.ts`
 * (`reactionPayload` / `parseReaction` / `isSingleEmoji`).
 *
 * A reaction is not a message: the server keeps one sealed record per (message, user) and learns who
 * reacted to which message, never the emoji. `r` ties the record to its message, so the server cannot
 * move a genuine reaction onto another; `e` is the person's whole set, oldest first. The record is
 * sealed as a tagged v2 identity envelope, never through the ratchet (crypto area).
 *
 * [toString] never prints the emoji.
 */
data class MessageReactionPayload(
    /** Always [KIND]. */
    val t: String,
    /** Server id of the message reacted to, lower case. */
    val r: String,
    /** The person's emoji, oldest first. */
    val e: List<String>,
) {
    /** `{"t":"reaction","r":"<id>","e":[…]}` — the web's `reactionPayload` byte for byte. */
    fun encoded(): ByteArray = LenientJson.encodeToBytes(
        JsonObject(
            linkedMapOf(
                "t" to JsonPrimitive(t),
                "r" to JsonPrimitive(r),
                "e" to JsonArray(e.map(::JsonPrimitive)),
            ),
        ),
    )

    override fun toString(): String = "MessageReactionPayload(t=$t, emoji=${e.size})"

    companion object {
        const val KIND = "reaction"

        /** One emoji is at most a few scalars; anything longer is not an emoji we show (`:20-21`). */
        const val MAX_EMOJI_BYTES = 32

        /**
         * Most emoji a reader shows from one record, whatever the server's limit is today (`:22-25`):
         * a lowered limit never hides what was there, and a modified client can't flood a bubble.
         */
        const val READER_CAP = 20

        /** The payload of [emojis] on [messageId] (`:27-29`): `r` lower case. */
        fun make(emojis: List<String>, messageId: UUID): MessageReactionPayload =
            MessageReactionPayload(t = KIND, r = Ids.wire(messageId), e = emojis)

        /**
         * The emoji in [plaintext] when it is a reaction to [messageId], else null (`:31-46`).
         *
         * Strict like iOS's `JSONDecoder`: a JSON object with string `t` and `r` and an array of
         * strings `e` (a missing key, `null` or one non-string entry → null — the web skips such
         * entries instead; iOS is the reference). `t == "reaction"`; `r` must be exactly a UUID equal
         * to [messageId] in any case — a record whose `r` names another message is dropped, not moved.
         * Then each [isSingleEmoji] entry once, in order, at most [READER_CAP].
         */
        fun parse(plaintext: ByteArray, messageId: UUID): List<String>? {
            val obj = LenientJson.parseObject(plaintext) ?: return null
            val t = LenientJson.string(obj["t"]) ?: return null
            val r = LenientJson.string(obj["r"]) ?: return null
            val array = obj["e"] as? JsonArray ?: return null
            val entries = array.map { LenientJson.string(it) ?: return null }
            if (t != KIND || LenientJson.uuidExact(r) != messageId) return null
            val seen = HashSet<String>()
            val emojis = ArrayList<String>()
            for (emoji in entries) {
                if (!isSingleEmoji(emoji) || !seen.add(emoji)) continue
                emojis += emoji
                if (emojis.size == READER_CAP) break
            }
            return emojis
        }

        /**
         * One grapheme drawn as an emoji (`:48-58`; web `reactions.ts:97-104` is the same rule): at
         * most [MAX_EMOJI_BYTES] UTF-8 bytes and exactly one grapheme; its first code point has
         * `Emoji_Presentation` (or is unknown to this ICU inside the emoji blocks,
         * [TextUnits.drawsAsEmoji]), or has `Emoji` and is followed by more code points — "❤" + VS16,
         * keycaps, flags. Rejects text, digits and several emoji at once. The byte cap is checked
         * first so a long string never reaches the segmenter.
         */
        fun isSingleEmoji(text: String): Boolean {
            if (text.isEmpty() || WireText.utf8Count(text) > MAX_EMOJI_BYTES) return false
            val units = TextUnits.current
            if (units.graphemeCount(text) != 1) return false
            val first = text.codePointAt(0)
            if (units.drawsAsEmoji(first)) return true
            return units.isEmoji(first) && text.codePointCount(0, text.length) > 1
        }
    }
}
