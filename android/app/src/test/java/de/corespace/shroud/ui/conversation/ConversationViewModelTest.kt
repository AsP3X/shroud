package de.corespace.shroud.ui.conversation

import androidx.compose.ui.geometry.Rect
import de.corespace.shroud.core.model.ChatDeleteOutcome
import de.corespace.shroud.core.model.ChatPeerActivity
import de.corespace.shroud.core.model.Haptic
import de.corespace.shroud.core.model.NOTES_PEER_ID
import de.corespace.shroud.core.model.ReactionFailure
import de.corespace.shroud.core.model.ReceiptStatus
import de.corespace.shroud.core.net.CallModality
import de.corespace.shroud.core.net.ConversationDeleteScope
import de.corespace.shroud.core.net.MessageDeleteScope
import de.corespace.shroud.core.net.PresenceDto
import de.corespace.shroud.ui.components.Toast
import de.corespace.shroud.ui.conversation.ConversationFixtures.PEER
import de.corespace.shroud.ui.conversation.ConversationFixtures.message
import de.corespace.shroud.ui.conversation.menu.MessageMenuAction
import de.corespace.shroud.ui.conversation.menu.MessageMenuState
import de.corespace.shroud.ui.theme.Motion
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.ZoneId
import java.util.Locale
import java.util.UUID

/**
 * The conversation's state rules (`ConversationView.swift`; conversation-thread §1.3, §1.7, §3.10,
 * §3.11, §13, §14.6, §16): opening and leaving, the failed first load and its retry, quote jumps, the
 * menu's actions, deletes, calls, reactions, the hold guard and what a lock or purge drops.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ConversationViewModelTest {
    private class Harness(
        val test: TestScope,
        peer: UUID = PEER,
        val backend: FakeConversationBackend = FakeConversationBackend(),
    ) {
        val compose = FakeConversationCompose()
        val haptics = ArrayList<Haptic>()
        var backs = 0
        var now = 10_000L
        val job = Job()
        val scope = CoroutineScope(UnconfinedTestDispatcher(test.testScheduler) + job)
        val vm by lazy {
            ConversationViewModel(
                peer = peer,
                username = "ana",
                backend = backend,
                scope = scope,
                haptic = { haptics += it },
                onBack = { backs++ },
                now = { now },
            ).also { it.compose = compose }
        }

        val toast: Toast? get() = vm.toasts.current

        fun close() {
            vm.close()
            scope.cancel()
        }
    }

    private fun harness(test: TestScope, peer: UUID = PEER, thread: List<de.corespace.shroud.core.model.ChatMessage> = emptyList()): Harness {
        val backend = FakeConversationBackend()
        if (thread.isNotEmpty()) backend.setThread(peer, thread)
        return Harness(test, peer, backend)
    }

    // ---- Opening and leaving (CV:345-379; §3.11) --------------------------------------------------

    @Test
    fun openingMarksTheChatReadPinsToTheNewestAndReconciles() = runTest {
        val h = harness(this, thread = listOf(message(0), message(1)))
        h.vm.onAppear()
        assertEquals(listOf("setActivePeer:peer", "loadThread:reconcile=true"), h.backend.log)
        assertTrue(h.vm.didOpen)
        assertTrue("the opening pins at least once", h.vm.pinToBottomToken > 0)
        assertFalse("messages are here: no spinner", h.vm.loadingFirstPage)
        h.close()
    }

    @Test
    fun aChatWithNothingHereShowsTheSpinnerUntilTheFirstPageLands() = runTest {
        val h = harness(this)
        val page = CompletableDeferred<Unit>()
        h.backend.onLoad = { page.await() }
        h.vm.onAppear()
        assertTrue(h.vm.loadingFirstPage)
        val pinsBefore = h.vm.pinToBottomToken
        h.backend.setThread(PEER, listOf(message(0)))
        page.complete(Unit)
        runCurrent()
        assertFalse(h.vm.loadingFirstPage)
        assertNull(h.vm.firstLoadError)
        assertTrue("the opening pins again once the page is in", h.vm.pinToBottomToken > pinsBefore)
        h.close()
    }

    @Test
    fun aFailedFirstLoadKeepsTheSharedErrorAndRetriesOnceItClears() = runTest {
        val h = harness(this)
        h.backend.onLoad = { h.backend.lastError.value = "You're offline." }
        h.vm.onAppear()
        assertEquals("You're offline.", h.vm.firstLoadError)
        assertEquals(1, h.backend.log.count { it.startsWith("loadThread") })

        // The reconnect cleared the shared error while this chat is open: reload here (CV:1044-1051).
        h.backend.onLoad = { h.backend.setThread(PEER, listOf(message(0))) }
        h.backend.lastError.value = null
        runCurrent()
        assertEquals(2, h.backend.log.count { it.startsWith("loadThread") })
        assertNull(h.vm.firstLoadError)
        h.close()
    }

    @Test
    fun messagesArrivingAnywayClearTheFailedLoad() = runTest {
        val h = harness(this)
        h.backend.onLoad = { h.backend.lastError.value = "Server unreachable." }
        h.vm.onAppear()
        assertEquals("Server unreachable.", h.vm.firstLoadError)
        h.backend.setThread(PEER, listOf(message(0)))
        runCurrent()
        assertNull(h.vm.firstLoadError)
        h.close()
    }

    @Test
    fun leavingStopsTypingAndPlaybackAndGivesUpTheActivePeer() = runTest {
        val h = harness(this, thread = listOf(message(0)))
        h.vm.onAppear()
        h.backend.log.clear()
        h.vm.onDisappear()
        assertEquals(listOf("stopPlayback", "typing:false", "recording:false", "setActivePeer:null"), h.backend.log)
        assertEquals(listOf("leave:profilePushed=false"), h.compose.log)
        h.close()
    }

    @Test
    fun comingBackFromTheProfileKeepsTheReadersPlaceAndTheDraftsPreview() = runTest {
        val h = harness(this, thread = listOf(message(0)))
        h.vm.onAppear()
        val pins = h.vm.pinToBottomToken
        h.vm.willOpenProfile()
        h.vm.onDisappear()
        assertEquals(listOf("leave:profilePushed=true"), h.compose.log)

        h.backend.log.clear()
        h.vm.onAppear()
        assertEquals("no pin when coming back", pins, h.vm.pinToBottomToken)
        assertEquals(listOf("setActivePeer:peer", "loadThread:reconcile=true"), h.backend.log)
        h.close()
    }

    @Test
    fun closingWhileTheProfileCoversTheChatLeavesForGood() = runTest {
        val h = harness(this, thread = listOf(message(0)))
        h.vm.onAppear()
        h.vm.willOpenProfile()
        h.vm.onDisappear()
        h.compose.log.clear()
        assertEquals(1, h.backend.sinks.size)
        h.vm.close()
        assertEquals(listOf("leave:profilePushed=false"), h.compose.log)
        assertTrue("the artifact sink is unregistered", h.backend.sinks.isEmpty())
        h.scope.cancel()
    }

    @Test
    fun notesNeitherTypesNorShowsPresence() = runTest {
        val h = harness(this, peer = NOTES_PEER_ID, thread = listOf(message(0, mine = true, peer = NOTES_PEER_ID)))
        h.backend.presence.value = mapOf(NOTES_PEER_ID to PresenceDto(NOTES_PEER_ID, online = true))
        h.backend.peerActivities.value = mapOf(NOTES_PEER_ID to ChatPeerActivity.Typing)
        assertTrue(h.vm.isNotes)
        assertNull(h.vm.peerActivity)
        assertNull(h.vm.presence)
        assertFalse(h.vm.isOnline)
        h.vm.onAppear()
        h.vm.onDisappear()
        assertTrue(h.backend.log.none { it.startsWith("typing") || it.startsWith("recording") })
        val header = h.vm.headerState(Instant.EPOCH, ZoneId.of("UTC"), Locale.UK, is24h = true)
        assertEquals(ConversationPresence.NOTES, header.subtitle)
        assertFalse(header.subtitleIsAccent)
        h.close()
    }

    // ---- Header (CV:162-175, 821-833) -----------------------------------------------------------------

    @Test
    fun theHeaderSaysWhatThePeerIsDoing() = runTest {
        val h = harness(this, thread = listOf(message(0)))
        val zone = ZoneId.of("UTC")
        val now = Instant.parse("2026-10-02T12:00:00Z")
        fun subtitle() = h.vm.headerState(now, zone, Locale.UK, is24h = true).subtitle

        assertEquals("…", subtitle())
        h.backend.isOffline.value = true
        assertEquals("offline · local copy", subtitle())
        h.backend.isOffline.value = false
        h.backend.presence.value = mapOf(PEER to PresenceDto(PEER, online = false, lastSeenAt = Instant.parse("2026-10-02T09:41:00Z")))
        assertEquals("last seen 09:41", subtitle())
        h.backend.presence.value = mapOf(PEER to PresenceDto(PEER, online = true))
        assertEquals("online", subtitle())
        assertTrue(h.vm.headerState(now, zone, Locale.UK, true).subtitleIsAccent)
        h.backend.peerActivities.value = mapOf(PEER to ChatPeerActivity.Recording)
        assertEquals("recording…", subtitle())
        h.close()
    }

    @Test
    fun aCallThatCannotStartShowsCoresSentence() = runTest {
        val h = harness(this, thread = listOf(message(0)))
        h.backend.callError = "Microphone access is off for Shroud."
        h.vm.startCall(CallModality.Video)
        assertEquals(listOf("call:Video"), h.backend.log)
        assertEquals(Toast.failure("Microphone access is off for Shroud."), h.toast)

        h.backend.callError = null
        h.vm.toasts.dismiss()
        h.vm.startCall(CallModality.Voice)
        assertNull("a call that started says nothing here", h.toast)
        h.close()
    }

    // ---- Paging (CV:1119-1134) -------------------------------------------------------------------------

    @Test
    fun anOlderPageIsAskedForOnlyWhenTheServerHasOneAndNoneIsLoading() = runTest {
        val h = harness(this)
        h.vm.loadOlder()
        assertTrue("an empty chat has nothing older", h.backend.log.isEmpty())

        h.backend.setThread(PEER, listOf(message(0)))
        h.vm.loadOlder()
        assertEquals(listOf("loadOlder"), h.backend.log)

        h.backend.loadingOlderPeerIds.value = setOf(PEER)
        assertTrue(h.vm.isLoadingOlder)
        h.vm.loadOlder()
        assertEquals(1, h.backend.log.size)

        h.backend.loadingOlderPeerIds.value = emptySet()
        h.backend.olderHistoryExhausted.value = setOf(PEER)
        assertFalse(h.vm.hasOlderOnServer)
        h.vm.loadOlder()
        assertEquals(1, h.backend.log.size)
        h.close()
    }

    // ---- Quote jumps (CV:1301-1331) ---------------------------------------------------------------------

    @Test
    fun aQuoteNoLongerHereSaysSoAndOneStillHereFlashes() = runTest {
        val first = message(0)
        val h = harness(this, thread = listOf(first, message(1)))
        h.vm.jumpToQuoted(UUID.randomUUID())
        assertEquals(Toast.failure(ConversationViewModel.QUOTED_GONE), h.toast)
        assertEquals(listOf(Haptic.Warning), h.haptics)
        assertNull(h.vm.jumpTarget)

        h.vm.jumpToQuoted(first.id)
        val target = h.vm.jumpTarget!!
        assertEquals(first.id, target.messageId)
        h.vm.didJump(target)
        assertNull(h.vm.jumpTarget)
        assertEquals(first.id, h.vm.highlightedId)
        advanceTimeBy(ConversationViewModel.HIGHLIGHT_HOLD_MS - 1)
        assertEquals(first.id, h.vm.highlightedId)
        advanceTimeBy(2)
        assertNull(h.vm.highlightedId)

        // The same quote tapped twice is a new request.
        h.vm.jumpToQuoted(first.id)
        val again = h.vm.jumpTarget!!
        assertTrue(again != target)
        h.close()
    }

    // ---- The menu (CV:1936-2086, 2204-2254) ----------------------------------------------------------

    @Test
    fun theHoldThatOpenedTheMenuKeepsTheBubblesControlsAwayUntilTheMenuIsGone() = runTest {
        val held = message(0)
        val other = message(1)
        val h = harness(this, thread = listOf(held, other))
        h.vm.openMessageMenu(held, Rect(16f, 700f, 386f, 733f))
        assertEquals(listOf(Haptic.LongPress), h.haptics)
        assertFalse(h.vm.allowsInnerTaps(held.id))
        assertTrue(h.vm.allowsInnerTaps(other.id))
        h.vm.holdReleased()
        assertFalse("the menu's own bubble stays inert while it is open", h.vm.allowsInnerTaps(held.id))
        h.vm.menu.clear()
        assertTrue(h.vm.allowsInnerTaps(held.id))
        h.close()
    }

    @Test
    fun theMenuLiftsFromTheRowWhenNoBubbleFrameWasReported() = runTest {
        val m = message(0, mine = true)
        val h = harness(this, thread = listOf(m))
        val row = Rect(16f, 700f, 386f, 733f)
        h.vm.openMessageMenu(m, row)
        assertEquals(row, h.vm.menu.session?.sourceInRoot)
        h.vm.menu.clear()

        val bubble = Rect(200f, 700f, 386f, 733f)
        h.vm.reportBubbleBounds(m.id, bubble)
        h.vm.openMessageMenu(m, row)
        assertEquals(bubble, h.vm.menu.session?.sourceInRoot)
        h.close()
    }

    @Test
    fun theBackdropIgnoresTheReleaseOfTheHoldThatOpenedIt() = runTest {
        val m = message(0)
        val h = harness(this, thread = listOf(m))
        h.vm.openMessageMenu(m, Rect.Zero)
        h.now += MessageMenuState.TAP_GRACE_MS
        h.vm.onBackdropTap()
        assertFalse(h.vm.menu.isClosing)
        h.now += 1
        h.vm.onBackdropTap()
        assertTrue(h.vm.menu.isClosing)
        h.close()
    }

    @Test
    fun linksAndMediaRefuseWhileAMenuIsOpen() = runTest {
        val m = message(0, text = "see example.com")
        val h = harness(this, thread = listOf(m))
        h.vm.openMessageMenu(m, Rect.Zero)
        h.vm.onOpenLink("https://example.com")
        h.vm.onTapMedia(m)
        assertTrue(h.backend.log.none { it == "openLink" })
        assertTrue(h.compose.log.isEmpty())

        h.vm.menu.clear()
        h.vm.onOpenLink("https://example.com")
        assertTrue(h.backend.log.contains("openLink"))
        assertTrue("a link's tap is claimed so the row's double tap skips it", h.vm.tapClaim.isClaimed())
        h.close()
    }

    @Test
    fun copyConfirmsWithAToastOnlyWhereTheSystemDoesNot() = runTest {
        val m = message(0, text = "meet at nine, see example.com")
        val h = harness(this, thread = listOf(m))
        h.vm.onMenuAction(MessageMenuAction.Copy, m)
        assertEquals("meet at nine, see example.com", h.backend.clipboard)
        assertEquals(Toast.success(ConversationViewModel.COPIED), h.toast)
        h.vm.handleMenu(MessageMenuAction.CopyLink, m)
        assertEquals("https://example.com", h.backend.clipboard)
        assertEquals(Toast.success(ConversationViewModel.LINK_COPIED), h.toast)
        assertEquals(listOf(Haptic.Success, Haptic.Success), h.haptics)

        h.vm.toasts.dismiss()
        h.backend.confirmsCopies = true
        h.vm.handleMenu(MessageMenuAction.Copy, m)
        assertNull("Android 13+ shows its own confirmation", h.toast)
        assertEquals(Haptic.Success, h.haptics.last())
        h.close()
    }

    @Test
    fun actionsNotBuiltYetSayTheyAreComing() = runTest {
        val m = message(0)
        val h = harness(this, thread = listOf(m))
        for (action in listOf(MessageMenuAction.Edit, MessageMenuAction.Pin, MessageMenuAction.Forward, MessageMenuAction.Select)) {
            h.vm.handleMenu(action, m)
            assertEquals(Toast.info("${action.title} coming soon"), h.toast)
        }
        assertEquals(List(4) { Haptic.Light }, h.haptics)
        h.close()
    }

    @Test
    fun replyFromTheMenuStartsAReplyOnlyForQuotableMessages() = runTest {
        val m = message(0)
        val h = harness(this, thread = listOf(m))
        h.vm.handleMenu(MessageMenuAction.Reply, m)
        assertEquals(listOf("startReply"), h.compose.log)
        h.vm.startReply(message(1, deleted = true))
        assertEquals(1, h.compose.log.size)
        h.close()
    }

    @Test
    fun talkBackGetsTheRowsActionsInOrder() = runTest {
        val m = message(0, text = "see example.com")
        val h = harness(this, thread = listOf(m))
        assertEquals(listOf("Reply", "Message options", "Copy", "Copy Link", "Delete"), h.vm.rowActionLabels(m))
        assertEquals(listOf("Message options", "Delete"), h.vm.rowActionLabels(message(1, deleted = true)))

        // "Message options" opens the menu with no finger down: the bubble's controls stay reachable.
        val options = h.vm.accessibilityActions(m).first { it.label == ConversationViewModel.ACTION_OPTIONS }
        options.action()
        assertTrue(h.vm.menu.isOpen)
        assertFalse(h.vm.rowActionLabels(m).contains("Reply"))
        h.close()
    }

    // ---- Deletes (CV:229-254, 2256-2303) -------------------------------------------------------------

    @Test
    fun deletingAsksFirstThenSaysWhatWasDeleted() = runTest {
        val m = message(0, mine = true, receipt = ReceiptStatus.Delivered)
        val h = harness(this, thread = listOf(m))
        h.vm.handleMenu(MessageMenuAction.Delete, m)
        assertEquals(m, h.vm.pendingDelete)
        h.vm.performDelete(m, MessageDeleteScope.Everyone)
        assertNull(h.vm.pendingDelete)
        assertEquals("delete:Everyone", h.backend.log.last())
        assertEquals(Toast.success("Deleted for everyone"), h.toast)
        assertEquals(Haptic.Success, h.haptics.last())

        h.backend.deleteError = "Couldn't delete the message."
        h.vm.requestDelete(m)
        h.vm.performDelete(m, MessageDeleteScope.Me)
        assertEquals(Toast.failure("Couldn't delete the message."), h.toast)
        assertEquals(Haptic.Error, h.haptics.last())
        h.close()
    }

    @Test
    fun aDeleteAskedFromThePhotoViewerClosesItFirst() = runTest {
        val m = message(0, mine = true)
        val h = harness(this, thread = listOf(m))
        h.compose.isViewingMedia = true
        h.vm.requestDelete(m)
        h.vm.performDelete(m, MessageDeleteScope.Me)
        assertEquals(listOf("closeViewer"), h.compose.log)
        h.close()
    }

    @Test
    fun deleteAllNotesLeavesTheChatOnlyWhenItWorked() = runTest {
        val h = harness(this, peer = NOTES_PEER_ID, thread = listOf(message(0, mine = true, peer = NOTES_PEER_ID)))
        h.backend.deleteConversationOutcome = ChatDeleteOutcome.Failed("Couldn't delete your notes.")
        h.vm.askDeleteAllNotes()
        assertTrue(h.vm.showsNotesDeleteConfirm)
        h.vm.deleteAllNotes()
        assertFalse(h.vm.showsNotesDeleteConfirm)
        assertEquals("deleteConversation:${ConversationDeleteScope.Me}", h.backend.log.last())
        assertEquals(Toast.failure("Couldn't delete your notes."), h.toast)
        assertEquals(0, h.backs)

        h.backend.deleteConversationOutcome = ChatDeleteOutcome.ClearedForMe
        h.vm.deleteAllNotes()
        assertEquals(1, h.backs)
        assertEquals(Haptic.Success, h.haptics.last())
        h.close()
    }

    // ---- Reactions (CV:2120-2202, 422-426) -----------------------------------------------------------

    @Test
    fun aMessageNotYetSentCannotTakeAReaction() = runTest {
        val m = message(0)
        val h = harness(this, thread = listOf(m))
        h.backend.reactable = false
        h.vm.react("👍", m)
        assertEquals(Toast.info(ConversationViewModel.REACT_LATER), h.toast)
        assertTrue(h.backend.log.none { it.startsWith("toggleReaction") })
        h.close()
    }

    @Test
    fun aPickFromTheMenuLandsOnlyAfterTheBubbleIsBackInItsSlot() = runTest {
        val m = message(0)
        val h = harness(this, thread = listOf(m))
        h.vm.openMessageMenu(m, Rect.Zero)
        h.vm.reactAfterMenu("🔥", m, from = Rect(10f, 10f, 44f, 44f), reduceMotion = false)
        assertTrue(h.vm.menu.isClosing)
        assertEquals("🔥", h.vm.flights.flight?.emoji)
        advanceTimeBy(Motion.MENU_DROP_MS + ConversationViewModel.REACT_AFTER_MENU_SLACK_MS - 1)
        assertTrue(h.backend.log.none { it.startsWith("toggleReaction") })
        advanceTimeBy(2)
        assertEquals("toggleReaction:🔥", h.backend.log.last())
        h.close()
    }

    @Test
    fun takingBackOurOwnEmojiFliesNothing() = runTest {
        val m = message(0)
        val h = harness(this, thread = listOf(m))
        h.backend.myReactionsById[m.id] = listOf("🔥")
        h.vm.openMessageMenu(m, Rect.Zero)
        h.vm.reactAfterMenu("🔥", m, from = Rect(10f, 10f, 44f, 44f), reduceMotion = false)
        assertNull(h.vm.flights.flight)
        h.close()
    }

    @Test
    fun aDoubleTapReactsWithAHeartUnlessAMenuIsOpen() = runTest {
        val m = message(0)
        val h = harness(this, thread = listOf(m))
        h.vm.quickReact(m, h.vm.quickReactStart(androidx.compose.ui.geometry.Offset(100f, 200f), 44f), reduceMotion = false)
        assertEquals("toggleReaction:❤️", h.backend.log.last())
        val flight = h.vm.flights.flight!!
        assertEquals(ConversationViewModel.QUICK_START_SCALE, flight.fromScale, 0f)
        assertEquals(Rect(78f, 178f, 122f, 222f), flight.from)

        h.backend.log.clear()
        h.vm.openMessageMenu(m, Rect.Zero)
        h.vm.quickReact(m, Rect.Zero, reduceMotion = false)
        assertTrue(h.backend.log.isEmpty())
        h.close()
    }

    @Test
    fun aRefusedReactionInThisChatShowsCoresSentence() = runTest {
        val m = message(0)
        val h = harness(this, thread = listOf(m))
        h.backend.reactionFailures.tryEmit(ReactionFailure(UUID.randomUUID(), UUID.randomUUID(), "Couldn't save your reaction."))
        assertNull("another chat's failure", h.toast)
        h.backend.reactionFailures.tryEmit(ReactionFailure(UUID.randomUUID(), m.id, "You're offline. Your reaction wasn't saved."))
        assertEquals(Toast.failure("You're offline. Your reaction wasn't saved."), h.toast)
        assertEquals(Haptic.Error, h.haptics.last())
        h.close()
    }

    @Test
    fun aCallTakingTheMediaStopsTheTakeAndPlayback() = runTest {
        val h = harness(this, thread = listOf(message(0)))
        h.vm.toString()
        h.compose.isRecording = true
        h.backend.callMediaStarting.tryEmit(Unit)
        assertEquals(listOf("cancelTake"), h.compose.log)
        assertEquals("stopPlayback", h.backend.log.last())
        h.close()
    }

    // ---- Bubble callbacks (CV:1509-1677) -------------------------------------------------------------

    @Test
    fun bubbleCallbacksForwardToTheComposerAndTheEngines() = runTest {
        val photo = message(0, kind = de.corespace.shroud.core.model.ChatMessageKind.Image, text = "Photo")
        val h = harness(this, thread = listOf(photo))
        h.vm.onTapMedia(photo)
        h.vm.onCancelDownload(photo)
        assertEquals(listOf("mediaTap", "cancelDownload"), h.compose.log)
        assertTrue("the ring's ✕ claims the tap", h.vm.tapClaim.isClaimed())
        h.vm.onRetry(photo)
        assertEquals("retryImage", h.backend.log.last())
        h.vm.onToggleTodo(photo)
        assertEquals("toggleTodo", h.backend.log.last())
        assertTrue(h.vm.opensOnTap(photo))
        assertFalse(h.vm.reactsOnDoubleTap(photo))
        assertTrue(h.vm.reactsOnDoubleTap(message(1)))
        h.close()
    }

    // ---- Lock and purge (invariant 5; conversation-thread §19) --------------------------------------

    @Test
    fun aLockDropsEverythingReadableTheScreenHeld() = runTest {
        val m = message(0)
        val h = harness(this, thread = listOf(m))
        h.vm.requestDelete(m)
        h.vm.flashHighlight(m.id)
        h.vm.openMessageMenu(m, Rect.Zero)
        h.vm.quickReact(message(1), Rect.Zero, reduceMotion = false)
        h.backend.sinks.single().onSensitiveMemoryLocked()
        assertNull(h.vm.menu.session)
        assertNull(h.vm.pendingDelete)
        assertNull(h.vm.highlightedId)
        assertNull(h.vm.flights.flight)
        h.close()
    }

    @Test
    fun aPurgeDropsOnlyThatMessagesMenuAndDelete() = runTest {
        val gone = message(0)
        val kept = message(1)
        val h = harness(this, thread = listOf(gone, kept))
        h.vm.requestDelete(kept)
        h.vm.openMessageMenu(gone, Rect.Zero)
        h.backend.sinks.single().onPurged(listOf(gone.id))
        assertNull(h.vm.menu.session)
        assertEquals(kept, h.vm.pendingDelete)
        h.backend.sinks.single().onPurged(listOf(kept.id))
        assertNull(h.vm.pendingDelete)
        h.close()
    }
}
