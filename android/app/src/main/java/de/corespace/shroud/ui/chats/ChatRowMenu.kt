package de.corespace.shroud.ui.chats

import androidx.compose.ui.graphics.vector.ImageVector
import de.corespace.shroud.core.model.MuteDuration
import de.corespace.shroud.ui.components.MenuAction
import de.corespace.shroud.ui.theme.ShroudIcons

/**
 * The items of a chat row's long-press menu, in order (`ChatsView.swift:98-105, 135-144, 229-286`;
 * shell-chats §8.7; design `Chats — Chat Menu` AqgbA).
 *
 * | Item | Shown | Glyph (design) |
 * | --- | --- | --- |
 * | [MarkAsRead] | the chat has unread messages (CV:231) | Phosphor `chat-circle-dots` |
 * | [Mute] ▸ | not muted — opens the five durations (CV:245-252) | Phosphor `bell-slash` |
 * | [Unmute] | muted (CV:239-244) | Phosphor `bell` |
 * | [DeleteChat] | every conversation (CV:276-285) | Phosphor `trash`, danger |
 * | [DeleteAllNotes] | the Notes row, alone (CV:99-105) | Phosphor `trash`, danger |
 */
enum class ChatRowMenuItem(val title: String, val destructive: Boolean = false) {
    MarkAsRead("Mark as Read"),
    Mute("Mute"),
    Unmute("Unmute"),
    DeleteChat("Delete Chat", destructive = true),
    DeleteAllNotes("Delete All Notes", destructive = true),
    ;

    /** The design's glyph (AqgbA; iOS `checkmark.message`, `bell.slash`, `bell`, `trash`). */
    val icon: ImageVector
        get() = when (this) {
            MarkAsRead -> ShroudIcons.ChatCircleDots
            Mute -> ShroudIcons.BellSlash
            Unmute -> ShroudIcons.BellRegular
            DeleteChat, DeleteAllNotes -> ShroudIcons.Trash
        }
}

/** What a row's menu offers and what each item does. */
object ChatRowMenu {
    /** TalkBack's name for the long press and the menu pane (shell-chats §8.7). */
    const val OPTIONS_LABEL = "Chat options"

    /** The items for [row], in iOS order. */
    fun items(row: ChatRowModel): List<ChatRowMenuItem> {
        if (row.isNotes) return listOf(ChatRowMenuItem.DeleteAllNotes)
        return buildList {
            if ((row.unreadCount ?: 0) > 0) add(ChatRowMenuItem.MarkAsRead)
            add(if (row.isMuted) ChatRowMenuItem.Unmute else ChatRowMenuItem.Mute)
            add(ChatRowMenuItem.DeleteChat)
        }
    }

    /**
     * The menu card for [row]: "Mute" carries the five durations as a submenu, in `MuteDuration`
     * order with iOS's titles ("For 1 Hour" … "Until I Turn It Back On", NM:124-153); the card
     * cross-fades into it with a "Mute" back row (shell-chats §8.8, D6).
     */
    fun actions(
        row: ChatRowModel,
        onMarkRead: () -> Unit,
        onMute: (MuteDuration) -> Unit,
        onUnmute: () -> Unit,
        onDelete: () -> Unit,
    ): List<MenuAction> = items(row).map { item ->
        when (item) {
            ChatRowMenuItem.MarkAsRead -> MenuAction(item.title, item.icon, onClick = onMarkRead)
            ChatRowMenuItem.Mute -> MenuAction(
                item.title,
                item.icon,
                submenu = MuteDuration.entries.map { duration -> MenuAction(duration.title, onClick = { onMute(duration) }) },
            )
            ChatRowMenuItem.Unmute -> MenuAction(item.title, item.icon, onClick = onUnmute)
            ChatRowMenuItem.DeleteChat, ChatRowMenuItem.DeleteAllNotes ->
                MenuAction(item.title, item.icon, destructive = true, onClick = onDelete)
        }
    }
}
