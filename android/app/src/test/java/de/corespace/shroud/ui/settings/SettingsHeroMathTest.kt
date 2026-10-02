package de.corespace.shroud.ui.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Settings hero's collapse (settings-lock §3.3, §18.3 `SettingsHeroMathTest`), checked against
 * the formulas of `SettingsView.swift:41-143` evaluated by hand, and against the iOS code itself run
 * in Swift ([matchesTheIosFormulasEvaluatedInSwift]; iOS has no test of its own for the hero).
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

    /**
     * Every value of [SettingsHeroMath.frame] against the iOS code itself: `SettingsView.swift:44-143`
     * copied verbatim into a Swift script and run with Apple Swift 6.4 on 2026-10-03 (CGFloat,
     * printed to six decimals). Columns: offset, progress, sticky height, avatar scale, blur,
     * opacity, avatar centre, name centre, name size, bold, handle opacity, handle centre, badge
     * opacity, bar title progress.
     */
    @Test
    fun matchesTheIosFormulasEvaluatedInSwift() {
        val swift = listOf(
            SwiftRow(-30f, 0f, 208f, 1f, 0f, 1f, 56f, 127f, 26f, true, 1f, 156f, 1f, 0f),
            SwiftRow(0f, 0f, 178f, 1f, 0f, 1f, 56f, 127f, 26f, true, 1f, 156f, 1f, 0f),
            SwiftRow(0.2f, 0.001493f, 177.8f, 0.998836f, 0.029851f, 0.999683f, 55.844179f, 126.835448f, 25.99998f, true, 0.997015f, 155.835448f, 0.996418f, 0f),
            SwiftRow(10f, 0.074627f, 168f, 0.941791f, 1.492537f, 0.957875f, 48.208955f, 118.772388f, 25.949877f, true, 0.850746f, 147.772388f, 0.820896f, 0f),
            SwiftRow(26.8f, 0.2f, 151.2f, 0.844f, 4f, 0.855552f, 35.12f, 104.95f, 25.64f, true, 0.6f, 133.95f, 0.52f, 0f),
            SwiftRow(33.5f, 0.25f, 144.5f, 0.805f, 5f, 0.809081f, 29.9f, 99.4375f, 25.4375f, true, 0.5f, 128.4375f, 0.4f, 0f),
            SwiftRow(50f, 0.373134f, 128f, 0.708955f, 7.462687f, 0.68504f, 17.044776f, 85.86194f, 24.746937f, true, 0.253731f, 114.86194f, 0.104478f, 0f),
            SwiftRow(67f, 0.5f, 111f, 0.61f, 10f, 0.545916f, 3.8f, 71.875f, 23.75f, true, 0f, 100.875f, 0f, 0f),
            SwiftRow(80f, 0.597015f, 98f, 0.534328f, 11.940299f, 0.433232f, -6.328358f, 61.179104f, 22.792159f, false, 0f, 90.179104f, 0f, 0f),
            SwiftRow(100f, 0.746269f, 78f, 0.41791f, 14.925373f, 0.250895f, -21.910448f, 44.723881f, 20.987748f, false, 0f, 73.723881f, 0f, 0f),
            SwiftRow(113.9f, 0.85f, 64.1f, 0.337f, 17f, 0.118551f, -32.74f, 33.2875f, 19.4975f, false, 0f, 62.2875f, 0f, 0f),
            SwiftRow(120f, 0.895522f, 58f, 0.301493f, 17.910448f, 0.059152f, -37.492537f, 28.268657f, 18.782357f, false, 0f, 57.268657f, 0f, 0.303483f),
            SwiftRow(127.619048f, 0.952381f, 50.380952f, 0.257143f, 19.047619f, 0f, -43.428572f, 22f, 17.836735f, false, 0f, 51f, 0f, 0.68254f),
            SwiftRow(130f, 0.970149f, 48f, 0.243284f, 19.402985f, 0f, -45.283582f, 22f, 17.529294f, false, 0f, 51f, 0f, 0.800995f),
            SwiftRow(134f, 1f, 44f, 0.22f, 20f, 0f, -48.4f, 22f, 17f, false, 0f, 51f, 0f, 1f),
            SwiftRow(200f, 1f, 44f, 0.22f, 20f, 0f, -48.4f, 22f, 17f, false, 0f, 51f, 0f, 1f),
            SwiftRow(400f, 1f, 44f, 0.22f, 20f, 0f, -48.4f, 22f, 17f, false, 0f, 51f, 0f, 1f),
        )
        // Float against the Swift CGFloat (Double): a few ulps of the largest values.
        val tolerance = 2e-4f
        for (expected in swift) {
            val f = SettingsHeroMath.frame(expected.offset)
            val at = "offset ${expected.offset}"
            assertEquals(at, expected.progress, f.progress, tolerance)
            assertEquals(at, expected.sticky, f.stickyHeight, tolerance)
            assertEquals(at, expected.avatarScale, f.avatarScale, tolerance)
            assertEquals(at, expected.avatarBlur, f.avatarBlur, tolerance)
            assertEquals(at, expected.avatarOpacity, f.avatarOpacity, tolerance)
            assertEquals(at, expected.avatarCenterY, f.avatarCenterY, tolerance)
            assertEquals(at, expected.nameCenterY, f.nameCenterY, tolerance)
            assertEquals(at, expected.nameFontSize, f.nameFontSize, tolerance)
            assertEquals(at, expected.nameIsBold, f.nameIsBold)
            assertEquals(at, expected.handleOpacity, f.handleOpacity, tolerance)
            assertEquals(at, expected.handleCenterY, f.handleCenterY, tolerance)
            assertEquals(at, expected.badgeOpacity, f.badgeOpacity, tolerance)
            assertEquals(at, expected.barTitleProgress, f.barTitleProgress, tolerance)
        }
    }

    private data class SwiftRow(
        val offset: Float,
        val progress: Float,
        val sticky: Float,
        val avatarScale: Float,
        val avatarBlur: Float,
        val avatarOpacity: Float,
        val avatarCenterY: Float,
        val nameCenterY: Float,
        val nameFontSize: Float,
        val nameIsBold: Boolean,
        val handleOpacity: Float,
        val handleCenterY: Float,
        val badgeOpacity: Float,
        val barTitleProgress: Float,
    )

    @Test
    fun smallScrollChangesAreNotApplied() {
        assertFalse(SettingsHeroMath.shouldApply(10f, 10.2f))
        assertFalse(SettingsHeroMath.shouldApply(10f, 9.85f))
        assertTrue(SettingsHeroMath.shouldApply(10f, 10.25f))
        assertTrue(SettingsHeroMath.shouldApply(0f, 1f))
    }
}
