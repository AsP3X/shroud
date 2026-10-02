package de.corespace.shroud.ui.shell

import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imeAnimationTarget
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.tappableElement
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.movableContentOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import de.corespace.shroud.ui.components.LocalGlassBackdrop
import de.corespace.shroud.ui.components.rememberGlassBackdrop
import de.corespace.shroud.ui.theme.Motion
import de.corespace.shroud.ui.theme.ShroudTheme
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeSource
import kotlin.math.roundToInt

/**
 * The signed-in shell, iOS `MainTabView` (`MainTabView.swift:10-309`; shell-chats §4, §3.15): four
 * tabs, each with its own stack ([ShellNavigator]), the floating glass tab bar with its search mode,
 * notification and invite opens, the unlock reveal, and the window sizes of P12a.
 *
 * - **Compact** (< 600 dp; design `Chats · 360` zMjzp at the narrowest): one [StackHost] over the
 *   selected tab. Its root is the tab content (switched with [tabTransition]) with the floating bar
 *   on top, so a pushed screen covers the bar and the predictive back preview reveals the list
 *   *with* its bar (design I3RNnl).
 * - **Two-pane** (≥ 600 dp, every tab, P12a / D5): the tab root and its bar in a 360 dp list pane,
 *   the tab's stack in the detail pane (cross-fade), a placeholder when nothing is open.
 *
 * The tab root with its bar is movable content: a fold, an unfold or a split-screen resize across
 * 600 dp moves it between the two layouts instead of building it again, so the list keeps its
 * scroll position and the search its field (shell-chats §4.9, "no state is lost"). The stacks are
 * the navigator's lists in both layouts.
 *
 * Unlock reveal: the tab content rises from 0.96 once revealed (`:105-108`); the bar never scales.
 * Back: the stack pops first (predictive), then an open bar search closes, then the system takes it
 * (P12b: at a tab root back leaves the app, as iOS has no back there).
 *
 * The shell mounts itself on [AppRouter.mainShellMounted] (the lock screen's prewarm waits for it).
 *
 * @param screens the tab roots, pushed screens, badges and opens ([AppShellScreens] in the app).
 */
@Composable
fun MainShell(router: AppRouter, isRevealed: Boolean, modifier: Modifier = Modifier, screens: ShellScreens = AppShellScreens) {
    val navigator = remember { ShellNavigator() }
    val reduce = ShroudTheme.reduceMotion
    DisposableEffect(router) {
        router.mainShellMounted.value = true
        onDispose { router.mainShellMounted.value = false }
    }
    var hasAppeared by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { hasAppeared = true }
    val revealScale by animateFloatAsState(
        targetValue = if ((isRevealed && hasAppeared) || reduce) 1f else REVEAL_START_SCALE,
        animationSpec = Motion.respecting(reduce, Motion.gentle()),
        label = "shellReveal",
    )
    // Read in the content's layer only, so the reveal's frames redraw it without recomposing.
    val currentScale by rememberUpdatedState(revealScale)

    screens.Opens(navigator)
    // Lower priority than the stacks' handlers (registered first): a pushed screen pops before the search closes.
    BackHandler(enabled = navigator.isSearching) { navigator.setSearching(false) }

    val tabRoot = remember(navigator, screens) {
        movableContentOf { rootModifier: Modifier -> TabRootWithBar(navigator, screens, { currentScale }, rootModifier) }
    }

    // Read here, not through `maxWidth`: a density change (Settings › Display size, `wm density`)
    // keeps the window's pixels, and BoxWithConstraints does not measure its content again for it
    // (C3 device check: compact at 720 dp, two panes at 411 dp). The new density makes a new lambda.
    val density = LocalDensity.current
    BoxWithConstraints(modifier.fillMaxSize().background(ShroudTheme.colors.background)) {
        val layout = ShellLayoutMath.windowLayout(with(density) { constraints.maxWidth.toDp() }.value)
        CompositionLocalProvider(LocalShellNavigation provides navigator, LocalWindowLayout provides layout) {
            when (layout) {
                WindowLayout.Compact -> CompactShell(navigator, screens, tabRoot)
                WindowLayout.TwoPane -> TwoPaneShell(navigator, screens, tabRoot)
            }
        }
    }
}

/** One stack over the whole window; its root is the tab content with the bar. */
@Composable
private fun CompactShell(navigator: ShellNavigator, screens: ShellScreens, tabRoot: @Composable (Modifier) -> Unit) {
    StackHost(
        routes = navigator.selectedStack(),
        onPop = { navigator.pop() },
        takeChatOpenPush = navigator::takeChatOpenPush,
        root = { tabRoot(Modifier.fillMaxSize()) },
        entry = { route -> screens.Route(route, navigator) },
    )
}

/**
 * The design's `Unfolded / Tablet — Two Pane` (KQGfV; shell-chats §4.9): list pane 360 dp with the
 * bar, a 1 dp separator, the detail pane on `backgroundChat` hosting the selected tab's stack.
 */
@Composable
private fun TwoPaneShell(navigator: ShellNavigator, screens: ShellScreens, tabRoot: @Composable (Modifier) -> Unit) {
    val colors = ShroudTheme.colors
    Row(Modifier.fillMaxSize()) {
        tabRoot(Modifier.width(ShellLayoutMath.listPaneWidth).fillMaxHeight().background(colors.background))
        Box(Modifier.width(1.dp).fillMaxHeight().background(colors.separator))
        StackHost(
            routes = navigator.selectedStack(),
            onPop = { navigator.pop() },
            modifier = Modifier.weight(1f).fillMaxHeight().background(colors.backgroundChat),
            style = StackStyle.Fade,
            root = { DetailPlaceholder(showsCopy = navigator.tab.isSearchable) },
            entry = { route -> screens.Route(route, navigator) },
        )
    }
}

/**
 * The tab content under the floating bar (`MainTabView.body`, `:98-133`): the bar's placement off
 * the keyboard and the navigation bar, its visibility, and the clearance the root lists pad by
 * ([LocalTabBarClearance]); the bar's glass blurs the tab content (API 31+).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TabRootWithBar(navigator: ShellNavigator, screens: ShellScreens, revealScale: () -> Float, modifier: Modifier) {
    val density = LocalDensity.current
    val focusManager = LocalFocusManager.current
    val reduce = ShroudTheme.reduceMotion
    val backdrop = rememberGlassBackdrop()
    val searchFocus = remember { FocusRequester() }
    var searchFieldFocused by remember { mutableStateOf(false) }

    // Keyboard and navigation insets, dp (`keyboardHeight`, `homeIndicatorInset`, `:29-37, 235-252`).
    val imeBottom = with(density) { WindowInsets.ime.getBottom(this).toDp() }
    val imeTarget = with(density) { WindowInsets.imeAnimationTarget.getBottom(this).toDp() }
    val navigationBottom = with(density) { WindowInsets.navigationBars.getBottom(this).toDp() }
    val gestureNavigation = WindowInsets.tappableElement.getBottom(density) == 0
    var keyboardServesSearch by remember { mutableStateOf(false) }
    keyboardServesSearch = ShellLayoutMath.keyboardServesSearch(keyboardServesSearch, navigator.isSearching, imeBottom, imeTarget)

    val follows = ShellLayoutMath.barFollowsKeyboard(navigator.isSearching, keyboardServesSearch)
    val keyboardVisible = imeBottom > 0.dp
    // The bar sits in the stack's root layer: a pushed screen covers it, so it counts as shown here
    // and the root's clearance stays put under a pushed screen (no jump when it pops).
    val barVisible = ShellLayoutMath.barVisible(showsTabBar = true, followsKeyboard = follows, keyboardVisible = keyboardVisible)
    val aboveKeyboard = ShellLayoutMath.barAboveKeyboard(follows, keyboardVisible)
    val barBottom = ShellLayoutMath.barBottom(aboveKeyboard, imeBottom, gestureNavigation, navigationBottom)
    val clearance = ShellLayoutMath.tabBarClearance(barVisible, barBottom, navigator.isSearching)
    val sideInset by animateDpAsState(ShellLayoutMath.barSideInset(aboveKeyboard), Motion.respecting(reduce, Motion.standard()), label = "barSides")
    val shown by animateFloatAsState(if (barVisible) 1f else 0f, Motion.respecting(reduce, Motion.scrim()), label = "barVisible")

    // Search focus (`searchFocused`, `:165-178`): the bar's field takes the keyboard when search opens,
    // lets go when it closes or a result is pushed (the search and its query stay then).
    LaunchedEffect(navigator.searchFocusRequests) {
        if (navigator.searchFocusRequests == 0) return@LaunchedEffect
        withFrameNanos { }
        runCatching { searchFocus.requestFocus() }
    }
    LaunchedEffect(navigator.searchFocusReleases) {
        if (navigator.searchFocusReleases == 0 || !searchFieldFocused) return@LaunchedEffect
        focusManager.clearFocus()
    }
    LaunchedEffect(navigator.showsTabBar) {
        if (!navigator.showsTabBar) navigator.onTabBarHidden()
    }

    Box(modifier) {
        CompositionLocalProvider(
            LocalTabBarClearance provides clearance,
            LocalIsTabBarSearchActive provides navigator.isSearching,
            LocalGlassBackdrop provides backdrop,
        ) {
            TabContent(navigator, screens, revealScale, Modifier.fillMaxSize().tabBarBackdropSource(backdrop))
        }
        CompositionLocalProvider(LocalGlassBackdrop provides backdrop) {
            FloatingTabBar(
                selection = navigator.tab,
                onSelect = navigator::select,
                isSearching = navigator.isSearching,
                onSearchingChange = navigator::setSearching,
                query = navigator.searchQuery,
                onQueryChange = { navigator.searchQuery = it },
                searchFocus = searchFocus,
                badges = screens.badges(),
                onSearchFocusChanged = { searchFieldFocused = it },
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = barBottom)
                    .padding(horizontal = sideInset)
                    .widthIn(max = ShellLayoutMath.barMaxWidth)
                    .graphicsLayer {
                        alpha = shown
                        translationY = (1f - shown) * BAR_HIDDEN_DROP.toPx()
                    }
                    .then(if (barVisible) Modifier else Modifier.consumeAllPointers())
                    .hiddenFromAccessibility(!barVisible),
            )
        }
    }
}

/**
 * The selected tab's root (`tabRoot(for:)`, `:296-308`), switched with [tabTransition]; it carries
 * the reveal's rise. Only tab switches animate here — pushes and the bar never do (`:6-9, 143`).
 */
@Composable
private fun TabContent(navigator: ShellNavigator, screens: ShellScreens, revealScale: () -> Float, modifier: Modifier) {
    val reduce = ShroudTheme.reduceMotion
    val density = LocalDensity.current
    AnimatedContent(
        targetState = navigator.tab,
        transitionSpec = { tabTransition(navigator.movesForward, reduce, density.density) },
        modifier = modifier.graphicsLayer {
            val scale = revealScale()
            scaleX = scale
            scaleY = scale
        },
        label = "tabContent",
    ) { tab ->
        Box(Modifier.fillMaxSize()) {
            screens.TabRoot(tab, navigator)
        }
    }
}

/**
 * Tab switch (`tabContentTransition`, `:283-294`): in with a fade, 0.985 scale and 14 dp toward the
 * new tab, out with a fade, 1.01 scale and 10 dp away, all [Motion.standard]; Reduce Motion
 * cross-fades ([Motion.reduced]).
 */
internal fun tabTransition(movesForward: Boolean, reduce: Boolean, density: Float): ContentTransform {
    if (reduce) {
        return ContentTransform(fadeIn(Motion.reduced()), fadeOut(Motion.reduced()), sizeTransform = SizeTransform(clip = false))
    }
    val insert = ShellLayoutMath.tabInsertOffset(movesForward).value * density
    val remove = ShellLayoutMath.tabRemoveOffset(movesForward).value * density
    return ContentTransform(
        targetContentEnter = fadeIn(Motion.standard()) +
            scaleIn(Motion.standard(), initialScale = ShellLayoutMath.TAB_INSERT_SCALE) +
            slideInHorizontally(Motion.standard()) { insert.roundToInt() },
        initialContentExit = fadeOut(Motion.standard()) +
            scaleOut(Motion.standard(), targetScale = ShellLayoutMath.TAB_REMOVE_SCALE) +
            slideOutHorizontally(Motion.standard()) { remove.roundToInt() },
        sizeTransform = SizeTransform(clip = false),
    )
}

/** The tab content is what the bar's glass blurs (Haze needs API 31; below the opaque fill applies). */
private fun Modifier.tabBarBackdropSource(state: HazeState): Modifier =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) hazeSource(state) else this

/** The content rises from this scale on the unlock reveal (`MainTabView.swift:108`). */
private const val REVEAL_START_SCALE = 0.96f

/** A hidden bar drops by this much (`offset(y: barVisible ? 0 : 24)`, `:127`). */
private val BAR_HIDDEN_DROP: Dp = 24.dp
