package de.corespace.shroud.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsTopHeight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import de.corespace.shroud.ui.theme.ShroudIcons
import de.corespace.shroud.ui.theme.ShroudTheme
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.rememberHazeState

/**
 * A root tab screen under a glass bar (`TransparentMainChrome.swift:3-56`; shell-chats §7):
 * Chats, Contacts and Calls. One bar row — [leading] glass controls, the plain [title] centred on
 * the screen ([GlassBarTitle]), [trailing] controls — pinned under the status bar; the list scrolls
 * under both, where [ScrollEdgeEffect] fades it (iOS `glassTopBar`, `GlassBar.swift:266-269`).
 *
 * - One [LazyColumn] on [state]: the optional [header] first (the header search field, the
 *   offline banner — they scroll away under the bar, as iOS's accessory does,
 *   `TransparentMainChrome.swift:33-40`), then [content].
 * - Top: status bar + 4 + 44 + 6 dp ([GlassBarMetrics.mainBarHeight]); the header sits 12 dp
 *   under the bar row (design `Header gap 12`, shell-chats D13). Callers pad the header
 *   themselves (the search field: h 16, bottom 10).
 * - Bottom: the navigation bar or the keyboard, whichever is taller (shell-chats §4.2). The
 *   shell's tab-bar clearance replaces it once `LocalTabBarClearance` exists (W2-INT seam; see
 *   the package report).
 * - A drag on the list hides the keyboard and clears focus (iOS
 *   `.scrollDismissesKeyboard(.interactively)`, shell-chats §4.4).
 * - Background `background` (`ChatsView.swift:184`).
 *
 * The scroll content is the screen's glass backdrop ([MainScrollBackdrop]); the edge effect blurs
 * it on API 31+.
 */
@Composable
fun MainScrollScreen(
    title: String,
    state: LazyListState = rememberLazyListState(),
    leading: @Composable RowScope.() -> Unit = {},
    trailing: @Composable RowScope.() -> Unit = {},
    header: (@Composable () -> Unit)? = null,
    modifier: Modifier = Modifier,
    content: LazyListScope.() -> Unit,
) {
    val backdrop = rememberHazeState()
    val statusTop = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val bottom = WindowInsets.navigationBars.union(WindowInsets.ime).asPaddingValues().calculateBottomPadding()
    val barBlock = statusTop + GlassBarMetrics.mainBarHeight
    val scrolledUnder by remember(state) {
        derivedStateOf { MainScrollBackdrop.isUnderBar(state.firstVisibleItemIndex, state.firstVisibleItemScrollOffset) }
    }
    Box(modifier.fillMaxSize().background(ShroudTheme.colors.background)) {
        LazyColumn(
            state = state,
            modifier = Modifier
                .fillMaxSize()
                .edgeEffectSource(backdrop)
                .nestedScroll(rememberKeyboardDismissOnDrag()),
            contentPadding = PaddingValues(top = barBlock, bottom = bottom),
        ) {
            if (header != null) {
                item(key = MainScrollBackdrop.HEADER_KEY, contentType = MainScrollBackdrop.HEADER_KEY) {
                    Box(Modifier.padding(top = GlassBarMetrics.headerGap - GlassBarMetrics.bottomPadding)) { header() }
                }
            }
            content()
        }
        MainBar(title, leading, trailing, scrolledUnder, barBlock, backdrop)
    }
}

/** The pinned bar of [MainScrollScreen] over its edge effect. */
@Composable
private fun MainBar(
    title: String,
    leading: @Composable RowScope.() -> Unit,
    trailing: @Composable RowScope.() -> Unit,
    scrolledUnder: Boolean,
    extent: Dp,
    backdrop: HazeState,
) {
    Box(Modifier.fillMaxWidth()) {
        ScrollEdgeEffect(visible = scrolledUnder, extent = extent, backdrop = backdrop)
        Column(Modifier.fillMaxWidth()) {
            Spacer(Modifier.windowInsetsTopHeight(WindowInsets.statusBars))
            GlassBarRow(
                leading = leading,
                title = { GlassBarTitle(title) },
                trailing = trailing,
                modifier = Modifier.padding(top = GlassBarMetrics.topPadding),
            )
        }
    }
}

/** The pure rules of [MainScrollScreen]. */
object MainScrollBackdrop {
    /** Lazy-list key of the header item (a `String`, so the list can save it in a `Bundle`). */
    const val HEADER_KEY = "shroud.mainScroll.header"

    /** Content is under the bar once the list has moved at all (shell-chats §6.4). */
    fun isUnderBar(firstVisibleItemIndex: Int, firstVisibleItemScrollOffset: Int): Boolean =
        firstVisibleItemIndex > 0 || firstVisibleItemScrollOffset > 0
}

/**
 * A nested-scroll connection that hides the keyboard and clears focus when the user drags the
 * list (iOS `.scrollDismissesKeyboard(.interactively)`; shell-chats §4.4). Consumes nothing.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun rememberKeyboardDismissOnDrag(): NestedScrollConnection {
    val keyboard by rememberUpdatedState(LocalSoftwareKeyboardController.current)
    val focus by rememberUpdatedState(LocalFocusManager.current)
    val imeVisible by rememberUpdatedState(WindowInsets.isImeVisible)
    return remember {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                // Only while the keyboard is up: a hide request on every scroll frame would be waste.
                if (imeVisible && source == NestedScrollSource.UserInput && available.y != 0f) {
                    keyboard?.hide()
                    focus.clearFocus()
                }
                return Offset.Zero
            }
        }
    }
}

@Preview(name = "Main scroll screen · 412", widthDp = 412, heightDp = 600)
@Composable
private fun MainScrollScreenPreview() {
    ShroudTheme(dark = false) {
        MainScrollScreen(
            title = "Chats",
            trailing = { GlassBarButton(ShroudIcons.CaretRight, "New chat", {}) },
            header = { SearchField("", {}, modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 10.dp)) },
        ) {
            items(12) { index ->
                ChatRow("Contact $index", "Scrolls under the bar and fades out", "9:41", { NameAvatar("c$index") }, onClick = {})
            }
        }
    }
}
