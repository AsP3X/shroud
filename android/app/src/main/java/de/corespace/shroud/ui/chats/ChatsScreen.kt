package de.corespace.shroud.ui.chats

import android.text.format.DateFormat
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import de.corespace.shroud.core.messaging.ChatListFormatting
import de.corespace.shroud.core.model.AppClock
import de.corespace.shroud.core.model.MuteDuration
import de.corespace.shroud.ui.LocalAppContainer
import de.corespace.shroud.ui.components.ContextMenu
import de.corespace.shroud.ui.components.GlassBarButton
import de.corespace.shroud.ui.components.ListEntranceHost
import de.corespace.shroud.ui.components.ToastHost
import de.corespace.shroud.ui.components.rememberToastState
import de.corespace.shroud.ui.shell.ChatRoute
import de.corespace.shroud.ui.shell.LocalIsTabBarSearchActive
import de.corespace.shroud.ui.shell.LocalShellNavigation
import de.corespace.shroud.ui.shell.LocalTabBarClearance
import de.corespace.shroud.ui.shell.LocalWindowLayout
import de.corespace.shroud.ui.shell.ShellNavigation
import de.corespace.shroud.ui.shell.WindowLayout
import de.corespace.shroud.ui.theme.ShroudIcons
import de.corespace.shroud.ui.theme.rememberHaptics
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import java.time.ZoneId
import java.util.Locale
import java.util.UUID

/**
 * The Chats tab (`ChatsView.swift`; shell-chats §8; design `Chats` eVLXT, `Chats — Search` Fevlu,
 * `Chats — Chat Menu` AqgbA, `Chats · Dark` MGREE, `Chats · 360` zMjzp): "Notes to me" pinned on
 * top, the conversations in server order, the row menu (Mark as Read, Mute ▸, Unmute, Delete Chat),
 * the delete confirmation, pull to refresh and New Chat. [query] is the shell's shared search text
 * (header field here, the tab bar's field there); [onQueryChange] edits it.
 *
 * Plan §1.7.13 entry point (W2-INT seam); owner W3-CHATS. Reads the messaging engine and the roster
 * through [MessagingChatsSource]; pushes chats through [LocalShellNavigation].
 */
@Composable
fun ChatsScreen(query: String, onQueryChange: (String) -> Unit) {
    val container = LocalAppContainer.current
    val source = remember(container) { MessagingChatsSource(container.messaging.controller, container.contacts.controller) }
    ChatsTab(source, query, onQueryChange, actionScope = container.appScope, clock = container.clock)
}

/** The chat a long press lifted, and where its row was. */
private data class ChatMenuTarget(val row: ChatRowModel, val anchor: Rect)

/**
 * [ChatsScreen] on any [ChatsSource] (tests, previews). [actionScope] runs the menu's and the
 * delete sheet's requests so they finish when the list goes away (iOS `Task {}`); the app scope in
 * the app.
 */
@Composable
internal fun ChatsTab(
    source: ChatsSource,
    query: String,
    onQueryChange: (String) -> Unit,
    actionScope: CoroutineScope,
    clock: AppClock,
    navigation: ShellNavigation = LocalShellNavigation.current,
    layout: WindowLayout = LocalWindowLayout.current,
) {
    val context = LocalContext.current
    val locale: Locale = LocalConfiguration.current.locales[0] ?: Locale.getDefault()
    val is24h = DateFormat.is24HourFormat(context)
    val zone = ZoneId.systemDefault()
    val live = rememberChatsSnapshot(source, clock)
    val snapshot = live.snapshot
    val selectedPeer = if (layout == WindowLayout.TwoPane) snapshot.activePeer else null
    val ui = remember(snapshot, live.tick, query, selectedPeer, locale, is24h, zone) {
        val now = clock.now()
        ChatsUiState.derive(snapshot, query, selectedPeer, locale) { at -> ChatListFormatting.timeLabel(at, now, zone, locale, is24h) }
    }

    val toasts = rememberToastState()
    val haptics = rememberHaptics()
    val currentHaptics by rememberUpdatedState(haptics)
    val formatting by rememberUpdatedState(Triple(locale, is24h, zone))
    val actions = remember(source, actionScope) {
        ChatsActions(
            source = source,
            scope = actionScope,
            muteLabel = { mute ->
                val (labelLocale, labelIs24h, labelZone) = formatting
                MuteDuration.label(mute, clock.now(), labelZone, labelLocale, labelIs24h)
            },
            feedback = { feedback ->
                feedback.toast?.let(toasts::show)
                currentHaptics(feedback.haptic)
            },
        )
    }

    var menu by remember { mutableStateOf<ChatMenuTarget?>(null) }
    var pendingDelete by remember { mutableStateOf<PendingChatDelete?>(null) }
    var showNewChat by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()

    // iOS `.task` (CV:220-222): the list on arrival, coalescing with the poll.
    LaunchedEffect(source) { source.refreshConversations(force = false) }

    val openChat: (UUID, String) -> Unit = { peer, username ->
        // Two-pane: the chat replaces what the detail pane shows (shell-chats §4.9); compact: pushed.
        if (layout == WindowLayout.TwoPane) navigation.openChat(peer, username) else navigation.push(ChatRoute.Conversation(peer, username))
    }
    val askDelete: (ChatRowModel) -> Unit = { row -> pendingDelete = PendingChatDelete(row.peerId, row.title, row.isNotes) }
    val onMenuItem: (ChatRowModel, ChatRowMenuItem, Rect?) -> Unit = { row, item, anchor ->
        when (item) {
            ChatRowMenuItem.MarkAsRead -> actions.markRead(row.peerId)
            ChatRowMenuItem.Mute -> anchor?.let { menu = ChatMenuTarget(row, it) }
            ChatRowMenuItem.Unmute -> actions.changeMute(row.peerId, null)
            ChatRowMenuItem.DeleteChat, ChatRowMenuItem.DeleteAllNotes -> askDelete(row)
        }
    }
    val clearance = LocalTabBarClearance.current
    val systemBottom = rememberSystemBottom()

    Box(Modifier.fillMaxSize()) {
        ListEntranceHost(key = ui.hasNoConversations) {
            ChatsScaffold(
                title = ChatsCopy.TITLE,
                state = listState,
                trailing = { GlassBarButton(ShroudIcons.SquarePen, ChatsCopy.NEW_CHAT_BUTTON, onClick = { showNewChat = true }) },
                header = { ChatsHeader(ui.isOffline, LocalIsTabBarSearchActive.current, query, onQueryChange) },
                onRefresh = { source.refreshConversations(force = true) },
            ) {
                val notes = ui.notesRow
                if (notes != null) {
                    item(key = NOTES_KEY, contentType = ROW_TYPE) {
                        ChatListRow(
                            row = notes,
                            entranceIndex = 0,
                            separator = ui.separatorAfterNotes,
                            onOpen = { openChat(it.peerId, it.title) },
                            onMenu = { row, anchor -> menu = ChatMenuTarget(row, anchor) },
                            onMenuItem = onMenuItem,
                        )
                    }
                }
                val offset = if (notes != null) 1 else 0
                itemsIndexed(ui.rows, key = { _, row -> row.peerId.toString() }, contentType = { _, _ -> ROW_TYPE }) { index, row ->
                    ChatListRow(
                        row = row,
                        entranceIndex = index + offset,
                        separator = index < ui.rows.lastIndex,
                        onOpen = { openChat(it.peerId, it.title) },
                        onMenu = { target, anchor -> menu = ChatMenuTarget(target, anchor) },
                        onMenuItem = onMenuItem,
                    )
                }
                item(key = BLOCK_KEY, contentType = BLOCK_KEY) {
                    ChatsBlockView(ui.block, onRetry = { source.refreshConversations(force = true) }, onNewChat = { showNewChat = true })
                }
                item(key = BOTTOM_KEY, contentType = BOTTOM_KEY) { Spacer(Modifier.fillMaxWidth().height(16.dp)) }
            }
        }
        ToastHost(toasts, bottomInset = ChatsLayout.toastLift(clearance, systemBottom))
    }

    menu?.let { target ->
        // The menu reflects the row as it is now (a message may land while it is open).
        val row = ui.notesRow?.takeIf { it.peerId == target.row.peerId }
            ?: ui.rows.firstOrNull { it.peerId == target.row.peerId }
            ?: target.row
        val peer = target.row.peerId
        ContextMenu(
            anchor = target.anchor,
            actions = remember(target) {
                ChatRowMenu.actions(
                    target.row,
                    onMarkRead = { actions.markRead(peer) },
                    onMute = { duration -> actions.changeMute(peer, duration) },
                    onUnmute = { actions.changeMute(peer, null) },
                    onDelete = { askDelete(target.row) },
                )
            },
            onDismiss = { menu = null },
            paneTitle = ChatRowMenu.OPTIONS_LABEL,
            header = {
                // The lifted row (design AqgbA): tapping it opens the chat, the menu goes with it.
                ChatRowView(row, onClick = {
                    menu = null
                    openChat(row.peerId, row.title)
                }, onLongPress = null)
            },
        )
    }
    ChatDeleteSheet(
        pending = pendingDelete,
        onDismiss = { pendingDelete = null },
        onDelete = { pending, scope -> actions.delete(pending, scope) },
    )
    NewChatSheet(
        visible = showNewChat,
        source = source,
        clock = clock,
        onDismiss = { showNewChat = false },
        onSelect = { userId, username -> openChat(userId, username) },
    )
}

/** The latest [ChatsSnapshot] and a counter bumped whenever the clock alone changed the rows. */
@Stable
internal class LiveChatsSnapshot(initial: ChatsSnapshot) {
    var snapshot by mutableStateOf(initial)
    var tick by mutableIntStateOf(0)
}

/**
 * Reads [source] whenever it changes, and again when a mute runs out or the day turns
 * ([ChatsClock]) — the moments iOS's redraw-time `isMuted` / `timeLabel` would change on their own.
 */
@Composable
internal fun rememberChatsSnapshot(source: ChatsSource, clock: AppClock): LiveChatsSnapshot {
    val live = remember(source) { LiveChatsSnapshot(source.snapshot()) }
    LaunchedEffect(source) {
        source.changes.collect { live.snapshot = source.snapshot() }
    }
    LaunchedEffect(source, live.snapshot.mutedUntil, live.tick) {
        val now = clock.now()
        delay(ChatsClock.delayMillis(now, ChatsClock.nextChange(now, live.snapshot.mutedUntil.values, ZoneId.systemDefault())))
        live.snapshot = source.snapshot()
        live.tick++
    }
    return live
}

private const val NOTES_KEY = "shroud.chats.notes"
private const val BLOCK_KEY = "shroud.chats.block"
private const val BOTTOM_KEY = "shroud.chats.bottom"
private const val ROW_TYPE = "shroud.chats.row"
