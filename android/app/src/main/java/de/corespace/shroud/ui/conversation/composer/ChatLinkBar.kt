package de.corespace.shroud.ui.conversation.composer

import androidx.compose.animation.Crossfade
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import de.corespace.shroud.core.model.Haptic
import de.corespace.shroud.ui.components.ContextMenu
import de.corespace.shroud.ui.components.MenuAction
import de.corespace.shroud.ui.components.MenuRows
import de.corespace.shroud.ui.components.ShroudIcon
import de.corespace.shroud.ui.components.ShroudText
import de.corespace.shroud.ui.components.pressable
import de.corespace.shroud.ui.theme.Motion
import de.corespace.shroud.ui.theme.ShroudIcons
import de.corespace.shroud.ui.theme.ShroudTheme
import de.corespace.shroud.ui.theme.inter

/**
 * The link-preview strip above the composer (iOS `ChatLinkBar`, `ChatLinkBar.swift:19-142`; design
 * k81Ye, yYKYM, r3Ij1X; conversation-compose-media §6): the reply strip's sibling — same glyph column,
 * stripe and ✕ — so a quote and a preview read as the same kind of attachment. Tapping the preview
 * opens Telegram's link options (above / below the text, larger / smaller picture, no preview); ✕
 * drops the preview for this link and leaves the text alone.
 */
@Composable
internal fun ChatLinkBar(
    state: ChatLinkBarState,
    showsAboveText: Boolean,
    canToggleImageSize: Boolean,
    usesLargeImage: Boolean,
    onToggleAboveText: () -> Unit,
    onToggleImageSize: () -> Unit,
    onRemove: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = ShroudTheme.colors
    var blockBounds by remember { mutableStateOf(Rect.Zero) }
    var menuAnchor by remember { mutableStateOf<Rect?>(null) }
    Row(
        modifier
            .fillMaxWidth()
            .padding(start = 10.dp, end = 6.dp, top = 6.dp, bottom = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(width = 26.dp, height = 34.dp), contentAlignment = Alignment.Center) {
            ShroudIcon(ShroudIcons.Link, colors.accent, size = 19.dp)
        }
        LinkPreviewBlock(
            state = state,
            modifier = Modifier
                .weight(1f)
                .onGloballyPositioned { blockBounds = it.boundsInRoot() }
                .pressable(scale = 1f, dimming = 0.12f, haptic = Haptic.None) { menuAnchor = blockBounds }
                .clearAndSetSemantics {
                    contentDescription = state.accessibilityText
                    onClick(label = "Shows link preview options") {
                        menuAnchor = blockBounds
                        true
                    }
                },
        )
        StripCloseButton(icon = ShroudIcons.X, contentDescription = "Remove link preview", onClick = onRemove)
    }
    menuAnchor?.let { anchor ->
        ContextMenu(
            anchor = anchor,
            actions = linkMenuActions(
                state = state,
                showsAboveText = showsAboveText,
                canToggleImageSize = canToggleImageSize,
                usesLargeImage = usesLargeImage,
                onToggleAboveText = onToggleAboveText,
                onToggleImageSize = onToggleImageSize,
                onRemove = onRemove,
            ),
            onDismiss = { menuAnchor = null },
            paneTitle = "Link preview options",
            rows = MenuRows.Trailing,
        )
    }
}

/**
 * The link options (iOS `Menu`, `ChatLinkBar.swift:39-60`; design r3Ij1X): the layout rows only for
 * a loaded preview, the image size only when both layouts exist, "Remove Preview" always.
 */
internal fun linkMenuActions(
    state: ChatLinkBarState,
    showsAboveText: Boolean,
    canToggleImageSize: Boolean,
    usesLargeImage: Boolean,
    onToggleAboveText: () -> Unit,
    onToggleImageSize: () -> Unit,
    onRemove: () -> Unit,
): List<MenuAction> = buildList {
    if (state is ChatLinkBarState.Ready) {
        add(
            MenuAction(
                title = if (showsAboveText) "Show Below Text" else "Show Above Text",
                icon = if (showsAboveText) ShroudIcons.ArrowDownToLine else ShroudIcons.ArrowUpToLine,
                onClick = onToggleAboveText,
            ),
        )
        if (canToggleImageSize) {
            add(
                MenuAction(
                    title = if (usesLargeImage) "Smaller Image" else "Larger Image",
                    icon = if (usesLargeImage) ShroudIcons.Minimize2 else ShroudIcons.Maximize2,
                    onClick = onToggleImageSize,
                ),
            )
        }
    }
    add(MenuAction(title = "Remove Preview", icon = ShroudIcons.Trash2, destructive = true, onClick = onRemove))
}

/**
 * Title over snippet with the accent stripe, drawn like the reply quote (`ChatLinkBar.swift:87-112`):
 * the link itself (loading, or no description) reads muted and middle-truncated; content changes
 * cross-fade.
 */
@Composable
private fun LinkPreviewBlock(state: ChatLinkBarState, modifier: Modifier = Modifier) {
    val colors = ShroudTheme.colors
    Box(
        modifier
            .height(IntrinsicSize.Min)
            .clip(RoundedCornerShape(6.dp)),
    ) {
        Box(
            Modifier
                .align(Alignment.CenterStart)
                .width(STRIPE_WIDTH)
                .fillMaxHeight()
                .background(colors.accent),
        )
        Crossfade(targetState = state, animationSpec = Motion.fade(), label = "linkBar") { shown ->
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(start = STRIPE_WIDTH + 6.dp, end = 8.dp, top = 4.dp, bottom = 4.dp),
                verticalArrangement = Arrangement.spacedBy(1.dp),
            ) {
                ShroudText(
                    shown.stripTitle,
                    inter(15f, FontWeight.SemiBold),
                    colors.accent,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                ShroudText(
                    shown.stripSnippet,
                    inter(15f),
                    if (shown.isSnippetMuted) colors.textSecondary else colors.textPrimary,
                    maxLines = 1,
                    overflow = if (shown.isSnippetMuted) TextOverflow.MiddleEllipsis else TextOverflow.Ellipsis,
                )
            }
        }
    }
}

private val STRIPE_WIDTH = 3.dp
