package de.corespace.shroud.ui.media.video

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.dropShadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.shadow.Shadow
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import de.corespace.shroud.core.media.video.ChatVideoPlayer
import de.corespace.shroud.core.media.video.VideoTrim
import de.corespace.shroud.core.model.Haptic
import de.corespace.shroud.ui.components.shimmering
import de.corespace.shroud.ui.theme.Motion
import de.corespace.shroud.ui.theme.rememberHaptics

/**
 * Telegram's trim bar: a filmstrip with two draggable handles and a live playhead (`VideoTrimStrip`,
 * `ios/shroud/ShroudUI/Components/VideoTrimStrip.swift`; conversation-compose-media §15.5). The
 * geometry and clamps are [TrimMath].
 *
 * Human: everything outside the handles dims, the kept region gets a bright frame, and the handles
 * are wide enough to grab without hiding the frames they bound.
 *
 * Agent: writes only through [onTrimChange]. [onSeek] previews the frame a handle sits on;
 * [onScrubEnd] is when the host should resume playing. TalkBack adjusts each handle by
 * [TrimMath.nudgeStep].
 */
@Composable
internal fun VideoTrimStrip(
    frames: List<ImageBitmap>,
    duration: Double,
    trim: VideoTrim,
    onTrimChange: (VideoTrim) -> Unit,
    playhead: Double?,
    onSeek: (Double) -> Unit,
    onScrubEnd: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val haptic = rememberHaptics()
    var active by remember { mutableStateOf<TrimHandle?>(null) }
    val currentTrim by rememberUpdatedState(trim)
    val currentDuration by rememberUpdatedState(duration)
    val currentOnTrim by rememberUpdatedState(onTrimChange)
    val currentOnSeek by rememberUpdatedState(onSeek)
    val currentOnScrubEnd by rememberUpdatedState(onScrubEnd)
    val handle = TrimMath.HANDLE_WIDTH.dp
    val stripHeight = TrimMath.STRIP_HEIGHT.dp

    BoxWithConstraints(
        modifier
            .fillMaxWidth()
            .height(stripHeight)
            .pointerInput(Unit) {
                val handlePx = TrimMath.HANDLE_WIDTH.dp.toPx()
                val slopPx = TrimMath.HANDLE_SLOP.dp.toPx()
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val track = TrimMath.trackWidth(size.width.toFloat(), handlePx)
                    val startX = TrimMath.startX(currentTrim, currentDuration, track, handlePx)
                    val endX = TrimMath.endX(currentTrim, currentDuration, track, handlePx)
                    val which = TrimMath.handleAt(down.position.x, startX, endX, handlePx, slopPx) ?: return@awaitEachGesture
                    // Where the finger landed relative to the cut, so the cut moves by the finger's travel (`:21-23`).
                    val grab = down.position.x - if (which == TrimHandle.Start) startX else endX
                    active = which
                    haptic(Haptic.Light)
                    down.consume()
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        if (!change.pressed) break
                        TrimMath.move(currentTrim, which, change.position.x - grab, currentDuration, track, handlePx)?.let { moved ->
                            currentOnTrim(moved)
                            currentOnSeek(TrimMath.seekTime(moved, which))
                        }
                        change.consume()
                    }
                    active = null
                    currentOnScrubEnd()
                }
            },
    ) {
        val widthPx = with(density) { maxWidth.toPx() }
        val handlePx = with(density) { handle.toPx() }
        val track = TrimMath.trackWidth(widthPx, handlePx)
        val startX = with(density) { TrimMath.startX(trim, duration, track, handlePx).toDp() }
        val endX = with(density) { TrimMath.endX(trim, duration, track, handlePx).toDp() }
        val trackDp = with(density) { track.toDp() }

        // Tiles span the track only, so each sits under the time it shows (`:39-46`).
        Box(
            Modifier
                .fillMaxSize()
                .clip(RoundedCornerShape(8.dp))
                .clearAndSetSemantics { },
        ) {
            Row(
                Modifier
                    .fillMaxSize()
                    .padding(horizontal = handle)
                    .animateContentSize(Motion.snappy()),
            ) {
                if (frames.isEmpty()) {
                    // The compose screen is dark in both appearances (`:94-97`).
                    Box(Modifier.fillMaxSize().background(Color.White.copy(alpha = 0.09f)).shimmering(adaptsToAppearance = false))
                } else {
                    frames.forEach { frame ->
                        Image(
                            bitmap = frame,
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.weight(1f).fillMaxSize(),
                        )
                    }
                }
            }
        }

        // What will be cut away reads as "off" (`:49-50`).
        Dim(from = handle, to = startX, height = stripHeight)
        Dim(from = endX, to = handle + trackDp, height = stripHeight)

        // The kept region's frame wraps the handles too (`:53-57`).
        Box(
            Modifier
                .offset(x = startX - handle)
                .size(width = (endX - startX).coerceAtLeast(0.dp) + handle * 2, height = stripHeight)
                .border(2.5.dp, Color.White, RoundedCornerShape(8.dp)),
        )

        if (playhead != null && TrimMath.showsPlayhead(playhead, trim, active != null)) {
            val x = with(density) { (handlePx + TrimMath.x(playhead, duration, track)).toDp() }
            Box(
                Modifier
                    .offset(x = x - 1.25.dp, y = 4.dp)
                    .size(width = 2.5.dp, height = stripHeight - 8.dp)
                    .dropShadow(CircleShape, Shadow(radius = 2.dp, color = Color.Black.copy(alpha = 0.45f)))
                    .clip(CircleShape)
                    .background(Color.White),
            )
        }

        TrimHandleView(TrimHandle.Start, startX - handle, active == TrimHandle.Start, trim, duration, onTrimChange, onSeek, onScrubEnd)
        TrimHandleView(TrimHandle.End, endX, active == TrimHandle.End, trim, duration, onTrimChange, onSeek, onScrubEnd)
    }
}

@Composable
private fun Dim(from: Dp, to: Dp, height: Dp) {
    Box(
        Modifier
            .offset(x = from)
            .size(width = (to - from).coerceAtLeast(0.dp), height = height)
            .background(Color.Black.copy(alpha = 0.55f)),
    )
}

/** A white 16 × 48 handle with a dark grip; swells sideways while held (`handle(_:at:)`, `:110-142`). */
@Composable
private fun TrimHandleView(
    which: TrimHandle,
    x: Dp,
    isActive: Boolean,
    trim: VideoTrim,
    duration: Double,
    onTrimChange: (VideoTrim) -> Unit,
    onSeek: (Double) -> Unit,
    onScrubEnd: () -> Unit,
) {
    val swell by animateFloatAsState(if (isActive) 1.2f else 1f, Motion.snappy(), label = "trimHandle")
    val label = if (which == TrimHandle.Start) "Trim start" else "Trim end"
    val value = TrimMath.seekTime(trim, which)
    Box(
        Modifier
            .offset(x = x)
            .size(width = TrimMath.HANDLE_WIDTH.dp, height = TrimMath.STRIP_HEIGHT.dp)
            .graphicsLayer { scaleX = swell }
            .clip(RoundedCornerShape(5.dp))
            .background(Color.White)
            .semantics {
                contentDescription = label
                stateDescription = ChatVideoPlayer.timeLabel(value)
                if (duration > 0) {
                    progressBarRangeInfo = ProgressBarRangeInfo(value.toFloat(), 0f..duration.toFloat())
                    // Swipe up / down steps the cut, so trimming doesn't depend on dragging (`:135-139`).
                    setProgress { target ->
                        val step = TrimMath.nudgeStep(duration)
                        val delta = if (target > value) step else -step
                        val nudged = TrimMath.nudge(trim, which, delta, duration) ?: return@setProgress false
                        onTrimChange(nudged)
                        onSeek(TrimMath.seekTime(nudged, which))
                        onScrubEnd()
                        true
                    }
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .width(2.dp)
                .height(16.dp)
                .clip(CircleShape)
                .background(Color.Black.copy(alpha = 0.35f)),
        )
    }
}
