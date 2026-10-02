package de.corespace.shroud.ui.conversation.menu

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.dismiss
import androidx.compose.ui.semantics.isTraversalGroup
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlin.math.max
import kotlin.math.roundToInt

/** What TalkBack announces when the menu opens (conversation-thread §16.1, §17). */
const val MESSAGE_MENU_PANE_TITLE = "Message options"

/**
 * The long-press menu over the dimmed thread: reaction bar, the lifted bubble and the action card,
 * placed by [MessageMenuLayout] and flown out of the bubble's slot in the list
 * (`MessageMenuOverlay`, `MessageActionMenu.swift:629-784`; conversation-thread §16.4–§16.5).
 *
 * Human: [progress] 0 draws the bubble exactly where it sits in the thread (the list hides its own
 * copy meanwhile), 1 is the resting stack; the bar and card ride along with the bubble and fade with
 * it. Only a message too tall for the screen gets a scroll container, opened at its bottom so the
 * card is visible first. "More" grows the bar into the full set over a bubble and card that step
 * back to 35 %; a tap outside the panel then dismisses.
 *
 * Agent: [sourceInRoot] is the bubble's slot in root px when the hold began. The host animates
 * [progress] and owns what the buttons do; [hero] and [card] are drawn in slots of the planned size.
 * [rowWidth] (dp, 0 when unknown) is the thread's row width: the hero gets a slot that wide, pinned to
 * the bubble's side, so it sizes itself from the same width as the list bubble and cannot re-wrap
 * (iOS passes `chatRowWidth` to the hero and offers it the bubble's width + 1, MAM:745-761).
 * The bubble is never interactive (its taps are the backdrop's), the card only above progress 0.5.
 * Reads the window insets (keyboard included) on every layout. Modal for TalkBack, announced as
 * [MESSAGE_MENU_PANE_TITLE]; TalkBack's dismiss is [onBackdropTap].
 */
@Composable
fun MessageMenuOverlay(
    sourceInRoot: Rect,
    isMine: Boolean,
    cardHeight: Float,
    progress: Float,
    onReaction: (String, Rect?) -> Unit,
    selectedReactions: Set<String>,
    showsReactions: Boolean,
    onBackdropTap: () -> Unit,
    rowWidth: Float = 0f,
    hero: @Composable () -> Unit,
    card: @Composable () -> Unit,
) {
    val density = LocalDensity.current
    val onBackdrop by rememberUpdatedState(onBackdropTap)
    var origin by remember { mutableStateOf(Offset.Zero) }
    var showsAllReactions by remember { mutableStateOf(false) }
    val statusTop = WindowInsets.statusBars.asPaddingValues().calculateTopPadding().value
    val bottom = max(
        WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding().value,
        WindowInsets.ime.asPaddingValues().calculateBottomPadding().value,
    )
    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .onGloballyPositioned { origin = it.positionInRoot() }
            .semantics {
                paneTitle = MESSAGE_MENU_PANE_TITLE
                isTraversalGroup = true
                dismiss {
                    onBackdrop()
                    true
                }
            },
    ) {
        val container = Size(maxWidth.value, maxHeight.value)
        val safeArea = MenuInsets(top = statusTop, bottom = bottom)
        val source = with(density) {
            Rect(
                left = (sourceInRoot.left - origin.x).toDp().value,
                top = (sourceInRoot.top - origin.y).toDp().value,
                right = (sourceInRoot.left - origin.x).toDp().value + max(1f, sourceInRoot.width.toDp().value),
                bottom = (sourceInRoot.top - origin.y).toDp().value + max(1f, sourceInRoot.height.toDp().value),
            )
        }
        val metrics = MessageMenuLayout.Metrics(
            reactionSize = if (showsReactions) {
                Size(MessageReactionBarMetrics.barWidth, MessageReactionBarMetrics.barHeight)
            } else {
                Size.Zero
            },
            cardSize = Size(MessageContextMenuCardMetrics.WIDTH, cardHeight),
        )
        val plan = MessageMenuLayout.plan(source, container, safeArea, metrics)
        val scroll = rememberScrollState(initial = with(density) { plan.initialOffset.dp.roundToPx() })
        // Scroll-content coordinates: while scrolled, the list slot sits `offset` further down (MAM:691-694).
        val offset = if (plan.scrolls) with(density) { scroll.value.toDp().value } else 0f
        val heroInContent = MessageMenuLayout.lerp(source.translate(0f, offset), plan.hero, progress)
        val heroOnScreen = heroInContent.translate(0f, -offset)
        val chrome = MessageMenuLayout.chrome(heroOnScreen, isMine, plan, metrics)
        val interactive = progress > 0.5f

        MessageMenuBackdrop(onTap = onBackdropTap, progress = progress)

        if (plan.scrolls) {
            Box(
                Modifier
                    .fillMaxSize()
                    .verticalScroll(scroll, enabled = interactive)
                    .then(if (interactive) Modifier else Modifier.tapsGoTo(onBackdropTap)),
            ) {
                Box(
                    Modifier
                        .requiredSize(container.width.dp, plan.contentHeight.dp)
                        // The backdrop is under the scroll container; its empty space dismisses here.
                        .pointerInput(Unit) { detectTapGestures { onBackdrop() } },
                ) {
                    BubbleAndCard(
                        hero = heroInContent,
                        card = chrome.card.translate(0f, offset),
                        isMine = isMine,
                        progress = progress,
                        dimmed = showsAllReactions,
                        onBackdropTap = onBackdropTap,
                        rowWidth = rowWidth,
                        heroContent = hero,
                        cardContent = card,
                    )
                }
            }
        } else {
            BubbleAndCard(
                hero = heroOnScreen,
                card = chrome.card,
                isMine = isMine,
                progress = progress,
                dimmed = showsAllReactions,
                onBackdropTap = onBackdropTap,
                rowWidth = rowWidth,
                heroContent = hero,
                cardContent = card,
            )
        }

        // Over the bubble: pinned at the top over a scrolled tall message; grown into the full set,
        // the panel keeps the bar's top edge where it can.
        if (showsReactions) {
            MessageReactionPanel(
                onReaction = onReaction,
                expanded = showsAllReactions,
                onExpandedChange = { showsAllReactions = it },
                progress = progress,
                selected = selectedReactions,
                bar = chrome.reactions,
                container = container,
                safeArea = safeArea,
                onBackdropTap = onBackdropTap,
            )
        }
    }
}

/** The lifted bubble and the card, placed in one coordinate space (MAM:753-774). Rects in dp. */
@Composable
private fun BubbleAndCard(
    hero: Rect,
    card: Rect,
    isMine: Boolean,
    progress: Float,
    dimmed: Boolean,
    onBackdropTap: () -> Unit,
    rowWidth: Float,
    heroContent: @Composable () -> Unit,
    cardContent: @Composable () -> Unit,
) {
    val slotWidth = MessageMenuLayout.heroSlotWidth(hero.width, rowWidth)
    val slotLeft = if (isMine) hero.right - slotWidth else hero.left
    // Behind the grown panel the message and its actions step back into the dimmed thread.
    Box(Modifier.fillMaxSize().graphicsLayer { alpha = if (dimmed) 0.35f else 1f }) {
        Box(
            Modifier
                .offset { IntOffset(slotLeft.dp.roundToPx(), hero.top.dp.roundToPx()) }
                .requiredSize(slotWidth.dp, hero.height.dp)
                // Same size as the list bubble, so progress 0 is a seamless hand-off; never pressed.
                .tapsGoTo(onBackdropTap),
            contentAlignment = if (isMine) Alignment.TopEnd else Alignment.TopStart,
        ) {
            heroContent()
        }
        Box(
            Modifier
                .offset { IntOffset(card.left.dp.roundToPx(), card.top.dp.roundToPx()) }
                .requiredSize(card.width.dp, card.height.dp)
                .graphicsLayer { alpha = progress.coerceIn(0f, 1f) }
                // Under the grown reaction panel, and while it fades, the actions are out of reach:
                // a tap there is a tap outside the panel, which the backdrop turns into a dismiss.
                .then(if (progress > 0.5f && !dimmed) Modifier else Modifier.tapsGoTo(onBackdropTap)),
            contentAlignment = Alignment.TopStart,
        ) {
            cardContent()
        }
    }
}
