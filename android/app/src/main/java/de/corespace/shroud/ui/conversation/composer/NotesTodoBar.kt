package de.corespace.shroud.ui.conversation.composer

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import de.corespace.shroud.core.model.Haptic
import de.corespace.shroud.ui.components.GlassStyle
import de.corespace.shroud.ui.components.ShroudIcon
import de.corespace.shroud.ui.components.ShroudText
import de.corespace.shroud.ui.components.glassSurface
import de.corespace.shroud.ui.components.pressable
import de.corespace.shroud.ui.theme.ShroudIcons
import de.corespace.shroud.ui.theme.ShroudTheme
import de.corespace.shroud.ui.theme.inter

/**
 * Notes' toolbar above the composer, in the same bottom bar (iOS `notesToolbar`,
 * `ConversationView.swift:1694-1721`; design nPQKZ "Todo Bar"; conversation-compose-media §3.8): one
 * small glass capsule, "Todo", that turns the draft into a checklist item. The capsule is 36 dp high;
 * its touch target reaches 48 dp without moving it (plan §2.0 rule 8).
 */
@Composable
internal fun NotesTodoBar(onTodo: () -> Unit, modifier: Modifier = Modifier) {
    val accent = ShroudTheme.colors.accent
    Row(
        modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            Modifier
                .outsetTouch(horizontal = 0.dp, vertical = TODO_TOUCH_OUTSET)
                // `PressableButtonStyle(scale: 1, dimming: 0)`: the interactive glass is the feedback, plus the tick.
                .pressable(scale = 1f, dimming = 0f, haptic = Haptic.Light, onClick = onTodo)
                .padding(vertical = TODO_TOUCH_OUTSET)
                .height(TODO_HEIGHT)
                .glassSurface(CapsuleShape, GlassStyle.Regular, interactive = true)
                .padding(horizontal = 14.dp)
                // The clickable above carries the role and the action; this names it.
                .clearAndSetSemantics { contentDescription = "Add as todo" },
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ShroudIcon(ShroudIcons.ListChecks, accent, size = 16.dp)
            ShroudText("Todo", inter(14f, FontWeight.SemiBold), accent, maxLines = 1)
        }
    }
}

/** `.frame(height: 36)` (`ConversationView.swift:1711`). */
private val TODO_HEIGHT = 36.dp

/** (48 − 36) / 2: the capsule's touch target grows to 48 dp. */
private val TODO_TOUCH_OUTSET = 6.dp
