package de.corespace.shroud.core.storage

import de.corespace.shroud.testing.FakeSharedPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The stored auto-lock delay (iOS `ios/shroudTests/AutoLockDelayTests.swift:31-37`) and the privacy
 * switches' defaults (`ios/shroud/Services/Crypto/SecurityPreferences.swift:12-79`; crypto spec
 * §17.4). The iOS `theOldSwitchCarriesOver` has no Android counterpart: no build ever wrote the
 * old key. The delay arithmetic is `AutoLockDelayTest`.
 */
class SecurityPreferencesTest {
    /** `defaultsToImmediately` (`AutoLockDelayTests.swift:31-37`). */
    @Test
    fun defaultsToImmediatelyAndASetValueReadsBack() {
        val prefs = FakeSharedPreferences()
        val security = SecurityPreferences(prefs, StorageSeal())
        assertEquals(AutoLockDelay.Immediately, security.autoLockDelay.value)
        security.setAutoLockDelay(AutoLockDelay.FiveMinutes)
        assertEquals(AutoLockDelay.FiveMinutes, security.autoLockDelay.value)
        assertEquals(300, prefs.getInt(SecurityPreferences.KEY_AUTO_LOCK, 0))
        // A new instance on the same file reads it back.
        assertEquals(AutoLockDelay.FiveMinutes, SecurityPreferences(prefs, StorageSeal()).autoLockDelay.value)
    }

    @Test
    fun privacyDefaultsMatchIos() {
        val security = SecurityPreferences(FakeSharedPreferences(), StorageSeal())
        assertTrue("link previews default on (absent = true)", security.generatesLinkPreviews.value)
        assertFalse("relay-only calls default off", security.alwaysRelayCalls.value)
        assertTrue("hide during capture defaults on (absent = true)", security.hidesDuringScreenCapture.value)
    }

    @Test
    fun theKeysAreTheIosKeysInTheWipedPreferencesFile() {
        assertEquals("shroud.preferences", PrefsFiles.PREFERENCES)
        assertEquals("security.autoLockDelay", SecurityPreferences.KEY_AUTO_LOCK)
        assertEquals("privacy.generateLinkPreviews", SecurityPreferences.KEY_LINK_PREVIEWS)
        assertEquals("privacy.alwaysRelayCalls", SecurityPreferences.KEY_RELAY_CALLS)
        assertEquals("privacy.hideDuringScreenCapture", SecurityPreferences.KEY_HIDE_CAPTURE)
    }

    @Test
    fun settersWriteThroughAndSkipUnchangedValues() {
        val prefs = FakeSharedPreferences()
        val security = SecurityPreferences(prefs, StorageSeal())
        security.setGeneratesLinkPreviews(true) // already true: no write
        assertEquals(0, prefs.editCount)
        security.setGeneratesLinkPreviews(false)
        security.setAlwaysRelayCalls(true)
        security.setHidesDuringScreenCapture(false)
        assertEquals(3, prefs.editCount)
        assertFalse(prefs.getBoolean(SecurityPreferences.KEY_LINK_PREVIEWS, true))
        assertTrue(prefs.getBoolean(SecurityPreferences.KEY_RELAY_CALLS, false))
        assertFalse(prefs.getBoolean(SecurityPreferences.KEY_HIDE_CAPTURE, true))
        assertFalse(security.generatesLinkPreviews.value)
        assertTrue(security.alwaysRelayCalls.value)
        assertFalse(security.hidesDuringScreenCapture.value)
    }

    @Test
    fun aWipeInProgressDropsEveryWrite() {
        val prefs = FakeSharedPreferences()
        val seal = StorageSeal()
        val security = SecurityPreferences(prefs, seal)
        seal.seal()
        security.setAutoLockDelay(AutoLockDelay.Never)
        security.setGeneratesLinkPreviews(false)
        security.setAlwaysRelayCalls(true)
        security.setHidesDuringScreenCapture(false)
        assertEquals(0, prefs.editCount)
        assertEquals(AutoLockDelay.Immediately, security.autoLockDelay.value)
        assertTrue(security.generatesLinkPreviews.value)
    }

    @Test
    fun clearingTheFileResetsTheFlowsToTheDefaults() {
        val prefs = FakeSharedPreferences()
        val security = SecurityPreferences(prefs, StorageSeal())
        security.setAutoLockDelay(AutoLockDelay.OneMinute)
        security.setGeneratesLinkPreviews(false)
        prefs.edit().clear().apply() // what the Log Out wipe does to the file
        assertEquals(AutoLockDelay.Immediately, security.autoLockDelay.value)
        assertTrue(security.generatesLinkPreviews.value)
    }

    @Test
    fun anotherWriterOfTheFileIsHeard() {
        val prefs = FakeSharedPreferences()
        val security = SecurityPreferences(prefs, StorageSeal())
        prefs.edit().putBoolean(SecurityPreferences.KEY_RELAY_CALLS, true).putInt(SecurityPreferences.KEY_AUTO_LOCK, 900).apply()
        assertTrue(security.alwaysRelayCalls.value)
        assertEquals(AutoLockDelay.FifteenMinutes, security.autoLockDelay.value)
    }

    @Test
    fun anUnknownStoredDelayReadsAsTheDefault() {
        val prefs = FakeSharedPreferences(mapOf(SecurityPreferences.KEY_AUTO_LOCK to 42))
        assertEquals(AutoLockDelay.Immediately, SecurityPreferences(prefs, StorageSeal()).autoLockDelay.value)
    }
}
