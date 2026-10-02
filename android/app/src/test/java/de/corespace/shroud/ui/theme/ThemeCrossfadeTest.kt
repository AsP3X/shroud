package de.corespace.shroud.ui.theme

import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import de.corespace.shroud.ui.components.ComposeHarness
import kotlinx.coroutines.channels.Channel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.Duration

/**
 * The window's theme switch (settings-lock §8.2; iOS `WindowColorTheme`,
 * `ColorThemePreference.swift:104-126`): the content switches at once, the old frame fades out over
 * it in 0.3 s, linear, and Reduce Motion keeps the fade (nothing moves).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w200dp-h200dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ThemeCrossfadeTest {
    @Test
    fun thePictureFadesLinearlyOver300Ms() {
        assertEquals(300L, ThemeCrossfadeMath.DURATION_MS)
        assertEquals(1f, ThemeCrossfadeMath.pictureAlpha(0L), 0f)
        assertEquals(0.5f, ThemeCrossfadeMath.pictureAlpha(150_000_000L), 1e-6f)
        assertEquals(0.25f, ThemeCrossfadeMath.pictureAlpha(225_000_000L), 1e-6f)
        assertEquals(0f, ThemeCrossfadeMath.pictureAlpha(300_000_000L), 0f)
        assertEquals(0f, ThemeCrossfadeMath.pictureAlpha(2_000_000_000L), 0f)
        assertEquals(1f, ThemeCrossfadeMath.pictureAlpha(-5L), 0f)
    }

    /**
     * Robolectric runs a frame loop to its end within one idle, so the fade's frames come from
     * [frames] here, one per [frame] call.
     */
    private val frames = Channel<Long>(Channel.UNLIMITED)

    private fun frame(ms: Long) {
        frames.trySend(ms * 1_000_000L)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(16))
    }

    private fun host(dark: () -> Boolean, applied: MutableList<Boolean> = ArrayList()) = ComposeHarness {
        // Reduce Motion on (the harness default): the fade still runs.
        ThemeCrossfade(dark(), Modifier.fillMaxSize(), nextFrameNanos = { frames.receive() }) { shown ->
            applied += shown
            Box(Modifier.fillMaxSize().background(if (shown) Color.Black else Color.White))
        }
    }

    @Test
    fun theContentSwitchesAndTheOldFrameFadesOutOverIt() {
        var dark by mutableStateOf(false)
        val applied = ArrayList<Boolean>()
        val ui = host({ dark }, applied)
        assertEquals("the first frame is in the chosen theme, no fade at launch", listOf(false), applied.distinct())
        assertEquals(0xFFFFFF, centre(ui))

        dark = true
        frame(0)
        assertEquals("the content now draws dark", true, applied.last())
        assertEquals("the old light frame lies over it, opaque", 0xFFFFFF, centre(ui))

        frame(150)
        val middle = centre(ui)
        assertTrue("half way it is grey: ${hex(middle)}", red(middle) in 0x70..0x90)

        frame(240)
        assertTrue("a fifth of the old frame is left: ${hex(centre(ui))}", red(centre(ui)) in 0x28..0x38)

        frame(300)
        assertEquals("after 0.3 s only the dark content is left", 0x000000, centre(ui))
        frame(400)
        assertEquals(0x000000, centre(ui))
    }

    @Test
    fun aSwitchBackMidFadeEndsOnTheLatestTheme() {
        var dark by mutableStateOf(false)
        val ui = host({ dark })
        centre(ui)
        dark = true
        frame(0)
        frame(100)
        assertTrue("a third through: ${hex(centre(ui))}", red(centre(ui)) in 0x98..0xB8)
        dark = false
        // The running fade is dropped; the second switch pictures the content as it is now (dark,
        // without the first picture) and waits for its first frame.
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(16))
        assertEquals("the dark frame now lies over the light content", 0x000000, centre(ui))
        frame(1000)
        frame(1150)
        assertTrue("half way back: ${hex(centre(ui))}", red(centre(ui)) in 0x70..0x90)
        frame(1300)
        assertEquals(0xFFFFFF, centre(ui))
    }

    @Test
    fun withoutAPictureTheThemeSwitchesAtOnce() {
        // Nothing drawn yet: there is no frame to fade from.
        var dark by mutableStateOf(false)
        val applied = ArrayList<Boolean>()
        host({ dark }, applied)
        dark = true
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(32))
        assertEquals(true, applied.last())
    }

    /** The root drawn now (Robolectric runs no traversal on its own), at its centre, as RGB. */
    private fun centre(ui: ComposeHarness): Int {
        val root = ui.root
        val bitmap = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
        root.draw(Canvas(bitmap))
        return bitmap.getPixel(root.width / 2, root.height / 2) and 0xFFFFFF
    }

    private fun red(rgb: Int) = rgb shr 16 and 0xFF

    private fun hex(rgb: Int) = "#%06X".format(rgb)
}
