package de.corespace.shroud.core.links

import de.corespace.shroud.core.storage.SecurityPreferences
import de.corespace.shroud.core.storage.StorageSeal
import de.corespace.shroud.testing.FakeSharedPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Settings → Privacy → Link previews (`SecurityPreferences.swift:23-38`): on by default, stored as
 * `privacy.generateLinkPreviews` in `shroud.preferences` through [SecurityPreferences], dropped
 * while a wipe runs, and back to on once the wipe cleared the file.
 */
class LinkPreviewSettingsTest {
    private val prefs = FakeSharedPreferences()
    private val seal = StorageSeal()
    private val settings = LinkPreviewSettings(SecurityPreferences(prefs, seal))

    @Test
    fun onByDefault() {
        assertTrue(settings.enabled)
        assertTrue(settings.changes.value)
    }

    @Test
    fun switchingOffIsStoredUnderTheIosKey() {
        settings.enabled = false
        assertFalse(settings.enabled)
        assertFalse(settings.changes.value)
        assertEquals(false, prefs.all["privacy.generateLinkPreviews"])
        settings.enabled = true
        assertEquals(true, prefs.all["privacy.generateLinkPreviews"])
    }

    @Test
    fun aWipeDropsTheWriteAndClearingTheFileTurnsPreviewsBackOn() {
        settings.enabled = false
        seal.seal()
        settings.enabled = true
        assertFalse("dropped while sealed", settings.enabled)
        prefs.edit().clear().apply()
        assertTrue(settings.enabled)
    }

    @Test
    fun theComposerReadsTheSwitch() {
        val composerSwitch: () -> Boolean = { settings.enabled }
        settings.enabled = false
        assertFalse(composerSwitch())
    }
}
