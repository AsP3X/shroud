package de.corespace.shroud.core.net

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.util.UUID

/**
 * `ReactionWriteResult.from` — the port of `MessagesService.reactionWrite`
 * (`MessagesService.swift:119-129`). Vectors verbatim from
 * `MessageReactionTests.reactionWriteAnswersDecode` (`MessageReactionTests.swift:128-153`).
 */
class ReactionWriteResultTest {
    /** The container's API Json (AppContainer; api-realtime §2.7). */
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; explicitNulls = true }

    @Test
    fun aConflictWithACurrentRecordIsAMergeNotAnError() {
        val conflict = """{"error":{"code":"REACTION_CHANGED","message":"Your reaction changed on another device."},"current":{"message_id":"7c9e6679-7425-40de-944b-e07fc1f90ae7","user_id":"3f2504e0-4f89-41d3-9a0c-0305e82c3301","ciphertext":null,"seq":42,"updated_at":"2026-09-23T21:08:35.759754Z"}}"""
        val result = ReactionWriteResult.from(409, conflict, json)
        assertTrue("a 409 with a current record is a merge, not an error", result is ReactionWriteResult.ChangedElsewhere)
        val current = (result as ReactionWriteResult.ChangedElsewhere).current
        assertEquals(42L, current.seq)
        assertNull(current.ciphertext)
        assertEquals(UUID.fromString("7c9e6679-7425-40de-944b-e07fc1f90ae7"), current.messageId)
        assertEquals(UUID.fromString("3f2504e0-4f89-41d3-9a0c-0305e82c3301"), current.userId)
        assertEquals(Instant.parse("2026-09-23T21:08:35.759754Z"), current.updatedAt)
    }

    @Test
    fun aTwoHundredIsSaved() {
        val saved = """{"message_id":"7c9e6679-7425-40de-944b-e07fc1f90ae7","user_id":"3f2504e0-4f89-41d3-9a0c-0305e82c3301","ciphertext":"aGVhcnQ=","seq":43,"updated_at":"2026-09-23T21:08:36Z"}"""
        val result = ReactionWriteResult.from(200, saved, json)
        assertTrue("a 200 is saved", result is ReactionWriteResult.Saved)
        val dto = (result as ReactionWriteResult.Saved).reaction
        assertEquals(43L, dto?.seq)
        assertEquals("aGVhcnQ=", dto?.ciphertext)
    }

    @Test
    fun aNoContentOrEmptyAnswerIsSavedWithNothing() {
        assertEquals(ReactionWriteResult.Saved(null), ReactionWriteResult.from(204, "", json))
        // A 2xx with an empty body is the same (`guard status != 204, !data.isEmpty`).
        assertEquals(ReactionWriteResult.Saved(null), ReactionWriteResult.from(200, "", json))
    }

    @Test
    fun anythingElseStaysAnErrorAConflictWithoutARecordIncluded() {
        val notFound = assertThrows(ApiError::class.java) {
            ReactionWriteResult.from(404, """{"error":{"code":"NOT_FOUND","message":"Message not found."}}""", json)
        } as ApiError.Server
        assertEquals(ErrorCodes.NOT_FOUND, notFound.code)
        assertEquals("Message not found.", notFound.userMessage)
        assertEquals(404, notFound.status)

        val retry = assertThrows(ApiError::class.java) {
            ReactionWriteResult.from(409, """{"error":{"code":"REACTION_CHANGED","message":"Try again."}}""", json)
        } as ApiError.Server
        assertEquals(ErrorCodes.REACTION_CHANGED, retry.code)
        assertEquals("Try again.", retry.userMessage)
        assertEquals(409, retry.status)

        // A conflict whose record does not decode is no merge either.
        assertThrows(ApiError::class.java) {
            ReactionWriteResult.from(409, """{"error":{"code":"REACTION_CHANGED","message":"Try again."},"current":{"seq":"x"}}""", json)
        }
        // A 401 is an auth failure like any other answer.
        val unauthorized = assertThrows(ApiError::class.java) { ReactionWriteResult.from(401, "", json) }
        assertTrue(unauthorized.isUnauthorized)
    }

    @Test
    fun aTwoHundredThatDoesNotDecodeIsADecodingError() {
        assertThrows(ApiError.Decoding::class.java) { ReactionWriteResult.from(200, """{"seq":43}""", json) }
        assertThrows(ApiError.Decoding::class.java) { ReactionWriteResult.from(200, "<html>", json) }
    }
}
