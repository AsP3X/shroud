package de.corespace.shroud.core.media

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.ByteArrayOutputStream
import kotlin.random.Random

/**
 * `EnvelopePreview.chatPreviewJpeg` on real pixels (Robolectric native graphics: `ImageDecoder`
 * and the JPEG encoder are the platform's): the result is a JPEG of at most 6 KiB whose longest
 * edge is at most 160 px, the picture keeps its aspect, and bytes that are not an image give no
 * preview (`MediaCrypto.swift:151-171`; media-voice-links §4.4).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class EnvelopePreviewBitmapTest {
    @Test
    fun aLargePhotoBecomesASmallJpeg() {
        val photo = jpeg(noise(1600, 1200), quality = 92)
        val preview = EnvelopePreview.chatPreviewJpeg(photo)
        assertNotNull(preview)
        assertTrue("≤ 6 KiB, got ${preview!!.size}", preview.size <= MediaEnvelopeBudget.MAX_THUMB)
        val bounds = bounds(preview)
        assertEquals("image/jpeg", bounds.outMimeType)
        assertTrue("longest edge ≤ 160, got ${bounds.outWidth}x${bounds.outHeight}", maxOf(bounds.outWidth, bounds.outHeight) <= 160)
        // 4:3 stays 4:3 (within a pixel of rounding).
        val ratio = bounds.outWidth.toDouble() / bounds.outHeight
        assertEquals(4.0 / 3.0, ratio, 0.03)
    }

    @Test
    fun aPortraitPictureKeepsItsOrientation() {
        val preview = EnvelopePreview.chatPreviewJpeg(jpeg(gradient(600, 1200), quality = 90))!!
        val bounds = bounds(preview)
        assertTrue(bounds.outHeight > bounds.outWidth)
        assertTrue(bounds.outHeight <= 160)
    }

    @Test
    fun aSmallPictureIsNotScaledUp() {
        val preview = EnvelopePreview.chatPreviewJpeg(jpeg(gradient(64, 48), quality = 90))!!
        val bounds = bounds(preview)
        assertEquals(64, bounds.outWidth)
        assertEquals(48, bounds.outHeight)
    }

    @Test
    fun aPngIsReencodedAsJpeg() {
        val out = ByteArrayOutputStream()
        gradient(400, 300).compress(Bitmap.CompressFormat.PNG, 100, out)
        val preview = EnvelopePreview.chatPreviewJpeg(out.toByteArray())!!
        assertEquals("image/jpeg", bounds(preview).outMimeType)
    }

    @Test
    fun bytesThatAreNotAnImageGiveNoPreview() {
        assertNull(EnvelopePreview.chatPreviewJpeg(ByteArray(0)))
        assertNull(EnvelopePreview.chatPreviewJpeg(Random(7).nextBytes(4096)))
    }

    private fun bounds(jpeg: ByteArray) = BitmapFactory.Options().apply { inJustDecodeBounds = true }.also {
        BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, it)
    }

    private fun jpeg(bitmap: Bitmap, quality: Int): ByteArray {
        val out = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, quality, out)
        bitmap.recycle()
        return out.toByteArray()
    }

    /** Random pixels: the worst case for JPEG size, so the ladder has to work. */
    private fun noise(width: Int, height: Int): Bitmap {
        val random = Random(42)
        val pixels = IntArray(width * height) { 0xFF000000.toInt() or random.nextInt(0x1000000) }
        return Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
    }

    private fun gradient(width: Int, height: Int): Bitmap {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint()
        for (y in 0 until height step 8) {
            paint.color = Color.rgb(y * 255 / height, 128, 255 - y * 255 / height)
            canvas.drawRect(0f, y.toFloat(), width.toFloat(), (y + 8).toFloat(), paint)
        }
        return bitmap
    }
}
