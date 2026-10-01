package de.corespace.shroud.core.crypto

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * The envelope/record JSON configuration (crypto spec §1.4): nil optionals are left out like
 * Swift's `encodeIfPresent`, defaults are not written, unknown keys are ignored.
 */
class CryptoJsonTest {
    @Serializable
    private data class Box(val ek: String, val ct: String, val t: String? = null)

    @Serializable
    private data class Record(val v: Int = 1, val dh: String)

    @Test
    fun nullOptionalsAreLeftOut() {
        val json = CryptoJson.encodeToString(Box.serializer(), Box(ek = "e", ct = "c"))
        assertFalse(json, json.contains("\"t\""))
        assertEquals(setOf("ek", "ct"), keys(json))
        assertEquals(setOf("ek", "ct", "t"), keys(CryptoJson.encodeToString(Box.serializer(), Box("e", "c", "tag"))))
    }

    @Test
    fun defaultsAreLeftOut() {
        assertEquals(setOf("dh"), keys(CryptoJson.encodeToString(Record.serializer(), Record(dh = "d"))))
        assertEquals(setOf("v", "dh"), keys(CryptoJson.encodeToString(Record.serializer(), Record(v = 3, dh = "d"))))
    }

    @Test
    fun unknownKeysAndSwiftEscapesAreRead() {
        // Swift's JSONEncoder writes keys in any order and escapes '/' as "\/".
        val decoded = CryptoJson.decodeFromString(Box.serializer(), """{"x":1,"ct":"a\/b","ek":"e","t":null}""")
        assertEquals(Box(ek = "e", ct = "a/b", t = null), decoded)
    }

    private fun keys(json: String): Set<String> =
        CryptoJson.parseToJsonElement(json).jsonObject.keys
}
