package de.corespace.shroud.ui.chats

import de.corespace.shroud.core.model.ChatDeleteOutcome
import de.corespace.shroud.core.model.Haptic
import de.corespace.shroud.core.model.MuteDuration
import de.corespace.shroud.core.net.ChatMuteDto
import de.corespace.shroud.core.net.ConversationDeleteScope
import de.corespace.shroud.ui.chats.ChatsFixtures.jane
import de.corespace.shroud.ui.components.Toast
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/**
 * The row menu's and the delete sheet's results (`ChatsView.swift:225-335`; shell-chats §8.8–§8.10):
 * which engine call runs, which toast shows (text, style, duration) and which haptic plays.
 */
class ChatsActionsTest {
    private val source = FakeChatsSource()
    private val feedback = ArrayList<ChatsFeedback>()
    private val labels = ArrayList<ChatMuteDto?>()

    private fun actions(scope: CoroutineScope) = ChatsActions(
        source = source,
        scope = scope,
        muteLabel = { mute ->
            labels += mute
            mute?.let { "Muted until 16:13" }
        },
        feedback = { feedback += it },
    )

    @Test
    fun markAsReadTapsLightlyWithoutAToast() = runTest {
        actions(this).markRead(jane)
        assertEquals(listOf("markChatRead:$jane"), source.log)
        assertEquals(listOf(ChatsFeedback(null, Haptic.Light)), feedback)
    }

    @Test
    fun aMuteShowsTheSavedMutesLabel() = runTest {
        val saved = ChatMuteDto(until = Instant.parse("2026-09-21T16:13:20Z"))
        source.mutes[jane] = saved
        actions(this).changeMute(jane, MuteDuration.Hour).join()
        assertEquals(listOf("muteChat:$jane:Hour"), source.log)
        assertEquals(listOf(saved), labels)
        assertEquals(listOf(ChatsFeedback(Toast.success("Muted until 16:13"), Haptic.Light)), feedback)
        assertEquals(Toast.SUCCESS_MS, feedback.single().toast!!.durationMillis)
    }

    @Test
    fun aMuteWithoutALabelSaysMuted() = runTest {
        actions(this).changeMute(jane, MuteDuration.Forever).join()
        assertEquals(listOf(ChatsFeedback(Toast.success("Muted"), Haptic.Light)), feedback)
    }

    @Test
    fun anUnmuteSaysNotificationsOn() = runTest {
        actions(this).changeMute(jane, null).join()
        assertEquals(listOf("unmuteChat:$jane"), source.log)
        assertTrue("no label is asked for an unmute", labels.isEmpty())
        assertEquals(listOf(ChatsFeedback(Toast.success("Notifications on"), Haptic.Light)), feedback)
    }

    @Test
    fun aFailedMuteShowsTheEnginesErrorAndBuzzes() = runTest {
        source.muteError = "A chat can be muted once it has messages."
        actions(this).changeMute(jane, MuteDuration.Day).join()
        val shown = feedback.single()
        assertEquals(Toast.failure("A chat can be muted once it has messages."), shown.toast)
        assertEquals(Toast.Style.Failure, shown.toast!!.style)
        assertEquals(Toast.FAILURE_MS, shown.toast.durationMillis)
        assertEquals(Haptic.Error, shown.haptic)
        assertTrue(labels.isEmpty())
    }

    @Test
    fun deleteOutcomesShowTheirToastsFor2400Ms() {
        assertEquals(ChatsFeedback(Toast("Chat deleted", Toast.Style.Success, 2_400), Haptic.Success), ChatsFeedbackRules.deleted(ChatDeleteOutcome.ClearedForMe, "jane"))
        assertEquals(ChatsFeedback(Toast("Chat deleted for both", Toast.Style.Success, 2_400), Haptic.Success), ChatsFeedbackRules.deleted(ChatDeleteOutcome.ClearedForBoth, "jane"))
        assertEquals(
            ChatsFeedback(Toast("Deleted · jane keeps their own messages", Toast.Style.Success, 2_400), Haptic.Success),
            ChatsFeedbackRules.deleted(ChatDeleteOutcome.UnsentForPeer, "jane"),
        )
        assertEquals(
            ChatsFeedback(Toast("Saved Messages can only be deleted for you.", Toast.Style.Failure, 2_400), Haptic.Error),
            ChatsFeedbackRules.deleted(ChatDeleteOutcome.Failed("Saved Messages can only be deleted for you."), "Notes to me"),
        )
    }

    @Test
    fun aDeleteRunsWithItsScopeAndReports() = runTest {
        source.deleteOutcome = ChatDeleteOutcome.UnsentForPeer
        actions(this).delete(PendingChatDelete(jane, "jane", isNotes = false), ConversationDeleteScope.Everyone).join()
        assertEquals(listOf("deleteConversation:$jane:everyone"), source.log)
        assertEquals("Deleted · jane keeps their own messages", feedback.single().toast!!.message)
    }

    @Test
    fun aDeleteOutlivesTheScreenThatAskedForIt() = runTest {
        // The actions run in the app's scope (iOS `Task {}`): leaving the list does not cancel them.
        val appScope = CoroutineScope(StandardTestDispatcher(testScheduler) + SupervisorJob())
        val gate = CompletableDeferred<Unit>()
        val slow = object : ChatsSource by source {
            override suspend fun deleteConversation(peer: java.util.UUID, scope: ConversationDeleteScope): ChatDeleteOutcome {
                gate.await()
                return source.deleteConversation(peer, scope)
            }
        }
        val screenScope = CoroutineScope(StandardTestDispatcher(testScheduler) + SupervisorJob())
        val job = ChatsActions(slow, appScope, { null }, { feedback += it }).delete(PendingChatDelete(jane, "jane", false), ConversationDeleteScope.Me)
        testScheduler.runCurrent()
        screenScope.cancel()
        gate.complete(Unit)
        job.join()
        assertEquals("Chat deleted", feedback.single().toast!!.message)
        appScope.cancel()
    }

    @Test
    fun markedReadFeedbackHasNoToast() {
        assertNull(ChatsFeedbackRules.markedRead.toast)
    }
}
