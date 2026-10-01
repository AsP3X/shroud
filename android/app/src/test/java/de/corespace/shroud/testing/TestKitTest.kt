package de.corespace.shroud.testing

import android.content.SharedPreferences
import de.corespace.shroud.core.storage.SealedFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.time.Instant

/** The shared JVM test kit behaves like the platform pieces it stands in for. */
class TestKitTest {
    @get:Rule val main = MainDispatcherRule()

    @get:Rule val temp = TempDirRule()

    @Test
    fun prefsStoreReadAndRemoveEveryType() {
        val prefs = FakeSharedPreferences()
        prefs.edit()
            .putString("s", "x")
            .putInt("i", 7)
            .putLong("l", 8L)
            .putFloat("f", 1.5f)
            .putBoolean("b", true)
            .putStringSet("set", setOf("a", "b"))
            .apply()
        assertEquals("x", prefs.getString("s", null))
        assertEquals(7, prefs.getInt("i", 0))
        assertEquals(8L, prefs.getLong("l", 0L))
        assertEquals(1.5f, prefs.getFloat("f", 0f), 0f)
        assertTrue(prefs.getBoolean("b", false))
        assertEquals(setOf("a", "b"), prefs.getStringSet("set", null))
        assertEquals(6, prefs.all.size)

        prefs.edit().remove("s").putString("i2", null).putStringSet("set", null).commit()
        assertFalse(prefs.contains("s"))
        assertFalse(prefs.contains("set"))
        assertEquals("d", prefs.getString("s", "d"))
        assertEquals(setOf("i", "l", "f", "b"), prefs.keys)
    }

    @Test(expected = ClassCastException::class)
    fun prefsReadAsTheWrongTypeThrowsLikeTheDevice() {
        val prefs = FakeSharedPreferences(mapOf("n" to 1))
        prefs.getString("n", null)
    }

    @Test
    fun prefsClearRunsBeforeTheEditorsPuts() {
        val prefs = FakeSharedPreferences(mapOf("old" to "1", "keep" to "2"))
        prefs.edit().putString("keep", "3").clear().apply()
        assertEquals(setOf("keep"), prefs.keys)
        assertEquals("3", prefs.getString("keep", null))
    }

    @Test
    fun prefsListenersHearClearThenChangedKeysOnly() {
        val prefs = FakeSharedPreferences(mapOf("a" to "1"))
        val heard = ArrayList<String?>()
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key -> heard += key }
        prefs.registerOnSharedPreferenceChangeListener(listener)

        prefs.edit().putString("a", "1").apply() // equal value: no change
        assertEquals(emptyList<String?>(), heard)
        prefs.edit().putString("a", "2").putString("b", "x").apply()
        assertEquals(listOf("b", "a"), heard) // last modified first, as SharedPreferencesImpl
        heard.clear()
        prefs.edit().clear().putString("c", "y").apply()
        assertEquals(listOf(null, "c"), heard)
        heard.clear()
        prefs.edit().remove("missing").apply()
        assertEquals(emptyList<String?>(), heard)

        prefs.unregisterOnSharedPreferenceChangeListener(listener)
        prefs.edit().putString("d", "z").apply()
        assertEquals(emptyList<String?>(), heard)
        assertEquals(5, prefs.editCount)
    }

    @Test
    fun prefsStringSetsAreCopies() {
        val source = mutableSetOf("a")
        val prefs = FakeSharedPreferences()
        prefs.edit().putStringSet("set", source).apply()
        source += "b"
        assertEquals(setOf("a"), prefs.getStringSet("set", null))
    }

    @Test
    fun clockMovesWallAndUptimeTogetherUnlessTheWallIsSet() {
        val clock = FakeAppClock()
        assertEquals(Instant.parse("2026-09-21T14:13:20Z"), clock.now())
        val elapsed = clock.elapsedMillis()
        clock.advanceBy(60_000)
        assertEquals(FakeAppClock.START_WALL_MILLIS + 60_000, clock.nowMillis())
        assertEquals(elapsed + 60_000, clock.elapsedMillis())
        clock.setWall(0)
        assertEquals(0L, clock.nowMillis())
        assertEquals(elapsed + 60_000, clock.elapsedMillis())
    }

    @Test
    fun xorSealerRoundTripsThroughASealedFile() {
        val file = temp.file("nested/record.sealed")
        val record = SealedFile(file, XorSealer())
        record.write("token".toByteArray())
        assertFalse(file.readBytes().contentEquals("token".toByteArray()))
        assertArrayEquals("token".toByteArray(), record.read())
        assertEquals(listOf("nested/record.sealed"), temp.listFiles())
        record.delete()
        assertNull(record.read())
    }

    @Test
    fun sealedTestKeyIsAFreshCopyOf0x5A() {
        val key = SealedTestKey.bytes()
        assertEquals(32, key.size)
        assertTrue(key.all { it == 0x5A.toByte() })
        assertEquals(SealedTestKey.HEX, key.joinToString("") { "%02x".format(it) })
        key.fill(0)
        assertTrue(SealedTestKey.bytes().all { it == 0x5A.toByte() })
    }

    @Test
    fun tempDirsAreCreated() {
        assertTrue(temp.noBackupFilesDir.isDirectory)
        assertTrue(temp.cacheDir.isDirectory)
        assertTrue(temp.root.isDirectory)
    }

    @Test
    fun mainDispatcherRuleReplacesMain() = runTest(main.dispatcher) {
        // Without the rule the JVM has no main looper and Dispatchers.Main fails.
        assertEquals(42, withContext(Dispatchers.Main.immediate) { 42 })
    }
}
