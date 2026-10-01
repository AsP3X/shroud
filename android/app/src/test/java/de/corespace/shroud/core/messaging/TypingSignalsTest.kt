package de.corespace.shroud.core.messaging

import de.corespace.shroud.core.model.ChatPeerActivity
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

/**
 * The typing contract with iOS and the web (`MessagingController.swift:2249-2389`, `web/src/typing.ts`;
 * messaging-core §17): keepalive at most every 3 s, idle `false` after 3 s, a switch of peer says
 * `false` to the old one, the receiver drops an indicator 6 s after the last `true`, recording keeps
 * itself alive, nothing goes out while typing indicators are off.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TypingSignalsTest {
    private val bob = UUID.randomUUID()
    private val carol = UUID.randomUUID()
    private val socket = FakeSocket()
    private var allowed = true

    private fun TestScope.signals() = TypingSignals(
        scope = backgroundScope,
        sender = object : TypingSignals.Sender {
            override fun sendTyping(peer: UUID, isTyping: Boolean) = socket.sendTyping(peer, isTyping)
            override fun sendRecording(peer: UUID, isRecording: Boolean) = socket.sendRecording(peer, isRecording)
        },
        allowed = { allowed },
        elapsedMillis = { testScheduler.currentTime },
    )

    @Test
    fun keepaliveAtMostEveryThreeSecondsThenIdleFalse() = runTest {
        val typing = signals()
        typing.setTyping(bob, true)
        advanceTimeBy(1_000)
        typing.setTyping(bob, true) // within 3 s: no frame
        advanceTimeBy(1_000)
        typing.setTyping(bob, true)
        assertEquals(listOf(bob to true), socket.typingFrames)
        advanceTimeBy(1_500)
        typing.setTyping(bob, true) // 3.5 s since the frame: keepalive
        assertEquals(listOf(bob to true, bob to true), socket.typingFrames)
        // 3 s without a keystroke: false.
        advanceTimeBy(3_001)
        runCurrent()
        assertEquals(bob to false, socket.typingFrames.last())
        assertEquals(3, socket.typingFrames.size)
    }

    @Test
    fun anEmptyDraftAndASwitchOfPeerSayFalse() = runTest {
        val typing = signals()
        typing.setTyping(bob, true)
        typing.setTyping(carol, true)
        assertEquals(listOf(bob to true, bob to false, carol to true), socket.typingFrames)
        typing.setTyping(carol, false)
        assertEquals(carol to false, socket.typingFrames.last())
        // Already said false: nothing more.
        typing.setTyping(carol, false)
        assertEquals(4, socket.typingFrames.size)
    }

    @Test
    fun recordingKeepsItselfAliveAndStopsTyping() = runTest {
        val typing = signals()
        typing.setTyping(bob, true)
        typing.setRecording(bob, true)
        assertEquals(listOf(bob to true, bob to false), socket.typingFrames)
        assertEquals(listOf(bob to true), socket.recordingFrames)
        advanceTimeBy(3_001)
        runCurrent()
        advanceTimeBy(3_000)
        runCurrent()
        assertEquals(3, socket.recordingFrames.size)
        // Stopping for another peer does nothing; for this one says false.
        typing.setRecording(carol, false)
        assertEquals(3, socket.recordingFrames.size)
        typing.setRecording(bob, false)
        assertEquals(bob to false, socket.recordingFrames.last())
        advanceTimeBy(10_000)
        runCurrent()
        assertEquals(4, socket.recordingFrames.size)
    }

    @Test
    fun nothingGoesOutWhileTypingIndicatorsAreOff() = runTest {
        allowed = false
        val typing = signals()
        typing.setTyping(bob, true)
        typing.setRecording(bob, true)
        assertTrue(socket.typingFrames.isEmpty())
        assertTrue(socket.recordingFrames.isEmpty())
    }

    @Test
    fun theReceiverDropsAnIndicatorAfterSixSeconds() = runTest {
        val typing = signals()
        typing.setPeerTyping(bob, true)
        assertEquals(ChatPeerActivity.Typing, typing.peerActivity(bob))
        advanceTimeBy(5_000)
        typing.setPeerTyping(bob, true) // refreshed
        advanceTimeBy(5_000)
        runCurrent()
        assertEquals(ChatPeerActivity.Typing, typing.peerActivity(bob))
        advanceTimeBy(1_001)
        runCurrent()
        assertNull(typing.peerActivity(bob))
        assertTrue(typing.activities.value.isEmpty())
    }

    @Test
    fun recordingWinsAndReplacesTyping() = runTest { // MessagingController.swift:2266-2270, 2345-2374
        val typing = signals()
        typing.setPeerTyping(bob, true)
        typing.setPeerRecording(bob, true)
        assertEquals(ChatPeerActivity.Recording, typing.peerActivity(bob))
        assertEquals(emptySet<UUID>(), typing.typing.value)
        typing.setPeerTyping(bob, true)
        assertEquals(ChatPeerActivity.Typing, typing.peerActivity(bob))
        assertEquals(emptySet<UUID>(), typing.recording.value)
        typing.setPeerRecording(carol, true)
        typing.clearPeer(bob)
        assertEquals(mapOf(carol to ChatPeerActivity.Recording), typing.activities.value)
        typing.clearAll()
        assertTrue(typing.activities.value.isEmpty())
        advanceTimeBy(10_000)
        runCurrent()
        assertTrue(typing.activities.value.isEmpty())
    }

    @Test
    fun clearAllSaysFalseForWhatWeSaidTrue() = runTest {
        val typing = signals()
        typing.setRecording(bob, true)
        typing.clearAll()
        assertEquals(bob to false, socket.recordingFrames.last())
    }
}
