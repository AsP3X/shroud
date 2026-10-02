package de.corespace.shroud.ui.shell

import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The shell's pure geometry (shell-chats §4.2, §4.4, §4.9, §5.2, §12.5, §14 `ShellLayoutMathTest`). */
class ShellLayoutMathTest {
    @Test
    fun itemWidthLeavesTheRimOnBothSides() {
        // 412 dp: capsule 300 → item 73 (design `Tab Chats` 73 × 56); 360 dp: capsule 248 → item 60.
        assertEquals(73f, ShellLayoutMath.itemWidth(300f), 0f)
        assertEquals(60f, ShellLayoutMath.itemWidth(248f), 0f)
        // Never zero (`max(1, …)`, FloatingTabBar.swift:98).
        assertEquals(1f, ShellLayoutMath.itemWidth(0f), 0f)
    }

    @Test
    fun tabAtMapsPositionsAndClamps() {
        val item = ShellLayoutMath.itemWidth(300f)
        assertEquals(MainTab.Chats, ShellLayoutMath.tabAt(0f, item))
        assertEquals(MainTab.Chats, ShellLayoutMath.tabAt(4f + 72f, item))
        assertEquals(MainTab.Contacts, ShellLayoutMath.tabAt(4f + 73f, item))
        assertEquals(MainTab.Calls, ShellLayoutMath.tabAt(4f + 2 * 73f + 1f, item))
        assertEquals(MainTab.Settings, ShellLayoutMath.tabAt(299f, item))
        assertEquals(MainTab.Chats, ShellLayoutMath.tabAt(-50f, item))
        assertEquals(MainTab.Settings, ShellLayoutMath.tabAt(5_000f, item))
    }

    @Test
    fun lensSitsOnItsTabAndStaysOverTheItems() {
        assertEquals(0f, ShellLayoutMath.lensOffset(MainTab.Chats, 73f), 0f)
        assertEquals(219f, ShellLayoutMath.lensOffset(MainTab.Settings, 73f), 0f)
        assertEquals(0f, ShellLayoutMath.clampLens(-20f, 73f), 0f)
        assertEquals(219f, ShellLayoutMath.clampLens(400f, 73f), 0f)
        assertEquals(100f, ShellLayoutMath.clampLens(100f, 73f), 0f)
    }

    @Test
    fun badgeTextCapsAtNinetyNinePlus() {
        assertNull(ShellLayoutMath.badgeText(null))
        assertNull(ShellLayoutMath.badgeText(0))
        assertNull(ShellLayoutMath.badgeText(-3))
        assertEquals("1", ShellLayoutMath.badgeText(1))
        assertEquals("99", ShellLayoutMath.badgeText(99))
        assertEquals("99+", ShellLayoutMath.badgeText(100))
    }

    @Test
    fun barHidesUnderAKeyboardItDoesNotRide() {
        // Header search: keyboard up, bar not following → hidden.
        assertFalse(ShellLayoutMath.barVisible(showsTabBar = true, followsKeyboard = false, keyboardVisible = true))
        // Bar search: it rides the keyboard.
        assertTrue(ShellLayoutMath.barVisible(showsTabBar = true, followsKeyboard = true, keyboardVisible = true))
        assertTrue(ShellLayoutMath.barVisible(showsTabBar = true, followsKeyboard = false, keyboardVisible = false))
        assertFalse(ShellLayoutMath.barVisible(showsTabBar = false, followsKeyboard = true, keyboardVisible = false))
        assertTrue(ShellLayoutMath.barFollowsKeyboard(isSearching = false, keyboardServesSearch = true))
        assertTrue(ShellLayoutMath.barAboveKeyboard(followsKeyboard = true, keyboardVisible = true))
        assertFalse(ShellLayoutMath.barAboveKeyboard(followsKeyboard = true, keyboardVisible = false))
    }

    @Test
    fun barBottomPerNavigationMode() {
        // Gesture navigation: 20 dp from the screen edge, the handle under the bar.
        assertEquals(20.dp, ShellLayoutMath.barBottom(aboveKeyboard = false, imeBottom = 0.dp, gestureNavigation = true, navigationBarBottom = 21.dp))
        // Three buttons: never over them, 8 dp above.
        assertEquals(56.dp, ShellLayoutMath.barBottom(aboveKeyboard = false, imeBottom = 0.dp, gestureNavigation = false, navigationBarBottom = 48.dp))
        // Above the keyboard: 8 dp over it …
        assertEquals(308.dp, ShellLayoutMath.barBottom(aboveKeyboard = true, imeBottom = 300.dp, gestureNavigation = true, navigationBarBottom = 21.dp))
        // … but never below its resting place while the keyboard rides down.
        assertEquals(20.dp, ShellLayoutMath.barBottom(aboveKeyboard = true, imeBottom = 5.dp, gestureNavigation = true, navigationBarBottom = 21.dp))
    }

    @Test
    fun sideInsetTightensOverTheKeyboard() {
        assertEquals(12.dp, ShellLayoutMath.barSideInset(aboveKeyboard = true))
        assertEquals(20.dp, ShellLayoutMath.barSideInset(aboveKeyboard = false))
    }

    @Test
    fun clearanceIsTheFullDistanceFromTheScreenBottom() {
        assertEquals(0.dp, ShellLayoutMath.tabBarClearance(barVisible = false, barBottom = 20.dp, isSearching = false))
        assertEquals(84.dp, ShellLayoutMath.tabBarClearance(barVisible = true, barBottom = 20.dp, isSearching = false))
        // While searching the bar is the 48 dp field.
        assertEquals(356.dp, ShellLayoutMath.tabBarClearance(barVisible = true, barBottom = 308.dp, isSearching = true))
    }

    @Test
    fun keyboardServesSearchUntilItIsFullyDown() {
        // Comes up for the bar's field.
        assertTrue(ShellLayoutMath.keyboardServesSearch(false, isSearching = true, imeBottom = 0.dp, imeTarget = 300.dp))
        // Search closed while the keyboard goes down: still served, so the bar rides it down.
        assertTrue(ShellLayoutMath.keyboardServesSearch(true, isSearching = false, imeBottom = 120.dp, imeTarget = 0.dp))
        // Fully down: released.
        assertFalse(ShellLayoutMath.keyboardServesSearch(true, isSearching = false, imeBottom = 0.dp, imeTarget = 0.dp))
        // A keyboard for the header field never serves the bar.
        assertFalse(ShellLayoutMath.keyboardServesSearch(false, isSearching = false, imeBottom = 200.dp, imeTarget = 300.dp))
    }

    @Test
    fun twoPaneFromSixHundredDp() {
        assertEquals(WindowLayout.Compact, ShellLayoutMath.windowLayout(360f))
        assertEquals(WindowLayout.Compact, ShellLayoutMath.windowLayout(599.9f))
        assertEquals(WindowLayout.TwoPane, ShellLayoutMath.windowLayout(600f))
        assertEquals(WindowLayout.TwoPane, ShellLayoutMath.windowLayout(840f))
        assertEquals(360.dp, ShellLayoutMath.listPaneWidth)
    }

    @Test
    fun tabSwitchMovesTowardTheNewTab() {
        assertEquals(14.dp, ShellLayoutMath.tabInsertOffset(movesForward = true))
        assertEquals((-14).dp, ShellLayoutMath.tabInsertOffset(movesForward = false))
        assertEquals((-10).dp, ShellLayoutMath.tabRemoveOffset(movesForward = true))
        assertEquals(10.dp, ShellLayoutMath.tabRemoveOffset(movesForward = false))
        assertEquals(0.985f, ShellLayoutMath.TAB_INSERT_SCALE, 0f)
        assertEquals(1.01f, ShellLayoutMath.TAB_REMOVE_SCALE, 0f)
    }

    @Test
    fun searchMorphGrowsTheCircleIntoTheField() {
        val density = 2f
        val width = 372f * density
        val tabs = ShellLayoutMath.morph(width, 0f, density)
        // At rest: the 64 dp circle at the trailing edge, the capsule fills the rest minus the gap.
        assertEquals(64f * density, tabs.height, 0.01f)
        assertEquals(width - 64f * density, tabs.fieldX, 0.01f)
        assertEquals(64f * density, tabs.fieldWidth, 0.01f)
        assertEquals(width - (64f + 8f) * density, tabs.capsuleWidth, 0.01f)
        val search = ShellLayoutMath.morph(width, 1f, density)
        // Searching: a 48 dp field from the leading edge to the ✕, which sits at the trailing edge.
        assertEquals(48f * density, search.height, 0.01f)
        assertEquals(0f, search.fieldX, 0.01f)
        assertEquals(width - (48f + 8f) * density, search.fieldWidth, 0.01f)
        assertEquals(width - 48f * density, search.closeX, 0.01f)
    }
}
