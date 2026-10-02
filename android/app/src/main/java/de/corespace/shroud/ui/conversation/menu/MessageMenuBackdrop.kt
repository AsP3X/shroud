package de.corespace.shroud.ui.conversation.menu

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.semantics.clearAndSetSemantics

/**
 * The dim veil behind the long-press menu, driven by one continuous [progress]
 * (`MessageMenuBackdrop`, `MessageActionMenu.swift:833-853`; conversation-thread §16.5): two solid
 * layers, black @ 0.42·progress and `rgb(15,15,20)` @ 0.28·progress, full window. Solid on purpose —
 * no blur (decision D5: a material's opacity animation stutters on dismiss).
 *
 * A tap calls [onTap] (the host ignores the release of the opening hold); it only takes touches once
 * [progress] is above 0.05. Decorative for TalkBack: the overlay's pane carries the dismiss action.
 */
@Composable
fun MessageMenuBackdrop(onTap: () -> Unit, progress: Float, modifier: Modifier = Modifier) {
    val p = progress.coerceIn(0f, 1f)
    Box(
        modifier
            .fillMaxSize()
            .clearAndSetSemantics {}
            .background(Color.Black.copy(alpha = 0.42f * p))
            .background(BackdropTint.copy(alpha = 0.28f * p))
            .then(if (progress > 0.05f) Modifier.tapsGoTo(onTap) else Modifier),
    )
}

/** `Color(red: 0.06, green: 0.06, blue: 0.08)` (MAM:846). */
private val BackdropTint = Color(red = 0.06f, green = 0.06f, blue = 0.08f)

/**
 * Takes every touch on this element away from its children and turns a tap into [onTap] — the
 * Android form of SwiftUI's `.allowsHitTesting(false)` over the menu's backdrop: in Compose a touch
 * never falls through to a lower sibling, so a surface that must not be pressed (the lifted bubble,
 * a card that is still fading in, the scrolled stack's empty space) hands its taps to the backdrop
 * itself. Consumes in the initial pass, so nothing inside fires on release.
 */
internal fun Modifier.tapsGoTo(onTap: () -> Unit): Modifier = composed {
    val current by rememberUpdatedState(onTap)
    val slop = LocalViewConfiguration.current.touchSlop
    pointerInput(Unit) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            down.consume()
            var moved = 0f
            var tapped = false
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                val change = event.changes.firstOrNull { it.id == down.id }
                event.changes.forEach { it.consume() }
                if (change == null) break
                moved += change.positionChange().getDistance()
                if (!change.pressed) {
                    tapped = moved <= slop
                    break
                }
            }
            if (tapped) current()
        }
    }
}
