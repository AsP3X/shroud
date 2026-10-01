package de.corespace.shroud.core.appearance

import de.corespace.shroud.core.auth.WipeKeepList
import de.corespace.shroud.core.auth.WipeLocations
import de.corespace.shroud.core.storage.PrefsFiles
import de.corespace.shroud.core.storage.StorageSeal
import de.corespace.shroud.testing.FakeSharedPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File

/** `ios/shroudTests/ColorThemePreferenceTests.swift:16-60`: stored per phone, cleared by a Log Out. */
class ColorThemePreferenceTest {
    @Test
    fun startsOnSystem() {
        assertEquals(ColorTheme.System, ColorThemePreference(FakeSharedPreferences()).theme.value)
    }

    @Test
    fun aChoiceSurvivesARelaunch() {
        val prefs = FakeSharedPreferences()
        ColorThemePreference(prefs).choose(ColorTheme.Dark)
        assertEquals(ColorTheme.Dark, ColorThemePreference(prefs).theme.value)
        // The web client's key and values, so the two read alike.
        assertEquals("dark", prefs.getString("shroud.theme", null))
    }

    @Test
    fun systemStoresNothing() {
        val prefs = FakeSharedPreferences()
        val preference = ColorThemePreference(prefs)
        preference.choose(ColorTheme.Light)
        preference.choose(ColorTheme.System)
        assertFalse(prefs.contains(ColorThemePreference.KEY))
    }

    @Test
    fun forgetGoesBackToSystem() {
        val prefs = FakeSharedPreferences()
        val preference = ColorThemePreference(prefs)
        preference.choose(ColorTheme.Dark)
        preference.forget()
        assertEquals(ColorTheme.System, preference.theme.value)
        assertFalse(prefs.contains(ColorThemePreference.KEY))
    }

    @Test
    fun anUnknownValueFallsBackToSystem() {
        val prefs = FakeSharedPreferences(mapOf("shroud.theme" to "sepia"))
        assertEquals(ColorTheme.System, ColorThemePreference(prefs).theme.value)
    }

    @Test
    fun aLogoutClearsIt() {
        val keep = WipeKeepList.forApp(WipeLocations.under(File("/data/user/0/de.corespace.shroud")))
        assertFalse(keep.keepsPrefsKey(PrefsFiles.APPEARANCE, ColorThemePreference.KEY))
    }

    @Test
    fun whileAWipeRunsAChoiceIsNotWritten() {
        val prefs = FakeSharedPreferences()
        val seal = StorageSeal()
        val preference = ColorThemePreference(prefs, seal)
        seal.seal()
        preference.choose(ColorTheme.Dark)
        assertEquals(ColorTheme.Dark, preference.theme.value)
        assertNull(prefs.getString(ColorThemePreference.KEY, null))
    }

    @Test
    fun titlesAndRawValues() {
        assertEquals(listOf("System", "Light", "Dark"), ColorTheme.entries.map { it.title })
        assertEquals(listOf("system", "light", "dark"), ColorTheme.entries.map { it.raw })
        assertEquals(ColorTheme.Light, ColorTheme.fromRaw("light"))
        assertEquals(ColorTheme.System, ColorTheme.fromRaw(null))
    }
}
