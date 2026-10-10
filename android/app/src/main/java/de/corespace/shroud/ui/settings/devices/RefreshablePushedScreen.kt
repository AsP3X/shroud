package de.corespace.shroud.ui.settings.devices

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsBottomHeight
import androidx.compose.foundation.layout.windowInsetsTopHeight
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import de.corespace.shroud.ui.components.GlassBarMetrics
import de.corespace.shroud.ui.components.GlassBarTitle
import de.corespace.shroud.ui.components.GlassCircleButton
import de.corespace.shroud.ui.components.LocalGlassBackdrop
import de.corespace.shroud.ui.components.PullToRefresh
import de.corespace.shroud.ui.components.PushedBarMetrics
import de.corespace.shroud.ui.components.PushedScreen
import de.corespace.shroud.ui.components.ScrollEdgeEffect
import de.corespace.shroud.ui.components.edgeEffectSource
import de.corespace.shroud.ui.components.rememberKeyboardDismissOnDrag
import de.corespace.shroud.ui.theme.ShroudIcons
import de.corespace.shroud.ui.theme.ShroudTheme
import dev.chrisbanes.haze.rememberHazeState

/**
 * [PushedScreen] whose content pulls to refresh — iOS `.refreshable` on the Devices list
 * (`DevicesView.swift:73-77`; settings-lock §4.2, shell-chats §10.16). The kit's [PushedScreen]
 * scrolls its content itself and has no refresh hook, and wrapping it in [PullToRefresh] would drag
 * the bar down with the list; so this draws the same bar (44 dp, the glass back circle, a centred
 * title, the scroll edge effect) over a list that [PullToRefresh] moves on its own, the spinner
 * just under the bar.
 *
 * Contract change request (W3-SETTINGS-B → W3-INT): give `PushedScreen` an
 * `onRefresh: (suspend () -> Unit)? = null` parameter doing exactly this; Devices then uses it and
 * this file goes.
 */
@Composable
internal fun RefreshablePushedScreen(
    title: String,
    onBack: () -> Unit,
    onRefresh: suspend () -> Unit,
    modifier: Modifier = Modifier,
    scrollState: ScrollState = rememberScrollState(),
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = ShroudTheme.colors
    val backdrop = rememberHazeState()
    val statusTop = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val scrolledUnder by remember(scrollState) { derivedStateOf { scrollState.value > 0 } }
    Column(modifier.fillMaxSize().background(colors.backgroundGrouped)) {
        Box(Modifier.weight(1f).fillMaxWidth()) {
            PullToRefresh(onRefresh = onRefresh, indicatorTop = statusTop + PushedBarMetrics.height) {
                Column(
                    Modifier
                        .fillMaxSize()
                        .edgeEffectSource(backdrop)
                        .nestedScroll(rememberKeyboardDismissOnDrag(scrollState.interactionSource))
                        .verticalScroll(scrollState)
                        .padding(top = statusTop + PushedBarMetrics.height),
                    content = content,
                )
            }
            ScrollEdgeEffect(visible = scrolledUnder, extent = statusTop + PushedBarMetrics.height, backdrop = backdrop)
            CompositionLocalProvider(LocalGlassBackdrop provides backdrop) {
                Column(Modifier.fillMaxWidth()) {
                    Spacer(Modifier.windowInsetsTopHeight(WindowInsets.statusBars))
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .height(PushedBarMetrics.height)
                            .padding(horizontal = PushedBarMetrics.horizontalInset),
                    ) {
                        GlassCircleButton(
                            ShroudIcons.CaretLeftBold,
                            "Back",
                            onBack,
                            Modifier.align(Alignment.CenterStart),
                            tint = colors.textPrimary,
                        )
                        // Both sides keep the back circle's width plus the gap free, so the title
                        // stays centred on the screen (`PushedBarMetrics.titleWidth`).
                        Box(
                            Modifier
                                .align(Alignment.Center)
                                .padding(horizontal = GlassBarMetrics.controlSize + PushedBarMetrics.spacing),
                        ) {
                            GlassBarTitle(title)
                        }
                    }
                }
            }
        }
        Spacer(Modifier.windowInsetsBottomHeight(WindowInsets.navigationBars.union(WindowInsets.ime)))
    }
}
