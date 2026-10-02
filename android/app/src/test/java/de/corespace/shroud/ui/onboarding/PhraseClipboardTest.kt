package de.corespace.shroud.ui.onboarding

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * `EncryptionPhrasePasteboardTests.swift` ported (00-plan Appendix A; crypto §17.1, C36), vectors
 * verbatim, plus what Android adds: the clip is labelled and marked sensitive, and it is taken off
 * the clipboard after a minute — unless the user copied something else meanwhile.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PhraseClipboardTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val clipboard = context.getSystemService(ClipboardManager::class.java)
    private val scope = TestScope(StandardTestDispatcher())

    @Before
    fun emptyClipboard() {
        clipboard.clearPrimaryClip()
    }

    @Test
    fun copyWritesPlainTextToPasteboard() {
        // `EncryptionPhrasePasteboardTests.copyWritesPlainTextToPasteboard` (`:8-15`).
        val phrase = "ember copper lyric marble frost anchor velvet orbit prism delta canyon harbor"
        val copied = PhraseClipboard.copy(context, phrase, scope)

        assertTrue(copied)
        assertEquals(phrase, PhraseClipboard.read(context))
    }

    @Test
    fun copyIsReadableAsGeneralPasteboardString() {
        // `copyIsReadableAsGeneralPasteboardString` (`:17-24`): other apps paste the plain-text payload.
        val phrase = "alpha bravo charlie delta echo foxtrot golf hotel india juliet kilo lima"
        assertTrue(PhraseClipboard.copy(context, phrase, scope))
        assertEquals(phrase, clipboard.primaryClip!!.getItemAt(0).coerceToText(context).toString())
        assertTrue(clipboard.primaryClipDescription!!.hasMimeType(ClipDescription.MIMETYPE_TEXT_PLAIN))
    }

    @Test
    fun copyRejectsEmptyPhrase() {
        // `copyRejectsEmptyPhrase` (`:26-30`): nothing is put on the clipboard.
        assertFalse(PhraseClipboard.copy(context, "", scope))
        assertFalse(PhraseClipboard.copy(context, "   ", scope))
        assertNull(clipboard.primaryClip)
    }

    @Test
    fun copyTrimsThePhrase() {
        // `EncryptionPhrasePasteboard.copy` trims `.whitespacesAndNewlines` (`:17-18`).
        assertTrue(PhraseClipboard.copy(context, "  abandon ability able \n", scope))
        assertEquals("abandon ability able", clipboard.primaryClip!!.getItemAt(0).text.toString())
    }

    @Test
    fun theClipIsLabelledAndSensitive() {
        assertTrue(PhraseClipboard.copy(context, "abandon ability able", scope))
        val description = clipboard.primaryClipDescription!!
        assertEquals("Shroud encryption phrase", description.label)
        assertTrue(description.extras!!.getBoolean(ClipDescription.EXTRA_IS_SENSITIVE))
    }

    @Test
    fun thePhraseLeavesTheClipboardAfterAMinute() {
        // iOS `.expirationDate` 60 s (`:7-8`); Android clears it from the app scope.
        assertTrue(PhraseClipboard.copy(context, "abandon ability able", scope))
        scope.advanceTimeBy(59_999)
        scope.runCurrent()
        assertEquals("abandon ability able", PhraseClipboard.read(context))
        scope.advanceTimeBy(1)
        scope.runCurrent()
        assertNull(PhraseClipboard.read(context))
    }

    @Test
    fun somethingCopiedLaterStaysOnTheClipboard() {
        assertTrue(PhraseClipboard.copy(context, "abandon ability able", scope))
        clipboard.setPrimaryClip(ClipData.newPlainText("Notes", "shopping list"))
        scope.advanceTimeBy(60_000)
        scope.runCurrent()
        assertEquals("shopping list", PhraseClipboard.read(context))
    }

    @Test
    fun readTrimsAndIgnoresABlankClip() {
        // `read()` trims (`:48-74`); an empty clipboard or blank text reads as nothing.
        assertNull(PhraseClipboard.read(context))
        clipboard.setPrimaryClip(ClipData.newPlainText("Notes", "  \n "))
        assertNull(PhraseClipboard.read(context))
        clipboard.setPrimaryClip(ClipData.newPlainText("Notes", "\n abandon ability able \t"))
        assertEquals("abandon ability able", PhraseClipboard.read(context))
    }
}
