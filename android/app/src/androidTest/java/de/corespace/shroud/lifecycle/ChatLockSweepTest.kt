package de.corespace.shroud.lifecycle

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import de.corespace.shroud.ShroudApplication
import de.corespace.shroud.core.storage.SensitiveTempFiles
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * The chat lock sweeps stale plaintext temp files (`lockChatsInMemory`, `RootView.swift:315-320`;
 * plan §1.1 rule 7, §1.5 "swept at start and on lock (10 min rule)"): a `shroud-*` file nobody
 * wrote to for more than ten minutes is gone after the lock, a fresh one (a recording in progress)
 * and files without the prefix stay.
 */
@RunWith(AndroidJUnit4::class)
class ChatLockSweepTest {
    private val app = ApplicationProvider.getApplicationContext<ShroudApplication>()

    @Test
    fun theLockSweepsStaleTempFilesAndKeepsFreshOnes() = runBlocking {
        val temp = app.container.keys.sensitiveTempFiles
        val stale = temp.create("voice", "m4a")
        val fresh = temp.create("voice", "m4a")
        val unrelated = File(app.cacheDir, "not-ours.tmp").apply { writeText("x") }
        try {
            assertTrue(stale.setLastModified(System.currentTimeMillis() - SensitiveTempFiles.STALE_AGE_MS - 60_000))

            withContext(Dispatchers.Main.immediate) { app.container.lockChatsInMemory() }

            assertFalse("a stale shroud-* file outlived the lock", stale.exists())
            assertTrue("a fresh take was swept", fresh.exists())
            assertTrue(unrelated.exists())
        } finally {
            stale.delete()
            fresh.delete()
            unrelated.delete()
        }
    }
}
