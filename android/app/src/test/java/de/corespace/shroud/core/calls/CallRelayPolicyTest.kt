package de.corespace.shroud.core.calls

import de.corespace.shroud.core.net.IceServerDto
import kotlinx.serialization.json.Json
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * "Always relay calls": relayed when the server has a relay, refused when it has none, and direct
 * paths first when the switch is off — iOS `CallRelayPolicyTests`
 * (`ios/shroudTests/CallRelayPolicyTests.swift`, all 4).
 */
class CallRelayPolicyTest {
    private val json = Json { ignoreUnknownKeys = true }
    private val turn = server("""{"urls": ["turn:turn.example:3478"], "username": "1:u", "credential": "c"}""")
    private val turns = server("""{"urls": ["TURNS:turn.example:5349"], "username": "1:u", "credential": "c"}""")
    private val stun = server("""{"urls": ["stun:stun.example:3478"]}""")

    /** As `GET /calls/ice-servers` sends them. */
    private fun server(text: String): IceServerDto = json.decodeFromString(IceServerDto.serializer(), text)

    @Test
    fun recognisesTurnAndTurnsButNotStun() {
        assertTrue(CallController.offersRelay(listOf(stun, turn)))
        assertTrue(CallController.offersRelay(listOf(turns)))
        assertFalse(CallController.offersRelay(listOf(stun)))
        assertFalse(CallController.offersRelay(emptyList()))
        // A single string is a list of one (CallModels.swift:136-142).
        assertTrue(CallController.offersRelay(listOf(server("""{"urls": "turn:one.example:3478"}"""))))
    }

    @Test
    fun offMeansDirectPathsFirst() {
        assertFalse(CallController.relayPolicy(listOf(stun, turn), alwaysRelay = false))
        assertFalse(CallController.relayPolicy(emptyList(), alwaysRelay = false))
    }

    @Test
    fun onStaysOnTheRelay() {
        assertTrue(CallController.relayPolicy(listOf(stun, turn), alwaysRelay = true))
    }

    @Test(expected = CallRelayUnavailableException::class)
    fun onWithoutARelayRefusesTheCall() {
        CallController.relayPolicy(listOf(stun), alwaysRelay = true)
    }
}
