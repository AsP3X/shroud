package de.corespace.shroud.ui.components

import android.graphics.Bitmap
import android.os.Build
import android.os.ParcelFileDescriptor
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import de.corespace.shroud.ui.theme.ShroudTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Device acceptance of W1-UI-THEME (00-plan §2.2): glass blurs on API 31+ and uses the design's
 * *Glass — Without Blur* fill on API 30; Reduce Motion reacts live; the phrase badge pulses only on
 * false → true (settings-lock addendum EncryptionPhraseCard E1).
 *
 * The glass captures are kept for the acceptance record in the app's cache:
 * `adb exec-out run-as de.corespace.shroud cat cache/acceptance/glass-api<N>.png > glass-api<N>.png`.
 */
@RunWith(AndroidJUnit4::class)
class ThemeKitDeviceTest {
    @get:Rule
    val compose = createComposeRule()

    /** Luminance (0–255) of a row through the middle of [image], from 30 % to 70 % of its width. */
    private fun middleRow(image: ImageBitmap): List<Float> {
        val pixels = image.toPixelMap()
        val y = image.height / 2
        return ((image.width * 3 / 10) until (image.width * 7 / 10)).map { x ->
            val c = pixels[x, y]
            (0.2126f * c.red + 0.7152f * c.green + 0.0722f * c.blue) * 255f
        }
    }

    private fun keep(image: ImageBitmap, name: String) {
        val dir = File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir, "acceptance").apply { mkdirs() }
        File(dir, name).outputStream().use { image.asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test
    fun glassBlursFrom31AndFallsBackToTheOpaqueFillOn30() {
        compose.setContent {
            ShroudTheme(dark = false) {
                val backdrop = rememberGlassBackdrop()
                CompositionLocalProvider(LocalGlassBackdrop provides backdrop) {
                    Box(Modifier.size(240.dp)) {
                        // Black and white 4 dp stripes: text-like detail a blur must wash out.
                        Box(
                            Modifier
                                .fillMaxSize()
                                .glassBackdropSource()
                                .drawBehind {
                                    drawRect(Color.White)
                                    val stripe = 4.dp.toPx()
                                    var x = 0f
                                    while (x < size.width) {
                                        drawRect(Color.Black, Offset(x, 0f), Size(stripe, size.height))
                                        x += stripe * 2
                                    }
                                },
                        )
                        Box(
                            Modifier
                                .align(Alignment.Center)
                                .size(140.dp)
                                .testTag("glass")
                                .glassSurface(RoundedCornerShape(28.dp), GlassStyle.Regular),
                        )
                    }
                }
            }
        }
        val image = compose.onNodeWithTag("glass").captureToImage()
        keep(image, "glass-api${Build.VERSION.SDK_INT}.png")
        val row = middleRow(image)
        val range = row.max() - row.min()
        val mean = row.average().toFloat()
        // Unblurred translucent glass (#FFFFFFB8) over the stripes would swing ≈ 71 levels.
        assertTrue("stripes show through the glass: range $range", range < 30f)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            // Blurred grey under the 72 % white fill ≈ 220, darker than the opaque fallback.
            assertTrue("expected a blurred backdrop, mean $mean", mean < 238f)
        } else {
            // `glassOpaque` #FFFFFFF5 over the stripes: 245–255.
            assertTrue("expected the no-blur fill, mean $mean", mean > 240f)
        }
    }

    @Test
    fun reduceMotionReactsLive() {
        val original = shell("settings get global animator_duration_scale").trim()
        var reduce: Boolean? = null
        compose.setContent {
            ShroudTheme { reduce = ShroudTheme.reduceMotion }
        }
        try {
            shell("settings put global animator_duration_scale 0")
            compose.waitUntil(5_000) { reduce == true }
            shell("settings put global animator_duration_scale 1")
            compose.waitUntil(5_000) { reduce == false }
        } finally {
            if (original.isEmpty() || original == "null") {
                shell("settings delete global animator_duration_scale")
            } else {
                shell("settings put global animator_duration_scale $original")
            }
        }
    }

    @Test
    fun phraseBadgePulsesOnlyWhenItsWordArrives() {
        compose.mainClock.autoAdvance = false
        var revealed by mutableStateOf(true)
        compose.setContent {
            ShroudTheme(dark = false) { PhraseWordNumberBadge(1, revealed, Modifier.testTag("badge")) }
        }
        // Red channel of the fill left of the number: accentSoft #ECECFC (236) vs accent #5E5CE6 (94).
        fun fillRed(): Float {
            val image = compose.onNodeWithTag("badge").captureToImage()
            val pixels = image.toPixelMap()
            return pixels[image.width * 3 / 20, image.height / 2].red * 255f
        }
        compose.mainClock.advanceTimeBy(150)
        assertTrue("a badge composed revealed must not flash", fillRed() > 220f)

        revealed = false
        compose.mainClock.advanceTimeBy(500)
        revealed = true
        compose.mainClock.advanceTimeBy(200)
        assertTrue("false → true pulses accent", fillRed() < 160f)

        compose.mainClock.advanceTimeBy(1_000)
        assertTrue("the pulse settles back", fillRed() > 220f)
    }

    private fun shell(command: String): String {
        val descriptor: ParcelFileDescriptor = InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command)
        return ParcelFileDescriptor.AutoCloseInputStream(descriptor).bufferedReader().use { it.readText() }
    }
}
