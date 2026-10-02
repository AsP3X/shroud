package de.corespace.shroud.ui.media.video

import de.corespace.shroud.core.media.video.ChatVideoPlayer
import kotlin.math.abs
import kotlin.math.max

/**
 * The full-screen player's chrome and gesture rules (`VideoPlayerOverlay`,
 * `ios/shroud/ShroudUI/Components/VideoPlayerOverlay.swift`; conversation-compose-media §17). Pure;
 * distances in dp, times in seconds.
 */
internal object VideoPlayerRules {
    /** How far down the finger travels before the player lets go (`:25`). */
    const val DISMISS_DISTANCE = 110f

    /** A flick whose predicted end passes this lets go too (`:322`). */
    const val DISMISS_PREDICTED = 320f

    /** A drag must travel this far before it counts (`DragGesture(minimumDistance: 14)`, `:320`). */
    const val DRAG_MIN_DISTANCE = 14f

    /** Idle time before the chrome fades out during playback (`:27`). */
    const val CHROME_IDLE_MS = 2_800L

    /** Chrome fades in and out over 0.18 s (`showChrome` / `hideChrome`, `:343-351`). */
    const val CHROME_FADE_MS = 180

    /** "-m:ss" left to play; "-0:00" before the duration is known (`:34-37`). */
    fun remainingLabel(duration: Double, shown: Double): String {
        if (!(duration > 0)) return "-0:00"
        return "-" + ChatVideoPlayer.timeLabel(max(0.0, duration - shown))
    }

    /** The backdrop dims as the clip travels, never under 35 % (`:39-41`). */
    fun backdropOpacity(dy: Float): Float = max(0.35f, 1f - abs(dy) / 420f)

    /** The chrome lets go with the clip (`:44-46`). */
    fun chromeOpacity(dy: Float): Float = max(0f, 1f - abs(dy) / 180f)

    /** Down to 86 % while dragged; none under Reduce Motion (`:53-56`). */
    fun dragScale(dy: Float, reduceMotion: Boolean): Float = if (reduceMotion) 1f else max(0.86f, 1f - abs(dy) / 1600f)

    /**
     * SwiftUI's `predictedEndTranslation` at the normal deceleration rate (0.998 per ms): where the
     * finger's speed would carry the drag — about half a second more of travel.
     */
    fun predictedEnd(dy: Float, velocityY: Float): Float = dy + velocityY * PREDICTION_SECONDS

    /** Whether a drag ending at [dy] closes the player (`:321-326`): far enough, or flung down. */
    fun shouldDismiss(dy: Float, predictedEnd: Float): Boolean = abs(dy) > DISMISS_DISTANCE || predictedEnd > DISMISS_PREDICTED

    /** The playhead under a finger at [x] on a scrubber [width] wide (`:290`). */
    fun scrubTarget(x: Float, width: Float, duration: Double): Double {
        if (!(duration > 0)) return 0.0
        val w = max(1f, width)
        return (x.coerceIn(0f, w) / w).toDouble() * duration
    }

    /** One TalkBack step: a twentieth of the clip, at least a second (`:309`). */
    fun accessibilityStep(duration: Double): Double = max(1.0, duration / 20.0)

    /** The centre control: up while paused, else it follows the chrome (`:150`). */
    fun showsCentreControl(isReady: Boolean, isPlaying: Boolean, chromeVisible: Boolean): Boolean =
        isReady && (!isPlaying || chromeVisible)

    /** Chrome hides by itself only while playing, not scrubbing, and when nothing assistive needs it (`:356-363`). */
    fun autoHides(isPlaying: Boolean, scrubbing: Boolean, assistive: Boolean): Boolean = isPlaying && !scrubbing && !assistive

    /** The scrubber position, 0…1 (`:281`). */
    fun fraction(shown: Double, duration: Double): Float =
        if (duration > 0) (shown / duration).coerceIn(0.0, 1.0).toFloat() else 0f

    /** TalkBack's value for the scrubber (`:306`). */
    fun positionValue(shown: Double, duration: Double): String =
        "${ChatVideoPlayer.timeLabel(shown)} of ${ChatVideoPlayer.timeLabel(duration)}"

    private const val PREDICTION_SECONDS = 0.499f
}
