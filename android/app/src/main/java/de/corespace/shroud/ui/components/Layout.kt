package de.corespace.shroud.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import de.corespace.shroud.ui.theme.ShroudTheme

/** Horizontal screen inset from the design system (`ScreenContent.swift`). */
val ScreenInset = 20.dp

/**
 * Grouped background, edge to edge, content kept clear of the system bars, the cutout and the
 * keyboard (`GroupedScreen`).
 */
@Composable
fun GroupedScreen(modifier: Modifier = Modifier, content: @Composable BoxScope.() -> Unit) {
    Box(
        modifier
            .fillMaxSize()
            .background(ShroudTheme.colors.backgroundGrouped)
            .safeDrawingPadding(),
        content = content,
    )
}

/** Leading / trailing controls of a glass bar row (`GlassBarRow`): 16 inset, 44 high, 6 below. */
@Composable
fun GlassBarRow(
    modifier: Modifier = Modifier,
    leading: @Composable RowScope.() -> Unit = {},
    trailing: @Composable RowScope.() -> Unit = {},
) {
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = 44.dp)
            .padding(start = 16.dp, end = 16.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        leading()
        Spacer(Modifier.weight(1f))
        trailing()
    }
}
