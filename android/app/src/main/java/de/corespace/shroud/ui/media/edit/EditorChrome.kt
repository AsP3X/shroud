package de.corespace.shroud.ui.media.edit

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.selection.LocalTextSelectionColors
import androidx.compose.foundation.text.selection.TextSelectionColors
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import de.corespace.shroud.core.model.Haptic
import de.corespace.shroud.ui.components.OverlayLayer
import de.corespace.shroud.ui.components.ShroudIcon
import de.corespace.shroud.ui.components.ShroudText
import de.corespace.shroud.ui.components.isOverlayUp
import de.corespace.shroud.ui.components.pressable
import de.corespace.shroud.ui.components.rememberOverlayTransition
import de.corespace.shroud.ui.theme.MediaColors
import de.corespace.shroud.ui.theme.Motion
import de.corespace.shroud.ui.theme.ShroudTheme
import de.corespace.shroud.ui.theme.inter
import de.corespace.shroud.ui.theme.rememberHaptics

/*
 * Pieces every photo editor shares (crop, draw, text; conversation-compose-media §11.5, §12.1,
 * §13.4). iOS repeats them in each editor (`MediaCropEditor.swift:461-564`,
 * `MediaDrawEditor.swift:101-197`, `MediaTextEditor.swift:312-434`); the numbers are the same in all three.
 */

/** iOS `.pressable(scale: 0.88, dimming: 0)` on an editor tool. */
private const val TOOL_PRESS_SCALE = 0.88f

/** iOS `.pressable(scale: 0.92, dimming: 0, haptic: nil)` on Cancel / Done. */
private const val CANCEL_DONE_PRESS_SCALE = 0.92f

/**
 * A round editor tool with its caption: a 44 dp `chrome` circle holding an 18 dp white glyph, and
 * an 11 sp medium label in white 70 % under it. Disabled tools dim the glyph to 35 % and the label
 * to 30 % and take no taps (`toolButton`, `MediaDrawEditor.swift:173-197`). [selected] is read to
 * TalkBack (Draw's Tools while the palette is open).
 */
@Composable
internal fun EditorToolButton(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    selected: Boolean = false,
    stateDescription: String? = null,
) {
    Column(
        modifier
            .pressable(enabled = enabled, scale = TOOL_PRESS_SCALE, dimming = 0f, haptic = Haptic.Light, onClick = onClick)
            .semantics(mergeDescendants = true) {
                contentDescription = label
                if (selected) this.selected = true
                if (stateDescription != null) this.stateDescription = stateDescription
            },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Box(Modifier.size(44.dp).clip(CircleShape).background(MediaColors.chrome), contentAlignment = Alignment.Center) {
            ShroudIcon(icon, tint = Color.White.copy(alpha = if (enabled) 1f else 0.35f), size = 18.dp)
        }
        ShroudText(
            label,
            inter(11f, FontWeight.Medium),
            Color.White.copy(alpha = if (enabled) 0.7f else 0.3f),
            Modifier.clearAndSetSemantics {},
        )
    }
}

/**
 * "Cancel" (17 sp semibold white, light haptic) on the left and "Done" (17 sp bold `blue`, medium
 * haptic) on the right, each 44 dp tall with 8 dp side padding, in a row padded 20 dp
 * (`MediaCropEditor.swift:506-536`).
 */
@Composable
internal fun EditorCancelDoneRow(onCancel: () -> Unit, onDone: () -> Unit, modifier: Modifier = Modifier) {
    val haptic = rememberHaptics()
    Row(modifier.fillMaxWidth().padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
        EditorTextButton("Cancel", FontWeight.SemiBold, Color.White) {
            haptic(Haptic.Light)
            onCancel()
        }
        Spacer(Modifier.weight(1f))
        EditorTextButton("Done", FontWeight.Bold, MediaColors.blue) {
            haptic(Haptic.Medium)
            onDone()
        }
    }
}

@Composable
private fun EditorTextButton(title: String, weight: FontWeight, color: Color, onClick: () -> Unit) {
    Box(
        Modifier
            .height(44.dp)
            .pressable(scale = CANCEL_DONE_PRESS_SCALE, dimming = 0f, haptic = Haptic.None, onClick = onClick)
            .padding(horizontal = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        ShroudText(title, inter(17f, weight), color)
    }
}

/** Behind every editor's controls: black 0 % → 90 % → 100 % from top to bottom (`MediaCropEditor.swift:539-545`). */
internal val EditorControlsGradient: Brush = Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0f), Color.Black.copy(alpha = 0.9f), Color.Black))

/**
 * A full-screen editor over the compose screen: iOS presents each as a `fullScreenCover`, which
 * rises from the bottom (`MediaComposeOverlay.swift:205-207, 626-628`); here it slides up and
 * fades in on [Motion.gentle] and leaves on [Motion.standard] (conversation-compose-media §9.5),
 * a plain fade under Reduce Motion. Drawn in the window's overlay layer, on black, modal for TalkBack.
 * [content] stays composed until the exit finished.
 */
@Composable
internal fun MediaEditorLayer(visible: Boolean, content: @Composable () -> Unit) {
    val visibility = rememberOverlayTransition(visible)
    val reduce = ShroudTheme.reduceMotion
    OverlayLayer(active = visibility.isOverlayUp) {
        AnimatedVisibility(
            visibleState = visibility,
            enter = editorEnter(reduce),
            exit = editorExit(reduce),
            modifier = Modifier.fillMaxSize(),
        ) {
            Box(Modifier.fillMaxSize().background(Color.Black)) { content() }
        }
    }
}

private fun editorEnter(reduce: Boolean): EnterTransition =
    if (reduce) fadeIn(Motion.reduced()) else slideInVertically(Motion.gentle()) { it } + fadeIn(Motion.gentle())

private fun editorExit(reduce: Boolean): ExitTransition =
    if (reduce) fadeOut(Motion.reduced()) else slideOutVertically(Motion.standard()) { it } + fadeOut(Motion.standard())

/**
 * The media surfaces are dark in both appearances (`.preferredColorScheme(.dark)`,
 * `MediaComposeOverlay.swift:209`): dark tokens for what they draw from the kit (confirmation sheet,
 * menus), the `blue` cursor handles of the caption and sticker fields, and light status-bar icons
 * while [content] is on screen, restored when it leaves (conversation-compose-media §9.1).
 */
@Composable
internal fun DarkMediaSurface(content: @Composable () -> Unit) {
    LightStatusBarIcons()
    ShroudTheme(dark = true) {
        CompositionLocalProvider(LocalTextSelectionColors provides MediaSelectionColors, content = content)
    }
}

private val MediaSelectionColors = TextSelectionColors(handleColor = MediaColors.blue, backgroundColor = MediaColors.blue.copy(alpha = 0.3f))

/** White status-bar icons over the black screen; the previous setting comes back on dispose. */
@Composable
private fun LightStatusBarIcons() {
    val view = LocalView.current
    DisposableEffect(view) {
        val window = view.context.findActivity()?.window
        val controller = window?.let { WindowCompat.getInsetsController(it, view) }
        val previous = controller?.isAppearanceLightStatusBars
        controller?.isAppearanceLightStatusBars = false
        onDispose {
            if (previous != null) controller?.isAppearanceLightStatusBars = previous
        }
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

/**
 * Lets a child reach [horizontal] past its parent's padding on both sides — SwiftUI's negative
 * `.padding(.horizontal, -n)`, used by the photo strip (−16) and the filter strip (−12)
 * (`MediaComposeOverlay.swift:387, 432`).
 */
internal fun Modifier.bleed(horizontal: Dp): Modifier = layout { measurable, constraints ->
    val extra = horizontal.roundToPx() * 2
    val maxWidth = if (constraints.hasBoundedWidth) constraints.maxWidth + extra else constraints.maxWidth
    val placeable = measurable.measure(constraints.copy(minWidth = (constraints.minWidth + extra).coerceAtMost(maxWidth), maxWidth = maxWidth))
    val width = (placeable.width - extra).coerceAtLeast(0)
    layout(width, placeable.height) { placeable.place(-horizontal.roundToPx(), 0) }
}
