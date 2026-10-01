package de.corespace.shroud.core.notifications

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The eight kinds, their wire strings and lines (notifications-push §2; iOS `NotificationPayload.Kind`
 * and `body(for:)`, `ios/ShroudShared/NotificationPayload.swift:22-32, 93-104`; server
 * `fallback_body`, `push/payload.rs:48-59`; web `sw.js:13-22`).
 */
class NotificationKindTest {
    /** `NotificationPayloadTests.testBodiesForEveryKind` (`NotificationPayloadTests.swift:88-94`), all eight lines. */
    @Test
    fun bodiesForEveryKind() {
        assertEquals("New message", NotificationKind.Message.bodyLine)
        assertEquals("Reacted to your message", NotificationKind.Reaction.bodyLine)
        assertEquals("Wants to add you as a contact", NotificationKind.ContactRequest.bodyLine)
        assertEquals("Incoming call", NotificationKind.Call.bodyLine)
        assertEquals("Incoming video call", NotificationKind.VideoCall.bodyLine)
        assertEquals("Missed call", NotificationKind.MissedCall.bodyLine)
        assertEquals("Call ended", NotificationKind.CallEnded.bodyLine)
        assertEquals("Notifications are working", NotificationKind.Test.bodyLine)
    }

    @Test
    fun wireStringsMatchTheServer() {
        assertEquals(
            listOf("message", "reaction", "contact_request", "call", "video_call", "missed_call", "call_ended", "test"),
            NotificationKind.entries.map { it.wire },
        )
        for (kind in NotificationKind.entries) assertEquals(kind, NotificationKind.fromWire(kind.wire))
    }

    /** `testParsesTheAppPart`: an unknown kind is not one of ours; `video_call` is a video call. */
    @Test
    fun unknownKindsReadAsNull() {
        assertNull(NotificationKind.fromWire("future_kind"))
        assertNull(NotificationKind.fromWire("read"))
        assertNull(NotificationKind.fromWire("Message"))
        assertEquals(NotificationKind.VideoCall, NotificationKind.fromWire("video_call"))
    }

    @Test
    fun callKindsGoToTheCallPath() {
        assertEquals(
            setOf(NotificationKind.Call, NotificationKind.VideoCall, NotificationKind.MissedCall, NotificationKind.CallEnded),
            NotificationKind.entries.filter { it.isCall }.toSet(),
        )
        assertFalse(NotificationKind.Test.isCall)
        assertTrue(NotificationKind.MissedCall.isCall)
    }
}
