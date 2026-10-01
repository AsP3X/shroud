package de.corespace.shroud.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The pure numbers behind the skeleton shimmer (`Motion.swift:216-268`, shell-chats §10.6), the
 * phrase placeholder (`ShimmerPlaceholder.swift:4-36`, design-inventory addendum SH.1), the press
 * style (`Motion.swift:77-97`) and the phrase badge pulse (`EncryptionPhraseCard.swift:93-110`).
 */
class ShimmerAndPressTest {
    @Test
    fun skeletonSweepRunsEvery1400Ms() {
        assertEquals(1_400L, Shimmer.PERIOD_MS)
        assertEquals(0f, Shimmer.phaseAt(0), 0f)
        assertEquals(0.5f, Shimmer.phaseAt(700), 1e-6f)
        assertEquals(0f, Shimmer.phaseAt(1_400), 0f)
        assertEquals(0.5f, Shimmer.phaseAt(2_100), 1e-6f)
        assertEquals(0.75f, Shimmer.phaseAt(-350), 1e-6f)
        // Same instant, same phase: separate placeholders pulse in step.
        assertEquals(Shimmer.phaseAt(123_456_789), Shimmer.phaseAt(123_456_789), 0f)
    }

    @Test
    fun skeletonBandTravelsFromOffLeadingToOffTrailing() {
        val width = 100f
        assertEquals(0.6f, Shimmer.BAND_FRACTION)
        assertEquals(-60f, Shimmer.bandStart(0f, width), 1e-4f) // −0.6 w: fully off the leading edge
        assertEquals(20f, Shimmer.bandStart(0.5f, width), 1e-4f)
        assertEquals(100f, Shimmer.bandStart(1f, width), 1e-4f) // +1.0 w: fully off the trailing edge
    }

    @Test
    fun skeletonPeakAdaptsToDarkMode() {
        assertEquals(0.65f, Shimmer.peakAlpha(dark = false, adaptsToAppearance = true))
        assertEquals(0.12f, Shimmer.peakAlpha(dark = true, adaptsToAppearance = true))
        assertEquals(0.65f, Shimmer.peakAlpha(dark = true, adaptsToAppearance = false))
        assertEquals(0.65f, Shimmer.peakAlpha(dark = false, adaptsToAppearance = false))
    }

    @Test
    fun phrasePlaceholderSweepsItsHighlightFromMinusPoint1ToOnePoint9Widths() {
        assertEquals(850, PHRASE_SHIMMER_MS)
        assertEquals(1.8f, PHRASE_SHIMMER_SPAN)
        val width = 92f
        // The highlight is the middle of a 1.8 w gradient that starts at w × phase, phase −1 → 1.
        val middleAtStart = phraseShimmerStart(-1f, width) + PHRASE_SHIMMER_SPAN * width / 2
        val middleAtEnd = phraseShimmerStart(1f, width) + PHRASE_SHIMMER_SPAN * width / 2
        assertEquals(-0.1f * width, middleAtStart, 1e-3f)
        assertEquals(1.9f * width, middleAtEnd, 1e-3f)
    }

    @Test
    fun pressScalesOnlyWhileDownAndNeverUnderReduceMotion() {
        assertEquals(0.96f, pressScale(down = true, scale = PRESS_SCALE, reduceMotion = false))
        assertEquals(1f, pressScale(down = true, scale = PRESS_SCALE, reduceMotion = true))
        assertEquals(1f, pressScale(down = false, scale = PRESS_SCALE, reduceMotion = false))
        assertEquals(0.975f, pressScale(down = true, scale = 0.975f, reduceMotion = false))
        // `pressable()` defaults (`Motion.swift:124-128`).
        assertEquals(0.96f, PRESS_SCALE)
        assertEquals(0.08f, PRESS_DIMMING)
    }

    @Test
    fun phraseBadgePulsesOnlyWhenItsWordArrives() {
        assertTrue(badgePulses(was = false, now = true))
        assertFalse(badgePulses(was = true, now = true)) // composed already revealed: no flash (E1)
        assertFalse(badgePulses(was = true, now = false))
        assertFalse(badgePulses(was = false, now = false))
    }
}
