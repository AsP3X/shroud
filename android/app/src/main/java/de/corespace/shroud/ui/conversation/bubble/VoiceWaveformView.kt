package de.corespace.shroud.ui.conversation.bubble

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Rounded waveform bars, [progress] (0…1) drawn in [playedColor] and the rest in [remainingColor]
 * (iOS `VoiceWaveformView`; C24). The voice bubble draws a note's stored waveform with it, the
 * composer's recording bar the live levels.
 *
 * **Entry-point stub (W2-INT seam, plan §1.7.13), owner W3-THREAD-BUBBLES**, which replaces the
 * body. Draws nothing until then.
 */
@Suppress("UNUSED_PARAMETER")
@Composable
fun VoiceWaveformView(
    samples: List<Float>,
    progress: Float,
    playedColor: Color,
    remainingColor: Color,
    modifier: Modifier = Modifier,
    barWidth: Dp = 3.dp,
    spacing: Dp = 2.dp,
    minHeight: Dp = 3.dp,
) {
}
