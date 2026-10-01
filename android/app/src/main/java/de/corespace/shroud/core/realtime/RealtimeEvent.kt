package de.corespace.shroud.core.realtime

import de.corespace.shroud.core.net.CallDto
import de.corespace.shroud.core.net.ChatMuteDto
import de.corespace.shroud.core.net.ContactRequestDto
import de.corespace.shroud.core.net.MessageDto
import de.corespace.shroud.core.net.ReactionDto
import kotlinx.serialization.json.JsonObject
import java.time.Instant
import java.util.UUID

// Typed socket events (api-realtime §11.12; server shapes §11.5, `routes/ws.rs` and the
// `publish` calls cited per event). iOS hands consumers `.messageNew(MessageDTO)` or
// `.raw(type:json:)` and each consumer picks fields out of the dictionary
// (`Services/Realtime/RealtimeClient.swift:292-351`); Android parses once, in
// `RealtimeEventParser` (W1-RT), applying exactly the guards iOS's consumers apply — a missing or
// malformed required field drops the event where iOS's guard drops it (table in §11.12).
//
// Ids are UUIDs (plan C1). Times parse with or without fractional seconds (`ApiTime.parse`);
// iOS's `apiFlexible` silently loses whole-second times (`MessagingController.swift:5882-5886`).
// The server can drop or duplicate events: every consumer is idempotent and keeps its catch-up
// path (api-realtime §17.2).

/** One server → client event, after `auth.ok`. Delivered through `RealtimeClient.events`. */
sealed interface RealtimeEvent {
    /**
     * `auth.ok` (`user_id`, `device_id`). [isReconnect]: not the socket's first `auth.ok` since a
     * holder asked for it — a call then checks what it missed (`RealtimeClient.swift:304-305`).
     */
    data class Connected(val userId: UUID, val deviceId: UUID, val isReconnect: Boolean) : RealtimeEvent

    /**
     * `message.new` (`messages.rs:370-383`): the `POST /messages` answer (`delivered:false`,
     * `read:false`, no `reactions`), annotations included. [message] null: the DTO did not decode
     * — refresh the chats and the open thread instead (`RealtimeClient.swift:318-324`,
     * `MessagingController.swift:4099-4107`).
     */
    data class MessageNew(val message: MessageDto?) : RealtimeEvent

    /** `message.delivered` (`messages.rs:1027-1045`); [deviceId] is the acking device. Requires `message_id` (MC:4162-4167). */
    data class MessageDelivered(val messageId: UUID, val deviceId: UUID?, val deliveredAt: Instant?) : RealtimeEvent

    /**
     * `message.read` (`messages.rs:838-860, 941-966`; chat read: [messageId] = the newest peer
     * message covered). A bulk read adds [upToMessageId] (= [messageId]) and [marked]. Requires
     * `up_to_message_id` or `message_id` (MC:4214-4227). [userId] is the reader.
     */
    data class MessageRead(
        val messageId: UUID?,
        val upToMessageId: UUID?,
        val conversationId: UUID?,
        val userId: UUID?,
        val deviceId: UUID?,
        val readAt: Instant?,
        val marked: Long?,
    ) : RealtimeEvent

    /** `message.deleted`, scope `everyone`, to all devices of both users (`messages.rs:1473-1485`). Requires `message_id` (MC:4170-4176). */
    data class MessageDeleted(val messageId: UUID, val conversationId: UUID?) : RealtimeEvent

    /**
     * `message.reaction` (`reactions.rs:965-995`): [reaction] must decode (MC:5417-5424);
     * [added] defaults to true. [deviceId] is the reactor's device; [messageSenderId] the author
     * of the reacted message.
     */
    data class MessageReaction(
        val reaction: ReactionDto,
        val conversationId: UUID?,
        val messageSenderId: UUID?,
        val deviceId: UUID?,
        val added: Boolean,
    ) : RealtimeEvent

    /** `reactions.seen` to the caller's other devices (`reactions.rs:589-660`). Requires both fields (MC:5475-5479). */
    data class ReactionsSeen(val peerUserId: UUID, val seenSeq: Long, val conversationId: UUID?) : RealtimeEvent

    /**
     * `conversation.deleted` (`conversations.rs:579-600, 621-636`): [initiatorUserId] is the
     * wire `user_id`, [otherUserId] the wire `peer_user_id` — the other participant, which is us
     * when the peer deleted. Requires both (MC:4186-4196); [clearedForPeer] defaults to false.
     * [scope] is `me` or `everyone`; [conversationId] is null on account deletion.
     */
    data class ConversationDeleted(
        val initiatorUserId: UUID,
        val otherUserId: UUID,
        val conversationId: UUID?,
        val scope: String?,
        val clearedForPeer: Boolean,
    ) : RealtimeEvent

    /** `conversation.read` to the reader's other devices (`conversations.rs:311-345`). Requires `peer_user_id`; [unreadCount] defaults to 0 (MC:5650-5656). */
    data class ConversationRead(
        val peerUserId: UUID,
        val conversationId: UUID?,
        val readAt: Instant?,
        val unreadCount: Int,
    ) : RealtimeEvent

    /** `conversation.mute` to the caller's other devices (`notifications.rs:251-275`); [mute] null = unmuted (MC:5665-5675). */
    data class ConversationMute(val peerUserId: UUID, val mute: ChatMuteDto?) : RealtimeEvent

    /** `typing` from [userId] (relayed client frame). Requires `user_id` and `is_typing` (MC:4272-4285). */
    data class Typing(val userId: UUID, val isTyping: Boolean) : RealtimeEvent

    /** `recording` (a voice message) from [userId]. Requires `user_id` and `is_recording` (MC:4272-4285). */
    data class Recording(val userId: UUID, val isRecording: Boolean) : RealtimeEvent

    /** `presence.update` to contacts who share presence. Requires `user_id` and `online` (MC:4288-4303). */
    data class PresenceUpdate(val userId: UUID, val online: Boolean, val lastSeenAt: Instant?) : RealtimeEvent

    /** `call.ring` (`calls.rs:286-305`), replayed on connect while ringing. [call] must decode (CC:769-771). */
    data class CallRing(val call: CallDto) : RealtimeEvent

    /** `call.accepted` to both users except the answering device (`calls.rs:446-447`; CC:929-941). */
    data class CallAccepted(val call: CallDto) : RealtimeEvent

    /** `call.ended` (`calls.rs:723-760, 967`; CC:929-941). */
    data class CallEnded(val call: CallDto) : RealtimeEvent

    /**
     * `call.signal` to the one other device in the call (`calls.rs:570-584`). Requires `call_id`,
     * `signal_type`, `payload` (CC:953-963). [fromDeviceId] is the wire value lower-cased, or `""`
     * when absent — kept as text because the signal checks compare it as iOS does.
     */
    data class CallSignal(
        val callId: UUID,
        val fromUserId: UUID?,
        val fromDeviceId: String,
        val signalType: String,
        val payload: String,
    ) : RealtimeEvent

    /**
     * `contact.request|accepted|rejected|cancelled|updated` carry [request] (null when it does not
     * decode); `contact.removed` carries [userId] (who removed) and [peerUserId]
     * (`contacts.rs:195-201, 264-283, 452-459, 536-543, 609-618, 684-700`; MC:4137-4160).
     */
    data class ContactChanged(
        val kind: Kind,
        val request: ContactRequestDto?,
        val userId: UUID?,
        val peerUserId: UUID?,
    ) : RealtimeEvent {
        /** The wire suffix after `contact.`. */
        enum class Kind { Request, Accepted, Rejected, Cancelled, Removed, Updated }
    }

    /** A `type` this build does not know. iOS drops these (`RealtimeClient.swift:333-334`); consumers ignore it. */
    data class Unknown(val type: String, val raw: JsonObject) : RealtimeEvent
}

/** What one text frame from the server is, before the client acts on it (W1-RT parses). */
sealed interface RealtimeFrame {
    /** `auth.ok`: the socket is authenticated as this device. */
    data class AuthOk(val userId: UUID, val deviceId: UUID) : RealtimeFrame

    /**
     * `auth.error` (`error {code, message}`), then the server closes. Codes: `UNAUTHORIZED`,
     * `DEVICE_REMOVED`, `RATE_LIMITED` — the policy is in plan §1.7.3 (C31).
     */
    data class AuthError(val code: String?, val message: String?) : RealtimeFrame

    /** Any other event. */
    data class Event(val event: RealtimeEvent) : RealtimeFrame
}
