package de.corespace.shroud.core.notifications

import androidx.core.app.NotificationCompat
import de.corespace.shroud.core.storage.StorageSeal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

/**
 * What the shade shows (notifications-push §5.7.2, §5.12.10, N6, P11c, P11d; `SystemNotifierCountTest`
 * of §7.2; iOS `NotificationPayloadTests.testDressNamesTheSenderAndNeverShowsText`,
 * `NotificationPayloadTests.swift:70-86`, with Android's "Shroud" title fallback).
 */
class SystemNotifierTest {
    private val sink = FakeSink()
    private val store = FakeChannelStore()
    private var prefs = NotificationPrefsState()
    private val channels = NotificationChannels(store) { prefs }
    private val seal = StorageSeal()
    private val notifier = SystemNotifier(sink, channels, seal, DirectExecutor)

    private val chat = UUID.fromString("6f9619ff-8b86-4d01-b42d-00c04fc964ff")
    private val otherChat = UUID.fromString("0b1c2d3e-4f50-4617-8293-a4b5c6d7e8f9")
    private val peer = UUID.fromString("5f0c3a52-7b1e-4c6d-9a8b-2e4f6d8c0a1b")
    private val call = UUID.fromString("00000000-0000-0000-0000-00000000001f")

    private fun push(kind: NotificationKind, conversation: UUID? = chat, callId: UUID? = null, badge: Int? = null) = PushContents(
        kind = kind,
        rawKind = kind.wire,
        thread = PushContents.threadFor(kind, conversation),
        conversationId = conversation,
        peerUserId = peer,
        callId = callId,
        badge = badge,
    )

    /** `testDressNamesTheSenderAndNeverShowsText`: the name as the title, the kind's line as the body. */
    @Test
    fun namesTheSenderAndNeverShowsText() {
        notifier.post(push(NotificationKind.Message), name = "alice")
        val shown = sink.showing("6f9619ff-8b86-4d01-b42d-00c04fc964ff", SystemNotifier.ID_MESSAGE)!!
        assertEquals("alice", shown.title)
        assertEquals("alice", shown.name)
        assertEquals("New message", shown.body)
        assertEquals("messages.default.b", shown.channelId)
        assertEquals(NotificationCompat.CATEGORY_MESSAGE, shown.category)
        assertEquals(NotificationKind.Message, shown.kind)
        assertEquals(peer, shown.peerUserId)

        notifier.post(push(NotificationKind.Reaction), name = null)
        val reaction = sink.showing("6f9619ff-8b86-4d01-b42d-00c04fc964ff:reaction", SystemNotifier.ID_REACTION)!!
        assertEquals("Shroud", reaction.title)
        assertNull("no name, no avatar", reaction.name)
        assertEquals("Reacted to your message", reaction.body)
        assertEquals(NotificationCompat.CATEGORY_SOCIAL, reaction.category)
    }

    /** N6: one notification per chat that counts; another chat has its own; reactions never count. */
    @Test
    fun aChatsMessagesCount() {
        notifier.post(push(NotificationKind.Message), "alice")
        notifier.post(push(NotificationKind.Message), "alice")
        val tag = chat.toString()
        assertEquals("2 new messages", sink.showing(tag, SystemNotifier.ID_MESSAGE)!!.body)
        assertEquals(2, sink.showing(tag, SystemNotifier.ID_MESSAGE)!!.count)
        notifier.post(push(NotificationKind.Message), "alice")
        assertEquals("3 new messages", sink.showing(tag, SystemNotifier.ID_MESSAGE)!!.body)

        notifier.post(push(NotificationKind.Message, conversation = otherChat), "bob")
        assertEquals("New message", sink.showing(otherChat.toString(), SystemNotifier.ID_MESSAGE)!!.body)

        notifier.post(push(NotificationKind.Reaction), "alice")
        notifier.post(push(NotificationKind.Reaction), "alice")
        val reaction = sink.showing("$tag:reaction", SystemNotifier.ID_REACTION)!!
        assertEquals("Reacted to your message", reaction.body)
        assertEquals(1, reaction.count)
        assertEquals("3 new messages", sink.showing(tag, SystemNotifier.ID_MESSAGE)!!.body)
    }

    /** A dismissed notification starts counting again. */
    @Test
    fun aDismissedChatStartsOver() {
        notifier.post(push(NotificationKind.Message), "alice")
        notifier.post(push(NotificationKind.Message), "alice")
        sink.cancel(chat.toString(), SystemNotifier.ID_MESSAGE)
        notifier.post(push(NotificationKind.Message), "alice")
        assertEquals("New message", sink.showing(chat.toString(), SystemNotifier.ID_MESSAGE)!!.body)
    }

    /** `clearDelivered` (`NotificationsController.swift:227-241`): the chat's messages and reactions go. */
    @Test
    fun readingAChatClosesItsNotifications() {
        notifier.post(push(NotificationKind.Message), "alice")
        notifier.post(push(NotificationKind.Reaction), "alice")
        notifier.post(push(NotificationKind.Message, conversation = otherChat), "bob")
        notifier.cancelChat(chat)
        assertEquals(setOf(otherChat.toString() to SystemNotifier.ID_MESSAGE), sink.shade.keys)
    }

    /** P11d: the server's `read` push closes the chat read on another device. */
    @Test
    fun aReadPushClosesTheChat() {
        notifier.post(push(NotificationKind.Message), "alice")
        notifier.post(push(NotificationKind.Reaction), "alice")
        notifier.post(PushContents(kind = null, rawKind = PushContents.KIND_READ, thread = chat.toString(), conversationId = chat, badge = 0), null)
        assertTrue(sink.shade.isEmpty())
        assertEquals(2, sink.posted.size)
    }

    @Test
    fun contactRequestsAndTheTest() {
        notifier.post(push(NotificationKind.ContactRequest, conversation = null), "carol")
        val request = sink.showing("contacts:$peer", SystemNotifier.ID_CONTACT_REQUEST)!!
        assertEquals("contact_requests.default.b", request.channelId)
        assertEquals("Wants to add you as a contact", request.body)
        assertEquals("carol", request.title)

        notifier.post(push(NotificationKind.Test, conversation = null), "ignored")
        val test = sink.showing("test", SystemNotifier.ID_TEST)!!
        assertEquals("Shroud", test.title)
        assertNull(test.name)
        assertEquals("Notifications are working", test.body)
        assertEquals("messages.default.b", test.channelId)
    }

    /** §5.18 until Calls rings itself: a plain ring for the ring time, replaced by its missed call; `call_ended` takes it away. */
    @Test
    fun callKindsShareOneSlot() {
        notifier.post(push(NotificationKind.VideoCall, conversation = null, callId = call), "dave")
        val tag = "call:00000000-0000-0000-0000-00000000001f"
        val ring = sink.showing(tag, SystemNotifier.ID_CALL)!!
        assertEquals("Incoming video call", ring.body)
        assertEquals("calls.missed.default.b", ring.channelId)
        assertEquals(60_000L, ring.timeoutMs)
        assertEquals(NotificationCompat.CATEGORY_CALL, ring.category)

        notifier.post(push(NotificationKind.MissedCall, conversation = null, callId = call), "dave")
        val missed = sink.showing(tag, SystemNotifier.ID_CALL)!!
        assertEquals("Missed call", missed.body)
        assertNull(missed.timeoutMs)
        assertEquals(NotificationCompat.CATEGORY_MISSED_CALL, missed.category)
        assertEquals(1, sink.shade.size)

        notifier.post(push(NotificationKind.Call, conversation = null, callId = call), "dave")
        notifier.post(push(NotificationKind.CallEnded, conversation = null, callId = call), "dave")
        assertTrue(sink.shade.isEmpty())
    }

    @Test
    fun unknownKindsAreDropped() {
        notifier.post(PushContents(kind = null, rawKind = "future_kind", thread = "shroud"), "alice")
        assertTrue(sink.posted.isEmpty())
    }

    @Test
    fun theBadgeBecomesTheNumber() {
        notifier.post(push(NotificationKind.Message, badge = 7), "alice")
        assertEquals(7, sink.posted.last().number)
        notifier.postLocal(NotificationKind.Message, "alice", peer, otherChat, badge = 4)
        assertEquals(4, sink.posted.last().number)
    }

    /** §5.12.10: worded and counted like a push; requests without a chat thread by requester. */
    @Test
    fun localPostsAreWordedLikePushes() {
        notifier.postLocal(NotificationKind.Message, null, peer, chat, badge = 0)
        notifier.postLocal(NotificationKind.Message, null, peer, chat, badge = 0)
        val shown = sink.showing(chat.toString(), SystemNotifier.ID_MESSAGE)!!
        assertEquals("Shroud", shown.title)
        assertEquals("2 new messages", shown.body)
        notifier.postLocal(NotificationKind.ContactRequest, "erin", peer, null, badge = 0)
        assertEquals("erin", sink.showing("contacts:$peer", SystemNotifier.ID_CONTACT_REQUEST)!!.title)
        notifier.postLocal(NotificationKind.Message, null, peer, null, badge = 0)
        assertEquals("New message", sink.showing("shroud", SystemNotifier.ID_MESSAGE)!!.body)
    }

    /** Without the permission (or blocked in Settings) nothing is posted (§5.7.1). */
    @Test
    fun nothingPostsWhileNotificationsAreOff() {
        sink.enabled = false
        notifier.post(push(NotificationKind.Message), "alice")
        notifier.postLocal(NotificationKind.Reaction, "alice", peer, chat, 0)
        assertTrue(sink.posted.isEmpty())
    }

    /** A running wipe posts nothing new; cancelling still works. */
    @Test
    fun aWipePostsNothing() {
        notifier.post(push(NotificationKind.Message), "alice")
        seal.seal()
        notifier.post(push(NotificationKind.Message, conversation = otherChat), "bob")
        notifier.cancelAll()
        assertTrue(sink.shade.isEmpty())
        assertEquals(1, sink.posted.size)
    }

    /** web-parity §5.3: after a removal wipe one neutral notice replaces everything. */
    @Test
    fun theSignedOutNotice() {
        notifier.post(push(NotificationKind.Message), "alice")
        seal.seal()
        notifier.postSignedOutNotice()
        val notice = sink.shade.values.single()
        assertEquals("Shroud", notice.title)
        assertEquals("This phone was signed out.", notice.body)
        assertNull(notice.kind)
        assertNull(notice.peerUserId)
        notifier.postSignedOutNotice("tablet")
        assertEquals("This tablet was signed out.", sink.shade.values.single().body)
    }

    @Test
    fun theChannelsFollowThePreferences() {
        prefs = prefs.copy(sound = NotificationSound.Chime, badge = false)
        notifier.post(push(NotificationKind.Message), "alice")
        assertEquals("messages.chime.n", sink.posted.last().channelId)
        notifier.post(push(NotificationKind.MissedCall, conversation = null, callId = call), null)
        assertEquals("calls.missed.chime.n", sink.posted.last().channelId)
    }

    /** web-parity §7.6: the requests list on screen closes every request's notification. */
    @Test
    fun theRequestsListClosesRequests() {
        val other = UUID.fromString("00000000-0000-0000-0000-00000000000c")
        notifier.postLocal(NotificationKind.ContactRequest, "carol", peer, null, 0)
        notifier.postLocal(NotificationKind.ContactRequest, "dan", other, null, 0)
        notifier.postLocal(NotificationKind.Message, "alice", peer, chat, 0)
        notifier.cancelContactRequests()
        assertEquals(setOf(chat.toString() to SystemNotifier.ID_MESSAGE), sink.shade.keys)
    }

    /** web-parity §7.6: the first list after an unlock closes chats read elsewhere meanwhile. */
    @Test
    fun theFirstListClosesSettledChats() {
        notifier.post(push(NotificationKind.Message), "alice")
        notifier.post(push(NotificationKind.Reaction), "alice")
        notifier.post(push(NotificationKind.Message, conversation = otherChat), "bob")
        notifier.post(push(NotificationKind.Reaction, conversation = otherChat), "bob")
        notifier.closeSettledChats(readChats = listOf(chat), reactionsSeenChats = listOf(otherChat))
        assertEquals(
            setOf("$chat:reaction" to SystemNotifier.ID_REACTION, otherChat.toString() to SystemNotifier.ID_MESSAGE),
            sink.shade.keys,
        )
    }

    @Test
    fun messageBodies() {
        assertEquals("New message", SystemNotifier.messageBody(1))
        assertEquals("2 new messages", SystemNotifier.messageBody(2))
        assertEquals("10 new messages", SystemNotifier.messageBody(10))
    }
}
