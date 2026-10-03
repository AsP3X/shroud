package de.corespace.shroud.core.crypto

import de.corespace.shroud.core.crypto.CryptoFixtures.sequence
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.security.MessageDigest
import java.util.UUID

/**
 * The shared contact-name book (web `src/crypto/contactBook.selftest.ts`, iOS
 * `ContactNameBookTests.swift`). The pinned digest is shared with both: change all three or none.
 */
class ContactNameBookSealTest {
    private val historyKey = sequence(0x01, 32)
    private val owner: UUID = UUID.fromString("0190A3B4-0000-7000-8000-000000000001")
    private val names = mapOf(
        "0190a3b4-0000-7000-8000-0000000000aa" to "alice",
        "0190a3b4-0000-7000-8000-0000000000bb" to "bob_2",
    )

    @Test
    fun sealsTheSameBytesAsTheWebAndIos() {
        val sealed = ContactNameBookSeal.seal(names, owner, historyKey, sequence(0xA0, 12))
        assertEquals(1404, sealed.length)
        val digest = MessageDigest.getInstance("SHA-256").digest(sealed.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        assertEquals("b346b39ba8ba513d4ee111f8a44ae5441df440965245bb7a5e0157bbbfe158e8", digest)
        assertEquals(names, ContactNameBookSeal.open(sealed, owner, historyKey))
    }

    @Test
    fun anotherAccountsBookDoesNotOpen() {
        val sealed = ContactNameBookSeal.seal(names, owner, historyKey)
        assertNull(ContactNameBookSeal.open(sealed, UUID.fromString("0190A3B4-0000-7000-8000-000000000002"), historyKey))
    }

    @Test
    fun malformedEntriesAreDropped() {
        val sealed = ContactNameBookSeal.seal(
            mapOf("not-an-id" to "alice", "0190a3b4-0000-7000-8000-0000000000cc" to "Bad Name"),
            owner,
            historyKey,
        )
        assertEquals(emptyMap<String, String>(), ContactNameBookSeal.open(sealed, owner, historyKey))
    }
}
