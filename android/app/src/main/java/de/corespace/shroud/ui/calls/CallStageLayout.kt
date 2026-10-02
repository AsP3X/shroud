package de.corespace.shroud.ui.calls

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.unit.Constraints
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * The call screen's fixed sizes, in dp (iOS `InCallOverlay`, `InCallOverlay.swift:55-64, 387-388,
 * 472, 558-560`; calls §8.3–§8.7).
 */
object CallScreenMetrics {
    /** Our own picture in the top-trailing corner (`selfViewSize`, :56). */
    val selfView = Size(108f, 164f)

    /** Beside their shared screen the pictures are tiles, theirs above ours (`tileSize`, :58). */
    val tile = Size(90f, 136f)

    /** `selfViewInsets` (:61): top 12, trailing 16. */
    const val INSET_TOP = 12f
    const val INSET_TRAILING = 16f

    /** What the docked name block leaves free at the trailing edge: our picture, its inset and 12 (:64). */
    const val SELF_VIEW_RESERVE = INSET_TRAILING + 108f + 12f

    /** The Share control's circle (:472). */
    const val SHARE_CONTROL = 40f

    /** What the "Not verified" badge takes at the top of the corner (:560). */
    const val SAFETY_BADGE_RESERVE = SHARE_CONTROL + 10f

    /** The room the sharing pill takes at the top (:388). */
    const val SHARING_INSET = 40f

    /** The face (`AvatarView` 104, initials 36; :801-806). */
    const val FACE = 104f

    /** The control row's bottom padding and the gap above it (:187, :203). */
    const val CONTROLS_BOTTOM = 48f
    const val COLUMN_SPACING = 28f

    /**
     * The name block leaves the face only while their picture is on screen; our own camera does
     * not move it (`nameBelongsInCorner(remotePicture:)`, :68-70).
     */
    fun nameBelongsInCorner(remotePicture: Boolean): Boolean = remotePicture
}

/**
 * Where the face and the name block go (iOS `CallStageLayout`, `InCallOverlay.swift:935-1046`;
 * calls §8.3; tested by `CallStageLayoutTest`, the 15 vectors of `CallStageLayoutTests.swift`).
 *
 * The face sits in the middle of the stage (the space above the controls). Under it, the block
 * hangs [GAP] below; docked, it sits at [cornerX], [cornerY] (+ the badge's drop) and the face
 * stays in the middle, moving beside or below the block only when they would overlap. In between,
 * both origins move on one fraction: one straight glide. Both ends land on whole dp. Pure; dp in, dp out.
 */
object CallStageGeometry {
    /** Between the face and the block under it. */
    const val GAP = 28f

    /** The block's side margins under the face. */
    const val MARGIN = 24f

    /** The docked block's corner, from the stage's: level with our own picture. */
    const val CORNER_X = 20f
    const val CORNER_Y = CallScreenMetrics.INSET_TOP

    /** The block never grows wider than this (landscape, tablets): a notice wraps instead. */
    const val CORNER_MAX_WIDTH = 320f

    /** The face and the block in stage coordinates. */
    data class Frames(val face: Rect, val block: Rect)

    /**
     * One width for the block in both places, so a long name never re-wraps on the way: what the
     * corner leaves before our own picture, within the side margins, at most [CORNER_MAX_WIDTH]
     * (`blockWidth(stage:)`, :982-984).
     */
    fun blockWidth(stageWidth: Float): Float =
        max(0f, minOf(CORNER_MAX_WIDTH, stageWidth - CORNER_X - CallScreenMetrics.SELF_VIEW_RESERVE, stageWidth - MARGIN * 2))

    /** Where the face and the block are in [stage] at [progress] (0 under the face, 1 docked) (:988-1012). */
    fun frames(stage: Rect, face: Size, block: Size, progress: Float, cornerDrop: Float = 0f): Frames {
        val under = underFace(stage, face, block)
        val docked = inCorner(stage, face, block, cornerDrop)
        // A spring can run a little past either end; the ends hold while it settles.
        val p = min(1f, max(0f, progress))
        if (p == 0f) return under
        if (p == 1f) return docked
        val blockOrigin = Offset(
            under.block.left + (docked.block.left - under.block.left) * p,
            under.block.top + (docked.block.top - under.block.top) * p,
        )
        val faceOrigin = Offset(
            under.face.left + (docked.face.left - under.face.left) * p,
            under.face.top + (docked.face.top - under.face.top) * p,
        )
        return Frames(Rect(faceOrigin, face), Rect(blockOrigin, block))
    }

    /** The face in the middle; on a stage too short for both, it rises just enough for the block (:1017-1025). */
    private fun underFace(stage: Rect, face: Size, block: Size): Frames {
        val centred = stage.center.y - face.height / 2
        val fits = stage.bottom - (face.height + GAP + block.height)
        val top = max(stage.top, min(centred, fits))
        return Frames(
            whole(Offset(stage.center.x - face.width / 2, top), face),
            whole(Offset(stage.center.x - block.width / 2, top + face.height + GAP), block),
        )
    }

    /** Docked: the block in the corner; the face alone in the middle, beside or below the block if they meet (:1028-1041). */
    private fun inCorner(stage: Rect, face: Size, block: Size, drop: Float): Frames {
        val blockFrame = Rect(Offset(stage.left + CORNER_X, stage.top + CORNER_Y + drop), block)
        var faceX = stage.center.x - face.width / 2
        var faceY = stage.center.y - face.height / 2
        if (Rect(Offset(faceX, faceY), face).overlaps(blockFrame)) {
            if (blockFrame.right + GAP + face.width <= stage.right) {
                faceX = blockFrame.right + GAP
            } else {
                faceY = blockFrame.bottom + GAP
            }
        }
        return Frames(whole(Offset(faceX, faceY), face), whole(blockFrame.topLeft, block))
    }

    private fun whole(origin: Offset, size: Size): Rect = Rect(Offset(roundHalfAway(origin.x), roundHalfAway(origin.y)), size)

    /** Swift's `rounded()`: to the nearest, halves away from zero (Kotlin's `round` goes to even). */
    fun roundHalfAway(value: Float): Float = if (value >= 0f) floor(value + 0.5f) else -floor(-value + 0.5f)

    /**
     * The radius that covers the stage's farthest corner from [center], plus 2 (`fullRadius`,
     * `CallVideoView.swift:221-226`): the face reveal's open circle.
     */
    fun fullRadius(center: Offset, size: Size): Float {
        val dx = max(center.x, size.width - center.x)
        val dy = max(center.y, size.height - center.y)
        return sqrt(dx * dx + dy * dy) + 2f
    }

    /** A hair inside the face's edge, so no picture shows round it once it is back (`faceRadius`, :215-218). */
    fun faceRadius(faceWidth: Float): Float = max(1f, faceWidth / 2f * 0.96f)
}

/**
 * Places the face and the name block (`CallStageLayout: Layout`, :935-978): the face measured at
 * its own size, the block once at [CallStageGeometry.blockWidth] and placed at its measured size in
 * both positions, so the text never re-wraps while it moves. [progress] and [cornerDrop] are read
 * while placing only: an animation moves the two without recomposing or remeasuring them. The face
 * is placed last, on top: a long name passes behind it.
 */
@Composable
internal fun CallStageLayout(
    progress: () -> Float,
    cornerDrop: () -> Float,
    face: @Composable () -> Unit,
    block: @Composable () -> Unit,
    modifier: Modifier = Modifier,
) {
    Layout(contents = listOf(face, block), modifier = modifier) { (faceMeasurables, blockMeasurables), constraints ->
        // Fills what it is offered (`sizeThatFits` returns the proposal, :958-960).
        val width = if (constraints.hasBoundedWidth) constraints.maxWidth else constraints.minWidth
        val height = if (constraints.hasBoundedHeight) constraints.maxHeight else constraints.minHeight
        val scale = density
        val stage = Rect(0f, 0f, width / scale, height / scale)
        val blockWidthPx = (CallStageGeometry.blockWidth(stage.width) * scale).roundToInt()
        val facePlaceables = faceMeasurables.map { it.measure(Constraints()) }
        val blockPlaceables = blockMeasurables.map { it.measure(Constraints(maxWidth = blockWidthPx)) }
        val facePx = facePlaceables.firstOrNull()
        val blockPx = blockPlaceables.firstOrNull()
        layout(width, height) {
            val faceSize = Size((facePx?.width ?: 0) / scale, (facePx?.height ?: 0) / scale)
            val blockSize = Size((blockPx?.width ?: 0) / scale, (blockPx?.height ?: 0) / scale)
            val frames = CallStageGeometry.frames(stage, faceSize, blockSize, progress(), cornerDrop())
            blockPx?.place((frames.block.left * scale).roundToInt(), (frames.block.top * scale).roundToInt())
            facePx?.place((frames.face.left * scale).roundToInt(), (frames.face.top * scale).roundToInt(), zIndex = 1f)
        }
    }
}
