package de.corespace.shroud.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.dropShadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.shadow.Shadow
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import de.corespace.shroud.ui.theme.Motion
import de.corespace.shroud.ui.theme.ShroudTheme

/**
 * The app's switch (design `Toggle` `A6Obz`, design-inventory §3.5): a 63 × 28 track (radius 14,
 * padding 2) with a 39 × 24 white pill knob (radius 12, shadow `#0000001F` 0/2/6), `accent` when on,
 * `toggleOff` when off; knob and track ease with `Motion.snappy`. Not the Material switch. iOS
 * draws a `UISwitch` tinted with the accent.
 *
 * [enabled] false takes no taps (the row around it dims, settings-lock §2 `ToggleRow`).
 */
@Composable
fun ShroudToggle(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val colors = ShroudTheme.colors
    val track by animateColorAsState(if (checked) colors.accent else colors.toggleOff, Motion.snappy(), label = "toggleTrack")
    val knobX by animateDpAsState(if (checked) 20.dp else 0.dp, Motion.snappy(), label = "toggleKnob")
    Box(
        modifier
            .pressable(enabled = enabled, scale = 0.97f, role = Role.Switch, onClick = { onCheckedChange(!checked) })
            .semantics(mergeDescendants = true) {
                contentDescription = label
                stateDescription = if (checked) "On" else "Off"
            }
            .size(63.dp, 28.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(track)
            .padding(2.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        Box(
            Modifier
                .offset { IntOffset(knobX.roundToPx(), 0) }
                .dropShadow(RoundedCornerShape(12.dp), Shadow(radius = 6.dp, color = Color(0x1F000000), offset = DpOffset(0.dp, 2.dp)))
                .size(39.dp, 24.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(Color.White),
        )
    }
}
