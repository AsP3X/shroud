package de.corespace.shroud.core.auth

import de.corespace.shroud.core.appearance.ColorThemePreference
import de.corespace.shroud.core.storage.PrefsFiles
import de.corespace.shroud.testing.TempDirRule
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.Locale

/**
 * The device wipe against a fake data directory, fake preferences and a fake Keystore
 * (`ios/shroudTests/DeviceDataWipeTests.swift:105-236`; Android fixture of settings-lock §18.2).
 */
class DeviceDataWipeTest {
    @get:Rule val temp = TempDirRule()

    @Test
    fun inventoryCountsWhatIsThere() {
        val fixture = WipeFixture(temp.root)
        fixture.seedAccount()

        val inventory = fixture.wipe.inventory()
        assertEquals(4, inventory.messages)
        // The sealed media and three cached files; not the kept weights.
        assertEquals(4, inventory.mediaFiles)
        assertEquals(4096L + 2048 + 512 + 16, inventory.mediaBytes)
        assertEquals(6672L, inventory.mediaBytes)
        // Three aliases, the identity record and the two sealed records at the no-backup root.
        assertEquals(3 + 3, inventory.keys)
        // security.autoLockDelay, transcription.locale, shroud.theme — not the kept keys.
        assertEquals(3, inventory.settings)
    }

    @Test
    fun wipeLeavesOnlyTheKeepList() = runTest {
        val fixture = WipeFixture(temp.root)
        fixture.seedAccount()
        assertTrue(fixture.wipe.leftovers().isNotEmpty())

        fixture.wipe.wipeMessages()
        fixture.wipe.wipeMedia()
        fixture.wipe.wipeKeys()
        fixture.wipe.wipeSettings()

        assertEquals(emptyList<DeviceDataWipe.Leftover>(), fixture.wipe.leftovers())
        assertEquals(DeviceDataWipe.Inventory(), fixture.wipe.inventory())
        for (gone in WipeFixture.ACCOUNT_PATHS) assertFalse("$gone survived the wipe", fixture.exists(gone))
        for (kept in WipeFixture.KEPT_PATHS) assertTrue("$kept should have been kept", fixture.exists(kept))
        assertTrue(fixture.aliases.aliases.isEmpty())
        assertEquals(
            setOf("shroud.server/shroud.server.configuration", "shroud.voice/transcription.model", "shroud.voice/transcription.whispercpp.ready"),
            fixture.prefs.storedKeys(),
        )
        // The directories the system owns stay; their contents go.
        assertTrue(File(temp.root, "cache").isDirectory)
        assertTrue(File(temp.root, "app_webview").isDirectory)
    }

    @Test
    fun verifyNamesTheStepThatMissedSomething() = runTest {
        val fixture = WipeFixture(temp.root)
        fixture.wipe.wipeEverything()
        assertTrue(fixture.wipe.leftovers().isEmpty())

        // A store added later that the wipe does not know about still shows up.
        fixture.write("no_backup/shroud/plaintext/late.sealed")
        fixture.write("files/new-feature/cache.json")
        fixture.aliases.aliases += "late"
        fixture.prefs.put("shroud.preferences", "someFeature.flag", true)

        val found = fixture.wipe.leftovers().map { it.step }.toSet()
        assertEquals(setOf(WipeStep.Messages, WipeStep.Keys, WipeStep.Settings), found)

        fixture.wipe.wipeEverything()
        assertTrue(fixture.wipe.leftovers().isEmpty())
    }

    @Test
    fun aKeystoreThatCannotBeListedIsNotClean() = runTest {
        val fixture = WipeFixture(temp.root)
        fixture.aliases.listFails = true
        assertEquals(listOf(DeviceDataWipe.Leftover(WipeStep.Keys, "encryption keys")), fixture.wipe.leftovers())
        assertEquals(0, fixture.wipe.inventory().keys)
    }

    @Test
    fun leftoverLabelsNameEachStep() = runTest {
        val fixture = WipeFixture(temp.root)
        fixture.seedAccount()
        assertEquals(
            listOf(
                DeviceDataWipe.Leftover(WipeStep.Messages, "messages"),
                DeviceDataWipe.Leftover(WipeStep.Media, "media and cached files"),
                DeviceDataWipe.Leftover(WipeStep.Keys, "encryption keys"),
                DeviceDataWipe.Leftover(WipeStep.Settings, "settings"),
            ),
            fixture.wipe.leftovers(),
        )
    }

    @Test
    fun pendingMarkerOutlivesTheSettingsStepUntilCleared() = runTest {
        val fixture = WipeFixture(temp.root)
        fixture.wipe.markPending()
        fixture.wipe.wipeSettings()
        assertTrue(fixture.wipe.isPending)
        assertTrue(fixture.wipe.leftovers().isEmpty())
        fixture.wipe.clearPending()
        assertFalse(fixture.wipe.isPending)
        assertEquals(emptySet<String>(), fixture.prefs.storedKeys())
    }

    @Test
    fun keepListIsNarrow() {
        val keep = WipeFixture(temp.root).keepList
        assertTrue(keep.keepsPrefsKey(PrefsFiles.SERVER, "shroud.server.configuration"))
        assertTrue(keep.keepsPrefsKey(PrefsFiles.VOICE, "transcription.model"))
        assertTrue(keep.keepsPrefsKey(PrefsFiles.VOICE, "transcription.whispercpp.ready"))
        assertTrue(keep.keepsPrefsKey(PrefsFiles.WIPE, DeviceDataWipe.PENDING_KEY))
        assertTrue(keep.keepsPrefsKey(PrefsFiles.DEVICE, "notifications.permissionAsked"))
        assertFalse(keep.keepsPrefsKey(PrefsFiles.VOICE, "transcription.locale"))
        assertFalse(keep.keepsPrefsKey(PrefsFiles.VOICE, "transcription.languageStats"))
        assertFalse(keep.keepsPrefsKey(PrefsFiles.PREFERENCES, "security.autoLockDelay"))
        assertFalse(keep.keepsPrefsKey(PrefsFiles.PREFERENCES, "privacy.generatesLinkPreviews"))
        assertFalse(keep.keepsPrefsKey(PrefsFiles.NOTIFICATIONS, "notifications.sound"))
        assertFalse(keep.keepsPrefsKey(PrefsFiles.APPEARANCE, ColorThemePreference.KEY))
        assertFalse(keep.keepsPrefsKey(PrefsFiles.PUSH, "push.backgroundConnection"))
        // A kept key is kept only in its own file.
        assertFalse(keep.keepsPrefsKey(PrefsFiles.PREFERENCES, "shroud.server.configuration"))
    }

    @Test
    fun theSweepNeverFollowsALink() = runTest {
        val fixture = WipeFixture(temp.root)
        val outside = File(temp.root, "outside/precious.bin").apply { parentFile?.mkdirs(); writeBytes(ByteArray(4)) }
        File(temp.root, "files").mkdirs()
        Files.createSymbolicLink(File(temp.root, "files/link").toPath(), outside.parentFile!!.toPath())

        fixture.wipe.wipeSettings()

        assertFalse(Files.exists(File(temp.root, "files/link").toPath(), java.nio.file.LinkOption.NOFOLLOW_LINKS))
        assertTrue(outside.exists())
    }

    @Test
    fun theSystemStateIsPartOfTheSteps() = runTest {
        val system = FakeSystemWipe()
        val fixture = WipeFixture(temp.root, system)
        fixture.wipe.wipeMedia()
        fixture.wipe.wipeSettings()
        assertEquals(listOf("evict", "cancelWork", "clearSystem"), system.calls)
        system.stuck = listOf(DeviceDataWipe.Leftover(WipeStep.Settings, "notifications"))
        assertEquals(system.stuck, fixture.wipe.leftovers())
    }

    @Test
    fun mediaSummaryReadsLikeTheDesign() {
        assertTrue(DeviceDataWipe.Inventory(mediaFiles = 37, mediaBytes = 18_200_000).mediaSummary.startsWith("37 files · "))
        assertEquals("1 file", DeviceDataWipe.Inventory(mediaFiles = 1, mediaBytes = 0).mediaSummary)
        assertEquals("37 files · 18.2 MB", DeviceDataWipe.Inventory(mediaFiles = 37, mediaBytes = 18_200_000).mediaSummary { DeviceDataWipe.fileSize(it, Locale.US) })
    }

    /** iOS `ByteCountFormatter` (`.file`) vectors (`ByteCountLabelTest`), plan C34; review W2. */
    @Test
    fun fileSizesReadLikeByteCountFormatter() {
        assertEquals("1 byte", DeviceDataWipe.fileSize(1, Locale.US))
        assertEquals("512 bytes", DeviceDataWipe.fileSize(512, Locale.US))
        assertEquals("999 bytes", DeviceDataWipe.fileSize(999, Locale.US))
        assertEquals("1 KB", DeviceDataWipe.fileSize(1_000, Locale.US))
        assertEquals("7 KB", DeviceDataWipe.fileSize(6672, Locale.US))
        // Foundation rounds half up and grows the unit; the old DecimalFormat port said 2 KB / 1000 KB / 1.2 MB.
        assertEquals("3 KB", DeviceDataWipe.fileSize(2_500, Locale.US))
        assertEquals("1 MB", DeviceDataWipe.fileSize(999_999, Locale.US))
        assertEquals("1 MB", DeviceDataWipe.fileSize(999_600, Locale.US))
        assertEquals("1.3 MB", DeviceDataWipe.fileSize(1_250_000, Locale.US))
        assertEquals("18.2 MB", DeviceDataWipe.fileSize(18_200_000, Locale.US))
        assertEquals("18 MB", DeviceDataWipe.fileSize(18_000_000, Locale.US))
        assertEquals("1.52 GB", DeviceDataWipe.fileSize(1_520_000_000, Locale.US))
        assertEquals("18,2 MB", DeviceDataWipe.fileSize(18_200_000, Locale.GERMANY))
    }
}
