package de.corespace.shroud.ui.theme

import androidx.compose.animation.core.SpringSpec
import androidx.compose.animation.core.TweenSpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Motion tokens against `ios/shroud/ShroudUI/Theme/Motion.swift:18-67` (plan §1.7.12): SwiftUI
 * spring(response, dampingFraction) becomes a unit-mass Compose spring with stiffness (2π / response)²
 * and dampingRatio = dampingFraction; `.easeOut` / `.easeInOut` literals become tweens on the
 * SwiftUI Bézier curves. Expected stiffness values are computed independently (Python, f64).
 */
class MotionTest {
    private fun spring(spec: Any): SpringSpec<Float> = spec as SpringSpec<Float>

    private fun tween(spec: Any): TweenSpec<Float> = spec as TweenSpec<Float>

    private fun assertSpring(spec: Any, dampingRatio: Float, stiffness: Float) {
        val s = spring(spec)
        assertEquals("dampingRatio", dampingRatio, s.dampingRatio, 1e-6f)
        assertEquals("stiffness", stiffness, s.stiffness, stiffness * 1e-5f)
    }

    @Test
    fun swiftStiffnessIsTwoPiOverResponseSquared() {
        assertEquals(584.0002604f, Motion.swiftStiffness(0.26f), 0.01f)
        assertEquals(157.9136704f, Motion.swiftStiffness(0.5f), 0.01f)
        assertEquals(341.5088028f, Motion.swiftStiffness(0.34f), 0.01f)
    }

    @Test
    fun springTokensMatchMotionSwift() {
        assertSpring(Motion.release<Float>(), 0.62f, 341.5088028f) // :22 spring(0.34, 0.62)
        assertSpring(Motion.snappy<Float>(), 0.86f, 584.0002604f) // :24 spring(0.26, 0.86)
        assertSpring(Motion.standard<Float>(), 0.9f, 273.3962438f) // :25 spring(0.38, 0.9)
        assertSpring(Motion.gentle<Float>(), 0.92f, 157.9136704f) // :26 spring(0.5, 0.92)
        assertSpring(Motion.bouncy<Float>(), 0.66f, 385.5314219f) // :27 spring(0.32, 0.66)
        assertSpring(Motion.reactionFlight<Float>(), 0.82f, 186.5709717f) // :29 spring(0.46, 0.82)
        assertSpring(Motion.wordReveal<Float>(), 0.72f, 503.5512450f) // EncryptionPhraseReveal.wordRevealSpring (0.28, 0.72)
    }

    @Test
    fun menuLiftIsTelegramsSpringAtUnitMass() {
        // interpolatingSpring(mass: 5, stiffness: 900, damping: 104) (`Motion.swift:36`): same natural
        // frequency √(k/m) and damping ratio c / (2√(k·m)) at unit mass.
        assertSpring(Motion.menuLift<Float>(), 0.7751702f, 180f)
        assertEquals(180f, Motion.MENU_LIFT_STIFFNESS)
        assertEquals(0.7752f, Motion.MENU_LIFT_DAMPING_RATIO, 1e-4f)
    }

    @Test
    fun easeOutTokensMatchMotionSwift() {
        listOf(
            Motion.press<Float>() to 90, // :20 easeOut 0.09
            Motion.fade<Float>() to 180, // :31 easeOut 0.18
            Motion.scrim<Float>() to 220, // :32 easeOut 0.22
            Motion.reduced<Float>() to 150, // :43 easeOut 0.15
            Motion.follow<Float>() to 250, // ConversationView.swift:469 easeOut 0.25
            Motion.easeOut<Float>(300) to 300,
        ).forEach { (spec, ms) ->
            val t = tween(spec)
            assertEquals(ms, t.durationMillis)
            assertEquals(0, t.delay)
            assertSame(Motion.IosEaseOut, t.easing)
        }
    }

    @Test
    fun easeInOutTokensMatchMotionSwift() {
        listOf(
            Motion.menuDrop<Float>() to 200, // :38-39 easeInOut 0.2
            Motion.chatOpenPush<Float>() to 350, // ChatOpenTransition.swift:5 easeInOut 0.35
            Motion.easeInOut<Float>(400) to 400,
        ).forEach { (spec, ms) ->
            val t = tween(spec)
            assertEquals(ms, t.durationMillis)
            assertSame(Motion.IosEaseInOut, t.easing)
        }
        assertEquals(200L, Motion.MENU_DROP_MS)
        assertEquals(150L, Motion.REDUCED_MS)
    }

    @Test
    fun swiftUiCurvesAreTheirBeziers() {
        // UnitCurve.easeOut = (0, 0, 0.58, 1), easeInOut = (0.42, 0, 0.58, 1); reference values solved numerically.
        assertEquals(0.6846432f, Motion.IosEaseOut.transform(0.5f), 1e-3f)
        assertEquals(0.3781381f, Motion.IosEaseOut.transform(0.25f), 1e-3f)
        assertEquals(0.5f, Motion.IosEaseInOut.transform(0.5f), 1e-3f)
        assertEquals(0.1291619f, Motion.IosEaseInOut.transform(0.25f), 1e-3f)
        assertEquals(0f, Motion.IosEaseOut.transform(0f), 1e-6f)
        assertEquals(1f, Motion.IosEaseInOut.transform(1f), 1e-6f)
    }

    @Test
    fun respectingSwapsInTheReducedFade() {
        val reduced = tween(Motion.respecting(true, Motion.bouncy<Float>()))
        assertEquals(150, reduced.durationMillis)
        assertSpring(Motion.respecting(false, Motion.bouncy<Float>()), 0.66f, 385.5314219f)
    }

    @Test
    fun phraseRevealConstants() {
        assertEquals(45L, Motion.PHRASE_PAIR_DELAY_MS)
        assertEquals(320L, Motion.BADGE_PULSE_MS)
    }

    @Test
    fun transitionsCollapseToTheFadeUnderReduceMotion() {
        val swap = Motion.iconSwap
        assertSame(swap, swap.respecting(false))
        val reduced = swap.respecting(true)
        assertEquals(Motion.reducedTransition.enter, reduced.enter)
        assertEquals(Motion.reducedTransition.exit, reduced.exit)
        // Every shared transition has both halves (`Motion.swift:52-67`).
        listOf(Motion.iconSwap, Motion.riseFromBottom, Motion.bubbleIn(isMine = true), Motion.bubbleIn(isMine = false)).forEach {
            assertTrue(it.enter.toString().isNotEmpty())
            assertTrue(it.exit.toString().isNotEmpty())
        }
    }
}
