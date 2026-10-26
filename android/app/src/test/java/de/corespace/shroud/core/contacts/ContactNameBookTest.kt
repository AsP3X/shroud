package de.corespace.shroud.core.contacts

import de.corespace.shroud.core.storage.SealResult
import de.corespace.shroud.core.storage.SealedFile
import de.corespace.shroud.core.storage.Sealer
import de.corespace.shroud.core.storage.StorageSeal
import de.corespace.shroud.testing.FakeSharedPreferences
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
import java.util.concurrent.Executor

/**
 * The contact username book (security audit M9): sealed at rest like the notification names, an
 * older build's plaintext preferences moved into it, gone at Log Out, and never written over while
 * the record cannot be read.
 */
class ContactNameBookTest {
    @get:Rule val temp = TempDirRule()

    private val owner = UUID.fromString("8f14e45f-ceea-467a-9575-3a6b7a1e6c0e")
    private val alice = UUID.fromString("5f0c3a52-7b1e-4c6d-9a8b-2e4f6d8c0a1b")
    private val bob = UUID.fromString("0b1c2d3e-4f50-4617-8293-a4b5c6d7e8f9")
    private val seal = StorageSeal()
    private val prefs = FakeSharedPreferences()
    private var keyDeletes = 0
    private val file: File get() = File(temp.noBackupFilesDir, ContactNameBook.FILE_NAME)

    private fun book(sealer: Sealer = XorSealer(), legacy: FakeSharedPreferences? = prefs) = ContactNameBook(
        file = SealedFile(file, sealer),
        deleteKey = { keyDeletes++ },
        seal = seal,
        legacy = legacy,
        writer = Direct,
    )

    @Test
    fun namesAndFingerprintsSurviveARestartSealed() {
        val book = book()
        book.remember(owner, alice, "alice")
        book.remember(owner, bob, "bob_2")
        book.rememberPublished(owner, alice, "fp/alice+=")
        assertEquals("alice", book.name(owner, alice))

        val bytes = file.readBytes()
        assertFalse("never in the clear", String(bytes, Charsets.ISO_8859_1).contains("alice"))
        assertTrue("nothing in the preferences", prefs.keys.isEmpty())
        val restarted = book()
        assertEquals("alice", restarted.name(owner, alice))
        assertEquals("bob_2", restarted.name(owner, bob))
        assertEquals("fp/alice+=", restarted.published(owner, alice))
        assertNull(restarted.published(owner, bob))
        assertEquals(mapOf(alice.toString() to "alice", bob.toString() to "bob_2"), restarted.all(owner))
        assertTrue(restarted.all(UUID.randomUUID()).isEmpty())
    }

    @Test
    fun onlyUsernamesAreKept() {
        val book = book()
        book.remember(owner, alice, "Alice")
        book.remember(owner, bob, "x")
        book.rememberPublished(owner, bob, "")
        assertNull(book.name(owner, alice))
        assertNull(book.name(owner, bob))
        assertNull(book.published(owner, bob))
        assertFalse(file.exists())
    }

    /** The plaintext preferences of an older build move into the record, and their keys go. */
    @Test
    fun anOlderBuildsNamesMoveIntoTheRecord() {
        val legacy = FakeSharedPreferences(
            mapOf(
                "contact.names.$owner" to """{"$alice":"alice","$bob":"bob","not-an-id":"carol","${UUID.randomUUID()}":"NO"}""",
                // org.json escapes '/' in what it wrote.
                "contact.names.published.$owner" to """{"$alice":"fp\/a"}""",
                "contact.names.not-an-owner" to """{"$alice":"alice"}""",
                "security.autoLockDelay" to 60,
            ),
        )
        // The record wins over the older build where both name a peer.
        book(legacy = null).remember(owner, bob, "bobby")

        val book = book(legacy = legacy)
        assertEquals("alice", book.name(owner, alice))
        assertEquals("bobby", book.name(owner, bob))
        assertEquals("fp/a", book.published(owner, alice))
        assertEquals(setOf("security.autoLockDelay"), legacy.keys)

        val restarted = book(legacy = FakeSharedPreferences())
        assertEquals("alice", restarted.name(owner, alice))
        assertEquals("bobby", restarted.name(owner, bob))
        assertEquals("fp/a", restarted.published(owner, alice))
    }

    @Test
    fun preloadMovesAnOlderBuildsNamesAtProcessStart() {
        prefs.edit().putString("contact.names.$owner", """{"$alice":"alice"}""").apply()
        book().preload()
        assertTrue(prefs.keys.isEmpty())
        assertEquals("alice", book(legacy = null).name(owner, alice))
    }

    @Test
    fun retainDropsFormerContactsNamesAndFingerprints() {
        val book = book()
        book.remember(owner, alice, "alice")
        book.remember(owner, bob, "bob")
        book.rememberPublished(owner, bob, "fp")
        book.retain(owner, listOf(alice))
        assertEquals("alice", book.name(owner, alice))
        assertNull(book.name(owner, bob))
        assertNull(book.published(owner, bob))
        assertNull(book().name(owner, bob))
        assertEquals("alice", book().name(owner, alice))
    }

    /** Log Out / removal: memory, the record, its key and an older build's keys. */
    @Test
    fun forgetRemovesEverything() {
        val book = book()
        book.remember(owner, alice, "alice")
        prefs.edit().putString("contact.names.published.$owner", "{}").putString("privacy.x", "kept").apply()
        assertTrue(file.exists())
        book.forget()
        assertFalse(file.exists())
        assertEquals(1, keyDeletes)
        assertEquals(setOf("privacy.x"), prefs.keys)
        assertNull(book.name(owner, alice))
        assertNull(book().name(owner, alice))
        // The next account's names are written without the last one's.
        book.remember(owner, bob, "bob")
        assertNull(book().name(owner, alice))
        assertEquals("bob", book().name(owner, bob))
    }

    /** A running wipe writes nothing (crypto §14). */
    @Test
    fun aSealedStoreWritesNothing() {
        val book = book()
        seal.seal()
        book.remember(owner, alice, "alice")
        assertFalse(file.exists())
    }

    /**
     * A record that cannot be read now (phone locked, Keystore hiccup) is never written over, and an
     * older build's keys stay. What is learnt meanwhile shows, and reaches the record once it reads.
     */
    @Test
    fun anUnreadableRecordIsKeptAndMergedOnceItReads() {
        book(legacy = null).remember(owner, alice, "alice")
        val before = file.readBytes()
        prefs.edit().putString("contact.names.$owner", """{"${UUID.randomUUID()}":"carol"}""").apply()
        val sealer = LockableSealer()
        val book = book(sealer)
        assertNull(book.name(owner, alice))
        book.remember(owner, bob, "bob")
        assertEquals("bob", book.name(owner, bob))
        assertArrayEquals(before, file.readBytes())
        assertEquals(1, prefs.keys.size)

        sealer.locked = false
        assertEquals("alice", book.name(owner, alice))
        assertEquals("bob", book.name(owner, bob))
        assertTrue(prefs.keys.isEmpty())
        val restarted = book()
        assertEquals("alice", restarted.name(owner, alice))
        assertEquals("bob", restarted.name(owner, bob))
        assertEquals(3, restarted.all(owner).size)
    }

    /** `MutualContactNames.forget` is the book's. */
    @Test
    fun theExchangeForgetsTheBook() {
        val book = book()
        book.remember(owner, alice, "alice")
        val names = MutualContactNames(
            book = book,
            owner = { owner },
            username = { null },
            keys = { null },
            peerKey = { error("unused") },
            publish = { _, _, _ -> },
        )
        names.forget()
        assertFalse(file.exists())
        assertNull(book.name(owner, alice))
    }

    private class LockableSealer : Sealer {
        var locked = true
        private val xor = XorSealer()

        override fun seal(plaintext: ByteArray): ByteArray = xor.seal(plaintext)

        override fun open(sealed: ByteArray): ByteArray {
            check(!locked) { "locked" }
            return xor.open(sealed)
        }

        override fun openClassified(sealed: ByteArray): SealResult =
            if (locked) SealResult.DeviceLocked else SealResult.Opened(xor.open(sealed))
    }

    private object Direct : Executor {
        override fun execute(command: Runnable) = command.run()
    }
}
