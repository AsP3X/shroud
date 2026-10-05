package de.corespace.shroud.core.messaging

import de.corespace.shroud.core.model.ChatMessage
import de.corespace.shroud.core.model.ChatMessageKind
import de.corespace.shroud.core.model.NOTES_PEER_ID
import de.corespace.shroud.core.net.PresenceDto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Instant
import java.time.ZoneId
import java.util.Locale
import java.util.UUID

/** `ChatListFormatting.swift` strings (messaging-core §21, §18, Appendix A). */
class ChatListFormattingTest {
    private val peer = UUID.randomUUID()
    private val zone: ZoneId = ZoneId.of("Europe/Berlin")

    /** Thursday 2026-09-24 14:05 in Berlin. */
    private val now: Instant = Instant.parse("2026-09-24T12:05:00Z")

    private fun last(text: String, kind: ChatMessageKind = ChatMessageKind.Text, deleted: Boolean = false, transcript: String? = null, done: Boolean? = null) =
        mapOf(peer to listOf(ChatMessage(UUID.randomUUID(), peer, peer, text, now, isMine = false, deleted = deleted, kind = kind, transcript = transcript, todoDone = done)))

    private fun preview(threads: Map<UUID, List<ChatMessage>>, isNotes: Boolean = false) = ChatListFormatting.preview(peer, threads, isNotes)

    @Test
    fun aFileSaysItsCaptionElseItsName() { // docs/file-sharing.md §7 "Elsewhere"
        val file = ChatMessage(UUID.randomUUID(), peer, peer, "", now, isMine = false, kind = ChatMessageKind.File, fileName = "report.pdf")
        assertEquals("report.pdf", preview(mapOf(peer to listOf(file))))
        assertEquals("Q3 numbers", preview(mapOf(peer to listOf(file.copy(text = "Q3 numbers")))))
        assertEquals("Message deleted", preview(mapOf(peer to listOf(file.copy(deleted = true)))))
    }

    @Test
    fun previewForEveryKind() { // ChatListFormatting.swift:5-29
        assertEquals("hello", preview(last("hello")))
        assertEquals("Message deleted", preview(last("hello", deleted = true)))
        assertEquals("Message deleted", preview(last("Photo", kind = ChatMessageKind.Image, deleted = true)))
        assertEquals("Photo", preview(last("Photo", kind = ChatMessageKind.Image)))
        assertEquals("Photo", preview(last("", kind = ChatMessageKind.Image)))
        assertEquals("At the lake", preview(last("At the lake", kind = ChatMessageKind.Image)))
        assertEquals("Video", preview(last("", kind = ChatMessageKind.Video)))
        assertEquals("Video", preview(last("Video", kind = ChatMessageKind.Video)))
        assertEquals("Trailer", preview(last("Trailer", kind = ChatMessageKind.Video)))
        assertEquals("Voice message", preview(last("Voice message", kind = ChatMessageKind.Voice)))
        assertEquals("Voice message", preview(last("Voice message", kind = ChatMessageKind.Voice, transcript = "")))
        assertEquals("see you", preview(last("Voice message", kind = ChatMessageKind.Voice, transcript = "see you")))
        assertEquals("✓ Buy milk", preview(last("Buy milk", kind = ChatMessageKind.Todo, done = true)))
        assertEquals("○ Buy milk", preview(last("Buy milk", kind = ChatMessageKind.Todo, done = false)))
        assertEquals("○ Buy milk", preview(last("Buy milk", kind = ChatMessageKind.Todo)))
    }

    @Test
    fun emptyChats() {
        assertEquals("Encrypted conversation", preview(emptyMap()))
        assertEquals("Encrypted conversation", preview(mapOf(peer to emptyList())))
        assertEquals("Personal notes, photos & todos", ChatListFormatting.preview(NOTES_PEER_ID, emptyMap(), isNotes = true))
    }

    @Test
    fun timeLabels() {
        val us = Locale.US
        assertEquals("", ChatListFormatting.timeLabel(null, now, zone, us, is24h = true))
        assertEquals("14:05", ChatListFormatting.timeLabel(now, now, zone, us, is24h = true))
        assertEquals("2:05 PM".replace(' ', ' '), ChatListFormatting.timeLabel(now, now, zone, us, is24h = false).replace(' ', ' '))
        // Yesterday by the calendar, even when less than a day ago.
        assertEquals("Yesterday", ChatListFormatting.timeLabel(Instant.parse("2026-09-23T21:59:00Z"), now, zone, us, is24h = true))
        // Midnight in Berlin is already today.
        assertEquals("00:00", ChatListFormatting.timeLabel(Instant.parse("2026-09-23T22:00:00Z"), now, zone, us, is24h = true))
        assertEquals("Sep 22, 2026", ChatListFormatting.timeLabel(Instant.parse("2026-09-22T10:00:00Z"), now, zone, us, is24h = true))
        assertEquals("22 Sept 2026".replace("Sept", "Sep"), ChatListFormatting.timeLabel(Instant.parse("2026-09-22T10:00:00Z"), now, zone, Locale.UK, is24h = true).replace("Sept", "Sep"))
        assertEquals("09:41", ChatListFormatting.clockTimeLabel(Instant.parse("2026-09-24T07:41:00Z"), zone, us, is24h = true))
        assertEquals("", ChatListFormatting.clockTimeLabel(null, zone, us, is24h = true))
    }

    @Test
    fun presenceLabels() { // ChatListFormatting.swift:48-66
        val us = Locale.US
        fun label(p: PresenceDto?) = ChatListFormatting.presenceLabel(p, now, zone, us, is24h = true)
        assertNull(label(null))
        assertEquals("online", label(PresenceDto(peer, online = true)))
        assertEquals("offline", label(PresenceDto(peer, online = false)))
        assertEquals("last seen 09:41", label(PresenceDto(peer, online = false, lastSeenAt = Instant.parse("2026-09-24T07:41:00Z"))))
        assertEquals("last seen yesterday", label(PresenceDto(peer, online = false, lastSeenAt = Instant.parse("2026-09-23T07:41:00Z"))))
        assertEquals("last seen Sep 1, 2026", label(PresenceDto(peer, online = false, lastSeenAt = Instant.parse("2026-09-01T07:41:00Z"))))
    }
}
