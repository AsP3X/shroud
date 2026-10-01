package de.corespace.shroud.ui.conversation.bubble

import de.corespace.shroud.core.model.ChatMessage
import de.corespace.shroud.core.model.ChatMessageKind
import de.corespace.shroud.core.net.wire.Icu4jTextUnitsRule
import de.corespace.shroud.core.net.wire.MessageReplyReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.time.Instant
import java.util.UUID

/** `ReplyQuoteContent.make` (`ReplyQuoteView.swift:37-118`), published with the W2-INT seam. */
class ReplyQuoteContentTest {
    @get:Rule
    val textUnits = Icu4jTextUnitsRule()

    private val me = UUID.fromString("3f2c8a9e-5b1d-4c7a-9e2f-8d6b4a1c0e7f")
    private val peer = UUID.fromString("9b1deb4d-3b7d-4bad-9bdd-2b0d7b3dcb6d")

    private fun message(kind: ChatMessageKind, text: String, mine: Boolean = false, deleted: Boolean = false) =
        ChatMessage(UUID.randomUUID(), peer, if (mine) me else peer, text, Instant.EPOCH, isMine = mine, deleted = deleted, kind = kind)

    @Test
    fun aMissingOriginalShowsWhatTheSenderSealed() {
        val ref = MessageReplyReference(UUID.randomUUID(), me, MessageReplyReference.Kind.Image, "")
        val quote = ReplyQuoteContent.make(ref, original = null, peerName = "bob", myUserId = me)
        assertEquals("You", quote.author)
        assertEquals("Photo", quote.text)
        assertTrue(quote.isStandIn)

        val text = ReplyQuoteContent.make(MessageReplyReference(UUID.randomUUID(), peer, MessageReplyReference.Kind.Text, " hi "), null, "bob", me)
        assertEquals("bob", text.author)
        assertEquals("hi", text.text)
        assertFalse(text.isStandIn)
        assertNull(text.symbol)
    }

    @Test
    fun aLoadedOriginalWinsAndReadsItsCurrentState() {
        val ref = MessageReplyReference(UUID.randomUUID(), peer, MessageReplyReference.Kind.Text, "old words")
        val deleted = ReplyQuoteContent.make(ref, message(ChatMessageKind.Text, "old words", deleted = true), "bob", me)
        assertEquals("Message deleted", deleted.text)
        assertTrue(deleted.isStandIn)
    }

    @Test
    fun mediaWithoutACaptionAndVoiceNotesAreLabelled() {
        assertEquals("Video", ReplyQuoteContent.make(message(ChatMessageKind.Video, "Media"), "bob", me).text)
        assertEquals("a cat", ReplyQuoteContent.make(message(ChatMessageKind.Image, "a cat"), "bob", me).text)
        // Voice notes are quoted by name, never by transcript.
        val voice = ReplyQuoteContent.make(message(ChatMessageKind.Voice, "secret transcript", mine = true), "bob", me)
        assertEquals("You", voice.author)
        assertEquals("Voice message", voice.text)
        assertTrue(voice.isStandIn)
    }
}
