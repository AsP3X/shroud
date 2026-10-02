package de.corespace.shroud.core.media.edit

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import androidx.compose.ui.geometry.Rect
import androidx.test.core.app.ApplicationProvider
import de.corespace.shroud.AppContainer
import de.corespace.shroud.core.lifecycle.AppPhaseMonitor
import de.corespace.shroud.core.media.MediaComposeQuality
import de.corespace.shroud.core.media.MediaImageSource
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Crop, rotate, filter and drawing on small solid bitmaps (conversation-compose-media §10). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MediaEditRendererTest {
    private val renderer = BitmapMediaEditRenderer()

    @Test
    fun identityKeepsARedPixelAndTheSameBitmap() {
        val red = solid(8, 8, Color.RED)
        val out = renderer.render(red, MediaEdits.Identity)
        assertSame(red, out)
        assertEquals(Color.RED, out.getPixel(3, 3))
    }

    @Test
    fun monoTurnsSaturatedRedGreyAndAZeroIntensityDoesNot() {
        val red = solid(8, 8, Color.RED)
        val mono = renderer.render(red, MediaEdits(filter = MediaFilter.Mono))
        assertGrey(mono.getPixel(2, 2))
        assertTrue(Color.red(mono.getPixel(2, 2)) < 200)
        assertEquals(Color.RED, red.getPixel(0, 0))

        val skipped = renderer.render(red, MediaEdits(filter = MediaFilter.Mono, filterIntensity = 0f))
        assertSame(red, skipped)
        assertEquals(Color.RED, skipped.getPixel(1, 1))
    }

    @Test
    fun warmShiftsGreyTowardRed() {
        val grey = solid(6, 6, Color.rgb(128, 128, 128))
        val warm = renderer.render(grey, MediaEdits(filter = MediaFilter.Warm))
        val pixel = warm.getPixel(2, 2)
        assertTrue("red ${Color.red(pixel)} blue ${Color.blue(pixel)}", Color.red(pixel) > Color.blue(pixel))
    }

    @Test
    fun cropShrinksToTheRightHalf() {
        val image = Bitmap.createBitmap(40, 20, Bitmap.Config.ARGB_8888)
        fill(image, 0, 20, Color.RED)
        fill(image, 20, 40, Color.BLUE)
        val cropped = renderer.render(image, MediaEdits(cropRect = Rect(0.5f, 0f, 1f, 1f)))
        assertEquals(20, cropped.width)
        assertEquals(20, cropped.height)
        assertTrue(cropped.width < image.width)
        assertEquals(Color.BLUE, cropped.getPixel(4, 4))
    }

    @Test
    fun clockwiseQuarterTurnMovesTheLeftHalfToTheTop() {
        val image = Bitmap.createBitmap(40, 20, Bitmap.Config.ARGB_8888)
        fill(image, 0, 20, Color.RED)
        fill(image, 20, 40, Color.BLUE)
        val turned = renderer.render(image, MediaEdits(rotationQuarters = 1))
        assertEquals(20, turned.width)
        assertEquals(40, turned.height)
        assertEquals(Color.RED, turned.getPixel(10, 8))
        assertEquals(Color.BLUE, turned.getPixel(10, 32))
        assertEquals(Color.RED, image.getPixel(4, 4))
    }

    @Test
    fun mirrorSwapsTheHalves() {
        val image = Bitmap.createBitmap(40, 20, Bitmap.Config.ARGB_8888)
        fill(image, 0, 20, Color.RED)
        fill(image, 20, 40, Color.BLUE)
        val flipped = renderer.render(image, MediaEdits(mirrored = true))
        assertEquals(40, flipped.width)
        assertEquals(20, flipped.height)
        assertEquals(Color.BLUE, flipped.getPixel(8, 10))
        assertEquals(Color.RED, flipped.getPixel(32, 10))
    }

    @Test
    fun drawingPaintsOverThePhoto() {
        val white = solid(12, 12, Color.WHITE)
        val drawing = object : DrawingData {
            override val canvasWidth = 12f
            override val canvasHeight = 12f
            override fun draw(canvas: Canvas, outWidth: Int, outHeight: Int) {
                canvas.drawRect(0f, 0f, outWidth.toFloat(), outHeight.toFloat(), Paint().apply { color = Color.GREEN })
            }
        }
        val marked = renderer.render(white, MediaEdits(drawing = drawing))
        assertEquals(Color.GREEN, marked.getPixel(6, 6))
        assertEquals(Color.WHITE, white.getPixel(6, 6))
    }

    @Test
    fun textChangesAPixel() {
        val white = solid(80, 80, Color.WHITE)
        val edited = renderer.render(
            white,
            MediaEdits(texts = listOf(TextOverlay(string = "Hi", colorIndex = 1, relativeFontSize = 0.4f))),
        )
        assertTrue(changed(white, edited))
    }

    @Test
    fun previewUsesTheSameMonoRecipeAndCapsTheEdge() = runBlocking {
        val red = solid(100, 40, Color.RED)
        val fitted = renderer.preview(red, MediaEdits.Identity, 10)
        assertEquals(10, fitted.width)
        assertEquals(4, fitted.height)
        assertEquals(Color.RED, fitted.getPixel(1, 1))

        val mono = renderer.preview(solid(30, 30, Color.RED), MediaEdits(filter = MediaFilter.Mono), 30)
        assertGrey(mono.getPixel(8, 8))
    }

    @Test
    fun filterRecipesFollowTheEnum() {
        assertEquals(
            MediaFilter.entries.map { it.name to it.label },
            renderer.filters.map { it.id to it.title },
        )
        assertEquals("Original", renderer.filters.first().title)
        assertEquals("Mono", renderer.filters.first { it.id == "Mono" }.title)
    }

    @Test
    fun processStartRegistersTheRendererTheEncoderUses() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val container = AppContainer(app, AppPhaseMonitor(tracks = { false }))
        container.images.onProcessStart()
        val encoded = container.images.imageEncoder.encode(
            MediaImageSource.Decoded(solid(16, 16, Color.RED)),
            MediaComposeQuality.Original,
            MediaEdits(filter = MediaFilter.Mono),
        )
        val jpeg = encoded.data.toByteArray()
        val decoded = android.graphics.BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size)
        assertGrey(decoded.getPixel(decoded.width / 2, decoded.height / 2))
    }

    private fun assertGrey(pixel: Int) {
        val red = Color.red(pixel)
        val green = Color.green(pixel)
        val blue = Color.blue(pixel)
        assertEquals(red, green)
        assertEquals(green, blue)
        assertNotEquals(Color.RED, pixel)
    }

    private fun changed(before: Bitmap, after: Bitmap): Boolean {
        for (y in 0 until before.height) {
            for (x in 0 until before.width) {
                if (before.getPixel(x, y) != after.getPixel(x, y)) return true
            }
        }
        return false
    }

    private fun solid(width: Int, height: Int, color: Int) =
        Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply { eraseColor(color) }

    private fun fill(image: Bitmap, fromX: Int, toX: Int, color: Int) {
        for (x in fromX until toX) for (y in 0 until image.height) image.setPixel(x, y, color)
    }
}
