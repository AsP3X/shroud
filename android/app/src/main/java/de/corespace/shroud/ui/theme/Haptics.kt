package de.corespace.shroud.ui.theme

import android.annotation.SuppressLint
import android.os.Build
import android.view.HapticFeedbackConstants
import android.view.View
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalView
import de.corespace.shroud.core.model.Haptic

/**
 * The one mapping from a [Haptic] intent to Android feedback (00-plan §1.7.12, conflict C19). iOS
 * calls `Haptics.impact(_:)` / `Haptics.notification(_:)` (`ShroudUI/Theme/Haptics.swift:7-21`);
 * Android plays a `HapticFeedbackConstants` effect on a view, which honours the system's touch
 * feedback setting and needs no `VIBRATE` permission.
 *
 * | Haptic | iOS | Android |
 * | --- | --- | --- |
 * | Light, Soft | impact light / soft | `CLOCK_TICK` |
 * | Medium | impact medium | `KEYBOARD_TAP` |
 * | Rigid | impact rigid (recording discarded) | `GESTURE_THRESHOLD_DEACTIVATE` (34+), else `CONTEXT_CLICK` |
 * | Heavy | impact heavy (swipe armed) | `GESTURE_THRESHOLD_ACTIVATE` (34+), else `VIRTUAL_KEY` |
 * | LongPress | menu open | `LONG_PRESS` |
 * | Success | notification success | `CONFIRM` |
 * | Warning, Error | notification warning / error | `REJECT` |
 * | LockEngaged | success when a recording locks (`ChatComposerView.swift:373`) | `GESTURE_THRESHOLD_ACTIVATE` (34+), else `CONFIRM` |
 * | SegmentTick | pull-to-refresh threshold | `SEGMENT_TICK` (34+), else `CLOCK_TICK` |
 */
object Haptics {
    /** The feedback constant for [haptic] on API level [sdk]; null for [Haptic.None]. */
    @SuppressLint("InlinedApi") // Constants are compile-time ints; the API-34 ones are guarded by sdk.
    fun feedbackConstant(haptic: Haptic, sdk: Int = Build.VERSION.SDK_INT): Int? = when (haptic) {
        Haptic.None -> null
        Haptic.Light, Haptic.Soft -> HapticFeedbackConstants.CLOCK_TICK
        Haptic.Medium -> HapticFeedbackConstants.KEYBOARD_TAP
        Haptic.Rigid ->
            if (sdk >= API_GESTURE_THRESHOLDS) HapticFeedbackConstants.GESTURE_THRESHOLD_DEACTIVATE else HapticFeedbackConstants.CONTEXT_CLICK
        Haptic.Heavy ->
            if (sdk >= API_GESTURE_THRESHOLDS) HapticFeedbackConstants.GESTURE_THRESHOLD_ACTIVATE else HapticFeedbackConstants.VIRTUAL_KEY
        Haptic.LongPress -> HapticFeedbackConstants.LONG_PRESS
        Haptic.Success -> HapticFeedbackConstants.CONFIRM
        Haptic.Warning, Haptic.Error -> HapticFeedbackConstants.REJECT
        Haptic.LockEngaged ->
            if (sdk >= API_GESTURE_THRESHOLDS) HapticFeedbackConstants.GESTURE_THRESHOLD_ACTIVATE else HapticFeedbackConstants.CONFIRM
        Haptic.SegmentTick ->
            if (sdk >= API_GESTURE_THRESHOLDS) HapticFeedbackConstants.SEGMENT_TICK else HapticFeedbackConstants.CLOCK_TICK
    }

    /** `GESTURE_THRESHOLD_ACTIVATE` / `_DEACTIVATE` and `SEGMENT_TICK` arrived in Android 14. */
    const val API_GESTURE_THRESHOLDS = 34
}

/** Plays [haptic] on this view (no-op for [Haptic.None]). */
fun View.perform(haptic: Haptic) {
    val constant = Haptics.feedbackConstant(haptic) ?: return
    performHapticFeedback(constant)
}

/** A haptic player bound to the current view, for composables: `val haptic = rememberHaptics(); haptic(Haptic.Success)`. */
@Composable
fun rememberHaptics(): (Haptic) -> Unit {
    val view = LocalView.current
    return remember(view) { { haptic: Haptic -> view.perform(haptic) } }
}
