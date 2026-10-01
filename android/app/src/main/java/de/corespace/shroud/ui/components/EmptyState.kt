package de.corespace.shroud.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import de.corespace.shroud.ui.theme.Motion
import de.corespace.shroud.ui.theme.ShroudIcons
import de.corespace.shroud.ui.theme.ShroudTheme
import de.corespace.shroud.ui.theme.inter

/**
 * A list's empty state, when the load worked and there is nothing to show (`ChatsView.swift:371-406`,
 * `CallsView.swift:120-140`; design `Calls — Empty` `uof7e`): centred `Column(spacing 12)`, full
 * width, padding top 48, h 24 —
 *
 * - [icon] 34 dp `accent @ 0.85`, padding bottom 4, decorative, with a one-shot bounce on arrival
 *   (iOS `.symbolEffect(.bounce, options: .nonRepeating)`; none under reduce motion);
 * - [title] 16 sp SemiBold `textPrimary`;
 * - [message] 14 sp `textSecondary`, centred;
 * - an optional [action] 4 dp further down (Chats' "New Chat", [EmptyStateButton]).
 *
 * Show and hide it with [ListStateTransitions] (fade + 8 dp rise, `ChatsView.swift:405`). Never
 * stands in for a failed load: that is [ListLoadError].
 */
@Composable
fun EmptyState(
    icon: ImageVector,
    title: String,
    message: String?,
    modifier: Modifier = Modifier,
    action: (@Composable () -> Unit)? = null,
) {
    val colors = ShroudTheme.colors
    val reduceMotion = ShroudTheme.reduceMotion
    val bounce = remember { Animatable(if (reduceMotion) 1f else EmptyStateSpec.BOUNCE_FROM) }
    LaunchedEffect(Unit) {
        if (bounce.value != 1f) bounce.animateTo(1f, Motion.bouncy())
    }
    Column(
        modifier
            .fillMaxWidth()
            .padding(top = 48.dp, start = 24.dp, end = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        ShroudIcon(
            icon,
            colors.accent.copy(alpha = 0.85f),
            Modifier
                .padding(bottom = 4.dp)
                .clearAndSetSemantics {}
                .graphicsLayer {
                    scaleX = bounce.value
                    scaleY = bounce.value
                },
            size = EmptyStateSpec.iconSize,
        )
        ShroudText(title, inter(16f, FontWeight.SemiBold), colors.textPrimary, textAlign = TextAlign.Center)
        if (message != null) {
            ShroudText(message, inter(14f), colors.textSecondary, textAlign = TextAlign.Center)
        }
        if (action != null) {
            Box(Modifier.padding(top = 4.dp)) { action() }
        }
    }
}

/**
 * The text button under an [EmptyState] (`ChatsView.swift:392-399`): [title] 16 sp SemiBold
 * `accent`, pressable 0.94, 44 dp high.
 */
@Composable
fun EmptyStateButton(title: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier
            .heightIn(min = 44.dp)
            .pressable(scale = 0.94f, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        ShroudText(title, inter(16f, FontWeight.SemiBold), ShroudTheme.colors.accent, maxLines = 1)
    }
}

/** Numbers of [EmptyState]. */
object EmptyStateSpec {
    val iconSize = 34.dp

    /** The arrival bounce starts the glyph at this scale and springs it to 1 with `Motion.bouncy`. */
    const val BOUNCE_FROM = 0.8f
}

@Preview(name = "Empty state · 412", widthDp = 412)
@Composable
private fun EmptyStatePreview() {
    ShroudTheme(dark = false) {
        EmptyState(
            ShroudIcons.PhoneFill,
            "No calls yet",
            "Your recent calls show up here. To call someone, open their chat or profile and tap Call or Video.",
            Modifier.background(ShroudTheme.colors.background),
        )
    }
}

@Preview(name = "Empty state · 360 · dark", widthDp = 360)
@Composable
private fun EmptyStateDarkPreview() {
    ShroudTheme(dark = true) {
        EmptyState(
            ShroudIcons.LockFill,
            "No chats yet",
            "Message a contact to start a conversation.",
            Modifier.background(ShroudTheme.colors.background),
        ) {
            EmptyStateButton("New Chat", {})
        }
    }
}
