package de.corespace.shroud.ui.media.video

import de.corespace.shroud.core.media.video.VideoTrim
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** Which cut a trim handle moves (`VideoTrimStrip.Handle`, `VideoTrimStrip.swift:26`). */
enum class TrimHandle { Start, End }

/**
 * The trim strip's geometry and clamps (`VideoTrimStrip`, `ios/shroud/ShroudUI/Components/VideoTrimStrip.swift`;
 * conversation-compose-media §15.5). Pure: positions and widths share one unit (px in the strip),
 * times are seconds.
 *
 * The strip is the track plus one [HANDLE_WIDTH] handle at each end; a time `t` sits at
 * `HANDLE_WIDTH + x(t)` from the strip's start edge. Handles sit outside the kept range: the start
 * handle ends at its cut, the end handle begins at it.
 */
object TrimMath {
    /** Shortest clip the handles allow — a video message needs at least a moment (`:29`). */
    const val MINIMUM_DURATION = 1.0

    /** dp (`:30-31`). */
    const val HANDLE_WIDTH = 16f
    const val STRIP_HEIGHT = 48f

    /** Touch slop around a handle, dp (`:120`). */
    const val HANDLE_SLOP = 12f

    /** The seekable track: the strip minus one handle at each end, at least 1 (`:83-85`). */
    fun trackWidth(stripWidth: Float, handleWidth: Float = HANDLE_WIDTH): Float = max(1f, stripWidth - handleWidth * 2f)

    /** Where [seconds] sits on the track, clamped to it; 0 when the clip has no length (`:187-190`). */
    fun x(seconds: Double, duration: Double, trackWidth: Float): Float {
        if (!(duration > 0)) return 0f
        return (seconds / duration).coerceIn(0.0, 1.0).toFloat() * trackWidth
    }

    fun startX(trim: VideoTrim, duration: Double, trackWidth: Float, handleWidth: Float = HANDLE_WIDTH): Float =
        handleWidth + x(trim.start, duration, trackWidth)

    fun endX(trim: VideoTrim, duration: Double, trackWidth: Float, handleWidth: Float = HANDLE_WIDTH): Float =
        handleWidth + x(trim.end, duration, trackWidth)

    /** The shortest kept range: a second, or the whole clip when it is shorter (`:160`). */
    fun minimum(duration: Double): Double = min(MINIMUM_DURATION, duration)

    /**
     * A handle dragged to strip position [position] (`move(_:toX:)`, `:157-169`): the cut it lands
     * on, clamped so at least [minimum] stays kept. Null (nothing moves) for a clip without length
     * or before the strip was measured.
     */
    fun move(
        trim: VideoTrim,
        which: TrimHandle,
        position: Float,
        duration: Double,
        trackWidth: Float,
        handleWidth: Float = HANDLE_WIDTH,
    ): VideoTrim? {
        if (!(duration > 0) || !(trackWidth > 0f)) return null
        val raw = ((position - handleWidth) / trackWidth).toDouble() * duration
        val minimum = minimum(duration)
        return when (which) {
            TrimHandle.Start -> trim.copy(start = max(0.0, min(raw, trim.end - minimum)))
            TrimHandle.End -> trim.copy(end = min(duration, max(raw, trim.start + minimum)))
        }
    }

    /** TalkBack's step: one cut moved by [delta] seconds under the drag's limits (`nudge`, `:171-185`). */
    fun nudge(trim: VideoTrim, which: TrimHandle, delta: Double, duration: Double): VideoTrim? {
        if (!(duration > 0)) return null
        val minimum = minimum(duration)
        return when (which) {
            TrimHandle.Start -> trim.copy(start = max(0.0, min(trim.start + delta, trim.end - minimum)))
            TrimHandle.End -> trim.copy(end = min(duration, max(trim.end + delta, trim.start + minimum)))
        }
    }

    /** One TalkBack step: a twentieth of the clip, at least half a second (`:134`). */
    fun nudgeStep(duration: Double): Double = max(0.5, duration / 20.0)

    /** The time the dragged cut now sits on, which the preview seeks to (`onSeek`). */
    fun seekTime(trim: VideoTrim, which: TrimHandle): Double = if (which == TrimHandle.Start) trim.start else trim.end

    /** The playhead shows only inside the kept range and while no handle is held (`:65`). */
    fun showsPlayhead(playhead: Double?, trim: VideoTrim, dragging: Boolean): Boolean =
        playhead != null && !dragging && playhead >= trim.start && playhead <= trim.end

    /**
     * Which handle a touch at strip position [touchX] grabs, or null: the one whose slop-widened
     * box contains it (`contentShape(Rectangle().inset(by: -12))`, `:120`); where both do, the
     * nearer, and on a tie the end handle, which iOS draws on top.
     */
    fun handleAt(touchX: Float, startX: Float, endX: Float, handleWidth: Float = HANDLE_WIDTH, slop: Float = HANDLE_SLOP): TrimHandle? {
        // The start handle spans [startX − w, startX], the end handle [endX, endX + w].
        val reach = handleWidth / 2f + slop
        val nearStart = abs(touchX - (startX - handleWidth / 2f))
        val nearEnd = abs(touchX - (endX + handleWidth / 2f))
        val inStart = nearStart <= reach
        val inEnd = nearEnd <= reach
        return when {
            inStart && inEnd -> if (nearStart < nearEnd) TrimHandle.Start else TrimHandle.End
            inStart -> TrimHandle.Start
            inEnd -> TrimHandle.End
            else -> null
        }
    }
}
