package de.corespace.shroud.core.notifications

import androidx.core.content.edit
import de.corespace.shroud.core.storage.StorageSeal
import de.corespace.shroud.testing.FakeSharedPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `NotificationSettingsTests` (`ios/shroudTests/NotificationPayloadTests.swift:97-141`) over the
 * Android preferences file, plus the Android rules of notifications-push §5.4 and §5.10.4.
 */
class NotificationPreferencesTest {
    private val seal = StorageSeal()

    /** `testDefaultsAndServerPatch` (`:107-118`). */
    @Test
    fun defaultsAndServerPatch() {
        val preferences = NotificationPreferences(FakeSharedPreferences(), seal)
        assertTrue(preferences.enabled)
        assertTrue(preferences.showSender)
        assertTrue(preferences.showPreview)
        assertFalse(preferences.showContent)
        assertTrue(preferences.reactions)
        assertTrue(preferences.contactRequests)
        assertTrue(preferences.inAppBanners)
        assertTrue(preferences.inAppSounds)
        assertTrue(preferences.inAppVibrate)
        assertTrue(preferences.badge)
        assertFalse(preferences.badgeIncludesMuted)
        assertEquals(NotificationSound.Standard, preferences.sound)
        val patch = preferences.serverPatch()
        assertEquals("default", patch.sound)
        assertEquals(true, patch.enabled)
        assertEquals(true, patch.showSender)
        assertEquals(true, patch.reactions)
        assertEquals(true, patch.contactRequests)
        assertEquals(true, patch.badge)
        assertEquals(false, patch.badgeIncludesMuted)
    }

    /** `testPreferencesPersistAndReset` (`:120-141`). */
    @Test
    fun preferencesPersistAndReset() {
        val file = FakeSharedPreferences()
        val preferences = NotificationPreferences(file, seal)
        preferences.sound = NotificationSound.Chime
        preferences.showSender = false
        preferences.inAppBanners = false
        val reloaded = NotificationPreferences(file, seal)
        assertEquals(NotificationSound.Chime, reloaded.sound)
        assertFalse(reloaded.showSender)
        assertFalse(reloaded.inAppBanners)
        assertEquals("chime", reloaded.serverPatch().sound)
        reloaded.reset()
        assertEquals(NotificationSound.Standard, reloaded.sound)
        assertTrue(reloaded.showSender)
        assertTrue(NotificationPreferences(file, seal).inAppBanners)
        assertTrue(
            "a reset stores nothing, like a fresh install (the logout wipe checks for leftovers)",
            file.keys.none { it.startsWith("notifications.") },
        )
        reloaded.badge = false
        assertFalse("changes persist again after a reset", NotificationPreferences(file, seal).badge)
    }

    /** Every key name is the iOS one (`NotificationPreferences.swift:35-47`), stored as its type. */
    @Test
    fun keysAreTheIosNames() {
        val file = FakeSharedPreferences()
        val preferences = NotificationPreferences(file, seal)
        preferences.enabled = false
        preferences.showSender = false
        preferences.showPreview = false
        preferences.showContent = true
        preferences.reactions = false
        preferences.contactRequests = false
        preferences.sound = NotificationSound.Pulse
        preferences.inAppBanners = false
        preferences.inAppSounds = false
        preferences.inAppVibrate = false
        preferences.badge = false
        preferences.badgeIncludesMuted = true
        assertEquals(NotificationPreferences.ALL_KEYS.toSet(), file.keys)
        assertEquals("pulse", file.getString("notifications.sound", null))
        assertEquals(true, file.getBoolean("notifications.badgeIncludesMuted", false))
        assertEquals(false, file.getBoolean("notifications.enabled", true))
    }

    /** An absent key reads as its default, an unknown sound as Default (`:55-63`). */
    @Test
    fun storedValuesWinAndUnknownSoundsReadAsDefault() {
        val file = FakeSharedPreferences(mapOf("notifications.sound" to "trumpet", "notifications.badgeIncludesMuted" to true))
        val preferences = NotificationPreferences(file, seal)
        assertEquals(NotificationSound.Standard, preferences.sound)
        assertTrue(preferences.badgeIncludesMuted)
    }

    /**
     * notifications-push §5.10.4: the server's `enabled` also needs the system to allow
     * notifications; the stored preference stays as the user set it.
     */
    @Test
    fun theServerHearsEnabledOnlyWhenTheSystemAllows() {
        val file = FakeSharedPreferences()
        val preferences = NotificationPreferences(file, seal)
        assertEquals(false, preferences.serverPatch(systemAllows = false).enabled)
        assertTrue(preferences.enabled)
        assertFalse(file.contains("notifications.enabled"))
        preferences.enabled = false
        assertEquals(false, preferences.serverPatch(systemAllows = true).enabled)
    }

    @Test
    fun anUnchangedValueWritesNothing() {
        val file = FakeSharedPreferences()
        val preferences = NotificationPreferences(file, seal)
        preferences.enabled = true
        preferences.sound = NotificationSound.Standard
        assertEquals(0, file.editCount)
        assertTrue(file.keys.isEmpty())
    }

    /** A running wipe drops every write (crypto §14); a reset still removes. */
    @Test
    fun aSealedStoreDropsWrites() {
        val file = FakeSharedPreferences()
        val preferences = NotificationPreferences(file, seal)
        preferences.showSender = false
        seal.seal()
        preferences.sound = NotificationSound.Glass
        preferences.badge = false
        assertEquals(NotificationSound.Standard, preferences.sound)
        assertTrue(preferences.badge)
        preferences.reset()
        assertTrue(file.keys.isEmpty())
        assertTrue(preferences.showSender)
    }

    /** The wipe clears the file under it (`DeviceDataWipe`, settings step): the state follows. */
    @Test
    fun theStateFollowsAClearedFile() {
        val file = FakeSharedPreferences()
        val preferences = NotificationPreferences(file, seal)
        preferences.sound = NotificationSound.Pop
        preferences.badge = false
        file.edit(commit = true) { clear() }
        assertEquals(NotificationPrefsState(), preferences.state.value)
    }

    @Test
    fun theStateFlowFollowsEverySetter() {
        val preferences = NotificationPreferences(FakeSharedPreferences(), seal)
        preferences.inAppVibrate = false
        preferences.sound = NotificationSound.Note
        assertEquals(NotificationPrefsState(inAppVibrate = false, sound = NotificationSound.Note), preferences.state.value)
    }
}
