package de.corespace.shroud.ui.components

import de.corespace.shroud.core.model.ChatPeerActivity
import org.junit.Assert.assertEquals
import org.junit.Test

/** What TalkBack hears and what the unread badge shows (`ChatRowView.swift:123-140`; shell-chats §10.1). */
class ChatRowTest {
    @Test
    fun labelJoinsTitlePreviewTimeAndUnread() {
        assertEquals(
            "Design Team, Nina: Final icons are ready, 12:45, 3 unread",
            ChatRowAccessibility.label("Design Team", "Nina: Final icons are ready", "12:45", 3, false, false, null),
        )
    }

    @Test
    fun activityIsSpokenInsteadOfThePreview() {
        assertEquals(
            "Jane Cooper, recording a voice message",
            ChatRowAccessibility.label("Jane Cooper", "online", null, null, false, false, ChatPeerActivity.Recording),
        )
        assertEquals(
            "Jane Cooper, typing, 9:41",
            ChatRowAccessibility.label("Jane Cooper", "Ok", "9:41", 0, false, false, ChatPeerActivity.Typing),
        )
    }

    @Test
    fun reactionsAndMuteComeLast() {
        assertEquals(
            "Family, Photo, 11:02, 12 unread, new reactions, muted",
            ChatRowAccessibility.label("Family", "Photo", "11:02", 12, true, true, null),
        )
    }

    @Test
    fun noUnreadSaysNothingAboutUnread() {
        assertEquals("Mom, Call me, 9:12", ChatRowAccessibility.label("Mom", "Call me", "9:12", 0, false, false, null))
        assertEquals("Mom, Call me", ChatRowAccessibility.label("Mom", "Call me", null, null, false, false, null))
    }

    @Test
    fun badgeCapsAtNinetyNine() {
        assertEquals("1", ChatRowAccessibility.badgeText(1))
        assertEquals("99", ChatRowAccessibility.badgeText(99))
        assertEquals("99+", ChatRowAccessibility.badgeText(100))
        assertEquals("99+", ChatRowAccessibility.badgeText(12_345))
    }

    @Test
    fun activityWordsMatchIos() {
        // `TypingIndicatorBubble.swift:9-29`.
        assertEquals("typing", ChatPeerActivity.Typing.label)
        assertEquals("recording", ChatPeerActivity.Recording.label)
        assertEquals("typing", ChatPeerActivity.Typing.spokenLabel)
        assertEquals("recording a voice message", ChatPeerActivity.Recording.spokenLabel)
    }
}
