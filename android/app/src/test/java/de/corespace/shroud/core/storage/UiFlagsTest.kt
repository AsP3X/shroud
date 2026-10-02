package de.corespace.shroud.core.storage

import de.corespace.shroud.core.auth.WipeFixture
import de.corespace.shroud.di.AuthModule
import de.corespace.shroud.testing.FakeSharedPreferences
import de.corespace.shroud.testing.TempDirRule
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** [UiFlags] round-trips booleans, drops writes while sealed, and Log Out deletes the file's keys. */
class UiFlagsTest {
    @get:Rule val temp = TempDirRule()

    @Test
    fun flagsRoundTripAndASealDropsWrites() {
        val prefs = FakeSharedPreferences()
        val seal = StorageSeal()
        val flags = PrefsUiFlags(prefs, seal)
        assertFalse(flags.get("ui.a"))
        assertTrue(flags.get("ui.a", true))
        flags.set("ui.a", true)
        assertTrue(flags.get("ui.a"))
        flags.set("ui.a", false)
        assertFalse(flags.get("ui.a", true))
        assertTrue(prefs.contains("ui.a"))
        seal.seal()
        flags.set("ui.a", true)
        assertFalse(flags.get("ui.a", true))
        flags.set("ui.b", true)
        assertFalse(prefs.contains("ui.b"))
    }

    @Test
    fun logOutClearsUiFlags() = runTest {
        assertEquals("shroud.ui", PrefsFiles.UI)
        assertTrue(PrefsFiles.UI in AuthModule.KNOWN_PREFS)
        val fixture = WipeFixture(temp.root)
        assertFalse(fixture.keepList.keepsPrefsKey(PrefsFiles.UI, "ui.a"))
        val flags = PrefsUiFlags(fixture.prefs.open(PrefsFiles.UI), StorageSeal())
        flags.set("ui.a", true)
        flags.set("ui.b", false)
        assertTrue(flags.get("ui.a"))
        assertTrue(fixture.prefs.open(PrefsFiles.UI).contains("ui.b"))
        fixture.wipe.wipeSettings()
        assertFalse(flags.get("ui.a"))
        assertFalse(fixture.prefs.open(PrefsFiles.UI).contains("ui.a"))
        assertFalse(fixture.prefs.open(PrefsFiles.UI).contains("ui.b"))
    }

    /** `shroud.ui` is wiped; a flag written through [UiFlags.kept] lives in [PrefsFiles.DEVICE] and stays. */
    @Test
    fun logOutRemovesUiFlagsAndLeavesTheKeptOnes() = runTest {
        val fixture = WipeFixture(temp.root)
        val seal = StorageSeal()
        val kept = PrefsUiFlags(fixture.prefs.open(PrefsFiles.DEVICE), seal)
        val flags = PrefsUiFlags(fixture.prefs.open(PrefsFiles.UI), seal, kept)
        assertSame(kept, flags.kept)
        assertSame(kept, kept.kept)
        flags.set("ui.a", true)
        flags.kept.set("ui.photoAsked", true)
        assertTrue(fixture.keepList.keepsPrefsKey(PrefsFiles.DEVICE, "ui.photoAsked"))
        assertFalse(fixture.keepList.keepsPrefsKey(PrefsFiles.UI, "ui.a"))
        seal.seal()
        flags.kept.set("ui.photoAsked", false)
        flags.kept.set("ui.other", true)
        seal.unseal()
        assertTrue(flags.kept.get("ui.photoAsked"))
        assertFalse(fixture.prefs.open(PrefsFiles.DEVICE).contains("ui.other"))
        fixture.wipe.wipeSettings()
        assertFalse(flags.get("ui.a"))
        assertFalse(fixture.prefs.open(PrefsFiles.UI).contains("ui.a"))
        assertTrue(flags.kept.get("ui.photoAsked"))
        assertTrue(fixture.prefs.open(PrefsFiles.DEVICE).contains("ui.photoAsked"))
    }
}
