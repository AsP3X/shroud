package de.corespace.shroud.ui.shell

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.floor
import kotlin.math.max

/**
 * The pure geometry of the main shell and its floating tab bar (shell-chats §4.2, §4.9, §5.2,
 * §12.5), kept apart from the composables so it has unit tests (`ShellLayoutMathTest`).
 *
 * Metrics: iOS `FloatingTabBar` (`FloatingTabBar.swift:52-61`) and `MainTabView`'s bar placement
 * (`MainTabView.swift:45-91`). Lengths are dp (1 pt = 1 dp) unless a function says px.
 */
object ShellLayoutMath {
    /** The tab capsule's height: 56 dp items inside a 4 dp glass rim (`FloatingTabBar.swift:52-53`). */
    val barHeight: Dp = 64.dp

    /** The search field and its ✕ are slimmer than the capsule (`FloatingTabBar.swift:54-55`). */
    val searchHeight: Dp = 48.dp

    /** Glass rim around the items (`FloatingTabBar.swift:57`). */
    val rim: Dp = 4.dp

    /** One tab's height inside the rim (`FloatingTabBar.swift:58`). */
    val itemHeight: Dp = 56.dp

    /** Gap between the capsule and the search circle (`FloatingTabBar.swift:59`). */
    val spacing: Dp = 8.dp

    /** How far the lens swells while a finger holds it (`FloatingTabBar.swift:60-61`). */
    const val LIFT_SCALE = 1.12f

    /** Bar off the screen's bottom and sides with a home indicator / gesture handle (`MainTabView.swift:47`). */
    val edgeGap: Dp = 20.dp

    /** Bar off the bottom without one, and above the keyboard (`MainTabView.swift:48`). */
    val compactGap: Dp = 8.dp

    /** Side inset while the bar rides the keyboard (`MainTabView.swift:49`). */
    val keyboardSideInset: Dp = 12.dp

    /** The bar never grows wider than this (`MainTabView.swift:50`). */
    val barMaxWidth: Dp = 500.dp

    /** From this window width the shell shows list and detail side by side (shell-chats §4.9, P12a). */
    val twoPaneMinWidth: Dp = 600.dp

    /** The list pane of the two-pane layout (design `Unfolded / Tablet — Two Pane`, KQGfV). */
    val listPaneWidth: Dp = 360.dp

    /** Number of tabs. */
    const val TAB_COUNT = 4

    // ---- Tab capsule (FloatingTabBar.swift:96-238) ----

    /** One item's width: `max(1, (capsuleWidth − 2·rim) / 4)` (`FloatingTabBar.swift:98`). Any unit. */
    fun itemWidth(capsuleWidth: Float, rim: Float = this.rim.value): Float = max(1f, (capsuleWidth - rim * 2) / TAB_COUNT)

    /** The tab index under [x] (capsule coordinates, rim included), clamped (`FloatingTabBar.swift:215-218`). */
    fun tabIndexAt(x: Float, itemWidth: Float, rim: Float = this.rim.value): Int {
        val index = floor((x - rim) / itemWidth).toInt()
        return index.coerceIn(0, TAB_COUNT - 1)
    }

    /** The tab under [x] (`tab(at:)`). */
    fun tabAt(x: Float, itemWidth: Float, rim: Float = this.rim.value): MainTab = MainTab.entries[tabIndexAt(x, itemWidth, rim)]

    /** The lens's leading offset inside the rim when it sits on [tab] (`FloatingTabBar.swift:220-222`). */
    fun lensOffset(tab: MainTab, itemWidth: Float): Float = tab.ordinal * itemWidth

    /** A dragged lens stays over the items (`FloatingTabBar.swift:224-226`). */
    fun clampLens(x: Float, itemWidth: Float): Float = x.coerceIn(0f, (TAB_COUNT - 1) * itemWidth)

    /** The badge's text: none for 0 or a missing count, "99+" above 99 (`FloatingTabBar.swift:228-231`). */
    fun badgeText(count: Int?): String? {
        if (count == null || count <= 0) return null
        return if (count > 99) "99+" else count.toString()
    }

    // ---- Placement (MainTabView.swift:61-91) ----

    /** The bar follows the keyboard while its own search is open or the keyboard came up for it (`:63`). */
    fun barFollowsKeyboard(isSearching: Boolean, keyboardServesSearch: Boolean): Boolean = isSearching || keyboardServesSearch

    /** Visible on a tab root, and not under a keyboard it does not ride (`:67-69`). */
    fun barVisible(showsTabBar: Boolean, followsKeyboard: Boolean, keyboardVisible: Boolean): Boolean =
        showsTabBar && (followsKeyboard || !keyboardVisible)

    /** Above the keyboard only while following it (`:71`). */
    fun barAboveKeyboard(followsKeyboard: Boolean, keyboardVisible: Boolean): Boolean = followsKeyboard && keyboardVisible

    /** 12 dp sides over the keyboard, else 20 (`:78-80`). */
    fun barSideInset(aboveKeyboard: Boolean): Dp = if (aboveKeyboard) keyboardSideInset else edgeGap

    /**
     * The bar's bottom edge measured from the **screen** bottom (`:73-76, 124-125`; shell-chats §4.2):
     * gesture navigation 20 dp (the handle sits under the bar, design `Tab Bar Wrap` pad 20),
     * three-button navigation the buttons + 8 dp (never over them), and over the keyboard
     * `ime + 8 dp`, but never below the resting position, so the bar rides the keyboard down and
     * then rests (shell-chats §4.4).
     *
     * @param gestureNavigation `WindowInsets.tappableElement` has no bottom (no buttons to tap).
     */
    fun barBottom(aboveKeyboard: Boolean, imeBottom: Dp, gestureNavigation: Boolean, navigationBarBottom: Dp): Dp {
        val resting = if (gestureNavigation) edgeGap else navigationBarBottom + compactGap
        return if (aboveKeyboard) max(imeBottom.value + compactGap.value, resting.value).dp else resting
    }

    /**
     * What a root tab list pads at the bottom so its last row ends at the bar's top edge
     * (`tabBarClearance`, `MainTabView.swift:82-91`). Android lists draw edge to edge, so this is the
     * full distance from the screen bottom (iOS subtracts the home-indicator inset its scroll views
     * already add). Zero while the bar is hidden: roots then pad by the keyboard or the navigation bar.
     */
    fun tabBarClearance(barVisible: Boolean, barBottom: Dp, isSearching: Boolean): Dp =
        if (!barVisible) 0.dp else barBottom + (if (isSearching) searchHeight else barHeight)

    /** Compact below 600 dp, two panes from 600 dp (shell-chats §4.9). */
    fun windowLayout(widthDp: Float): WindowLayout = if (widthDp >= twoPaneMinWidth.value) WindowLayout.TwoPane else WindowLayout.Compact

    // ---- Search morph (FloatingTabBar.swift:77-92, 240-313) ----

    /** Linear interpolation, [t] in 0…1. */
    fun lerp(from: Float, to: Float, t: Float): Float = from + (to - from) * t

    /**
     * Where the bar's three glass parts sit while it morphs between tabs (`t` = 0) and search
     * (`t` = 1), in px of a bar [width] wide: the capsule shrinks away at the leading edge, the
     * search circle grows into the field, the close circle appears at the trailing edge
     * (iOS `glassEffectID` morph, shell-chats §5.7).
     */
    fun morph(width: Float, t: Float, density: Float): BarMorph {
        val bar = barHeight.value * density
        val search = searchHeight.value * density
        val gap = spacing.value * density
        val closeSpace = lerp(0f, search + gap, t)
        val fieldWidth = lerp(bar, (width - search - gap).coerceAtLeast(bar), t)
        val fieldHeight = lerp(bar, search, t)
        return BarMorph(
            height = lerp(bar, search, t),
            capsuleWidth = (width - bar - gap).coerceAtLeast(1f),
            fieldX = width - closeSpace - fieldWidth,
            fieldWidth = fieldWidth,
            fieldHeight = fieldHeight,
            closeX = width - search,
            closeSize = search,
        )
    }
}

/** Positions of [ShellLayoutMath.morph], px. */
data class BarMorph(
    val height: Float,
    val capsuleWidth: Float,
    val fieldX: Float,
    val fieldWidth: Float,
    val fieldHeight: Float,
    val closeX: Float,
    val closeSize: Float,
)
