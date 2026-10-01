package de.corespace.shroud.core.messaging

import de.corespace.shroud.core.model.ChatMessage
import de.corespace.shroud.core.model.ChatMessageKind
import de.corespace.shroud.core.model.NOTES_PEER_ID
import de.corespace.shroud.core.model.ReceiptStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.util.UUID

/** `NotesLocalTests` (`ios/shroudTests/LocalMessageStoreTests.swift:149-179`) and `notesMessageFromServer` (`MessagingController.swift:1531-1580`). */
class NotesLocalTest {
    @Test
    fun testTodoWireFormatRoundTrip() { // :150-158
        val wire = NotesLocal.syncedTodoPlaintext("Buy milk", done = false)
        assertEquals("[todo:0]Buy milk", wire)
        assertEquals("Buy milk" to false, NotesLocal.parseSyncedTodo(wire))
        assertEquals("[todo:1]Buy milk", NotesLocal.syncedTodoPlaintext("Buy milk", done = true))
        assertEquals(true, NotesLocal.parseSyncedTodo(NotesLocal.syncedTodoPlaintext("Buy milk", done = true))?.second)
        assertNull(NotesLocal.parseSyncedTodo("plain note"))
        assertNull(NotesLocal.parseSyncedTodo("[todo:0 no close"))
        // Anything but "1" reads as not done; the body keeps its brackets.
        assertEquals("a]b" to false, NotesLocal.parseSyncedTodo("[todo:x]a]b"))
    }

    @Test
    fun testToggleTodo() { // :160-170
        val note = NotesLocal.makeNote("Buy milk", ChatMessageKind.Todo, UUID.randomUUID(), todoDone = false)
        val toggled = NotesLocal.toggleTodo(note.id, listOf(note))
        assertEquals(true, toggled?.first()?.todoDone)
        assertEquals(false, NotesLocal.toggleTodo(note.id, toggled.orEmpty())?.first()?.todoDone)
        // Only todos toggle.
        val text = NotesLocal.makeNote("x", ChatMessageKind.Text, UUID.randomUUID())
        assertNull(NotesLocal.toggleTodo(text.id, listOf(text)))
    }

    @Test
    fun testDeleteNote() { // :172-178
        val a = NotesLocal.makeNote("a", ChatMessageKind.Text, UUID.randomUUID())
        val b = NotesLocal.makeNote("b", ChatMessageKind.Text, UUID.randomUUID())
        val (messages, removed) = NotesLocal.delete(a.id, listOf(a, b))
        assertTrue(removed)
        assertEquals(listOf(b.id), messages.map { it.id })
        assertFalse(NotesLocal.delete(UUID.randomUUID(), listOf(b)).second)
    }

    @Test
    fun aNoteIsOursSentAndUnderTheSentinel() {
        val me = UUID.randomUUID()
        val note = NotesLocal.makeNote("x", ChatMessageKind.Text, me)
        assertEquals(NOTES_PEER_ID, note.peerUserId)
        assertEquals(me, note.senderUserId)
        assertTrue(note.isMine)
        assertEquals(ReceiptStatus.Sent, note.receipt)
        assertFalse(note.pendingSync)
        assertTrue(NotesLocal.isNotes(NOTES_PEER_ID))
        assertFalse(NotesLocal.isNotes(me))
    }

    @Test
    fun aSyncedTodoComesBackAsATodo() {
        val me = UUID.randomUUID()
        val server = ChatMessage(
            UUID.randomUUID(), me, me, "[todo:1]Buy milk", Instant.now(), "2026-09-24T12:00:00.123456Z",
            isMine = true, receipt = ReceiptStatus.Delivered,
        )
        val todo = NotesLocal.fromServer(server)
        assertEquals(ChatMessageKind.Todo, todo.kind)
        assertEquals("Buy milk", todo.text)
        assertEquals(true, todo.todoDone)
        assertEquals(NOTES_PEER_ID, todo.peerUserId)
        assertEquals(ReceiptStatus.Sent, todo.receipt)
        assertEquals("2026-09-24T12:00:00.123456Z", todo.createdAtWire)
    }

    @Test
    fun aTextNoteMovesUnderTheSentinel() {
        val me = UUID.randomUUID()
        val server = ChatMessage(UUID.randomUUID(), me, me, "hello", Instant.now(), isMine = true, receipt = ReceiptStatus.Read)
        val note = NotesLocal.fromServer(server)
        assertEquals(NOTES_PEER_ID, note.peerUserId)
        assertEquals(ReceiptStatus.Sent, note.receipt)
        assertEquals("hello", note.text)
        // Already there: unchanged.
        assertSame(note, NotesLocal.fromServer(note))
    }

    @Test
    fun mediaNotesComeBackAsTheyAre() {
        val photo = ChatMessage(UUID.randomUUID(), NOTES_PEER_ID, UUID.randomUUID(), "Photo", Instant.now(), isMine = true, kind = ChatMessageKind.Image)
        assertSame(photo, NotesLocal.fromServer(photo))
    }
}
