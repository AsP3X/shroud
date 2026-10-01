package de.corespace.shroud.ui.components

import android.os.Build
import android.os.SystemClock
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.dropShadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.shadow.Shadow
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.layoutId
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.isTraversalGroup
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import de.corespace.shroud.ui.theme.Motion
import de.corespace.shroud.ui.theme.ShroudIcons
import de.corespace.shroud.ui.theme.ShroudTheme
import de.corespace.shroud.ui.theme.inter
import kotlinx.coroutines.launch
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * One row of a context menu, a menu picker or the message menu card.
 *
 * [submenu] makes the row open a nested list in the same card (the chat list's "Mute" ▸
 * durations, `ChatsView.swift:245-252`; shell-chats D6). [checked] draws the leading check of a
 * picker's current value. [onClick] runs after the menu began to close.
 */
data class MenuAction(
    val title: String,
    val icon: ImageVector? = null,
    val destructive: Boolean = false,
    val enabled: Boolean = true,
    val checked: Boolean = false,
    val submenu: List<MenuAction>? = null,
    val onClick: () -> Unit = {},
)

/**
 * [Light] follows the appearance: the chat list's long-press menu (design `Chats — Chat Menu`
 * AqgbA), pickers and the link options. [Dark] is dark in both appearances: the message menu card
 * (`MessageActionMenu.swift:416-528`, conversation-thread §16.8).
 */
enum class MenuStyle { Light, Dark }

/**
 * A long-press menu: a card of [actions] next to [anchor], over a scrim. iOS draws these with
 * `.contextMenu` / `Menu` (`ChatsView.swift:98-105, 135-144, 229-286`, `ChatLinkBar.swift:39-66`);
 * Android draws them itself (no platform popup), shell-chats §8.7 / §10.13.
 *
 * Human: With a [header] the menu lifts it out of the list — the row re-drawn where it was, inset
 * 8 dp each side, rounded and shadowed — and the app behind blurs (API 31+; a plain scrim on
 * Android 11). The card sits 8 dp under the anchor, or 8 dp above it when it would not fit above
 * the navigation bar. Tapping outside, back (also predictive) or any item closes it; an item's
 * action runs as the menu starts to close. A row with a submenu cross-fades the card into the
 * submenu, with a back row on top; back then returns to the first list.
 *
 * Agent: [anchor] is in root coordinates (`LayoutCoordinates.boundsInRoot()`) and px. Keep the
 * composable in composition while it is open (`if (menuFor != null) ContextMenu(…)`): [onDismiss]
 * is called once the closing animation has run, so the caller drops it then. Long-press triggers
 * (`combinedClickable`) already buzz on open; the menu itself does not. [dimsBackground] defaults
 * to dimming only when there is a [header] (iOS `Menu`s, like pickers, open over a clear backdrop).
 */
@Composable
fun ContextMenu(
    anchor: Rect,
    actions: List<MenuAction>,
    style: MenuStyle = MenuStyle.Light,
    onDismiss: () -> Unit,
    paneTitle: String = "Options",
    dimsBackground: Boolean? = null,
    header: (@Composable () -> Unit)? = null,
) {
    ContextMenuOverlay(
        anchor = anchor,
        actions = actions,
        style = style,
        onDismiss = onDismiss,
        paneTitle = paneTitle,
        dims = dimsBackground ?: (header != null),
        metrics = if (style == MenuStyle.Light) MenuCardMetrics.Light else MenuCardMetrics.Dark,
        header = header,
    )
}

/** Sizes shared by the menu cards (shell-chats §8.7, conversation-thread §16.8). */
object ContextMenuDefaults {
    val CardWidth: Dp = 250.dp
    val RowHeight: Dp = 44.dp

    /** Gap between the anchor and the card (iOS system menu; shell-chats D15). */
    val AnchorGap: Dp = 8.dp

    /** The card never comes closer to the screen's sides than this. */
    val SideInset: Dp = 16.dp

    /** Room kept above the card under the status bar. */
    val TopMargin: Dp = 8.dp

    /** Room kept between the card and the navigation bar (shell-chats §8.7: "navigationBars + 10 dp"). */
    val BottomMargin: Dp = 10.dp

    /** Each side of a lifted header is pulled in this far (396 wide at 412). */
    val HeaderInset: Dp = 8.dp

    /** Card height for [rows] rows: 6 dp vertical padding (light) or 1 dp hairlines (dark). */
    fun cardHeight(rows: Int, style: MenuStyle): Dp = when (style) {
        MenuStyle.Light -> RowHeight * rows + 12.dp
        MenuStyle.Dark -> RowHeight * rows + 1.dp * max(rows - 1, 0)
    }
}

/**
 * Where a menu card goes next to its anchor — pure, in px (shell-chats §8.7, D15).
 *
 * Vertically: 8 dp below the anchor; if that would cross the navigation bar (+10 dp), 8 dp above
 * it; if neither fits, the side with more room, clamped into the safe area. The side is chosen
 * with [decisionHeight] (the tallest the card can become, e.g. with a submenu open) so it does not
 * flip while the card changes height. Horizontally: leading-aligned with the anchor, or
 * trailing-aligned when the anchor starts in the right half (a trailing picker), clamped 16 dp
 * from the screen's sides.
 */
object ContextMenuPlacement {
    data class Placement(val x: Float, val y: Float, val below: Boolean, val alignEnd: Boolean)

    fun place(
        anchor: Rect,
        cardWidth: Float,
        cardHeight: Float,
        containerWidth: Float,
        containerHeight: Float,
        safeTop: Float,
        safeBottom: Float,
        gap: Float,
        sideInset: Float,
        topMargin: Float,
        bottomMargin: Float,
        decisionHeight: Float = cardHeight,
    ): Placement {
        val alignEnd = anchor.left > containerWidth / 2f
        val rawX = if (alignEnd) anchor.right - cardWidth else anchor.left
        val x = rawX.coerceIn(sideInset, max(sideInset, containerWidth - sideInset - cardWidth))
        val highest = safeTop + topMargin
        val lowest = containerHeight - safeBottom - bottomMargin
        val fitsBelow = anchor.bottom + gap + decisionHeight <= lowest
        val fitsAbove = anchor.top - gap - decisionHeight >= highest
        val below = when {
            fitsBelow -> true
            fitsAbove -> false
            else -> lowest - anchor.bottom >= anchor.top - highest
        }
        val y = if (below) {
            min(anchor.bottom + gap, lowest - cardHeight).coerceAtLeast(highest)
        } else {
            max(anchor.top - gap - cardHeight, highest)
        }
        return Placement(x, y, below, alignEnd)
    }
}

// ---- Card building blocks (shared with the message menu, conversation-thread §16.8) -----------

/**
 * A menu card listing [actions]; rows with a submenu or the check of a picker included. Flat: the
 * caller decides what a tap does ([onAction]). [ContextMenu] uses it; the conversation's message
 * menu builds its own card from [ContextMenuCardSurface], [ContextMenuItem] and
 * [ContextMenuSeparator] (it adds the muted receipt row).
 */
@Composable
fun ContextMenuCard(
    actions: List<MenuAction>,
    style: MenuStyle,
    onAction: (MenuAction) -> Unit,
    modifier: Modifier = Modifier,
    width: Dp = ContextMenuDefaults.CardWidth,
) {
    ContextMenuCardImpl(actions, style, onAction, modifier, width, if (style == MenuStyle.Light) MenuCardMetrics.Light else MenuCardMetrics.Dark, opaque = true)
}

/**
 * The card itself: [MenuStyle.Light] = card glass (radius 22, 6 dp vertical padding, 1 dp
 * stroke, 0/12/32 shadow — design AqgbA); [MenuStyle.Dark] = `#1F1F24` @ 0.94, radius 14,
 * 0.5 dp white @ 0.08 stroke (`MessageActionMenu.swift:470-480`). [translucent] lets the light
 * card show the blurred backdrop through (only over a blurred backdrop).
 */
@Composable
fun ContextMenuCardSurface(
    style: MenuStyle,
    modifier: Modifier = Modifier,
    translucent: Boolean = false,
    content: @Composable ColumnScope.() -> Unit,
) {
    val palette = overlayPalette()
    when (style) {
        MenuStyle.Light -> {
            val shape = RoundedCornerShape(22.dp)
            Column(
                modifier
                    .dropShadow(shape, Shadow(radius = 32.dp, color = OverlayShadows.card, offset = DpOffset(0.dp, 12.dp)))
                    .clip(shape)
                    .background(if (translucent) palette.cardGlass else palette.cardOpaque)
                    .border(1.dp, palette.cardStroke, shape)
                    .padding(vertical = 6.dp),
                content = content,
            )
        }
        MenuStyle.Dark -> {
            val shape = RoundedCornerShape(14.dp)
            Column(
                modifier
                    .clip(shape)
                    .background(DarkMenuFill)
                    .border(0.5.dp, Color.White.copy(alpha = 0.08f), shape),
                content = content,
            )
        }
    }
}

/** The 1 dp hairline between rows of the dark card (white @ 0.08); the light card has none. */
@Composable
fun ContextMenuSeparator(style: MenuStyle) {
    if (style == MenuStyle.Dark) {
        Box(Modifier.fillMaxWidth().height(1.dp).background(Color.White.copy(alpha = 0.08f)))
    }
}

/**
 * One row: optional leading glyph (or the picker's check), title, a trailing caret when it opens a
 * submenu. Pressed: the highlight lands at once and fades out (`HighlightRowButtonStyle`,
 * `Motion.swift:100-120`), with a light haptic. [muted] draws an information row that is not a
 * button (the message menu's receipt). [showsIconSlot] keeps titles aligned when only some rows
 * have a glyph.
 */
@Composable
fun ContextMenuItem(
    action: MenuAction,
    style: MenuStyle,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    muted: Boolean = false,
    showsIconSlot: Boolean = action.icon != null || action.checked,
) {
    ContextMenuItemImpl(
        action = action,
        style = style,
        onClick = onClick,
        modifier = modifier,
        muted = muted,
        showsIconSlot = showsIconSlot,
        metrics = if (style == MenuStyle.Light) MenuCardMetrics.Light else MenuCardMetrics.Dark,
    )
}

// ---- Implementation ------------------------------------------------------------------------------

/** `Color(red: 0.12, green: 0.12, blue: 0.14).opacity(0.94)` (MAM:471). */
private val DarkMenuFill = Color(0xF01F1F24)

/** The danger colour on the always-dark card: the dark `danger` token (#FF453A), MAM:479-481. */
private val DarkMenuDanger = Color(0xFFFF453A)

/** Row metrics per card kind. */
@Immutable
internal data class MenuCardMetrics(
    val style: MenuStyle,
    val labelSize: Float,
    val glyphSize: Dp,
    val iconSlot: Dp,
    val horizontalPadding: Dp,
) {
    companion object {
        /** Design AqgbA: padding h 16, gap 12, glyph 19, label 17 Regular. */
        val Light = MenuCardMetrics(MenuStyle.Light, labelSize = 17f, glyphSize = 19.dp, iconSlot = 19.dp, horizontalPadding = 16.dp)

        /** Settings pickers (settings-lock §2.5): 16 sp rows on the light card. */
        val Picker = Light.copy(labelSize = 16f)

        /** MAM:497-521: icon 15 medium in a 22 wide box, title 16, padding h 14. */
        val Dark = MenuCardMetrics(MenuStyle.Dark, labelSize = 16f, glyphSize = 15.dp, iconSlot = 22.dp, horizontalPadding = 14.dp)
    }
}

@Composable
private fun ContextMenuCardImpl(
    actions: List<MenuAction>,
    style: MenuStyle,
    onAction: (MenuAction) -> Unit,
    modifier: Modifier,
    width: Dp,
    metrics: MenuCardMetrics,
    opaque: Boolean,
    backRow: MenuAction? = null,
) {
    val showsIconSlot = actions.any { it.icon != null || it.checked } || backRow != null
    ContextMenuCardSurface(style, modifier.width(width), translucent = !opaque) {
        val rows = if (backRow != null) listOf(backRow) + actions else actions
        rows.forEachIndexed { index, action ->
            if (index > 0) ContextMenuSeparator(style)
            ContextMenuItemImpl(
                action = action,
                style = style,
                onClick = { onAction(action) },
                modifier = Modifier,
                muted = false,
                showsIconSlot = showsIconSlot,
                metrics = metrics,
            )
        }
    }
}

@Composable
private fun ContextMenuItemImpl(
    action: MenuAction,
    style: MenuStyle,
    onClick: () -> Unit,
    modifier: Modifier,
    muted: Boolean,
    showsIconSlot: Boolean,
    metrics: MenuCardMetrics,
) {
    val colors = ShroudTheme.colors
    val palette = overlayPalette()
    val dark = style == MenuStyle.Dark
    val danger = if (dark) DarkMenuDanger else colors.danger
    val labelColor = when {
        action.destructive -> danger
        dark && muted -> Color.White.copy(alpha = 0.55f)
        dark -> Color.White
        muted -> colors.textSecondary
        else -> colors.textPrimary
    }
    val glyphColor = when {
        action.destructive -> danger
        dark && muted -> Color.White.copy(alpha = 0.45f)
        dark -> Color.White.copy(alpha = 0.85f)
        else -> labelColor
    }
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val view = LocalView.current
    LaunchedEffect(pressed) {
        if (pressed) OverlayHaptics.light(view)
    }
    // Lands instantly, fades out (MOT:109-117).
    val highlight by animateFloatAsState(
        targetValue = if (pressed) 1f else 0f,
        animationSpec = if (pressed) snap() else Motion.fade(),
        label = "menuRowHighlight",
    )
    val highlightColor = if (dark) Color.White.copy(alpha = 0.10f) else palette.rowPressed
    val clickable = !muted && action.enabled
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = ContextMenuDefaults.RowHeight)
            .background(highlightColor.copy(alpha = highlightColor.alpha * highlight))
            .then(
                if (muted) {
                    Modifier.semantics(mergeDescendants = true) {}
                } else {
                    Modifier.clickable(
                        interactionSource = interaction,
                        indication = null,
                        enabled = clickable,
                        role = Role.Button,
                        onClick = onClick,
                    )
                },
            )
            .semantics {
                if (action.checked) selected = true
                if (!action.enabled) disabled()
            }
            .alpha(if (action.enabled) 1f else 0.4f)
            .padding(horizontal = metrics.horizontalPadding, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (showsIconSlot) {
            Box(Modifier.width(metrics.iconSlot), contentAlignment = Alignment.Center) {
                when {
                    action.checked -> ShroudIcon(ShroudIcons.Check, if (dark) Color.White else colors.accent, size = 14.dp)
                    action.icon != null -> ShroudIcon(action.icon, glyphColor, size = metrics.glyphSize)
                }
            }
        }
        ShroudText(
            text = action.title,
            style = inter(metrics.labelSize),
            color = labelColor,
            modifier = Modifier.weight(1f),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        if (action.submenu != null) {
            ShroudIcon(OverlayIcons.CaretRight, if (dark) Color.White.copy(alpha = 0.55f) else colors.textSecondary, size = 13.dp)
        }
    }
}

/** How the card comes in: lifted with its row (Telegram spring) or popped from its anchor. */
private enum class MenuEntrance { Lift, Pop }

@Composable
internal fun ContextMenuOverlay(
    anchor: Rect,
    actions: List<MenuAction>,
    style: MenuStyle,
    onDismiss: () -> Unit,
    paneTitle: String,
    dims: Boolean,
    metrics: MenuCardMetrics,
    header: (@Composable () -> Unit)?,
) {
    val reduceMotion = ShroudTheme.reduceMotion
    val entrance = if (header != null) MenuEntrance.Lift else MenuEntrance.Pop
    val progress = remember { Animatable(0f) }
    val openedAt = remember { SystemClock.uptimeMillis() }
    var closing by remember { mutableStateOf(false) }
    var submenuOf by remember { mutableStateOf<MenuAction?>(null) }
    val scope = rememberCoroutineScope()
    val currentOnDismiss by rememberUpdatedState(onDismiss)
    val blurs = dims && style == MenuStyle.Light && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

    LaunchedEffect(Unit) {
        progress.animateTo(
            1f,
            when {
                reduceMotion -> Motion.reduced()
                entrance == MenuEntrance.Lift -> OverlayMotion.menuLift()
                else -> Motion.snappy()
            },
        )
    }
    val close: () -> Unit = {
        if (!closing) {
            closing = true
            scope.launch {
                progress.animateTo(0f, if (reduceMotion) Motion.reduced() else OverlayMotion.menuDrop())
                currentOnDismiss()
            }
        }
    }
    val backRow = submenuOf?.let { MenuAction(title = it.title, icon = OverlayIcons.CaretLeft) }
    val onAction: (MenuAction) -> Unit = { action ->
        when {
            closing -> Unit
            backRow != null && action === backRow -> submenuOf = null
            action.submenu != null -> submenuOf = action
            else -> {
                // Dismiss first, then act (CV:2204-2254): a confirmation the action opens lands on top.
                close()
                action.onClick()
            }
        }
    }

    OverlayLayer(
        active = true,
        modal = true,
        backdropBlur = { if (blurs) MenuBackdropBlur * progress.value.coerceIn(0f, 1f) else 0.dp },
    ) {
        val back by rememberOverlayBack(enabled = !closing) {
            if (submenuOf != null) submenuOf = null else close()
        }
        val palette = overlayPalette()
        val density = LocalDensity.current
        val safe = WindowInsets.systemBars.union(WindowInsets.ime)
        val safeTop = safe.getTop(density).toFloat()
        val safeBottom = safe.getBottom(density).toFloat()
        var origin by remember { mutableStateOf(Offset.Zero) }
        val localAnchor = anchor.translate(-origin.x, -origin.y)
        val decisionHeight = with(density) {
            val heights = listOf(actions.size) + actions.mapNotNull { it.submenu?.size?.plus(1) }
            ContextMenuDefaults.cardHeight(heights.max(), style).toPx()
        }

        Box(
            Modifier
                .fillMaxSize()
                .onGloballyPositioned { origin = it.positionInRoot() }
                .semantics {
                    this.paneTitle = paneTitle
                    isTraversalGroup = true
                },
        ) {
            // Scrim: light menus blur the app behind and tint it; the dark card uses the message
            // menu's two solid layers (MAM:837-853); menus without a header only catch the tap.
            Box(
                Modifier
                    .fillMaxSize()
                    .graphicsLayer { alpha = progress.value.coerceIn(0f, 1f) }
                    .then(
                        when {
                            !dims -> Modifier
                            style == MenuStyle.Dark -> Modifier.background(Color.Black.copy(alpha = 0.42f)).background(Color(0xFF0F0F14).copy(alpha = 0.28f))
                            blurs -> Modifier.background(palette.menuScrim)
                            else -> Modifier.background(palette.menuScrimNoBlur)
                        },
                    )
                    .dismissOnTap(openedAt, onDismiss = close),
            )

            Layout(
                content = {
                    if (header != null) {
                        val p = progress.value.coerceIn(0f, 1f)
                        val shape = RoundedCornerShape(18.dp * p)
                        Box(
                            Modifier
                                .layoutId(HeaderId)
                                .dropShadow(shape, Shadow(radius = 24.dp, color = OverlayShadows.liftedRow, offset = DpOffset(0.dp, 8.dp), alpha = p))
                                .clip(shape)
                                .background(ShroudTheme.colors.background),
                        ) {
                            header()
                        }
                    }
                    Box(Modifier.layoutId(CardId)) {
                        AnimatedContent(
                            targetState = submenuOf,
                            transitionSpec = { (fadeIn(Motion.fade()) togetherWith fadeOut(Motion.fade())).using(SizeTransform(clip = false)) },
                            label = "menuCard",
                        ) { submenu ->
                            ContextMenuCardImpl(
                                actions = submenu?.submenu ?: actions,
                                style = style,
                                onAction = onAction,
                                modifier = Modifier,
                                width = ContextMenuDefaults.CardWidth,
                                metrics = metrics,
                                opaque = !blurs,
                                backRow = if (submenu != null) backRow else null,
                            )
                        }
                    }
                },
                modifier = Modifier.fillMaxSize(),
            ) { measurables, constraints ->
                val p = progress.value
                val inset = ContextMenuDefaults.HeaderInset.toPx() * p.coerceIn(0f, 1f)
                val headerPlaceable = measurables.firstOrNull { it.layoutId == HeaderId }?.measure(
                    Constraints.fixed(
                        width = max(0, (localAnchor.width - 2 * inset).roundToInt()),
                        height = max(0, localAnchor.height.roundToInt()),
                    ),
                )
                val cardPlaceable = measurables.first { it.layoutId == CardId }.measure(Constraints())
                val placement = ContextMenuPlacement.place(
                    anchor = localAnchor,
                    cardWidth = cardPlaceable.width.toFloat(),
                    cardHeight = cardPlaceable.height.toFloat(),
                    containerWidth = constraints.maxWidth.toFloat(),
                    containerHeight = constraints.maxHeight.toFloat(),
                    safeTop = safeTop,
                    safeBottom = safeBottom,
                    gap = ContextMenuDefaults.AnchorGap.toPx(),
                    sideInset = ContextMenuDefaults.SideInset.toPx(),
                    topMargin = ContextMenuDefaults.TopMargin.toPx(),
                    bottomMargin = ContextMenuDefaults.BottomMargin.toPx(),
                    decisionHeight = max(decisionHeight, cardPlaceable.height.toFloat()),
                )
                layout(constraints.maxWidth, constraints.maxHeight) {
                    headerPlaceable?.placeWithLayer((localAnchor.left + inset).roundToInt(), localAnchor.top.roundToInt()) {
                        val b = back.coerceIn(0f, 1f)
                        scaleX = 1f - 0.04f * b
                        scaleY = 1f - 0.04f * b
                    }
                    cardPlaceable.placeWithLayer(placement.x.roundToInt(), placement.y.roundToInt()) {
                        val visible = progress.value
                        val b = back.coerceIn(0f, 1f)
                        val grow = if (reduceMotion) 1f else 0.9f + 0.1f * visible
                        alpha = visible.coerceIn(0f, 1f)
                        scaleX = grow * (1f - 0.06f * b)
                        scaleY = grow * (1f - 0.06f * b)
                        transformOrigin = TransformOrigin(
                            pivotFractionX = if (placement.alignEnd) 1f else 0f,
                            pivotFractionY = if (placement.below) 0f else 1f,
                        )
                    }
                }
            }
        }
    }
}

/** The light menu's backdrop blur (design `Blur Scrim`: blur 12). */
private val MenuBackdropBlur = 12.dp

private const val HeaderId = "header"
private const val CardId = "card"

/**
 * Glyphs the overlays need that `ShroudIcons` does not have yet (Phosphor 2.1.1 regular, MIT —
 * the paths of `assets/regular/<name>.svg`). W1-UI-THEME's icon set may carry them later.
 */
internal object OverlayIcons {
    /** Phosphor `caret-right`: a row that opens a submenu. */
    val CaretRight: ImageVector by lazy {
        phosphor("CaretRight", "M181.66,133.66l-80,80a8,8,0,0,1-11.32-11.32L164.69,128,90.34,53.66a8,8,0,0,1,11.32-11.32l80,80A8,8,0,0,1,181.66,133.66Z")
    }

    /** Phosphor `caret-left`: the submenu's back row (shell-chats §8.8). */
    val CaretLeft: ImageVector by lazy {
        phosphor("CaretLeft", "M165.66,202.34a8,8,0,0,1-11.32,11.32l-80-80a8,8,0,0,1,0-11.32l80-80a8,8,0,0,1,11.32,11.32L91.31,128Z")
    }

    /** Phosphor `caret-up-down`: a menu picker's value (settings-lock §2.5). */
    val CaretUpDown: ImageVector by lazy {
        phosphor(
            "CaretUpDown",
            "M181.66,170.34a8,8,0,0,1,0,11.32l-48,48a8,8,0,0,1-11.32,0l-48-48a8,8,0,0,1,11.32-11.32L128,212.69l42.34-42.35A8,8,0,0,1,181.66,170.34Zm-96-84.68L128,43.31l42.34,42.35a8,8,0,0,0,11.32-11.32l-48-48a8,8,0,0,0-11.32,0l-48,48A8,8,0,0,0,85.66,85.66Z",
        )
    }

    private fun phosphor(name: String, path: String): ImageVector =
        ImageVector.Builder(name = name, defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 256f, viewportHeight = 256f)
            .apply { addPath(pathData = addPathNodes(path), fill = SolidColor(Color.Black)) }
            .build()
}
