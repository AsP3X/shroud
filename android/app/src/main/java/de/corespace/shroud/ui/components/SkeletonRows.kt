package de.corespace.shroud.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import de.corespace.shroud.ui.theme.ShroudTheme

/**
 * Placeholder in [ChatRow]'s geometry while a list's first page loads (`SkeletonRows.swift:7-38`;
 * shell-chats §10.5): 72 dp (52 dp circle, padding h 16 v 10, spacing 12), a title bar
 * [titleWidth] × 13 and a subtitle bar [subtitleWidth] × 11 (spacing 8), and a 34 × 10 time bar,
 * all `backgroundGrouped`. Showing the *shape* of the answer keeps the layout from jumping when
 * the rows arrive. Bars never push the time bar out on a narrow phone. Hidden from TalkBack.
 */
@Composable
fun SkeletonChatRow(modifier: Modifier = Modifier, titleWidth: Dp = 120.dp, subtitleWidth: Dp = 200.dp) {
    val fill = ShroudTheme.colors.backgroundGrouped
    Row(
        modifier
            .clearAndSetSemantics {}
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(52.dp).background(fill, CircleShape))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SkeletonBar(titleWidth, 13.dp)
            SkeletonBar(subtitleWidth, 11.dp)
        }
        // iOS `Spacer(minLength: 8)` inside the 12-spaced HStack.
        Spacer(Modifier.width(8.dp))
        SkeletonBar(34.dp, 10.dp)
    }
}

@Composable
private fun SkeletonBar(width: Dp, height: Dp) {
    Box(Modifier.width(width).height(height).background(ShroudTheme.colors.backgroundGrouped, RoundedCornerShape(height / 2)))
}

/**
 * [rows] shimmering [SkeletonChatRow]s (`SkeletonRows.swift:40-63`) with the fixed width table of
 * [SkeletonWidths], stable across redraws so nothing twitches. TalkBack hears one "Loading" instead
 * of an empty screen. Callers fade it in and out with `Motion.fade()` (iOS `.transition(.opacity)`).
 */
@Composable
fun SkeletonChatList(rows: Int = 7, modifier: Modifier = Modifier) {
    Column(
        modifier
            .clearAndSetSemantics { contentDescription = "Loading" }
            .shimmering(),
    ) {
        repeat(rows) { index ->
            val (title, subtitle) = SkeletonWidths.at(index)
            SkeletonChatRow(titleWidth = title.dp, subtitleWidth = subtitle.dp)
        }
    }
}

/** Deterministic pseudo-random (title, subtitle) bar widths in dp (`SkeletonRows.swift:44-48`). */
object SkeletonWidths {
    val widths: List<Pair<Int, Int>> = listOf(
        132 to 214, 96 to 168, 148 to 190, 110 to 232,
        124 to 152, 88 to 205, 140 to 176,
    )

    fun at(index: Int): Pair<Int, Int> = widths[index.mod(widths.size)]
}

@Preview(name = "Skeleton · 360", widthDp = 360)
@Composable
private fun SkeletonPreview() {
    ShroudTheme(dark = false) {
        SkeletonChatList(modifier = Modifier.background(ShroudTheme.colors.background))
    }
}
