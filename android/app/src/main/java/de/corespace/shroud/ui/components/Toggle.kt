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
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import de.corespace.shroud.ui.theme.Motion
import de.corespace.shroud.ui.theme.ShroudTheme

/**
 * The app's switch (`Toggle` in the design): a 63 × 28 track with a 39 × 24 pill knob, accent
 * when on. Not the Material switch.
 */
@Composable
fun ShroudToggle(checked: Boolean, onCheckedChange: (Boolean) -> Unit, label: String, modifier: Modifier = Modifier) {
    val colors = ShroudTheme.colors
    val track by animateColorAsState(if (checked) colors.accent else colors.toggleOff, Motion.snappy(), label = "toggleTrack")
    val knobX by animateDpAsState(if (checked) 20.dp else 0.dp, Motion.snappy(), label = "toggleKnob")
    Box(
        modifier
            .pressable(scale = 0.97f, role = Role.Switch, onClick = { onCheckedChange(!checked) })
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
                .shadow(3.dp, RoundedCornerShape(12.dp), ambientColor = Color(0x1F000000), spotColor = Color(0x1F000000))
                .size(39.dp, 24.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(Color.White),
        )
    }
}
