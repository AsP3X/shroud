package de.corespace.shroud.ui.shell

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.CollectionInfo
import androidx.compose.ui.semantics.CollectionItemInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.collectionInfo
import androidx.compose.ui.semantics.collectionItemInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import de.corespace.shroud.core.model.Haptic
import de.corespace.shroud.ui.components.GlassStyle
import de.corespace.shroud.ui.components.NoLearningTextInput
import de.corespace.shroud.ui.components.SearchKeyboard
import de.corespace.shroud.ui.components.ShroudIcon
import de.corespace.shroud.ui.components.ShroudText
import de.corespace.shroud.ui.components.glassSurface
import de.corespace.shroud.ui.components.pressable
import de.corespace.shroud.ui.theme.Motion
import de.corespace.shroud.ui.theme.ShroudIcons
import de.corespace.shroud.ui.theme.ShroudTheme
import de.corespace.shroud.ui.theme.inter
import de.corespace.shroud.ui.theme.perform
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/** The tab bar's fixed sizes, exposed for placement (`FloatingTabBar.height`, `.searchHeight`). */
object FloatingTabBarMetrics {
    val height: Dp get() = ShellLayoutMath.barHeight
    val searchHeight: Dp get() = ShellLayoutMath.searchHeight

    /** The bar's text never grows past 1.3× (shell-chats §4.9, D9 / P12c): iOS uses fixed sizes here. */
    const val MAX_FONT_SCALE = 1.3f

    /**
     * [density] with its font scale clamped to [MAX_FONT_SCALE] (P12c decided: clamp, rows scale
     * fully). iOS sets the labels in a fixed 10 pt that ignores Dynamic Type (`FloatingTabBar.swift:331`);
     * at Android's 200 % the bar keeps its 64 dp and "Contacts" still fits a 60 dp item at 360 dp.
     */
    fun clamp(density: Density): Density =
        if (density.fontScale <= MAX_FONT_SCALE) density else Density(density.density, MAX_FONT_SCALE)
}

/**
 * Telegram-style floating tab bar (iOS `FloatingTabBar`, `FloatingTabBar.swift:36-314`; design
 * `Tab Bar` tliql; shell-chats §5): four equal tabs in a glass capsule with a sliding selection
 * lens, and a round search button beside it that grows into a search field with a ✕.
 *
 * - Press anywhere on the tabs: light haptic, the lens jumps under the finger and lifts (×1.12);
 *   drag to scrub across tabs (the lens follows 1:1, the tab under it magnifies), release selects
 *   the tab under the finger; the lens returns to the selection with [Motion.standard] (§5.4).
 * - Two copies of the row: plain outside the lens, accent inside it; badges above both (§5.3, §5.6).
 * - A tab that becomes selected bounces its icon once (§5.5).
 * - Search: the circle morphs into a 48 dp field (+ ✕), the capsule fades away ([Motion.gentle]);
 *   the field asks the keyboard for no personalised learning (§5.7).
 * - Text is clamped to 1.3× font scale and auto-sizes 8–10 sp (P12c, §4.9).
 *
 * Only lays out its own row; placement (gaps, keyboard, visibility) is `MainShell`'s (`:42-43`).
 */
@Composable
fun FloatingTabBar(
    selection: MainTab,
    onSelect: (MainTab) -> Unit,
    isSearching: Boolean,
    onSearchingChange: (Boolean) -> Unit,
    query: String,
    onQueryChange: (String) -> Unit,
    searchFocus: FocusRequester,
    badges: Map<MainTab, Int>,
    modifier: Modifier = Modifier,
    onSearchFocusChanged: (Boolean) -> Unit = {},
) {
    val density = LocalDensity.current
    val clamped = remember(density) { FloatingTabBarMetrics.clamp(density) }
    val reduce = ShroudTheme.reduceMotion
    val morph by animateFloatAsState(if (isSearching) 1f else 0f, Motion.respecting(reduce, Motion.gentle()), label = "tabBarSearch")
    CompositionLocalProvider(LocalDensity provides clamped) {
        BoxWithConstraints(modifier.fillMaxWidth()) {
            val widthPx = constraints.maxWidth.toFloat()
            val geometry = ShellLayoutMath.morph(widthPx, morph, clamped.density)
            Layout(
                content = {
                    TabCapsule(
                        selection = selection,
                        onSelect = onSelect,
                        badges = badges,
                        enabled = !isSearching,
                        modifier = Modifier.graphicsLayer {
                            alpha = 1f - morph
                            val s = ShellLayoutMath.lerp(1f, 0.9f, morph)
                            scaleX = s
                            scaleY = s
                        },
                    )
                    SearchGlass(
                        isSearching = isSearching,
                        morph = morph,
                        onOpen = { onSearchingChange(true) },
                        query = query,
                        onQueryChange = onQueryChange,
                        searchFocus = searchFocus,
                        onFocusChanged = onSearchFocusChanged,
                    )
                    CloseSearchButton(
                        onClose = { onSearchingChange(false) },
                        modifier = Modifier.graphicsLayer {
                            alpha = morph
                            val s = ShellLayoutMath.lerp(0.9f, 1f, morph)
                            scaleX = s
                            scaleY = s
                        },
                    )
                },
            ) { measurables, constraints ->
                val height = geometry.height.roundToInt()
                val capsule = measurables[0].measure(Constraints.fixed(geometry.capsuleWidth.roundToInt(), ShellLayoutMath.barHeight.roundToPx()))
                val field = measurables[1].measure(Constraints.fixed(geometry.fieldWidth.roundToInt(), geometry.fieldHeight.roundToInt()))
                val close = measurables[2].measure(Constraints.fixed(geometry.closeSize.roundToInt(), geometry.closeSize.roundToInt()))
                layout(constraints.maxWidth, height) {
                    // Bottom-aligned (`FloatingTabBar.swift:91`); place() mirrors x in RTL.
                    if (morph < 1f) capsule.place(0, height - capsule.height)
                    field.place(geometry.fieldX.roundToInt(), height - field.height)
                    if (morph > 0f) close.place(geometry.closeX.roundToInt(), height - close.height)
                }
            }
        }
    }
}

/** The four tabs with their lens (`tabCapsule`, `FloatingTabBar.swift:96-140`). */
@Composable
private fun TabCapsule(
    selection: MainTab,
    onSelect: (MainTab) -> Unit,
    badges: Map<MainTab, Int>,
    enabled: Boolean,
    modifier: Modifier = Modifier,
) {
    val colors = ShroudTheme.colors
    val reduce = ShroudTheme.reduceMotion
    val view = LocalView.current
    val scope = rememberCoroutineScope()
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val currentOnSelect by rememberUpdatedState(onSelect)
    BoxWithConstraints(
        modifier
            .fillMaxSize()
            .glassSurface(CircleShape, GlassStyle.Soft, interactive = true),
    ) {
        val density = LocalDensity.current
        val rimPx = with(density) { ShellLayoutMath.rim.toPx() }
        val itemWidthPx = ShellLayoutMath.itemWidth(constraints.maxWidth.toFloat(), rimPx)
        val itemWidth = with(density) { itemWidthPx.toDp() }
        val itemHeightPx = with(density) { ShellLayoutMath.itemHeight.toPx() }
        val lensX = remember { Animatable(ShellLayoutMath.lensOffset(selection, itemWidthPx)) }
        var dragTab by remember { mutableStateOf<MainTab?>(null) }
        val currentSelection by rememberUpdatedState(selection)
        val lift by animateFloatAsState(
            if (dragTab != null && !reduce) ShellLayoutMath.LIFT_SCALE else 1f,
            Motion.respecting(reduce, Motion.snappy()),
            label = "lensLift",
        )
        // The lens rests on the selection; a new selection (or a resize) slides it there.
        LaunchedEffect(selection, itemWidthPx) {
            if (dragTab == null) lensX.animateTo(ShellLayoutMath.lensOffset(selection, itemWidthPx), Motion.respecting(reduce, Motion.standard()))
        }
        val lensFill = if (dragTab != null) colors.tabSelectedLifted else colors.tabSelected
        val lensPath = remember { Path() }

        fun lensRect(innerWidth: Float): RoundRect {
            val x = if (rtl) innerWidth - lensX.value - itemWidthPx else lensX.value
            val w = itemWidthPx * lift
            val h = itemHeightPx * lift
            val cx = x + itemWidthPx / 2
            val cy = itemHeightPx / 2
            return RoundRect(cx - w / 2, cy - h / 2, cx + w / 2, cy + h / 2, CornerRadius(h / 2))
        }

        Box(
            Modifier
                .fillMaxSize()
                .pointerInput(itemWidthPx, rtl, enabled) {
                    if (!enabled) return@pointerInput
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        val width = size.width.toFloat()
                        fun leading(x: Float) = if (rtl) width - x else x
                        view.perform(Haptic.Light)
                        val start = ShellLayoutMath.tabAt(leading(down.position.x), itemWidthPx, rimPx)
                        val startOffset = ShellLayoutMath.lensOffset(start, itemWidthPx)
                        dragTab = start
                        scope.launch { lensX.animateTo(startOffset, Motion.respecting(reduce, Motion.snappy())) }
                        var last = down.position
                        var cancelled = false
                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull { it.id == down.id } ?: break
                            last = change.position
                            if (!change.pressed) break
                            val dragX = leading(change.position.x) - leading(down.position.x)
                            // Follows the finger 1:1 — no animation while it moves (`:188-195`).
                            scope.launch { lensX.snapTo(ShellLayoutMath.clampLens(startOffset + dragX, itemWidthPx)) }
                            dragTab = ShellLayoutMath.tabAt(leading(change.position.x), itemWidthPx, rimPx)
                            if (event.changes.any { it.isConsumed && it.id != down.id }) {
                                cancelled = true
                                break
                            }
                        }
                        val released = ShellLayoutMath.tabAt(leading(last.x), itemWidthPx, rimPx)
                        dragTab = null
                        if (!cancelled) currentOnSelect(released)
                        // The lens returns from the finger to the (new) selection (`@GestureState` reset, `:68`).
                        val home = if (cancelled) currentSelection else released
                        scope.launch { lensX.animateTo(ShellLayoutMath.lensOffset(home, itemWidthPx), Motion.respecting(reduce, Motion.standard())) }
                    }
                }
                .padding(ShellLayoutMath.rim)
                .semantics {
                    collectionInfo = CollectionInfo(rowCount = 1, columnCount = MainTab.entries.size)
                }
                .selectableGroup(),
        ) {
            // The lens (`:108-112`).
            Box(
                Modifier
                    .offset { IntOffset(lensX.value.roundToInt(), 0) }
                    .size(itemWidth, ShellLayoutMath.itemHeight)
                    .graphicsLayer {
                        scaleX = lift
                        scaleY = lift
                    }
                    .background(lensFill, CircleShape),
            )
            // Plain copy, outside the lens (`:116-123`): carries the tabs' semantics.
            TabRow(
                selection = selection,
                color = colors.textPrimary,
                itemWidth = itemWidth,
                magnified = null,
                magnification = 1f,
                badges = badges,
                onSelect = onSelect,
                semantics = true,
                modifier = Modifier.drawWithContent {
                    lensPath.reset()
                    lensPath.addRoundRect(lensRect(size.width))
                    clipPath(lensPath, ClipOp.Difference) { this@drawWithContent.drawContent() }
                },
            )
            // Accent copy, inside the lens, magnified under the finger (`:124-126`); hidden from TalkBack.
            TabRow(
                selection = selection,
                color = colors.accent,
                itemWidth = itemWidth,
                magnified = dragTab,
                magnification = lift,
                badges = badges,
                onSelect = onSelect,
                semantics = false,
                modifier = Modifier
                    .clearAndSetSemantics {}
                    .drawWithContent {
                        lensPath.reset()
                        lensPath.addRoundRect(lensRect(size.width))
                        clipPath(lensPath) { this@drawWithContent.drawContent() }
                    },
            )
            // Badges above both rows, so the lens never tints them (`:158-181`).
            BadgeRow(itemWidth = itemWidth, badges = badges)
        }
    }
}

@Composable
private fun TabRow(
    selection: MainTab,
    color: Color,
    itemWidth: Dp,
    magnified: MainTab?,
    magnification: Float,
    badges: Map<MainTab, Int>,
    onSelect: (MainTab) -> Unit,
    semantics: Boolean,
    modifier: Modifier = Modifier,
) {
    Row(modifier) {
        MainTab.entries.forEachIndexed { index, tab ->
            val selected = tab == selection
            val badge = ShellLayoutMath.badgeText(badges[tab])
            Box(
                Modifier
                    .size(itemWidth, ShellLayoutMath.itemHeight)
                    .graphicsLayer {
                        val s = if (magnified == tab) magnification else 1f
                        scaleX = s
                        scaleY = s
                    }
                    .then(
                        if (semantics) {
                            // TalkBack: "Chats, tab, 1 of 4, selected, 3 unread" (`:149-153`; shell-chats §5.8).
                            Modifier.semantics(mergeDescendants = true) {
                                contentDescription = tab.title
                                role = Role.Tab
                                this.selected = selected
                                collectionItemInfo = CollectionItemInfo(0, 1, index, 1)
                                if (badge != null) stateDescription = "$badge unread"
                                onClick { onSelect(tab); true }
                            }
                        } else {
                            Modifier
                        },
                    ),
                contentAlignment = Alignment.Center,
            ) {
                TabItemLabel(tab = tab, selected = selected, color = color)
            }
        }
    }
}

/**
 * Icon over a 10 sp SemiBold title on Telegram's grid (`TabItemLabel`, `FloatingTabBar.swift:316-342`):
 * column spacing 1, icon box 32, title box 12 (auto-sized 8–10 sp, one line), padding top 4 bottom 7.
 * A tab that becomes selected bounces its icon once — not the one being left (`:321-341`).
 */
@Composable
private fun TabItemLabel(tab: MainTab, selected: Boolean, color: Color) {
    val reduce = ShroudTheme.reduceMotion
    val bounce = remember { Animatable(1f) }
    var wasSelected by remember { mutableStateOf(selected) }
    LaunchedEffect(selected) {
        val became = selected && !wasSelected
        wasSelected = selected
        if (became && !reduce) {
            bounce.animateTo(0.82f, tween(90))
            bounce.animateTo(1f, Motion.bouncy())
        }
    }
    Column(
        Modifier
            .clearAndSetSemantics {}
            .padding(top = 4.dp, bottom = 7.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(1.dp),
    ) {
        Box(Modifier.height(32.dp), contentAlignment = Alignment.Center) {
            ShroudIcon(
                tab.icon,
                color,
                size = tab.iconSize,
                modifier = Modifier.graphicsLayer {
                    scaleX = bounce.value
                    scaleY = bounce.value
                },
            )
        }
        Box(Modifier.height(12.dp), contentAlignment = Alignment.Center) {
            BasicText(
                text = tab.title,
                style = inter(10f, FontWeight.SemiBold).copy(color = color, textAlign = TextAlign.Center),
                maxLines = 1,
                autoSize = TextAutoSize.StepBased(minFontSize = 8.sp, maxFontSize = 10.sp, stepSize = 0.5.sp),
            )
        }
    }
}

/** The red count badges (`badgeRow` + `TabBadge`, `FloatingTabBar.swift:158-181, 352-364`). */
@Composable
private fun BadgeRow(itemWidth: Dp, badges: Map<MainTab, Int>) {
    val colors = ShroudTheme.colors
    Row(Modifier.clearAndSetSemantics {}) {
        MainTab.entries.forEach { tab ->
            val text = ShellLayoutMath.badgeText(badges[tab])
            Box(Modifier.size(itemWidth, ShellLayoutMath.itemHeight)) {
                // Trailing edge 24 dp right of the icon's centre, 5 dp below the top.
                Box(Modifier.width(itemWidth / 2 + 24.dp).padding(top = 5.dp), contentAlignment = Alignment.TopEnd) {
                    var last by remember { mutableStateOf(text ?: "") }
                    if (text != null) last = text
                    val transition = Motion.iconSwap(Motion.bouncy())
                    androidx.compose.animation.AnimatedVisibility(visible = text != null, enter = transition.enter, exit = transition.exit) {
                        Box(
                            Modifier
                                .heightIn(min = 18.dp)
                                .widthIn(min = 18.dp)
                                .background(colors.danger, CircleShape)
                                .padding(horizontal = 5.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            // Design `Badge Count`: 13 sp Medium, tabular digits (D14 follows the design).
                            ShroudText(last, inter(13f, FontWeight.Medium, tabularDigits = true), Color.White, maxLines = 1)
                        }
                    }
                }
            }
        }
    }
}

/**
 * The search circle that grows into the search field (`searchButton` / `searchField`,
 * `FloatingTabBar.swift:242-295`): one glass surface whose size [morph]s; the magnifier fades out
 * as the field fades in.
 */
@Composable
private fun SearchGlass(
    isSearching: Boolean,
    morph: Float,
    onOpen: () -> Unit,
    query: String,
    onQueryChange: (String) -> Unit,
    searchFocus: FocusRequester,
    onFocusChanged: (Boolean) -> Unit,
) {
    val colors = ShroudTheme.colors
    val keyboard = LocalSoftwareKeyboardController.current
    val currentOnFocusChanged by rememberUpdatedState(onFocusChanged)
    Box(
        Modifier
            .fillMaxSize()
            .glassSurface(RoundedCornerShape(50), GlassStyle.Soft, interactive = true)
            .then(
                if (!isSearching) {
                    Modifier
                        .pressable(scale = 1f, dimming = 0f, haptic = Haptic.Light, onClick = onOpen)
                        .semantics { contentDescription = "Search" }
                } else {
                    Modifier
                },
            ),
        contentAlignment = Alignment.Center,
    ) {
        if (morph < 1f) {
            ShroudIcon(
                ShroudIcons.MagnifyingGlassBold,
                colors.textPrimary,
                size = 24.dp,
                modifier = Modifier.graphicsLayer { alpha = 1f - morph },
            )
        }
        if (isSearching || morph > 0f) {
            Row(
                Modifier
                    .fillMaxSize()
                    .graphicsLayer { alpha = morph }
                    .padding(start = 14.dp, end = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ShroudIcon(ShroudIcons.MagnifyingGlassBold, colors.textSecondary, size = 18.dp)
                Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                    NoLearningTextInput {
                        BasicTextField(
                            value = query,
                            onValueChange = onQueryChange,
                            enabled = isSearching,
                            singleLine = true,
                            textStyle = inter(17f).copy(color = colors.textPrimary),
                            cursorBrush = SolidColor(colors.accent),
                            keyboardOptions = SearchKeyboard.options,
                            keyboardActions = KeyboardActions(onSearch = { keyboard?.hide() }),
                            modifier = Modifier
                                .fillMaxWidth()
                                .focusRequester(searchFocus)
                                .onFocusChanged { currentOnFocusChanged(it.isFocused) },
                            decorationBox = { field ->
                                Box(contentAlignment = Alignment.CenterStart) {
                                    if (query.isEmpty()) ShroudText("Search", inter(17f), colors.textSecondary, maxLines = 1)
                                    field()
                                }
                            },
                        )
                    }
                }
                val clear = Motion.iconSwap(Motion.snappy())
                AnimatedVisibility(visible = query.isNotEmpty(), enter = clear.enter, exit = clear.exit) {
                    Box(
                        Modifier
                            .size(28.dp)
                            .pressable(scale = 0.8f, onClick = { onQueryChange("") })
                            .semantics { contentDescription = "Clear search" },
                        contentAlignment = Alignment.Center,
                    ) {
                        ShroudIcon(ShroudIcons.XCircleFill, colors.textSecondary, size = 18.dp)
                    }
                }
            }
        }
    }
}

/** The ✕ beside the field (`closeSearchButton`, `FloatingTabBar.swift:297-313`): haptic only, no scale. */
@Composable
private fun CloseSearchButton(onClose: () -> Unit, modifier: Modifier = Modifier) {
    val colors = ShroudTheme.colors
    Box(
        modifier
            .fillMaxSize()
            .glassSurface(CircleShape, GlassStyle.Soft, interactive = true)
            .pressable(scale = 1f, dimming = 0f, haptic = Haptic.Light, onClick = onClose)
            .semantics { contentDescription = "Close search" },
        contentAlignment = Alignment.Center,
    ) {
        ShroudIcon(ShroudIcons.XBold, colors.textPrimary, size = 18.dp)
    }
}

/** The design's tab icons (Phosphor fills, shell-chats §5.1). */
private val MainTab.icon: ImageVector
    get() = when (this) {
        MainTab.Chats -> ShroudIcons.ChatsCircleFill
        MainTab.Contacts -> ShroudIcons.UserCircleFill
        MainTab.Calls -> ShroudIcons.PhoneFill
        MainTab.Settings -> ShroudIcons.GearSixFill
    }

/** Icon sizes that land each glyph in the ~23 dp icon box (`symbolPointSize`, `FloatingTabBar.swift:344-350`; design). */
private val MainTab.iconSize: Dp
    get() = when (this) {
        MainTab.Chats -> 25.dp
        MainTab.Contacts -> 24.dp
        MainTab.Calls -> 22.dp
        MainTab.Settings -> 24.dp
    }
