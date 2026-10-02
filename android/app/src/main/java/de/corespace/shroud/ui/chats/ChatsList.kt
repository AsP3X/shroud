package de.corespace.shroud.ui.chats

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyItemScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import de.corespace.shroud.core.messaging.local.LocalMessageStore
import de.corespace.shroud.ui.components.ChatRow
import de.corespace.shroud.ui.components.EmptyState
import de.corespace.shroud.ui.components.EmptyStateButton
import de.corespace.shroud.ui.components.ListLoadError
import de.corespace.shroud.ui.components.NameAvatar
import de.corespace.shroud.ui.components.SearchField
import de.corespace.shroud.ui.components.ShroudIcon
import de.corespace.shroud.ui.components.ShroudText
import de.corespace.shroud.ui.components.SkeletonChatList
import de.corespace.shroud.ui.components.SymbolAvatar
import de.corespace.shroud.ui.components.entranceRow
import de.corespace.shroud.ui.theme.Motion
import de.corespace.shroud.ui.theme.ShroudIcons
import de.corespace.shroud.ui.theme.ShroudTheme
import de.corespace.shroud.ui.theme.inter

/** The words of the Chats tab that tests and TalkBack read (`ChatsView.swift`). */
object ChatsCopy {
    const val TITLE = "Chats"

    /** The bar's New Chat button (CV:61-64). */
    const val NEW_CHAT_BUTTON = "New chat"

    /** The offline banner, with `LocalMessageStore.retentionDays` (CV:351). */
    val OFFLINE_BANNER = "Offline — showing last ${LocalMessageStore.RETENTION_DAYS} days on this device"

    /** What TalkBack says for the banner (CV:361). */
    const val OFFLINE_SPOKEN = "Offline. Showing cached messages."

    const val LOAD_ERROR_TITLE = "Can't load chats"
    const val NO_MATCHES_TITLE = "No matches"
    const val NO_MATCHES_MESSAGE = "Try a different name."
    const val NO_CHATS_TITLE = "No chats yet"
    const val NO_CHATS_MESSAGE = "Message a contact to start a conversation."
    const val NEW_CHAT_EMPTY_BUTTON = "New Chat"
}

/**
 * The header that scrolls away under the bar (`ChatsView.swift:65-79`): the offline banner (h 16,
 * sliding in from the top with a fade, `Motion.fade`) above the header [SearchField] (h 16, bottom
 * 10), which fades out while the tab bar's search is open (`isTabBarSearchActive`, CV:72-77) —
 * both fields edit the shell's one query.
 */
@Composable
internal fun ChatsHeader(isOffline: Boolean, tabBarSearchActive: Boolean, query: String, onQueryChange: (String) -> Unit) {
    val reduceMotion = ShroudTheme.reduceMotion
    Column(Modifier.fillMaxWidth()) {
        AnimatedVisibility(
            visible = isOffline,
            enter = if (reduceMotion) fadeIn(Motion.reduced()) else slideInVertically(Motion.fade()) { -it } + fadeIn(Motion.fade()),
            exit = if (reduceMotion) fadeOut(Motion.reduced()) else slideOutVertically(Motion.fade()) { -it } + fadeOut(Motion.fade()),
        ) {
            // `VStack(spacing: 8)`: the gap rides with the banner, so no gap stays behind without it.
            Column {
                OfflineBanner(Modifier.padding(horizontal = 16.dp))
                Spacer(Modifier.height(8.dp))
            }
        }
        AnimatedVisibility(visible = !tabBarSearchActive, enter = fadeIn(Motion.fade()), exit = fadeOut(Motion.fade())) {
            SearchField(query, onQueryChange, modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 10.dp))
        }
    }
}

/**
 * "Offline — showing last 90 days on this device" (`ChatsView.swift:347-362`; shell-chats §8.4):
 * Phosphor `wifi-slash` 13 dp + 13 sp Medium (two lines at most) in `textPrimary`, padding h 12 v 8,
 * `backgroundGrouped`, radius 12. One TalkBack node: "Offline. Showing cached messages."
 */
@Composable
internal fun OfflineBanner(modifier: Modifier = Modifier) {
    val colors = ShroudTheme.colors
    Row(
        modifier
            .fillMaxWidth()
            .clearAndSetSemantics { contentDescription = ChatsCopy.OFFLINE_SPOKEN }
            .background(colors.backgroundGrouped, RoundedCornerShape(12.dp))
            .padding(horizontal = 12.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ShroudIcon(ShroudIcons.WifiSlash, colors.textPrimary, size = 13.dp)
        ShroudText(ChatsCopy.OFFLINE_BANNER, inter(13f, FontWeight.Medium), colors.textPrimary, Modifier.weight(1f), maxLines = 2)
    }
}

/** Where a row sits on screen, for the menu that lifts it (read when the long press lands). */
@Stable
internal class RowAnchor {
    var coordinates: LayoutCoordinates? = null

    fun bounds(): Rect? = coordinates?.takeIf { it.isAttached }?.boundsInRoot()
}

/**
 * One chat row with its separator (`ChatsView.swift:83-149`): the [ChatRow] (Notes: the bookmark
 * on the brand gradient, CV:89-95, 341-345; others: initials on the username's gradient), the
 * press highlight and light haptic, the staggered entrance at [entranceIndex], a long press that
 * opens the row menu ([onMenu] with the row's bounds) and the 1 dp `separator` line inset 80
 * (16 + 52 + 12) under it when [separator] (CV:146-148, 364-369). Reordering animates with
 * `Motion.standard` (CV:170-171). TalkBack gets the menu's items as custom actions ([onMenuItem];
 * "Mute" opens the menu at its durations' row).
 */
@Composable
internal fun LazyItemScope.ChatListRow(
    row: ChatRowModel,
    entranceIndex: Int,
    separator: Boolean,
    onOpen: (ChatRowModel) -> Unit,
    onMenu: (ChatRowModel, Rect) -> Unit,
    onMenuItem: (ChatRowModel, ChatRowMenuItem, Rect?) -> Unit,
) {
    val reduceMotion = ShroudTheme.reduceMotion
    val anchor = remember { RowAnchor() }
    val actions = ChatRowMenu.items(row).map { item ->
        CustomAccessibilityAction(item.title) {
            onMenuItem(row, item, anchor.bounds())
            true
        }
    }
    Column(
        Modifier
            .animateItem(
                fadeInSpec = Motion.fade(),
                placementSpec = Motion.respecting(reduceMotion, Motion.standard()),
                fadeOutSpec = Motion.fade(),
            )
            .entranceRow(entranceIndex),
    ) {
        ChatRowView(
            row = row,
            onClick = { onOpen(row) },
            onLongPress = { anchor.bounds()?.let { onMenu(row, it) } },
            modifier = Modifier
                .onPlaced { anchor.coordinates = it }
                .semantics { customActions = actions },
        )
        if (separator) ListSeparator()
    }
}

/** [ChatRow] for a [ChatRowModel] — the list row and the lifted copy in the menu. */
@Composable
internal fun ChatRowView(row: ChatRowModel, onClick: () -> Unit, onLongPress: (() -> Unit)?, modifier: Modifier = Modifier) {
    ChatRow(
        title = row.title,
        subtitle = row.subtitle,
        time = row.time,
        avatar = {
            if (row.isNotes) SymbolAvatar(ShroudIcons.BookmarkSimpleFill) else NameAvatar(row.title, seed = row.avatarSeed)
        },
        unreadCount = row.unreadCount,
        muted = row.isMuted,
        hasUnseenReactions = row.hasUnseenReactions,
        activity = row.activity,
        selected = row.selected,
        onClick = onClick,
        onLongPress = onLongPress,
        modifier = modifier,
        onLongPressLabel = if (onLongPress != null) ChatRowMenu.OPTIONS_LABEL else null,
    )
}

/** 1 dp `separator`, inset 80 (`listSeparator`, CV:364-369). */
@Composable
internal fun ListSeparator(inset: androidx.compose.ui.unit.Dp = 80.dp) {
    Box(
        Modifier
            .padding(start = inset)
            .fillMaxWidth()
            .height(1.dp)
            .background(ShroudTheme.colors.separator),
    )
}

/**
 * What stands under the rows (`ChatsView.swift:151-166`; shell-chats §8.4): the shimmering
 * placeholders (fade), "Can't load chats" with Try Again (fade + 8 dp rise), the empty state (same),
 * the Notes-only spacer, or nothing. A changed error message updates in place.
 */
@Composable
internal fun ChatsBlockView(block: ChatsListBlock, onRetry: suspend () -> Unit, onNewChat: () -> Unit) {
    val reduceMotion = ShroudTheme.reduceMotion
    val rise = with(LocalDensity.current) { 8.dp.roundToPx() }
    AnimatedContent(
        targetState = block,
        contentKey = { it::class },
        transitionSpec = { ChatsBlockTransitions.between(initialState, targetState, rise, reduceMotion) },
        label = "chatsBlock",
    ) { shown ->
        when (shown) {
            ChatsListBlock.None -> Spacer(Modifier.fillMaxWidth())
            ChatsListBlock.Skeleton -> SkeletonChatList(rows = 7)
            is ChatsListBlock.LoadError -> ListLoadError(ChatsCopy.LOAD_ERROR_TITLE, shown.message, onRetry)
            is ChatsListBlock.Empty -> ChatsEmptyState(shown.isSearching, onNewChat)
            ChatsListBlock.OnlyNotes -> Spacer(Modifier.fillMaxWidth().height(8.dp))
        }
    }
}

/**
 * The empty list (`emptyState`, `ChatsView.swift:371-406`): Phosphor `chats-circle-fill` with its
 * one-shot bounce; "No matches" / "Try a different name." while searching, else "No chats yet" /
 * "Message a contact to start a conversation." with a "New Chat" button.
 */
@Composable
internal fun ChatsEmptyState(isSearching: Boolean, onNewChat: () -> Unit) {
    EmptyState(
        icon = ShroudIcons.ChatsCircleFill,
        title = if (isSearching) ChatsCopy.NO_MATCHES_TITLE else ChatsCopy.NO_CHATS_TITLE,
        message = if (isSearching) ChatsCopy.NO_MATCHES_MESSAGE else ChatsCopy.NO_CHATS_MESSAGE,
        action = if (isSearching) null else ({ EmptyStateButton(ChatsCopy.NEW_CHAT_EMPTY_BUTTON, onNewChat) }),
    )
}

/** How the list's state block changes (CV:171-173, LLE:64, CV:405). */
internal object ChatsBlockTransitions {
    fun between(from: ChatsListBlock, to: ChatsListBlock, risePx: Int, reduceMotion: Boolean): ContentTransform =
        ContentTransform(
            targetContentEnter = enter(to, risePx, reduceMotion),
            initialContentExit = exit(from, risePx, reduceMotion),
            sizeTransform = SizeTransform(clip = false),
        )

    private fun rises(block: ChatsListBlock): Boolean = block is ChatsListBlock.LoadError || block is ChatsListBlock.Empty

    private fun enter(block: ChatsListBlock, risePx: Int, reduceMotion: Boolean): EnterTransition = when {
        reduceMotion -> fadeIn(Motion.reduced())
        rises(block) -> fadeIn(Motion.fade()) + slideInVertically(Motion.fade()) { risePx }
        else -> fadeIn(Motion.fade())
    }

    private fun exit(block: ChatsListBlock, risePx: Int, reduceMotion: Boolean): ExitTransition = when {
        reduceMotion -> fadeOut(Motion.reduced())
        rises(block) -> fadeOut(Motion.fade()) + slideOutVertically(Motion.fade()) { risePx }
        else -> fadeOut(Motion.fade())
    }
}
