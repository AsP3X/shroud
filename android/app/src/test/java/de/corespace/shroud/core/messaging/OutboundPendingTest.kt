package de.corespace.shroud.core.messaging

import de.corespace.shroud.core.model.ChatMessage
import de.corespace.shroud.core.model.ChatMessageKind
import de.corespace.shroud.core.model.NOTES_PEER_ID
import de.corespace.shroud.core.model.ReceiptStatus
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.util.UUID

/** `OutboundPendingTests` (`ios/shroudTests/LocalMessageStoreTests.swift:181-275`) and the flush queue (`OutboundPending.swift:62-85`). */
@OptIn(ExperimentalCoroutinesApi::class)
class OutboundPendingTest {
    private val peer = UUID.randomUUID()
    private val me = UUID.randomUUID()
    private val now = Instant.parse("2026-09-24T12:00:00Z")

    private fun mine(text: String, at: Instant, pending: Boolean = true, kind: ChatMessageKind = ChatMessageKind.Text, receipt: ReceiptStatus = ReceiptStatus.Sending) =
        ChatMessage(UUID.randomUUID(), peer, me, text, at, isMine = true, receipt = receipt, kind = kind, pendingSync = pending)

    @Test
    fun testCollectsPendingInChronologicalOrder() { // :182-232
        val older = mine("first", now.minusSeconds(60))
        val newer = mine("second", now)
        val synced = mine("done", now.minusSeconds(30), pending = false, receipt = ReceiptStatus.Sent)
        val items = OutboundPending.items(mapOf(peer to listOf(newer, synced, older)))
        assertEquals(2, items.size)
        assertEquals(OutboundPendingItem.Text(older.id, peer, "first"), items[0])
        assertEquals(newer.id, (items[1] as OutboundPendingItem.Text).messageId)
    }

    @Test
    fun testCollectsPendingVoice() { // :234-259
        val voice = mine("Voice message", now, kind = ChatMessageKind.Voice, receipt = ReceiptStatus.Failed).copy(hasFullMedia = true, durationMs = 1200)
        assertEquals(listOf(OutboundPendingItem.Voice(voice.id, peer)), OutboundPending.items(mapOf(peer to listOf(voice))))
    }

    @Test
    fun testSkipsNotesPeer() { // :261-274
        val note = ChatMessage(UUID.randomUUID(), NOTES_PEER_ID, me, "local only", now, isMine = true, pendingSync = true)
        assertTrue(OutboundPending.items(mapOf(NOTES_PEER_ID to listOf(note))).isEmpty())
    }

    @Test
    fun standInCaptionsAreEmptyAndTodosAndInboundAreSkipped() {
        val photo = mine("Photo", now, kind = ChatMessageKind.Image)
        val captioned = mine("At the lake", now.plusSeconds(1), kind = ChatMessageKind.Image)
        val video = mine("Video", now.plusSeconds(2), kind = ChatMessageKind.Video)
        val todo = mine("milk", now.plusSeconds(3), kind = ChatMessageKind.Todo)
        val inbound = ChatMessage(UUID.randomUUID(), peer, peer, "theirs", now, isMine = false, pendingSync = true)
        assertEquals(
            listOf(
                OutboundPendingItem.Image(photo.id, peer, ""),
                OutboundPendingItem.Image(captioned.id, peer, "At the lake"),
                OutboundPendingItem.Video(video.id, peer, ""),
            ),
            OutboundPending.items(mapOf(peer to listOf(photo, captioned, video, todo, inbound))),
        )
    }

    @Test
    fun overlappingFlushesRunOnce() = runTest(StandardTestDispatcher()) {
        val queue = OutboundSendQueue(backgroundScope)
        val gate = CompletableDeferred<Unit>()
        var runs = 0
        val first = async { queue.flush { runs++; gate.await() } }
        val second = async { queue.flush { runs++ } }
        advanceUntilIdle()
        assertTrue(queue.isFlushing)
        gate.complete(Unit)
        first.await()
        second.await()
        assertEquals(1, runs)
        assertFalse(queue.isFlushing)
        // A flush after the first finished runs again.
        queue.flush { runs++ }
        assertEquals(2, runs)
    }

    @Test
    fun cancelStopsTheFlushAndReleasesItsWaiters() = runTest(StandardTestDispatcher()) {
        val queue = OutboundSendQueue(backgroundScope)
        val never = CompletableDeferred<Unit>()
        val waiter = async { queue.flush { never.await() } }
        advanceUntilIdle()
        queue.cancel()
        waiter.await()
        assertFalse(queue.isFlushing)
    }
}
