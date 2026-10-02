package de.corespace.shroud.ui.components

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import de.corespace.shroud.ShroudApplication
import de.corespace.shroud.core.appearance.BrandLogoStyle
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The two looks of the drawn brand mark (settings-lock §8.3; `BrandLogoMark.swift:114-152`) and
 * that a mark without a pinned style follows the launcher (`BrandLogoPreference`).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class BrandLogoMarkTest {
    /** 100 dp marks at density 1 in Robolectric's default display (mdpi): 1 dp = 1 px. */
    private fun render(ui: ComposeHarness): Bitmap {
        val root = ui.root
        val bitmap = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
        root.draw(Canvas(bitmap))
        return bitmap
    }

    private fun luminance(color: Int) = (Color.red(color) * 299 + Color.green(color) * 587 + Color.blue(color) * 114) / 1000

    /** A point of the 1024-unit icon in a [size]-px mark placed at [left]. */
    private fun at(bitmap: Bitmap, left: Int, size: Int, u: Float, v: Float): Int =
        bitmap.getPixel(left + (u / 1024f * size).toInt(), (v / 1024f * size).toInt())

    @Test
    fun detailedHasGlowShadowAndShadingSimpleIsFlat() {
        val ui = ComposeHarness {
            Row {
                BrandLogoMark(100.dp, style = BrandLogoStyle.Detailed)
                BrandLogoMark(100.dp, Modifier.padding(start = 20.dp), style = BrandLogoStyle.Simple)
            }
        }
        val bitmap = render(ui)
        val density = ui.activity.resources.displayMetrics.density
        val size = (100 * density).toInt()
        val simpleLeft = (120 * density).toInt()
        // The simple veil is flat white; the detailed cloth shades towards #E4E3FF low down.
        assertEquals(Color.WHITE, at(bitmap, simpleLeft, size, 512f, 500f))
        val cloth = at(bitmap, 0, size, 330f, 760f)
        assertTrue("cloth #%08X".format(cloth), Color.blue(cloth) > Color.red(cloth))
        // The glow lifts the detailed backdrop above the veil.
        assertTrue(luminance(at(bitmap, 0, size, 512f, 120f)) > luminance(at(bitmap, simpleLeft, size, 512f, 120f)) + 8)
        // Only the detailed veil casts a shadow under its hem.
        assertTrue(luminance(at(bitmap, 0, size, 330f, 815f)) + 6 < luminance(at(bitmap, simpleLeft, size, 330f, 815f)))
    }

    @Test
    fun anUnpinnedMarkFollowsTheLauncherStyle() {
        val container = (RuntimeEnvironment.getApplication() as ShroudApplication).container
        val ui = ComposeHarness {
            Row {
                BrandLogoMark(100.dp)
                Box(Modifier.padding(start = 20.dp)) { BrandLogoMark(100.dp, style = BrandLogoStyle.Simple) }
            }
        }
        val density = ui.activity.resources.displayMetrics.density
        val size = (100 * density).toInt()
        val simpleLeft = (120 * density).toInt()
        fun matchesSimple(bitmap: Bitmap) = (0 until 1024 step 64).all { u ->
            at(bitmap, 0, size, u.toFloat(), 815f) == at(bitmap, simpleLeft, size, u.toFloat(), 815f) &&
                at(bitmap, 0, size, u.toFloat(), 120f) == at(bitmap, simpleLeft, size, u.toFloat(), 120f)
        }
        assertTrue("detailed at first", !matchesSimple(render(ui)))
        runBlocking { container.auth.brandLogo.choose(BrandLogoStyle.Simple) }
        ui.idle()
        assertTrue("follows the launcher", matchesSimple(render(ui)))
    }
}
