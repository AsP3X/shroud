package de.corespace.shroud.ui.conversation.menu

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.dropShadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.shadow.Shadow
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import de.corespace.shroud.core.messaging.reactions.ReactionSearch
import de.corespace.shroud.core.model.Haptic
import de.corespace.shroud.ui.theme.Motion
import de.corespace.shroud.ui.theme.ShroudTheme
import de.corespace.shroud.ui.theme.rememberHaptics
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Where the reaction panel sits (`MessageReactionPanel.frame`, `MessageActionMenu.swift:314-333`;
 * conversation-thread §16.7). Pure; dp.
 */
object MessageReactionPanelLayout {
    /** The dark surface both states share — not the theme: the menu floats over a dimmed thread (`:315`). */
    val surface = Color(red = 0.14f, green = 0.14f, blue = 0.16f)

    const val EXPANDED_CORNER_RADIUS = 22f

    /**
     * Collapsed: the bar's frame. Expanded: [height] tall — as tall as the rows a search leaves —
     * grown down from the bar's top edge, no higher than 8 dp under the status bar, no lower than
     * 10 dp above the navigation bar or the keyboard, and no taller than that leaves room for
     * (`:322-333`).
     */
    fun frame(
        expanded: Boolean,
        bar: Rect,
        container: Size,
        safeArea: MenuInsets,
        height: Float = MessageReactionGridMetrics.height,
    ): Rect {
        if (!expanded) return bar
        val clamped = min(height, container.height - safeArea.top - safeArea.bottom - 16f)
        val lowest = container.height - safeArea.bottom - 10f - clamped
        val top = max(safeArea.top + 8f, min(bar.top, lowest))
        return Rect(bar.left, top, bar.left + bar.width, top + clamped)
    }
}

/**
 * The reaction bar and the full set it grows into, one surface over the lifted bubble
 * (`MessageReactionPanel`, `MessageActionMenu.swift:288-391`; design `Reaction Panel` 334 × 282).
 *
 * Human: "More" grows the capsule in place into a rounded panel — down from the bar's top edge, kept
 * on screen — with a search field over the grid and a "fewer" button back. The quick seven glide
 * into the grid's first row while the rest fade in; the panel's shape, size and place animate
 * together (`Motion.standard`), so it reads as one thing changing rather than a swap. A light haptic
 * marks each change; collapsing clears the search.
 *
 * Agent: draw it in a container-sized slot (top-left at the overlay's origin). [bar] (the bar's
 * frame, which rides the hero flight) and [container] are dp in the overlay; [safeArea] includes the
 * keyboard, so an open search never leaves the panel under it. Opacity follows [progress]; it only
 * takes touches above 0.5 (below, a tap is the backdrop's: [onBackdropTap]). [expanded] is the host's ("More" also dims the bubble and card behind).
 */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun MessageReactionPanel(
    onReaction: (String, Rect?) -> Unit,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    progress: Float,
    selected: Set<String>,
    bar: () -> Rect,
    container: Size,
    safeArea: MenuInsets,
    onBackdropTap: () -> Unit,
) {
    val reduceMotion = ShroudTheme.reduceMotion
    val haptic = rememberHaptics()
    var query by remember { mutableStateOf("") }
    val results = remember(query) { ReactionSearch.matches(query, MessageReactionBarMetrics.expanded) }
    val wanted = MessageReactionGridMetrics.height(MessageReactionGridMetrics.rows(results.size))
    val spec = Motion.respecting(reduceMotion, Motion.standard<Float>())
    // Keyed on the wanted height and the state, not the frame: the bar rides the hero with no lag.
    val expansion by animateFloatAsState(if (expanded) 1f else 0f, spec, label = "reactionPanelExpansion")
    val height by animateFloatAsState(wanted, spec, label = "reactionPanelHeight")
    // The size doesn't depend on where the bar is (only the place does), so the bar's position is read
    // while placing: it rides the hero's flight and the stack's scroll without recomposing the panel.
    val barSize = Size(MessageReactionBarMetrics.barWidth, MessageReactionBarMetrics.barHeight)
    val atOrigin = Rect(Offset.Zero, barSize)
    val openSize = MessageReactionPanelLayout.frame(true, atOrigin, container, safeArea, height).size
    val size = MessageMenuLayout.lerp(atOrigin, Rect(Offset.Zero, openSize), expansion).size
    val collapsedRadius = MessageReactionBarMetrics.barHeight / 2
    val radius = collapsedRadius + (MessageReactionPanelLayout.EXPANDED_CORNER_RADIUS - collapsedRadius) * expansion
    val shape = RoundedCornerShape(radius.dp)
    val setExpanded: (Boolean) -> Unit = { next ->
        haptic(Haptic.Light)
        if (!next) query = ""
        onExpandedChange(next)
    }
    Box(
        Modifier
            .offset {
                val current = bar()
                val frame = MessageMenuLayout.lerp(current, MessageReactionPanelLayout.frame(true, current, container, safeArea, height), expansion)
                IntOffset(frame.left.dp.roundToPx(), frame.top.dp.roundToPx())
            }
            .requiredSize(size.width.dp, size.height.dp)
            .graphicsLayer { alpha = progress.coerceIn(0f, 1f) }
            // Its own layer over the card: solid once grown, a shadow that deepens as it lifts (`:369-375`).
            .dropShadow(
                shape,
                Shadow(
                    radius = (12f + 14f * expansion).dp,
                    color = Color.Black.copy(alpha = 0.3f + 0.2f * expansion),
                    offset = DpOffset(0.dp, (5f + 7f * expansion).dp),
                ),
            )
            .clip(shape)
            .background(MessageReactionPanelLayout.surface.copy(alpha = 0.94f + 0.06f * expansion))
            .border(0.5.dp, Color.White.copy(alpha = 0.08f + 0.04f * expansion), shape)
            .then(if (progress > 0.5f) Modifier else Modifier.tapsGoTo(onBackdropTap)),
        contentAlignment = Alignment.TopStart,
    ) {
        SharedTransitionLayout {
            AnimatedContent(
                targetState = expanded,
                transitionSpec = { fadeIn(spec) togetherWith fadeOut(spec) },
                contentAlignment = Alignment.TopStart,
                label = "reactionPanelContent",
            ) { isExpanded ->
                // The quick seven glide between the bar and the grid's first row (`reactionGlide`, `:393-404`).
                val glide: @Composable (String) -> Modifier = { emoji ->
                    if (emoji in MessageReactionBarMetrics.reactions) {
                        Modifier.sharedElement(rememberSharedContentState(GLIDE_KEY + emoji), this@AnimatedContent)
                    } else {
                        Modifier
                    }
                }
                if (isExpanded) {
                    MessageReactionGrid(
                        onReaction = onReaction,
                        onCollapse = { setExpanded(false) },
                        selected = selected,
                        query = query,
                        onQueryChange = { query = it },
                        results = results,
                        modifier = Modifier.requiredSize(openSize.width.dp, openSize.height.dp),
                        emojiModifier = glide,
                    )
                } else {
                    MessageReactionBar(
                        onReaction = onReaction,
                        onMore = { setExpanded(true) },
                        selected = selected,
                        drawsCapsule = false,
                        emojiModifier = glide,
                    )
                }
            }
        }
    }
}

private const val GLIDE_KEY = "reaction-glide-"
