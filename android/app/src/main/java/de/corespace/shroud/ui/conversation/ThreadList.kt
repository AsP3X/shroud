package de.corespace.shroud.ui.conversation

import android.os.SystemClock
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyItemScope
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.toSize
import de.corespace.shroud.core.model.ChatMessage
import de.corespace.shroud.core.model.ChatPeerActivity
import de.corespace.shroud.core.model.canBeQuoted
import de.corespace.shroud.ui.components.ListLoadError
import de.corespace.shroud.ui.components.Spinner
import de.corespace.shroud.ui.conversation.bubble.LocalMessageRowActions
import de.corespace.shroud.ui.conversation.bubble.MessageBubble
import de.corespace.shroud.ui.conversation.bubble.TypingBubbleTransition
import de.corespace.shroud.ui.conversation.bubble.TypingIndicatorBubble
import de.corespace.shroud.ui.conversation.gestures.RowPress
import de.corespace.shroud.ui.conversation.gestures.SwipeToReplyRow
import de.corespace.shroud.ui.conversation.gestures.messageGestures
import de.corespace.shroud.ui.conversation.reactions.ReactionFlightPath
import de.corespace.shroud.ui.theme.Motion
import de.corespace.shroud.ui.theme.ShroudTheme
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

/** Words and sizes of the thread list (conversation-thread §2.3, §3.1, §3.7). */
object ThreadListCopy {
    /** The spinner of a page of older messages on its way (CV:1110). */
    const val LOADING_EARLIER = "Loading earlier messages"

    /** The spinner of a first page on its way (CV:853). */
    const val LOADING = "Loading messages"

    /** The failed first page (CV:856-860). */
    const val LOAD_ERROR_TITLE = "Can't load messages"

    /** The header chip of an empty chat (CV:1150). */
    const val TODAY = "Today"

    /** Gap between rows (`VStack(spacing: 3)`, CV:845). */
    val RowGap: Dp = 3.dp

    /** Vertical padding of the thread's content, inside the bars (CV:959-960). */
    val ContentPadding: Dp = 12.dp

    /** The older-history and loading rows keep this height either way, so rows below never shift (CV:1112-1116). */
    val HeaderRowMinHeight: Dp = 28.dp

    /** Day chips add this above and below themselves (CV:872). */
    val DayChipPadding: Dp = 8.dp

    /** The highlight runs under the side insets and 1.5 dp past the row (CV:887-896). */
    const val HIGHLIGHT_ALPHA = 0.14f
    val HighlightBleedY: Dp = 1.5.dp

    /** A jumped-to row fades out over 0.45 s (CV:1328). */
    const val HIGHLIGHT_FADE_OUT_MS = 450
}

/**
 * The thread's content padding: the top bar's height above, the composer's below (each + 12 dp),
 * 16 dp to the sides (conversation-thread §3.1, §23.6). Read while the list measures, so the
 * keyboard moving the composer re-lays the list out frame by frame without recomposing it.
 */
@Stable
internal class ThreadPadding(private val top: () -> Dp, private val bottom: () -> Dp) : PaddingValues {
    override fun calculateTopPadding(): Dp = top() + ThreadListCopy.ContentPadding
    override fun calculateBottomPadding(): Dp = bottom() + ThreadListCopy.ContentPadding
    override fun calculateLeftPadding(layoutDirection: LayoutDirection): Dp = ChatRowWidth.ThreadInset
    override fun calculateRightPadding(layoutDirection: LayoutDirection): Dp = ChatRowWidth.ThreadInset
}

/**
 * Remembers which messages just arrived at the bottom, so their rows grow out of the corner they
 * were spoken from — not the first messages shown when the chat opens, nor an older page landing at
 * the top (iOS animates the insertion on `newestMessageID` only once `renderFrom` is set,
 * `ConversationView.swift:961-967`; conversation-thread §3.5).
 *
 * Agent: plain fields (no recomposition). [observe] runs in composition and is idempotent per
 * thread instance; [take] is read once by a row's first composition. An arrival that is not drawn
 * within [WINDOW_MS] (the reader is up in the history) arrives without the animation.
 */
internal class ArrivalTracker(private val now: () -> Long = { SystemClock.uptimeMillis() }) {
    private var seen: List<ChatMessage>? = null
    private var newest: ChatMessage? = null
    private val fresh = HashMap<UUID, Long>()

    fun observe(messages: List<ChatMessage>) {
        if (messages === seen) return
        seen = messages
        val previous = newest
        val last = messages.lastOrNull()
        newest = last
        if (previous == null || last == null || last.id == previous.id) return
        val at = now()
        val anchor = messages.indexOfLast { it.id == previous.id }
        val start = if (anchor >= 0) anchor + 1 else messages.indexOfFirst { it.createdAt > previous.createdAt }.takeIf { it >= 0 } ?: messages.size
        for (index in start until messages.size) fresh[messages[index].id] = at
    }

    /** True once for a message that just arrived. */
    fun take(id: UUID): Boolean {
        val at = fresh.remove(id) ?: return false
        return now() - at <= WINDOW_MS
    }

    companion object {
        const val WINDOW_MS = 400L
    }
}

/**
 * The message list (`messageList`, `ConversationView.swift:838-1104`; conversation-thread §2–§3,
 * §13–§15): a reversed lazy list, newest at the bottom, with day chips, the end-to-end notice at the
 * start of the chat, the older-history row at the top, the typing bubble under the newest message.
 *
 * Human: every row can be held for its menu (0.25 s; never blocking a scroll, its release firing
 * nothing), swiped left to reply, double-tapped for ❤️ (text) or tapped to open (photo, video). A
 * row that just arrived grows out of its tail corner while the rows above glide up (0.25 s); a
 * jumped-to row flashes. The row whose menu is open keeps its place, invisible, so the menu's
 * lifted copy lands back exactly on it.
 *
 * Agent: rows are [MessageRowModel]s ([MessageRows]); a row redraws only when its model changes.
 * Scroll rules live in [ThreadScrollState] / [ThreadScrollEffects]. [padding] is read at measure time.
 */
@Composable
internal fun ThreadList(
    vm: ConversationViewModel,
    scroll: ThreadScrollState,
    padding: PaddingValues,
    modifier: Modifier = Modifier,
) {
    val reduceMotion = ShroudTheme.reduceMotion
    val locale = LocalConfiguration.current.locales[0]
    val zone = remember { ZoneId.systemDefault() }
    val messages = vm.messages
    val today = LocalDate.now(zone)
    val timeline = remember(messages, today, locale) { Timeline.items(messages, zone, today, locale) }
    val quoted = remember(messages) { Timeline.quotedMessages(messages) }
    val tail = remember(messages) { Timeline.transcriptTail(messages) }
    val header = ThreadHeader.of(
        isEmpty = messages.isEmpty(),
        hasOlderOnServer = vm.hasOlderOnServer,
        isLoadingOlder = vm.isLoadingOlder,
        loadingFirstPage = vm.loadingFirstPage,
        firstLoadError = vm.firstLoadError,
    )
    val transfers = vm.transfers
    val highlighted = vm.highlightedId
    val activity = vm.peerActivity
    val myUserId = vm.myUserId
    val items = remember(timeline, quoted, tail, transfers, highlighted, activity, header, myUserId) {
        Timeline.threadItems(timeline, activity, header) { message ->
            MessageRows.model(message, vm.isNotes, vm.username, myUserId, quoted, transfers, tail, highlighted)
        }
    }
    val arrivals = remember { ArrivalTracker() }
    arrivals.observe(messages)

    // A tap on a reply header, the composer's reply bar or TalkBack's "Show replied message" (CV:1085-1094).
    val currentItems by rememberUpdatedState(items)
    LaunchedEffect(vm.jumpTarget) {
        val target = vm.jumpTarget ?: return@LaunchedEffect
        val index = currentItems.indexOfFirst { it is ThreadItem.Row && it.model.message.id == target.messageId }
        if (index < 0) {
            vm.didJump(target)
            return@LaunchedEffect
        }
        scroll.scrollToCentre(index) { vm.didJump(target) }
    }

    val menuMessageId = vm.menu.session?.message?.id
    val menuOpen = menuMessageId != null
    val placement: FiniteAnimationSpec<androidx.compose.ui.unit.IntOffset> =
        if (reduceMotion) Motion.reduced() else tween(ThreadScrollMetrics.FOLLOW_MS, easing = Motion.IosEaseOut)
    val fadeOut: FiniteAnimationSpec<Float> = Motion.respecting(reduceMotion, Motion.bouncy())

    LazyColumn(
        state = scroll.list,
        modifier = modifier,
        reverseLayout = true,
        contentPadding = padding,
    ) {
        items(items, key = { it.key }, contentType = { it.contentType }) { item ->
            val animated = Modifier.animateItem(fadeInSpec = null, placementSpec = placement, fadeOutSpec = fadeOut)
            when (item) {
                ThreadItem.Bottom -> Spacer(Modifier.fillMaxWidth().height(1.dp))
                is ThreadItem.Typing -> TypingSlot(item.activity, reduceMotion, animated)
                is ThreadItem.Day -> ChatDateChipRow(item.label, animated)
                is ThreadItem.Row -> {
                    val id = item.model.message.id
                    val fresh = remember(id) { arrivals.take(id) }
                    MessageRow(
                        model = item.model,
                        vm = vm,
                        holdsMenu = menuMessageId == id,
                        menuOpen = menuOpen,
                        fresh = fresh,
                        reduceMotion = reduceMotion,
                        modifier = animated,
                    )
                }
                is ThreadItem.Header -> ThreadHeaderRow(item.header, vm, animated)
            }
        }
    }
}

/** The typing bubble's slot: empty while nobody types, the bubble growing out of its tail corner otherwise (TIB:79-82). */
@Composable
private fun LazyItemScope.TypingSlot(activity: ChatPeerActivity?, reduceMotion: Boolean, modifier: Modifier) {
    var shown by remember { mutableStateOf(activity ?: ChatPeerActivity.Typing) }
    if (activity != null) shown = activity
    val transition = TypingBubbleTransition.of(reduceMotion)
    AnimatedVisibility(visible = activity != null, modifier = modifier.fillMaxWidth(), enter = transition.enter, exit = transition.exit) {
        TypingIndicatorBubble(shown, Modifier.padding(bottom = ThreadListCopy.RowGap))
    }
}

/** A day chip, centred, 8 dp above and below (CV:868-872). */
@Composable
private fun ChatDateChipRow(label: String, modifier: Modifier) {
    Box(
        modifier
            .fillMaxWidth()
            .padding(bottom = ThreadListCopy.RowGap)
            .padding(vertical = ThreadListCopy.DayChipPadding),
        contentAlignment = Alignment.Center,
    ) {
        ChatDateChip(label)
    }
}

/** The topmost row: older history, the first page loading, the failed first page, or the chat's start (CV:846-865). */
@Composable
private fun ThreadHeaderRow(header: ThreadHeader, vm: ConversationViewModel, modifier: Modifier) {
    Box(modifier.fillMaxWidth().padding(bottom = ThreadListCopy.RowGap), contentAlignment = Alignment.Center) {
        when (header) {
            is ThreadHeader.OlderHistory -> SpinnerRow(if (header.showsSpinner) ThreadListCopy.LOADING_EARLIER else null)
            ThreadHeader.LoadingFirstPage -> SpinnerRow(ThreadListCopy.LOADING)
            is ThreadHeader.LoadError -> ListLoadError(
                title = ThreadListCopy.LOAD_ERROR_TITLE,
                message = header.message,
                onRetry = { vm.loadThreadKeepingFailure() },
            )
            is ThreadHeader.Chips -> Column(
                Modifier.fillMaxWidth().padding(bottom = 6.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                if (header.showsToday) ChatDateChip(ThreadListCopy.TODAY)
                ChatE2ENotice(Modifier.padding(bottom = 4.dp))
            }
        }
    }
}

/** A 28 dp row with a small spinner named [label] for TalkBack, or empty at the same height (CV:1106-1117). */
@Composable
private fun SpinnerRow(label: String?) {
    Box(Modifier.fillMaxWidth().heightIn(min = ThreadListCopy.HeaderRowMinHeight), contentAlignment = Alignment.Center) {
        if (label != null) {
            Spinner(
                ShroudTheme.colors.textSecondary,
                Modifier.clearAndSetSemantics {
                    contentDescription = label
                    progressBarRangeInfo = ProgressBarRangeInfo.Indeterminate
                },
                size = 16.dp,
            )
        }
    }
}

/**
 * One message row (CV:867-945; conversation-thread §3.7): the bubble with the row's TalkBack
 * actions, the jump highlight running full bleed behind it, swipe to reply, the arrival transition,
 * invisible while its menu is open (the hero sits on its slot), and the hold / tap / double tap.
 */
@Composable
private fun MessageRow(
    model: MessageRowModel,
    vm: ConversationViewModel,
    holdsMenu: Boolean,
    menuOpen: Boolean,
    fresh: Boolean,
    reduceMotion: Boolean,
    modifier: Modifier,
) {
    val message = model.message
    val colors = ShroudTheme.colors
    val density = LocalDensity.current
    val press = remember { RowPress() }
    val actions = remember(message, menuOpen) { vm.accessibilityActions(message) }
    val highlight by animateFloatAsState(
        targetValue = if (model.highlighted) 1f else 0f,
        animationSpec = if (model.highlighted) Motion.fade() else Motion.easeOut(ThreadListCopy.HIGHLIGHT_FADE_OUT_MS),
        label = "rowHighlight",
    )
    val entrance = remember { Animatable(if (fresh) 0f else 1f) }
    LaunchedEffect(Unit) {
        if (fresh) entrance.animateTo(1f, if (reduceMotion) Motion.reduced() else Motion.bouncy())
    }
    val corner = TransformOrigin(if (message.isMine) 1f else 0f, 1f)
    val bleedX = with(density) { ChatRowWidth.ThreadInset.toPx() }
    val bleedY = with(density) { ThreadListCopy.HighlightBleedY.toPx() }
    val quickSide = with(density) { ReactionFlightPath.QUICK_START_SIDE_DP.dp.toPx() }
    val onTap: (() -> Unit)? = if (vm.opensOnTap(message)) ({ vm.onTapMedia(message) }) else null
    val onDoubleTap: ((Offset) -> Unit)? = if (vm.reactsOnDoubleTap(message)) {
        { point -> vm.quickReact(message, vm.quickReactStart(point, quickSide), reduceMotion) }
    } else {
        null
    }
    Box(
        modifier
            .fillMaxWidth()
            .padding(bottom = ThreadListCopy.RowGap)
            .drawBehind {
                if (highlight > 0f) {
                    drawRect(
                        color = colors.accent.copy(alpha = ThreadListCopy.HIGHLIGHT_ALPHA * highlight),
                        topLeft = Offset(-bleedX, -bleedY),
                        size = Size(size.width + bleedX * 2, size.height + bleedY * 2),
                    )
                }
            }
            .graphicsLayer {
                val grow = entrance.value
                val scale = if (reduceMotion) 1f else 0.82f + 0.18f * grow
                scaleX = scale
                scaleY = scale
                transformOrigin = corner
                alpha = if (holdsMenu) 0f else grow.coerceIn(0f, 1f)
            }
            .onGloballyPositioned { vm.reportRowBounds(message.id, Rect(it.positionInRoot(), it.size.toSize())) }
            .messageGestures(
                press = press,
                claim = vm.tapClaim,
                enabled = !holdsMenu,
                onLongPress = { row -> vm.openMessageMenu(message, row) },
                onHoldReleased = vm::holdReleased,
                onTap = onTap,
                onDoubleTap = onDoubleTap,
            ),
    ) {
        SwipeToReplyRow(
            enabled = message.canBeQuoted && !menuOpen,
            isMine = message.isMine,
            press = press,
            onReply = { vm.startReply(message) },
        ) {
            CompositionLocalProvider(LocalMessageRowActions provides actions) {
                MessageBubble(model, vm)
            }
        }
    }
}
