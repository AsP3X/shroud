package de.corespace.shroud.ui.conversation.bubble

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.ViewGroup
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.Modifier
import de.corespace.shroud.AppContainer
import de.corespace.shroud.core.lifecycle.AppPhaseMonitor
import de.corespace.shroud.core.model.ChatPeerActivity
import de.corespace.shroud.core.transcription.TranscribeException
import de.corespace.shroud.ui.components.ComposeHarness
import de.corespace.shroud.ui.theme.ShroudTheme
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.math.abs

/** What C10 changed against iOS, pinned (conversation-thread §11.7, §12.2; plan §1.7.13). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "w412dp-h900dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class BubbleFixesTest {
    private val hosts = ArrayList<ComposeHarness>()

    @After
    fun tearDown() {
        hosts.forEach { host ->
            host.activity.findViewById<ViewGroup>(android.R.id.content).removeAllViews()
            host.idle()
        }
        VoiceTranscriptDisclosure.reset()
        BubbleRenderFixtures.flushSnapshotWrites()
    }

    // ---- "→A" that fails (`ConversationView.swift:1607-1632`) ----

    @Test
    fun audioThatIsNotHereFoldsBackWithoutAWord() {
        assertNull(VoiceBubbleMath.transcriptFailureToast(TranscriptAudioMissing()))
    }

    @Test
    fun aFailedTranscriptionSaysWhy() {
        val sentence = "Couldn't download the transcription model. Check your connection and try again."
        assertEquals(sentence, VoiceBubbleMath.transcriptFailureToast(TranscribeException(sentence)))
        assertEquals(VoiceBubbleMath.TRANSCRIPT_FAILED, VoiceBubbleMath.transcriptFailureToast(IllegalStateException()))
    }

    // ---- the typing ink keeps its colour where the layer effect is not drawn (`TypingIndicatorBubble.swift:165-170`) ----

    @Test
    fun theStillTypingInkIsAccentNotBlack() {
        var accent = 0
        val host = ComposeHarness(reduceMotion = true) {
            accent = ShroudTheme.colors.accent.let { android.graphics.Color.argb(255, (it.red * 255).toInt(), (it.green * 255).toInt(), (it.blue * 255).toInt()) }
            Box(Modifier) { TypingIndicatorBubble(ChatPeerActivity.Typing) }
        }
        hosts += host
        host.idle()
        val root = host.root
        val bitmap = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
        root.draw(Canvas(bitmap))
        var accentPixels = 0
        var blackPixels = 0
        // The bubble's first 66 × 37 dp: padding 13 / 8 around the 40 × 20 ink box, at 2×.
        for (y in 0 until 74) {
            for (x in 0 until 132) {
                val pixel = bitmap.getPixel(x, y)
                if (close(pixel, accent)) accentPixels++
                if (android.graphics.Color.alpha(pixel) == 255 && close(pixel, android.graphics.Color.BLACK)) blackPixels++
            }
        }
        assertTrue("accent dots drawn ($accentPixels)", accentPixels > 60)
        assertEquals("no black silhouette", 0, blackPixels)
    }

    private fun close(a: Int, b: Int): Boolean =
        abs(android.graphics.Color.red(a) - android.graphics.Color.red(b)) < 12 &&
            abs(android.graphics.Color.green(a) - android.graphics.Color.green(b)) < 12 &&
            abs(android.graphics.Color.blue(a) - android.graphics.Color.blue(b)) < 12

    // ---- one services per app, so the bubbles' sink is registered once ----

    @Test
    fun everyBubbleOfTheAppSharesOneServices() {
        val container = AppContainer(RuntimeEnvironment.getApplication(), AppPhaseMonitor(tracks = { false }))
        assertSame(BubbleServices.forContainer(container), BubbleServices.forContainer(container))
    }
}
