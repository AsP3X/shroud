package de.corespace.shroud.core.links

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import de.corespace.shroud.core.net.wire.LinkPreview
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
import java.nio.ByteBuffer

/**
 * The preview's pictures from a page image (`LinkPreviewFetcher.prepareImages`,
 * `LinkPreviewFetcher.swift:360-414`; media-voice-links §10.5) on Robolectric's native graphics:
 * downsampled to 1024 on the long edge, a square thumbnail within the 6 KiB envelope budget, icons
 * and bombs refused, transparency flattened onto white. The same card case runs on a device
 * (`LinkPreviewImagesDeviceTest`) with the platform's own `ImageDecoder`.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class LinkPreviewImagesTest {
    private fun encode(bitmap: Bitmap, format: Bitmap.CompressFormat = Bitmap.CompressFormat.PNG, quality: Int = 100): ByteArray {
        val out = ByteArrayOutputStream()
        bitmap.compress(format, quality, out)
        return out.toByteArray()
    }

    /** `LinkPageMetadataParserTests.swift:173-181`: twelve hue bands, 1200 × 630. */
    private fun card(width: Int = 1200, height: Int = 630): Bitmap {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint()
        for (band in 0 until 12) {
            paint.color = Color.HSVToColor(floatArrayOf(band * 30f, 0.7f, 0.8f))
            canvas.drawRect(band * width / 12f, 0f, (band + 1) * width / 12f, height.toFloat(), paint)
        }
        return bitmap
    }

    @Test
    fun cardImageGivesALargeJpegAndASmallThumbnail() { // :173-187
        val prepared = assertNotNull(LinkPreviewImages.prepare(encode(card())))
        assertEquals("downsampled to the large layout's edge", 1024, prepared!!.width)
        assertEquals(1024.0 * 630 / 1200, prepared.height.toDouble(), 1.0)
        val large = assertNotNull(prepared.large)
        val thumbnail = assertNotNull(prepared.thumbnail)
        assertTrue(thumbnail!!.size <= LinkPreview.MAX_THUMBNAIL_BYTES)

        val largeBitmap = BitmapFactory.decodeByteArray(large, 0, large!!.size)
        assertEquals(prepared.width, largeBitmap.width)
        assertEquals(prepared.height, largeBitmap.height)
        val thumbBitmap = BitmapFactory.decodeByteArray(thumbnail, 0, thumbnail.size)
        assertEquals("a square", thumbBitmap.width, thumbBitmap.height)
        assertTrue(thumbBitmap.width in 96..160)
        assertTrue("JPEG", large[0] == 0xFF.toByte() && large[1] == 0xD8.toByte())
    }

    @Test
    fun smallImagesAreNotUpscaled() {
        val prepared = LinkPreviewImages.prepare(encode(card(400, 300)))!!
        assertEquals(400, prepared.width)
        assertEquals(300, prepared.height)
    }

    @Test
    fun iconsAreRefused() {
        assertNull(LinkPreviewImages.prepare(encode(card(64, 64))))
        // 4000 × 300 decodes to 1024 × 77: too thin once downsampled (iOS checks the decoded size).
        assertNull(LinkPreviewImages.prepare(encode(card(4000, 300))))
    }

    @Test
    fun aDeclaredBombIsRefusedWithoutDecoding() {
        // A real PNG header claiming 20 000 × 20 000; the body is far too short to hold that.
        val header = encode(card(100, 100))
        val bomb = header.copyOf()
        ByteBuffer.wrap(bomb).putInt(16, 20_000).putInt(20, 20_000)
        assertNull(LinkPreviewImages.prepare(bomb))
    }

    @Test
    fun garbageIsNoImage() {
        assertNull(LinkPreviewImages.prepare("<html>not an image</html>".toByteArray()))
        assertNull(LinkPreviewImages.prepare(ByteArray(0)))
    }

    @Test
    fun transparencyIsFlattenedOntoWhite() {
        val clear = Bitmap.createBitmap(200, 200, Bitmap.Config.ARGB_8888) // fully transparent
        val prepared = LinkPreviewImages.prepare(encode(clear))!!
        val large = BitmapFactory.decodeByteArray(prepared.large, 0, prepared.large!!.size)
        val pixel = large.getPixel(100, 100)
        assertTrue("white, not black: ${Integer.toHexString(pixel)}", Color.red(pixel) > 240 && Color.green(pixel) > 240 && Color.blue(pixel) > 240)
    }

    @Test
    fun jpegAndWebpSourcesWork() {
        assertNotNull(LinkPreviewImages.prepare(encode(card(), Bitmap.CompressFormat.JPEG, 90)))
        assertNotNull(LinkPreviewImages.prepare(encode(card(), Bitmap.CompressFormat.WEBP_LOSSY, 80)))
    }

    private fun <T> assertNotNull(value: T?): T? {
        org.junit.Assert.assertNotNull(value)
        return value
    }
}
