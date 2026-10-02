package de.corespace.shroud.ui.media.video

import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import de.corespace.shroud.core.media.video.ChatVideoPlayer
import de.corespace.shroud.core.media.video.VideoProbe
import de.corespace.shroud.core.media.video.VideoSendPlan
import de.corespace.shroud.core.media.video.VideoSource
import de.corespace.shroud.core.media.video.VideoTrim
import de.corespace.shroud.core.media.video.VideoUploadQuality
import de.corespace.shroud.ui.components.ComposeHarness
import de.corespace.shroud.ui.media.PickedMovie
import de.corespace.shroud.ui.media.PickedVideo
import de.corespace.shroud.ui.media.VideoComposeDraft
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The video compose screen on scripted services (`VideoComposeOverlay`,
 * `ios/shroud/ShroudUI/Components/VideoComposeOverlay.swift`; conversation-compose-media §15): the
 * preview plays the clip on screen, the line under the strip, mute and its banner, the quality
 * menu, Send held back while a clip cannot fit, the plans Send hands out, Add once ten are staged,
 * and Remove from a clip's long press.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class VideoComposeStateTest {
    private class FakeServices : VideoComposeServices {
        val engines = ScriptedEngines { ScriptedEngine(durationSeconds = 8.0) }
        var strips = 0

        override fun newPlayer(): ChatVideoPlayer = engines.player()

        override fun filmstrip(uri: Uri, count: Int): Flow<Bitmap> {
            strips++
            return flowOf(*Array(count) { Bitmap.createBitmap(16, 9, Bitmap.Config.ARGB_8888) })
        }

        override fun compressJpeg(bitmap: Bitmap, quality: Int): ByteArray = byteArrayOf(quality.toByte())

        override fun recipientName(): String? = "ann"
    }

    private fun probe(seconds: Double, width: Int, height: Int, bytes: Long, hasAudio: Boolean = true, ext: String = "mp4") =
        VideoProbe(seconds, width, height, bytes, hasAudio, ext, "video/avc", if (hasAudio) "audio/mp4a-latm" else null)

    private fun video(name: String, probe: VideoProbe) = PickedVideo(movie = PickedMovie(Uri.parse("content://picker/$name")), probe = probe, poster = null)

    private val short = video("short", probe(8.0, 1920, 1080, 2_000_000))
    private val long4k = video("long", probe(4.0 * 3600, 3840, 2160, 80_000_000, ext = "mov"))

    private fun ComposeHarness.click(description: String) {
        described(description).config[SemanticsActions.OnClick].action!!.invoke()
        idle()
    }

    /** The one node whose content description is exactly [description]. */
    private fun ComposeHarness.described(description: String) =
        nodes().single { it.config.getOrElse(SemanticsProperties.ContentDescription) { emptyList() }.contains(description) }

    private fun ComposeHarness.clickText(text: String) {
        nodesWithText(text).single { SemanticsActions.OnClick in it.config }.config[SemanticsActions.OnClick].action!!.invoke()
        idle()
    }

    @Test
    fun previewPlaysTheClipAndTheLineSaysWhatWillBeSent() {
        val services = FakeServices()
        val ui = ComposeHarness(dark = true) {
            VideoComposeContent(VideoComposeDraft(listOf(short)), onSend = {}, onAddMore = {}, onRemove = {}, onClose = {}, services = services)
        }
        ui.idle()
        assertEquals(listOf<VideoSource>(VideoSource.Content(short.movie.uri)), services.engines.sources)
        assertEquals(1, services.strips)
        assertTrue(ui.describe(), ui.nodesWithText("0:08").any { node -> node.config[SemanticsProperties.Text].any { "720p" in it.text } })
        assertTrue(ui.nodesWithText("High").isNotEmpty())
        assertTrue(ui.node("Sending to ann").config.contains(SemanticsProperties.ContentDescription))
        assertEquals("Playing", ui.node("Video preview").config[SemanticsProperties.StateDescription])
        assertEquals("Trim start", ui.node("Trim start").config[SemanticsProperties.ContentDescription].single())
        // One clip: no strip and no count badge.
        assertTrue(ui.nodes().none { it.config.getOrElse(SemanticsProperties.ContentDescription) { emptyList() }.any { d -> d.startsWith("Video 1 of") } })
        assertFalse(SemanticsProperties.Disabled in ui.node("Send video").config)
    }

    @Test
    fun muteFlashesItsBannerAndTheSendCarriesIt() {
        val services = FakeServices()
        val sent = ArrayList<List<VideoSendPlan>>()
        val ui = ComposeHarness(dark = true) {
            VideoComposeContent(VideoComposeDraft(listOf(short), caption = "  hi "), onSend = { sent += it }, onAddMore = {}, onRemove = {}, onClose = {}, services = services)
        }
        ui.idle()
        ui.click("Sound on")
        assertTrue(ui.describe(), ui.nodesWithText("Sound will be removed").isNotEmpty())
        assertTrue(ui.nodesWithText("MUTED").isNotEmpty())
        assertEquals(0f, (services.engines.engines.last() as ScriptedEngine).volume, 0f)
        ui.click("Send video")
        val plans = sent.single()
        assertEquals(1, plans.size)
        assertEquals("hi", plans[0].caption)
        assertTrue(plans[0].removeAudio)
        assertEquals(VideoUploadQuality.High, plans[0].quality)
        assertEquals(VideoTrim(0.0, 8.0), plans[0].trim)
        assertEquals(8_000, plans[0].durationMs)
        // The poster is the first filmstrip tile, JPEG 70.
        assertEquals(listOf(70.toByte()), plans[0].posterJpeg?.toByteArray()?.toList())
    }

    @Test
    fun aClipThatCannotFitHoldsSendBackAndTheMenuSaysTooLong() {
        val services = FakeServices()
        val sent = ArrayList<List<VideoSendPlan>>()
        val ui = ComposeHarness(dark = true) {
            VideoComposeContent(VideoComposeDraft(listOf(short, long4k)), onSend = { sent += it }, onAddMore = {}, onRemove = {}, onClose = {}, services = services)
        }
        ui.idle()
        // Two clips: the strip and the count badge.
        listOf("Video 1 of 2", "Video 2 of 2", "2 videos").forEach { ui.described(it) }
        // Original fits the clip on screen, so it can be chosen — and then the other clip does not fit.
        ui.click("Video quality, High")
        ui.clickText("Original \u00B7 Full size")
        assertTrue(ui.describe(), ui.nodesWithText("One video won\u2019t fit at this quality. Original quality won\u2019t fit.").isNotEmpty())
        assertTrue(SemanticsProperties.Disabled in ui.described("Send 2 videos").config)
        ui.click("Send 2 videos")
        assertNull(sent.firstOrNull())
        // On the clip that does not fit, the line says so and its own sentence shows.
        ui.click("Video 2 of 2")
        assertTrue(ui.describe(), ui.nodesWithText("240:00  \u00B7  Too long").isNotEmpty())
        assertTrue(ui.nodesWithText("Original quality won\u2019t fit. Trim it to ").isNotEmpty())
        ui.click("Video quality, Original")
        val row = ui.nodesWithText("Original \u00B7 Too long").single()
        assertTrue(SemanticsProperties.Disabled in row.config)
        // A lower quality fits both, and Send is back.
        ui.clickText("Medium \u00B7 540p  \u2248")
        assertTrue(ui.nodesWithText("Medium \u00B7 540p").isNotEmpty())
        assertFalse(SemanticsProperties.Disabled in ui.described("Send 2 videos").config)
    }

    @Test
    fun addIsDisabledOnceTenAreStagedAndRemoveGoesThroughTheHost() {
        val removed = ArrayList<Int>()
        val ten = List(10) { video("v$it", probe(3.0, 640, 480, 500_000)) }
        val ui = ComposeHarness(dark = true) {
            VideoComposeContent(VideoComposeDraft(ten), onSend = {}, onAddMore = {}, onRemove = { removed += it }, onClose = {}, services = FakeServices())
        }
        ui.idle()
        assertTrue(SemanticsProperties.Disabled in ui.node("Add more videos").config)
        assertEquals("Send 10 videos", ui.node("Send 10 videos").config[SemanticsProperties.ContentDescription].single())
        ui.node("Video 3 of 10").config[SemanticsActions.OnLongClick].action!!.invoke()
        ui.idle()
        ui.clickText("Remove")
        assertEquals(listOf(2), removed)
    }

    @Test
    fun backCloses() {
        var closes = 0
        val ui = ComposeHarness(dark = true) {
            VideoComposeContent(VideoComposeDraft(listOf(short)), onSend = {}, onAddMore = {}, onRemove = {}, onClose = { closes++ }, services = FakeServices())
        }
        ui.click("Back")
        assertEquals(1, closes)
        ui.activity.onBackPressedDispatcher.onBackPressed()
        ui.idle()
        assertEquals(2, closes)
    }
}
