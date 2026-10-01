package de.corespace.shroud.ui.components

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.graphics.Typeface
import androidx.compose.ui.graphics.toArgb
import androidx.core.content.res.ResourcesCompat
import androidx.core.graphics.createBitmap
import de.corespace.shroud.R
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * The gradient initials avatar as a bitmap, off Compose, for a notification's `setLargeIcon`
 * (notifications-push §6.1; design shade: 40 dp gradient avatar, initials 15 SemiBold white). Same
 * palette and seed rule as [Avatar] (`AvatarView.swift:25-65`); the caller passes
 * [AvatarPalette.initials] and [AvatarPalette.seed] so a contact looks the same in the shade and
 * in the app.
 */
object AvatarBitmap {
    /**
     * Draws [initials] in white Inter SemiBold on [seed]'s top-to-bottom gradient, in a circle
     * [sizeDp] across (transparent corners), at the display's density.
     */
    fun render(context: Context, seed: String, initials: String, sizeDp: Int = 40): Bitmap {
        val metrics = layout(sizeDp, context.resources.displayMetrics.density)
        val px = metrics.sizePx
        val bitmap = createBitmap(px, px)
        val canvas = Canvas(bitmap)
        val (top, bottom) = AvatarPalette.colors(seed)
        val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = LinearGradient(0f, 0f, 0f, px.toFloat(), top.toArgb(), bottom.toArgb(), Shader.TileMode.CLAMP)
        }
        val radius = px / 2f
        canvas.drawCircle(radius, radius, radius, fill)

        val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.WHITE
            textSize = metrics.textSizePx
            textAlign = Paint.Align.CENTER
            typeface = interSemiBold(context)
        }
        // Centre the line box, as Compose centres the initials in [Avatar].
        val baseline = radius - (text.ascent() + text.descent()) / 2f
        canvas.drawText(initials, radius, baseline, text)
        return bitmap
    }

    /** Pixel size of the bitmap and of its initials for [sizeDp] at [density]. */
    internal fun layout(sizeDp: Int, density: Float): Metrics {
        val sizePx = max(1, (sizeDp * density).roundToInt())
        return Metrics(sizePx, initialsSizeDp(sizeDp) * density)
    }

    /** The design's 15 at 40 dp (shade, New Chat rows), kept in proportion at other sizes. */
    internal fun initialsSizeDp(sizeDp: Int): Float = sizeDp * 15f / 40f

    internal data class Metrics(val sizePx: Int, val textSizePx: Float)

    private fun interSemiBold(context: Context): Typeface =
        runCatching { ResourcesCompat.getFont(context, R.font.inter_semibold) }.getOrNull()
            ?: Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
}
