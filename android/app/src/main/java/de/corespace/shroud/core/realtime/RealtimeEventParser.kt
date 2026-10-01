package de.corespace.shroud.core.realtime

import de.corespace.shroud.core.model.Ids
import de.corespace.shroud.core.net.CallDto
import de.corespace.shroud.core.net.ChatMuteDto
import de.corespace.shroud.core.net.ContactRequestDto
import de.corespace.shroud.core.net.MessageDto
import de.corespace.shroud.core.net.ReactionDto
import de.corespace.shroud.core.net.wire.ApiTime
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull
import java.time.Instant
import java.util.UUID

/**
 * Turns one text frame from `/api/v1/ws` into a [RealtimeFrame] (api-realtime §11.12; server
 * shapes §11.5). Pure and thread-safe: `RealtimeClient` runs it on OkHttp's reader thread.
 *
 * iOS parses only `type` in the client (`RealtimeClient.swift:292-336`) and leaves the fields to
 * each consumer's guard; this parser applies those guards once, so an event a consumer would drop
 * on iOS never reaches the Android consumer. The guard of each event is cited where it is ported.
 *
 * Differences from iOS, all deliberate (api-realtime §11.12):
 * - times parse with or without fractional seconds ([ApiTime.parse]); iOS's `apiFlexible` needs
 *   them and silently loses a whole-second `last_seen_at`, `read_at` or mute `until`
 *   (`MessagingController.swift:5882-5886`);
 * - an unknown `type` becomes [RealtimeEvent.Unknown] instead of being dropped
 *   (`RealtimeClient.swift:333-334`); consumers ignore it.
 */
object RealtimeEventParser {
    /**
     * The frame [text] carries, or null for frames the client drops: not JSON, not an object, no
     * string `type` (`RealtimeClient.swift:293-296`), or an event missing a field its iOS consumer
     * requires. Decoding nested DTOs uses [json] (the app's API `Json`, `ignoreUnknownKeys`).
     */
    fun parse(text: String, json: Json): RealtimeFrame? {
        val root = try {
            json.parseToJsonElement(text)
        } catch (_: SerializationException) {
            return null
        } catch (_: IllegalArgumentException) {
            return null
        }
        val obj = root as? JsonObject ?: return null
        val type = obj.string("type") ?: return null
        return when (type) {
            "auth.ok" -> authOk(obj)
            "auth.error" -> authError(obj)
            else -> event(type, obj, json)?.let { RealtimeFrame.Event(it) }
        }
    }

    /** `ws.rs:124-128`. Both ids are always sent; a frame without them is not an `auth.ok` we can use. */
    private fun authOk(obj: JsonObject): RealtimeFrame? {
        val userId = obj.uuid("user_id") ?: return null
        val deviceId = obj.uuid("device_id") ?: return null
        return RealtimeFrame.AuthOk(userId, deviceId)
    }

    /**
     * `ws.rs:95-100, 108-114, 150-157, 271-276`. Every `auth.error` is one, with or without a
     * readable `error` — iOS fails the socket on the type alone (`RealtimeClient.swift:307-317`).
     */
    private fun authError(obj: JsonObject): RealtimeFrame {
        val error = obj["error"] as? JsonObject
        return RealtimeFrame.AuthError(code = error?.string("code"), message = error?.string("message"))
    }

    private fun event(type: String, obj: JsonObject, json: Json): RealtimeEvent? = when (type) {
        "message.new" -> messageNew(obj, json)
        "message.delivered" -> messageDelivered(obj)
        "message.read" -> messageRead(obj)
        "message.deleted" -> messageDeleted(obj)
        "message.reaction" -> messageReaction(obj, json)
        "reactions.seen" -> reactionsSeen(obj)
        "conversation.deleted" -> conversationDeleted(obj)
        "conversation.read" -> conversationRead(obj)
        "conversation.mute" -> conversationMute(obj)
        "typing" -> typing(obj)
        "recording" -> recording(obj)
        "presence.update" -> presence(obj)
        "call.ring" -> obj.decode("call", CallDto.serializer(), json)?.let { RealtimeEvent.CallRing(it) }
        "call.accepted" -> obj.decode("call", CallDto.serializer(), json)?.let { RealtimeEvent.CallAccepted(it) }
        "call.ended" -> obj.decode("call", CallDto.serializer(), json)?.let { RealtimeEvent.CallEnded(it) }
        "call.signal" -> callSignal(obj)
        "contact.request" -> contact(RealtimeEvent.ContactChanged.Kind.Request, obj, json)
        "contact.accepted" -> contact(RealtimeEvent.ContactChanged.Kind.Accepted, obj, json)
        "contact.rejected" -> contact(RealtimeEvent.ContactChanged.Kind.Rejected, obj, json)
        "contact.cancelled" -> contact(RealtimeEvent.ContactChanged.Kind.Cancelled, obj, json)
        "contact.updated" -> contact(RealtimeEvent.ContactChanged.Kind.Updated, obj, json)
        "contact.removed" -> RealtimeEvent.ContactChanged(
            kind = RealtimeEvent.ContactChanged.Kind.Removed,
            request = null,
            userId = obj.uuid("user_id"),
            peerUserId = obj.uuid("peer_user_id"),
        )
        else -> RealtimeEvent.Unknown(type, obj)
    }

    /**
     * `RealtimeClient.swift:318-324`: the whole frame decodes as `{type, message: MessageDTO}` or
     * the client emits the raw event, on which messaging refreshes the chats and reloads the open
     * thread (`MessagingController.swift:4100-4107`). An absent `message` fails that decode too,
     * so it is [RealtimeEvent.MessageNew] with null as well — never dropped.
     */
    private fun messageNew(obj: JsonObject, json: Json): RealtimeEvent =
        RealtimeEvent.MessageNew(obj.decode("message", MessageDto.serializer(), json))

    /** `MessagingController.swift:4162-4167`: `message_id` required. */
    private fun messageDelivered(obj: JsonObject): RealtimeEvent? {
        val messageId = obj.uuid("message_id") ?: return null
        return RealtimeEvent.MessageDelivered(messageId, obj.uuid("device_id"), obj.instant("delivered_at"))
    }

    /**
     * `MessagingController.swift:4214-4227`: a readable `up_to_message_id` wins, else
     * `message_id` must be readable. Both are kept; the consumer takes [RealtimeEvent.MessageRead.upToMessageId]
     * first.
     */
    private fun messageRead(obj: JsonObject): RealtimeEvent? {
        val upTo = obj.uuid("up_to_message_id")
        val messageId = obj.uuid("message_id")
        if (upTo == null && messageId == null) return null
        return RealtimeEvent.MessageRead(
            messageId = messageId,
            upToMessageId = upTo,
            conversationId = obj.uuid("conversation_id"),
            userId = obj.uuid("user_id"),
            deviceId = obj.uuid("device_id"),
            readAt = obj.instant("read_at"),
            marked = obj.number("marked"),
        )
    }

    /** `MessagingController.swift:4170-4176`: `message_id` required. */
    private fun messageDeleted(obj: JsonObject): RealtimeEvent? {
        val messageId = obj.uuid("message_id") ?: return null
        return RealtimeEvent.MessageDeleted(messageId, obj.uuid("conversation_id"))
    }

    /** `MessagingController.swift:5417-5424`: `reaction` must decode; `added` defaults to true. */
    private fun messageReaction(obj: JsonObject, json: Json): RealtimeEvent? {
        val reaction = obj.decode("reaction", ReactionDto.serializer(), json) ?: return null
        return RealtimeEvent.MessageReaction(
            reaction = reaction,
            conversationId = obj.uuid("conversation_id"),
            messageSenderId = obj.uuid("message_sender_id"),
            deviceId = obj.uuid("device_id"),
            added = obj.bool("added") ?: true,
        )
    }

    /** `MessagingController.swift:5475-5479`: `peer_user_id` and a numeric `seen_seq` required. */
    private fun reactionsSeen(obj: JsonObject): RealtimeEvent? {
        val peer = obj.uuid("peer_user_id") ?: return null
        val seen = obj.number("seen_seq") ?: return null
        return RealtimeEvent.ReactionsSeen(peer, seen, obj.uuid("conversation_id"))
    }

    /**
     * `MessagingController.swift:4186-4199`: `user_id` (the initiator) and `peer_user_id` required;
     * `cleared_for_peer` defaults to false.
     */
    private fun conversationDeleted(obj: JsonObject): RealtimeEvent? {
        val initiator = obj.uuid("user_id") ?: return null
        val other = obj.uuid("peer_user_id") ?: return null
        return RealtimeEvent.ConversationDeleted(
            initiatorUserId = initiator,
            otherUserId = other,
            conversationId = obj.uuid("conversation_id"),
            scope = obj.string("scope"),
            clearedForPeer = obj.bool("cleared_for_peer") ?: false,
        )
    }

    /** `MessagingController.swift:5650-5656`: `peer_user_id` required; `unread_count` defaults to 0. */
    private fun conversationRead(obj: JsonObject): RealtimeEvent? {
        val peer = obj.uuid("peer_user_id") ?: return null
        return RealtimeEvent.ConversationRead(
            peerUserId = peer,
            conversationId = obj.uuid("conversation_id"),
            readAt = obj.instant("read_at"),
            unreadCount = obj.number("unread_count")?.toInt() ?: 0,
        )
    }

    /**
     * `MessagingController.swift:5665-5675`: `peer_user_id` required; `mute` that is not an object
     * means unmuted; an object's `until` that is absent or unreadable means "until turned back on".
     */
    private fun conversationMute(obj: JsonObject): RealtimeEvent? {
        val peer = obj.uuid("peer_user_id") ?: return null
        val mute = (obj["mute"] as? JsonObject)?.let { ChatMuteDto(until = it.instant("until")) }
        return RealtimeEvent.ConversationMute(peer, mute)
    }

    /** `MessagingController.swift:4272-4278`: `user_id` and a Bool `is_typing` required. */
    private fun typing(obj: JsonObject): RealtimeEvent? {
        val user = obj.uuid("user_id") ?: return null
        val isTyping = obj.bool("is_typing") ?: return null
        return RealtimeEvent.Typing(user, isTyping)
    }

    /** `MessagingController.swift:4280-4286`: `user_id` and a Bool `is_recording` required. */
    private fun recording(obj: JsonObject): RealtimeEvent? {
        val user = obj.uuid("user_id") ?: return null
        val isRecording = obj.bool("is_recording") ?: return null
        return RealtimeEvent.Recording(user, isRecording)
    }

    /** `MessagingController.swift:4288-4296`: `user_id` and a Bool `online` required. */
    private fun presence(obj: JsonObject): RealtimeEvent? {
        val user = obj.uuid("user_id") ?: return null
        val online = obj.bool("online") ?: return null
        return RealtimeEvent.PresenceUpdate(user, online, obj.instant("last_seen_at"))
    }

    /**
     * `CallController.swift:953-963`: `call_id`, `signal_type` and a non-empty `payload` required;
     * `from_device_id` lower-cased, or `""` when absent.
     */
    private fun callSignal(obj: JsonObject): RealtimeEvent? {
        val callId = obj.uuid("call_id") ?: return null
        val signalType = obj.string("signal_type") ?: return null
        val payload = obj.string("payload")?.takeIf { it.isNotEmpty() } ?: return null
        return RealtimeEvent.CallSignal(
            callId = callId,
            fromUserId = obj.uuid("from_user_id"),
            fromDeviceId = obj.string("from_device_id")?.lowercase() ?: "",
            signalType = signalType,
            payload = payload,
        )
    }

    /**
     * `MessagingController.swift:4137-4160`: every `contact.*` refreshes the contacts; the
     * `request` (null when it does not decode) only feeds the optimistic insert of a pending one.
     */
    private fun contact(kind: RealtimeEvent.ContactChanged.Kind, obj: JsonObject, json: Json): RealtimeEvent =
        RealtimeEvent.ContactChanged(
            kind = kind,
            request = obj.decode("request", ContactRequestDto.serializer(), json),
            userId = null,
            peerUserId = null,
        )

    // Field readers with iOS `JSONSerialization` semantics: `as? String` takes JSON strings only,
    // `as? Bool` JSON booleans, `as? NSNumber` JSON numbers; ids go through `UUID(uuidString:)`.

    private fun JsonObject.string(key: String): String? =
        (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

    private fun JsonObject.uuid(key: String): UUID? = Ids.parse(string(key))

    private fun JsonObject.instant(key: String): Instant? = string(key)?.let(ApiTime::parse)

    private fun JsonObject.bool(key: String): Boolean? =
        (this[key] as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull

    /** `(x as? NSNumber)?.int64Value`: any JSON number, a fraction truncated. */
    private fun JsonObject.number(key: String): Long? {
        val primitive = (this[key] as? JsonPrimitive)?.takeIf { !it.isString } ?: return null
        return primitive.longOrNull ?: primitive.doubleOrNull?.takeIf { it.isFinite() }?.toLong()
    }

    /** `JSONDecoder.api.decode(T.self, from: data)` on a nested object; null when it does not decode. */
    private fun <T> JsonObject.decode(key: String, strategy: DeserializationStrategy<T>, json: Json): T? {
        val element: JsonElement = this[key] as? JsonObject ?: return null
        return try {
            json.decodeFromJsonElement(strategy, element)
        } catch (_: SerializationException) {
            null
        } catch (_: IllegalArgumentException) {
            null
        }
    }
}
