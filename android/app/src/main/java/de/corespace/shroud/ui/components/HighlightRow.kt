package de.corespace.shroud.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import de.corespace.shroud.core.model.Haptic
import de.corespace.shroud.ui.theme.Motion
import de.corespace.shroud.ui.theme.ShroudTheme
import kotlinx.coroutines.launch

/**
 * Full-width list row press state, iOS `HighlightRowButtonStyle` (`Motion.swift:100-120`;
 * shell-chats §10.11): the [fill] lands **instantly** on press-down and fades out with
 * `Motion.fade()` on release; [haptic] ticks on press-down; no scale (scaling a row that spans the
 * screen reads as a glitch) and no ripple.
 *
 * [fill] defaults to `backgroundGrouped`; New Chat rows pass `rowPressed` (the default equals the
 * sheet's dark row colour there, `NewChatSheet.swift:77-81`). A long press ([onLongClick]) also
 * plays the platform long-press haptic (`combinedClickable`'s own, the `LONG_PRESS` constant of
 * the §1.7.12 table). Disabled rows neither highlight nor tick.
 */
@OptIn(ExperimentalFoundationApi::class)
fun Modifier.highlightRow(
    onClick: () -> Unit,
    haptic: Haptic = Haptic.Light,
    enabled: Boolean = true,
    fill: Color? = null,
    onLongClick: (() -> Unit)? = null,
    onClickLabel: String? = null,
    onLongClickLabel: String? = null,
    role: Role? = Role.Button,
): Modifier = composed {
    val interaction = remember { MutableInteractionSource() }
    val highlight = remember { Animatable(0f) }
    val color = fill ?: ShroudTheme.colors.backgroundGrouped
    val view = LocalView.current
    val scope = rememberCoroutineScope()
    val currentHaptic = rememberUpdatedState(haptic)
    val currentEnabled = rememberUpdatedState(enabled)
    // Each interaction is handled in order: a quick tap inside a scrolling list delivers its
    // press and release in the same frame, which a pressed *state* would never show.
    LaunchedEffect(interaction) {
        interaction.interactions.collect { event ->
            when (event) {
                is PressInteraction.Press -> if (currentEnabled.value) {
                    highlight.snapTo(1f)
                    view.perform(currentHaptic.value)
                }
                is PressInteraction.Release, is PressInteraction.Cancel ->
                    scope.launch { highlight.animateTo(0f, Motion.fade()) }
            }
        }
    }
    this
        .drawBehind {
            val a = highlight.value
            if (a > 0f) drawRect(color.copy(alpha = color.alpha * a))
        }
        .combinedClickable(
            interactionSource = interaction,
            indication = null,
            enabled = enabled,
            onClickLabel = onClickLabel,
            role = role,
            onLongClickLabel = onLongClickLabel,
            onLongClick = onLongClick,
            onClick = onClick,
        )
}
