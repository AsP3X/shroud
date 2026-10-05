package de.corespace.shroud.ui.conversation

import androidx.compose.ui.graphics.Color
import de.corespace.shroud.core.model.ChatMessageKind
import de.corespace.shroud.core.net.wire.LinkPreview
import de.corespace.shroud.core.model.ReceiptStatus
import de.corespace.shroud.core.net.MessageDeleteScope
import de.corespace.shroud.ui.conversation.ConversationFixtures.message
import de.corespace.shroud.ui.conversation.gestures.SwipeToReplyMetrics
import de.corespace.shroud.ui.conversation.gestures.SwipeToReplyMetrics.Decision
import de.corespace.shroud.ui.conversation.gestures.TapClaim
import de.corespace.shroud.ui.conversation.menu.MessageActions
import de.corespace.shroud.ui.conversation.menu.MessageMenuAction
import de.corespace.shroud.ui.theme.Motion
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.pow

/**
 * The thread's new Android tests that pin ported rules with no iOS test of their own
 * (conversation-thread §21, the W3-THREAD-LIST half): swipe-to-reply banding and recognition, what
 * the menu and TalkBack offer for a message, the delete sheet's choices, the menu springs and the tap
 * claim window.
 */
class ConversationRulesTest {
    // ---- Swipe to reply (STR:4-35, 66-87) -------------------------------------------------------------

    @Test
    fun swipeBandingFollowsTheFingerToTheThresholdAndRubberBandsAfter() {
        assertEquals(0f, SwipeToReplyMetrics.banded(-5f, 45f), 0f)
        assertEquals(45f, SwipeToReplyMetrics.banded(45f, 45f), 0f)
        assertEquals(73.571f, SwipeToReplyMetrics.banded(145f, 45f), 0.001f)
        val far = SwipeToReplyMetrics.banded(1000f, 60f)
        assertEquals(138.99f, far, 0.01f)
        assertTrue(far < SwipeToReplyMetrics.MAX_TRAVEL)
    }

    @Test
    fun swipeThresholdsAndIconInsetsDependOnTheSide() {
        assertEquals(45f, SwipeToReplyMetrics.threshold(isMine = false), 0f)
        assertEquals(60f, SwipeToReplyMetrics.threshold(isMine = true), 0f)
        assertEquals(8.5f, SwipeToReplyMetrics.iconInset(isMine = false), 0f)
        assertEquals(42.5f, SwipeToReplyMetrics.iconInset(isMine = true), 0f)
        assertEquals(33f, SwipeToReplyMetrics.ICON_SIDE, 0f)
    }

    @Test
    fun swipeRecognitionLeavesRightwardToBackAndVerticalToTheList() {
        assertEquals(Decision.Fail, SwipeToReplyMetrics.decide(3f, 0f))
        assertEquals(Decision.Fail, SwipeToReplyMetrics.decide(0f, 3f))
        assertEquals(Decision.Fail, SwipeToReplyMetrics.decide(-1f, -3f))
        assertEquals(Decision.Begin, SwipeToReplyMetrics.decide(-3f, 0f))
        assertEquals(Decision.Begin, SwipeToReplyMetrics.decide(-3f, 1f))
        assertEquals(Decision.Wait, SwipeToReplyMetrics.decide(-1f, 1f))
        assertEquals(Decision.Wait, SwipeToReplyMetrics.decide(-3f, 2f))
    }

    // ---- What the menu offers (CV:2032-2042, 2225-2264, 1403-1412) ----------------------------------

    @Test
    fun copyOffersTheWordsTheReaderSeesNeverAStandIn() {
        assertEquals("hi there", MessageActions.copyableText(message(0, text = "hi there")))
        assertNull(MessageActions.copyableText(message(0, text = "hi", deleted = true)))
        assertNull(MessageActions.copyableText(message(0, text = "[Unable to decrypt]")))
        assertNull(MessageActions.copyableText(message(0, text = "[Binary message]")))
        assertNull(MessageActions.copyableText(message(0, text = "  \n ")))
        assertNull(MessageActions.copyableText(message(0, text = "Photo", kind = ChatMessageKind.Image)))
        assertNull(MessageActions.copyableText(message(0, text = "Media", kind = ChatMessageKind.Image)))
        assertEquals("sunset", MessageActions.copyableText(message(0, text = "sunset", kind = ChatMessageKind.Image)))
        assertNull(MessageActions.copyableText(message(0, text = "Video", kind = ChatMessageKind.Video)))
        assertEquals("clip", MessageActions.copyableText(message(0, text = "clip", kind = ChatMessageKind.Video)))
        assertNull(MessageActions.copyableText(message(0, text = "Voice message", kind = ChatMessageKind.Voice)))
        assertEquals(
            "see you at nine",
            MessageActions.copyableText(message(0, text = "Voice message", kind = ChatMessageKind.Voice).copy(transcript = "see you at nine")),
        )
        assertEquals("buy milk", MessageActions.copyableText(message(0, text = "buy milk", kind = ChatMessageKind.Todo)))
    }

    @Test
    fun copyLinkTakesThePreviewThenTheFirstLinkAndAnAddressAsTyped() {
        assertNull(MessageActions.copyableLink(message(0, text = "no links here")))
        assertEquals("https://example.com", MessageActions.copyableLink(message(0, text = "see example.com now")))
        assertEquals("ana@example.com", MessageActions.copyableLink(message(0, text = "mail ana@example.com please")))
        val previewed = message(0, text = "look https://a.example/x").copy(linkPreview = LinkPreview(url = "https://b.example/page"))
        assertEquals("https://b.example/page", MessageActions.copyableLink(previewed))
        assertNull(MessageActions.copyableLink(message(0, text = "example.com", deleted = true)))
        assertNull(MessageActions.copyableLink(message(0, text = "example.com", kind = ChatMessageKind.Image)))
    }

    @Test
    fun deleteForEveryoneIsTheSendersOnceTheServerHasIt() {
        assertTrue(MessageActions.canDeleteForEveryone(message(0, mine = true), isNotes = false))
        assertFalse(MessageActions.canDeleteForEveryone(message(0, mine = false), isNotes = false))
        assertFalse(MessageActions.canDeleteForEveryone(message(0, mine = true), isNotes = true))
        assertFalse(MessageActions.canDeleteForEveryone(message(0, mine = true, deleted = true), isNotes = false))
        assertFalse(MessageActions.canDeleteForEveryone(message(0, mine = true, pendingSync = true), isNotes = false))
        assertFalse(MessageActions.canDeleteForEveryone(message(0, mine = true, receipt = ReceiptStatus.Failed), isNotes = false))
    }

    @Test
    fun theDeleteSheetOffersEveryoneFirstThenMeAndJustDeleteInNotes() {
        val own = DeleteMessageCopy.options(message(0, mine = true), isNotes = false)
        assertEquals(listOf("Delete for everyone", "Delete for me"), own.map { it.title })
        assertEquals(listOf(MessageDeleteScope.Everyone, MessageDeleteScope.Me), own.map { it.scope })
        assertEquals(listOf("Delete for me"), DeleteMessageCopy.options(message(0), isNotes = false).map { it.title })
        assertEquals(listOf("Delete"), DeleteMessageCopy.options(message(0, mine = true), isNotes = true).map { it.title })
        assertEquals("Deleted for everyone", DeleteMessageCopy.deleted(MessageDeleteScope.Everyone))
        assertEquals("Deleted", DeleteMessageCopy.deleted(MessageDeleteScope.Me))
    }

    @Test
    fun theMenuCardOffersWhatTheLiveMessageCanDoAndOnlyOwnTicks() {
        val sending = message(0, mine = true, receipt = ReceiptStatus.Sending, text = "Photo", kind = ChatMessageKind.Image)
        assertEquals(listOf(MessageMenuAction.Pin, MessageMenuAction.Forward, MessageMenuAction.Delete), MessageActions.menuActions(sending, hasLink = false))
        assertEquals(ReceiptStatus.Read, MessageActions.menuReceipt(message(0, mine = true, receipt = ReceiptStatus.Read), isNotes = false))
        assertNull(MessageActions.menuReceipt(message(0, mine = false, receipt = ReceiptStatus.Read), isNotes = false))
        assertNull(MessageActions.menuReceipt(message(0, mine = true), isNotes = true))
        assertNull(MessageActions.menuReceipt(message(0, mine = true, deleted = true), isNotes = false))
    }

    @Test
    fun menuActionTitlesAreTheDesignsCopy() {
        assertEquals(
            listOf("Reply", "Copy", "Copy Link", "Save to Downloads", "Share", "Edit", "Pin", "Forward", "Select", "Delete", "More"),
            MessageMenuAction.entries.map { it.title },
        )
        assertEquals(listOf(MessageMenuAction.Delete), MessageMenuAction.entries.filter { it.isDestructive })
    }

    // ---- Springs (conversation-thread §16.1, §23.4) ---------------------------------------------------

    @Test
    fun theMenuLiftIsTelegramsSpringAndSwiftSpringsConvert() {
        // Telegram's mass 5, stiffness 900, damping 104 → Compose (dampingRatio, stiffness per unit mass).
        assertEquals(0.7752f, Motion.MENU_LIFT_DAMPING_RATIO, 0.0001f)
        assertEquals(180f, Motion.MENU_LIFT_STIFFNESS, 0f)
        assertEquals(200L, Motion.MENU_DROP_MS)
        assertEquals(150L, Motion.REDUCED_MS)
        // SwiftUI `.spring(response:)`: stiffness = (2π / response)².
        assertEquals((2 * PI / 0.46).pow(2).toFloat(), Motion.swiftStiffness(0.46f), 0.01f)
    }

    // ---- Tap claims (MLP:39-62) -----------------------------------------------------------------------

    @Test
    fun aClaimHoldsForFourHundredMilliseconds() {
        var now = 1_000L
        val claim = TapClaim { now }
        assertFalse(claim.isClaimed())
        claim.claim()
        assertTrue(claim.isClaimed())
        now += 399
        assertTrue(claim.isClaimed())
        now += 1
        assertFalse(claim.isClaimed())
    }

    // ---- Header backdrop -------------------------------------------------------------------------------

    @Test
    fun theHeaderBackdropHoldsThroughTheBarAndEasesOutBelowIt() {
        val stops = headerBackdropStops(hold = 0.75f, peak = HEADER_SCRIM_BLURRED, color = Color.Black)
        assertEquals(0f to HEADER_SCRIM_BLURRED, stops.first().first to stops.first().second.alpha)
        // Full strength down to the bar's bottom, where the presence line sits.
        assertEquals(0.75f, stops[1].first, 0f)
        assertEquals(HEADER_SCRIM_BLURRED, stops[1].second.alpha, 0.001f)
        // Then down to clear at the end, never rising on the way.
        assertEquals(1f, stops.last().first, 0f)
        assertEquals(0f, stops.last().second.alpha, 0.001f)
        stops.toList().zipWithNext().forEach { (a, b) ->
            assertTrue(b.first >= a.first)
            assertTrue(b.second.alpha <= a.second.alpha + 0.001f)
        }
        // Eased: the first step of the tail loses less than a linear ramp would.
        assertTrue(stops[2].second.alpha > HEADER_SCRIM_BLURRED * (1f - 1f / (stops.size - 2)))
    }
}
