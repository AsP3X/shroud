package de.corespace.shroud.core.messaging.reactions

import de.corespace.shroud.core.crypto.B64
import de.corespace.shroud.core.crypto.MessageCrypto
import de.corespace.shroud.core.crypto.OpenAs
import de.corespace.shroud.core.crypto.TestIdentity
import de.corespace.shroud.core.messaging.SendWorld
import de.corespace.shroud.core.model.ChatMessage
import de.corespace.shroud.core.model.ChatMessageKind
import de.corespace.shroud.core.model.MessageReaction
import de.corespace.shroud.core.model.NOTES_PEER_ID
import de.corespace.shroud.core.model.ReactionFailure
import de.corespace.shroud.core.model.ReceiptStatus
import de.corespace.shroud.core.net.ConversationItemDto
import de.corespace.shroud.core.net.ConversationPeerDto
import de.corespace.shroud.core.net.ReactionDto
import de.corespace.shroud.core.net.wire.Icu4jTextUnitsRule
import de.corespace.shroud.core.net.wire.MessageReactionPayload
import de.corespace.shroud.core.notifications.NotificationKind
import de.corespace.shroud.core.realtime.RealtimeEvent
import de.corespace.shroud.core.storage.StorageSeal
import de.corespace.shroud.testing.FakeSharedPreferences
import de.corespace.shroud.testing.MainDispatcherRule
import de.corespace.shroud.testing.TempDirRule
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.time.Instant
import java.util.UUID

/**
 * The reactions engine (messaging-core §19.3–§19.8; `MessagingController.swift` MC:5060-5956;
 * `MessageReactionTests.swift`): our writes (sealed v2, one request per message, 409 rebase ≤ 3
 * rounds, rollback), reading theirs (web-sealed interop, pages, the socket, catch-up 5 × 200 with its
 * cursor rules) and the heart badge.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ReactionEngineTest {
    @get:Rule val main = MainDispatcherRule()
    @get:Rule val textUnits = Icu4jTextUnitsRule()
    @get:Rule val temp = TempDirRule()

    private val createdAt = Instant.parse("2026-09-23T21:00:00Z")
    private val updatedAt = Instant.parse("2026-09-23T21:08:35Z")

    private class Setup(val world: SendWorld, val engine: ReactionEngine, val prefs: FakeSharedPreferences, val seal: StorageSeal, val message: ChatMessage)

    private fun TestScope.setup(
        meKeys: TestIdentity = TestIdentity.random(),
        peerKeys: TestIdentity = TestIdentity.random(),
        messageId: UUID = UUID.randomUUID(),
        mine: Boolean = false,
        reactions: List<MessageReaction> = emptyList(),
        prefs: FakeSharedPreferences = FakeSharedPreferences(),
    ): Setup {
        val w = SendWorld(backgroundScope, main.dispatcher, temp.cacheDir, meKeys = meKeys, peerKeys = peerKeys)
        val message = ChatMessage(
            id = messageId, peerUserId = w.peer, senderUserId = if (mine) w.me else w.peer, text = "hi",
            createdAt = createdAt, isMine = mine, reactions = reactions,
        )
        w.state.put(w.peer, listOf(message))
        val seal = StorageSeal()
        return Setup(w, ReactionEngine(w.state, { w.host }, w.deps, prefs, seal), prefs, seal, message)
    }

    private fun Setup.held(): ChatMessage = world.state.messages(world.peer)!!.single { it.id == message.id }

    private fun Setup.failures(scope: TestScope): MutableList<ReactionFailure> {
        val seen = mutableListOf<ReactionFailure>()
        scope.backgroundScope.launch { engine.failures.collect { seen += it } }
        return seen
    }

    /** A record sealed by [sender] (to [recipient]) as a v2 tagged envelope. */
    private fun sealedRecord(crypto: MessageCrypto, sender: TestIdentity, recipient: TestIdentity, emojis: List<String>, messageId: UUID): String =
        MessageCrypto.toWire(crypto.sealV2(MessageReactionPayload.make(emojis, messageId).encoded(), recipient.public, sender.private, sender.public))

    private fun Setup.peerRecord(emojis: List<String>, seq: Long, messageId: UUID = message.id) =
        ReactionDto(messageId, world.peer, sealedRecord(world.peerCrypto, world.peerKeys, world.meKeys, emojis, messageId), seq, updatedAt)

    private fun Setup.ownRecord(emojis: List<String>?, seq: Long) =
        ReactionDto(message.id, world.me, emojis?.let { sealedRecord(world.crypto, world.meKeys, world.peerKeys, it, message.id) }, seq, updatedAt)

    private fun conversation(peer: UUID, reactionSeq: Long?, unseen: Int?) =
        ConversationItemDto(UUID.nameUUIDFromBytes(peer.toString().toByteArray()), ConversationPeerDto(peer, "mira"), createdAt, null, reactionSeq, unseen)

    // ---- writing ours ----

    @Test
    fun ourReactionIsSealedV2ShownAtOnceAndConfirmed() = runTest(main.dispatcher) {
        val s = setup()
        val w = s.world
        val gate = CompletableDeferred<Unit>()
        w.server.reactionGate = gate
        s.engine.set(listOf("👍"), s.message.id, w.peer)
        // Shown at once, pending (MC:5143-5146).
        assertEquals(listOf(MessageReaction(w.me, listOf("👍"), 0, pending = true)), s.held().reactions)
        assertEquals(listOf("👍"), s.engine.myReactions(s.held()))
        gate.complete(Unit)

        val write = w.server.writes.single()
        assertEquals(0L, write.baseSeq)
        assertEquals(true, write.added)
        // Tagged v2 identity boxes, readable by the peer and by our other devices.
        val envelope = write.ciphertext!!
        assertEquals(listOf("👍"), MessageReactionPayload.parse(w.peerCrypto.openTagged(envelope, w.peerKeys.private, w.peerKeys.public, w.meKeys.public, OpenAs.Recipient), s.message.id))
        assertEquals(listOf("👍"), MessageReactionPayload.parse(w.crypto.openTagged(envelope, w.meKeys.private, w.meKeys.public, w.meKeys.public, OpenAs.Sender), s.message.id))
        assertEquals(listOf(MessageReaction(w.me, listOf("👍"), 41)), s.held().reactions)
        assertEquals(2, s.engine.revisions.value[w.peer])

        // The thread is saved once, batched (MC:5868-5876).
        assertTrue(w.state.persistThreads.isEmpty())
        advanceTimeBy(601)
        assertEquals(listOf(w.peer), w.state.persistThreads)
    }

    @Test
    fun takingItBackDeletesOnTheConfirmedSeq() = runTest(main.dispatcher) {
        val s = setup()
        val w = s.world
        s.engine.toggle("❤️", s.message.id, w.peer)
        s.engine.toggle("❤️", s.message.id, w.peer)
        assertEquals(2, w.server.writes.size)
        val removal = w.server.writes[1]
        assertNull(removal.ciphertext)
        assertEquals(41L, removal.baseSeq)
        assertEquals(emptyList<String>(), s.engine.myReactions(s.held()))
        assertEquals(listOf(MessageReaction(w.me, emptyList(), 42)), s.held().reactions)
    }

    @Test
    fun tapsWhileASaveRunsCollapseIntoOneFollowUp() = runTest(main.dispatcher) {
        val s = setup()
        val w = s.world
        val gate = CompletableDeferred<Unit>()
        w.server.reactionGate = gate
        s.engine.set(listOf("👍"), s.message.id, w.peer)
        s.engine.set(listOf("👍", "🔥"), s.message.id, w.peer)
        s.engine.set(listOf("🔥"), s.message.id, w.peer)
        assertEquals(1, w.server.writes.size)
        w.server.reactionGate = null
        gate.complete(Unit)

        assertEquals(2, w.server.writes.size)
        val followUp = w.server.writes[1]
        // Built on the confirmed first write; "🔥" is news, so `added`.
        assertEquals(41L, followUp.baseSeq)
        assertEquals(true, followUp.added)
        assertEquals(listOf(MessageReaction(w.me, listOf("🔥"), 42)), s.held().reactions)
    }

    @Test
    fun aConflictRebasesWhatWeChangedOntoOurOtherDevicesSet() = runTest(main.dispatcher) {
        // MessageReactionTests.rebasingKeepsBothDevicesPicks, end to end.
        val s = setup()
        val w = s.world
        w.state.put(w.peer, listOf(s.message.copy(reactions = listOf(MessageReaction(w.me, listOf("❤️"), 41)))))
        w.server.records[s.message.id to w.me] = s.ownRecord(listOf("❤️"), 41)
        w.server.conflicts += s.ownRecord(listOf("❤️", "👍"), 42)

        s.engine.set(listOf("❤️", "🔥"), s.message.id, w.peer)

        assertEquals(listOf(41L, 42L), w.server.writes.map { it.baseSeq })
        val rebased = MessageReactionPayload.parse(
            w.peerCrypto.openTagged(w.server.writes[1].ciphertext!!, w.peerKeys.private, w.peerKeys.public, w.meKeys.public, OpenAs.Recipient),
            s.message.id,
        )
        assertEquals(listOf("❤️", "👍", "🔥"), rebased)
        assertEquals(listOf(MessageReaction(w.me, listOf("❤️", "👍", "🔥"), 43)), s.held().reactions)
    }

    @Test
    fun aConflictThatAlreadyHoldsOurSetEndsWithoutAnotherWrite() = runTest(main.dispatcher) {
        val s = setup()
        val w = s.world
        val failures = s.failures(this)
        w.server.conflicts += s.ownRecord(listOf("👍"), 50)
        s.engine.set(listOf("👍"), s.message.id, w.peer)
        assertEquals(1, w.server.writes.size)
        assertEquals(listOf(MessageReaction(w.me, listOf("👍"), 50)), s.held().reactions)
        assertTrue(failures.isEmpty())
    }

    @Test
    fun moreThanThreeRebasesGiveUpAndSaySo() = runTest(main.dispatcher) {
        val s = setup()
        val w = s.world
        val failures = s.failures(this)
        for (seq in 42L..45L) w.server.conflicts += s.ownRecord(listOf("👍"), seq)
        s.engine.set(listOf("🔥"), s.message.id, w.peer)

        assertEquals(4, w.server.writes.size)
        // The server's set stands; the chip shows it, and a toast says ours wasn't saved.
        assertEquals(listOf(MessageReaction(w.me, listOf("👍"), 45)), s.held().reactions)
        assertEquals(listOf("Couldn't save your reaction."), failures.map { it.message })
        assertEquals(s.message.id, failures.single().messageId)
    }

    @Test
    fun aFailedSavePutsBackWhatTheServerHolds() = runTest(main.dispatcher) {
        val s = setup(reactions = emptyList())
        val w = s.world
        val failures = s.failures(this)
        val confirmed = MessageReaction(w.me, listOf("❤️"), 41)
        w.state.put(w.peer, listOf(s.message.copy(reactions = listOf(confirmed))))

        w.online = false
        s.engine.set(listOf("❤️", "👍"), s.message.id, w.peer)
        assertEquals(listOf(confirmed), s.held().reactions)
        assertEquals("You're offline. Your reaction wasn't saved.", failures.last().message)

        w.online = true
        w.server.reactionFailures = 1
        s.engine.set(listOf("❤️", "👍"), s.message.id, w.peer)
        assertEquals(listOf(confirmed), s.held().reactions)
        assertEquals("Couldn't save your reaction.", failures.last().message)
    }

    @Test
    fun aFailedSaveTakesTheCatchUpCursorBack() = runTest(main.dispatcher) {
        // A change from our other device ignored while our tap was pending must come back (MC:5232-5236).
        val s = setup()
        val w = s.world
        w.store.cursors = mapOf(w.peer to 10L)
        val other = ChatMessage(UUID.randomUUID(), w.peer, w.peer, "other", createdAt, isMine = false)
        w.state.put(w.peer, listOf(s.message, other))
        val gate = CompletableDeferred<Unit>()
        w.server.reactionGate = gate
        s.engine.set(listOf("👍"), s.message.id, w.peer)

        w.server.changeLog += s.peerRecord(listOf("🎉"), 50, other.id)
        s.engine.catchUp(w.peer, 60)
        assertEquals(50L, w.store.cursors[w.peer])

        w.server.reactionFailures = 1
        gate.complete(Unit)
        assertEquals(10L, w.store.cursors[w.peer])
    }

    @Test
    fun onlyReactableMessagesTakeReactions() = runTest(main.dispatcher) {
        val s = setup()
        val w = s.world
        val base = s.message
        assertTrue(s.engine.canReact(base))
        assertFalse(s.engine.canReact(base.copy(deleted = true)))
        assertFalse(s.engine.canReact(base.copy(peerUserId = NOTES_PEER_ID)))
        assertFalse(s.engine.canReact(base.copy(kind = ChatMessageKind.Todo)))
        assertFalse(s.engine.canReact(base.copy(pendingSync = true)))
        assertFalse(s.engine.canReact(base.copy(isMine = true, receipt = ReceiptStatus.Sending)))
        assertFalse(s.engine.canReact(base.copy(isMine = true, receipt = ReceiptStatus.Failed)))
        assertFalse(s.engine.canReact(base.copy(sendError = "Waiting for connection…")))

        // Not one emoji, or twice the same: nothing happens.
        s.engine.set(listOf("ok"), base.id, w.peer)
        s.engine.set(listOf("👍", "👍"), base.id, w.peer)
        assertTrue(w.server.writes.isEmpty())
    }

    @Test
    fun theLimitComesFromTheServerAndIsRemembered() = runTest(main.dispatcher) {
        val s = setup(prefs = FakeSharedPreferences(mapOf(ReactionEngine.LIMIT_KEY to 7)))
        assertEquals(7, s.engine.limit.value)
        s.world.server.reactionLimit = 3
        s.engine.refreshServerConfig()
        assertEquals(3, s.engine.limit.value)
        assertEquals(3, s.prefs.getInt(ReactionEngine.LIMIT_KEY, 0))

        // Past the limit our oldest goes (ReactionMerge.toggled).
        s.engine.set(listOf("❤️", "👍", "🔥"), s.message.id, s.world.peer)
        s.engine.toggle("😮", s.message.id, s.world.peer)
        assertEquals(listOf("👍", "🔥", "😮"), s.engine.myReactions(s.held()))

        // Never below 1; nothing written while a wipe seals the stores.
        s.seal.seal()
        s.world.server.reactionLimit = 0
        s.engine.refreshServerConfig()
        assertEquals(1, s.engine.limit.value)
        assertEquals(3, s.prefs.getInt(ReactionEngine.LIMIT_KEY, 0))
    }

    @Test
    fun aPendingChangeIsNeverPersistedAsConfirmed() = runTest(main.dispatcher) {
        val s = setup()
        val w = s.world
        val confirmed = MessageReaction(w.me, listOf("❤️"), 41)
        w.state.put(w.peer, listOf(s.message.copy(reactions = listOf(confirmed))))
        w.server.reactionGate = CompletableDeferred()
        s.engine.set(listOf("❤️", "👍"), s.message.id, w.peer)
        assertTrue(s.held().reactions.single().pending)
        assertEquals(listOf(confirmed), s.engine.settled(w.state.messages(w.peer)!!).single().reactions)
    }

    // ---- reading theirs ----

    @Test
    fun aReactionSealedOnTheWebOpensHere() = runTest(main.dispatcher) {
        // MessageReactionTests.reactionSealedOnTheWebOpensHere: alice (0xa1) → bob (0xb2), we are bob.
        val alice = TestIdentity.filled(0xa1)
        val bob = TestIdentity.filled(0xb2)
        val messageId = UUID.fromString("7C9E6679-7425-40DE-944B-E07FC1F90AE7")
        val s = setup(meKeys = bob, peerKeys = alice, messageId = messageId, mine = true)
        val w = s.world
        assertTrue(B64.decodeStrict(WEB_SEALED_REACTION)!!.toString(Charsets.UTF_8).startsWith("{\"v\":2,\"peer\":{"))
        s.engine.apply(RealtimeEvent.MessageReaction(ReactionDto(messageId, w.peer, WEB_SEALED_REACTION, 7, updatedAt), null, w.me, null, true))
        // Sealed with "ok", "🔥🔥", a bare "❤" and a repeat on the end, which neither client shows.
        val expected = listOf("👍🏽", "🏳️‍🌈", "🇩🇪", "❤️", "❤️‍🔥", "1️⃣", "👨‍👩‍👧")
        assertEquals(listOf(MessageReaction(w.peer, expected, 7)), s.held().reactions)
    }

    @Test
    fun recordsFromStrangersOrWithBrokenBoxesReadAsRemovals() = runTest(main.dispatcher) {
        val s = setup()
        val w = s.world
        // A stranger's record is ignored; an untagged/garbled one is no reaction.
        s.engine.apply(RealtimeEvent.MessageReaction(ReactionDto(s.message.id, UUID.randomUUID(), "aGVhcnQ=", 50, updatedAt), null, null, null, true))
        assertTrue(s.held().reactions.isEmpty())
        s.engine.apply(RealtimeEvent.MessageReaction(ReactionDto(s.message.id, w.peer, "aGVhcnQ=", 51, updatedAt), null, null, null, true))
        assertEquals(listOf(MessageReaction(w.peer, emptyList(), 51)), s.held().reactions)
    }

    @Test
    fun aKeyOutOfReachLeavesTheChangeForCatchUp() = runTest(main.dispatcher) {
        val s = setup()
        val w = s.world
        w.identities.resolveFailure = de.corespace.shroud.core.net.ApiError.Transport("offline")
        w.server.changeLog += s.peerRecord(listOf("🔥"), 30)
        s.engine.catchUp(w.peer, 40)
        // Nothing applied and the cursor did not move past it.
        assertTrue(s.held().reactions.isEmpty())
        assertTrue(w.store.savedCursors.isEmpty())

        w.identities.resolveFailure = null
        s.engine.catchUp(w.peer, 40)
        assertEquals(listOf(MessageReaction(w.peer, listOf("🔥"), 30)), s.held().reactions)
        assertEquals(30L, w.store.cursors[w.peer])
    }

    @Test
    fun catchUpWalksFivePagesOf200PerRefresh() = runTest(main.dispatcher) {
        val s = setup()
        val w = s.world
        // 1 200 removals by the peer (no crypto needed), seq 1…1200.
        for (seq in 1L..1_200L) w.server.changeLog += ReactionDto(s.message.id, w.peer, null, seq, updatedAt)
        s.engine.catchUp(w.peer, 1_200)
        assertEquals(5, w.server.changesPagesServed)
        assertEquals(1_000L, w.store.cursors[w.peer])
        assertEquals(listOf(MessageReaction(w.peer, emptyList(), 1_000)), s.held().reactions)

        s.engine.catchUp(w.peer, 1_200)
        assertEquals(6, w.server.changesPagesServed)
        assertEquals(1_200L, w.store.cursors[w.peer])
        // Caught up: no request at all.
        s.engine.catchUp(w.peer, 1_200)
        assertEquals(6, w.server.changesPagesServed)
    }

    @Test
    fun catchUpStopsBeforeAChangeOfOursLeftAloneWhileOurSaveRuns() = runTest(main.dispatcher) {
        val s = setup()
        val w = s.world
        w.server.reactionGate = CompletableDeferred()
        s.engine.set(listOf("👍"), s.message.id, w.peer)
        w.server.changeLog += s.ownRecord(listOf("❤️"), 30)
        w.server.changeLog += ReactionDto(s.message.id, w.peer, null, 35, updatedAt)
        s.engine.catchUp(w.peer, 40)
        assertEquals(29L, w.store.cursors[w.peer])
        // Our pending entry stood; the peer's change applied.
        assertTrue(s.held().reactions.any { it.userId == w.me && it.pending })
    }

    @Test
    fun anUnreadableCursorFileIsNeverOverwritten() = runTest(main.dispatcher) {
        val s = setup()
        val w = s.world
        w.store.cursorsReadable = false
        w.server.changeLog += ReactionDto(s.message.id, w.peer, null, 5, updatedAt)
        s.engine.catchUp(w.peer, 10)
        // Applied (from zero), but the map that was never read is not written back (MC:5896-5903).
        assertEquals(listOf(MessageReaction(w.peer, emptyList(), 5)), s.held().reactions)
        assertTrue(w.store.savedCursors.isEmpty())
    }

    @Test
    fun aPageReconcileDropsWhatThePageNoLongerLists() = runTest(main.dispatcher) {
        // MessageReactionTests: held peer ❤️@4, me 👍@12, snapshot 10 → only mine.
        val s = setup()
        val w = s.world
        val held = listOf(MessageReaction(w.peer, listOf("❤️"), 4), MessageReaction(w.me, listOf("👍"), 12))
        w.state.put(w.peer, listOf(s.message.copy(reactions = held)))
        val page = s.engine.openPage(w.peer, setOf(s.message.id), emptyMap(), 10)
        val reconciled = s.engine.reconcilePage(w.peer, page, w.state.messages(w.peer)!!)
        assertEquals(listOf(MessageReaction(w.me, listOf("👍"), 12)), reconciled.single().reactions)

        // And takes what the page lists, reusing an entry held at the same seq (no decrypt).
        val listed = s.engine.openPage(w.peer, setOf(s.message.id), mapOf(s.message.id to listOf(s.peerRecord(listOf("🎉"), 9))), 10)
        assertEquals(listOf(MessageReaction(w.peer, listOf("🎉"), 9)), listed.reactions[s.message.id])
    }

    @Test
    fun aPageSnapshotBelowTheCursorPullsItBack() = runTest(main.dispatcher) {
        val s = setup()
        val w = s.world
        w.store.cursors = mapOf(w.peer to 20L)
        s.engine.applyPage(w.peer, listOf(s.peerRecord(listOf("🔥"), 15)), 18)
        assertEquals(18L, w.store.cursors[w.peer])
        assertEquals(listOf(MessageReaction(w.peer, listOf("🔥"), 15)), s.held().reactions)
        // Caught up to the newest snapshot: no request (MC:5918-5919).
        s.engine.catchUp(w.peer)
        assertEquals(0, w.server.changesPagesServed)
        // A change after it: the next load's page names a newer snapshot, and catch-up walks from
        // the cursor the first page pulled back.
        w.server.changeLog += s.peerRecord(listOf("😮"), 19)
        s.engine.applyPage(w.peer, emptyList(), 19)
        assertEquals(18L, w.store.cursors[w.peer])
        s.engine.catchUp(w.peer)
        assertEquals(19L, w.store.cursors[w.peer])
        assertEquals(listOf(MessageReaction(w.peer, listOf("😮"), 19)), s.held().reactions)
    }

    // ---- activity, badge ----

    @Test
    fun theirReactionToOurMessageIsAnnouncedAndMarksTheBadge() = runTest(main.dispatcher) {
        val s = setup(mine = true)
        val w = s.world
        w.host.names[w.peer] = "mira"
        w.host.conversations.value = listOf(conversation(w.peer, 10, 1))
        val event = RealtimeEvent.MessageReaction(s.peerRecord(listOf("🔥"), 11), w.host.conversations.value.single().id, w.me, null, true)
        s.engine.apply(event)
        assertEquals(listOf(MessageReaction(w.peer, listOf("🔥"), 11)), s.held().reactions)
        assertEquals(listOf(Triple(NotificationKind.Reaction, w.peer, "mira")), w.notifier.announced)
        assertTrue(s.engine.hasUnseen(w.peer))
        // Another chat is open: the list is re-read once for the burst, after 700 ms (MC:5856-5864).
        assertEquals(0, w.host.refreshes)
        advanceTimeBy(701)
        assertEquals(1, w.host.refreshes)
        assertEquals(1, s.engine.revisions.value[w.peer])
    }

    @Test
    fun inTheOpenChatTheirReactionIsSeenAtOnce() = runTest(main.dispatcher) {
        val s = setup(mine = true)
        val w = s.world
        w.host.conversations.value = listOf(conversation(w.peer, 10, 1))
        w.host.activePeerId.value = w.peer
        s.engine.apply(RealtimeEvent.MessageReaction(s.peerRecord(listOf("🔥"), 11), null, w.me, null, true))
        val row = w.host.conversations.value.single()
        assertEquals(11L, row.reactionSeq)
        assertEquals(0, row.unseenReactions)
        assertEquals(listOf(w.peer to 11L), w.server.seenCalls)
        assertFalse(s.engine.hasUnseen(w.peer))
    }

    @Test
    fun takingBackOneOfSeveralIsNoNews() = runTest(main.dispatcher) {
        val s = setup(mine = true)
        val w = s.world
        s.engine.apply(RealtimeEvent.MessageReaction(s.peerRecord(listOf("🔥"), 11), null, w.me, null, false))
        assertTrue(w.notifier.announced.isEmpty())
        advanceTimeBy(5_000)
        assertEquals(0, w.host.refreshes)
    }

    @Test
    fun markSeenClearsTheBadgeAndForgetsTheMarkWhenNotSaved() = runTest(main.dispatcher) {
        val s = setup()
        val w = s.world
        val row = conversation(w.peer, 15, 2)
        w.host.conversations.value = listOf(row)
        assertTrue(s.engine.hasUnseen(w.peer))

        w.server.seenFails = true
        s.engine.markSeen(w.peer)
        assertEquals(listOf(w.peer to 15L), w.server.seenCalls)
        // Not saved: a list that still carries the badge shows it again (MC:5589-5593).
        assertEquals(listOf(row), s.engine.applyingLocalSeen(listOf(row)))

        w.server.seenFails = false
        w.host.conversations.value = listOf(row)
        s.engine.markSeen(w.peer)
        assertEquals(0, w.host.conversations.value.single().unseenReactions)
        // A racing list that predates the seen call keeps it cleared; a newer reaction shows.
        assertEquals(0, s.engine.applyingLocalSeen(listOf(row)).single().unseenReactions)
        assertEquals(1, s.engine.applyingLocalSeen(listOf(conversation(w.peer, 16, 1))).single().unseenReactions)
    }

    @Test
    fun notesNeverMarkSeen() = runTest(main.dispatcher) {
        val s = setup()
        s.engine.markSeen(NOTES_PEER_ID, 5)
        assertTrue(s.world.server.seenCalls.isEmpty())
    }

    @Test
    fun seenOnOurOtherDeviceClearsTheBadgeHere() = runTest(main.dispatcher) {
        val s = setup()
        val w = s.world
        w.host.conversations.value = listOf(conversation(w.peer, 15, 2))
        s.engine.apply(RealtimeEvent.ReactionsSeen(w.peer, 15, null))
        assertEquals(0, w.host.conversations.value.single().unseenReactions)
        advanceTimeBy(5_000)
        assertEquals(0, w.host.refreshes)

        // Nothing to clear here: the list is re-read instead.
        s.engine.apply(RealtimeEvent.ReactionsSeen(w.peer, 16, null))
        advanceTimeBy(5_000)
        assertEquals(1, w.host.refreshes)
    }

    // ---- lifecycle ----

    @Test
    fun aLockWritesBatchedSavesAtOnce() = runTest(main.dispatcher) {
        val s = setup()
        val w = s.world
        s.engine.set(listOf("👍"), s.message.id, w.peer)
        assertTrue(w.state.persistThreads.isEmpty())
        s.engine.flushPendingSaves()
        assertEquals(listOf(w.peer), w.state.persistThreads)
        advanceTimeBy(5_000)
        assertEquals(listOf(w.peer), w.state.persistThreads)
    }

    @Test
    fun resetStopsSavesAndForgetsTheAccount() = runTest(main.dispatcher) {
        val s = setup()
        val w = s.world
        w.server.reactionGate = CompletableDeferred()
        w.host.conversations.value = listOf(conversation(w.peer, 15, 2))
        s.engine.set(listOf("👍"), s.message.id, w.peer)
        s.engine.markSeen(w.peer)
        s.engine.reset()
        runCurrent()
        assertTrue(s.engine.revisions.value.isEmpty())
        assertEquals(listOf(conversation(w.peer, 15, 2)), s.engine.applyingLocalSeen(listOf(conversation(w.peer, 15, 2))))
        advanceTimeBy(5_000)
        assertTrue(w.state.persistThreads.isEmpty())
        assertEquals(1, w.server.writes.size)
    }

    private companion object {
        /** `ios/shroudTests/MessageReactionTests.swift:14-28`, copied line by line. */
        const val WEB_SEALED_REACTION =
            "eyJ2IjoyLCJwZWVyIjp7ImVrIjoibEtaSnd0KzZiSy9nSEJiUlBZNkMydS8vUExJTkluSTJEU1Q3K0YxODNGaz0i" +
                "LCJjdCI6ImdHWXZpVWpxaTRSQkxHdTRhS2p4eHRnTnJjZnlReVNneUNaelQ1Ym1LUFp5L0VWYzlYRTdhcnlRSE1L" +
                "QWU0MnVERHNrK2s5dytRa0FZSk9Lb0tDSHhUNWM4Yk1MUXNmKy9Cclc4cDd6ais0aXhMdjJFVVpoZks0a3d0cHhK" +
                "M3VSM0h4OVpJZzFwVFEraFVzTS9rZXNLWGtadjJKMVRleU0vTTZqdDN0OHk2MERmZVBOdVlsTmFLWmF6eFBrQldN" +
                "UXNmcEtvSGRnN0lkU0QweDY5a3hxOUFZL040ckthV2hVeHR3SzZ4VHptZlRsZG1ZcTFRb1BLN0tEUEh3ZUJJU0pN" +
                "amd3R0dJeFc4bUNSQ2hGUjYvVUF2d0IzVFdpRmttMFhicmZhcm89IiwidCI6InUwa0NxSlBzdThIYkwrOElzM3V5" +
                "ZEZNZEZLNVRLMkFHN1Q4MUtsZDZGWXM9In0sInNlbGYiOnsiZWsiOiJaTi92QXgzZkJTTjBpWStOaUVYSlE0TCs4" +
                "RW5pbmdsRWl1SGJmUjBIRmtBPSIsImN0Ijoib3QvaHBodjY1SG4zbHhVYkYxbTE1MEtmcUdpVCtETFZ5QmJxUWJH" +
                "MUpsZlFkd0xYVEpKckpLai9sN3BtTnl4OTROLzBhZHRxY2JKSDkzVzZrTHdQbStvUE5Yb1ZCLzVPeE13TEtnVmR5" +
                "Vlk4eXBEcWdrWmpEVVh2YmRsbDBmbEZmZWxIUjE4OUZEN2d2NURvV1V3M2F1QnlyUW5MKzhyU1VkTEFTTkVtaTgw" +
                "RkgwZURoKzdhc0NDY2luRFdqMzJ4bXRxTHBXSXRjWURUR212TmJoTUlNeUYwQzlpem1RdmZHOUZFME5adlNKNHVQ" +
                "aThLOTdWNTFGWTNQU0haQ2tiYTRZQWNUdStxNlpnRlladVdweldkRnRHcjl0MzVwQkVuWVVEdVpzaz0iLCJ0Ijoi" +
                "cllhcm9RNWZZY2ZlZ2lEa2JvN3RKUThoNVVyaVE5ajI2NnN4S1FwcDBHST0ifX0="
    }
}
