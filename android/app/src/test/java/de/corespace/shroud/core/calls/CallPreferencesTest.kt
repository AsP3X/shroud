package de.corespace.shroud.core.calls

import de.corespace.shroud.core.storage.SecurityPreferences
import de.corespace.shroud.core.storage.StorageSeal
import de.corespace.shroud.testing.FakeSharedPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Screen-share quality kept on this phone (CC:2149-2168; calls §11) and the relay switch read through. */
class CallPreferencesTest {
    private val p1080 = ScreenShareQuality.Resolution.P1080

    @Test
    fun theStandardUntilAChoiceIsMade() {
        val prefs = CallPreferences(FakeSharedPreferences(), StorageSeal(), null)
        assertEquals(ScreenShareQuality.Standard, prefs.screenShareQuality.value)
        assertFalse(prefs.alwaysRelayCalls)
    }

    @Test
    fun aChoiceIsKeptUnderTheWebsRawValues() {
        val file = FakeSharedPreferences()
        val prefs = CallPreferences(file, StorageSeal(), null)
        prefs.setScreenShareQuality(ScreenShareQuality(ScreenShareQuality.Resolution.Source, 60))
        assertEquals("source", file.getString("calls.screenShareResolution", null))
        assertEquals(60, file.getInt("calls.screenShareFrameRate", 0))
        assertEquals(ScreenShareQuality(ScreenShareQuality.Resolution.Source, 60), CallPreferences(file, StorageSeal(), null).screenShareQuality.value)
        // A frame rate that is not offered is ignored.
        prefs.setScreenShareQuality(ScreenShareQuality(p1080, 24))
        assertEquals(60, file.getInt("calls.screenShareFrameRate", 0))
    }

    @Test
    fun unknownPartsFallBackAlone() {
        val file = FakeSharedPreferences(mapOf("calls.screenShareResolution" to "4k", "calls.screenShareFrameRate" to 30))
        assertEquals(ScreenShareQuality(p1080, 30), CallPreferences(file, StorageSeal(), null).screenShareQuality.value)
    }

    @Test
    fun aWipeDropsWritesAndAClearResetsTheChoice() {
        val file = FakeSharedPreferences()
        val seal = StorageSeal()
        val prefs = CallPreferences(file, seal, null)
        prefs.setScreenShareQuality(ScreenShareQuality(ScreenShareQuality.Resolution.P720, 30))
        seal.seal()
        prefs.setScreenShareQuality(ScreenShareQuality(ScreenShareQuality.Resolution.Source, 60))
        assertEquals("720p", file.getString("calls.screenShareResolution", null))
        file.edit().clear().apply()
        assertEquals(ScreenShareQuality.Standard, prefs.screenShareQuality.value)
        assertTrue(file.keys.isEmpty())
    }

    @Test
    fun centerStageIsOnUntilTurnedOffAndAWipeDropsTheWrite() {
        val file = FakeSharedPreferences()
        val seal = StorageSeal()
        val prefs = CallPreferences(file, seal, null)
        assertTrue(prefs.centerStage.value)
        prefs.setCenterStage(false)
        assertFalse(file.getBoolean("calls.centerStage", true))
        assertFalse(CallPreferences(file, StorageSeal(), null).centerStage.value)
        seal.seal()
        prefs.setCenterStage(true)
        assertFalse(file.getBoolean("calls.centerStage", true))
        assertFalse(prefs.centerStage.value)
        file.edit().clear().apply()
        assertTrue(prefs.centerStage.value)
    }

    @Test
    fun theRelaySwitchIsSecurityPreferences() {
        val file = FakeSharedPreferences()
        val security = SecurityPreferences(file, StorageSeal())
        val prefs = CallPreferences(file, StorageSeal(), security)
        assertFalse(prefs.alwaysRelayCalls)
        security.setAlwaysRelayCalls(true)
        assertTrue(prefs.alwaysRelayCalls)
        assertTrue(file.getBoolean("privacy.alwaysRelayCalls", false))
    }
}
