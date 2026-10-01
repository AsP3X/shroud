package de.corespace.shroud.ui.components

import android.annotation.SuppressLint
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.rememberTransition
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.exclude
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.dropShadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.shadow.Shadow
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import de.corespace.shroud.ui.theme.Motion
import de.corespace.shroud.ui.theme.ShroudTheme
import kotlin.math.max

/**
 * The three sheet looks of the app (00-plan C21; one family instead of three components).
 *
 * - [Full]: the large sheet — full width, rounded top corners, from just under the status bar
 *   (onboarding Server Settings `aNX3S`; with `cornerRadius = 38.dp`, no handle and the sheet
 *   scrim also New Chat `WHZDi` and My QR Code — shell-chats §9.1).
 * - [Inset]: the floating card of iOS 26's medium detent — 8 dp from the sides and above the
 *   navigation bar, radius 38, grouped background, grabber, content padding [8,16,24,16] with a
 *   14 dp gap (Device Details `nUbf0`, Add Contact `l14KDf`; settings-lock §2.6, contacts §5.4).
 * - [Compact]: the bare floating card of the attach sheet — 8 dp from the sides and the window
 *   bottom, radius 38, `background`, the navigation-bar inset inside, no padding: the content
 *   draws its own grab capsule (`w4lZ1`; conversation-compose-media §7.1).
 */
enum class SheetStyle { Full, Inset, Compact }

/**
 * The app's own sheet (no Material): scrim, card, dragged down / backed out / scrim-tapped to
 * close. iOS `.sheet` (`NewChatSheet`, `DeviceDetailSheet` with `[.medium, .large]` detents,
 * `ChatAttachSheet` with a 420 pt detent; `DevicesView.swift:111-127`, `ConversationView.swift:389-404`).
 *
 * Human: The sheet slides up (`Motion.gentle`) and down (`Motion.standard`); with Reduce Motion it
 * fades. It follows a finger dragging it down — on the handle, or anywhere once the content is
 * scrolled to its top — and closes past 120 dp or on a quick flick, otherwise it springs back.
 * Back, predictive back (the sheet leans away while the gesture runs) and a tap on the scrim
 * close it. Floating sheets grow with their content up to 90 % of the window and then scroll
 * inside; they ride above the keyboard.
 *
 * Agent: Drawn in the [OverlayHost] layer, above the tab bar and toasts of the screen that opened
 * it (in place when there is no host, as before). [visible] is the caller's state; [onDismiss] asks
 * the caller to set it false. [paneTitle] is announced by TalkBack ("New Chat", "Device details").
 * Inset and Compact content sits in a vertical scroll container — no lazy lists inside them
 * (use [SheetStyle.Full] for a list). [cornerRadius], [scrim] and [color] override the style's
 * look; `Dp.Unspecified` / `Color.Unspecified` keep it. Today's call sites
 * (`ShroudSheet(visible, onDismiss) { … }`) compile and look unchanged.
 */
// Parameter order is the binding signature of 00-plan §1.7.12 (modifier after it).
@SuppressLint("ModifierParameter")
@Composable
fun ShroudSheet(
    visible: Boolean,
    onDismiss: () -> Unit,
    style: SheetStyle = SheetStyle.Full,
    modifier: Modifier = Modifier,
    paneTitle: String = "Sheet",
    showsHandle: Boolean = style != SheetStyle.Compact,
    cornerRadius: Dp = Dp.Unspecified,
    scrim: Color = Color.Unspecified,
    color: Color = Color.Unspecified,
    content: @Composable ColumnScope.() -> Unit,
) {
    val visibility = rememberOverlayTransition(visible)
    val currentOnDismiss by rememberUpdatedState(onDismiss)
    OverlayLayer(active = visibility.isOverlayUp) {
        val colors = ShroudTheme.colors
        val palette = overlayPalette()
        val reduceMotion = ShroudTheme.reduceMotion
        val transition = rememberTransition(visibility, label = "sheet")
        val back by rememberOverlayBack(enabled = visible) { currentOnDismiss() }
        val look = SheetLook.of(style).let {
            if (cornerRadius != Dp.Unspecified) it.copy(cornerRadius = cornerRadius) else it
        }
        val scrimColor = when {
            scrim != Color.Unspecified -> scrim
            style == SheetStyle.Full -> colors.scrim
            style == SheetStyle.Inset -> palette.sheetScrim
            else -> palette.compactSheetScrim
        }
        val fill = when {
            color != Color.Unspecified -> color
            style == SheetStyle.Inset -> colors.backgroundGrouped
            else -> colors.background
        }
        Box(Modifier.fillMaxSize().overlayPane(paneTitle) { currentOnDismiss() }) {
            transition.AnimatedVisibility(
                visible = { it },
                enter = fadeIn(Motion.scrim()),
                exit = fadeOut(Motion.scrim()),
            ) {
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(scrimColor)
                        .dismissOnTap(openedAt = 0L) { currentOnDismiss() },
                )
            }
            BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
                val maxCardHeight = maxHeight * FLOATING_MAX_HEIGHT_FRACTION
                transition.AnimatedVisibility(
                    visible = { it },
                    enter = sheetEnter(reduceMotion),
                    exit = sheetExit(reduceMotion),
                ) {
                    SheetCard(
                        style = style,
                        look = look,
                        fill = fill,
                        showsHandle = showsHandle,
                        back = { back },
                        maxFloatingHeight = maxCardHeight,
                        onDismiss = { currentOnDismiss() },
                        modifier = modifier,
                        content = content,
                    )
                }
            }
        }
    }
}

/** Sizes of a sheet style (design `Effects vocabulary`: Sheet (onboarding / large / floating)). */
internal data class SheetLook(
    val cornerRadius: Dp,
    /** Floating sheets only: distance to the window's sides and bottom. */
    val margin: Dp,
    val shadowColor: Color,
    val shadowRadius: Dp,
    val shadowOffsetY: Dp,
    /** Widest the card gets on large windows (centred). */
    val maxWidth: Dp,
) {
    companion object {
        fun of(style: SheetStyle): SheetLook = when (style) {
            // aNX3S: r22 top, shadow #0B0B1240 (0,−12) blur 40.
            SheetStyle.Full -> SheetLook(22.dp, 0.dp, OverlayShadows.fullSheet, 40.dp, (-12).dp, maxWidth = 640.dp)
            // nUbf0: r38, 8 inset, shadow #0B0B1229 (0,−4) blur 30.
            SheetStyle.Inset -> SheetLook(38.dp, 8.dp, OverlayShadows.insetSheet, 30.dp, (-4).dp, maxWidth = 480.dp)
            // w4lZ1: r38, 8 inset, shadow #0B0B1233 (0,−8) blur 32.
            SheetStyle.Compact -> SheetLook(38.dp, 8.dp, OverlayShadows.compactSheet, 32.dp, (-8).dp, maxWidth = 480.dp)
        }
    }
}

/**
 * The drag-to-dismiss rule of the sheets (as `ShroudSheet` always had it): past [SheetDrag.DISMISS_DISTANCE]
 * or flung down faster than [SheetDrag.DISMISS_VELOCITY] px/s closes; anything less springs back.
 */
internal object SheetDrag {
    val DISMISS_DISTANCE: Dp = 120.dp
    const val DISMISS_VELOCITY = 2000f

    fun shouldDismiss(offsetPx: Float, velocityPx: Float, dismissDistancePx: Float): Boolean =
        offsetPx > dismissDistancePx || velocityPx > DISMISS_VELOCITY

    /** The sheet never moves above its resting place. */
    fun dragged(offsetPx: Float, deltaPx: Float): Float = max(0f, offsetPx + deltaPx)
}

private fun sheetEnter(reduceMotion: Boolean): EnterTransition =
    if (reduceMotion) fadeIn(Motion.reduced()) else slideInVertically(Motion.gentle()) { it }

private fun sheetExit(reduceMotion: Boolean): ExitTransition =
    if (reduceMotion) fadeOut(Motion.reduced()) else slideOutVertically(Motion.standard()) { it }

/** The drag offset of an open sheet and how it settles. */
@Stable
private class SheetDragState(
    private val dismissDistancePx: Float,
    private val onDismiss: () -> Unit,
) {
    var offset by mutableFloatStateOf(0f)
        private set
    private var dismissed = false

    fun dragBy(delta: Float): Float {
        if (dismissed) return 0f
        val before = offset
        offset = SheetDrag.dragged(offset, delta)
        return offset - before
    }

    suspend fun settle(velocity: Float) {
        if (dismissed) return
        if (SheetDrag.shouldDismiss(offset, velocity, dismissDistancePx)) {
            dismissed = true
            onDismiss()
        } else {
            animate(offset, 0f, animationSpec = Motion.snappy()) { value, _ -> offset = value }
        }
    }

    /**
     * Content scrolled to its top hands a further pull down to the sheet; a push up first takes
     * the sheet back to rest before the content scrolls; letting go settles.
     */
    val nestedScroll = object : NestedScrollConnection {
        override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
            if (available.y < 0f && offset > 0f) return Offset(0f, dragBy(available.y))
            return Offset.Zero
        }

        override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
            if (source == NestedScrollSource.UserInput && available.y > 0f) return Offset(0f, dragBy(available.y))
            return Offset.Zero
        }

        override suspend fun onPreFling(available: Velocity): Velocity {
            if (offset <= 0f) return Velocity.Zero
            settle(available.y)
            return available
        }
    }
}

@Composable
private fun SheetCard(
    style: SheetStyle,
    look: SheetLook,
    fill: Color,
    showsHandle: Boolean,
    back: () -> Float,
    maxFloatingHeight: Dp,
    onDismiss: () -> Unit,
    modifier: Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    val density = LocalDensity.current
    val dismissDistance = with(density) { SheetDrag.DISMISS_DISTANCE.toPx() }
    val drag = remember(dismissDistance) { SheetDragState(dismissDistance, onDismiss) }
    val leanLift = with(density) { BACK_LEAN_LIFT.toPx() }
    val moved = Modifier.graphicsLayer {
        val progress = back().coerceIn(0f, 1f)
        val lean = backLeanScale(progress, maxShrink = 0.04f)
        translationY = drag.offset + leanLift * progress
        scaleX = lean
        scaleY = lean
    }
    when (style) {
        SheetStyle.Full -> {
            val shape = RoundedCornerShape(topStart = look.cornerRadius, topEnd = look.cornerRadius)
            Column(
                modifier
                    .statusBarsPadding()
                    .padding(top = 8.dp)
                    .widthIn(max = look.maxWidth)
                    .fillMaxWidth()
                    .then(moved)
                    .sheetShadow(shape, look)
                    .clip(shape)
                    .background(fill)
                    .nestedScroll(drag.nestedScroll)
                    .imePadding(),
            ) {
                if (showsHandle) SheetHandle(drag, fullSheet = true)
                content()
            }
        }
        SheetStyle.Inset, SheetStyle.Compact -> {
            val shape = RoundedCornerShape(look.cornerRadius)
            val bottomInsets = if (style == SheetStyle.Inset) {
                WindowInsets.navigationBars.union(WindowInsets.ime)
            } else {
                // The attach sheet sits 8 dp above the window bottom with the bar inside it.
                WindowInsets.ime.exclude(WindowInsets.navigationBars)
            }
            Column(
                modifier
                    .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Top))
                    .padding(start = look.margin, end = look.margin, top = look.margin)
                    .windowInsetsPadding(bottomInsets.only(WindowInsetsSides.Bottom))
                    .padding(bottom = look.margin)
                    .widthIn(max = look.maxWidth)
                    .fillMaxWidth()
                    .heightIn(max = maxFloatingHeight)
                    .then(moved)
                    .sheetShadow(shape, look)
                    .clip(shape)
                    .background(fill)
                    .nestedScroll(drag.nestedScroll),
            ) {
                if (showsHandle) SheetHandle(drag, fullSheet = false)
                val scroll = rememberScrollState()
                if (style == SheetStyle.Inset) {
                    // settings-lock §2.6: padding [8,16,24,16] (the grabber takes the top 8), gap 14.
                    Column(
                        Modifier
                            .weight(1f, fill = false)
                            .verticalScroll(scroll)
                            .padding(start = 16.dp, end = 16.dp, top = if (showsHandle) 14.dp else 8.dp, bottom = 24.dp),
                        verticalArrangement = Arrangement.spacedBy(14.dp),
                        content = content,
                    )
                } else {
                    Column(
                        Modifier
                            .weight(1f, fill = false)
                            .verticalScroll(scroll)
                            .windowInsetsPadding(WindowInsets.navigationBars.only(WindowInsetsSides.Bottom)),
                        content = content,
                    )
                }
            }
        }
    }
}

/** Floating sheets grow with their content up to this share of the window, then scroll (settings-lock §2.6). */
private const val FLOATING_MAX_HEIGHT_FRACTION = 0.9f

/** How far a sheet sinks while a predictive back gesture runs (plus a 4 % shrink). */
private val BACK_LEAN_LIFT = 24.dp

private fun Modifier.sheetShadow(shape: Shape, look: SheetLook): Modifier =
    dropShadow(shape, Shadow(radius = look.shadowRadius, color = look.shadowColor, offset = DpOffset(0.dp, look.shadowOffsetY)))

/**
 * The grabber, which also drags the sheet. Full: 36 × 5 in a 23 dp strip, `textSecondary` @ 35 %
 * (as before); floating: 36 × 5 radius 2.5 in `chevron` (iOS `systemGray3`), 8 dp from the top.
 */
@Composable
private fun SheetHandle(drag: SheetDragState, fullSheet: Boolean) {
    val colors = ShroudTheme.colors
    val palette = overlayPalette()
    Box(
        Modifier
            .fillMaxWidth()
            .draggable(
                orientation = Orientation.Vertical,
                state = rememberDraggableState { delta -> drag.dragBy(delta) },
                onDragStopped = { velocity -> drag.settle(velocity) },
            )
            .padding(top = if (fullSheet) 10.dp else 8.dp, bottom = if (fullSheet) 8.dp else 0.dp)
            .semantics { contentDescription = "Drag down to close" },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .size(36.dp, 5.dp)
                .clip(RoundedCornerShape(2.5.dp))
                .background(if (fullSheet) colors.textSecondary.copy(alpha = 0.35f) else palette.chevron),
        )
    }
}
