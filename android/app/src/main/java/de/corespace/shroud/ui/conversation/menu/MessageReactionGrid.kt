package de.corespace.shroud.ui.conversation.menu

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import de.corespace.shroud.ui.components.ShroudIcon
import de.corespace.shroud.ui.components.ShroudText
import de.corespace.shroud.ui.components.pressable
import de.corespace.shroud.ui.theme.Motion
import de.corespace.shroud.ui.theme.ShroudIcons
import de.corespace.shroud.ui.theme.inter

/**
 * The expanded set's numbers (`MessageReactionGrid`, `MessageActionMenu.swift:133-173`;
 * conversation-thread §16.7). Eight columns at the bar's pitch, so the quick seven land in the first
 * row a hair from where they were and the eighth takes the place of "More". Pure; dp.
 */
object MessageReactionGridMetrics {
    const val COLUMNS = 8
    const val CELL = 40f
    const val PADDING = 10f
    const val SEARCH_HEIGHT = 36f

    /** Between the search row and the grid. */
    const val SEARCH_GAP = 6f

    /** Rows shown before the grid scrolls. */
    const val VISIBLE_ROWS = 5.5f

    /** The grid's side padding: eight cells fill the bar's width ((334 − 320) / 2 = 7). */
    val sidePadding: Float get() = (MessageReactionBarMetrics.barWidth - CELL * COLUMNS) / 2

    /** The panel's tallest: the search row and 5.5 rows of emoji — `height(rows: 6)` = 282 (`:161`). */
    val height: Float get() = height(rows = kotlin.math.ceil(VISIBLE_ROWS).toInt())

    /**
     * The panel's height showing [rows] rows: as tall as they need, up to 5.5 rows (the rest
     * scrolls); never less than one row, which the "no matches" line takes (`:163-168`).
     */
    fun height(rows: Int): Float {
        val shown = minOf(maxOf(rows, 1).toFloat(), VISIBLE_ROWS)
        return PADDING + SEARCH_HEIGHT + SEARCH_GAP + CELL * shown + PADDING
    }

    /** How many rows [count] emoji fill (`:170-173`). */
    fun rows(count: Int): Int = (count + COLUMNS - 1) / COLUMNS

    /** The empty-search line (`:182`): the trimmed query in curly quotes. */
    fun noMatches(query: String): String = "No reactions match “${query.trim()}”"
}

/**
 * The full standard set with a search field over it, inside [MessageReactionPanel] once the bar has
 * grown (`MessageReactionGrid`, `MessageActionMenu.swift:136-284`; conversation-thread §16.7).
 *
 * Human: a capsule search field ("Search reactions", magnifying glass, a clear button once typed)
 * with a "fewer" button beside it, and the matches below in eight 40 dp columns; nothing matching
 * says so in one line. Scrolling the grid puts the keyboard away at once. Results switch without
 * animation — only the panel around them animates.
 *
 * Agent: [results] is `ReactionSearch.matches(query, all)`, computed by the panel (it sizes itself
 * by them). [onReaction] gets the emoji and its frame in root px.
 */
@Composable
fun MessageReactionGrid(
    onReaction: (String, Rect?) -> Unit,
    onCollapse: () -> Unit,
    selected: Set<String>,
    query: String,
    onQueryChange: (String) -> Unit,
    results: List<String>,
    modifier: Modifier = Modifier,
    emojiModifier: @Composable (String) -> Modifier = { Modifier },
) {
    val frames = remember { ReactionPickFrames() }
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    // `.scrollDismissesKeyboard(.immediately)` (MAM:224): the first scroll puts the keyboard away.
    val dismissOnScroll = remember(focusManager, keyboard) {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                if (source == NestedScrollSource.UserInput && available.y != 0f) {
                    keyboard?.hide()
                    focusManager.clearFocus()
                }
                return Offset.Zero
            }
        }
    }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(MessageReactionGridMetrics.SEARCH_GAP.dp)) {
        SearchRow(
            query = query,
            onQueryChange = onQueryChange,
            onCollapse = {
                focusManager.clearFocus()
                onCollapse()
            },
            modifier = Modifier
                .padding(horizontal = MessageReactionGridMetrics.PADDING.dp)
                .padding(top = MessageReactionGridMetrics.PADDING.dp),
        )
        if (results.isEmpty()) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = MessageReactionGridMetrics.CELL.dp)
                    .padding(horizontal = 20.dp),
                contentAlignment = Alignment.Center,
            ) {
                ShroudText(
                    MessageReactionGridMetrics.noMatches(query),
                    inter(14f),
                    Color.White.copy(alpha = 0.55f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        } else {
            LazyVerticalGrid(
                columns = GridCells.Fixed(MessageReactionGridMetrics.COLUMNS),
                modifier = Modifier.nestedScroll(dismissOnScroll),
                contentPadding = PaddingValues(
                    start = MessageReactionGridMetrics.sidePadding.dp,
                    end = MessageReactionGridMetrics.sidePadding.dp,
                    bottom = MessageReactionGridMetrics.PADDING.dp,
                ),
            ) {
                items(results, key = { it }) { emoji ->
                    ReactionCell(
                        emoji = emoji,
                        cellSize = MessageReactionGridMetrics.CELL,
                        ringSize = MessageReactionGridMetrics.CELL,
                        selected = emoji in selected,
                        onClick = { onReaction(emoji, frames[emoji]) },
                        onPositioned = { frames.report(emoji, it) },
                        glyphModifier = emojiModifier(emoji),
                    )
                }
            }
        }
    }
}

/** The search field and, beside it, the way back to the bar (`MessageActionMenu.swift:228-283`). */
@Composable
private fun SearchRow(query: String, onQueryChange: (String) -> Unit, onCollapse: () -> Unit, modifier: Modifier = Modifier) {
    var focused by remember { mutableStateOf(false) }
    val ring by animateFloatAsState(if (focused) 0.35f else 0f, Motion.snappy(), label = "reactionSearchRing")
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        Row(
            Modifier
                .weight(1f)
                .height(MessageReactionGridMetrics.SEARCH_HEIGHT.dp)
                .clip(CircleShape)
                .background(Color.White.copy(alpha = 0.1f))
                .border(1.dp, Color.White.copy(alpha = ring), CircleShape)
                .padding(horizontal = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ShroudIcon(ShroudIcons.MagnifyingGlassBold, Color.White.copy(alpha = if (focused) 0.85f else 0.55f), size = 15.dp)
            Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                if (query.isEmpty()) {
                    ShroudText("Search reactions", inter(15f), Color.White.copy(alpha = 0.45f), maxLines = 1)
                }
                BasicTextField(
                    value = query,
                    onValueChange = onQueryChange,
                    singleLine = true,
                    textStyle = inter(15f).copy(color = Color.White),
                    cursorBrush = SolidColor(Color.White),
                    keyboardOptions = KeyboardOptions(
                        capitalization = KeyboardCapitalization.None,
                        autoCorrectEnabled = false,
                        imeAction = ImeAction.Search,
                    ),
                    keyboardActions = KeyboardActions(onSearch = {}),
                    modifier = Modifier
                        .fillMaxWidth()
                        .onFocusChanged { focused = it.isFocused }
                        .semantics { contentDescription = "Search reactions" },
                )
            }
            AnimatedVisibility(
                visible = query.isNotEmpty(),
                enter = Motion.iconSwap.enter,
                exit = Motion.iconSwap.exit,
            ) {
                // 44 dp to the finger without moving the 24 dp glyph box (MAM:252-253).
                Box(
                    Modifier
                        .size(24.dp)
                        .pressable(scale = 0.8f, dimming = 0f, onClick = { onQueryChange("") })
                        .semantics { contentDescription = "Clear search" },
                    contentAlignment = Alignment.Center,
                ) {
                    ShroudIcon(ShroudIcons.XCircleFill, Color.White.copy(alpha = 0.55f), size = 14.dp)
                }
            }
        }
        Box(
            Modifier
                .size(30.dp)
                .clip(CircleShape)
                .background(Color.White.copy(alpha = 0.12f))
                .pressable(scale = 0.85f, dimming = 0f, onClick = onCollapse)
                .semantics { contentDescription = "Fewer reactions" },
            contentAlignment = Alignment.Center,
        ) {
            ShroudIcon(ShroudIcons.CaretUpBold, Color.White.copy(alpha = 0.85f), size = 13.dp)
        }
    }
}
