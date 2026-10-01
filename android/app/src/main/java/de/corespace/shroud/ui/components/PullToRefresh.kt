package de.corespace.shroud.ui.components

import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.animate
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import de.corespace.shroud.ui.theme.Motion
import de.corespace.shroud.ui.theme.ShroudTheme
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.max
import kotlin.math.min

/**
 * Pull to refresh for a list root — the app's own (no Material `PullToRefreshBox`, shell-chats
 * §10.16, D11), the Android form of iOS `.refreshable` on the chat list (`ChatsView.swift:175-181`:
 * "Explicit pull always fetches").
 *
 * Human: Pulling the list down at its top drags it with half the finger's travel and shows a
 * ring that fills as you pull; past 80 dp it ticks (haptic) and letting go refreshes — the list
 * stays 56 dp down with a spinner until [onRefresh] returns, then springs back. Letting go
 * earlier just springs back. TalkBack users get a "Refresh" action on the list.
 *
 * Agent: Wrap the scrolling list (`LazyColumn` / `verticalScroll`); the pull comes from the list's
 * unused downward scroll (nested scroll), so it only starts at the top. [indicatorTop] puts the
 * spinner under a bar that overlays the list (the glass bar of `MainScrollScreen`). While
 * [onRefresh] runs a second pull does nothing. [enabled] false (e.g. a search is active) turns the
 * gesture off. Only on list roots (never on a pushed conversation, `ChatsView.swift:175-178`).
 */
@Composable
fun PullToRefresh(
    onRefresh: suspend () -> Unit,
    enabled: Boolean = true,
    modifier: Modifier = Modifier,
    indicatorTop: Dp = 0.dp,
    content: @Composable () -> Unit,
) {
    val density = LocalDensity.current
    val view = LocalView.current
    val scope = rememberCoroutineScope()
    val reduceMotion by rememberUpdatedState(ShroudTheme.reduceMotion)
    val currentOnRefresh by rememberUpdatedState(onRefresh)
    val currentEnabled by rememberUpdatedState(enabled)
    val machine = remember(density) {
        with(density) {
            PullToRefreshMachine(
                thresholdPx = PullToRefreshDefaults.Threshold.toPx(),
                maxPx = PullToRefreshDefaults.MaxPull.toPx(),
                holdPx = PullToRefreshDefaults.RefreshingHold.toPx(),
            )
        }
    }
    val controller = remember(machine) { PullToRefreshController(machine) }
    val settleSpec: () -> FiniteAnimationSpec<Float> = { if (reduceMotion) Motion.reduced() else Motion.standard() }

    fun startRefresh() {
        controller.job?.cancel()
        controller.job = scope.launch {
            animate(machine.offset, machine.holdPx, animationSpec = settleSpec()) { value, _ -> machine.animateOffset(value) }
            try {
                currentOnRefresh()
            } finally {
                machine.refreshFinished()
                animate(machine.offset, 0f, animationSpec = settleSpec()) { value, _ -> machine.animateOffset(value) }
                machine.settled()
            }
        }
    }

    fun release() {
        if (machine.release()) {
            startRefresh()
        } else if (machine.phase == PullToRefreshMachine.Phase.Settling) {
            controller.job?.cancel()
            controller.job = scope.launch {
                animate(machine.offset, 0f, animationSpec = settleSpec()) { value, _ -> machine.animateOffset(value) }
                machine.settled()
            }
        }
    }

    val connection = remember(machine) {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                if (source != NestedScrollSource.UserInput || available.y >= 0f || !machine.isPulling) return Offset.Zero
                return Offset(0f, machine.retract(available.y))
            }

            override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
                if (!currentEnabled || source != NestedScrollSource.UserInput || available.y <= 0f || !machine.canPull) return Offset.Zero
                if (machine.phase == PullToRefreshMachine.Phase.Settling) controller.job?.cancel()
                val pulled = machine.pull(available.y)
                if (pulled.crossedThreshold) OverlayHaptics.segmentTick(view)
                return Offset(0f, pulled.consumed)
            }

            override suspend fun onPreFling(available: Velocity): Velocity {
                if (!machine.isPulling) return Velocity.Zero
                release()
                return available
            }
        }
    }

    Box(
        modifier
            .nestedScroll(connection)
            .semantics {
                if (enabled) {
                    customActions = listOf(
                        CustomAccessibilityAction("Refresh") {
                            if (machine.phase == PullToRefreshMachine.Phase.Idle) {
                                machine.beginProgrammaticRefresh()
                                startRefresh()
                                true
                            } else {
                                false
                            }
                        },
                    )
                }
            },
    ) {
        Box(Modifier.graphicsLayer { translationY = machine.offset }) {
            content()
        }
        PullIndicator(machine, Modifier.align(Alignment.TopCenter).padding(top = indicatorTop))
    }
}

/** shell-chats §10.16 numbers. */
object PullToRefreshDefaults {
    /** Pull past this (list travel) and release to refresh. */
    val Threshold: Dp = 80.dp

    /** The list never travels further. */
    val MaxPull: Dp = 120.dp

    /** Where the list rests while refreshing. */
    val RefreshingHold: Dp = 56.dp

    /** The list moves this share of the finger's travel. */
    const val RESISTANCE = 0.5f

    val IndicatorSize: Dp = 20.dp
}

/**
 * The pull-to-refresh state machine, pure (px): Idle → Pulling ⇄ Armed → (release) Refreshing →
 * Settling → Idle; a release before the threshold goes Pulling → Settling → Idle.
 *
 * [offset] is how far the list is pulled down (finger travel × [resistance], capped at [maxPx]).
 * Backed by snapshot state so a composable can draw it; plain to drive from a test.
 */
@Stable
class PullToRefreshMachine(
    val thresholdPx: Float,
    val maxPx: Float,
    val holdPx: Float,
    val resistance: Float = PullToRefreshDefaults.RESISTANCE,
) {
    enum class Phase { Idle, Pulling, Armed, Refreshing, Settling }

    /** What a pull did: finger px used, and whether it just crossed the threshold (tick once). */
    data class Pull(val consumed: Float, val crossedThreshold: Boolean)

    var phase by mutableStateOf(Phase.Idle)
        private set
    var offset by mutableFloatStateOf(0f)
        private set

    /** A finger is pulling the list (not yet released). */
    val isPulling: Boolean get() = phase == Phase.Pulling || phase == Phase.Armed

    /** A new pull may start or continue: not while refreshing. */
    val canPull: Boolean get() = phase != Phase.Refreshing

    /** How far towards the threshold the pull is, 0…1 (the indicator's fill). */
    val progress: Float get() = if (thresholdPx <= 0f) 0f else min(1f, offset / thresholdPx)

    /** A downward finger move of [deltaPx] the list did not use (it is at its top). */
    fun pull(deltaPx: Float): Pull {
        if (deltaPx <= 0f || !canPull) return Pull(0f, false)
        val wasArmed = phase == Phase.Armed
        offset = min(maxPx, offset + deltaPx * resistance)
        phase = if (offset >= thresholdPx) Phase.Armed else Phase.Pulling
        return Pull(consumed = deltaPx, crossedThreshold = !wasArmed && phase == Phase.Armed)
    }

    /**
     * An upward finger move of [deltaPx] (< 0) while pulling: the list goes back up first. Returns
     * the finger px used (≤ 0); the rest scrolls the list.
     */
    fun retract(deltaPx: Float): Float {
        if (deltaPx >= 0f || !isPulling) return 0f
        val before = offset
        offset = max(0f, offset + deltaPx * resistance)
        phase = when {
            offset <= 0f -> Phase.Idle
            offset >= thresholdPx -> Phase.Armed
            else -> Phase.Pulling
        }
        return (offset - before) / resistance
    }

    /** The finger let go: true when that starts a refresh (the list then goes to [holdPx]). */
    fun release(): Boolean = when (phase) {
        Phase.Armed -> {
            phase = Phase.Refreshing
            true
        }
        Phase.Pulling -> {
            phase = Phase.Settling
            false
        }
        else -> false
    }

    /** A refresh not started by a pull (TalkBack's "Refresh" action). */
    fun beginProgrammaticRefresh() {
        if (phase == Phase.Idle) phase = Phase.Refreshing
    }

    /** The animation moved the list (towards [holdPx] or back to 0). */
    fun animateOffset(value: Float) {
        offset = max(0f, value)
    }

    /** [onRefresh][PullToRefresh] returned: the list springs back. */
    fun refreshFinished() {
        if (phase == Phase.Refreshing) phase = Phase.Settling
    }

    /** The list is back at rest. */
    fun settled() {
        if (phase == Phase.Settling) {
            offset = 0f
            phase = Phase.Idle
        }
    }
}

private class PullToRefreshController(val machine: PullToRefreshMachine) {
    var job: Job? = null
}

/**
 * The indicator, centred in the gap the pull opens: a ring that fills and turns with the pull,
 * fading in; the spinning [Spinner] while refreshing (`textSecondary`, 20 dp).
 */
@Composable
private fun PullIndicator(machine: PullToRefreshMachine, modifier: Modifier) {
    val colors = ShroudTheme.colors
    val density = LocalDensity.current
    val size = PullToRefreshDefaults.IndicatorSize
    Box(
        modifier
            .fillMaxWidth()
            .graphicsLayer {
                val sizePx = with(density) { size.toPx() }
                // Centre of the opened gap, never above its top.
                translationY = max(0f, (machine.offset - sizePx) / 2f)
                alpha = when (machine.phase) {
                    PullToRefreshMachine.Phase.Refreshing -> 1f
                    else -> machine.progress
                }
            },
        contentAlignment = Alignment.TopCenter,
    ) {
        if (machine.phase == PullToRefreshMachine.Phase.Refreshing) {
            Spinner(colors.textSecondary, size = size)
        } else if (machine.offset > 0f) {
            val color = colors.textSecondary
            Canvas(Modifier.size(size)) {
                val stroke = this.size.minDimension * 0.12f
                rotate(machine.progress * 270f) {
                    drawArc(
                        color = color,
                        startAngle = -90f,
                        sweepAngle = 300f * machine.progress,
                        useCenter = false,
                        style = Stroke(stroke, cap = StrokeCap.Round),
                    )
                }
            }
        }
    }
}
