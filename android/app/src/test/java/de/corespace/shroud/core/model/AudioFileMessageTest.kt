package de.corespace.shroud.core.model

import de.corespace.shroud.core.messaging.ChatListFormatting
import de.corespace.shroud.core.messaging.ThreadMessageMerge
import de.corespace.shroud.core.net.wire.Icu4jTextUnitsRule
import de.corespace.shroud.core.net.wire.MessageReplyReference
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.time.Instant
import java.util.UUID

/**
 * An audio file elsewhere (docs/file-sharing.md §1, §7 "Elsewhere", §11.2): the chat list and the
 * notification say `🎵 {display title}`, a reply quotes it as `k: "audio"` with the display title,
 * and merges keep its tags.
 */
class AudioFileMessageTest {
    @get:Rule
    val textUnits = Icu4jTextUnitsRule()

    private val peer = UUID.randomUUID()

    private fun audio(name: String = "track01.mp3", caption: String = "", title: String? = "Midnight City", artist: String? = "M83") = ChatMessage(
        id = UUID.randomUUID(),
        peerUserId = peer,
        senderUserId = peer,
        text = caption,
        createdAt = Instant.parse("2026-10-06T10:00:00Z"),
        isMine = false,
        kind = ChatMessageKind.File,
        mediaObjectId = UUID.randomUUID(),
        fileName = name,
        audioTitle = title,
        audioArtist = artist,
        durationMs = 243_400,
    )

    @Test
    fun anAudioFileIsKnownByItsExtension() {
        assertTrue(audio().isAudioFile)
        assertTrue(audio(name = "Interview.FLAC").isAudioFile)
        assertFalse(audio(name = "report.pdf").isAudioFile)
        assertFalse(audio().copy(kind = ChatMessageKind.Voice).isAudioFile)
    }

    @Test
    fun theChatListAndNotificationSayTheCaptionElseTheNoteAndTheDisplayTitle() {
        assertEquals("🎵 Midnight City – M83", audio().previewText)
        assertEquals("🎵 Midnight City", audio(artist = null).previewText)
        assertEquals("🎵 track01.mp3", audio(title = null).previewText)
        assertEquals("for the drive", audio(caption = " for the drive ").previewText)
        assertEquals("report.pdf", audio(name = "report.pdf").previewText)
        val list = ChatListFormatting.preview(peer, mapOf(peer to listOf(audio())), isNotes = false)
        assertEquals("🎵 Midnight City – M83", list)
    }

    @Test
    fun aReplyQuotesTheDisplayTitleAsAudio() {
        val reference = audio().replyReference!!
        assertEquals(MessageReplyReference.Kind.Audio, reference.kind)
        assertEquals("Midnight City – M83", reference.snippet)
        assertEquals(JsonPrimitive("audio"), reference.wireObject()["k"])
        assertEquals("Audio", MessageReplyReference.Kind.Audio.mediaLabel)
        assertEquals(MessageReplyReference.Kind.Audio, MessageReplyReference.Kind.fromWire("audio"))
        val parsed = MessageReplyReference.parse(reference.wireObject())!!
        assertEquals(reference, parsed)
        // Any other file keeps quoting its name as a file.
        val pdf = audio(name = "report.pdf").replyReference!!
        assertEquals(MessageReplyReference.Kind.File, pdf.kind)
        assertEquals("report.pdf", pdf.snippet)
    }

    @Test
    fun mergesKeepTheTagsAndTheLength() {
        val prior = audio()
        val decoded = prior.copy(audioTitle = null, audioArtist = null, durationMs = null)
        val merged = ThreadMessageMerge.preferReadable(decoded, prior)
        assertEquals("Midnight City", merged.audioTitle)
        assertEquals("M83", merged.audioArtist)
        assertEquals(243_400, merged.durationMs)
        val fresh = prior.copy(audioTitle = "New title")
        assertEquals("New title", ThreadMessageMerge.preferReadable(fresh, prior).audioTitle)
    }
}
