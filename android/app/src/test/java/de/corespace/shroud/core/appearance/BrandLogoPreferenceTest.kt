package de.corespace.shroud.core.appearance

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The launcher icon switch (settings-lock §8.4, §18.3 `BrandLogoPreferenceTest`): alias states ↔ style, enable before disable. */
@OptIn(ExperimentalCoroutinesApi::class)
class BrandLogoPreferenceTest {
    private class FakeAliases(simpleEnabled: Boolean = false) : LauncherAliases {
        val enabled = mutableMapOf(BrandLogoStyle.Detailed to !simpleEnabled, BrandLogoStyle.Simple to simpleEnabled)
        val applied = ArrayList<List<Pair<BrandLogoStyle, Boolean>>>()
        var fails = false

        override fun isEnabled(style: BrandLogoStyle): Boolean = enabled.getValue(style)

        override fun apply(changes: List<Pair<BrandLogoStyle, Boolean>>) {
            if (fails) throw SecurityException("refused")
            applied += changes
            changes.forEach { (style, on) -> enabled[style] = on }
        }
    }

    @Test
    fun theLauncherIsTheRecord() = runTest {
        assertEquals(BrandLogoStyle.Detailed, BrandLogoPreference(FakeAliases()).style.value)
        assertEquals(BrandLogoStyle.Simple, BrandLogoPreference(FakeAliases(simpleEnabled = true)).style.value)
    }

    @Test
    fun theNewAliasIsEnabledBeforeTheOldOneGoes() = runTest {
        val aliases = FakeAliases()
        val preference = BrandLogoPreference(aliases, UnconfinedTestDispatcher(testScheduler))
        preference.choose(BrandLogoStyle.Simple)
        assertEquals(listOf(listOf(BrandLogoStyle.Simple to true, BrandLogoStyle.Detailed to false)), aliases.applied)
        assertEquals(BrandLogoStyle.Simple, preference.style.value)
        assertFalse(preference.isChanging.value)
        preference.choose(BrandLogoStyle.Detailed)
        assertEquals(listOf(BrandLogoStyle.Detailed to true, BrandLogoStyle.Simple to false), aliases.applied.last())
        assertTrue(aliases.enabled.getValue(BrandLogoStyle.Detailed))
        assertFalse(aliases.enabled.getValue(BrandLogoStyle.Simple))
    }

    @Test
    fun theSameStyleChangesNothing() = runTest {
        val aliases = FakeAliases()
        BrandLogoPreference(aliases, UnconfinedTestDispatcher(testScheduler)).choose(BrandLogoStyle.Detailed)
        assertTrue(aliases.applied.isEmpty())
    }

    @Test
    fun aRefusalKeepsTheOldStyleAndThrows() = runTest {
        val aliases = FakeAliases().apply { fails = true }
        val preference = BrandLogoPreference(aliases, UnconfinedTestDispatcher(testScheduler))
        val thrown = runCatching { preference.choose(BrandLogoStyle.Simple) }.exceptionOrNull()
        assertTrue(thrown is SecurityException)
        assertEquals(BrandLogoStyle.Detailed, preference.style.value)
        assertFalse(preference.isChanging.value)
    }

    @Test
    fun theAliasNamesMatchTheManifest() {
        assertEquals("de.corespace.shroud.LauncherDetailed", PackageManagerLauncherAliases.aliasName(BrandLogoStyle.Detailed))
        assertEquals("de.corespace.shroud.LauncherSimple", PackageManagerLauncherAliases.aliasName(BrandLogoStyle.Simple))
        assertEquals(listOf("Detailed", "Simple"), BrandLogoStyle.entries.map { it.title })
    }
}
