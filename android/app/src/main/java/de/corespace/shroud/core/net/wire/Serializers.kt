package de.corespace.shroud.core.net.wire

import de.corespace.shroud.core.model.Ids
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonTransformingSerializer
import java.time.Instant
import java.util.UUID

/**
 * `java.util.UUID` as its wire string: any case in, lower case out (api-realtime §2.7, decision
 * §17-3). Parsing is as strict as iOS `UUID(uuidString:)`: a malformed id fails the whole DTO,
 * as `JSONDecoder` does (`MessageModels.swift:148-152`).
 */
object UuidSerializer : KSerializer<UUID> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("de.corespace.shroud.UUID", PrimitiveKind.STRING)

    override fun deserialize(decoder: Decoder): UUID =
        Ids.parse(decoder.decodeString()) ?: throw SerializationException("Invalid UUID.")

    override fun serialize(encoder: Encoder, value: UUID) = encoder.encodeString(Ids.wire(value))
}

/**
 * `java.time.Instant` as RFC 3339 text: read with [ApiTime.parse] (0–9 fraction digits, `Z` or an
 * offset), written with [ApiTime.format]. Invalid text fails the decode like iOS's
 * `dataCorruptedError` (`APIClient.swift:417-431`); `ApiClient` turns that into `ApiError.Decoding`.
 */
object InstantIsoSerializer : KSerializer<Instant> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("de.corespace.shroud.Instant", PrimitiveKind.STRING)

    override fun deserialize(decoder: Decoder): Instant {
        val text = decoder.decodeString()
        return ApiTime.parse(text) ?: throw SerializationException("Invalid ISO-8601 date: $text")
    }

    override fun serialize(encoder: Encoder, value: Instant) = encoder.encodeString(ApiTime.format(value))
}

/**
 * A list of strings the server may send as one string or as an array — ICE server `urls`
 * (`CallModels.swift:133-142`): a string becomes a one-element list, an array of strings stays,
 * anything else (null, numbers, a mixed array) becomes an empty list, as iOS falls back to `[]`.
 */
object StringOrListSerializer : JsonTransformingSerializer<List<String>>(ListSerializer(String.serializer())) {
    override fun transformDeserialize(element: JsonElement): JsonElement = when {
        element is JsonPrimitive && element.isString -> JsonArray(listOf(element))
        element is JsonArray && element.all { it is JsonPrimitive && it.isString } -> element
        else -> JsonArray(emptyList())
    }
}
