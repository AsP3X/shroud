package de.corespace.shroud.ui.conversation

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import de.corespace.shroud.ui.components.ShroudText
import de.corespace.shroud.ui.theme.ShroudTheme
import de.corespace.shroud.ui.theme.inter

/**
 * The centred day label in the thread (`ChatDateChip`, `ChatDateChip.swift:3-21`; design `Date Chip`
 * in `Conversation` hc3Jf; conversation-thread §2.5): 12 Medium in `textPrimary` @ 0.6 — not
 * `textSecondary`, which is 2.6:1 on the darkened chip in light mode — padded 10 / 4 on a
 * `textPrimary` @ 0.06 capsule. A heading, so TalkBack can step day by day through a long thread.
 */
@Composable
fun ChatDateChip(label: String, modifier: Modifier = Modifier) {
    val colors = ShroudTheme.colors
    ShroudText(
        text = label,
        style = inter(12f, FontWeight.Medium),
        color = colors.textPrimary.copy(alpha = 0.6f),
        modifier = modifier
            .semantics { heading() }
            .clip(CircleShape)
            .background(colors.textPrimary.copy(alpha = 0.06f))
            .padding(horizontal = 10.dp, vertical = 4.dp),
        maxLines = 1,
    )
}
