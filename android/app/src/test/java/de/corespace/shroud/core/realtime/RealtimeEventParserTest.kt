package de.corespace.shroud.core.realtime

import de.corespace.shroud.core.net.CallStatus
import de.corespace.shroud.core.net.ContactRequestStatus
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.util.UUID

/**
 * One frame per server event (api-realtime §11.5; shapes from the server's `routes/` files) and each
 * required-field drop of §11.12, where the iOS consumer's guard drops it.
 */
class RealtimeEventParserTest {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; explicitNulls = true }

    private val alice = "11111111-1111-1111-1111-111111111111"
    private val bob = "22222222-2222-2222-2222-222222222222"
    private val conversation = "44444444-4444-4444-4444-444444444444"
    private val message = "7c9e6679-7425-40de-944b-e07fc1f90ae7"
    private val device = "3f2504e0-4f89-41d3-9a0c-0305e82c3301"

    private fun parse(text: String): RealtimeFrame? = RealtimeEventParser.parse(text, json)

    private fun event(text: String): RealtimeEvent? = (parse(text) as? RealtimeFrame.Event)?.event

    private fun uuid(text: String): UUID = UUID.fromString(text)

    // ---- Frames the client drops (RealtimeClient.swift:293-296) ----

    @Test
    fun notJsonNoObjectNoTypeAreDropped() {
        assertNull(parse("not json"))
        assertNull(parse("[1,2]"))
        assertNull(parse("""{"user_id":"$alice"}"""))
        assertNull(parse("""{"type":7}"""))
        assertNull(parse(""))
    }

    // ---- Handshake (ws.rs:95-157) ----

    @Test
    fun authOk() {
        // Upper-case ids are read like any id; the server sends lower case.
        val frame = parse("""{"type":"auth.ok","user_id":"$alice","device_id":"${device.uppercase()}"}""")
        assertEquals(RealtimeFrame.AuthOk(uuid(alice), uuid(device)), frame)
        assertNull(parse("""{"type":"auth.ok","user_id":"$alice"}"""))
        assertNull(parse("""{"type":"auth.ok","user_id":"1-1-1-1-1","device_id":"$device"}"""))
    }

    @Test
    fun authErrorCodes() {
        assertEquals(
            RealtimeFrame.AuthError("UNAUTHORIZED", "Authentication required."),
            parse("""{"type":"auth.error","error":{"code":"UNAUTHORIZED","message":"Authentication required."}}"""),
        )
        assertEquals(
            RealtimeFrame.AuthError("DEVICE_REMOVED", "This device was removed from your account."),
            parse("""{"type":"auth.error","error":{"code":"DEVICE_REMOVED","message":"This device was removed from your account."}}"""),
        )
        assertEquals(
            RealtimeFrame.AuthError("RATE_LIMITED", "Too many WebSocket connections for this account."),
            parse("""{"type":"auth.error","error":{"code":"RATE_LIMITED","message":"Too many WebSocket connections for this account."}}"""),
        )
        // iOS fails the socket on the type alone (RealtimeClient.swift:307-317).
        assertEquals(RealtimeFrame.AuthError(null, null), parse("""{"type":"auth.error"}"""))
    }

    // ---- Messages ----

    private fun messageJson(createdAt: String = "2026-09-24T12:00:00.123456Z") =
        """{"id":"$message","conversation_id":"$conversation","sender_user_id":"$alice","sender_device_id":"$device","client_message_id":"33333333-3333-3333-3333-333333333333","content_type":"text","ciphertext":"c2VhbGVk","deleted_for_everyone":false,"created_at":"$createdAt","delivered":false,"read":false}"""

    @Test
    fun messageNewDecodesTheDto() {
        val event = event("""{"type":"message.new","message":${messageJson()}}""") as RealtimeEvent.MessageNew
        val dto = assertNotNullAndGet(event.message)
        assertEquals(uuid(message), dto.id)
        assertEquals("2026-09-24T12:00:00.123456Z", dto.createdAtWire)
        assertEquals("c2VhbGVk", dto.ciphertext)
    }

    @Test
    fun undecodableOrMissingMessageIsMessageNewNull() {
        // RealtimeClient.swift:318-324: the raw event makes messaging refresh (MC:4100-4107).
        assertEquals(RealtimeEvent.MessageNew(null), event("""{"type":"message.new","message":${messageJson(createdAt = "yesterday")}}"""))
        assertEquals(RealtimeEvent.MessageNew(null), event("""{"type":"message.new","message":{"id":"$message"}}"""))
        assertEquals(RealtimeEvent.MessageNew(null), event("""{"type":"message.new"}"""))
        assertEquals(RealtimeEvent.MessageNew(null), event("""{"type":"message.new","message":"nope"}"""))
    }

    @Test
    fun messageDelivered() {
        assertEquals(
            RealtimeEvent.MessageDelivered(uuid(message), uuid(device), Instant.parse("2026-09-24T12:00:01.5Z")),
            event("""{"type":"message.delivered","message_id":"$message","device_id":"$device","delivered_at":"2026-09-24T12:00:01.5Z"}"""),
        )
        assertEquals(
            RealtimeEvent.MessageDelivered(uuid(message), null, null),
            event("""{"type":"message.delivered","message_id":"$message"}"""),
        )
        assertNull(event("""{"type":"message.delivered","device_id":"$device"}"""))
        assertNull(event("""{"type":"message.delivered","message_id":"nope"}"""))
    }

    @Test
    fun messageReadSingleAndBulk() {
        // messages.rs:945-966 (one message).
        val single = event(
            """{"type":"message.read","message_id":"$message","conversation_id":"$conversation","user_id":"$bob","device_id":"$device","read_at":"2026-09-24T12:00:00.123456Z"}""",
        )
        assertEquals(
            RealtimeEvent.MessageRead(
                messageId = uuid(message),
                upToMessageId = null,
                conversationId = uuid(conversation),
                userId = uuid(bob),
                deviceId = uuid(device),
                readAt = Instant.parse("2026-09-24T12:00:00.123456Z"),
                marked = null,
            ),
            single,
        )
        // messages.rs:838-860 (bulk): up_to_message_id and marked.
        val bulk = event(
            """{"type":"message.read","message_id":"$message","conversation_id":"$conversation","user_id":"$bob","device_id":"$device","read_at":"2026-09-24T12:00:00Z","up_to_message_id":"$message","marked":4}""",
        ) as RealtimeEvent.MessageRead
        assertEquals(uuid(message), bulk.upToMessageId)
        assertEquals(4L, bulk.marked)
        assertEquals(Instant.parse("2026-09-24T12:00:00Z"), bulk.readAt)
        // MC:4214-4227: either id will do; a bad up_to falls back to message_id.
        assertEquals(uuid(message), (event("""{"type":"message.read","up_to_message_id":"$message"}""") as RealtimeEvent.MessageRead).upToMessageId)
        assertEquals(uuid(message), (event("""{"type":"message.read","up_to_message_id":"x","message_id":"$message"}""") as RealtimeEvent.MessageRead).messageId)
        assertNull(event("""{"type":"message.read","conversation_id":"$conversation","user_id":"$bob"}"""))
    }

    @Test
    fun messageDeleted() {
        assertEquals(
            RealtimeEvent.MessageDeleted(uuid(message), uuid(conversation)),
            event("""{"type":"message.deleted","message_id":"$message","conversation_id":"$conversation","scope":"everyone"}"""),
        )
        assertNull(event("""{"type":"message.deleted","conversation_id":"$conversation"}"""))
    }

    // ---- Reactions ----

    private val reactionJson =
        """{"message_id":"$message","user_id":"$device","ciphertext":"aGVhcnQ=","seq":43,"updated_at":"2026-09-23T21:08:35.759754Z"}"""

    @Test
    fun messageReaction() {
        val event = event(
            """{"type":"message.reaction","conversation_id":"$conversation","message_sender_id":"$alice","device_id":"$device","added":false,"reaction":$reactionJson}""",
        ) as RealtimeEvent.MessageReaction
        assertEquals(43L, event.reaction.seq)
        assertEquals("aGVhcnQ=", event.reaction.ciphertext)
        assertEquals(uuid(conversation), event.conversationId)
        assertEquals(uuid(alice), event.messageSenderId)
        assertEquals(uuid(device), event.deviceId)
        assertFalse(event.added)
        // MC:5424: added defaults to true; a removed reaction has a null ciphertext.
        val removed = event(
            """{"type":"message.reaction","reaction":{"message_id":"$message","user_id":"$device","ciphertext":null,"seq":44,"updated_at":"2026-09-23T21:08:36Z"}}""",
        ) as RealtimeEvent.MessageReaction
        assertTrue(removed.added)
        assertNull(removed.reaction.ciphertext)
        // MC:5418-5421: the reaction must decode.
        assertNull(event("""{"type":"message.reaction","conversation_id":"$conversation","added":true}"""))
        assertNull(event("""{"type":"message.reaction","reaction":{"message_id":"$message","seq":1}}"""))
    }

    @Test
    fun reactionsSeen() {
        assertEquals(
            RealtimeEvent.ReactionsSeen(uuid(bob), 42L, uuid(conversation)),
            event("""{"type":"reactions.seen","conversation_id":"$conversation","peer_user_id":"$bob","seen_seq":42}"""),
        )
        assertNull(event("""{"type":"reactions.seen","peer_user_id":"$bob"}"""))
        assertNull(event("""{"type":"reactions.seen","peer_user_id":"$bob","seen_seq":"42"}"""))
        assertNull(event("""{"type":"reactions.seen","seen_seq":42}"""))
    }

    // ---- Conversations ----

    @Test
    fun conversationDeleted() {
        assertEquals(
            RealtimeEvent.ConversationDeleted(uuid(alice), uuid(bob), uuid(conversation), "everyone", true),
            event("""{"type":"conversation.deleted","conversation_id":"$conversation","user_id":"$alice","peer_user_id":"$bob","scope":"everyone","cleared_for_peer":true}"""),
        )
        // Account deletion: no conversation id; cleared_for_peer defaults to false (MC:4199).
        assertEquals(
            RealtimeEvent.ConversationDeleted(uuid(alice), uuid(bob), null, "me", false),
            event("""{"type":"conversation.deleted","conversation_id":null,"user_id":"$alice","peer_user_id":"$bob","scope":"me"}"""),
        )
        assertNull(event("""{"type":"conversation.deleted","user_id":"$alice"}"""))
        assertNull(event("""{"type":"conversation.deleted","peer_user_id":"$bob"}"""))
    }

    @Test
    fun conversationRead() {
        assertEquals(
            RealtimeEvent.ConversationRead(uuid(bob), uuid(conversation), Instant.parse("2026-09-24T12:00:00Z"), 2),
            event("""{"type":"conversation.read","conversation_id":"$conversation","peer_user_id":"$bob","read_at":"2026-09-24T12:00:00Z","unread_count":2}"""),
        )
        // MC:5656: unread_count defaults to 0; read_at may be null.
        assertEquals(
            RealtimeEvent.ConversationRead(uuid(bob), null, null, 0),
            event("""{"type":"conversation.read","peer_user_id":"$bob","read_at":null}"""),
        )
        assertNull(event("""{"type":"conversation.read","conversation_id":"$conversation"}"""))
    }

    @Test
    fun conversationMute() {
        val muted = event("""{"type":"conversation.mute","peer_user_id":"$bob","mute":{"until":"2026-09-24T19:00:00.5Z"}}""") as RealtimeEvent.ConversationMute
        assertEquals(Instant.parse("2026-09-24T19:00:00.5Z"), muted.mute?.until)
        // A whole-second `until` (iOS's apiFlexible loses it, MessagingController.swift:5882-5886).
        val wholeSecond = event("""{"type":"conversation.mute","peer_user_id":"$bob","mute":{"until":"2026-09-24T19:00:00Z"}}""") as RealtimeEvent.ConversationMute
        assertEquals(Instant.parse("2026-09-24T19:00:00Z"), wholeSecond.mute?.until)
        // Until turned back on.
        val forever = event("""{"type":"conversation.mute","peer_user_id":"$bob","mute":{"until":null}}""") as RealtimeEvent.ConversationMute
        assertNotNull(forever.mute)
        assertNull(forever.mute?.until)
        // Unmuted: null or not an object (MC:5671).
        assertEquals(RealtimeEvent.ConversationMute(uuid(bob), null), event("""{"type":"conversation.mute","peer_user_id":"$bob","mute":null}"""))
        assertEquals(RealtimeEvent.ConversationMute(uuid(bob), null), event("""{"type":"conversation.mute","peer_user_id":"$bob"}"""))
        assertNull(event("""{"type":"conversation.mute","mute":null}"""))
    }

    // ---- Typing, recording, presence ----

    @Test
    fun typingAndRecording() {
        assertEquals(
            RealtimeEvent.Typing(uuid(alice), true),
            event("""{"type":"typing","is_typing":true,"user_id":"$alice","device_id":"$device","peer_user_id":"$bob"}"""),
        )
        assertEquals(
            RealtimeEvent.Recording(uuid(alice), false),
            event("""{"type":"recording","is_recording":false,"user_id":"$alice","device_id":"$device","peer_user_id":"$bob"}"""),
        )
        // MC:4272-4285: the Bool is required (the web would default it to true), as is user_id.
        assertNull(event("""{"type":"typing","user_id":"$alice"}"""))
        assertNull(event("""{"type":"typing","user_id":"$alice","is_typing":"true"}"""))
        assertNull(event("""{"type":"typing","is_typing":true}"""))
        assertNull(event("""{"type":"recording","user_id":"$alice"}"""))
        assertNull(event("""{"type":"recording","is_recording":true}"""))
    }

    @Test
    fun presenceUpdate() {
        // A whole-second last_seen_at parses (api-realtime §13).
        assertEquals(
            RealtimeEvent.PresenceUpdate(uuid(alice), false, Instant.parse("2026-07-15T12:00:00Z")),
            event("""{"type":"presence.update","user_id":"$alice","online":false,"last_seen_at":"2026-07-15T12:00:00Z"}"""),
        )
        assertEquals(
            RealtimeEvent.PresenceUpdate(uuid(alice), true, null),
            event("""{"type":"presence.update","user_id":"$alice","online":true,"last_seen_at":null}"""),
        )
        assertNull(event("""{"type":"presence.update","user_id":"$alice"}"""))
        assertNull(event("""{"type":"presence.update","online":true}"""))
    }

    // ---- Calls ----

    private val callJson =
        """{"id":"$message","caller_user_id":"$alice","caller_device_id":"$device","caller_username":"alice","callee_user_id":"$bob","callee_username":null,"modality":"video","status":"ringing","protocol":2,"created_at":"2026-09-30T09:41:00.5Z"}"""

    @Test
    fun callRingAcceptedEnded() {
        val ring = event("""{"type":"call.ring","call":$callJson}""") as RealtimeEvent.CallRing
        assertEquals(uuid(message), ring.call.id)
        assertEquals(CallStatus.Ringing, ring.call.callStatus)
        assertEquals(2, ring.call.callProtocol)
        assertEquals("2026-09-30T09:41:00.5Z", ring.call.createdAtWire)
        assertTrue(event("""{"type":"call.accepted","call":$callJson}""") is RealtimeEvent.CallAccepted)
        assertTrue(event("""{"type":"call.ended","call":$callJson}""") is RealtimeEvent.CallEnded)
        // CC:769-771, 929-941: the call must decode.
        assertNull(event("""{"type":"call.ring"}"""))
        assertNull(event("""{"type":"call.accepted","call":{"id":"$message"}}"""))
        assertNull(event("""{"type":"call.ended","call":null}"""))
    }

    @Test
    fun callSignal() {
        assertEquals(
            RealtimeEvent.CallSignal(uuid(message), uuid(alice), device, "offer", "c2VhbGVk"),
            event("""{"type":"call.signal","call_id":"$message","from_user_id":"$alice","from_device_id":"${device.uppercase()}","signal_type":"offer","payload":"c2VhbGVk"}"""),
        )
        // from_device_id absent → "" (CC:963).
        assertEquals(
            RealtimeEvent.CallSignal(uuid(message), null, "", "ice", "x"),
            event("""{"type":"call.signal","call_id":"$message","signal_type":"ice","payload":"x"}"""),
        )
        // CC:953-959: call_id, signal_type and a non-empty payload are required.
        assertNull(event("""{"type":"call.signal","signal_type":"offer","payload":"x"}"""))
        assertNull(event("""{"type":"call.signal","call_id":"$message","payload":"x"}"""))
        assertNull(event("""{"type":"call.signal","call_id":"$message","signal_type":"offer"}"""))
        assertNull(event("""{"type":"call.signal","call_id":"$message","signal_type":"offer","payload":""}"""))
    }

    // ---- Contacts (MC:4137-4160) ----

    private val requestJson =
        """{"id":"$message","from_user_id":"$alice","to_user_id":"$bob","status":"pending","created_at":"2026-09-24T10:00:00.123456Z","responded_at":null,"user":{"id":"$alice","username":"alice"}}"""

    @Test
    fun contactEvents() {
        val request = event("""{"type":"contact.request","request":$requestJson}""") as RealtimeEvent.ContactChanged
        assertEquals(RealtimeEvent.ContactChanged.Kind.Request, request.kind)
        assertEquals(ContactRequestStatus.PENDING, request.request?.status)
        assertEquals("alice", request.request?.user?.username)
        val kinds = mapOf(
            "contact.accepted" to RealtimeEvent.ContactChanged.Kind.Accepted,
            "contact.rejected" to RealtimeEvent.ContactChanged.Kind.Rejected,
            "contact.cancelled" to RealtimeEvent.ContactChanged.Kind.Cancelled,
            "contact.updated" to RealtimeEvent.ContactChanged.Kind.Updated,
        )
        for ((type, kind) in kinds) {
            val changed = event("""{"type":"$type","request":$requestJson}""") as RealtimeEvent.ContactChanged
            assertEquals(kind, changed.kind)
            assertNotNull(changed.request)
        }
        // A request that does not decode still refreshes the contacts.
        assertEquals(
            RealtimeEvent.ContactChanged(RealtimeEvent.ContactChanged.Kind.Rejected, null, null, null),
            event("""{"type":"contact.rejected","request":{"id":"$message"}}"""),
        )
        assertEquals(
            RealtimeEvent.ContactChanged(RealtimeEvent.ContactChanged.Kind.Removed, null, uuid(alice), uuid(bob)),
            event("""{"type":"contact.removed","user_id":"$alice","peer_user_id":"$bob"}"""),
        )
        // contact.removed requires nothing.
        assertEquals(
            RealtimeEvent.ContactChanged(RealtimeEvent.ContactChanged.Kind.Removed, null, null, null),
            event("""{"type":"contact.removed"}"""),
        )
    }

    // ---- Unknown types (iOS drops them, RealtimeClient.swift:333-334) ----

    @Test
    fun unknownTypeIsKept() {
        val unknown = event("""{"type":"x.y","n":1}""") as RealtimeEvent.Unknown
        assertEquals("x.y", unknown.type)
        assertEquals("1", unknown.raw["n"]?.jsonPrimitive?.content)
        assertTrue(event("""{"type":"contact.archived"}""") is RealtimeEvent.Unknown)
    }

    private fun <T> assertNotNullAndGet(value: T?): T {
        assertNotNull(value)
        return value!!
    }
}
