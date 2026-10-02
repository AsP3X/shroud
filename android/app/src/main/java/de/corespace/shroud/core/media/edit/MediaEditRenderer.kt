package de.corespace.shroud.core.media.edit

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorMatrixColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.toArgb
import de.corespace.shroud.core.media.MediaEditBaker
import de.corespace.shroud.core.media.MediaImages
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * One filter chip: [id] is [MediaFilter.name], [title] is the strip label (`MediaFilter.label`).
 */
data class FilterRecipe(val id: String, val title: String)

/**
 * Bakes [MediaEdits] into pixels (conversation-compose-media §10, `MediaEdits.swift` `MediaEditRenderer`).
 * [render] is the full-resolution send path [de.corespace.shroud.core.media.ImageEncoder] calls through
 * [MediaEditBaker]. Identity edits are not modified: the same bitmap is returned, and the encoder
 * does not call [render] for them at all. [preview] runs the same steps on a bitmap scaled to [maxEdge].
 *
 * Order: rotate/mirror, crop, filter, drawing, text. Bitmaps from the decoder are already upright.
 * Text uses the platform sans-serif bold face — Inter is not bundled.
 */
interface MediaEditRenderer : MediaEditBaker {
    override fun render(image: Bitmap, edits: MediaEdits): Bitmap
    suspend fun preview(source: Bitmap, edits: MediaEdits, maxEdge: Int): Bitmap
    val filters: List<FilterRecipe>
}

internal class BitmapMediaEditRenderer : MediaEditRenderer {
    override val filters: List<FilterRecipe> = MediaFilter.entries.map { FilterRecipe(it.name, it.label) }

    override fun render(image: Bitmap, edits: MediaEdits): Bitmap {
        if (edits.isIdentity) return image
        val drop = ArrayList<Bitmap>(4)
        var current = image
        fun advance(next: Bitmap) {
            if (current !== image && current !== next) drop += current
            current = next
        }
        advance(rotated(current, edits.rotationQuarters, edits.mirrored))
        advance(cropped(current, edits.cropRect))
        advance(filtered(current, edits.filter, edits.filterIntensity))
        advance(annotated(current, edits))
        for (bitmap in drop) if (!bitmap.isRecycled) bitmap.recycle()
        return current
    }

    override suspend fun preview(source: Bitmap, edits: MediaEdits, maxEdge: Int): Bitmap = withContext(Dispatchers.Default) {
        val scaled = MediaImages.scaledToFit(source, maxEdge)
        val rendered = render(scaled, edits)
        if (scaled !== source && scaled !== rendered && !scaled.isRecycled) scaled.recycle()
        rendered
    }

    /**
     * Centre the source, mirror, then rotate on a y-down canvas, then move to the target centre.
     * [Matrix.postConcat] applies the new step after the ones already recorded, so this order is
     * the order the pixels move (iOS mirrors, then rotates). A positive quarter-turn sends the
     * left edge to the top.
     */
    private fun rotated(image: Bitmap, quarters: Int, mirrored: Boolean): Bitmap {
        val steps = Math.floorMod(quarters, 4)
        if (steps == 0 && !mirrored) return image
        val width = image.width
        val height = image.height
        val swap = steps % 2 == 1
        val targetWidth = if (swap) height else width
        val targetHeight = if (swap) width else height
        val matrix = Matrix()
        matrix.postTranslate(-width / 2f, -height / 2f)
        if (mirrored) matrix.postScale(-1f, 1f)
        if (steps != 0) matrix.postRotate(90f * steps)
        matrix.postTranslate(targetWidth / 2f, targetHeight / 2f)
        val out = Bitmap.createBitmap(targetWidth, targetHeight, Bitmap.Config.ARGB_8888)
        Canvas(out).drawBitmap(image, matrix, Paint(Paint.FILTER_BITMAP_FLAG))
        return out
    }

    /** Normalised crop of the already-rotated image; [MediaEdits.UNIT] and an empty intersection stay put. */
    private fun cropped(image: Bitmap, rect: Rect): Bitmap {
        if (rect == MediaEdits.UNIT) return image
        val width = image.width.toFloat()
        val height = image.height.toFloat()
        var x = (rect.left * width).roundToInt()
        var y = (rect.top * height).roundToInt()
        var cropWidth = max(1, (rect.width * width).roundToInt())
        var cropHeight = max(1, (rect.height * height).roundToInt())
        if (x < 0) {
            cropWidth += x
            x = 0
        }
        if (y < 0) {
            cropHeight += y
            y = 0
        }
        if (x + cropWidth > image.width) cropWidth = image.width - x
        if (y + cropHeight > image.height) cropHeight = image.height - y
        if (cropWidth <= 0 || cropHeight <= 0) return image
        if (x == 0 && y == 0 && cropWidth == image.width && cropHeight == image.height) return image
        return Bitmap.createBitmap(image, x, y, cropWidth, cropHeight)
    }

    private fun filtered(image: Bitmap, filter: MediaFilter, intensity: Float): Bitmap {
        val matrix = FilterRecipes.matrix(filter, intensity) ?: return image
        val out = Bitmap.createBitmap(image.width, image.height, Bitmap.Config.ARGB_8888)
        val paint = Paint(Paint.FILTER_BITMAP_FLAG).apply { colorFilter = ColorMatrixColorFilter(matrix) }
        Canvas(out).drawBitmap(image, 0f, 0f, paint)
        return out
    }

    /** A fresh bitmap, so a caller's source (a preview still held on screen) is never drawn into. */
    private fun annotated(image: Bitmap, edits: MediaEdits): Bitmap {
        val texts = edits.texts.filter { it.string.isNotEmpty() }
        if (edits.drawing == null && texts.isEmpty()) return image
        val out = Bitmap.createBitmap(image.width, image.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        canvas.drawBitmap(image, 0f, 0f, null)
        edits.drawing?.draw(canvas, out.width, out.height)
        for (text in texts) drawText(canvas, text, out.width, out.height)
        return out
    }

    private fun drawText(canvas: Canvas, overlay: TextOverlay, width: Int, height: Int) {
        val pointSize = max(8f, overlay.relativeFontSize * width * overlay.scale)
        val filled = overlay.style == TextOverlay.Style.Filled
        val hPad = if (filled) pointSize * 0.28f else 0f
        val vPad = if (filled) pointSize * 0.18f else 0f
        val wrap = max(1, (width - 2f * hPad).roundToInt())
        val color = overlay.color.toArgb()
        val contrast = overlay.contrastColor.toArgb()
        val textColor = if (filled) contrast else color
        val layout = hug(overlay.string, textPaint(pointSize, textColor, shadow = overlay.style == TextOverlay.Style.Plain), wrap)

        canvas.save()
        canvas.translate(overlay.center.x * width, overlay.center.y * height)
        canvas.rotate(Math.toDegrees(overlay.rotation.toDouble()).toFloat())
        if (filled) {
            val box = RectF(
                -layout.width / 2f - hPad,
                -layout.height / 2f - vPad,
                layout.width / 2f + hPad,
                layout.height / 2f + vPad,
            )
            canvas.drawRoundRect(box, pointSize * 0.26f, pointSize * 0.26f, Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color })
        }
        if (overlay.style == TextOverlay.Style.Outlined) {
            val stroke = hug(overlay.string, textPaint(pointSize, contrast, stroke = true), wrap)
            canvas.save()
            canvas.translate(-stroke.width / 2f, -stroke.height / 2f)
            stroke.draw(canvas)
            canvas.restore()
        }
        canvas.save()
        canvas.translate(-layout.width / 2f, -layout.height / 2f)
        layout.draw(canvas)
        canvas.restore()
        canvas.restore()
    }

    private fun textPaint(pointSize: Float, color: Int, shadow: Boolean = false, stroke: Boolean = false) =
        TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = pointSize
            typeface = SANS_BOLD
            this.color = color
            if (shadow) setShadowLayer(pointSize * 0.12f, 0f, pointSize * 0.03f, SHADOW)
            if (stroke) {
                style = Paint.Style.FILL_AND_STROKE
                strokeWidth = pointSize * 0.06f
                strokeJoin = Paint.Join.ROUND
            }
        }

    /** Wrap at [wrap], then again at the widest line so the slab hugs the text. */
    private fun hug(text: String, paint: TextPaint, wrap: Int): StaticLayout {
        val first = layout(text, paint, wrap)
        var widest = 0f
        for (line in 0 until first.lineCount) widest = max(widest, first.getLineWidth(line))
        val tight = max(1, ceil(widest.toDouble()).toInt())
        return if (tight < wrap) layout(text, paint, tight) else first
    }

    private fun layout(text: String, paint: TextPaint, width: Int): StaticLayout =
        StaticLayout.Builder.obtain(text, 0, text.length, paint, width)
            .setAlignment(Layout.Alignment.ALIGN_NORMAL)
            .setIncludePad(true)
            .build()

    private companion object {
        val SANS_BOLD: Typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        val SHADOW = android.graphics.Color.argb(115, 0, 0, 0)
    }
}
