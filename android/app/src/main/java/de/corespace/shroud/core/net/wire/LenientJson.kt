package de.corespace.shroud.core.net.wire

import de.corespace.shroud.core.model.Ids
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.util.UUID

/**
 * The hand-rolled JSON reads and writes of the sealed plaintext shapes (api-realtime §7, messaging-core
 * §3): iOS builds and reads them with `JSONSerialization` and `as?` casts, never `JSONDecoder`, because
 * `JSONDecoder` decoded differently on iOS 26 and 27 (`MediaModels.swift:113-119`,
 * `MessageReplyReference.swift:84-89`). Android does the same with `JsonElement` trees and the helpers
 * below — never `decodeFromString<T>` for these shapes — so one phone's payload always opens on another.
 *
 * Each helper names the Swift expression it reproduces. Nothing here throws.
 */
object LenientJson {
    private val json: Json = Json

    /**
     * Swift `String(data:encoding: .utf8)`: the text of [bytes], or null when they are not valid UTF-8
     * (`String(bytes, UTF_8)` would quietly substitute U+FFFD instead).
     */
    fun utf8OrNull(bytes: ByteArray): String? = try {
        Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes))
            .toString()
    } catch (_: CharacterCodingException) {
        null
    }

    /**
     * `try? JSONSerialization.jsonObject(with:) as? [String: Any]`: [text] as a JSON object, or null
     * when it is not strict JSON or not an object.
     *
     * kotlinx's tree parser is laxer than `JSONSerialization` in two ways that matter for text a person
     * typed: it takes raw control characters inside strings (a line break typed between quotes) and
     * bare words as values (`{"a":abc}`). Both are refused here, so such a message stays plain text on
     * Android as it does on iOS.
     */
    fun parseObject(text: String): JsonObject? {
        if (!hasNoRawControlCharactersInStrings(text)) return null
        val element = try {
            json.parseToJsonElement(text)
        } catch (_: IllegalArgumentException) {
            // SerializationException (malformed JSON) extends IllegalArgumentException.
            return null
        } catch (_: StackOverflowError) {
            return null
        }
        if (element !is JsonObject || !isStrictTree(element)) return null
        return element
    }

    /** [parseObject] of the UTF-8 text of [bytes]; null for invalid UTF-8. */
    fun parseObject(bytes: ByteArray): JsonObject? = utf8OrNull(bytes)?.let(::parseObject)

    /** `value as? String`: a JSON string, untouched; null for anything else (`null` included). */
    fun string(value: JsonElement?): String? =
        (value as? JsonPrimitive)?.takeIf { it.isString }?.content

    /**
     * The `string(_:)` helpers of the sealed shapes (`MediaModels.swift:176-183`,
     * `MessageReplyReference.swift:114-118`, `LinkPreview.swift:201-205`): a JSON string trimmed of
     * whitespace and newlines, null when that leaves nothing or the value is not a string.
     */
    fun trimmedString(value: JsonElement?): String? =
        string(value)?.let(WireText::trimWhitespacesAndNewlines)?.takeIf { it.isNotEmpty() }

    /**
     * `MediaMessagePayload.int(_:)` (`MediaModels.swift:185-192`): a JSON number (a double truncated
     * toward zero, `1500.0` → 1500), a boolean (`JSONSerialization` hands those over as `NSNumber`
     * 0/1), or a string Swift's `Int(_:)` accepts (ASCII digits with an optional sign, nothing else);
     * null otherwise. Values outside `Int` are null: Swift's `Int` is 64-bit, but no width, height
     * or duration gets there.
     */
    fun int(value: JsonElement?): Int? = long(value)?.takeIf { it in Int.MIN_VALUE..Int.MAX_VALUE }?.toInt()

    /** [int] for 64-bit fields (`s`, the plaintext size). */
    fun long(value: JsonElement?): Long? {
        val primitive = value as? JsonPrimitive ?: return null
        if (primitive is JsonNull) return null
        if (primitive.isString) return swiftIntOrNull(primitive.content)
        return nsNumberLong(primitive)
    }

    /**
     * `(value as? NSNumber)?.intValue` (`LinkPreview.swift:207-209`): JSON numbers and booleans, never
     * strings (`"1200"` is ignored).
     */
    fun number(value: JsonElement?): Int? {
        val primitive = value as? JsonPrimitive ?: return null
        if (primitive is JsonNull || primitive.isString) return null
        return nsNumberLong(primitive)?.takeIf { it in Int.MIN_VALUE..Int.MAX_VALUE }?.toInt()
    }

    /**
     * `value as? Bool == true` (`LinkPreview.swift:174-175`): JSON `true`, or a number that is exactly
     * 1 — Swift bridges such an `NSNumber` to `true` (SE-0170). Strings never count.
     */
    fun isTrue(value: JsonElement?): Boolean {
        val primitive = value as? JsonPrimitive ?: return false
        if (primitive is JsonNull || primitive.isString) return false
        if (primitive.content == "true") return true
        return primitive.content.toDoubleOrNull() == 1.0
    }

    /** `UUID(uuidString:)`: canonical 8-4-4-4-12 hex in any case, nothing around it (no trimming). */
    fun uuidExact(text: String): UUID? = if (text.length == 36) Ids.parse(text) else null

    /**
     * `JSONSerialization.WritingOptions.sortedKeys`: the same tree with every object's keys in
     * ascending order, recursively (`MessageReplyReference.swift:148-150`). Keys of the sealed shapes
     * are ASCII, where Foundation's order and Kotlin's `String` order agree.
     */
    fun sortedKeys(element: JsonElement): JsonElement = when (element) {
        is JsonObject -> JsonObject(element.entries.sortedBy { it.key }.associateTo(LinkedHashMap()) { it.key to sortedKeys(it.value) })
        is JsonArray -> JsonArray(element.map(::sortedKeys))
        else -> element
    }

    /**
     * Compact JSON text of [element] in its key order (no pretty printing). iOS escapes `/` as `\/`
     * and kotlinx does not; every reader accepts both, so only byte counts differ (api-realtime §7).
     */
    fun encode(element: JsonElement): String = json.encodeToString(JsonElement.serializer(), element)

    /** [encode] as UTF-8 bytes. */
    fun encodeToBytes(element: JsonElement): ByteArray = encode(element).toByteArray(Charsets.UTF_8)

    // ---------------------------------------------------------------------------------------------

    private val JSON_NUMBER = Regex("-?(0|[1-9][0-9]*)(\\.[0-9]+)?([eE][+-]?[0-9]+)?")
    private val SWIFT_INT = Regex("[+-]?[0-9]+")

    /** Swift `Int(_ text:)`: optional sign, ASCII digits only, no spaces; overflow → null. */
    private fun swiftIntOrNull(text: String): Long? =
        if (SWIFT_INT.matches(text)) text.toLongOrNull() else null

    /** `NSNumber.int64Value` of a non-string literal: booleans 0/1, integers, doubles truncated. */
    private fun nsNumberLong(primitive: JsonPrimitive): Long? {
        val content = primitive.content
        if (content == "true") return 1
        if (content == "false") return 0
        content.toLongOrNull()?.let { return it }
        val double = content.toDoubleOrNull() ?: return null
        if (double.isNaN() || double.isInfinite()) return null
        if (double >= Long.MAX_VALUE.toDouble() || double <= Long.MIN_VALUE.toDouble()) return null
        return double.toLong() // truncates toward zero, like the C cast behind intValue
    }

    /**
     * Every non-string literal of the tree is `true`, `false`, `null` or a JSON number. Iterative: a
     * peer's 64 KiB envelope can nest ~32 000 levels deep, more than a background thread's stack.
     */
    private fun isStrictTree(root: JsonElement): Boolean {
        val pending = ArrayDeque<JsonElement>()
        pending.addLast(root)
        while (pending.isNotEmpty()) {
            when (val element = pending.removeLast()) {
                is JsonObject -> pending.addAll(element.values)
                is JsonArray -> pending.addAll(element)
                is JsonNull -> Unit
                is JsonPrimitive -> if (!element.isString && element.content != "true" && element.content != "false" &&
                    !JSON_NUMBER.matches(element.content)
                ) {
                    return false
                }
            }
        }
        return true
    }

    /** No unescaped U+0000–U+001F inside a string literal (RFC 8259 §7; `JSONSerialization` refuses them). */
    private fun hasNoRawControlCharactersInStrings(text: String): Boolean {
        var inString = false
        var i = 0
        while (i < text.length) {
            val c = text[i]
            if (inString) {
                when {
                    c == '\\' -> i++ // the escaped character is the parser's business
                    c == '"' -> inString = false
                    c.code < 0x20 -> return false
                }
            } else if (c == '"') {
                inString = true
            }
            i++
        }
        return true
    }
}
