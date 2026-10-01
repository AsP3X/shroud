package de.corespace.shroud.core.messaging.reactions

import de.corespace.shroud.core.net.ReactionDto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.util.UUID

/**
 * The last-write-wins rules that keep late or stale changes from resurrecting a reaction. Ports the
 * merge part of `ios/shroudTests/MessageReactionTests.swift:155-312` (rebase, apply, reconcile,
 * replacing, chips, toggled) and of `web/src/reactions.selftest.ts:68-130` (incl. `pageReactionsFor`);
 * vectors verbatim. The stored-message case (`storedMessagesWithoutReactionsStillDecode`, `:289-305`)
 * belongs to the local store (W2-MSG-STORE).
 */
class ReactionMergeTest {
    // The web selftest's ids; iOS uses random ones (no vector depends on their value).
    private val me = UUID.fromString("aaaaaaaa-0000-4000-8000-000000000001")
    private val peer = UUID.fromString("bbbbbbbb-0000-4000-8000-000000000002")

    private fun entry(user: UUID, emojis: List<String>, seq: Long, pending: Boolean = false) =
        MessageReaction(userId = user, emojis = emojis, seq = seq, pending = pending)

    // --- rebase --------------------------------------------------------------------------------------

    @Test
    fun rebasingKeepsBothDevicesPicks() { // iOS :155-167, web :105-110
        // The phone had ❤️ and added 👍; the laptop, still seeing ❤️, added 🔥.
        assertEquals(listOf("❤️", "👍", "🔥"), ReactionMerge.rebased(listOf("❤️", "🔥"), listOf("❤️"), listOf("❤️", "👍"), 5))
        // The laptop took ❤️ back: the phone's 👍 stays.
        assertEquals(listOf("👍"), ReactionMerge.rebased(emptyList(), listOf("❤️"), listOf("❤️", "👍"), 5))
        // Both added the same one: once.
        assertEquals(listOf("🔥"), ReactionMerge.rebased(listOf("🔥"), emptyList(), listOf("🔥"), 5))
        // The phone took everything back meanwhile: only what the laptop added survives.
        assertEquals(listOf("🔥"), ReactionMerge.rebased(listOf("❤️", "🔥"), listOf("❤️"), emptyList(), 5))
        // Past the limit the oldest go.
        assertEquals(listOf("👍", "❤️", "🔥"), ReactionMerge.rebased(listOf("😮", "🔥"), listOf("😮"), listOf("😮", "👍", "❤️"), 3))
    }

    @Test
    fun rebaseNeverGoesBelowOne() {
        assertEquals(listOf("🔥"), ReactionMerge.rebased(listOf("❤️", "🔥"), listOf("❤️"), listOf("👍"), 0))
    }

    // --- apply ---------------------------------------------------------------------------------------

    @Test
    fun newerChangeWinsAndOlderIsIgnored() { // iOS :169-176, web :79-82
        val held = listOf(entry(peer, listOf("❤️"), 5))
        val replaced = ReactionMerge.apply(entry(peer, listOf("🔥"), 7), held)!!
        assertEquals(listOf(entry(peer, listOf("🔥"), 7)), replaced)
        assertNull("a late event is stale", ReactionMerge.apply(entry(peer, listOf("👍"), 6), replaced))
        assertNull("a replay changes nothing", ReactionMerge.apply(entry(peer, listOf("🔥"), 7), replaced))
    }

    @Test
    fun removalIsKeptSoAnOlderSetCannotResurrectIt() { // iOS :178-183, web :84-86
        val removed = ReactionMerge.apply(entry(peer, emptyList(), 9), listOf(entry(peer, listOf("❤️"), 5)))!!
        assertEquals(listOf(entry(peer, emptyList(), 9)), removed)
        assertTrue(ReactionMerge.chips(removed, me).isEmpty())
        assertNull(ReactionMerge.apply(entry(peer, listOf("❤️"), 5), removed))
    }

    @Test
    fun pendingChangeOfOursOutlivesServerEvents() { // iOS :185-192, web :88-89
        val held = listOf(entry(me, listOf("👍"), 3, pending = true))
        // Our other device's older state arriving over the socket must not undo the tap.
        assertNull(ReactionMerge.apply(entry(me, listOf("😢"), 8), held))
        val confirmed = ReactionMerge.replacing(me, entry(me, listOf("👍"), 10), held)
        assertEquals(listOf(entry(me, listOf("👍"), 10)), confirmed)
        assertFalse("the ack replaces the pending entry", confirmed.single().pending)
    }

    @Test
    fun aNewPersonIsAddedAndAPendingChangeReplacesOurs() {
        val held = listOf(entry(peer, listOf("❤️"), 5))
        assertEquals(listOf(entry(peer, listOf("❤️"), 5), entry(me, listOf("🔥"), 2)).sortedBy { it.seq },
            ReactionMerge.apply(entry(me, listOf("🔥"), 2), held))
        // A pending change of ours is applied whatever its seq (it keeps the previous one).
        val ours = listOf(entry(me, listOf("🔥"), 6))
        assertEquals(listOf(entry(me, listOf("😮"), 6, pending = true)), ReactionMerge.apply(entry(me, listOf("😮"), 6, pending = true), ours))
    }

    @Test
    fun entriesSortBySeqWithPendingLastAndTiesByUserId() { // MessageReactions.swift:147-153
        val a = UUID.fromString("0a000000-0000-4000-8000-000000000000")
        val b = UUID.fromString("b0000000-0000-4000-8000-000000000000")
        val c = UUID.fromString("c0000000-0000-4000-8000-000000000000")
        val sorted = ReactionMerge.replacing(
            c,
            entry(c, listOf("🔥"), 1, pending = true),
            listOf(entry(b, listOf("❤️"), 4), entry(a, listOf("👍"), 4), entry(me, listOf("😮"), 2)),
        )
        assertEquals(listOf(me, a, b, c), sorted.map { it.userId })
        // Dropped when the replacement is null.
        assertEquals(listOf(me, a, b), ReactionMerge.replacing(c, null, sorted).map { it.userId })
    }

    // --- reconcile with a history page ---------------------------------------------------------------

    @Test
    fun pageReconcileDropsWhatThePageNoLongerLists() { // iOS :194-200
        val held = listOf(entry(peer, listOf("❤️"), 4), entry(me, listOf("👍"), 12))
        // Snapshot 10: the peer's heart is gone by then; our 12 happened after the page was read.
        assertEquals(listOf(entry(me, listOf("👍"), 12)), ReactionMerge.reconcile(held, emptyList(), 10))
    }

    @Test
    fun pageReconcileTakesThePageUpToItsSnapshot() { // iOS :202-209
        val held = listOf(entry(peer, listOf("❤️"), 4), entry(me, emptyList(), 6))
        val page = listOf(entry(peer, listOf("🔥"), 9))
        assertEquals(page, ReactionMerge.reconcile(held, page, 9))
        val pending = entry(me, listOf("😮"), 6, pending = true)
        assertEquals(listOf(entry(peer, listOf("🔥"), 9), pending), ReactionMerge.reconcile(listOf(pending), page, 9))
    }

    @Test
    fun pageEntryNewerThanItsSnapshotBeatsAnOlderHeldOne() { // iOS :211-219
        // The server reads the snapshot before the page: the page may carry seq 12 > 10.
        val held = listOf(entry(peer, listOf("❤️"), 11))
        val page = listOf(entry(peer, listOf("🔥"), 12))
        assertEquals(page, ReactionMerge.reconcile(held, page, 10))
        // …while a held entry newer than both still wins.
        assertEquals(listOf(entry(peer, listOf("😮"), 13)), ReactionMerge.reconcile(listOf(entry(peer, listOf("😮"), 13)), page, 10))
    }

    @Test
    fun reconcileKeepsTheNewestPageEntryPerPerson() {
        val page = listOf(entry(peer, listOf("❤️"), 7), entry(peer, listOf("🔥"), 9), entry(peer, listOf("👍"), 8))
        assertEquals(listOf(entry(peer, listOf("🔥"), 9)), ReactionMerge.reconcile(emptyList(), page, 9))
    }

    // --- chips ---------------------------------------------------------------------------------------

    @Test
    fun onePersonsReactionsShareOneChip() { // iOS :262-279, web :91-102
        // Several emoji by one person: one chip, not one per emoji.
        val chips = ReactionMerge.chips(listOf(entry(peer, listOf("❤️", "🔥", "👍"), 2), entry(me, listOf("😮"), 3)), me)
        assertEquals(listOf(listOf("❤️", "🔥", "👍"), listOf("😮")), chips.map { it.emojis })
        assertEquals(listOf(false, true), chips.map { it.includesMe })

        // The same emoji picked by both people: one chip with both faces.
        val shared = ReactionMerge.chips(listOf(entry(peer, listOf("❤️", "🔥"), 2), entry(me, listOf("🔥", "❤️"), 3)), me)
        assertEquals(1, shared.size)
        assertEquals(listOf(peer, me), shared[0].userIds)
        assertTrue(shared[0].includesMe)
        assertEquals(listOf("❤️", "🔥"), shared[0].emojis)

        // The other side's chip first, ours after, whoever reacted first.
        val order = ReactionMerge.chips(listOf(entry(me, listOf("👍"), 1), entry(peer, listOf("🔥"), 2)), me)
        assertEquals(listOf(listOf("🔥"), listOf("👍")), order.map { it.emojis })
        assertEquals(listOf("👍", "🔥"), ReactionMerge.emojis(me, listOf(entry(me, listOf("👍", "🔥"), 1))))
        assertEquals(emptyList<String>(), ReactionMerge.emojis(peer, listOf(entry(me, listOf("👍"), 1))))
    }

    @Test
    fun chipsWithoutAKnownMeIncludeNobody() {
        val chips = ReactionMerge.chips(listOf(entry(peer, listOf("🔥"), 1), entry(me, listOf("🔥"), 2)), null)
        assertEquals(1, chips.size)
        assertFalse(chips[0].includesMe)
        assertEquals("bbbbbbbb-0000-4000-8000-000000000002+aaaaaaaa-0000-4000-8000-000000000001", chips[0].id)
    }

    // --- toggled -------------------------------------------------------------------------------------

    @Test
    fun pickingTogglesAndTheLimitDropsTheOldest() { // iOS :276-287, web :99-103
        assertEquals(listOf("❤️"), ReactionMerge.toggled("❤️", emptyList(), 5))
        assertEquals(listOf("❤️", "🔥"), ReactionMerge.toggled("🔥", listOf("❤️"), 5))
        assertEquals("a second pick takes it back", listOf("🔥"), ReactionMerge.toggled("❤️", listOf("❤️", "🔥"), 5))
        val full = listOf("❤️", "🔥", "👍", "😮", "🙏")
        assertEquals(listOf("🔥", "👍", "😮", "🙏", "🎉"), ReactionMerge.toggled("🎉", full, 5))
        // A limit lowered on the server trims on the next pick, never before.
        assertEquals(listOf("😮", "🙏", "🎉"), ReactionMerge.toggled("🎉", full, 3))
        assertEquals(listOf("🔥", "👍", "😮", "🙏"), ReactionMerge.toggled("❤️", full, 3))
        assertEquals("never below one", listOf("❤️"), ReactionMerge.toggled("❤️", emptyList(), 0))
    }

    // --- history pages (web pageReactionsFor; MessagingController.swift:1180-1187) -------------------

    @Test
    fun pageRecordsAreThisMessagesMembersNewestOnly() { // web :124-131
        val message = UUID.fromString("3f2504e0-4f89-41d3-9a0c-0305e82c3301")
        val other = UUID.fromString("cccccccc-0000-4000-8000-000000000003")
        fun dto(messageId: UUID, userId: UUID, ciphertext: String, seq: Long) =
            ReactionDto(messageId = messageId, userId = userId, ciphertext = ciphertext, seq = seq, updatedAt = Instant.EPOCH)
        val wires = listOf(
            dto(message, peer, "a", 3),
            dto(message, peer, "b", 5),
            dto(other, peer, "c", 6),
            dto(message, other, "d", 7),
        )
        val trusted = ReactionMerge.pageRecords(message, wires, setOf(me, peer))
        assertEquals(1, trusted.size)
        assertEquals("b", trusted[0].ciphertext)
        // The newest wins whatever order the page lists them in.
        assertEquals("b", ReactionMerge.pageRecords(message, wires.reversed(), setOf(me, peer)).single().ciphertext)
    }

    // --- model ---------------------------------------------------------------------------------------

    @Test
    fun aRemovalIsNotLiveAndNothingPrintsTheEmoji() {
        assertFalse(entry(peer, emptyList(), 3).isLive)
        assertTrue(entry(peer, listOf("🔥"), 3).isLive)
        assertFalse(entry(peer, listOf("🔥"), 3).toString().contains("🔥"))
        assertFalse(ReactionChip(listOf("🔥"), listOf(peer), false).toString().contains("🔥"))
    }
}
