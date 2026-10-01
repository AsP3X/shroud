package de.corespace.shroud.core.notifications

import de.corespace.shroud.core.net.wire.Icu4jTextUnitsRule
import de.corespace.shroud.core.storage.SealedFile
import de.corespace.shroud.core.storage.Sealer
import de.corespace.shroud.core.storage.SealResult
import de.corespace.shroud.core.storage.StorageSeal
import de.corespace.shroud.testing.TempDirRule
import de.corespace.shroud.testing.XorSealer
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.util.UUID

/**
 * The AFU name cache of the background connection (00-plan §1.5, §1.7.10; `NotificationNameCacheTest`
 * of the W2-NOTIF card): sealed at rest, names only while Show Sender is on, gone at Log Out.
 */
class NotificationNameCacheTest {
    @get:Rule val temp = TempDirRule()
    @get:Rule val icu = Icu4jTextUnitsRule()

    private val alice = UUID.fromString("5f0c3a52-7b1e-4c6d-9a8b-2e4f6d8c0a1b")
    private val bob = UUID.fromString("0b1c2d3e-4f50-4617-8293-a4b5c6d7e8f9")
    private val seal = StorageSeal()
    private var namesOn = true
    private var keyDeletes = 0
    private val file: File get() = File(temp.noBackupFilesDir, NotificationNameCache.FILE_NAME)

    private fun cache(sealer: Sealer = XorSealer()) = NotificationNameCache(
        file = SealedFile(file, sealer),
        deleteKey = { keyDeletes++ },
        seal = seal,
        namesOn = { namesOn },
        writer = DirectExecutor,
    )

    @Test
    fun namesSurviveARestartSealed() {
        val cache = cache()
        cache.remember(alice, "alice")
        cache.rememberAll(mapOf(bob to "  bob\n"))
        assertEquals("alice", cache.name(alice))
        assertEquals("bob", cache.name(bob))

        val bytes = file.readBytes()
        assertFalse("never in the clear", String(bytes, Charsets.ISO_8859_1).contains("alice"))
        val restarted = cache()
        assertEquals("alice", restarted.name(alice))
        assertEquals("bob", restarted.name(bob))
        assertNull(restarted.name(UUID.randomUUID()))
    }

    /** `{"<id>":"<name>"}`, keys sorted — one stable record. */
    @Test
    fun theRecordFormat() {
        val encoded = NotificationNameCache.encode(mapOf(alice to "alice", bob to "bob"))
        assertArrayEquals(
            """{"0b1c2d3e-4f50-4617-8293-a4b5c6d7e8f9":"bob","5f0c3a52-7b1e-4c6d-9a8b-2e4f6d8c0a1b":"alice"}""".toByteArray(),
            encoded,
        )
        val decoded = NotificationNameCache.decode(
            """{"${alice}":"alice","1-1-1-1-1":"x","$bob":"  ","00000000-0000-0000-0000-00000000000c":5}""".toByteArray(),
        )
        assertEquals(mapOf(alice to "alice"), decoded)
        assertTrue(NotificationNameCache.decode("garbage".toByteArray()).isEmpty())
    }

    @Test
    fun nothingIsWrittenWhenNothingChanged() {
        val cache = cache()
        cache.remember(alice, "alice")
        val first = file.lastModified()
        file.setLastModified(first - 10_000)
        cache.remember(alice, "alice")
        assertEquals(first - 10_000, file.lastModified())
        cache.remember(alice, "alice2")
        assertEquals("alice2", cache.name(alice))
    }

    /** Names are cut like a push's sender (64 grapheme clusters). */
    @Test
    fun namesAreClamped() {
        val cache = cache()
        cache.remember(alice, "👍🏽".repeat(70))
        assertEquals("👍🏽".repeat(64), cache.name(alice))
        cache.remember(bob, "   ")
        assertNull(cache.name(bob))
    }

    /** Show Sender off: no name is answered, none is kept. */
    @Test
    fun namesOffMeansNoNames() {
        val cache = cache()
        cache.remember(alice, "alice")
        namesOn = false
        assertNull(cache.name(alice))
        cache.remember(bob, "bob")
        namesOn = true
        assertNull(cache.name(bob))
    }

    @Test
    fun deleteAllRemovesTheFileAndTheKey() {
        val cache = cache()
        cache.remember(alice, "alice")
        assertTrue(file.exists())
        cache.deleteAll()
        assertFalse(file.exists())
        assertEquals(1, keyDeletes)
        assertNull(cache.name(alice))
        assertNull(cache().name(alice))
    }

    /** A running wipe writes nothing (crypto §14). */
    @Test
    fun aSealedStoreWritesNothing() {
        val cache = cache()
        seal.seal()
        cache.remember(alice, "alice")
        assertFalse(file.exists())
        assertNull(cache.name(alice))
    }

    /** A record that cannot be read now (phone locked, Keystore hiccup) is never overwritten. */
    @Test
    fun anUnreadableRecordIsKept() {
        cache().remember(alice, "alice")
        val before = file.readBytes()
        val locked = object : Sealer {
            override fun seal(plaintext: ByteArray): ByteArray = XorSealer().seal(plaintext)
            override fun open(sealed: ByteArray): ByteArray = throw IllegalStateException("locked")
            override fun openClassified(sealed: ByteArray): SealResult = SealResult.DeviceLocked
        }
        val cache = cache(locked)
        assertNull(cache.name(alice))
        cache.remember(bob, "bob")
        assertArrayEquals(before, file.readBytes())
        assertEquals("alice", cache().name(alice))
    }
}
