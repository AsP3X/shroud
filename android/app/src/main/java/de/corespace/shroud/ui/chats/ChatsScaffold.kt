package de.corespace.shroud.ui.chats

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsTopHeight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import de.corespace.shroud.ui.components.GlassBarMetrics
import de.corespace.shroud.ui.components.GlassBarRow
import de.corespace.shroud.ui.components.GlassBarTitle
import de.corespace.shroud.ui.components.LocalGlassBackdrop
import de.corespace.shroud.ui.components.MainScrollBackdrop
import de.corespace.shroud.ui.components.PullToRefresh
import de.corespace.shroud.ui.components.ScrollEdgeEffect
import de.corespace.shroud.ui.components.edgeEffectSource
import de.corespace.shroud.ui.components.rememberGlassBackdrop
import de.corespace.shroud.ui.components.rememberKeyboardDismissOnDrag
import de.corespace.shroud.ui.shell.LocalTabBarClearance
import de.corespace.shroud.ui.theme.ShroudTheme

/**
 * The Chats tab's root chrome: `MainScrollScreen` (`TransparentMainChrome.swift:3-56`; shell-chats
 * §7) plus the two things it cannot do yet — pull to refresh on the list alone (`.refreshable`,
 * `ChatsView.swift:175-181`) and the floating tab bar's clearance (`\.tabBarClearance`, shell-chats §4.2).
 *
 * Same layout as `MainScrollScreen`: one [LazyColumn] with the [header] first (it scrolls away
 * under the bar: offline banner, header search), the bar row pinned under the status bar with the
 * centred [title] and [trailing] glass controls, the scroll edge effect while content is under it,
 * keyboard dismissed on drag. Differences: the list (not the bar) moves with the pull and the
 * spinner opens under the bar; the bottom padding is [LocalTabBarClearance] when the tab bar is up,
 * else the navigation bar or keyboard.
 *
 * Contract change request (package report): `MainScrollScreen` gains `onRefresh` and the
 * clearance, and this file collapses into it.
 */
@Composable
internal fun ChatsScaffold(
    title: String,
    state: LazyListState,
    trailing: @Composable RowScope.() -> Unit,
    header: @Composable () -> Unit,
    onRefresh: suspend () -> Unit,
    modifier: Modifier = Modifier,
    content: LazyListScope.() -> Unit,
) {
    val backdrop = rememberGlassBackdrop()
    val statusTop = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val barBlock = statusTop + GlassBarMetrics.mainBarHeight
    val bottom = ChatsLayout.listBottomPadding(LocalTabBarClearance.current, rememberSystemBottom())
    val scrolledUnder by remember(state) {
        derivedStateOf { MainScrollBackdrop.isUnderBar(state.firstVisibleItemIndex, state.firstVisibleItemScrollOffset) }
    }
    Box(modifier.fillMaxSize().background(ShroudTheme.colors.background)) {
        PullToRefresh(onRefresh = onRefresh, indicatorTop = barBlock) {
            LazyColumn(
                state = state,
                modifier = Modifier
                    .fillMaxSize()
                    .edgeEffectSource(backdrop)
                    .nestedScroll(rememberKeyboardDismissOnDrag()),
                contentPadding = PaddingValues(top = barBlock, bottom = bottom),
            ) {
                item(key = MainScrollBackdrop.HEADER_KEY, contentType = MainScrollBackdrop.HEADER_KEY) {
                    Box(Modifier.padding(top = GlassBarMetrics.headerGap - GlassBarMetrics.bottomPadding)) { header() }
                }
                content()
            }
        }
        Box(Modifier.fillMaxWidth()) {
            ScrollEdgeEffect(visible = scrolledUnder, extent = barBlock, backdrop = backdrop)
            // The bar's glass blurs the same scrolled content as the edge effect (API 31+) and takes
            // the opaque fill on API 30 (Glass.kt).
            CompositionLocalProvider(LocalGlassBackdrop provides backdrop) {
                Column(Modifier.fillMaxWidth()) {
                    Spacer(Modifier.windowInsetsTopHeight(WindowInsets.statusBars))
                    GlassBarRow(
                        title = { GlassBarTitle(title) },
                        trailing = trailing,
                        modifier = Modifier.padding(top = GlassBarMetrics.topPadding),
                    )
                }
            }
        }
    }
}

/** The navigation bar or the keyboard, whichever is taller (shell-chats §4.2). */
@Composable
internal fun rememberSystemBottom(): Dp =
    WindowInsets.navigationBars.union(WindowInsets.ime).asPaddingValues().calculateBottomPadding()

/** The bottom insets of the Chats tab (shell-chats §4.2, §10.15). Pure. */
object ChatsLayout {
    /**
     * The list's bottom padding: the floating tab bar's clearance (the full distance from the screen
     * bottom to the bar's top, as the shell publishes it) while the bar is up, else the navigation
     * bar or keyboard ([systemBottom]) — the header search hides the bar and the list must sit
     * above the keyboard (MTV:109-112).
     */
    fun listBottomPadding(tabBarClearance: Dp, systemBottom: Dp): Dp =
        if (tabBarClearance > 0.dp) tabBarClearance else systemBottom

    /**
     * The extra `bottomInset` the screen's toast needs so it floats 20 dp over the tab bar
     * (`ToastBanner.swift:97`): `ToastHost` does not read the clearance yet (its TODO), so it is
     * passed in — the clearance minus the system inset `ToastHost` adds itself. 0 without a bar.
     * When `ToastHost` reads `LocalTabBarClearance` (contract change request), this returns 0.
     */
    fun toastLift(tabBarClearance: Dp, systemBottom: Dp): Dp =
        if (tabBarClearance > systemBottom) tabBarClearance - systemBottom else 0.dp
}
