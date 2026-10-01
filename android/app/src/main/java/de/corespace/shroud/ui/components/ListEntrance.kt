package de.corespace.shroud.ui.components

import android.os.SystemClock
import androidx.compose.animation.core.Animatable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import de.corespace.shroud.ui.theme.Motion
import de.corespace.shroud.ui.theme.ShroudTheme
import kotlin.math.max
import kotlin.math.min
import kotlinx.coroutines.delay

/**
 * The timing of the staggered list entrance (`Motion.swift:133-214`; shell-chats §10.12), pure so
 * it is tested: rows fade and lift 10 dp into place only during a 600 ms window after the list
 * appeared (or its key changed), each one 30 ms after the one above, rows past the eighth sharing
 * the last delay so a long list never queues seconds of motion.
 */
object ListEntranceTiming {
    /** How long after the list's start rows may still animate in (`Motion.swift:176`). */
    const val WINDOW_MS = 600L

    /** Delay between neighbouring rows (`Motion.swift:174`). */
    const val STEP_MS = 30L

    /** Rows past this index share the last delay (`Motion.swift:173`). */
    const val STAGGER_LIMIT = 8

    /** Lift of an entering row (`Motion.swift:186`); none under reduce motion. */
    const val LIFT_DP = 10f

    /** Whether a row first shown at [nowMillis] animates in a list that started at [startMillis] (`Motion.swift:191-196`). */
    fun animates(nowMillis: Long, startMillis: Long): Boolean = nowMillis - startMillis < WINDOW_MS

    /** Delay before row [index] starts (`Motion.swift:197`): `min(index, 8) × 30 ms`. */
    fun delayMillis(index: Int): Long = min(max(index, 0), STAGGER_LIMIT) * STEP_MS
}

/** The entrance of one list: when it started and which rows already showed (so recycled items never replay). */
@Stable
class ListEntranceState internal constructor(val startMillis: Long) {
    private val shown = HashSet<Int>()

    /** True the first time row [index] asks; later appearances of the same row appear at once. */
    internal fun claim(index: Int): Boolean = shown.add(index)
}

private val LocalListEntrance = staticCompositionLocalOf<ListEntranceState?> { null }

/**
 * Hosts a staggered row entrance (`Motion.swift:154-166`, `listEntranceHost(resetOn:)`): the
 * entrance window starts when this first composes and again whenever [key] changes — pass the
 * moment loaded data replaces an empty list (e.g. "has loaded"). Rows inside opt in with
 * [entranceRow]; rows scrolled into view later just appear.
 */
@Composable
fun ListEntranceHost(key: Any, content: @Composable () -> Unit) {
    val state = remember(key) { ListEntranceState(SystemClock.uptimeMillis()) }
    CompositionLocalProvider(LocalListEntrance provides state, content = content)
}

/**
 * Fades a row in and lifts it 10 dp into place, staggered by its [index] (`Motion.swift:168-202`):
 * `Motion.standard` (reduce motion: `Motion.reduced`, no lift) after
 * [ListEntranceTiming.delayMillis]. Only inside the host's window and only the first time the row
 * shows; otherwise, or without a [ListEntranceHost], it does nothing.
 */
fun Modifier.entranceRow(index: Int): Modifier = composed {
    val host = LocalListEntrance.current
    val reduceMotion = ShroudTheme.reduceMotion
    // Decided once, when the row first shows (iOS `onAppear`): a later key change never makes a
    // row that is already on screen enter again.
    val animates = remember {
        host != null && host.claim(index) && ListEntranceTiming.animates(SystemClock.uptimeMillis(), host.startMillis)
    }
    if (!animates) return@composed this
    val progress = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        delay(ListEntranceTiming.delayMillis(index))
        progress.animateTo(1f, Motion.respecting(reduceMotion, Motion.standard()))
    }
    this.graphicsLayer {
        val p = progress.value
        alpha = p.coerceIn(0f, 1f)
        translationY = if (reduceMotion) 0f else (1f - p) * ListEntranceTiming.LIFT_DP.dp.toPx()
    }
}
