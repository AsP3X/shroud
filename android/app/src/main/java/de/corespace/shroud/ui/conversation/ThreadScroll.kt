package de.corespace.shroud.ui.conversation

import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.lazy.LazyListLayoutInfo
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.platform.LocalDensity
import de.corespace.shroud.core.model.ChatMessage
import de.corespace.shroud.ui.theme.Motion
import de.corespace.shroud.ui.theme.ShroudTheme
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import java.util.UUID

/** One row's extent along the thread, in the lazy list's logical coordinates (px; 0 is the bottom edge). */
@Immutable
data class ItemExtent(val index: Int, val offset: Int, val size: Int)

/**
 * Where the reader is in the thread, measured from the lazy list (iOS `ThreadScrollMetrics`,
 * `ConversationView.swift:2457-2464`; conversation-thread §3.2).
 *
 * @property atBottom on the newest message (2 dp of slack).
 * @property nearTop within [ThreadScrollMetrics.REVEAL_SLACK_DP] of the oldest end.
 * @property scrollable the thread is taller than the screen (a short one can't be "away").
 */
@Immutable
data class ThreadPosition(val atBottom: Boolean, val nearTop: Boolean, val scrollable: Boolean)

/**
 * The thread's scroll rules as pure functions (conversation-thread §3.2–§3.10). The list is a
 * `LazyColumn(reverseLayout = true)` (decision D1): index 0 — the 1 dp bottom spacer — sits at the
 * bottom, logical offsets grow upward, and a forward scroll moves toward older messages.
 */
object ThreadScrollMetrics {
    /** How close (dp) to the oldest end the reader gets before an older page is fetched (`revealSlack`, CV:467). */
    const val REVEAL_SLACK_DP = 600f

    /** "At the bottom" allows 2 dp (CV:1000-1001). */
    const val AT_BOTTOM_TOLERANCE_DP = 2f

    /** Scroll to a message landing at the bottom: ease-out 0.25 s (`followAnimation`, CV:469). */
    const val FOLLOW_MS = 250

    /** A forced pin re-pins after these delays, for the first layout passes (CV:1222-1227). */
    val PIN_RETRY_DELAYS_MS: LongArray = longArrayOf(16L, 50L, 120L)

    /** On the newest message: the bottom spacer is the first row and scrolled away by at most [tolerancePx]. */
    fun atBottom(firstVisibleIndex: Int, firstVisibleScrollOffset: Int, tolerancePx: Float): Boolean =
        firstVisibleIndex == 0 && firstVisibleScrollOffset <= tolerancePx

    /**
     * How far (px) the thread still goes past the top of the screen: exact once the oldest row is
     * laid out, otherwise estimated from the laid-out rows' average height (a lazy list does not
     * measure what it has not shown). iOS: `contentOffset.y + contentInsets.top` (CV:996).
     *
     * @param visible the laid-out rows, nearest the bottom first.
     * @param viewportEndOffset the list's far (top) edge in logical px.
     * @param afterContentPadding the padding past the oldest row (the top bar's clearance).
     */
    fun remainingToTop(visible: List<ItemExtent>, totalItems: Int, viewportEndOffset: Int, afterContentPadding: Int): Float {
        val last = visible.lastOrNull() ?: return 0f
        val beyond = (last.offset + last.size + afterContentPadding - viewportEndOffset).toFloat()
        val unseen = totalItems - 1 - last.index
        if (unseen <= 0) return beyond
        val average = visible.sumOf { it.size }.toFloat() / visible.size
        return beyond + average * unseen
    }

    /** The reader is within [slackPx] of the oldest end (CV:996). */
    fun nearTop(remainingPx: Float, slackPx: Float): Boolean = remainingPx < slackPx

    /** [ThreadPosition] of a laid-out list ([density] px per dp). */
    fun position(info: LazyListLayoutInfo, firstVisibleIndex: Int, firstVisibleScrollOffset: Int, scrollable: Boolean, density: Float): ThreadPosition {
        val visible = info.visibleItemsInfo.map { ItemExtent(it.index, it.offset, it.size) }
        val remaining = remainingToTop(visible, info.totalItemsCount, info.viewportEndOffset, info.afterContentPadding)
        return ThreadPosition(
            atBottom = atBottom(firstVisibleIndex, firstVisibleScrollOffset, AT_BOTTOM_TOLERANCE_DP * density),
            nearTop = visible.isNotEmpty() && nearTop(remaining, REVEAL_SLACK_DP * density),
            scrollable = scrollable,
        )
    }

    /**
     * Whether a new newest message takes the thread to the bottom (CV:1056-1065): the first messages
     * shown, anything before the opening pin settled, a reader already at the bottom, and our own
     * sends do; someone reading history stays where they are (only the jump badge counts).
     */
    fun follows(previousNewest: UUID?, settled: Boolean, atBottom: Boolean, newestIsMine: Boolean): Boolean =
        previousNewest == null || !settled || atBottom || newestIsMine

    /**
     * The reader is off the bottom by their own doing (`updateJumpToLatest`, CV:1140-1146): not before
     * the opening pin landed, not while a scroll to the bottom is under way, and not in a thread too
     * short to scroll.
     */
    fun isAway(settled: Boolean, pinning: Int, headingToBottom: Boolean, atBottom: Boolean, scrollable: Boolean): Boolean =
        settled && pinning == 0 && !headingToBottom && !atBottom && scrollable

    /** An older page may be asked for (`revealOlder`, CV:1119-1134; the render window is not ported, D1). */
    fun mayRevealOlder(settled: Boolean, pinning: Int, nearTop: Boolean): Boolean = settled && pinning == 0 && nearTop

    /**
     * The forward scroll (px) that centres a row on the screen (`proxy.scrollTo(id, anchor: .center)`,
     * CV:1088-1092): positive moves toward older rows.
     */
    fun centeringDelta(item: ItemExtent, viewportStartOffset: Int, viewportEndOffset: Int): Float =
        (item.offset + item.size / 2f) - (viewportStartOffset + viewportEndOffset) / 2f
}

/**
 * The thread's scroll bookkeeping and moves (iOS `ThreadScrollState` + `scrollToBottom`,
 * `revealOlder`, `updateJumpToLatest`, the jump to a quoted message; `ConversationView.swift:
 * 995-1104, 1119-1146, 1199-1233, 2466-2489`; conversation-thread §3.2–§3.10).
 *
 * Human: the chat opens on its newest message and stays there while things land under the reader;
 * someone reading history is never moved, except by their own send. Near the top the next older page
 * is fetched. Leaving the bottom by scrolling shows the jump-to-latest control; a scroll to the bottom
 * that is still on its way does not.
 *
 * Agent: main thread. Plain fields on purpose — they change every scroll frame and nothing draws
 * from them; only [jump] is observable, and only the jump control reads it. The reversed list keeps
 * the bottom anchored by itself (the bottom spacer is the first visible row), so arrivals under a
 * reader at the bottom need no scroll. Every programmatic scroll runs in its own coroutine of
 * [scope] and gives way to the reader's finger.
 */
@Stable
class ThreadScrollState(
    val list: LazyListState,
    val jump: JumpToLatestState,
    private val scope: CoroutineScope,
) {
    /** The opening pin has landed; before that the offset says nothing about the reader (CV:2484). */
    var settled: Boolean = false
        private set

    /** Forced pins still settling (CV:2482). */
    var pinning: Int = 0
        private set

    /** A scroll to the newest message is under way, so being off the bottom is not the reader's doing (CV:2487). */
    var headingToBottom: Boolean = false
        private set

    /** The last measured position. */
    var position: ThreadPosition = ThreadPosition(atBottom = true, nearTop = false, scrollable = false)
        private set

    /** The newest message, for the jump badge's anchor. */
    internal var newest: () -> ChatMessage? = { null }

    /** Asks for the next older page (the view model checks what is loading). */
    internal var loadOlder: () -> Unit = {}

    internal var reduceMotion: Boolean = false

    /** The geometry changed (`onScrollGeometryChange`, CV:995-1035). */
    fun onPosition(next: ThreadPosition) {
        position = next
        // On the newest message: whatever scroll there was under way has landed (CV:1009).
        if (next.atBottom) headingToBottom = false
        revealOlder()
        updateJumpToLatest()
    }

    /** The reader's finger went down or came up: where the thread sits is their doing now (CV:1078-1084). */
    fun onReaderDrag() {
        headingToBottom = false
        updateJumpToLatest()
    }

    /** Shows or hides the jump control (`updateJumpToLatest`, CV:1140-1146). */
    fun updateJumpToLatest() {
        val away = ThreadScrollMetrics.isAway(settled, pinning, headingToBottom, position.atBottom, position.scrollable)
        if (away != jump.isAway) jump.setAway(away, newest())
    }

    /** Near the top: fetch another page (`revealOlder`, CV:1119-1134). */
    fun revealOlder() {
        if (ThreadScrollMetrics.mayRevealOlder(settled, pinning, position.nearTop)) loadOlder()
    }

    /**
     * Pins the thread to its newest content (`scrollToBottom`, CV:1199-1233). [force] re-pins after
     * the first layout passes and only then lets the reader's position count ([settled]).
     */
    fun scrollToBottom(animated: Boolean, force: Boolean = false) {
        // Off the bottom until this lands, but not by the reader's doing: the jump control goes.
        if (!position.atBottom) headingToBottom = true
        updateJumpToLatest()
        if (animated) {
            scope.launch { guarded { animateToBottom() } }
        } else {
            list.requestScrollToItem(0)
        }
        if (!force) return
        pinning++
        scope.launch {
            try {
                for (wait in ThreadScrollMetrics.PIN_RETRY_DELAYS_MS) {
                    delay(wait)
                    list.requestScrollToItem(0)
                }
            } finally {
                pinning--
                settled = true
            }
            revealOlder()
            updateJumpToLatest()
        }
    }

    /**
     * A new newest message (`onChange(of: newestMessageID)`, CV:1056-1065): scrolls only for the
     * opening, a reader at the bottom, or our own send.
     */
    fun onNewestChanged(previous: UUID?, newest: ChatMessage?) {
        if (newest == null) return
        if (!ThreadScrollMetrics.follows(previous, settled, position.atBottom, newest.isMine)) return
        scrollToBottom(animated = previous != null, force = previous == null)
    }

    /**
     * Up to a quoted message, centred, as if the reader had scrolled there (CV:1085-1094);
     * [onLanded] flashes it. [index] is the row's index in the list.
     */
    fun scrollToCentre(index: Int, onLanded: () -> Unit) {
        headingToBottom = false
        scope.launch {
            guarded {
                val spec = Motion.respecting(reduceMotion, Motion.standard<Float>())
                if (list.layoutInfo.visibleItemsInfo.none { it.index == index }) list.animateScrollToItem(index)
                val info = list.layoutInfo
                val item = info.visibleItemsInfo.firstOrNull { it.index == index }
                if (item != null) {
                    val delta = ThreadScrollMetrics.centeringDelta(
                        ItemExtent(item.index, item.offset, item.size),
                        info.viewportStartOffset,
                        info.viewportEndOffset,
                    )
                    list.animateScrollBy(delta, spec)
                }
            }
            onLanded()
        }
    }

    /**
     * Down to the newest message with the follow animation (ease-out 0.25 s) when the bottom is laid
     * out — the distance is known then — else the list's own animated scroll; lands exactly on the
     * bottom spacer.
     */
    private suspend fun animateToBottom() {
        val bottom = list.layoutInfo.visibleItemsInfo.firstOrNull { it.index == 0 }
        if (bottom != null) {
            // Logical offsets grow upward; a bottom spacer scrolled up by d sits at -d.
            val distance = -bottom.offset.toFloat()
            if (distance > 0f) {
                val spec = if (reduceMotion) Motion.reduced<Float>() else tween(ThreadScrollMetrics.FOLLOW_MS, easing = Motion.IosEaseOut)
                list.animateScrollBy(-distance, spec)
            }
        } else {
            list.animateScrollToItem(0)
        }
        list.requestScrollToItem(0)
    }

    /** A programmatic scroll the reader's finger interrupted is no failure; our own cancellation still is. */
    private suspend fun guarded(block: suspend () -> Unit) {
        try {
            block()
        } catch (interrupted: CancellationException) {
            currentCoroutineContext().ensureActive()
        }
    }
}

/** A [ThreadScrollState] for [list] in this composition. */
@Composable
fun rememberThreadScrollState(list: LazyListState, jump: JumpToLatestState): ThreadScrollState {
    val scope = rememberCoroutineScope()
    return remember(list, jump) { ThreadScrollState(list, jump, scope) }
}

/**
 * Drives [scroll] from the conversation (conversation-thread §3.3–§3.10): pins on open and after
 * the first load ([ConversationViewModel.pinToBottomToken]), follows new messages, measures the
 * reader's position, notices their finger, pages near the top and jumps to quoted messages.
 * Compose it once, next to the list.
 */
@Composable
fun ThreadScrollEffects(scroll: ThreadScrollState, vm: ConversationViewModel) {
    val density = LocalDensity.current.density
    val reduceMotion = ShroudTheme.reduceMotion
    val currentVm by rememberUpdatedState(vm)
    scroll.reduceMotion = reduceMotion
    scroll.newest = { currentVm.messages.lastOrNull() }
    scroll.loadOlder = { currentVm.loadOlder() }

    // Opening + post-load: pin without animation, so the thread never flashes its top (CV:1095-1098).
    LaunchedEffect(scroll, vm.pinToBottomToken) {
        if (vm.pinToBottomToken > 0) scroll.scrollToBottom(animated = false, force = true)
    }
    LaunchedEffect(scroll) {
        var previous: UUID? = vm.messages.lastOrNull()?.id
        snapshotFlow { vm.messages.lastOrNull() }
            .distinctUntilChanged { a, b -> a?.id == b?.id }
            .drop(1)
            .collect { newest ->
                scroll.onNewestChanged(previous, newest)
                previous = newest?.id
            }
    }
    LaunchedEffect(scroll, density) {
        snapshotFlow {
            val list = scroll.list
            ThreadScrollMetrics.position(
                info = list.layoutInfo,
                firstVisibleIndex = list.firstVisibleItemIndex,
                firstVisibleScrollOffset = list.firstVisibleItemScrollOffset,
                scrollable = list.canScrollForward || list.canScrollBackward,
                density = density,
            )
        }.distinctUntilChanged().collect(scroll::onPosition)
    }
    LaunchedEffect(scroll) {
        // An older page landed (or a send went out) while the reader sits at the top (CV:1052-1055).
        snapshotFlow { vm.messages.size }.distinctUntilChanged().drop(1).collect { scroll.revealOlder() }
    }
    LaunchedEffect(scroll) {
        scroll.list.interactionSource.interactions.collect { interaction ->
            if (interaction is DragInteraction.Start || interaction is DragInteraction.Stop || interaction is DragInteraction.Cancel) {
                scroll.onReaderDrag()
            }
        }
    }
}
