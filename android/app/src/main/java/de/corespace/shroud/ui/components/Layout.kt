package de.corespace.shroud.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import de.corespace.shroud.ui.theme.ShroudTheme

/** Horizontal screen inset from the design system (`ScreenContent.swift:4-21`). */
val ScreenInset = 20.dp

/**
 * Grouped background, edge to edge, content kept clear of the system bars, the cutout and the
 * keyboard (`GroupedScreen`, `ScreenContent.swift:24-36`). Onboarding only: screens inside the
 * main shell use [MainScrollScreen] or [PushedScreen], whose bars own the insets.
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
