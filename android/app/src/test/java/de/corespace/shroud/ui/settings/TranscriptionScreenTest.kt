package de.corespace.shroud.ui.settings

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import de.corespace.shroud.core.transcription.TranscriptionInstallState
import de.corespace.shroud.core.transcription.VoiceTranscription
import de.corespace.shroud.ui.components.ComposeHarness
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.util.Locale
import java.util.UUID

/**
 * Settings › Transcription on the K7 seam (`transcription.voice`; settings-lock §9,
 * `TranscriptionLanguageView.swift:9-187`): Whisper's languages in the picker's order, the pin read on
 * appear and written on a tap (Automatic clears it), and the download card following the install
 * phases — shown only while the model downloads, never while a note is transcribed. Choosing a
 * language never starts a download.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class TranscriptionScreenTest {
    /** K7 with a stored pin; records every write and every model preparation. */
    private class FakeVoice(private var pin: Locale?) : VoiceTranscription {
        val installs = MutableStateFlow(TranscriptionInstallState.Idle)
        var prepared = 0
        val writes = ArrayList<Locale?>()

        override val isAvailable: StateFlow<Boolean> = MutableStateFlow(true)
        override val install: StateFlow<TranscriptionInstallState> = installs

        override suspend fun prepareModel(): Boolean {
            prepared++
            return true
        }

        override suspend fun modelIsInstalled(): Boolean = false

        override suspend fun transcribe(audio: ByteArray, mime: String, hints: List<String>, conversationId: UUID?, tracking: UUID?): String = ""

        // `whisperLocales`: the device's languages first, then Whisper's list.
        override fun availableLocales(): List<Locale> = listOf("de", "en", "fr", "ar", "zh", "nb").map(Locale::forLanguageTag)

        override var languageOverride: Locale?
            get() = pin
            set(value) {
                writes += value
                pin = value
            }

        override fun handOff(from: UUID, to: UUID) = Unit
    }

    private fun SemanticsNode.click() = config[SemanticsActions.OnClick].action!!.invoke()

    private fun SemanticsNode.isSelected(): Boolean = config.getOrNull(SemanticsProperties.Selected) == true

    private fun ComposeHarness.row(title: String): SemanticsNode =
        nodes().single { node -> node.config.getOrNull(SemanticsProperties.Text)?.firstOrNull()?.text == title && SemanticsActions.OnClick in node.config }

    private fun ComposeHarness.rowTitles(): List<String> =
        nodes().filter { SemanticsActions.OnClick in it.config && SemanticsProperties.Selected in it.config }
            .mapNotNull { it.config.getOrNull(SemanticsProperties.Text)?.firstOrNull()?.text }

    private fun name(tag: String) = Locale.forLanguageTag(tag).getDisplayName(Locale.getDefault())

    @Test
    fun thePinIsReadOnAppearAndWrittenOnATap() {
        val voice = FakeVoice(Locale.forLanguageTag("fr-CA"))
        val ui = ComposeHarness { TranscriptionScreen(voice, onBack = {}, deviceLanguageTags = { listOf("de-DE") }) }

        // German leads (the phone's language), then the rest A–Z by name.
        val expected = listOf("de") + listOf("en", "fr", "ar", "zh", "nb").sortedWith(compareBy(java.text.Collator.getInstance()) { name(it) })
        assertEquals(listOf("Automatic") + expected.map(::name), ui.rowTitles())
        assertTrue("a region-tagged pin selects its language", ui.row(name("fr")).isSelected())

        ui.row(name("ar")).click()
        ui.idle()
        assertEquals(listOf<Locale?>(Locale.forLanguageTag("ar")), voice.writes)
        assertTrue(ui.row(name("ar")).isSelected())

        ui.row("Automatic").click()
        ui.idle()
        assertNull("Automatic clears the pin", voice.writes.last())
        assertTrue(ui.row("Automatic").isSelected())
        assertEquals("choosing never downloads the model", 0, voice.prepared)
    }

    @Test
    fun theDownloadCardFollowsTheInstallPhases() {
        val voice = FakeVoice(null)
        val ui = ComposeHarness { TranscriptionScreen(voice, onBack = {}, deviceLanguageTags = { listOf("en-US") }) }
        fun card(): List<String> = ui.nodes().mapNotNull { node ->
            node.config.getOrNull(SemanticsProperties.Text)?.firstOrNull()?.text?.takeIf { it.startsWith("Downloading Whisper") }
        }
        assertEquals("idle: no card", emptyList<String>(), card())

        val id = UUID.randomUUID()
        voice.installs.value = TranscriptionInstallState(TranscriptionInstallState.Phase.Downloading, 0.0, isDeterminate = false, languageName = null, messageId = id)
        ui.idle()
        assertEquals(listOf("Downloading Whisper…"), card())

        voice.installs.value = TranscriptionInstallState(TranscriptionInstallState.Phase.Downloading, 0.004, isDeterminate = true, languageName = null, messageId = id)
        ui.idle()
        assertEquals("under half a percent rounds to 0: no number yet", listOf("Downloading Whisper…"), card())

        voice.installs.value = TranscriptionInstallState(TranscriptionInstallState.Phase.Downloading, 0.675, isDeterminate = true, languageName = null, messageId = id)
        ui.idle()
        assertEquals(listOf("Downloading Whisper… 68%"), card())

        voice.installs.value = TranscriptionInstallState(TranscriptionInstallState.Phase.Transcribing, 1.0, isDeterminate = false, languageName = null, messageId = id)
        ui.idle()
        assertEquals("transcribing: the bubble shows it, not Settings", emptyList<String>(), card())

        voice.installs.value = TranscriptionInstallState.Idle
        ui.idle()
        assertEquals(emptyList<String>(), card())
    }
}
