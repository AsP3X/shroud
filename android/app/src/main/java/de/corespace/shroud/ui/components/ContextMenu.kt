package de.corespace.shroud.ui.components

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
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import de.corespace.shroud.ui.theme.Motion
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
 * durations, `ChatsView.swift:245-252`; shell-chats §8.8, D6). [checked] draws the leading check
 * of a picker's current value. [onClick] runs as the menu starts to close.
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
 * AqgbA), pickers, link options. [Dark] is dark in both appearances: the message menu card
 * (`MessageActionMenu.swift:416-528`, conversation-thread §16.8).
 */
enum class MenuStyle { Light, Dark }

/**
 * How a menu's rows are laid out.
 *
 * [Leading]: long-press menus (design AqgbA, iOS `.contextMenu` on Android): 44 dp rows, leading
 * glyph, light card radius 22. [Trailing]: button menus (iOS `Menu`; the link options of design
 * r3Ij1X, conversation-compose-media §6): 42 dp rows, 16 sp label, 18 dp glyph trailing in
 * `textSecondary`, radius 14, hairlines between rows.
 */
enum class MenuRows { Leading, Trailing }

/**
 * A long-press menu: a card of [actions] next to [anchor], over a scrim. iOS draws these with
 * `.contextMenu` / `Menu` (`ChatsView.swift:98-105, 135-144, 229-286`, `ChatLinkBar.swift:39-66`);
 * Android draws them itself (no platform popup), shell-chats §8.7 / §10.13.
 *
 * Human: With a [header] the menu lifts it out of the list — the row re-drawn where it was, inset
 * 8 dp each side, rounded and shadowed — and the app behind blurs (API 31+; a plain scrim on
 * Android 11). The card sits 8 dp under the anchor, or 8 dp above it when it would not fit above
 * the navigation bar. Tapping outside, back (also predictive) or any item closes it; an item's
 * action runs as the menu starts to close, so a confirmation it opens lands on top. A row with a
 * submenu cross-fades the card into the submenu, with a back row on top; back then returns to the
 * first list.
 *
 * Agent: [anchor] is in root coordinates and px (`LayoutCoordinates.boundsInRoot()`). Keep the
 * composable in composition while it is open (`if (menuFor != null) ContextMenu(…)`): [onDismiss]
 * is called once the closing animation has run, so the caller drops it then. Long-press triggers
 * (`combinedClickable`) buzz on open (`Haptic.LongPress`); the menu itself does not. Scrim taps in
 * the first 0.4 s after a lifted menu opened are ignored (the release of the opening hold).
 * [paneTitle] is what TalkBack announces ("Chat options"). [dimsBackground] defaults to dimming
 * only when there is a [header] (iOS `Menu`s, like pickers, open over a clear backdrop). [rows]
 * picks the long-press or the button-menu row layout ([MenuRows]). The conversation's message
 * menu has its own Telegram layout and builds its card from [ContextMenuCardSurface],
 * [ContextMenuItem] and [ContextMenuSeparator].
 */
@Composable
fun ContextMenu(
    anchor: Rect,
    actions: List<MenuAction>,
    style: MenuStyle = MenuStyle.Light,
    onDismiss: () -> Unit,
    paneTitle: String = "Options",
    dimsBackground: Boolean? = null,
    rows: MenuRows = MenuRows.Leading,
    header: (@Composable () -> Unit)? = null,
) {
    ContextMenuOverlay(
        anchor = anchor,
        actions = actions,
        style = style,
        onDismiss = onDismiss,
        paneTitle = paneTitle,
        dims = dimsBackground ?: (header != null),
        metrics = MenuCardMetrics.of(style, rows),
        header = header,
    )
}

/** Sizes shared by the menu cards (shell-chats §8.7, conversation-thread §16.8). */
object ContextMenuDefaults {
    val CardWidth: Dp = 250.dp
    val RowHeight: Dp = 44.dp

    /** Gap between the anchor and the card (iOS system menu; shell-chats D15). */
    val AnchorGap: Dp = 8.dp

    /** The card never comes closer to the screen's sides than this (shell-chats §8.7: 16 dp). */
    val SideInset: Dp = 16.dp

    /** Room kept above the card under the status bar. */
    val TopMargin: Dp = 8.dp

    /** Room kept between the card and the navigation bar (shell-chats §8.7: "navigationBars + 10 dp"). */
    val BottomMargin: Dp = 10.dp

    /** Each side of a lifted header is pulled in this far (396 wide at 412). */
    val HeaderInset: Dp = 8.dp

    /** Corner radius of a lifted header (design AqgbA: r18). */
    val HeaderRadius: Dp = 18.dp

    /** Row height of button menus ([MenuRows.Trailing], design r3Ij1X). */
    val CompactRowHeight: Dp = 42.dp

    /**
     * Card height for [rows] rows: the light long-press card adds 6 dp of padding above and below;
     * the dark card and button menus a 1 dp hairline between rows (`MessageContextMenuCard.height`,
     * MAM:433-436).
     */
    fun cardHeight(rows: Int, style: MenuStyle, layout: MenuRows = MenuRows.Leading): Dp {
        val metrics = MenuCardMetrics.of(style, layout)
        val gaps = if (metrics.separators) 1.dp * max(rows - 1, 0) else 0.dp
        return metrics.rowHeight * rows + metrics.verticalPadding * 2 + gaps
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
 * A menu card listing [actions] (rows with a submenu caret or a picker's check included). Flat:
 * the caller decides what a tap does ([onAction]). [ContextMenu] and [MenuPicker] use it inside
 * their overlay; on its own it is the card of an overlay a later package lays out itself.
 */
@Composable
fun ContextMenuCard(
    actions: List<MenuAction>,
    style: MenuStyle,
    onAction: (MenuAction) -> Unit,
    modifier: Modifier = Modifier,
    width: Dp = ContextMenuDefaults.CardWidth,
    rows: MenuRows = MenuRows.Leading,
) {
    ContextMenuCardImpl(
        actions = actions,
        onAction = onAction,
        modifier = modifier,
        width = width,
        metrics = MenuCardMetrics.of(style, rows),
        translucent = false,
        back = null,
    )
}

/**
 * The card itself: [MenuStyle.Light] = card glass (radius 22, 6 dp vertical padding, 1 dp
 * stroke, 0/12/32 shadow — design AqgbA, `Light context menu`); [MenuStyle.Dark] = `#1F1F24` @
 * 0.94, radius 14, 0.5 dp white @ 0.08 stroke (`MessageActionMenu.swift:470-480`); button menus
 * ([MenuRows.Trailing]) radius 14 without padding. [translucent] lets the light card show the
 * blurred backdrop through — only over a blurred backdrop; otherwise it takes the near-opaque
 * no-blur fill (design Gwp1b).
 */
@Composable
fun ContextMenuCardSurface(
    style: MenuStyle,
    modifier: Modifier = Modifier,
    translucent: Boolean = false,
    rows: MenuRows = MenuRows.Leading,
    content: @Composable ColumnScope.() -> Unit,
) {
    ContextMenuCardSurfaceImpl(MenuCardMetrics.of(style, rows), modifier, translucent, content)
}

@Composable
private fun ContextMenuCardSurfaceImpl(
    metrics: MenuCardMetrics,
    modifier: Modifier,
    translucent: Boolean,
    content: @Composable ColumnScope.() -> Unit,
) {
    val palette = overlayPalette()
    val shape = RoundedCornerShape(metrics.radius)
    when (metrics.style) {
        MenuStyle.Light -> Column(
            modifier
                .dropShadow(shape, Shadow(radius = 32.dp, color = OverlayShadows.card, offset = DpOffset(0.dp, 12.dp)))
                .clip(shape)
                .background(if (translucent) palette.cardGlass else palette.cardOpaque)
                .border(1.dp, palette.cardStroke, shape)
                .padding(vertical = metrics.verticalPadding),
            content = content,
        )
        MenuStyle.Dark -> Column(
            modifier
                .clip(shape)
                .background(DarkMenuFill)
                .border(0.5.dp, Color.White.copy(alpha = 0.08f), shape)
                .padding(vertical = metrics.verticalPadding),
            content = content,
        )
    }
}

/**
 * The 1 dp hairline between rows: white @ 0.08 on the dark card (MAM:486-490), `separator` on a
 * light button menu (design r3Ij1X `#E9E9EC`); the light long-press card has none.
 */
@Composable
fun ContextMenuSeparator(style: MenuStyle, rows: MenuRows = MenuRows.Leading) {
    ContextMenuSeparatorImpl(MenuCardMetrics.of(style, rows))
}

@Composable
private fun ContextMenuSeparatorImpl(metrics: MenuCardMetrics) {
    if (!metrics.separators) return
    val color = if (metrics.style == MenuStyle.Dark) Color.White.copy(alpha = 0.08f) else ShroudTheme.colors.separator
    Box(Modifier.fillMaxWidth().height(1.dp).background(color))
}

/**
 * One row: optional leading glyph (or the picker's check), title, a trailing caret when it opens a
 * submenu; in a button menu ([MenuRows.Trailing]) the glyph trails the title instead. Pressed: the highlight lands at once and fades out (`HighlightRowButtonStyle`,
 * `Motion.swift:100-120`), with a light haptic. [muted] draws an information row that is not a
 * button (the message menu's receipt, MAM:449-453). [showsIconSlot] keeps titles aligned when only
 * some rows have a glyph.
 */
@Composable
fun ContextMenuItem(
    action: MenuAction,
    style: MenuStyle,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    muted: Boolean = false,
    showsIconSlot: Boolean = action.icon != null || action.checked,
    rows: MenuRows = MenuRows.Leading,
) {
    ContextMenuItemImpl(
        action = action,
        onClick = onClick,
        modifier = modifier,
        muted = muted,
        showsIconSlot = showsIconSlot,
        metrics = MenuCardMetrics.of(style, rows),
    )
}

// ---- Implementation ------------------------------------------------------------------------------

/** `Color(red: 0.12, green: 0.12, blue: 0.14).opacity(0.94)` (MAM:471). */
private val DarkMenuFill = Color(0xF01F1F24)

/** The danger colour on the always-dark card: the dark `danger` token (#FF453A), MAM:479-481. */
private val DarkMenuDanger = Color(0xFFFF453A)

/** Card and row metrics per card kind. */
@Immutable
internal data class MenuCardMetrics(
    val style: MenuStyle,
    val labelSize: Float,
    val glyphSize: Dp,
    val iconSlot: Dp,
    val horizontalPadding: Dp,
    val rowHeight: Dp = ContextMenuDefaults.RowHeight,
    val radius: Dp,
    val verticalPadding: Dp,
    val separators: Boolean,
    val trailingIcon: Boolean = false,
) {
    companion object {
        /** Design AqgbA: padding h 16, gap 12, glyph 19, label 17 Regular, card r22 with 6 dp padding. */
        val Light = MenuCardMetrics(
            MenuStyle.Light, labelSize = 17f, glyphSize = 19.dp, iconSlot = 19.dp, horizontalPadding = 16.dp,
            radius = 22.dp, verticalPadding = 6.dp, separators = false,
        )

        /** Settings pickers (settings-lock §2.5): 16 sp rows on the light card, check 14. */
        val Picker = Light.copy(labelSize = 16f, iconSlot = 14.dp)

        /** MAM:497-521: icon 15 medium in a 22 wide box, title 16, padding h 14; r14, hairlines. */
        val Dark = MenuCardMetrics(
            MenuStyle.Dark, labelSize = 16f, glyphSize = 15.dp, iconSlot = 22.dp, horizontalPadding = 14.dp,
            radius = 14.dp, verticalPadding = 0.dp, separators = true,
        )

        /** Design r3Ij1X (conversation-compose-media §6): rows 42, padding h 14, label 16, trailing icon 18, r14, hairlines. */
        private fun buttonMenu(style: MenuStyle) = MenuCardMetrics(
            style, labelSize = 16f, glyphSize = 18.dp, iconSlot = 18.dp, horizontalPadding = 14.dp,
            rowHeight = ContextMenuDefaults.CompactRowHeight, radius = 14.dp, verticalPadding = 0.dp, separators = true,
            trailingIcon = true,
        )

        fun of(style: MenuStyle, rows: MenuRows): MenuCardMetrics = when (rows) {
            MenuRows.Leading -> if (style == MenuStyle.Light) Light else Dark
            MenuRows.Trailing -> buttonMenu(style)
        }
    }
}

/**
 * A card of [actions]; with [back] a submenu's card, led by a back row ([backTitle] with a
 * leading caret, shell-chats §8.8).
 */
@Composable
internal fun ContextMenuCardImpl(
    actions: List<MenuAction>,
    onAction: (MenuAction) -> Unit,
    modifier: Modifier,
    width: Dp,
    metrics: MenuCardMetrics,
    translucent: Boolean,
    back: (() -> Unit)?,
    backTitle: String = "",
) {
    val showsIconSlot = actions.any { it.icon != null || it.checked } || back != null
    ContextMenuCardSurfaceImpl(metrics, modifier.width(width), translucent) {
        if (back != null) {
            // The back row keeps its caret leading in every layout: it points the way back.
            ContextMenuItemImpl(
                action = MenuAction(title = backTitle, icon = OverlayIcons.CaretLeft),
                onClick = back,
                modifier = Modifier,
                muted = false,
                showsIconSlot = true,
                metrics = metrics.copy(trailingIcon = false, iconSlot = maxOf(metrics.iconSlot, 13.dp)),
            )
        }
        actions.forEachIndexed { index, action ->
            if (index > 0 || back != null) ContextMenuSeparatorImpl(metrics)
            ContextMenuItemImpl(
                action = action,
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
    onClick: () -> Unit,
    modifier: Modifier,
    muted: Boolean,
    showsIconSlot: Boolean,
    metrics: MenuCardMetrics,
) {
    val colors = ShroudTheme.colors
    val palette = overlayPalette()
    val dark = metrics.style == MenuStyle.Dark
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
        // Button menus draw their trailing glyphs grey (r3Ij1X).
        metrics.trailingIcon -> colors.textSecondary
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
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = metrics.rowHeight)
            .background(highlightColor.copy(alpha = highlightColor.alpha * highlight))
            .then(
                if (muted) {
                    Modifier.semantics(mergeDescendants = true) {}
                } else {
                    Modifier.clickable(
                        interactionSource = interaction,
                        indication = null,
                        enabled = action.enabled,
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
        val glyph: @Composable () -> Unit = {
            Box(Modifier.width(metrics.iconSlot), contentAlignment = Alignment.Center) {
                when {
                    // The title names the row; glyphs stay silent (MAM:505-506).
                    action.checked -> ShroudIcon(OverlayIcons.CheckBold, if (dark) Color.White else colors.accent, size = 14.dp)
                    action.icon != null -> ShroudIcon(action.icon, glyphColor, size = metrics.glyphSize)
                }
            }
        }
        if (showsIconSlot && !metrics.trailingIcon) glyph()
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
        } else if (showsIconSlot && metrics.trailingIcon) {
            glyph()
        }
    }
}

/** How the card comes in: lifted with its row (Telegram spring) or popped from its anchor. */
private enum class MenuEntrance { Lift, Pop }

/**
 * The overlay behind [ContextMenu] and [MenuPicker]: scrim, optional lifted [header], card.
 * Opens on composition, closes itself (animated) and then calls [onDismiss].
 */
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
    val blurs = dims && style == MenuStyle.Light && overlayCanBlur()

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
    val onAction: (MenuAction) -> Unit = { action ->
        when {
            closing || !action.enabled -> Unit
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
            val rows = listOf(actions.size) + actions.mapNotNull { it.submenu?.size?.plus(1) }
            val rowHeight = metrics.rowHeight.toPx()
            val hairline = if (metrics.separators) 1.dp.toPx() else 0f
            val most = rows.max()
            most * rowHeight + 2 * metrics.verticalPadding.toPx() + hairline * max(most - 1, 0)
        }

        Box(
            Modifier
                .fillMaxSize()
                .onGloballyPositioned { origin = it.positionInRoot() }
                .overlayPane(paneTitle, onDismiss = close),
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
                            style == MenuStyle.Dark -> Modifier
                                .background(Color.Black.copy(alpha = 0.42f))
                                .background(Color(0xFF0F0F14).copy(alpha = 0.28f))
                            blurs -> Modifier.background(palette.menuScrim)
                            else -> Modifier.background(palette.menuScrimNoBlur)
                        },
                    )
                    .dismissOnTap(
                        openedAt = openedAt,
                        guardMillis = if (entrance == MenuEntrance.Lift) MENU_OPEN_TAP_GUARD_MS else 0L,
                        onDismiss = close,
                    ),
            )

            Layout(
                content = {
                    if (header != null) {
                        val p = progress.value.coerceIn(0f, 1f)
                        val shape = RoundedCornerShape(ContextMenuDefaults.HeaderRadius * p)
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
                                onAction = onAction,
                                modifier = Modifier,
                                width = ContextMenuDefaults.CardWidth,
                                metrics = metrics,
                                translucent = blurs,
                                back = if (submenu != null) ({ submenuOf = null }) else null,
                                backTitle = submenu?.title.orEmpty(),
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
                        val lean = backLeanScale(back, maxShrink = 0.04f)
                        scaleX = lean
                        scaleY = lean
                    }
                    cardPlaceable.placeWithLayer(placement.x.roundToInt(), placement.y.roundToInt()) {
                        val visible = progress.value
                        val grow = if (reduceMotion) 1f else 0.9f + 0.1f * visible
                        val lean = backLeanScale(back)
                        alpha = visible.coerceIn(0f, 1f)
                        scaleX = grow * lean
                        scaleY = grow * lean
                        // Grows out of the corner nearest the anchor (settings-lock §2.5).
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
 * the paths of `assets/regular/<name>.svg`, already listed in `assets/licenses/icons.txt` as the
 * Phosphor set). W1-UI-THEME's icon set carries them as well; W1-INT may point these at it.
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

    /** Phosphor `check-bold`: a picker's current value (settings-lock §2.5: "check 14 bold accent"). */
    val CheckBold: ImageVector by lazy {
        phosphor("CheckBold", "M232.49,80.49l-128,128a12,12,0,0,1-17,0l-56-56a12,12,0,1,1,17-17L96,183,215.51,63.51a12,12,0,0,1,17,17Z")
    }

    private fun phosphor(name: String, path: String): ImageVector =
        ImageVector.Builder(name = name, defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 256f, viewportHeight = 256f)
            .apply { addPath(pathData = addPathNodes(path), fill = SolidColor(Color.Black)) }
            .build()
}
