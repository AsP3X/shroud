package de.corespace.shroud.core.push

import de.corespace.shroud.core.calls.CallPush
import de.corespace.shroud.core.model.AppClock
import de.corespace.shroud.core.net.CallDto
import de.corespace.shroud.core.net.CallModality
import de.corespace.shroud.core.net.CallStatus
import de.corespace.shroud.core.net.ContactRequestDto
import de.corespace.shroud.core.net.ContentType
import de.corespace.shroud.core.net.MessageDto
import de.corespace.shroud.core.notifications.NotificationKind
import de.corespace.shroud.core.notifications.PushContents
import de.corespace.shroud.core.realtime.RealtimeEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.util.UUID

/** One arrival per kind, a read, and one id dropped when the socket and the push both deliver it. */
class PushDispatcherTest {
    private val conv: UUID = UUID.fromString("3f2504e0-4f89-41d3-9a0c-0305e82c3301")
    private val peer: UUID = UUID.fromString("3f2504e0-4f89-41d3-9a0c-0305e82c3302")
    private val msg: UUID = UUID.fromString("3f2504e0-4f89-41d3-9a0c-0305e82c3303")
    private val callId: UUID = UUID.fromString("3f2504e0-4f89-41d3-9a0c-0305e82c3304")
    private val self: UUID = UUID.fromString("3f2504e0-4f89-41d3-9a0c-0305e82c3305")

    @Test
    fun everyKindAndARead() {
        val fx = sink()
        fx.dispatcher.dispatchPlaintext(payload(NotificationKind.Message.wire, "\"message_id\":\"$msg\",\"conversation_id\":\"$conv\",\"peer_user_id\":\"$peer\",\"sender\":\"Ada\""))
        fx.dispatcher.dispatchPlaintext(payload(NotificationKind.Reaction.wire, "\"message_id\":\"$msg\",\"conversation_id\":\"$conv\",\"peer_user_id\":\"$peer\",\"sender\":\"Ada\""))
        fx.dispatcher.dispatchPlaintext(payload(NotificationKind.ContactRequest.wire, "\"peer_user_id\":\"$peer\",\"sender\":\"Ada\""))
        fx.dispatcher.dispatchPlaintext(payload(NotificationKind.Test.wire, "\"sender\":\"Ada\""))
        fx.dispatcher.dispatchPlaintext(payload(NotificationKind.Call.wire, "\"call_id\":\"$callId\",\"peer_user_id\":\"$peer\",\"sender\":\"Ada\""))
        fx.dispatcher.dispatchPlaintext(payload(NotificationKind.VideoCall.wire, "\"call_id\":\"$callId\",\"peer_user_id\":\"$peer\",\"sender\":\"Ada\""))
        fx.dispatcher.dispatchPlaintext(payload(NotificationKind.CallEnded.wire, "\"call_id\":\"$callId\",\"peer_user_id\":\"$peer\",\"sender\":\"Ada\""))
        fx.dispatcher.dispatchPlaintext(payload(NotificationKind.MissedCall.wire, "\"call_id\":\"$callId\",\"peer_user_id\":\"$peer\",\"sender\":\"Ada\""))
        fx.dispatcher.dispatchPlaintext(payload(PushContents.KIND_READ, "\"conversation_id\":\"$conv\""))
        fx.dispatcher.dispatchPlaintext("""{"v":1,"kind":"device_removed"}""".toByteArray())

        assertEquals(
            listOf(NotificationKind.Message, NotificationKind.Reaction, NotificationKind.ContactRequest, NotificationKind.Test),
            fx.posted.map { it.first },
        )
        assertTrue(fx.posted.all { it.second == "Ada" })
        assertEquals(
            listOf(NotificationKind.Call, NotificationKind.VideoCall, NotificationKind.CallEnded, NotificationKind.MissedCall),
            fx.calls.map { it.kind },
        )
        assertTrue(fx.calls.all { it.source == CallPush.Source.UnifiedPush && it.callerName == "Ada" && it.callId == callId })
        assertEquals(listOf(conv), fx.cancelled)
        assertEquals(1, fx.removals[0])
    }

    @Test
    fun socketUsesTheNameCacheAndDedupsAgainstPush() {
        val fx = sink()
        fx.dispatcher.onSocket(RealtimeEvent.MessageNew(message()))
        fx.dispatcher.dispatchPlaintext(payload(NotificationKind.Message.wire, "\"message_id\":\"$msg\",\"conversation_id\":\"$conv\",\"peer_user_id\":\"$peer\",\"sender\":\"Ada\""))
        assertEquals(1, fx.posted.size)
        assertEquals(NotificationKind.Message, fx.posted.single().first)
        assertEquals("Cached", fx.posted.single().second)
        assertEquals(listOf(peer to "Ada"), fx.remembered)
    }

    @Test
    fun aRingAndItsEndBothPass() {
        val fx = sink()
        fx.dispatcher.onSocket(RealtimeEvent.CallRing(call(CallModality.Voice.wire)))
        fx.dispatcher.onSocket(RealtimeEvent.CallEnded(call(CallModality.Voice.wire, CallStatus.Ended.wire)))
        assertEquals(listOf(NotificationKind.Call, NotificationKind.CallEnded), fx.calls.map { it.kind })
        assertTrue(fx.calls.all { it.source == CallPush.Source.BackgroundSocket })
        assertTrue(fx.posted.isEmpty())
    }

    @Test
    fun aReadOnTheSocketClosesTheChat() {
        val fx = sink()
        fx.dispatcher.onSocket(RealtimeEvent.ConversationRead(peer, conv, null, 0))
        assertEquals(listOf(conv), fx.cancelled)
        assertTrue(fx.posted.isEmpty())
    }

    @Test
    fun ourOwnMessageClosesTheChat() {
        val fx = sink()
        fx.dispatcher.onSocket(RealtimeEvent.MessageNew(message(sender = self)))
        assertEquals(listOf(conv), fx.cancelled)
        assertTrue(fx.posted.isEmpty())
    }

    @Test
    fun anAnnotationIsNotANotification() {
        val fx = sink()
        fx.dispatcher.onSocket(RealtimeEvent.MessageNew(message(contentType = ContentType.ANNOTATION)))
        assertTrue(fx.posted.isEmpty())
        assertTrue(fx.cancelled.isEmpty())
    }

    @Test
    fun theOpenChatIsNotPostedAgain() {
        val fx = sink(running = true)
        fx.dispatcher.dispatchPlaintext(payload(NotificationKind.Message.wire, "\"message_id\":\"$msg\",\"conversation_id\":\"$conv\",\"sender\":\"Ada\""))
        assertTrue(fx.posted.isEmpty())
    }

    @Test
    fun aContactRequestUsesTheCacheWhenThePayloadHasNoName() {
        val fx = sink()
        fx.dispatcher.onSocket(
            RealtimeEvent.ContactChanged(
                kind = RealtimeEvent.ContactChanged.Kind.Request,
                request = ContactRequestDto(msg, peer, self, "pending", Instant.parse("2026-10-02T00:00:00Z")),
                userId = null,
                peerUserId = null,
            ),
        )
        assertEquals("Cached", fx.posted.single().second)
        assertEquals(NotificationKind.ContactRequest, fx.posted.single().first)
    }

    private fun payload(kind: String, fields: String): ByteArray =
        """{"v":1,"kind":"$kind",$fields}""".toByteArray()

    private fun message(
        sender: UUID = peer,
        contentType: String = ContentType.TEXT,
    ) = MessageDto(
        id = msg,
        conversationId = conv,
        senderUserId = sender,
        senderDeviceId = peer,
        clientMessageId = msg,
        contentType = contentType,
        deletedForEveryone = false,
        createdAtWire = "2026-10-02T00:00:00Z",
    )

    private fun call(modality: String, status: String = CallStatus.Ringing.wire) = CallDto(
        id = callId,
        callerUserId = peer,
        callerDeviceId = peer,
        callerUsername = "Ada",
        calleeUserId = self,
        modality = modality,
        status = status,
        createdAtWire = "2026-10-02T00:00:00Z",
    )

    private class Sink(
        val dispatcher: PushDispatcher,
        val posted: MutableList<Pair<NotificationKind?, String?>>,
        val cancelled: MutableList<UUID>,
        val calls: MutableList<CallPush>,
        val removals: IntArray,
        val remembered: MutableList<Pair<UUID, String>>,
    )

    private fun sink(running: Boolean = false): Sink {
        val posted = mutableListOf<Pair<NotificationKind?, String?>>()
        val cancelled = mutableListOf<UUID>()
        val calls = mutableListOf<CallPush>()
        val removals = intArrayOf(0)
        val remembered = mutableListOf<Pair<UUID, String>>()
        val dispatcher = PushDispatcher(
            dedup = PushDedup(),
            clock = object : AppClock {
                override fun nowMillis(): Long = 0
                override fun elapsedMillis(): Long = 0
            },
            post = { contents, name -> posted += contents.kind to name },
            cancelChat = { cancelled += it },
            onPushWhileRunning = { _, _ -> running },
            calls = { calls += it },
            scheduleRemoval = { removals[0]++ },
            nameFor = { "Cached" },
            selfUserId = { self.toString() },
            rememberName = { id, name -> remembered += id to name },
        )
        return Sink(dispatcher, posted, cancelled, calls, removals, remembered)
    }
}
