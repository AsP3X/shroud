package de.corespace.shroud.core.net.wire

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

/**
 * The hand-rolled reads of the sealed shapes (api-realtime §7): each helper reproduces one Swift
 * `JSONSerialization` + `as?` expression, and [LenientJson.parseObject] refuses what
 * `JSONSerialization` refuses even where kotlinx's tree parser would bend.
 */
class LenientJsonTest {
    private fun el(json: String): JsonElement = Json.parseToJsonElement(json)

    // --- text and objects ---------------------------------------------------------------------------

    @Test
    fun utf8IsStrict() {
        assertEquals("héllo 🔥", LenientJson.utf8OrNull("héllo 🔥".toByteArray()))
        assertNull(LenientJson.utf8OrNull(byteArrayOf(0xC3.toByte(), 0x28)))
        assertNull(LenientJson.utf8OrNull(byteArrayOf(0xED.toByte(), 0xA0.toByte(), 0x80.toByte()))) // an encoded surrogate
        assertEquals("", LenientJson.utf8OrNull(ByteArray(0)))
    }

    @Test
    fun parseObjectTakesStrictJsonObjectsOnly() {
        assertEquals(JsonObject(mapOf("a" to JsonPrimitive(1))), LenientJson.parseObject("""{"a":1}"""))
        assertNotNull(LenientJson.parseObject(" \n{ \"a\" : [true, false, null, -1.5e3, \"x\"] }\t"))
        for (notAnObject in listOf("", "[]", "\"text\"", "1", "null", "{", """{"a":1}}""", """{"a":1,}""")) {
            assertNull(notAnObject, LenientJson.parseObject(notAnObject))
        }
    }

    @Test
    fun parseObjectRefusesWhatJsonSerializationRefuses() {
        // Bare words and malformed numbers as values.
        for (lax in listOf("""{"a":abc}""", """{"a":tru}""", """{"a":01}""", """{"a":NaN}""", """{"a":[1,x]}""", """{"a":.5}""")) {
            assertNull(lax, LenientJson.parseObject(lax))
        }
        // A raw control character inside a string; escaped it is fine.
        assertNull(LenientJson.parseObject("{\"a\":\"x\ty\"}"))
        assertNull(LenientJson.parseObject("{\"a\":\"x\u0000y\"}"))
        assertEquals("x\ty", LenientJson.string(LenientJson.parseObject("""{"a":"x\ty"}""")!!["a"]))
        // A quote escaped inside a string does not end it.
        assertEquals("say \"hi\"\n", LenientJson.string(LenientJson.parseObject("""{"a":"say \"hi\"\n"}""")!!["a"]))
        // Whitespace between tokens may be a raw newline.
        assertNotNull(LenientJson.parseObject("{\n\"a\":\n1\n}"))
    }

    @Test
    fun deepNestingNeverCrashes() {
        val deep = "{\"a\":" + "[".repeat(20_000) + "]".repeat(20_000) + "}"
        // Whatever the parser decides, it must not throw.
        LenientJson.parseObject(deep)
        assertNotNull(LenientJson.parseObject("{\"a\":" + "[".repeat(200) + "]".repeat(200) + "}"))
    }

    @Test
    fun parseObjectOfBytes() {
        assertNotNull(LenientJson.parseObject("""{"t":"x"}""".toByteArray()))
        assertNull(LenientJson.parseObject(byteArrayOf(0x7B, 0xFF.toByte(), 0x7D)))
    }

    // --- field reads --------------------------------------------------------------------------------

    @Test
    fun stringIsAJsonStringOnly() {
        assertEquals(" x ", LenientJson.string(el("\" x \"")))
        assertNull(LenientJson.string(el("1")))
        assertNull(LenientJson.string(el("true")))
        assertNull(LenientJson.string(JsonNull))
        assertNull(LenientJson.string(el("[\"x\"]")))
        assertNull(LenientJson.string(null))
    }

    @Test
    fun trimmedStringDropsBlankOnes() {
        assertEquals("x", LenientJson.trimmedString(el("\" \\n x\\u00a0 \"")))
        assertNull(LenientJson.trimmedString(el("\" \\n\\t\"")))
        assertNull(LenientJson.trimmedString(el("5")))
    }

    @Test
    fun intAcceptsWhatMediaMessagePayloadAccepts() { // MediaModels.swift:185-192
        assertEquals(1500, LenientJson.int(el("1500")))
        assertEquals(1500, LenientJson.int(el("1500.0")))
        assertEquals(1500, LenientJson.int(el("1.5e3")))
        assertEquals(-2, LenientJson.int(el("-2.9")))
        assertEquals(1, LenientJson.int(el("true")))
        assertEquals(0, LenientJson.int(el("false")))
        assertEquals(42, LenientJson.int(el("\"42\"")))
        assertEquals(-7, LenientJson.int(el("\"-7\"")))
        assertEquals(7, LenientJson.int(el("\"+7\"")))
        for (refused in listOf("\" 42\"", "\"4.2\"", "\"\"", "\"0x10\"", "null", "[1]", "{}")) {
            assertNull(refused, LenientJson.int(el(refused)))
        }
        assertNull("outside Int", LenientJson.int(el("3000000000")))
        assertEquals(3_000_000_000L, LenientJson.long(el("3000000000")))
        assertNull(LenientJson.long(el("1e400")))
        assertNull(LenientJson.int(null))
    }

    @Test
    fun numberIgnoresStrings() { // LinkPreview.swift:207-209
        assertEquals(1200, LenientJson.number(el("1200")))
        assertEquals(630, LenientJson.number(el("630.9")))
        assertEquals(1, LenientJson.number(el("true")))
        assertNull(LenientJson.number(el("\"1200\"")))
        assertNull(LenientJson.number(JsonNull))
    }

    @Test
    fun isTrueIsJsonTrueOrTheNumberOne() { // LinkPreview.swift:174-175
        assertTrue(LenientJson.isTrue(el("true")))
        assertTrue(LenientJson.isTrue(el("1")))
        assertTrue(LenientJson.isTrue(el("1.0")))
        for (falsy in listOf("false", "0", "2", "\"true\"", "null", "[true]")) {
            assertFalse(falsy, LenientJson.isTrue(el(falsy)))
        }
        assertFalse(LenientJson.isTrue(null))
    }

    @Test
    fun uuidExactIsUuidUuidString() {
        val id = UUID.fromString("3f2504e0-4f89-41d3-9a0c-0305e82c3301")
        assertEquals(id, LenientJson.uuidExact("3f2504e0-4f89-41d3-9a0c-0305e82c3301"))
        assertEquals(id, LenientJson.uuidExact("3F2504E0-4F89-41D3-9A0C-0305E82C3301"))
        for (refused in listOf(" 3f2504e0-4f89-41d3-9a0c-0305e82c3301", "1-1-1-1-1", "3f2504e04f8941d39a0c0305e82c3301", "", "{3f2504e0-4f89-41d3-9a0c-0305e82c3301}")) {
            assertNull(refused, LenientJson.uuidExact(refused))
        }
    }

    // --- writing ------------------------------------------------------------------------------------

    @Test
    fun sortedKeysSortsEveryObjectRecursively() {
        val tree = el("""{"t":"text","re":{"x":"s","u":"b","k":"text","id":"a"},"c":"body","lp":{"u":"https://x","ab":true,"d":"d"},"list":[{"b":1,"a":2}]}""")
        assertEquals(
            """{"c":"body","list":[{"a":2,"b":1}],"lp":{"ab":true,"d":"d","u":"https://x"},"re":{"id":"a","k":"text","u":"b","x":"s"},"t":"text"}""",
            LenientJson.encode(LenientJson.sortedKeys(tree)),
        )
    }

    @Test
    fun encodeIsCompactAndKeepsSlashesAndUnicode() {
        val obj = JsonObject(mapOf("u" to JsonPrimitive("https://a/b"), "e" to JsonArray(listOf(JsonPrimitive("🔥"))), "q" to JsonPrimitive("\"\n")))
        assertEquals("""{"u":"https://a/b","e":["🔥"],"q":"\"\n"}""", LenientJson.encode(obj))
        assertEquals(LenientJson.encode(obj), String(LenientJson.encodeToBytes(obj), Charsets.UTF_8))
    }
}
