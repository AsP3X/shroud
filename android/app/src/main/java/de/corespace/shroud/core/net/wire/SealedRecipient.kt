package de.corespace.shroud.core.net.wire

import de.corespace.shroud.core.model.Ids
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.util.UUID

/**
 * The recipient a message names inside its own plaintext (security audit M7) — iOS
 * `SealedRecipient` (`ios/shroud/Services/API/SealedRecipient.swift`), web `sealedRecipient.ts`.
 *
 * Every message is sealed twice with the same plaintext: for the peer, and for the sender's other
 * devices (the `self` box). The envelope does not say whom the `self` box was for, so a server could
 * list my message to Alice in my chat with Bob, and my other devices would show it as sent to Bob.
 * New messages therefore carry `to`, the recipient's user id (lower-case UUID; our own id in Notes),
 * inside the sealed payload, where the server can neither read nor strip it:
 * - text: the envelope `{"c":<body>,"t":"text","to":<id>,…}` ([MessageTextPayload]); every build
 *   since replies reads `c` of that envelope and ignores keys it does not know;
 * - media: `to` in [MediaMessagePayload], whose unknown keys every build ignores;
 * - annotation: `to` in [MessageAnnotation], likewise.
 *
 * Opening checks it ([accepts]): a message we sent must name the chat's peer, one from the peer must
 * name us. A payload without `to` (every message from builds before it) is accepted as before.
 *
 * `content_type` is set by the server outside the seal, so it never decides whether `to` is read
 * (security audit C1): any plaintext that is a JSON object with `to` is checked. It only marks a text
 * message whose plaintext is a media payload or an annotation as misfiled. The rule is written down
 * in `docs/architecture.md` (Sealed recipient).
 */
object SealedRecipient {
    /** Key of the recipient's user id in the sealed payload. */
    const val KEY = "to"

    private const val MEDIA = "media"

    /** What a plaintext says about its recipient. */
    sealed interface Named {
        /** No `to` (or `null`): a payload from before it, raw text, or not a JSON object. */
        data object Nobody : Named

        /** `to` is this user. */
        data class User(val id: UUID) : Named

        /** `to` is there but not a user id: refused like a mismatch. */
        data object Invalid : Named
    }

    /** The `to` of [id] as sealed: the lower-case wire form. */
    fun wire(id: UUID): JsonPrimitive = JsonPrimitive(Ids.wire(id))

    /**
     * What [plaintext] names: the `to` of the JSON object it is, read as [MediaMessagePayload.parse]
     * reads a payload (a leading BOM skipped, trimmed), whatever the message is labelled. Raw text
     * and anything else that is not an object names nobody.
     */
    fun named(plaintext: ByteArray): Named = payloadObject(plaintext)?.let(::named) ?: Named.Nobody

    private fun named(obj: JsonObject): Named {
        val value = obj[KEY] ?: return Named.Nobody
        if (value is JsonNull) return Named.Nobody
        val text = LenientJson.string(value) ?: return Named.Invalid
        return LenientJson.uuidExact(text)?.let { Named.User(it) } ?: Named.Invalid
    }

    /**
     * Whether [plaintext] (of a message labelled [contentType]) may be shown where it is for
     * [expected]: the chat's peer for a message we sent, us for the peer's (us in Notes). True when
     * it names nobody (legacy); false when it names someone else, its `to` is not a user id, or a
     * message not labelled media or annotation holds a media payload or an annotation (relabelled by
     * the server, its raw JSON would be the bubble).
     */
    fun accepts(plaintext: ByteArray, contentType: String, expected: UUID): Boolean {
        val obj = payloadObject(plaintext) ?: return true
        val isPayloadType = contentType == MEDIA || contentType == MessageAnnotation.CONTENT_TYPE
        if (!isPayloadType && isPayloadShaped(obj)) return false
        return when (val named = named(obj)) {
            Named.Nobody -> true
            Named.Invalid -> false
            is Named.User -> named.id == expected
        }
    }

    /**
     * The shape of a media payload (`t` and `k` strings, [MediaMessagePayload.parse]) or of an
     * annotation (`t` is `transcript` and `r` a string, [MessageAnnotation.parseTranscript]).
     */
    private fun isPayloadShaped(obj: JsonObject): Boolean {
        val t = LenientJson.string(obj["t"]) ?: return false
        return LenientJson.string(obj["k"]) != null ||
            (t == MessageAnnotation.KIND_TRANSCRIPT && LenientJson.string(obj["r"]) != null)
    }

    /** The payload's object, as [MediaMessagePayload.parse] finds it. */
    private fun payloadObject(plaintext: ByteArray): JsonObject? {
        val json = if (plaintext.size >= 3 && plaintext[0] == 0xEF.toByte() && plaintext[1] == 0xBB.toByte() && plaintext[2] == 0xBF.toByte()) {
            plaintext.copyOfRange(3, plaintext.size)
        } else {
            plaintext
        }
        val text = LenientJson.utf8OrNull(json) ?: return null
        LenientJson.parseObject(text)?.let { return it }
        val trimmed = WireText.trimWhitespacesAndNewlines(text)
        if (!trimmed.startsWith("{")) return null
        return LenientJson.parseObject(trimmed)
    }
}
