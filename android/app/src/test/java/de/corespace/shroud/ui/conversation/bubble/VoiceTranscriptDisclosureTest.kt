package de.corespace.shroud.ui.conversation.bubble

import de.corespace.shroud.core.model.ChatMessage
import de.corespace.shroud.core.model.ChatMessageKind
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.Instant
import java.util.UUID

/**
 * Port of `ios/shroudTests/VoiceTranscriptDisclosureTests.swift` (7 cases; conversation-thread §20.5).
 * The automatic unfold rule is shared with the web client (`transcriptView.ts`).
 */
class VoiceTranscriptDisclosureTest {
    private val peer = UUID.randomUUID()

    private fun voice(deleted: Boolean = false) = ChatMessage(
        id = UUID.randomUUID(),
        peerUserId = peer,
        senderUserId = peer,
        text = "Voice message",
        createdAt = Instant.now(),
        isMine = false,
        deleted = deleted,
        kind = ChatMessageKind.Voice,
    )

    private fun text() = ChatMessage(
        id = UUID.randomUUID(),
        peerUserId = peer,
        senderUserId = peer,
        text = "hello",
        createdAt = Instant.now(),
        isMine = false,
        deleted = false,
        kind = ChatMessageKind.Text,
    )

    @Before
    fun setUp() = VoiceTranscriptDisclosure.reset()

    @After
    fun tearDown() = VoiceTranscriptDisclosure.reset()

    @Test
    fun tailIsTheNewestContiguousVoiceNotes() {
        val older = voice()
        val newer = voice()
        assertEquals(listOf(newer.id, older.id), VoiceTranscriptDisclosure.tail(listOf(older, newer)))
    }

    @Test
    fun tailStopsAtATextMessage() {
        val voiceNote = voice()
        assertTrue(VoiceTranscriptDisclosure.tail(listOf(voiceNote, text())).isEmpty())
    }

    @Test
    fun tailStopsAtADeletedVoiceNote() {
        val live = voice()
        assertTrue(VoiceTranscriptDisclosure.tail(listOf(live, voice(deleted = true))).isEmpty())
    }

    @Test
    fun tailCapsAtTwo() {
        val notes = listOf(voice(), voice(), voice())
        assertEquals(listOf(notes[2].id, notes[1].id), VoiceTranscriptDisclosure.tail(notes))
    }

    @Test
    fun shortTranscriptsOpenUnasked() {
        assertTrue(VoiceTranscriptDisclosure.opensUnasked(transcript = "Hi", durationMs = 1_000, isWorking = false))
        val long = "a".repeat(VoiceTranscriptDisclosure.AUTO_OPEN_MAX_CHARACTERS + 1)
        assertFalse(VoiceTranscriptDisclosure.opensUnasked(transcript = long, durationMs = 1_000, isWorking = false))
    }

    @Test
    fun aShortNoteStillTranscribingOpensUnasked() {
        assertTrue(VoiceTranscriptDisclosure.opensUnasked(transcript = null, durationMs = 5_000, isWorking = true))
        assertFalse(VoiceTranscriptDisclosure.opensUnasked(transcript = null, durationMs = 5_000, isWorking = false))
        assertFalse(
            VoiceTranscriptDisclosure.opensUnasked(
                transcript = null,
                durationMs = VoiceTranscriptDisclosure.AUTO_OPEN_MAX_WORKING_MS + 1,
                isWorking = true,
            ),
        )
    }

    @Test
    fun handOffCarriesTheReadersChoice() {
        val old = UUID.randomUUID()
        val new = UUID.randomUUID()
        VoiceTranscriptDisclosure.setOpen(false, old)
        VoiceTranscriptDisclosure.handOff(old, new)
        assertNull(VoiceTranscriptDisclosure.choice(old))
        assertEquals(false, VoiceTranscriptDisclosure.choice(new))
        assertTrue(VoiceTranscriptDisclosure.wasHandedOff(new))
    }

    // ---- Android additions (no iOS counterpart) ----

    /** Characters are counted by grapheme, as Swift counts them: 400 emoji are 400 characters. */
    @Test
    fun theLengthLimitCountsGraphemesNotUtf16Units() {
        val emoji = "👍".repeat(VoiceTranscriptDisclosure.AUTO_OPEN_MAX_CHARACTERS)
        assertTrue(VoiceTranscriptDisclosure.opensUnasked(transcript = emoji, durationMs = 1_000, isWorking = false))
    }

    /** A note lands once per session, and a hand-off carries the pin to the server id. */
    @Test
    fun landingIsPinnedOncePerNoteAndFollowsAHandOff() {
        val old = UUID.randomUUID()
        val new = UUID.randomUUID()
        assertFalse(VoiceTranscriptDisclosure.hasLanded(old))
        VoiceTranscriptDisclosure.markLanded(old)
        VoiceTranscriptDisclosure.handOff(old, new)
        assertFalse(VoiceTranscriptDisclosure.hasLanded(old))
        assertTrue(VoiceTranscriptDisclosure.hasLanded(new))
    }

    /** Purged notes leave no ids behind (`MessageArtifactSinks.onPurged`). */
    @Test
    fun forgettingPurgedNotesDropsTheirState() {
        val id = UUID.randomUUID()
        VoiceTranscriptDisclosure.setOpen(true, id)
        VoiceTranscriptDisclosure.markLanded(id)
        VoiceTranscriptDisclosure.forget(listOf(id))
        assertNull(VoiceTranscriptDisclosure.choice(id))
        assertFalse(VoiceTranscriptDisclosure.hasLanded(id))
    }
}
