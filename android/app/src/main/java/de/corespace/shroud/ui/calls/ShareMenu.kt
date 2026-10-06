package de.corespace.shroud.ui.calls

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.dismiss
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.isTraversalGroup
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import de.corespace.shroud.core.calls.ActiveCall
import de.corespace.shroud.core.calls.FRAME_RATES
import de.corespace.shroud.core.calls.ScreenShareQuality
import de.corespace.shroud.core.calls.label
import de.corespace.shroud.core.calls.screenFrameRateLabel
import de.corespace.shroud.ui.components.ContextMenuCardSurface
import de.corespace.shroud.ui.components.ContextMenuDefaults
import de.corespace.shroud.ui.components.ContextMenuItem
import de.corespace.shroud.ui.components.ContextMenuPlacement
import de.corespace.shroud.ui.components.MenuAction
import de.corespace.shroud.ui.components.MenuStyle
import de.corespace.shroud.ui.components.OverlayLayer
import de.corespace.shroud.ui.components.ShroudIcon
import de.corespace.shroud.ui.components.ShroudText
import de.corespace.shroud.ui.components.isOverlayUp
import de.corespace.shroud.ui.components.rememberOverlayTransition
import de.corespace.shroud.ui.theme.Motion
import de.corespace.shroud.ui.theme.ShroudIcons
import de.corespace.shroud.ui.theme.ShroudTheme
import de.corespace.shroud.ui.theme.inter

/**
 * Share, in the top-trailing corner above the pictures (`shareControl(for:)`,
 * `InCallOverlay.swift:419-472`; calls §8.5): a 40 dp glass circle (accent while sharing) with the
 * Lucide `screen-share` glyph, dimmed while sharing cannot be used yet. It opens the app's own menu
 * ([ShareMenu]); opening it keeps the controls up ([onTouch]).
 */
@Composable
internal fun ShareControl(
    call: ActiveCall,
    quality: ScreenShareQuality,
    onShare: () -> Unit,
    onQuality: (ScreenShareQuality) -> Unit,
    onTouch: () -> Unit,
    modifier: Modifier = Modifier,
    interactive: Boolean = true,
) {
    val colors = ShroudTheme.colors
    val sharing = call.isSharingScreen || call.screenShareStarting
    val available = CallScreenRules.shareAvailable(call)
    var bounds by remember { mutableStateOf(Rect.Zero) }
    var menuOpen by remember { mutableStateOf(false) }
    val tint by animateColorAsState(if (sharing) colors.accent.copy(alpha = 0.9f) else colors.accent.copy(alpha = 0f), Motion.snappy(), label = "shareTint")
    val open = {
        onTouch()
        menuOpen = true
    }
    Box(
        modifier
            .size(CallScreenMetrics.SHARE_CONTROL.dp)
            .onGloballyPositioned { bounds = it.boundsInRoot() }
            .callGlass(CircleShape, tint = tint.takeIf { it.alpha > 0f })
            .then(
                if (interactive) {
                    Modifier.clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, role = Role.Button, onClick = open)
                } else {
                    Modifier
                },
            )
            .clearAndSetSemantics {
                contentDescription = CallScreenRules.shareLabel(sharing)
                stateDescription = CallScreenRules.shareValue(sharing, quality) +
                    if (available) "" else ". ${CallScreenRules.SHARE_UNAVAILABLE_HINT}"
                role = Role.Button
                onClick {
                    open()
                    true
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        ShroudIcon(ShroudIcons.ScreenShare, Color.White, Modifier.graphicsLayer { alpha = if (available) 1f else 0.45f }, size = 18.dp)
    }
    ShareMenu(
        visible = menuOpen,
        anchor = bounds,
        sharing = sharing,
        quality = quality,
        onDismiss = { menuOpen = false },
        onShare = {
            menuOpen = false
            onShare()
        },
        onQuality = { chosen ->
            menuOpen = false
            onQuality(chosen)
        },
    )
}

/**
 * Center Stage, under Share while our camera is on (docs/calls.md, "Framing and Center Stage"):
 * the same 40 dp glass circle, accent while on, with Lucide `square-user` (a person in a frame).
 * A tap turns it on or off for this call and the next ones ([onToggle]) and keeps the controls up
 * ([onTouch]). Its own element, apart from our picture, so a tap never flips the camera.
 */
@Composable
internal fun CenterStageControl(
    on: Boolean,
    onToggle: () -> Unit,
    onTouch: () -> Unit,
    modifier: Modifier = Modifier,
    interactive: Boolean = true,
) {
    val colors = ShroudTheme.colors
    val tint by animateColorAsState(if (on) colors.accent.copy(alpha = 0.9f) else colors.accent.copy(alpha = 0f), Motion.snappy(), label = "centerStageTint")
    val toggle = {
        onTouch()
        onToggle()
    }
    Box(
        modifier
            .size(CallScreenMetrics.SHARE_CONTROL.dp)
            .callGlass(CircleShape, tint = tint.takeIf { it.alpha > 0f })
            .then(
                if (interactive) {
                    Modifier.clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, role = Role.Switch, onClick = toggle)
                } else {
                    Modifier
                },
            )
            .clearAndSetSemantics {
                contentDescription = CallScreenRules.CENTER_STAGE_LABEL
                stateDescription = CallScreenRules.centerStageValue(on)
                role = Role.Switch
                onClick {
                    toggle()
                    true
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        ShroudIcon(ShroudIcons.SquareUser, Color.White, size = 18.dp)
    }
}

/**
 * The share menu (design *Screen Share — Share Menu* H6R4MI, 250 wide; `InCallOverlay.swift:430-453`):
 * "Share Screen" or, while sharing, "Stop Sharing" (destructive); "Resolution" 720p / 1080p /
 * Source and "Frame rate" 15 / 30 / 60 fps, the current ones checked. Dark, as everything on the
 * call screen. Placed under its anchor like every menu of the app ([ContextMenuPlacement]);
 * a tap outside or back closes it.
 */
@Composable
internal fun ShareMenu(
    visible: Boolean,
    anchor: Rect,
    sharing: Boolean,
    quality: ScreenShareQuality,
    onDismiss: () -> Unit,
    onShare: () -> Unit,
    onQuality: (ScreenShareQuality) -> Unit,
) {
    val transition = rememberOverlayTransition(visible)
    val reduceMotion = ShroudTheme.reduceMotion
    val statusTop = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val navigationBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    OverlayLayer(active = transition.isOverlayUp, modal = true) {
        BackHandler(enabled = visible, onBack = onDismiss)
        Layout(
            content = {
                AnimatedVisibility(
                    visibleState = transition,
                    enter = if (reduceMotion) fadeIn(Motion.reduced()) else scaleIn(Motion.snappy(), 0.8f, TransformOrigin(1f, 0f)) + fadeIn(Motion.snappy()),
                    exit = if (reduceMotion) fadeOut(Motion.reduced()) else scaleOut(Motion.menuDrop(), 0.8f, TransformOrigin(1f, 0f)) + fadeOut(Motion.menuDrop()),
                ) {
                    ShareMenuCard(sharing, quality, onDismiss, onShare, onQuality)
                }
            },
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(Unit) { detectTapGestures { onDismiss() } },
        ) { measurables, constraints ->
            // AnimatedVisibility emits no child on the frame it is fully closed. single()
            // then throws and the call process dies.
            val measurable = measurables.firstOrNull()
            if (measurable == null) {
                layout(constraints.maxWidth, constraints.maxHeight) {}
            } else {
                val cardWidth = ContextMenuDefaults.CardWidth.roundToPx()
                val card = measurable.measure(Constraints(maxWidth = cardWidth, maxHeight = constraints.maxHeight))
                val placement = ContextMenuPlacement.place(
                    anchor = anchor,
                    cardWidth = card.width.toFloat(),
                    cardHeight = card.height.toFloat(),
                    containerWidth = constraints.maxWidth.toFloat(),
                    containerHeight = constraints.maxHeight.toFloat(),
                    safeTop = statusTop.toPx(),
                    safeBottom = navigationBottom.toPx(),
                    gap = ContextMenuDefaults.AnchorGap.toPx(),
                    sideInset = ContextMenuDefaults.SideInset.toPx(),
                    topMargin = ContextMenuDefaults.TopMargin.toPx(),
                    bottomMargin = ContextMenuDefaults.BottomMargin.toPx(),
                )
                layout(constraints.maxWidth, constraints.maxHeight) { card.place(placement.x.toInt(), placement.y.toInt()) }
            }
        }
    }
}

@Composable
private fun ShareMenuCard(
    sharing: Boolean,
    quality: ScreenShareQuality,
    onDismiss: () -> Unit,
    onShare: () -> Unit,
    onQuality: (ScreenShareQuality) -> Unit,
) {
    val style = MenuStyle.Light
    ContextMenuCardSurface(
        style = style,
        modifier = Modifier
            .width(ContextMenuDefaults.CardWidth)
            // Taps on the card stay on it.
            .pointerInput(Unit) { detectTapGestures { } }
            .semantics {
                paneTitle = CallScreenRules.shareLabel(sharing)
                isTraversalGroup = true
                dismiss {
                    onDismiss()
                    true
                }
            },
    ) {
        val share = if (sharing) {
            MenuAction("Stop Sharing", ShroudIcons.StopFill, destructive = true)
        } else {
            MenuAction("Share Screen", ShroudIcons.ScreenShare)
        }
        ContextMenuItem(share, style, onClick = onShare)
        MenuSection("Resolution")
        for (resolution in ScreenShareQuality.Resolution.entries) {
            val item = MenuAction(resolution.label, checked = quality.resolution == resolution)
            ContextMenuItem(item, style, showsIconSlot = true, onClick = { onQuality(quality.copy(resolution = resolution)) })
        }
        MenuSection("Frame rate")
        for (rate in ScreenShareQuality.FRAME_RATES) {
            val item = MenuAction(screenFrameRateLabel(rate), checked = quality.frameRate == rate)
            ContextMenuItem(item, style, showsIconSlot = true, onClick = { onQuality(quality.copy(frameRate = rate)) })
        }
    }
}

/** A section's title under a hairline, as iOS menus head a titled section. */
@Composable
private fun MenuSection(title: String) {
    val colors = ShroudTheme.colors
    Box(Modifier.fillMaxWidth().padding(top = 6.dp).height(1.dp).background(colors.separator))
    ShroudText(
        title,
        inter(13f, FontWeight.Medium),
        colors.textSecondary,
        Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 2.dp).semantics { heading() },
        maxLines = 1,
    )
}
