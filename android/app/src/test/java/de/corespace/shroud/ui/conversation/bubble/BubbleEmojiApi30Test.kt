package de.corespace.shroud.ui.conversation.bubble

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.Looper
import android.text.Spanned
import android.view.ViewGroup
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicText
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.emoji2.text.EmojiCompat
import androidx.emoji2.text.EmojiSpan
import de.corespace.shroud.core.messaging.reactions.ReactionSet
import de.corespace.shroud.ui.components.ComposeHarness
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.time.Duration

/**
 * Emoji on Android 11 (API 30), whose system font stops at Emoji 13 while the reaction set reaches
 * Emoji 14/15 (decision D3b): the bundled EmojiCompat font that [BubbleEmoji] installs — through the
 * current executor constructor, the one without an executor being deprecated — loads from the APK and
 * covers every reaction, and a Compose text draws a newer emoji in colour rather than as tofu.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], application = Application::class, qualifiers = "xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class BubbleEmojiApi30Test {
    private val hosts = ArrayList<ComposeHarness>()

    @Before
    fun install() {
        BubbleEmoji.install(RuntimeEnvironment.getApplication())
        awaitLoaded()
    }

    @After
    fun tearDown() {
        hosts.forEach { host ->
            host.activity.findViewById<ViewGroup>(android.R.id.content).removeAllViews()
            host.idle()
        }
    }

    /** The font's metadata loads on the IO pool and reports to the main thread. */
    private fun awaitLoaded() {
        val deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos()
        while (EmojiCompat.get().loadState == EmojiCompat.LOAD_STATE_LOADING && System.nanoTime() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(20)
        }
        shadowOf(Looper.getMainLooper()).idle()
    }

    @Test
    fun theBundledFontLoadsOnApi30() {
        assertTrue(EmojiCompat.isConfigured())
        assertEquals(EmojiCompat.LOAD_STATE_SUCCEEDED, EmojiCompat.get().loadState)
    }

    @Test
    fun theBundledFontCoversEveryReaction() {
        val compat = EmojiCompat.get()
        // Emoji 14 and 15 among them: 🫠 melting face, 🫡 saluting face, 🩷 pink heart, 🫨 shaking face.
        val newer = listOf("🫠", "🫡", "🩷", "🫨")
        for (emoji in (ReactionSet.all + newer).distinct()) {
            val processed = compat.process(emoji, 0, emoji.length, Int.MAX_VALUE, EmojiCompat.REPLACE_STRATEGY_ALL)
            val spans = (processed as? Spanned)?.getSpans(0, processed.length, EmojiSpan::class.java).orEmpty()
            assertTrue("no bundled glyph for $emoji", spans.isNotEmpty())
        }
    }

    /** "🫠" (Emoji 14) through Compose text: coloured pixels, where the system font alone draws a grey box. */
    @Test
    fun aNewerEmojiDrawsInColourInComposeText() {
        // Android 11's own font cannot draw it: any colour below comes from the bundled font.
        assertFalse(Paint().hasGlyph("🫠"))
        val ui = ComposeHarness {
            Box(Modifier.background(androidx.compose.ui.graphics.Color.White).padding(8.dp)) {
                BasicText("🫠", style = TextStyle(fontSize = 40.sp))
            }
        }
        hosts += ui
        ui.idle()
        val root = ui.root
        val bitmap = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
        root.draw(Canvas(bitmap))
        if (System.getenv("SHROUD_RENDER_SCREENS") == "1") {
            val dir = File("build/outputs/c10-screens").apply { mkdirs() }
            File(dir, "11-emoji-api30.png").outputStream().use { Bitmap.createBitmap(bitmap, 0, 0, minOf(bitmap.width, 200), minOf(bitmap.height, 160)).compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
        var colourful = 0
        val hsv = FloatArray(3)
        for (y in 0 until minOf(bitmap.height, 160)) {
            for (x in 0 until minOf(bitmap.width, 200)) {
                Color.colorToHSV(bitmap.getPixel(x, y), hsv)
                if (hsv[1] > 0.4f && hsv[2] > 0.4f) colourful++
            }
        }
        // The melting face is a yellow disc: hundreds of saturated pixels at 2×; a tofu box has none.
        assertTrue("the emoji drew no colour ($colourful saturated pixels)", colourful > 300)
    }
}
