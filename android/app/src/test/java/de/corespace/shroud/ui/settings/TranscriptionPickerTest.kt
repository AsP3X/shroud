package de.corespace.shroud.ui.settings

import de.corespace.shroud.core.transcription.TranscriptionInstallState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

/** Settings › Transcription's order, selection and download copy (`TranscriptionLanguageView.swift:46-131`; settings-lock §9). */
class TranscriptionPickerTest {
    private fun locales(vararg codes: String) = codes.map(Locale::forLanguageTag)

    @Test
    fun theDevicesLanguagesLeadThenTheRestByName() {
        // `whisperLocales` already lists the device's languages first ("de", "en").
        val all = locales("de", "en", "zh", "fr", "ar", "ca", "hr")
        val ordered = TranscriptionPicker.order(all, listOf("de-DE", "en-US"), Locale.ENGLISH)
        assertEquals(listOf("de", "en", "ar", "ca", "zh", "hr", "fr"), ordered.map { it.language })
        assertEquals(
            listOf("German", "English", "Arabic", "Catalan", "Chinese", "Croatian", "French"),
            ordered.map { TranscriptionPicker.displayName(it, Locale.ENGLISH) },
        )
    }

    @Test
    fun onlyTheLeadingRunStaysInPlace() {
        // A preferred language that is not at the front (iOS `prefix(while:)`) is sorted with the rest.
        val ordered = TranscriptionPicker.order(locales("en", "ja", "de"), listOf("en", "de"), Locale.ENGLISH)
        assertEquals(listOf("en", "de", "ja"), ordered.map { it.language })
    }

    @Test
    fun noPreferredLanguagesSortsEverything() {
        val ordered = TranscriptionPicker.order(locales("sv", "en", "de"), emptyList(), Locale.ENGLISH)
        assertEquals(listOf("en", "de", "sv"), ordered.map { it.language })
    }

    @Test
    fun selectionIsByLanguageCode() {
        val german = Locale.forLanguageTag("de")
        assertTrue(TranscriptionPicker.isSelected(german, Locale.forLanguageTag("de-DE")))
        assertTrue(TranscriptionPicker.isSelected(german, german))
        assertFalse(TranscriptionPicker.isSelected(german, Locale.forLanguageTag("en")))
        assertFalse("Automatic selects no language", TranscriptionPicker.isSelected(german, null))
    }

    @Test
    fun downloadTitle() {
        fun state(fraction: Double, determinate: Boolean) =
            TranscriptionInstallState(TranscriptionInstallState.Phase.Downloading, fraction, determinate, languageName = null, messageId = null)
        fun title(state: TranscriptionInstallState) =
            listOfNotNull(TranscriptionPicker.DOWNLOAD_TITLE, TranscriptionPicker.downloadPercent(state)).joinToString(" ")
        assertEquals("Downloading Whisper… 42%", title(state(0.42, true)))
        assertEquals("Downloading Whisper… 1%", title(state(0.005, true)))
        assertEquals("Downloading Whisper…", title(state(0.004, true)))
        assertEquals("Downloading Whisper…", title(state(0.5, false)))
        assertEquals(0.02f, TranscriptionPicker.barFraction(state(0.0, true)), 0f)
        assertEquals(0.5f, TranscriptionPicker.barFraction(state(0.5, true)), 0f)
    }

    @Test
    fun copy() {
        assertEquals(
            "Voice messages are transcribed on this device with Whisper. Audio never leaves it. The model downloads once, the first " +
                "time you transcribe, then works for every language.",
            TranscriptionPicker.HEADER,
        )
        assertEquals("Detects the spoken language. Remembers it per chat when Whisper is unsure.", TranscriptionPicker.AUTOMATIC_SUBTITLE)
    }
}
