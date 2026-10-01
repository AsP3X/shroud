package de.corespace.shroud.ui.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import de.corespace.shroud.core.model.ChatPeerActivity
import de.corespace.shroud.ui.theme.Motion
import de.corespace.shroud.ui.theme.ShroudIcons
import de.corespace.shroud.ui.theme.ShroudTheme
import de.corespace.shroud.ui.theme.inter

/**
 * List row for chats and contacts (`ChatRowView.swift:4-141`; shell-chats §10.1; design
 * `Chat Row` `R22zHn`; contacts §5.1.1).
 *
 * Layout: padding h 16 v 10 around the 52 dp [avatar] (72 dp; grows with the font scale, never
 * clips), spacing 12; text column spacing 3. Top line: name 16 SemiBold (+ Phosphor
 * `bell-slash-fill` 12 dp when [muted], `Motion.iconSwap` with `Motion.snappy`), at least 8 dp, time
 * 13. Bottom line: subtitle 14 (`accent` when [subtitleAccent], contacts' "online") cross-fading on
 * change — or [TypingLabel] when [activity] is set —, the 20 dp heart badge for
 * [hasUnseenReactions] and the unread capsule (`99+` cap, `mutedBadge` when muted, digits roll
 * with `Motion.bouncy`). No fill of its own: the press highlight ([highlightRow]) and, in two-pane,
 * the [selected] `accentSoft` sit behind it.
 *
 * TalkBack: one node, "title, subtitle-or-activity, time, N unread, new reactions, muted"
 * ([ChatRowAccessibility.label], `ChatRowView.swift:127-140`). [onLongPress] opens the row menu;
 * pass [onLongPressLabel] ("Chat options", shell-chats §8.7) so TalkBack names it.
 */
@Composable
fun ChatRow(
    title: String,
    subtitle: String,
    time: String? = null,
    avatar: @Composable () -> Unit,
    unreadCount: Int? = null,
    muted: Boolean = false,
    hasUnseenReactions: Boolean = false,
    activity: ChatPeerActivity? = null,
    selected: Boolean = false,
    onClick: () -> Unit,
    onLongPress: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
    subtitleAccent: Boolean = false,
    onLongPressLabel: String? = null,
) {
    val colors = ShroudTheme.colors
    val label = ChatRowAccessibility.label(title, subtitle, time, unreadCount, hasUnseenReactions, muted, activity)
    Row(
        modifier
            .fillMaxWidth()
            .then(if (selected) Modifier.background(colors.accentSoft) else Modifier)
            .highlightRow(onClick = onClick, onLongClick = onLongPress, onLongClickLabel = onLongPressLabel)
            .clearAndSetSemantics {
                contentDescription = label
                if (selected) this.selected = true
            }
            .padding(horizontal = 16.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        avatar()
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            TopLine(title, time, muted)
            BottomLine(subtitle, subtitleAccent, activity, hasUnseenReactions, unreadCount, muted)
        }
    }
}

@Composable
private fun TopLine(title: String, time: String?, muted: Boolean) {
    val colors = ShroudTheme.colors
    // `HStack(alignment: .firstTextBaseline)` (`ChatRowView.swift:42-63`): name, bell and time
    // share the name's baseline; the bell sits on it.
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            Modifier.weight(1f).alignByBaseline(),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            ShroudText(
                title,
                inter(16f, FontWeight.SemiBold),
                colors.textPrimary,
                modifier = Modifier.weight(1f, fill = false).alignByBaseline(),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            AnimatedVisibility(
                visible = muted,
                modifier = Modifier.alignBy { it.measuredHeight },
                enter = kitIconSwapIn(Motion.snappy()),
                exit = kitIconSwapOut(Motion.snappy()),
            ) {
                ShroudIcon(ShroudIcons.BellSlashFill, colors.textSecondary, size = 12.dp)
            }
        }
        if (time != null) {
            // iOS `Spacer(minLength: 8)` between the 8-spaced name group and time.
            Spacer(Modifier.width(8.dp))
            ShroudText(time, inter(13f), colors.textSecondary, Modifier.alignByBaseline(), maxLines = 1)
        }
    }
}

@Composable
private fun BottomLine(
    subtitle: String,
    subtitleAccent: Boolean,
    activity: ChatPeerActivity?,
    hasUnseenReactions: Boolean,
    unreadCount: Int?,
    muted: Boolean,
) {
    val colors = ShroudTheme.colors
    val subtitleColor by animateColorAsState(
        if (subtitleAccent) colors.accent else colors.textSecondary,
        Motion.snappy(),
        label = "subtitleColor",
    )
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        // Activity replaces the preview in place instead of hard-cutting (`ChatRowView.swift:66-83`).
        AnimatedContent(
            targetState = activity,
            transitionSpec = { fadeIn(Motion.fade()) togetherWith fadeOut(Motion.fade()) },
            contentAlignment = Alignment.CenterStart,
            modifier = Modifier.weight(1f),
            label = "chatRowActivity",
        ) { shown ->
            if (shown != null) {
                TypingLabel(shown, inter(14f))
            } else {
                AnimatedContent(
                    targetState = subtitle,
                    transitionSpec = { fadeIn(Motion.fade()) togetherWith fadeOut(Motion.fade()) },
                    contentAlignment = Alignment.CenterStart,
                    label = "chatRowSubtitle",
                ) { text ->
                    ShroudText(text, inter(14f), subtitleColor, Modifier.fillMaxWidth(), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
        AnimatedVisibility(
            visible = hasUnseenReactions,
            enter = kitIconSwapIn(Motion.bouncy()),
            exit = kitIconSwapOut(Motion.bouncy()),
        ) {
            Box(Modifier.size(20.dp).background(colors.accent, CircleShape), contentAlignment = Alignment.Center) {
                ShroudIcon(ShroudIcons.HeartFill, Color.White, size = 11.dp)
            }
        }
        UnreadBadge(unreadCount ?: 0, muted)
    }
}

/**
 * The unread capsule: a new badge pops (`Motion.iconSwap` + `Motion.bouncy`), an increment rolls
 * the digits up and a decrement down (iOS `.contentTransition(.numericText)`), a mute fades the fill
 * to `mutedBadge` (`ChatRowView.swift:95-112`). The last count stays on the capsule while it leaves.
 */
@Composable
private fun UnreadBadge(count: Int, muted: Boolean) {
    val colors = ShroudTheme.colors
    val lastShown = remember { mutableIntStateOf(count) }
    if (count > 0) lastShown.intValue = count
    val fill by animateColorAsState(if (muted) colors.mutedBadge else colors.accent, Motion.fade(), label = "unreadFill")
    AnimatedVisibility(
        visible = count > 0,
        enter = kitIconSwapIn(Motion.bouncy()),
        exit = kitIconSwapOut(Motion.bouncy()),
    ) {
        AnimatedContent(
            targetState = lastShown.intValue,
            transitionSpec = {
                if (targetState > initialState) {
                    (slideInVertically(Motion.bouncy()) { it } + fadeIn(Motion.bouncy())) togetherWith
                        (slideOutVertically(Motion.bouncy()) { -it } + fadeOut(Motion.bouncy()))
                } else {
                    (slideInVertically(Motion.bouncy()) { -it } + fadeIn(Motion.bouncy())) togetherWith
                        (slideOutVertically(Motion.bouncy()) { it } + fadeOut(Motion.bouncy()))
                }
            },
            contentAlignment = Alignment.Center,
            modifier = Modifier.background(fill, CircleShape).padding(horizontal = 7.dp, vertical = 3.dp),
            label = "unreadCount",
        ) { shown ->
            ShroudText(
                ChatRowAccessibility.badgeText(shown),
                inter(12f, FontWeight.SemiBold).copy(fontFeatureSettings = "tnum"),
                Color.White,
                maxLines = 1,
            )
        }
    }
}

/** The pure parts of [ChatRow]: what TalkBack says and what the badge shows. */
object ChatRowAccessibility {
    /** `ChatRowView.swift:127-140`. */
    fun label(
        title: String,
        subtitle: String,
        time: String?,
        unreadCount: Int?,
        hasUnseenReactions: Boolean,
        muted: Boolean,
        activity: ChatPeerActivity?,
    ): String {
        val parts = mutableListOf(title, activity?.spokenLabel ?: subtitle)
        if (time != null) parts += time
        if (unreadCount != null && unreadCount > 0) parts += "$unreadCount unread"
        if (hasUnseenReactions) parts += "new reactions"
        if (muted) parts += "muted"
        return parts.joinToString(", ")
    }

    /** `ChatRowView.swift:123-125`: "99+" past 99. */
    fun badgeText(count: Int): String = if (count > 99) "99+" else count.toString()
}

@Preview(name = "Chat rows · 412", widthDp = 412)
@Composable
private fun ChatRowPreview() = ChatRowSamples(dark = false)

@Preview(name = "Chat rows · 360 · dark", widthDp = 360)
@Composable
private fun ChatRowDarkPreview() = ChatRowSamples(dark = true)

@Composable
private fun ChatRowSamples(dark: Boolean) {
    ShroudTheme(dark = dark) {
        Column(Modifier.background(ShroudTheme.colors.background)) {
            ChatRow("Design Team", "Nina: Final icons are ready", "12:45", { NameAvatar("Design Team") }, unreadCount = 3, onClick = {})
            ChatRow("Jane Cooper", "online", null, { NameAvatar("Jane Cooper") }, activity = ChatPeerActivity.Recording, onClick = {}, subtitleAccent = true)
            ChatRow("Family", "Photo", "11:02", { NameAvatar("Family") }, unreadCount = 120, muted = true, hasUnseenReactions = true, onClick = {})
            ChatRow("Notes to me", "Personal notes, photos & todos", "Yesterday", { SymbolAvatar(ShroudIcons.LockFill) }, selected = true, onClick = {})
        }
    }
}
