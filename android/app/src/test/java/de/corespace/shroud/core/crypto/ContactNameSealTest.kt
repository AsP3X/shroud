package de.corespace.shroud.core.crypto

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** A username sealed to one contact opens only with a sender tag that proves that contact. */
class ContactNameSealTest {
    private val alice = TestIdentity.random()
    private val bob = TestIdentity.random()
    private val mallory = TestIdentity.random()

    @Test
    fun aSealedNameOpensForThatContact() {
        val sealed = ContactNameSeal.seal("alice_1", alice.private, alice.public, bob.public)
        assertEquals("alice_1", ContactNameSeal.open(sealed, bob.private, bob.public, alice.public))
    }

    @Test
    fun anUntaggedOrForeignBoxIsNoName() {
        val sealed = ContactNameSeal.seal("alice_1", alice.private, alice.public, bob.public)
        val box = CryptoJson.decodeFromString(SealedBox.serializer(), sealed)
        val untagged = CryptoJson.encodeToString(SealedBox.serializer(), box.copy(t = null))
        assertNull(ContactNameSeal.open(untagged, bob.private, bob.public, alice.public))
        // Mallory's box claiming to be Alice's.
        val forged = ContactNameSeal.seal("alice_1", mallory.private, alice.public, bob.public)
        assertNull(ContactNameSeal.open(forged, bob.private, bob.public, alice.public))
        assertNull(ContactNameSeal.open("not json", bob.private, bob.public, alice.public))
    }
}
