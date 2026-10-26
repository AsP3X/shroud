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
 */
object SealedRecipient {
    /** Key of the recipient's user id in the sealed payload. */
    const val KEY = "to"

    private const val MEDIA = "media"

    /** What a plaintext says about its recipient. */
    sealed interface Named {
        /** No `to` (or `null`): a payload from before it, or not a payload of this content type. */
        data object Nobody : Named

        /** `to` is this user. */
        data class User(val id: UUID) : Named

        /** `to` is there but not a user id: refused like a mismatch. */
        data object Invalid : Named
    }

    /** The `to` of [id] as sealed: the lower-case wire form. */
    fun wire(id: UUID): JsonPrimitive = JsonPrimitive(Ids.wire(id))

    /**
     * What [plaintext] (of a message of [contentType]) names. A text message names someone only as
     * the envelope [MessageTextPayload.parse] reads (`"t":"text"` and a string `c`), never as JSON
     * someone typed; media and annotations are read as JSON objects as [MediaMessagePayload.parse]
     * reads them (a leading BOM skipped, trimmed).
     */
    fun named(plaintext: ByteArray, contentType: String): Named {
        val isPayload = contentType == MEDIA || contentType == MessageAnnotation.CONTENT_TYPE
        val obj = (if (isPayload) payloadObject(plaintext) else textEnvelope(plaintext)) ?: return Named.Nobody
        val value = obj[KEY] ?: return Named.Nobody
        if (value is JsonNull) return Named.Nobody
        val text = LenientJson.string(value) ?: return Named.Invalid
        return LenientJson.uuidExact(text)?.let { Named.User(it) } ?: Named.Invalid
    }

    /**
     * Whether [plaintext] may be shown where it is for [expected]: the chat's peer for a message we
     * sent, us for the peer's (us in Notes). True when it names nobody (legacy); false when it names
     * someone else or its `to` is not a user id.
     */
    fun accepts(plaintext: ByteArray, contentType: String, expected: UUID): Boolean =
        when (val named = named(plaintext, contentType)) {
            Named.Nobody -> true
            Named.Invalid -> false
            is Named.User -> named.id == expected
        }

    /** The text envelope's object, under exactly [MessageTextPayload.parse]'s conditions. */
    private fun textEnvelope(plaintext: ByteArray): JsonObject? {
        val text = LenientJson.utf8OrNull(plaintext) ?: return null
        val trimmed = WireText.trimWhitespacesAndNewlines(text)
        if (!trimmed.startsWith("{") || !trimmed.endsWith("}")) return null
        val obj = LenientJson.parseObject(trimmed) ?: return null
        if (LenientJson.string(obj["t"]) != MessageTextPayload.KIND || LenientJson.string(obj["c"]) == null) return null
        return obj
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
