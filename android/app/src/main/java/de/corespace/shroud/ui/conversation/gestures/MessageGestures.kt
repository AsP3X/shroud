package de.corespace.shroud.ui.conversation.gestures

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.toSize

/**
 * One press on a thread row, shared by its long press, taps and swipe to reply (iOS `PressMemory`,
 * `MessageLongPressGesture.swift:146-152`). Not snapshot state: it changes on every touch and nothing
 * draws from it.
 */
class RowPress {
    /** This press already opened the menu: its release and trailing tap must do nothing else. */
    var longPressed: Boolean = false

    /** A swipe to reply took this press: the hold must not open the menu under it. */
    var swiping: Boolean = false

    /** The row's frame in root px, unclipped (the menu's hero flies from it, CV:921-945). */
    var boundsInRoot: Rect = Rect.Zero
}

/** The hold that opens a message's menu (`messageContextLongPress(minimumDuration: 0.25)`, CV:921-923). */
const val MESSAGE_LONG_PRESS_MS = 250L

/**
 * A thread row's press handling (`MessageContextLongPress`, `MessageLongPressGesture.swift:64-219`;
 * conversation-thread §13).
 *
 * Human: holding a row for 0.25 s opens its menu with the row's fresh frame; the hold never blocks a
 * scroll that starts on a bubble (memory *LongPress onChanged blocks scroll*: nothing is consumed
 * before the hold is recognised), never steals taps from the bubble's own controls, and its release
 * does not fire anything under the finger (memory *Hold release fires bubble controls*): once the
 * menu opened, the rest of the gesture is swallowed before any child sees it. A single tap
 * ([onTap], photo and video rows) and a double tap ([onDoubleTap], text rows that can take a
 * reaction) run only when no inner control handled the touch — a control that consumed the release
 * stops them, and one that only [TapClaim.claim]s is checked a main-loop turn later (memory *Row tap
 * fires before inner controls*).
 *
 * Agent: [onLongPress] gets the row's unclipped root frame (px) and runs on the gesture thread
 * (main); [onHoldReleased] runs when the finger that opened the menu lifts. [onDoubleTap] gets the
 * second tap's point in root px. Disabled ([enabled] false — the row whose menu is open): no
 * detection at all. No `combinedClickable` (its 400 ms timeout consumes, and it would claim the row).
 */
fun Modifier.messageGestures(
    press: RowPress,
    claim: TapClaim,
    enabled: Boolean,
    onLongPress: (rowBoundsInRoot: Rect) -> Unit,
    onHoldReleased: () -> Unit,
    onTap: (() -> Unit)?,
    onDoubleTap: ((pointInRoot: Offset) -> Unit)?,
): Modifier = composed {
    val view = LocalView.current
    val longPress by rememberUpdatedState(onLongPress)
    val released by rememberUpdatedState(onHoldReleased)
    val tap by rememberUpdatedState(onTap)
    val doubleTap by rememberUpdatedState(onDoubleTap)
    val hasTap = onTap != null
    val hasDoubleTap = onDoubleTap != null
    this
        .onGloballyPositioned { coordinates ->
            // Unclipped: a row half under the bar still lifts from where it really is.
            press.boundsInRoot = Rect(coordinates.positionInRoot(), coordinates.size.toSize())
        }
        .pointerInput(enabled) {
            if (!enabled) return@pointerInput
            awaitEachGesture {
                // Initial pass, nothing consumed: children and the list see the touch exactly as before.
                val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                // Every new press starts clean (iOS clears `PressMemory` on touch-down, MLP:110-112).
                press.longPressed = false
                press.swiping = false
                val slop = viewConfiguration.touchSlop
                // Non-null when the touch lifted, moved past the slop or became a pinch before 0.25 s.
                val endedEarly = withTimeoutOrNull(MESSAGE_LONG_PRESS_MS) {
                    var ended = false
                    while (!ended) {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        val change = event.changes.firstOrNull { it.id == down.id }
                        ended = change == null ||
                            !change.pressed ||
                            event.changes.count { it.pressed } > 1 ||
                            (change.position - down.position).getDistance() > slop
                    }
                    true
                }
                if (endedEarly != null || press.swiping) return@awaitEachGesture
                // Held still for 0.25 s: the menu opens, and the rest of this touch belongs to it.
                press.longPressed = true
                longPress(press.boundsInRoot)
                do {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    event.changes.forEach { it.consume() }
                } while (event.changes.any { it.pressed })
                released()
            }
        }
        .then(
            if (!enabled || (!hasTap && !hasDoubleTap)) {
                Modifier
            } else {
                Modifier.pointerInput(hasTap, hasDoubleTap) {
                    awaitEachGesture {
                        // Main pass: the bubble's own controls had the touch first.
                        awaitFirstDown(requireUnconsumed = false)
                        // Null when an inner control consumed the release, the list scrolled, or the hold swallowed it.
                        val up = waitForUpOrCancellation() ?: return@awaitEachGesture
                        if (press.longPressed || press.swiping) return@awaitEachGesture
                        val onDouble = doubleTap
                        if (onDouble == null) {
                            view.post { if (!claim.isClaimed()) tap?.invoke() }
                            return@awaitEachGesture
                        }
                        // Text rows: only a second tap acts (no single-tap delay is added anywhere else).
                        val second = withTimeoutOrNull(viewConfiguration.doubleTapTimeoutMillis) {
                            awaitFirstDown(requireUnconsumed = false)
                        } ?: return@awaitEachGesture
                        if ((second.position - up.position).getDistance() > viewConfiguration.touchSlop * 4) return@awaitEachGesture
                        val secondUp = waitForUpOrCancellation() ?: return@awaitEachGesture
                        if (press.longPressed || press.swiping) return@awaitEachGesture
                        val point = press.boundsInRoot.topLeft + secondUp.position
                        view.post { if (!claim.isClaimed()) doubleTap?.invoke(point) }
                    }
                }
            },
        )
}
