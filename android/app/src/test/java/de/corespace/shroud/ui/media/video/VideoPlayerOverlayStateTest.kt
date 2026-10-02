package de.corespace.shroud.ui.media.video

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import de.corespace.shroud.core.media.video.VideoSource
import de.corespace.shroud.ui.media.HarnessRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.util.UUID

/**
 * The player overlay on a scripted `ChatVideoPlayer` (`VideoPlayerOverlay`,
 * `ios/shroud/ShroudUI/Components/VideoPlayerOverlay.swift`; conversation-compose-media §17): it
 * plays the one `VideoSource` it was given at once, shows the sender and date, the failure line
 * for a clip that cannot be opened, the scrubber's TalkBack position, closes from the ✕, and tears
 * the player down when it leaves.
 */
@RunWith(RobolectricTestRunner::class)
// A plain Application: these screens run on fakes, and ShroudApplication would start the whole
// container (network, push) for every test, which piles up in the one test JVM.
@Config(sdk = [35], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class VideoPlayerOverlayStateTest {
    @get:Rule
    val harness = HarnessRule()

    private val message = VideoSource.Message(UUID.fromString("6f9619ff-8b86-d011-b42d-00cf4fc964ff"))

    @Test
    fun playsTheMessageVideoAtOnceWithItsTitle() {
        val engines = ScriptedEngines { ScriptedEngine(durationSeconds = 10.0) }
        val player = engines.player()
        var closes = 0
        val ui = harness.compose {
            VideoPlayerOverlayContent(message, "ann", "01.10.26", onClose = { closes++ }, player = player)
        }
        ui.idle()
        assertEquals(listOf<VideoSource>(message), engines.sources)
        assertTrue(player.state.value.isReady)
        assertTrue(player.state.value.isPlaying)
        assertTrue(ui.nodesWithText("ann").isNotEmpty())
        assertTrue(ui.nodesWithText("01.10.26").isNotEmpty())
        val scrubber = ui.node("Playback position")
        assertEquals("0:00 of 0:10", scrubber.config[SemanticsProperties.StateDescription])
        assertTrue(ui.nodesWithText("-0:10").isNotEmpty())
        // Pausing from the centre disc.
        ui.node("Pause").config[SemanticsActions.OnClick].action!!.invoke()
        ui.idle()
        assertFalse(player.state.value.isPlaying)
        // Sound off and on again.
        ui.node("Mute").config[SemanticsActions.OnClick].action!!.invoke()
        ui.idle()
        assertEquals(0f, (engines.engines.single() as ScriptedEngine).lastVolume, 0f)
        ui.node("Unmute").config[SemanticsActions.OnClick].action!!.invoke()
        ui.idle()
        assertEquals(1f, (engines.engines.single() as ScriptedEngine).lastVolume, 0f)
        ui.node("Close video").config[SemanticsActions.OnClick].action!!.invoke()
        ui.idle()
        assertEquals(1, closes)
    }

    @Test
    fun aClipThatCannotBeOpenedSaysSo() {
        val engines = ScriptedEngines { null }
        val ui = harness.compose {
            VideoPlayerOverlayContent(message, "You", "", onClose = {}, player = engines.player())
        }
        ui.idle()
        assertTrue(ui.describe(), ui.nodesWithText("This video could not be opened.").isNotEmpty())
        // No subtitle line for an empty one.
        assertTrue(ui.nodesWithText("You").isNotEmpty())
    }

    @Test
    fun leavingTearsThePlayerDown() {
        val engines = ScriptedEngines { ScriptedEngine() }
        val player = engines.player()
        var open by mutableStateOf(true)
        val ui = harness.compose {
            if (open) VideoPlayerOverlayContent(message, "ann", "01.10.26", onClose = {}, player = player)
        }
        ui.idle()
        val engine = engines.engines.single() as ScriptedEngine
        open = false
        ui.idle()
        assertTrue(engine.released)
        assertFalse(player.state.value.isReady)
    }
}
