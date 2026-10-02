package de.corespace.shroud.ui.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Settings hero's collapse (settings-lock §3.3, §18.3 `SettingsHeroMathTest`), checked against
 * the formulas of `SettingsView.swift:41-143` evaluated by hand.
 */
class SettingsHeroMathTest {
    private val eps = 1e-4f

    @Test
    fun theHeroIs178TallAndCollapsesOver134() {
        assertEquals(178f, SettingsHeroMath.EXPANDED_HERO_HEIGHT, 0f)
        assertEquals(134f, SettingsHeroMath.collapseDistance, 0f)
        assertEquals(134f, SettingsHeroMath.contentSpacer, 0f)
    }

    @Test
    fun progressAtOffsets0_67_134_200() {
        assertEquals(0f, SettingsHeroMath.progress(0f), 0f)
        assertEquals(0.5f, SettingsHeroMath.progress(67f), eps)
        assertEquals(1f, SettingsHeroMath.progress(134f), 0f)
        assertEquals(1f, SettingsHeroMath.progress(200f), 0f)
        // An overscroll above the top never runs the collapse backwards.
        assertEquals(0f, SettingsHeroMath.progress(-30f), 0f)
    }

    @Test
    fun atRest() {
        val f = SettingsHeroMath.frame(0f)
        assertEquals(178f, f.stickyHeight, eps)
        assertEquals(1f, f.avatarScale, eps)
        assertEquals(0f, f.avatarBlur, eps)
        assertEquals(1f, f.avatarOpacity, eps)
        assertEquals(56f, f.avatarCenterY, eps) // 12 + 88 / 2
        assertEquals(127f, f.nameCenterY, eps) // 12 + 88 + 12 + 15
        assertEquals(26f, f.nameFontSize, eps)
        assertTrue(f.nameIsBold)
        assertEquals(1f, f.handleOpacity, eps)
        assertEquals(156f, f.handleCenterY, eps) // 127 + 15 + 4 + 10
        assertEquals(1f, f.badgeOpacity, eps)
        assertEquals(0f, f.barTitleProgress, eps)
    }

    @Test
    fun halfway() {
        val f = SettingsHeroMath.frame(67f)
        assertEquals(0.5f, f.progress, eps)
        assertEquals(111f, f.stickyHeight, eps) // 178 − 67
        assertEquals(0.61f, f.avatarScale, eps) // 1 − 0.78 · 0.5
        assertEquals(10f, f.avatarBlur, eps)
        assertEquals(0.545904f, f.avatarOpacity, eps) // 1 − 0.5^1.25 · 1.08
        assertEquals(3.8f, f.avatarCenterY, eps) // 56 + (−48.4 − 56) · 0.5
        assertEquals(71.875f, f.nameCenterY, eps) // 127 + (22 − 127) · 0.525
        assertEquals(23.75f, f.nameFontSize, eps) // 26 − 9 · 0.25
        assertTrue("SemiBold only past halfway", f.nameIsBold)
        assertEquals(0f, f.handleOpacity, eps)
        assertEquals(100.875f, f.handleCenterY, eps) // 71.875 + 29
        assertEquals(0f, f.badgeOpacity, eps) // 1 − 2.4 · 0.5 < 0
        assertEquals(0f, f.barTitleProgress, eps)
    }

    @Test
    fun collapsed() {
        val f = SettingsHeroMath.frame(134f)
        assertEquals(44f, f.stickyHeight, eps)
        assertEquals(0.22f, f.avatarScale, eps)
        assertEquals(20f, f.avatarBlur, eps)
        assertEquals(0f, f.avatarOpacity, eps)
        assertEquals(-48.4f, f.avatarCenterY, eps)
        assertEquals(22f, f.nameCenterY, eps) // the compact bar's centre
        assertEquals(17f, f.nameFontSize, eps)
        assertFalse(f.nameIsBold)
        assertEquals(51f, f.handleCenterY, eps)
        assertEquals(1f, f.barTitleProgress, eps)
        // Scrolling on keeps the bar compact.
        assertEquals(44f, SettingsHeroMath.frame(400f).stickyHeight, eps)
    }

    @Test
    fun theNameReachesTheBarBeforeTheEnd() {
        // t = min(1, 1.05 p): the name is in place at p ≈ 0.952 while the avatar is still leaving.
        val f = SettingsHeroMath.frame(134f / 1.05f)
        assertEquals(22f, f.nameCenterY, 1e-3f)
        assertTrue(f.avatarCenterY > -48.4f)
    }

    @Test
    fun theBarTitleTakesOverInTheLast15Percent() {
        assertEquals(0f, SettingsHeroMath.frame(134f * 0.84f).barTitleProgress, eps)
        assertEquals(0f, SettingsHeroMath.frame(134f * 0.85f).barTitleProgress, eps)
        assertEquals(0.5f, SettingsHeroMath.frame(134f * 0.925f).barTitleProgress, 1e-3f)
        assertEquals(1f, SettingsHeroMath.frame(134f).barTitleProgress, eps)
    }

    @Test
    fun theBadgeAndHandleFadeEarly() {
        // badge: 1 − 2.4 p, gone at p = 1/2.4; handle: 1 − 2 p, gone at p = 0.5.
        assertEquals(0.52f, SettingsHeroMath.frame(134f * 0.2f).badgeOpacity, eps)
        assertEquals(0.6f, SettingsHeroMath.frame(134f * 0.2f).handleOpacity, eps)
        assertEquals(0f, SettingsHeroMath.frame(134f * 0.42f).badgeOpacity, eps)
    }

    @Test
    fun smallScrollChangesAreNotApplied() {
        assertFalse(SettingsHeroMath.shouldApply(10f, 10.2f))
        assertFalse(SettingsHeroMath.shouldApply(10f, 9.85f))
        assertTrue(SettingsHeroMath.shouldApply(10f, 10.25f))
        assertTrue(SettingsHeroMath.shouldApply(0f, 1f))
    }
}
