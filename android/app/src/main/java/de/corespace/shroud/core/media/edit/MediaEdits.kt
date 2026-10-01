package de.corespace.shroud.core.media.edit

import android.graphics.Canvas
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import java.util.UUID

// Photo edits as data (conversation-compose-media §10.1, `MediaEdits.swift`). Published by W1-INT;
// W3-MEDIA-EDIT renders them (MediaEditRenderer) and draws the editors.

/** Filters in strip order (`MediaEdits.swift:8-24`). */
enum class MediaFilter(val label: String) {
    None("Original"),
    Vivid("Vivid"),
    Warm("Warm"),
    Cool("Cool"),
    Fade("Fade"),
    Dramatic("Dramatic"),
    Mono("Mono"),
    Noir("Noir"),
}

/** A text label placed on the photo (`MediaEdits.swift:83-171`). */
data class TextOverlay(
    val id: UUID = UUID.randomUUID(),
    val string: String = "",
    /** Index into [PALETTE]. */
    val colorIndex: Int = 0,
    val style: Style = Style.Plain,
    /** Normalised image coordinates, 0…1. */
    val center: Offset = Offset(0.5f, 0.5f),
    /** Fraction of the image width. */
    val relativeFontSize: Float = 0.09f,
    /** Radians. */
    val rotation: Float = 0f,
    val scale: Float = 1f,
) {
    enum class Style {
        Plain,
        Filled,
        Outlined,
        ;

        /** The style button cycles Plain → Filled → Outlined. */
        val next: Style get() = entries[(ordinal + 1) % entries.size]
    }

    val color: Color get() = PALETTE[Math.floorMod(colorIndex, PALETTE.size)]

    /** Readable contrast for Filled slabs and Outlined strokes (`MediaEdits.swift:165-170`). */
    val contrastColor: Color get() = CONTRAST[Math.floorMod(colorIndex, CONTRAST.size)]

    /** Never prints the text. */
    override fun toString(): String = "TextOverlay(id=$id, style=$style, chars=${string.length})"

    companion object {
        val PALETTE: List<Color> = listOf(
            Color.White,
            Color.Black,
            Color(0.98f, 0.24f, 0.31f),
            Color(0.99f, 0.70f, 0.16f),
            Color(0.28f, 0.80f, 0.45f),
            Color(0.20f, 0.56f, 0.93f),
            Color(0.66f, 0.35f, 0.95f),
        )
        val PALETTE_NAMES: List<String> = listOf("White", "Black", "Red", "Orange", "Green", "Blue", "Purple")
        val CONTRAST: List<Color> = listOf(Color.Black, Color.White, Color.White, Color.Black, Color.Black, Color.White, Color.White)
    }
}

/** Engine-neutral strokes (Jetpack Ink or hand-rolled paths, W3-MEDIA-EDIT): drawn scaled to any output size. */
interface DrawingData {
    val canvasWidth: Float
    val canvasHeight: Float
    fun draw(canvas: Canvas, outWidth: Int, outHeight: Int)
}

/** Everything the editors changed (`MediaEdits.swift:178-206`). */
data class MediaEdits(
    /** Normalised, in the rotated image. */
    val cropRect: Rect = UNIT,
    /** Clockwise 90° steps. */
    val rotationQuarters: Int = 0,
    val mirrored: Boolean = false,
    val filter: MediaFilter = MediaFilter.None,
    /** 0…1; does not count for [isIdentity]. */
    val filterIntensity: Float = 1f,
    val drawing: DrawingData? = null,
    val texts: List<TextOverlay> = emptyList(),
) {
    val isIdentity: Boolean
        get() = cropRect == UNIT && rotationQuarters == 0 && !mirrored && filter == MediaFilter.None && drawing == null && texts.isEmpty()

    val hasCrop: Boolean get() = cropRect != UNIT || rotationQuarters != 0 || mirrored

    val hasDrawing: Boolean get() = drawing != null

    companion object {
        val UNIT = Rect(0f, 0f, 1f, 1f)
        val Identity = MediaEdits()
    }
}
