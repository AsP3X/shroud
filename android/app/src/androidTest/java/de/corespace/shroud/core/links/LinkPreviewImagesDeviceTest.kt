package de.corespace.shroud.core.links

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import androidx.test.ext.junit.runners.AndroidJUnit4
import de.corespace.shroud.core.net.wire.LinkPreview
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream

/**
 * `testCardImageGivesALargeJPEGAndASmallThumbnail` (`LinkPageMetadataParserTests.swift:173-187`)
 * with the platform's own `ImageDecoder` and JPEG encoder (media-voice-links §10.5).
 *
 * `./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=de.corespace.shroud.core.links.LinkPreviewImagesDeviceTest`
 */
@RunWith(AndroidJUnit4::class)
class LinkPreviewImagesDeviceTest {
    private fun png(width: Int, height: Int): ByteArray {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint()
        for (band in 0 until 12) {
            paint.color = Color.HSVToColor(floatArrayOf(band * 30f, 0.7f, 0.8f))
            canvas.drawRect(band * width / 12f, 0f, (band + 1) * width / 12f, height.toFloat(), paint)
        }
        val out = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
        return out.toByteArray()
    }

    @Test
    fun cardImageGivesALargeJpegAndASmallThumbnail() {
        val prepared = LinkPreviewImages.prepare(png(1200, 630))
        assertNotNull(prepared)
        assertEquals(1024, prepared!!.width)
        assertEquals(1024.0 * 630 / 1200, prepared.height.toDouble(), 1.0)
        val large = prepared.large!!
        val decoded = BitmapFactory.decodeByteArray(large, 0, large.size)
        assertEquals(prepared.width, decoded.width)
        assertTrue(prepared.thumbnail!!.size <= LinkPreview.MAX_THUMBNAIL_BYTES)
    }

    @Test
    fun iconsAreRefused() {
        assertNull(LinkPreviewImages.prepare(png(64, 64)))
    }
}
