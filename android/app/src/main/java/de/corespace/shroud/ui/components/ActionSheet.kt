package de.corespace.shroud.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.rememberTransition
import androidx.compose.animation.core.snap
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import de.corespace.shroud.ui.theme.Motion
import de.corespace.shroud.ui.theme.ShroudTheme
import de.corespace.shroud.ui.theme.inter

/** One button of an [ActionSheet]; [destructive] draws it in `danger`. */
data class ActionSheetItem(val title: String, val destructive: Boolean = false, val onClick: () -> Unit)

/**
 * A confirmation as a bottom action sheet — the Android form of iOS `confirmationDialog` and of the
 * destructive `.alert`s (00-plan C22, P13c; shell-chats §10.14, D7; settings-lock §2.4 S7). Used
 * for "Delete chat with {username}?" (`ChatsView.swift:200-217`), device removal
 * (`DevicesView.swift:85-109`), Reset QR code (`PrivacySecurityView.swift:146-152`) and the
 * message delete dialog.
 *
 * Human: A card of buttons rises from the bottom over a dim — the [title] and [message] on top in
 * small grey type, the [items] below (destructive ones in red), and a separate Cancel card under
 * it. Unlike iOS 26's anchored dialog, Cancel always shows (memory
 * `ios26-confirmationdialog-hides-cancel`). Back, predictive back, a tap on the dim or Cancel
 * close it without acting.
 *
 * Agent: [visible] is the caller's state; [onDismiss] asks the caller to hide it. A tap on an item
 * calls [onDismiss] first, then the item's `onClick` (iOS dismisses the dialog before the
 * action runs, `ChatsView.swift:312-335`). The last shown title/message/items stay on screen while
 * it animates out, so the caller may clear its pending state in [onDismiss]. Drawn in the
 * [OverlayHost] layer. Card fills are the near-opaque card colours (no backdrop blur here).
 */
@Composable
fun ActionSheet(
    visible: Boolean,
    title: String? = null,
    message: String? = null,
    items: List<ActionSheetItem>,
    onDismiss: () -> Unit,
    cancelTitle: String = "Cancel",
) {
    val visibility = rememberOverlayTransition(visible)
    val currentOnDismiss by rememberUpdatedState(onDismiss)
    // What was last shown, kept for the exit animation (not state: written while visible only).
    val shown = remember { ShownActionSheet() }
    if (visible) shown.content = ActionSheetContent(title, message, items, cancelTitle)

    OverlayLayer(active = visibility.isOverlayUp, onDismissRequest = { if (visibility.targetState) currentOnDismiss() }) {
        val content = shown.content ?: return@OverlayLayer
        val palette = ShroudTheme.colors
        val reduceMotion = ShroudTheme.reduceMotion
        val transition = rememberTransition(visibility, label = "actionSheet")
        val back by rememberOverlayBack(enabled = visible) { currentOnDismiss() }
        val dismiss: () -> Unit = { if (visibility.targetState) currentOnDismiss() }

        Box(Modifier.fillMaxSize().overlayPane(content.title ?: "Options", onDismiss = dismiss)) {
            transition.AnimatedVisibility(visible = { it }, enter = fadeIn(Motion.scrim()), exit = fadeOut(Motion.scrim())) {
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(palette.sheetScrim)
                        .dismissOnTap(openedAt = 0L, onDismiss = dismiss),
                )
            }
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
                transition.AnimatedVisibility(
                    visible = { it },
                    enter = actionSheetEnter(reduceMotion),
                    exit = actionSheetExit(reduceMotion),
                ) {
                    Column(
                        Modifier
                            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Top))
                            .padding(horizontal = ActionSheetMetrics.Margin, vertical = ActionSheetMetrics.Margin)
                            .windowInsetsPadding(WindowInsets.navigationBars.only(WindowInsetsSides.Bottom))
                            .widthIn(max = ActionSheetMetrics.MaxWidth)
                            .fillMaxWidth()
                            .graphicsLayer {
                                val lean = backLeanScale(back, maxShrink = 0.04f)
                                scaleX = lean
                                scaleY = lean
                            },
                        verticalArrangement = Arrangement.spacedBy(ActionSheetMetrics.Margin),
                    ) {
                        ActionSheetCard(Modifier.weight(1f, fill = false)) {
                            val hasHeader = content.title != null || content.message != null
                            if (hasHeader) {
                                ActionSheetHeader(content.title, content.message)
                            }
                            content.items.forEachIndexed { index, item ->
                                if (hasHeader || index > 0) ActionSheetDivider()
                                ActionSheetButton(
                                    title = item.title,
                                    color = if (item.destructive) ShroudTheme.colors.danger else ShroudTheme.colors.accent,
                                    weight = FontWeight.Normal,
                                    onClick = {
                                        if (visibility.targetState) {
                                            currentOnDismiss()
                                            item.onClick()
                                        }
                                    },
                                )
                            }
                        }
                        ActionSheetCard {
                            ActionSheetButton(
                                title = content.cancelTitle,
                                color = ShroudTheme.colors.accent,
                                weight = FontWeight.SemiBold,
                                onClick = dismiss,
                            )
                        }
                    }
                }
            }
        }
    }
}

/** Sizes of shell-chats §10.14. */
internal object ActionSheetMetrics {
    val Margin = 8.dp
    val Radius = 22.dp
    val ButtonHeight = 56.dp

    /** Widest the cards get on large windows (centred); phones are narrower. */
    val MaxWidth = 480.dp
}

@Immutable
private data class ActionSheetContent(
    val title: String?,
    val message: String?,
    val items: List<ActionSheetItem>,
    val cancelTitle: String,
)

private class ShownActionSheet {
    var content: ActionSheetContent? = null
}

private fun actionSheetEnter(reduceMotion: Boolean): EnterTransition =
    if (reduceMotion) fadeIn(Motion.reduced()) else slideInVertically(Motion.gentle()) { it } + fadeIn(Motion.fade())

private fun actionSheetExit(reduceMotion: Boolean): ExitTransition =
    if (reduceMotion) fadeOut(Motion.reduced()) else slideOutVertically(Motion.standard()) { it } + fadeOut(Motion.fade())

@Composable
private fun ActionSheetCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    val shape = RoundedCornerShape(ActionSheetMetrics.Radius)
    Column(
        modifier
            .fillMaxWidth()
            .clip(shape)
            .background(ShroudTheme.colors.cardOpaque)
            .verticalScroll(rememberScrollState()),
        content = content,
    )
}

/** Title 13 SemiBold and message 13, both `textSecondary`, centred, padding 16. */
@Composable
private fun ActionSheetHeader(title: String?, message: String?) {
    val colors = ShroudTheme.colors
    Column(
        Modifier.fillMaxWidth().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (title != null) {
            ShroudText(
                text = title,
                style = inter(13f, FontWeight.SemiBold),
                color = colors.textSecondary,
                textAlign = TextAlign.Center,
                modifier = Modifier.semantics { heading() },
            )
        }
        if (message != null) {
            ShroudText(text = message, style = inter(13f), color = colors.textSecondary, textAlign = TextAlign.Center)
        }
    }
}

@Composable
private fun ActionSheetDivider() {
    Box(Modifier.fillMaxWidth().height(0.5.dp).background(ShroudTheme.colors.separator))
}

/**
 * A 56 dp button row, 20 sp; the press highlight lands at once and fades out
 * (`HighlightRowButtonStyle`, `Motion.swift:100-120`).
 */
@Composable
private fun ActionSheetButton(title: String, color: Color, weight: FontWeight, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val highlight by animateFloatAsState(
        targetValue = if (pressed) 1f else 0f,
        animationSpec = if (pressed) snap() else Motion.fade(),
        label = "actionSheetHighlight",
    )
    val pressedFill = ShroudTheme.colors.rowPressed
    Box(
        Modifier
            .fillMaxWidth()
            .heightIn(min = ActionSheetMetrics.ButtonHeight)
            .background(pressedFill.copy(alpha = pressedFill.alpha * highlight))
            .clickable(interactionSource = interaction, indication = null, role = Role.Button, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        ShroudText(text = title, style = inter(20f, weight), color = color, textAlign = TextAlign.Center)
    }
}

@Preview(name = "Action sheet", widthDp = 412, heightDp = 915)
@Composable
private fun ActionSheetPreview() {
    ShroudTheme(dark = false) {
        Box(Modifier.fillMaxSize().background(ShroudTheme.colors.background)) {
            ActionSheet(
                visible = true,
                title = "Delete chat with jane_cooper?",
                message = "Deleting for both unsends your messages in jane_cooper's chat. Their own messages stay unless they allow chats to be cleared for them. They stay in your contacts.",
                items = listOf(
                    ActionSheetItem("Delete for me and jane_cooper", destructive = true) {},
                    ActionSheetItem("Delete for me", destructive = true) {},
                ),
                onDismiss = {},
            )
        }
    }
}
