package de.corespace.shroud.ui.shell

import androidx.activity.BackEventCompat
import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import de.corespace.shroud.ui.theme.Motion
import de.corespace.shroud.ui.theme.ShroudTheme
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** How a [StackHost] animates: the compact push slide, or the two-pane detail's cross-fade (shell-chats §4.9). */
enum class StackStyle { Slide, Fade }

/**
 * The per-screen back gate of a stack (iOS `interactivePopGesture(enabled:)`, `InteractivePopGesture.swift:4-18`):
 * every pushed screen gets its own, starting enabled, so a screen removed while it held back off
 * never leaves the next one blocked (`viewDidDisappear` re-enables, `:61-67`). Pure; tested by `StackHostTest`.
 */
class StackGates<K : Any> {
    private val gates = LinkedHashMap<K, EntryGate>()

    /** [key]'s gate, created enabled on first use. */
    fun gate(key: K): EntryGate = gates.getOrPut(key) { EntryGate() }

    /** Drops the gates of screens no longer on the stack. */
    fun retain(keys: Collection<K>) {
        gates.keys.retainAll(keys.toSet())
    }

    /** Number of gates held (tests). */
    val size: Int get() = gates.size
}

/** One screen's [BackGate]; snapshot state, so the stack's handler follows it. */
@Stable
class EntryGate : BackGate {
    private var open by mutableStateOf(true)

    /** The screen lets the stack take a back gesture. */
    val allowsBack: Boolean get() = open

    override fun setEnabled(enabled: Boolean) {
        open = enabled
    }
}

/** The rules of the stack's back handling (`gestureRecognizerShouldBegin`, `InteractivePopGesture.swift:112-117`). */
object StackBackRules {
    /**
     * The stack's predictive handler may take a back gesture: something to pop, no pop or push
     * still animating (a second pop while the first animates left the stack out of sync), and the
     * top screen lets go.
     */
    fun canPop(depth: Int, animating: Boolean, topGateEnabled: Boolean): Boolean = depth > 0 && !animating && topGateEnabled
}

/**
 * The predictive back storyboard (design `Predictive Back — Chat to Chats`, I3RNnl; shell-chats
 * §4.8): the top screen shrinks to 90 %, rounds its corners to 32 dp and drifts toward the swiped
 * edge, the screen under it shows full size under a 20 % dim. Pure; tested by `StackHostTest`.
 */
object PredictiveBackMotion {
    /** Scale of the top screen at [progress]. */
    fun scale(progress: Float): Float = 1f - 0.1f * progress

    /** Corner radius of the top screen at [progress]. */
    fun cornerRadius(progress: Float): Dp = 32.dp * progress

    /**
     * Horizontal drift of the top screen, px: `sign · (0.05·W − 8 dp) · progress`, toward the edge
     * the swipe started from ([edgeSign] +1 from the left edge, −1 from the right).
     */
    fun translationX(progress: Float, edgeSign: Float, widthPx: Float, density: Float): Float =
        edgeSign * (0.05f * widthPx - 8f * density) * progress

    /** +1 for a swipe from the left edge (the screen moves right), −1 from the right edge. */
    fun edgeSign(swipeEdge: Int): Float = if (swipeEdge == BackEventCompat.EDGE_RIGHT) -1f else 1f
}

/** What a [StackHost] is animating. */
internal enum class StackMotionKind { Idle, Push, Pop, Preview, Commit }

/** A composed screen of a stack: its identity (position and route when pushed) and route. */
internal data class StackLayer<R : Any>(val key: Any, val route: R)

/** The animation state of one [StackHost]. Main thread. */
@Stable
internal class StackMotionState<R : Any>(initial: List<R>) {
    /** Screens composed above the root: the stack, plus a popped screen while it animates out. */
    var displayed by mutableStateOf(layersOf(initial))

    /** The routes the stack last settled on. */
    var target: List<R> = initial

    var kind by mutableStateOf(StackMotionKind.Idle)
    var edgeSign by mutableFloatStateOf(1f)

    /** Push/pop/commit slide, 0 → 1. */
    val slide = Animatable(1f)

    /** Push/pop/commit fade, 0 → 1. */
    val fade = Animatable(1f)

    /** Predictive back progress, 0 → 1 (follows the finger, then animates back on cancel). */
    val progress = Animatable(0f)

    /** The progress a commit started from. */
    var committedFrom by mutableFloatStateOf(0f)

    /** Anything moves: placement shows the screen under the top, touches are held. */
    val animating: Boolean get() = kind != StackMotionKind.Idle

    /** A push or pop runs (not a back gesture): the back handler waits for it. */
    val transitionRunning: Boolean get() = kind == StackMotionKind.Push || kind == StackMotionKind.Pop

    suspend fun settle() {
        kind = StackMotionKind.Idle
        slide.snapTo(1f)
        fade.snapTo(1f)
        progress.snapTo(0f)
        committedFrom = 0f
    }
}

/**
 * One navigation stack: [root] with [routes] pushed above it, the iOS `NavigationStack` push and pop
 * (`MainTabView.swift:296-308`; shell-chats §4.1, §4.8) with Android's predictive back.
 *
 * - Every screen of the stack stays composed, as iOS keeps a `NavigationStack`'s views alive: going
 *   back finds the screen as it was. Only the top screen is placed (drawn, touchable, read by
 *   TalkBack), plus the one under it while a transition or a back gesture shows it.
 * - Push: the new screen slides in from the end and fades in, the old one drifts back a third and
 *   fades ([Motion.standard], [Motion.fade]; New Chat's open slides with [Motion.chatOpenPush]).
 *   Pop mirrors it. [StackStyle.Fade] cross-fades instead (the two-pane detail). Reduce motion:
 *   cross-fades only. RTL mirrors the direction.
 * - Back: a [PredictiveBackHandler] registered before the screens, so their own handlers (menus,
 *   sheets, viewers) win; it runs only when [StackBackRules.canPop] — and [backEnabled]. A swipe
 *   plays [PredictiveBackMotion]; release commits (slide off toward the edge + fade, then [onPop]),
 *   cancel springs back ([Motion.snappy]); the three-button back plays the normal pop.
 * - Each pushed screen gets its own [LocalPushedBackGate] ([StackGates]).
 */
@Composable
fun <R : Any> StackHost(
    routes: List<R>,
    onPop: () -> Unit,
    modifier: Modifier = Modifier,
    style: StackStyle = StackStyle.Slide,
    backEnabled: Boolean = true,
    takeChatOpenPush: () -> Boolean = { false },
    root: @Composable () -> Unit,
    entry: @Composable (R) -> Unit,
) {
    val reduce = ShroudTheme.reduceMotion
    val state = remember { StackMotionState(routes.toList()) }
    val gates = remember { StackGates<Any>() }
    val scope = rememberCoroutineScope()
    val currentOnPop by rememberUpdatedState(onPop)
    val density = LocalDensity.current.density

    val topKey: Any? = routes.lastIndex.takeIf { it >= 0 }?.let { entryKey(it, routes[it]) }
    val topGateEnabled = topKey?.let { gates.gate(it).allowsBack } ?: true

    // Registered before the screens below: their own handlers are added later and win.
    PredictiveBackHandler(enabled = backEnabled && StackBackRules.canPop(routes.size, state.transitionRunning, topGateEnabled)) { events ->
        var previewing = false
        val before = state.target
        try {
            events.collect { event ->
                if (!previewing) {
                    previewing = true
                    state.edgeSign = PredictiveBackMotion.edgeSign(event.swipeEdge)
                    state.kind = StackMotionKind.Preview
                }
                state.progress.snapTo(event.progress.coerceIn(0f, 1f))
            }
            if (previewing) {
                state.committedFrom = state.progress.value
                state.kind = StackMotionKind.Commit
                state.slide.snapTo(0f)
                state.fade.snapTo(0f)
                coroutineScope {
                    val spec: AnimationSpec<Float> = if (reduce) Motion.reduced() else Motion.standard()
                    launch { state.slide.animateTo(1f, spec) }
                    launch { state.fade.animateTo(1f, if (reduce) Motion.reduced() else Motion.fade()) }
                }
            }
            currentOnPop()
            if (previewing) {
                // A pop that changed nothing must not leave the screen faded out under a touch block.
                val moved = withTimeoutOrNull(COMMIT_SETTLE_TIMEOUT_MS) { snapshotFlow { state.target }.first { it != before } }
                if (moved == null && state.kind == StackMotionKind.Commit) state.settle()
            }
        } catch (cancelled: CancellationException) {
            if (previewing) {
                scope.launch {
                    state.progress.animateTo(0f, Motion.respecting(reduce, Motion.snappy()))
                    if (state.kind == StackMotionKind.Preview) state.kind = StackMotionKind.Idle
                }
            }
            throw cancelled
        }
    }

    LaunchedEffect(routes.toList()) {
        val next = routes.toList()
        val previous = state.target
        state.target = next
        gates.retain(next.mapIndexed { index, route -> entryKey(index, route) })
        if (state.kind == StackMotionKind.Commit && next.size < previous.size && previous.subList(0, next.size) == next) {
            // The back gesture already animated the screen away.
            state.displayed = layersOf(next)
            state.settle()
            return@LaunchedEffect
        }
        state.settle()
        when {
            next == previous -> state.displayed = layersOf(next)
            next.size < previous.size && previous.subList(0, next.size) == next -> {
                // Pop (or back to an earlier screen): the old top leaves, under its own identity, over the new one.
                state.displayed = layersOf(next) + StackLayer(entryKey(previous.lastIndex, previous.last()), previous.last())
                animate(state, StackMotionKind.Pop, style, reduce, chatOpen = false)
                state.displayed = layersOf(next)
                state.settle()
                gates.retain(next.mapIndexed { index, route -> entryKey(index, route) })
            }
            next.isNotEmpty() -> {
                // Push, or a replaced stack: the new top arrives over what is under it.
                state.displayed = layersOf(next)
                animate(state, StackMotionKind.Push, style, reduce, chatOpen = takeChatOpenPush())
                state.settle()
            }
            else -> state.displayed = layersOf(next)
        }
    }

    val layoutDirection = LocalLayoutDirection.current
    val direction = if (layoutDirection == LayoutDirection.Rtl) -1f else 1f
    val displayed = state.displayed
    val topIndex = displayed.size
    val dimColor = ShroudTheme.colors.dimPredictiveBack
    val fadeOnly = style == StackStyle.Fade || reduce

    Box(modifier) {
        Layout(
            content = {
                // Layer 0: the root.
                key(ROOT_KEY) {
                    LayerBox(index = 0, topIndex = topIndex, state = state, direction = direction, density = density, fadeOnly = fadeOnly, dimColor = dimColor) {
                        CompositionLocalProvider(LocalPushedBackGate provides BackGate.None) { root() }
                    }
                }
                displayed.forEachIndexed { index, layer ->
                    key(layer.key) {
                        LayerBox(index = index + 1, topIndex = topIndex, state = state, direction = direction, density = density, fadeOnly = fadeOnly, dimColor = dimColor) {
                            CompositionLocalProvider(LocalPushedBackGate provides gates.gate(layer.key)) { entry(layer.route) }
                        }
                    }
                }
            },
        ) { measurables, constraints ->
            val loose = Constraints.fixed(constraints.maxWidth, constraints.maxHeight)
            val placeables = measurables.map { it.measure(loose) }
            layout(constraints.maxWidth, constraints.maxHeight) {
                placeables.forEachIndexed { index, placeable ->
                    val visible = index == topIndex || (index == topIndex - 1 && state.animating)
                    if (visible) placeable.place(0, 0, zIndex = index.toFloat())
                }
            }
        }
        // No touches while a screen moves (UIKit blocks interaction during a push too).
        if (state.animating) {
            Box(
                Modifier
                    .matchParentSize()
                    .pointerInput(Unit) {
                        awaitPointerEventScope {
                            while (true) awaitPointerEvent(PointerEventPass.Initial).changes.forEach { it.consume() }
                        }
                    },
            )
        }
    }
}

/** A screen of the stack with its transition transform. */
@Composable
private fun <R : Any> LayerBox(
    index: Int,
    topIndex: Int,
    state: StackMotionState<R>,
    direction: Float,
    density: Float,
    fadeOnly: Boolean,
    dimColor: Color,
    content: @Composable () -> Unit,
) {
    Box(
        Modifier
            .graphicsLayer {
                val isTop = index == topIndex
                val isUnder = index == topIndex - 1
                val w = size.width
                var tx = 0f
                var a = 1f
                var s = 1f
                var corner = 0.dp
                var lift = 0f
                when (state.kind) {
                    StackMotionKind.Idle -> Unit
                    StackMotionKind.Push -> when {
                        isTop -> {
                            if (!fadeOnly) tx = direction * w * (1f - state.slide.value)
                            a = state.fade.value
                        }
                        isUnder && !fadeOnly -> {
                            tx = -direction * w / 3f * state.slide.value
                            a = 1f - state.fade.value
                        }
                    }
                    StackMotionKind.Pop -> when {
                        isTop -> {
                            if (!fadeOnly) tx = direction * w * state.slide.value
                            a = 1f - state.fade.value
                        }
                        isUnder && !fadeOnly -> {
                            tx = -direction * w / 3f * (1f - state.slide.value)
                            a = state.fade.value
                        }
                    }
                    StackMotionKind.Preview -> if (isTop && !fadeOnly) {
                        val p = state.progress.value
                        s = PredictiveBackMotion.scale(p)
                        tx = PredictiveBackMotion.translationX(p, state.edgeSign, w, density)
                        corner = PredictiveBackMotion.cornerRadius(p)
                        lift = p
                    }
                    StackMotionKind.Commit -> if (isTop) {
                        val p = state.committedFrom
                        if (!fadeOnly) {
                            s = PredictiveBackMotion.scale(p)
                            val from = PredictiveBackMotion.translationX(p, state.edgeSign, w, density)
                            tx = from + (state.edgeSign * w - from) * state.slide.value
                            corner = PredictiveBackMotion.cornerRadius(p)
                            lift = p
                        }
                        a = 1f - state.fade.value
                    }
                }
                translationX = tx
                alpha = a.coerceIn(0f, 1f)
                scaleX = s
                scaleY = s
                if (corner > 0.dp) {
                    shape = RoundedCornerShape(corner)
                    clip = true
                    shadowElevation = PREVIEW_ELEVATION.toPx() * lift
                    spotShadowColor = PREVIEW_SHADOW
                    ambientShadowColor = PREVIEW_SHADOW
                } else {
                    clip = false
                    shadowElevation = 0f
                }
            }
            .drawWithContent {
                drawContent()
                // The screen a back gesture reveals sits under a dim that fades with the gesture.
                if (index == topIndex - 1) {
                    val dim = when (state.kind) {
                        StackMotionKind.Preview -> state.progress.value
                        StackMotionKind.Commit -> state.committedFrom * (1f - state.slide.value)
                        else -> 0f
                    }
                    if (dim > 0f) drawRect(dimColor, alpha = dim.coerceIn(0f, 1f))
                }
            },
    ) {
        content()
    }
}

/** Runs a push or pop of [kind] to its end. */
private suspend fun <R : Any> animate(state: StackMotionState<R>, kind: StackMotionKind, style: StackStyle, reduce: Boolean, chatOpen: Boolean) {
    state.slide.snapTo(0f)
    state.fade.snapTo(0f)
    state.kind = kind
    val slideSpec: AnimationSpec<Float> = when {
        reduce -> Motion.reduced()
        chatOpen -> Motion.chatOpenPush()
        else -> Motion.standard()
    }
    val fadeSpec: AnimationSpec<Float> = when {
        reduce -> Motion.reduced()
        style == StackStyle.Fade -> Motion.fade()
        chatOpen -> Motion.chatOpenPush()
        else -> Motion.fade()
    }
    coroutineScope {
        launch { state.slide.animateTo(1f, slideSpec) }
        launch { state.fade.animateTo(1f, fadeSpec) }
    }
}

/** A pushed screen's identity: its position and route (the same chat pushed twice is two screens). */
private fun entryKey(index: Int, route: Any): Any = index to route

private fun <R : Any> layersOf(routes: List<R>): List<StackLayer<R>> = routes.mapIndexed { index, route -> StackLayer(entryKey(index, route), route) }

/** A committed back gesture waits this long for the pop to reach the stack. */
private const val COMMIT_SETTLE_TIMEOUT_MS = 500L

/** Shadow under the shrinking screen at full progress (design 0/8/32). */
private val PREVIEW_ELEVATION = 16.dp

private const val ROOT_KEY = "shroud.stack.root"

/** Design `0/8/32 #0B0B1240` under the shrinking screen. */
private val PREVIEW_SHADOW = Color(0x400B0B12)
