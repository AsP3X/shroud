package de.corespace.shroud.ui.chats

import de.corespace.shroud.core.model.MuteDuration
import de.corespace.shroud.core.model.NOTES_DISPLAY_NAME
import de.corespace.shroud.core.model.NOTES_PEER_ID
import de.corespace.shroud.core.net.ConversationDeleteScope
import de.corespace.shroud.ui.chats.ChatsFixtures.jane
import de.corespace.shroud.ui.theme.ShroudIcons
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The row menu (`ChatsView.swift:98-105, 135-144, 229-286`; shell-chats §8.7–§8.8) and the delete
 * confirmation's words (`ChatsView.swift:200-217, 288-310`; §8.9).
 */
class ChatRowMenuTest {
    private val row = ChatRowModel(jane, "jane", "Ok", "9:38")
    private val notes = ChatRowModel(NOTES_PEER_ID, NOTES_DISPLAY_NAME, "Photo", null, isNotes = true)

    @Test
    fun notesOfferOnlyDeleteAllNotes() {
        assertEquals(listOf(ChatRowMenuItem.DeleteAllNotes), ChatRowMenu.items(notes))
        // Even with stray facts on the row: Notes never mute, never count unread.
        assertEquals(listOf(ChatRowMenuItem.DeleteAllNotes), ChatRowMenu.items(notes.copy(unreadCount = 3, isMuted = true)))
    }

    @Test
    fun markAsReadOnlyWithUnreadThenMuteOrUnmuteThenDelete() {
        assertEquals(listOf(ChatRowMenuItem.Mute, ChatRowMenuItem.DeleteChat), ChatRowMenu.items(row))
        assertEquals(
            listOf(ChatRowMenuItem.MarkAsRead, ChatRowMenuItem.Mute, ChatRowMenuItem.DeleteChat),
            ChatRowMenu.items(row.copy(unreadCount = 2)),
        )
        assertEquals(listOf(ChatRowMenuItem.Unmute, ChatRowMenuItem.DeleteChat), ChatRowMenu.items(row.copy(isMuted = true)))
    }

    @Test
    fun itemsUseTheDesignsWordsAndGlyphs() {
        assertEquals(
            listOf("Mark as Read", "Mute", "Unmute", "Delete Chat", "Delete All Notes"),
            ChatRowMenuItem.entries.map { it.title },
        )
        assertEquals(ShroudIcons.ChatCircleDots, ChatRowMenuItem.MarkAsRead.icon)
        assertEquals(ShroudIcons.BellSlash, ChatRowMenuItem.Mute.icon)
        assertEquals(ShroudIcons.BellRegular, ChatRowMenuItem.Unmute.icon)
        assertEquals(ShroudIcons.Trash, ChatRowMenuItem.DeleteChat.icon)
        assertEquals(ShroudIcons.Trash, ChatRowMenuItem.DeleteAllNotes.icon)
        assertEquals(listOf(ChatRowMenuItem.DeleteChat, ChatRowMenuItem.DeleteAllNotes), ChatRowMenuItem.entries.filter { it.destructive })
    }

    @Test
    fun muteOpensTheFiveDurationsInIosOrderAndEachActs() {
        val log = ArrayList<String>()
        val actions = ChatRowMenu.actions(
            row.copy(unreadCount = 1),
            onMarkRead = { log += "read" },
            onMute = { log += "mute:${it.name}" },
            onUnmute = { log += "unmute" },
            onDelete = { log += "delete" },
        )
        assertEquals(listOf("Mark as Read", "Mute", "Delete Chat"), actions.map { it.title })
        val mute = actions[1]
        assertNull(mute.submenu?.firstOrNull { it.icon != null })
        assertEquals(
            listOf("For 1 Hour", "For 8 Hours", "For 1 Day", "For 7 Days", "Until I Turn It Back On"),
            mute.submenu!!.map { it.title },
        )
        actions[0].onClick()
        mute.submenu!!.forEach { it.onClick() }
        actions[2].onClick()
        assertEquals(
            listOf("read") + MuteDuration.entries.map { "mute:${it.name}" } + "delete",
            log,
        )
        assertTrue(actions[2].destructive)
    }

    @Test
    fun unmuteActsAtOnce() {
        var unmuted = 0
        val actions = ChatRowMenu.actions(row.copy(isMuted = true), {}, {}, { unmuted++ }, {})
        assertEquals(listOf("Unmute", "Delete Chat"), actions.map { it.title })
        assertNull(actions[0].submenu)
        actions[0].onClick()
        assertEquals(1, unmuted)
    }

    // ---- delete confirmation (CV:200-217, 288-310) ----

    @Test
    fun aChatsConfirmationSpellsOutBothScopes() {
        val pending = PendingChatDelete(jane, "jane", isNotes = false)
        assertEquals("Delete chat with jane?", ChatDeleteCopy.title(pending))
        assertEquals(
            "Deleting for both unsends your messages in jane's chat. Their own messages stay unless they allow " +
                "chats to be cleared for them. They stay in your contacts.",
            ChatDeleteCopy.message(pending),
        )
        assertEquals(
            listOf(
                ChatDeleteCopy.Choice("Delete for me and jane", ConversationDeleteScope.Everyone),
                ChatDeleteCopy.Choice("Delete for me", ConversationDeleteScope.Me),
            ),
            ChatDeleteCopy.choices(pending),
        )
    }

    @Test
    fun notesConfirmationOnlyDeletesForMe() {
        val pending = PendingChatDelete(NOTES_PEER_ID, NOTES_DISPLAY_NAME, isNotes = true)
        assertEquals("Delete all notes?", ChatDeleteCopy.title(pending))
        assertEquals("Removes every note from this device and your account.", ChatDeleteCopy.message(pending))
        assertEquals(listOf(ChatDeleteCopy.Choice("Delete", ConversationDeleteScope.Me)), ChatDeleteCopy.choices(pending))
        assertEquals("Delete chat?", ChatDeleteCopy.title(null))
    }
}
