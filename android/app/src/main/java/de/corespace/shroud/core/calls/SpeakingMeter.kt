package de.corespace.shroud.core.calls

import kotlin.math.log10

/**
 * The speaking indicator's level mapping (iOS `SpeakingIndicatorView.Meter`,
 * `ios/shroud/ShroudUI/Components/SpeakingIndicatorView.swift:83-99`; calls §8.10, §12). The level
 * comes from [CallController.localAudioLevel] (sender stats, memory *Call mic level source*); the
 * bars and their easing are the call screen's (W3-CALLS-UI).
 */
object SpeakingMeter {
    /** Below this display level the microphone counts as quiet (about −45 dBFS after the mapping). */
    const val GATE = 0.12f

    /** Linear 0…1 to a display fraction: −50 dBFS is the floor, −10 dBFS fills the bar. */
    fun display(linear: Float): Float {
        if (!(linear > 0f)) return 0f
        val decibels = 20f * log10(linear)
        return ((decibels + 50f) / 40f).coerceIn(0f, 1f)
    }
}
