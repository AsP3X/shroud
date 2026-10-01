package de.corespace.shroud.ui.components

import androidx.compose.animation.core.EaseOut
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import de.corespace.shroud.ui.theme.ShroudTheme

/**
 * Live presence (`PresenceDot.swift:10-50`; shell-chats §10.3, contacts §5.9, conversation-thread
 * §12.4): a solid `online` dot that, unless reduce motion is on, emits a slow halo — the same
 * circle growing to 2.6× while fading 0.45 → 0 over 1.6 s ease-out, then resetting invisibly after
 * a 0.5 s pause. Deliberately slow and low-contrast so it reads as "connected". Decorative.
 * The halo draws outside the dot's bounds (no layout space).
 */
@Composable
fun PresenceDot(size: Dp = 7.dp, modifier: Modifier = Modifier) {
    val color = ShroudTheme.colors.online
    val pulses = !ShroudTheme.reduceMotion
    val clock = if (pulses) {
        val t by rememberInfiniteTransition(label = "presenceHalo").animateFloat(
            initialValue = 0f,
            targetValue = PresenceHalo.CYCLE_MS.toFloat(),
            animationSpec = infiniteRepeatable(tween(PresenceHalo.CYCLE_MS, easing = LinearEasing), RepeatMode.Restart),
            label = "presenceHaloTime",
        )
        { t }
    } else {
        null
    }
    Canvas(modifier.clearAndSetSemantics {}.size(size)) {
        val radius = this.size.minDimension / 2
        if (clock != null) {
            val frame = PresenceHalo.frame(clock())
            if (frame.alpha > 0f) drawCircle(color.copy(alpha = frame.alpha), radius = radius * frame.scale)
        }
        drawCircle(color, radius = radius)
    }
}

/**
 * The halo's two-phase loop as a pure function of time (`PresenceDot.swift:35-49`):
 * 0–1600 ms ease-out from (scale 1, alpha 0.45) to (2.6, 0); held invisible until 2100 ms; a
 * 10 ms linear reset back to (1, 0.45).
 */
object PresenceHalo {
    const val EXPAND_MS = 1600
    const val PAUSE_MS = 500
    const val RESET_MS = 10
    const val CYCLE_MS = EXPAND_MS + PAUSE_MS + RESET_MS

    data class Frame(val scale: Float, val alpha: Float)

    fun frame(tMillis: Float): Frame {
        val t = tMillis.mod(CYCLE_MS.toFloat())
        return when {
            t < EXPAND_MS -> {
                val p = EaseOut.transform(t / EXPAND_MS)
                Frame(1f + 1.6f * p, 0.45f * (1f - p))
            }
            t < EXPAND_MS + PAUSE_MS -> Frame(2.6f, 0f)
            else -> {
                val p = (t - EXPAND_MS - PAUSE_MS) / RESET_MS
                Frame(2.6f - 1.6f * p, 0.45f * p)
            }
        }
    }
}

@Preview(name = "Presence dot")
@Composable
private fun PresenceDotPreview() {
    ShroudTheme(dark = false) {
        Row(Modifier.background(ShroudTheme.colors.background).padding(40.dp), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
            PresenceDot()
            PresenceDot(size = 12.dp)
        }
    }
}
