package de.corespace.shroud.core.notifications

import de.corespace.shroud.core.net.wire.Icu4jTextUnitsRule
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.util.UUID

/**
 * The decrypted Web Push payload (notifications-push §3.4, §5.8.1 `fromWebPushJson`; server
 * `push/payload.rs` `web`, `web_read`, `web_device_removed` and their tests `:425-498`), read with
 * iOS's rules (`NotificationPayload.parse`, `ios/ShroudShared/NotificationPayload.swift:47-62`;
 * `NotificationPayloadTests.testParsesTheAppPart`, `NotificationPayloadTests.swift:45-68`).
 */
class PushContentsTest {
    @get:Rule val icu = Icu4jTextUnitsRule()

    private val conversation = "6f9619ff-8b86-4d01-b42d-00c04fc964ff"
    private val peer = "5f0c3a52-7b1e-4c6d-9a8b-2e4f6d8c0a1b"
    private val message = "0b1c2d3e-4f50-4617-8293-a4b5c6d7e8f9"

    private fun json(text: String): JsonObject = Json.parseToJsonElement(text).jsonObject

    private fun parse(text: String): PushContents? = PushContents.fromWebPushJson(json(text))

    @Test
    fun parsesAMessage() {
        val contents = parse(
            """{"v":1,"kind":"message","tag":"$conversation","silent":false,"conversation_id":"$conversation",
               "peer_user_id":"$peer","message_id":"$message","sender":"alice","badge":3}""",
        )!!
        assertEquals(NotificationKind.Message, contents.kind)
        assertEquals("message", contents.rawKind)
        assertEquals(conversation, contents.thread)
        assertEquals(UUID.fromString(conversation), contents.conversationId)
        assertEquals(UUID.fromString(peer), contents.peerUserId)
        assertEquals(UUID.fromString(message), contents.messageId)
        assertNull(contents.callId)
        assertEquals("alice", contents.plainName)
        assertEquals(3, contents.badge)
        assertFalse(contents.isRead)
    }

    /** The bytes as `WebPushDecryptor` (W3-PUSH) hands them over. */
    @Test
    fun parsesTheDecryptedBytes() {
        val bytes = """{"v":1,"kind":"test","tag":"test","silent":true}""".toByteArray()
        val contents = PushContents.fromWebPushJson(bytes)!!
        assertEquals(NotificationKind.Test, contents.kind)
        assertEquals("test", contents.thread)
        assertNull(contents.plainName)
        assertNull(contents.badge)
        assertNull(PushContents.fromWebPushJson("not json".toByteArray()))
        assertNull(PushContents.fromWebPushJson(byteArrayOf(0xC3.toByte(), 0x28)))
        assertNull(PushContents.fromWebPushJson("[1,2]".toByteArray()))
    }

    /** `testParsesTheAppPart`: no kind → not one of ours; an unknown kind is kept for what it is. */
    @Test
    fun missingOrUnknownKinds() {
        assertNull(parse("""{"v":1}"""))
        assertNull(parse("""{"v":1,"kind":5}"""))
        assertNull(parse("""{"v":1,"kind":null}"""))
        val future = parse("""{"v":1,"kind":"future_kind","conversation_id":"$conversation"}""")!!
        assertNull(future.kind)
        assertEquals("future_kind", future.rawKind)
        assertEquals(conversation, future.thread)
        assertEquals(NotificationKind.VideoCall, parse("""{"kind":"video_call"}""")!!.kind)
    }

    /** The server's own read payload (`a_read_names_the_chat_and_nothing_else`, `payload.rs:458-472`; P11d). */
    @Test
    fun aReadClosesAChat() {
        val read = parse(
            """{"v":1,"kind":"read","tag":"00000000-0000-0000-0000-000000000123","conversation_id":"00000000-0000-0000-0000-000000000123","badge":3}""",
        )!!
        assertTrue(read.isRead)
        assertNull(read.kind)
        assertEquals(UUID.fromString("00000000-0000-0000-0000-000000000123"), read.conversationId)
        assertEquals(3, read.badge)
        assertNull(parse("""{"v":1,"kind":"read","conversation_id":"$conversation"}""")!!.badge)
    }

    /** `{"v":1,"kind":"device_removed"}` is the removal wake, not a notification (settings-lock §14.6). */
    @Test
    fun theRemovalWakeIsNotANotification() {
        val removed = json("""{"v":1,"kind":"device_removed"}""")
        assertNull(PushContents.fromWebPushJson(removed))
        assertTrue(PushContents.isDeviceRemoval(removed))
        val withReason = json("""{"v":1,"kind":"device_removed","reason":"account_deleted"}""")
        assertNull(PushContents.fromWebPushJson(withReason))
        assertTrue(PushContents.isDeviceRemoval(withReason))
        assertFalse(PushContents.isDeviceRemoval(json("""{"v":1,"kind":"message"}""")))
        assertFalse(PushContents.isDeviceRemoval(json("""{"type":"message"}""")))
    }

    /** `a_call_end_is_the_same_web_shape_as_its_ring` (`payload.rs:474-494`). */
    @Test
    fun aCallEnd() {
        val ended = parse(
            """{"v":1,"kind":"call_ended","tag":"calls","silent":false,"peer_user_id":"00000000-0000-0000-0000-000000000020",
               "call_id":"00000000-0000-0000-0000-00000000001f","sender":"erin"}""",
        )!!
        assertEquals(NotificationKind.CallEnded, ended.kind)
        assertEquals("calls", ended.thread)
        assertEquals(UUID.fromString("00000000-0000-0000-0000-00000000001f"), ended.callId)
        assertEquals(UUID.fromString("00000000-0000-0000-0000-000000000020"), ended.peerUserId)
        assertEquals("erin", ended.plainName)
    }

    /** `Notification::thread` (`payload.rs:80-95`): requests, calls and the test share one thread each. */
    @Test
    fun threads() {
        val conv = UUID.fromString(conversation)
        assertEquals("contacts", PushContents.threadFor(NotificationKind.ContactRequest, conv))
        for (kind in listOf(NotificationKind.Call, NotificationKind.VideoCall, NotificationKind.MissedCall, NotificationKind.CallEnded)) {
            assertEquals("calls", PushContents.threadFor(kind, conv))
        }
        assertEquals("test", PushContents.threadFor(NotificationKind.Test, null))
        assertEquals(conversation, PushContents.threadFor(NotificationKind.Reaction, conv))
        assertEquals("shroud", PushContents.threadFor(NotificationKind.Message, null))
        assertEquals("shroud", PushContents.threadFor(null, null))
    }

    /**
     * Ids read like Swift's `UUID(uuidString:)` (notifications-push §5.8.1): `1-1-1-1-1` would pass
     * `UUID.fromString`; upper case is fine and comes out lower-case; a bad id drops only itself.
     */
    @Test
    fun idsAreReadStrictly() {
        val contents = parse(
            """{"kind":"message","conversation_id":"1-1-1-1-1","peer_user_id":"5F0C3A52-7B1E-4C6D-9A8B-2E4F6D8C0A1B",
               "message_id":" $message","call_id":42}""",
        )!!
        assertNull(contents.conversationId)
        assertEquals("shroud", contents.thread)
        assertEquals(UUID.fromString(peer), contents.peerUserId)
        assertEquals(peer, contents.peerUserId.toString())
        assertNull(contents.messageId)
        assertNull(contents.callId)
    }

    @Test
    fun badges() {
        assertEquals(0, parse("""{"kind":"message","badge":0}""")!!.badge)
        assertEquals(999, parse("""{"kind":"message","badge":999}""")!!.badge)
        assertNull(parse("""{"kind":"message","badge":"3"}""")!!.badge)
        assertNull(parse("""{"kind":"message","badge":-1}""")!!.badge)
        assertNull(parse("""{"kind":"message","badge":null}""")!!.badge)
    }

    /**
     * The sender is cut like a sealed name (`NotificationPayload.swift:84-85`; notifications-push §3.1
     * vectors B, C and D): trimmed, empty → none, the first 64 grapheme clusters.
     */
    @Test
    fun senderNamesAreClamped() {
        assertEquals("zoë 👩‍💻", parse("""{"kind":"contact_request","sender":"  zoë 👩‍💻  "}""")!!.plainName)
        assertNull(parse("""{"kind":"message","sender":"   "}""")!!.plainName)
        assertNull(parse("""{"kind":"message","sender":"\n\t"}""")!!.plainName)
        assertNull(parse("""{"kind":"message","sender":5}""")!!.plainName)
        val seventy = "👍🏽".repeat(70)
        val cut = PushContents.clampName(seventy)!!
        assertEquals("👍🏽".repeat(64), cut)
        assertEquals(256, cut.length)
        assertEquals("x".repeat(64), PushContents.clampName("x".repeat(80)))
        assertEquals("bob", PushContents.clampName("bob"))
    }
}
