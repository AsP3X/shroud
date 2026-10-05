package de.corespace.shroud.ui.conversation

import android.text.format.DateFormat
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.node.LayoutAwareModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.UnplacedAwareModifierNode
import androidx.compose.ui.platform.InspectorInfo
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import de.corespace.shroud.core.model.ChatMessage
import de.corespace.shroud.ui.LocalAppContainer
import de.corespace.shroud.ui.components.OverlayLayer
import de.corespace.shroud.ui.components.Toast
import de.corespace.shroud.ui.components.ToastHost
import de.corespace.shroud.ui.components.ToastState
import de.corespace.shroud.ui.components.LocalGlassBackdrop
import de.corespace.shroud.ui.components.glassBackdropSource
import de.corespace.shroud.ui.components.rememberGlassBackdrop
import de.corespace.shroud.ui.conversation.bubble.LocalBubbleToaster
import de.corespace.shroud.ui.conversation.bubble.MessageBubble
import de.corespace.shroud.ui.conversation.composer.ComposeController
import de.corespace.shroud.ui.conversation.composer.FileAction
import de.corespace.shroud.ui.conversation.composer.ComposeHost
import de.corespace.shroud.ui.conversation.composer.ConversationComposeHost
import de.corespace.shroud.ui.conversation.composer.composerToastInset
import de.corespace.shroud.ui.conversation.menu.MessageActions
import de.corespace.shroud.ui.conversation.menu.MessageContextMenuCard
import de.corespace.shroud.ui.conversation.menu.MessageContextMenuCardMetrics
import de.corespace.shroud.ui.conversation.menu.MessageMenuAnimator
import de.corespace.shroud.ui.conversation.menu.MessageMenuOverlay
import de.corespace.shroud.ui.conversation.reactions.LocalReactionFlightTarget
import de.corespace.shroud.ui.conversation.reactions.ReactionFlightLayer
import de.corespace.shroud.ui.conversation.reactions.ReactionFlightTarget
import de.corespace.shroud.ui.shell.ChatRoute
import de.corespace.shroud.ui.shell.LocalPushedBackGate
import de.corespace.shroud.ui.shell.LocalShellNavigation
import de.corespace.shroud.ui.theme.Motion
import de.corespace.shroud.ui.theme.ShroudTheme
import de.corespace.shroud.ui.theme.rememberHaptics
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.util.UUID
import de.corespace.shroud.ui.conversation.bubble.LocalChatRowWidth as BubbleRowWidth

/**
 * One conversation — iOS `ConversationView` (`ConversationView.swift:11-689`; conversation-thread §1;
 * plan §1.7.13 entry point): the glass header, the thread, the composer, the jump-to-latest control,
 * the long-press menu with its reactions, the delete confirmations and the toasts. Notes ("Notes to
 * me") is the same screen with `NOTES_PEER_ID`.
 *
 * Human: opens on the newest message and marks the chat read while it is on screen; the contact's
 * profile opens from the header and coming back keeps the reader's place. Back closes the message
 * menu first, then the viewers and editors (the composer's layers), discards a voice take, and only
 * then leaves the chat — with the predictive animation only in that last case.
 *
 * Agent: builds the [ConversationViewModel] (per chat, kept while the profile is pushed over it —
 * the shell's stack keeps covered screens composed) and the composer's [ComposeController] on this
 * composition's main-thread scope, and closes both when the chat leaves the stack. The chat counts
 * as shown while the stack places it ([onPlacementChanged]): covered by the profile it is not.
 */
@Composable
fun ConversationScreen(peerId: UUID, username: String, onBack: () -> Unit) {
    val container = LocalAppContainer.current
    val context = LocalContext.current
    val navigation = LocalShellNavigation.current
    val haptics = rememberHaptics()
    val scope = rememberCoroutineScope()
    val currentOnBack by rememberUpdatedState(onBack)
    val currentHaptics by rememberUpdatedState(haptics)
    val backend = remember(container, context) { ConversationBackend.of(container, context) }
    val vm = remember(peerId, backend) {
        ConversationViewModel(
            peer = peerId,
            username = username,
            backend = backend,
            scope = scope,
            haptic = { currentHaptics(it) },
            onBack = { currentOnBack() },
        )
    }
    val controller = remember(vm) {
        ComposeController(peerId, vm.isNotes, container, scope, ScreenComposeHost(vm), username).also {
            vm.compose = ComposerPort(it)
        }
    }
    DisposableEffect(vm) { onDispose { vm.close() } }
    val isRecording by controller.isRecording.collectAsState()
    ConversationContent(
        vm = vm,
        onBack = onBack,
        onOpenProfile = {
            vm.willOpenProfile()
            navigation.push(ChatRoute.ContactProfile(peerId, username))
        },
        coversComposer = controller.coversComposer,
        isRecording = isRecording,
        isSendingMedia = controller.isSendingMedia,
        composer = { onHeight -> ConversationComposeHost(controller, onHeight) },
    )
}

/**
 * The conversation drawn from [vm] (`body` + `chatSurface`, CV:214-689). [composer] draws the bottom
 * bar and reports its height (the keyboard or navigation bar included); [coversComposer] (a viewer,
 * player or compose screen is up), [isRecording] and [isSendingMedia] come from the composer.
 * Separate from [ConversationScreen] so the screen's states can be drawn without the engines.
 */
@Composable
internal fun ConversationContent(
    vm: ConversationViewModel,
    onBack: () -> Unit,
    onOpenProfile: () -> Unit,
    coversComposer: Boolean,
    isRecording: Boolean,
    isSendingMedia: Boolean,
    composer: @Composable (onHeightChanged: (Dp) -> Unit) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = ShroudTheme.colors
    val reduceMotion = ShroudTheme.reduceMotion
    val density = LocalDensity.current
    val backdrop = rememberGlassBackdrop()
    var headerHeight by remember { mutableStateOf(0.dp) }
    var composerHeight by remember { mutableStateOf(0.dp) }
    val list = rememberLazyListState()
    val jump = remember { JumpToLatestState() }
    val scroll = rememberThreadScrollState(list, jump)
    val padding = remember { ThreadPadding(top = { headerHeight }, bottom = { composerHeight }) }
    val toaster = remember(vm) { { toast: Toast -> vm.toasts.show(toast) } }
    ThreadScrollEffects(scroll, vm)
    MessageMenuAnimator(vm.menu, reduceMotion)

    // The pop (and its predictive animation) waits while something else owns the screen: the
    // message menu, a voice take, a viewer, player or compose screen, the sending card (CV:189-196).
    val allowsBack = !vm.menu.isOpen && !isRecording && !coversComposer && !isSendingMedia
    val gate = LocalPushedBackGate.current
    SideEffect { gate.setEnabled(allowsBack) }
    DisposableEffect(gate) { onDispose { gate.setEnabled(true) } }
    // Registered before the composer's handlers: theirs (a viewer, a take) win; this only keeps Back
    // from leaving the app while nothing else answers it (the sending card).
    BackHandler(enabled = !allowsBack) {}

    BoxWithConstraints(
        modifier
            .fillMaxSize()
            .background(colors.backgroundChat)
            .onPlacementChanged { placed -> if (placed) vm.onAppear() else vm.onDisappear() },
    ) {
        // `chatRowWidth`: the list and the menu's hero size bubbles from the same width (CV:261-264).
        val rowWidth = ChatRowWidth.of(maxWidth)
        CompositionLocalProvider(
            LocalGlassBackdrop provides backdrop,
            LocalChatRowWidth provides rowWidth,
            BubbleRowWidth provides rowWidth,
            LocalBubbleToaster provides toaster,
            LocalReactionFlightTarget provides vm.flights.flight?.let { ReactionFlightTarget(it.messageId, it.emoji) },
        ) {
            ThreadList(vm, scroll, padding, Modifier.fillMaxSize().glassBackdropSource())
            EdgeFades(list, { headerHeight }, { composerHeight })
            HeaderHost(
                vm = vm,
                onBack = onBack,
                onOpenProfile = onOpenProfile,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .onSizeChanged { headerHeight = with(density) { it.height.toDp() } },
            )
            Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth()) {
                composer { composerHeight = it }
            }
            JumpToLatestLayer(
                jump = jump,
                vm = vm,
                isAllowed = !coversComposer && !isRecording,
                composerHeight = { composerHeight },
                onJump = { scroll.scrollToBottom(animated = !reduceMotion) },
                modifier = Modifier.align(Alignment.BottomEnd),
            )
        }

        // Back closes the menu before anything else (registered after the composer's handlers).
        BackHandler(enabled = vm.menu.isOpen) { vm.dismissMessageMenu() }
        CompositionLocalProvider(LocalChatRowWidth provides rowWidth, BubbleRowWidth provides rowWidth) {
            MessageMenuLayer(vm, rowWidth)
        }
        // Above the menu: a pick leaves the bar while the menu is still fading out (CV:222-223).
        ReactionFlightLayer(vm.flights.flight, onLanded = vm.flights::landed)
        DeleteMessageSheet(
            pending = vm.pendingDelete,
            isNotes = vm.isNotes,
            onDelete = vm::performDelete,
            onDismiss = vm::cancelDelete,
        )
        DeleteAllNotesSheet(
            visible = vm.showsNotesDeleteConfirm,
            onDelete = vm::deleteAllNotes,
            onDismiss = vm::cancelDeleteAllNotes,
        )
        ToastLayer(vm.toasts, composerHeight = { composerHeight }, coversComposer = coversComposer)
    }
}

/** The header, reading presence on its own so a presence change redraws only the bar. */
@Composable
private fun HeaderHost(vm: ConversationViewModel, onBack: () -> Unit, onOpenProfile: () -> Unit, modifier: Modifier) {
    val context = LocalContext.current
    val locale = LocalConfiguration.current.locales[0]
    val is24h = DateFormat.is24HourFormat(context)
    val state = vm.headerState(Instant.now(), ZoneId.systemDefault(), locale, is24h)
    ConversationHeader(
        state = state,
        onBack = onBack,
        onOpenProfile = onOpenProfile,
        onCall = vm::startCall,
        onDeleteAllNotes = vm::askDeleteAllNotes,
        modifier = modifier,
    )
}

/** The header's backdrop and the composer's soft fade while the thread has content under them. */
@Composable
private fun BoxScope.EdgeFades(list: LazyListState, headerHeight: () -> Dp, composerHeight: () -> Dp) {
    // Reversed list: forward is toward the older messages above, backward toward the newest below.
    val underTop by remember(list) { derivedStateOf { list.canScrollForward } }
    val underBottom by remember(list) { derivedStateOf { list.canScrollBackward } }
    ChatHeaderBackdrop(visible = underTop, barHeight = headerHeight(), modifier = Modifier.align(Alignment.TopCenter))
    ChatEdgeFade(visible = underBottom, height = composerHeight(), fromTop = false, modifier = Modifier.align(Alignment.BottomCenter))
}

/**
 * The jump-to-latest control over the composer's send / mic slot (`jumpToLatestLayer`, CV:612-628):
 * 14 dp from the trailing edge, 8 dp above the composer bar, riding up and down with it
 * (`Motion.snappy`). Not over a full-screen layer, nor during a voice take ([isAllowed]).
 */
@Composable
private fun JumpToLatestLayer(
    jump: JumpToLatestState,
    vm: ConversationViewModel,
    isAllowed: Boolean,
    composerHeight: () -> Dp,
    onJump: () -> Unit,
    modifier: Modifier,
) {
    val reduceMotion = ShroudTheme.reduceMotion
    val lift by animateDpAsState(
        composerHeight() + JumpToLatestMetrics.ABOVE_COMPOSER.dp,
        Motion.respecting(reduceMotion, Motion.snappy()),
        label = "jumpLift",
    )
    ChatJumpToLatestButton(
        isVisible = isAllowed && jump.isAway,
        count = jump.unseenCount(vm.messages),
        onClick = onJump,
        modifier = modifier.padding(end = JumpToLatestMetrics.TRAILING.dp, bottom = lift),
    )
}

/** The chat's toasts, 20 dp above the composer, or at the screen's bottom while a full-screen layer covers it (CV:204-212). */
@Composable
private fun ToastLayer(toasts: ToastState, composerHeight: () -> Dp, coversComposer: Boolean) {
    val systemBottom = WindowInsets.navigationBars.union(WindowInsets.ime).asPaddingValues().calculateBottomPadding()
    ToastHost(toasts, bottomInset = composerToastInset(composerHeight(), systemBottom, coversComposer))
}

/**
 * The long-press menu while a session is open or closing (`messageMenuOverlay(session:)`,
 * CV:2027-2087): in the app's overlay layer, modal for TalkBack, over the header and the composer.
 * Every button closes the menu first, then acts. A new message is a new menu.
 */
@Composable
private fun MessageMenuLayer(vm: ConversationViewModel, rowWidth: Dp) {
    val session = vm.menu.session
    OverlayLayer(active = session != null, modal = true) {
        val current = vm.menu.session ?: return@OverlayLayer
        val message = current.message
        val reduceMotion = ShroudTheme.reduceMotion
        val messages = vm.messages
        val live = vm.live(message)
        val receipt = MessageActions.menuReceipt(live, vm.isNotes)
        val actions = MessageActions.menuActions(live, hasLink = MessageActions.copyableLink(message) != null)
        val hero = remember(message, messages, vm.transfers) {
            MessageRows.hero(
                MessageRows.model(
                    message = message,
                    isNotes = vm.isNotes,
                    peerName = vm.username,
                    myUserId = vm.myUserId,
                    quoted = Timeline.quotedMessages(messages),
                    transfers = vm.transfers,
                    transcriptTail = Timeline.transcriptTail(messages),
                    highlightedId = null,
                ),
            )
        }
        key(message.id) {
            MessageMenuOverlay(
                sourceInRoot = current.sourceInRoot,
                isMine = message.isMine,
                cardHeight = MessageContextMenuCardMetrics.height(receipt, actions),
                progress = vm.menu.progress,
                onReaction = { emoji, from -> vm.reactAfterMenu(emoji, message, from, reduceMotion) },
                selectedReactions = vm.selectedReactions(message),
                showsReactions = vm.canReact(message),
                onBackdropTap = vm::onBackdropTap,
                rowWidth = rowWidth.value,
                hero = { MessageBubble(hero, vm) },
                card = {
                    MessageContextMenuCard(receipt = receipt, actions = actions, onAction = { vm.onMenuAction(it, message) })
                },
            )
        }
    }
}

/** What the composer asks of the screen (plan §1.7.13 `ComposeHost`), forwarded to the view model. */
private class ScreenComposeHost(private val vm: ConversationViewModel) : ComposeHost {
    override fun pinToBottom() = vm.pinToBottom()
    override fun jumpToQuoted(messageId: UUID) = vm.jumpToQuoted(messageId)
    override val isShowingMessageMenu: Boolean get() = vm.menu.isOpen
    override fun showToast(toast: Toast) = vm.toasts.show(toast)
    override fun requestDelete(message: ChatMessage) = vm.requestDelete(message)
    override fun showMessageMenu(message: ChatMessage) = vm.openMessageMenuFromTap(message)
}

/** The composer as the thread drives it ([ConversationCompose]), forwarded to [ComposeController]. */
private class ComposerPort(private val controller: ComposeController) : ConversationCompose {
    override val isRecording: Boolean get() = controller.isRecording.value
    override fun startReply(message: ChatMessage) = controller.startReply(message)
    override fun handleMediaTap(message: ChatMessage) = controller.handleMediaTap(message)
    override fun onLeave(profilePushed: Boolean) = controller.onLeave(profilePushed)
    override fun cancelVoiceTake() = controller.cancelVoiceTake()
    override val isViewingMedia: Boolean get() = controller.viewingMedia != null
    override fun closeMediaViewer() = controller.closeMediaViewer()
    override fun cancelDownload(message: ChatMessage) = controller.cancelDownload(message)
    override fun saveFileToDownloads(message: ChatMessage) = controller.requestFileAction(message, FileAction.Save)
    override fun shareFile(message: ChatMessage) = controller.requestFileAction(message, FileAction.Share)
}

/**
 * Calls [onChange] with true when this element starts being placed and false when its parents stop
 * placing it (a screen pushed over it in the shell's stack, which keeps covered screens composed) —
 * the Android counterpart of SwiftUI's `onAppear` / `onDisappear` for a screen under a pushed one.
 * Delivered on the main thread right after the layout pass, in order.
 */
internal fun Modifier.onPlacementChanged(onChange: (Boolean) -> Unit): Modifier = this then PlacementElement(onChange)

private class PlacementElement(private val onChange: (Boolean) -> Unit) : ModifierNodeElement<PlacementNode>() {
    override fun create(): PlacementNode = PlacementNode(onChange)

    override fun update(node: PlacementNode) {
        node.onChange = onChange
    }

    override fun InspectorInfo.inspectableProperties() {
        name = "onPlacementChanged"
    }

    override fun equals(other: Any?): Boolean = other is PlacementElement && other.onChange === onChange

    override fun hashCode(): Int = onChange.hashCode()
}

private class PlacementNode(var onChange: (Boolean) -> Unit) : Modifier.Node(), LayoutAwareModifierNode, UnplacedAwareModifierNode {
    private var placed = false

    override fun onPlaced(coordinates: LayoutCoordinates) {
        if (placed) return
        placed = true
        deliver(true)
    }

    override fun onUnplaced() {
        if (!placed) return
        placed = false
        deliver(false)
    }

    override fun onDetach() {
        placed = false
    }

    // Not from inside the layout pass: the receiver changes state and talks to the engines.
    private fun deliver(value: Boolean) {
        coroutineScope.launch { onChange(value) }
    }
}
