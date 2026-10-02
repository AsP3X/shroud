package de.corespace.shroud.ui.conversation.reactions

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import de.corespace.shroud.core.messaging.reactions.ReactionMerge
import de.corespace.shroud.core.model.MessageReaction
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.Locale
import java.util.UUID

/**
 * The rendering parts of `ios/shroudTests/MessageReactionTests.swift` (conversation-thread §20.7): the
 * footer geometry (`:314-363`) and the emoji flow (`:346-357`), byte for byte; plus the chip content,
 * TalkBack summary and actions the bubbles build from `ReactionMerge` chips (§14.1, §14.5). The merge
 * and toggle vectors themselves run in `ReactionMergeTest` (W1-WIRE).
 */
class ReactionRenderingTest {
    private val me = UUID.fromString("3f2c8a9e-5b1d-4c7a-9e2f-8d6b4a1c0e7f")
    private val peer = UUID.fromString("9b1deb4d-3b7d-4bad-9bdd-2b0d7b3dcb6d")
    private val message = UUID.fromString("0f1e2d3c-4b5a-4968-8776-655443322110")

    private var savedJoiner: ((List<String>) -> String)? = null

    @Before
    fun icuLists() {
        savedJoiner = ReactionSpeech.joiner
        ReactionSpeech.joiner = { names -> com.ibm.icu.text.ListFormatter.getInstance(Locale.US).format(names) }
    }

    @After
    fun restore() {
        savedJoiner?.let { ReactionSpeech.joiner = it }
    }

    // ---- Footer geometry (`MessageReactionTests.swift:314-363`) ----

    private val chip = Size(50f, 26f)
    private val meta = Size(44f, 13f)

    @Test
    fun timeSitsAtTheEndOfTheChipRowWhenItFits() {
        val result = ReactionFooterMath.arrange(chips = listOf(chip, chip), meta = meta, width = 200f)
        assertEquals(Offset(0f, 0f), result.frames[0].topLeft)
        assertEquals(Offset(56f, 0f), result.frames[1].topLeft)
        assertEquals(Size(50f + 6 + 50 + 8 + 44, 26f), result.size)
        assertTrue(result.frames[2].bottom <= 26f)
    }

    @Test
    fun timeDropsToItsOwnLineWhenTheRowIsFull() {
        val result = ReactionFooterMath.arrange(chips = listOf(chip, chip), meta = meta, width = 120f)
        assertEquals(Offset(0f, 26f + 6), result.frames[2].topLeft)
        assertEquals(26f + 6 + 13, result.size.height)
    }

    @Test
    fun chipsWrapAtTheBubbleWidth() {
        val result = ReactionFooterMath.arrange(chips = listOf(chip, chip, chip), meta = meta, width = 110f)
        assertEquals(Offset(0f, 32f), result.frames[2].topLeft)
        assertTrue(result.frames[3].top >= 32f)
        assertEquals(58f, result.size.height)
        assertTrue(result.size.width <= 110f)
    }

    @Test
    fun aWideSetWrapsInsideItsChip() {
        val emoji = Size(24f, 30f)
        val oneLine = ReactionEmojiFlowMath.arrange(List(5) { emoji }, width = null)
        assertEquals(Size(5 * 24f + 4 * 2, 30f), oneLine.size)

        val wrapped = ReactionEmojiFlowMath.arrange(List(20) { emoji }, width = 200f)
        assertEquals(Offset(0f, 30f), wrapped.frames[7].topLeft)
        assertEquals(Size(180f, 90f), wrapped.size)

        assertEquals(oneLine.size, ReactionEmojiFlowMath.arrange(List(5) { emoji }, width = 300f).size)
    }

    @Test
    fun unboundedWidthIsOneLine() {
        val result = ReactionFooterMath.arrange(chips = listOf(chip, chip, chip), meta = meta, width = null)
        assertEquals(26f, result.size.height)
    }

    // ---- Android additions ----

    /** No chips: the meta alone, at the start (the layout pins it to the trailing edge). */
    @Test
    fun noChipsIsJustTheMeta() {
        val result = ReactionFooterMath.arrange(chips = emptyList(), meta = meta, width = 200f)
        assertEquals(1, result.frames.size)
        assertEquals(meta, result.size)
    }

    /** The time sits centred on the chip row, 2 lower, never below it (`MessageReactionChips.swift:321-323`). */
    @Test
    fun theTimeIsCentredOnTheLastChipRowANudgeLow() {
        val result = ReactionFooterMath.arrange(chips = listOf(chip), meta = meta, width = 200f)
        assertEquals((26f - 13f) / 2 + 2, result.frames[1].top)
    }

    // ---- Chips for a bubble (conversation-thread §14.1) ----

    private fun entry(user: UUID, emojis: List<String>, seq: Long) = MessageReaction(user, emojis, seq)

    @Test
    fun chipsNameOurFaceAndTheirs() {
        val chips = ReactionMerge.chips(listOf(entry(peer, listOf("❤️", "🔥"), 2), entry(me, listOf("😮"), 3)), me)
        val content = ReactionChipContent.from(chips, me, myUsername = "alice", peerName = "bob", myEmojis = listOf("😮"), messageId = message)
        assertEquals(listOf(listOf("❤️", "🔥"), listOf("😮")), content.map { it.emojis })
        assertEquals("bob", content[0].reactors.single().name)
        assertFalse(content[0].reactors.single().isMe)
        assertEquals("alice", content[1].reactors.single().name)
        assertTrue(content[1].reactors.single().isMe)
        assertEquals(message, content[1].messageId)
        // Our face falls back to "You" without a username.
        assertEquals("You", ReactionChipContent.from(chips, me, null, "bob", emptyList(), null)[1].reactors.single().name)
    }

    @Test
    fun theSummaryNamesEveryoneWithTheirEmoji() {
        val chips = ReactionMerge.chips(listOf(entry(peer, listOf("❤️", "🔥"), 2), entry(me, listOf("👍"), 3)), me)
        val content = ReactionChipContent.from(chips, me, "alice", "anna", listOf("👍"), message)
        assertEquals("Reactions: anna ❤️ 🔥, you 👍", content.spokenSummary())
        assertNull(emptyList<ReactionChipContent>().spokenSummary())
    }

    @Test
    fun aSharedChipSpeaksBothNames() {
        val chips = ReactionMerge.chips(listOf(entry(peer, listOf("❤️"), 2), entry(me, listOf("❤️"), 3)), me)
        val content = ReactionChipContent.from(chips, me, "alice", "anna", listOf("❤️"), message)
        assertEquals(1, content.size)
        assertEquals("anna and you", content.single().spokenNames)
    }

    /** Ours are taken back, theirs added unless we have them already (`MessageReactionChips.swift:157-166`). */
    @Test
    fun whichEmojiAct() {
        val theirs = ReactionChipContent(listOf("❤️", "🔥"), emptyList(), includesMe = false, myEmojis = listOf("🔥"))
        assertTrue(ReactionChipMetrics.acts(theirs, "❤️", hasHandler = true))
        assertFalse(ReactionChipMetrics.acts(theirs, "🔥", hasHandler = true))
        val ours = ReactionChipContent(listOf("🔥"), emptyList(), includesMe = true, myEmojis = listOf("🔥"))
        assertTrue(ReactionChipMetrics.acts(ours, "🔥", hasHandler = true))
        assertFalse(ReactionChipMetrics.acts(ours, "🔥", hasHandler = false))
    }

    /** The bubble's TalkBack actions: each emoji once, in chip order, then the quick ❤️ (§14.5). */
    @Test
    fun accessibilityActionsCoverEveryEmojiOnceAndTheQuickReaction() {
        val chips = ReactionMerge.chips(listOf(entry(peer, listOf("🔥", "👍"), 2), entry(me, listOf("👍"), 3)), me)
        val content = ReactionChipContent.from(chips, me, "alice", "anna", listOf("👍"), message)
        assertEquals(
            listOf("React with 🔥", "Remove your 👍 reaction", "React with ❤️"),
            reactionActionLabels(content),
        )
        val withHeart = ReactionChipContent.from(
            ReactionMerge.chips(listOf(entry(peer, listOf("❤️"), 2)), me),
            me, "alice", "anna", emptyList(), message,
        )
        assertEquals(listOf("React with ❤️"), reactionActionLabels(withHeart))
        assertTrue(reactionAccessibilityActions(content, onTap = null).isEmpty())
        assertEquals(3, reactionAccessibilityActions(content, onTap = {}).size)
    }

    /** The chip id is the user ids joined — what flights and keys use (`:26`). */
    @Test
    fun chipIdentityIsTheUserIds() {
        val content = ReactionChipContent(listOf("❤️"), listOf(ReactionChipContent.Reactor(peer, "bob"), ReactionChipContent.Reactor(me, "alice", true)), true)
        assertEquals("9b1deb4d-3b7d-4bad-9bdd-2b0d7b3dcb6d+3f2c8a9e-5b1d-4c7a-9e2f-8d6b4a1c0e7f", content.id)
        // Nothing prints the emoji or the names.
        assertFalse(content.toString().contains("❤️"))
        assertFalse(content.toString().contains("bob"))
    }
}
