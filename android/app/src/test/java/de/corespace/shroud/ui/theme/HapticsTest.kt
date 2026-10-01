package de.corespace.shroud.ui.theme

import android.view.HapticFeedbackConstants
import de.corespace.shroud.core.model.Haptic
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The haptic mapping of plan §1.7.12 (conflict C19) per API level: Android 11–13 (30–33) fall
 * back from the Android 14 gesture-threshold and segment-tick constants.
 */
class HapticsTest {
    private val legacy = listOf(30, 31, 32, 33)
    private val modern = listOf(34, 35, 36, 37)

    private fun assertMaps(haptic: Haptic, expected: Int, sdks: List<Int>) {
        sdks.forEach { sdk -> assertEquals("$haptic on API $sdk", expected, Haptics.feedbackConstant(haptic, sdk)) }
    }

    @Test
    fun impactsMapTheSameOnEveryApiLevel() {
        val all = legacy + modern
        assertMaps(Haptic.Light, HapticFeedbackConstants.CLOCK_TICK, all)
        assertMaps(Haptic.Soft, HapticFeedbackConstants.CLOCK_TICK, all)
        assertMaps(Haptic.Medium, HapticFeedbackConstants.KEYBOARD_TAP, all)
        assertMaps(Haptic.LongPress, HapticFeedbackConstants.LONG_PRESS, all)
    }

    @Test
    fun notificationsUseConfirmAndRejectFromApi30() {
        val all = legacy + modern
        assertMaps(Haptic.Success, HapticFeedbackConstants.CONFIRM, all)
        assertMaps(Haptic.Warning, HapticFeedbackConstants.REJECT, all)
        assertMaps(Haptic.Error, HapticFeedbackConstants.REJECT, all)
    }

    @Test
    fun rigidIsGestureThresholdDeactivateFrom34ElseContextClick() {
        assertMaps(Haptic.Rigid, HapticFeedbackConstants.GESTURE_THRESHOLD_DEACTIVATE, modern)
        assertMaps(Haptic.Rigid, HapticFeedbackConstants.CONTEXT_CLICK, legacy)
    }

    @Test
    fun heavyIsGestureThresholdActivateFrom34ElseVirtualKey() {
        assertMaps(Haptic.Heavy, HapticFeedbackConstants.GESTURE_THRESHOLD_ACTIVATE, modern)
        assertMaps(Haptic.Heavy, HapticFeedbackConstants.VIRTUAL_KEY, legacy)
    }

    @Test
    fun lockEngagedIsGestureThresholdActivateFrom34ElseConfirm() {
        assertMaps(Haptic.LockEngaged, HapticFeedbackConstants.GESTURE_THRESHOLD_ACTIVATE, modern)
        assertMaps(Haptic.LockEngaged, HapticFeedbackConstants.CONFIRM, legacy)
    }

    @Test
    fun segmentTickIsSegmentTickFrom34ElseClockTick() {
        assertMaps(Haptic.SegmentTick, HapticFeedbackConstants.SEGMENT_TICK, modern)
        assertMaps(Haptic.SegmentTick, HapticFeedbackConstants.CLOCK_TICK, legacy)
    }

    @Test
    fun noneIsSilentAndEveryOtherIntentPlays() {
        (legacy + modern).forEach { sdk ->
            assertNull(Haptics.feedbackConstant(Haptic.None, sdk))
            Haptic.entries.filter { it != Haptic.None }.forEach { assertNotNull("$it on $sdk", Haptics.feedbackConstant(it, sdk)) }
        }
    }

    @Test
    fun theThresholdIsAndroid14() {
        assertEquals(34, Haptics.API_GESTURE_THRESHOLDS)
    }
}
