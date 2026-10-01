package de.corespace.shroud.core.storage

import de.corespace.shroud.testing.TempDirRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.io.File

/**
 * Decrypted media left in `cacheDir` by a crash must not outlive the next launch, and a lock must
 * not pull the file out from under a recording or playback that is still writing to it
 * (iOS `ios/shroudTests/SensitiveTempFilesTests.swift:6-44`; crypto spec §15). The iOS
 * `testTemporaryDirectoryIsProtectedAfterLaunchPrep` has no Android equivalent (no
 * `completeUnlessOpen`, crypto §15, R10).
 */
class SensitiveTempFilesTest {
    @get:Rule
    val temp = TempDirRule()

    private val dir: File get() = temp.cacheDir
    private val files by lazy { SensitiveTempFiles(dir) }

    private fun make(name: String, modifiedMs: Long = System.currentTimeMillis()): File =
        File(dir, name).apply {
            writeText("plaintext")
            setLastModified(modifiedMs)
        }

    /** `testLaunchSweepRemovesEveryShroudFileAndNothingElse` (`SensitiveTempFilesTests.swift:26-34`). */
    @Test
    fun launchSweepRemovesEveryShroudFileAndNothingElse() {
        val leftover = make("shroud-test-new.mp4")
        val unrelated = make("unrelated-test-file")
        files.prepareAtLaunch()
        assertFalse(leftover.exists())
        assertTrue(unrelated.exists())
    }

    /** `testLockSweepKeepsFilesStillInUse` (`SensitiveTempFilesTests.swift:36-44`), with the 600 s lock age. */
    @Test
    fun lockSweepKeepsFilesStillInUse() {
        val now = System.currentTimeMillis()
        val stale = make("shroud-test-old.mp4", modifiedMs = now - 3_600_000)
        val live = make("shroud-test-new.mp4", modifiedMs = now)
        files.sweep(olderThanMs = 600_000, nowMs = now)
        assertFalse(stale.exists())
        assertTrue(live.exists())
    }

    @Test
    fun theLockAgeIsTenMinutesInclusive() {
        val now = 10_000_000_000L
        val atTheAge = make("shroud-a.m4a", modifiedMs = now - SensitiveTempFiles.STALE_AGE_MS)
        val justYounger = make("shroud-b.m4a", modifiedMs = now - SensitiveTempFiles.STALE_AGE_MS + 1_000)
        files.sweep(olderThanMs = SensitiveTempFiles.STALE_AGE_MS, nowMs = now)
        assertFalse(atTheAge.exists())
        assertTrue(justYounger.exists())
        assertEquals(600_000L, SensitiveTempFiles.STALE_AGE_MS)
    }

    @Test
    fun sweepRemovesShroudDirectoriesToo() {
        val nested = File(dir, "shroud-share-1/photo.jpg").apply {
            parentFile!!.mkdirs()
            writeText("x")
        }
        files.sweep()
        assertFalse(nested.parentFile!!.exists())
    }

    @Test
    fun createMakesAPrefixedEmptyFileOnce() {
        val a = files.create("voice", "m4a")
        val b = files.create("shroud-voice", ".m4a")
        assertTrue(a.name.startsWith("shroud-voice-") && a.name.endsWith(".m4a"))
        assertTrue(b.name.startsWith("shroud-voice-") && !b.name.startsWith("shroud-shroud-"))
        assertTrue(a.exists() && a.length() == 0L)
        assertFalse(a == b)
        val bare = files.create("tx", "")
        assertFalse(bare.name.contains('.'))
        files.sweep()
        assertFalse(a.exists() || b.exists() || bare.exists())
    }

    @Test
    fun createRefusesStemsThatCouldEscapeTheDirectory() {
        for ((stem, ext) in listOf("../x" to "mp4", "a/b" to "mp4", "" to "mp4", "ok" to "m/p4")) {
            try {
                files.create(stem, ext)
                throw AssertionError("accepted $stem.$ext")
            } catch (_: IllegalArgumentException) {
            }
        }
    }

    @Test
    fun sweepingAMissingDirectoryDoesNothing() {
        SensitiveTempFiles(File(temp.root, "absent")).sweep()
    }
}
