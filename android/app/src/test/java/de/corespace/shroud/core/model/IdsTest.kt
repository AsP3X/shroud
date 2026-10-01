package de.corespace.shroud.core.model

import de.corespace.shroud.core.auth.Session
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

/** Plan §1.7.1 and conflict C1: strict parsing, lower-case wire form, string order. */
class IdsTest {
    @Test
    fun canonicalIdsParseInAnyCaseAndTrimmed() {
        val lower = "8f14e45f-ceea-467a-9575-3a6b7a1e6c0e"
        assertEquals(UUID.fromString(lower), Ids.parse(lower))
        assertEquals(UUID.fromString(lower), Ids.parse("8F14E45F-CEEA-467A-9575-3A6B7A1E6C0E"))
        assertEquals(UUID.fromString(lower), Ids.parse("  8F14e45f-CEEA-467a-9575-3A6B7A1E6C0E\n"))
    }

    @Test
    fun upperCaseInputIsWrittenLowerCase() {
        // iOS writes `uuidString.lowercased()` on the wire and on disk.
        val id = Ids.require("ABCDEF01-2345-4678-9ABC-DEF012345678")
        assertEquals("abcdef01-2345-4678-9abc-def012345678", Ids.wire(id))
        assertEquals(Ids.wire(id), id.toString())
    }

    @Test
    fun lenientShapesThatUuidFromStringAcceptsAreRejected() {
        // UUID.fromString reads this as 00000001-0001-0001-0001-000000000001; iOS UUID(uuidString:) refuses it.
        assertEquals(UUID.fromString("00000001-0001-0001-0001-000000000001"), UUID.fromString("1-1-1-1-1"))
        assertNull(Ids.parse("1-1-1-1-1"))
        assertNull(Ids.parse("8f14e45f-ceea-467a-9575-3a6b7a1e6c0"))          // 11 digits in the last group
        assertNull(Ids.parse("8f14e45f-ceea-467a-9575-3a6b7a1e6c0e0"))        // 13
        assertNull(Ids.parse("8f14e45fceea467a95753a6b7a1e6c0e"))             // no dashes
        assertNull(Ids.parse("{8f14e45f-ceea-467a-9575-3a6b7a1e6c0e}"))
        assertNull(Ids.parse("8f14e45f-ceea-467a-9575-3a6b7a1e6c0g"))         // not hex
        assertNull(Ids.parse("8f14e45f-ceea-467a-9575_3a6b7a1e6c0e"))
        assertNull(Ids.parse(""))
        assertNull(Ids.parse("   "))
        assertNull(Ids.parse(null))
        assertNull(Ids.parse("d1"))
    }

    @Test
    fun requireThrowsWithoutRepeatingTheInput() {
        val error = assertThrows(IllegalArgumentException::class.java) { Ids.require("secret-token-value") }
        assertFalse(error.message.orEmpty().contains("secret-token-value"))
    }

    @Test
    fun precedesComparesWireStringsNotSignedLongs() {
        // MessageCrypto.swift:155 — the initiator is the lower lower-case uuid string.
        val low = Ids.require("7fffffff-ffff-4fff-bfff-ffffffffffff")
        val high = Ids.require("80000000-0000-4000-8000-000000000000")
        // UUID.compareTo compares the most significant 64 bits as a signed long: 0x8000… is negative.
        assertTrue(high < low)
        // The wire rule says the opposite, as iOS and the web do.
        assertTrue(Ids.precedes(low, high))
        assertFalse(Ids.precedes(high, low))
        assertFalse(Ids.precedes(low, low))
    }

    @Test
    fun precedesIgnoresTheCaseTheIdWasReadIn() {
        val a = Ids.require("ABCDEF00-0000-4000-8000-000000000000")
        val b = Ids.require("abcdef00-0000-4000-8000-000000000001")
        assertTrue(Ids.precedes(a, b))
        assertFalse(Ids.precedes(b, a))
    }

    @Test
    fun sessionIdsReadBackAsUuids() {
        val session = Session("tok", "8f14e45f-ceea-467a-9575-3a6b7a1e6c0e", "noah", null, "2e6f9b0c-1d3a-4e5b-8c7d-9f0a1b2c3d4e")
        assertEquals(UUID.fromString("8f14e45f-ceea-467a-9575-3a6b7a1e6c0e"), session.userUuid)
        assertEquals(UUID.fromString("2e6f9b0c-1d3a-4e5b-8c7d-9f0a1b2c3d4e"), session.deviceUuid)
    }
}
