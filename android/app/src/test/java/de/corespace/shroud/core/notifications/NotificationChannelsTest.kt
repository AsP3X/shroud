package de.corespace.shroud.core.notifications

import android.app.NotificationManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Channel ids and their upkeep (notifications-push §5.6, N8, N9; `NotificationChannelsIdTest` of
 * §7.2): the sound and badge are part of the id, a change creates new channels and deletes the
 * old ones of our three families only.
 */
class NotificationChannelsTest {
    private var prefs = NotificationPrefsState()
    private val store = FakeChannelStore()
    private val channels = NotificationChannels(store) { prefs }

    @Test
    fun idsForEverySoundAndBadge() {
        assertEquals("messages.default.b", NotificationChannels.idFor(NotificationChannels.Family.Messages, NotificationPrefsState()))
        assertEquals(
            "messages.none.n",
            NotificationChannels.idFor(NotificationChannels.Family.Messages, NotificationPrefsState(sound = NotificationSound.None, badge = false)),
        )
        assertEquals(
            "contact_requests.chime.b",
            NotificationChannels.idFor(NotificationChannels.Family.ContactRequests, NotificationPrefsState(sound = NotificationSound.Chime)),
        )
        assertEquals(
            "calls.missed.pulse.n",
            NotificationChannels.idFor(NotificationChannels.Family.MissedCalls, NotificationPrefsState(sound = NotificationSound.Pulse, badge = false)),
        )
        val all = NotificationSound.entries.flatMap { sound ->
            listOf(true, false).flatMap { badge ->
                NotificationChannels.Family.entries.map { NotificationChannels.idFor(it, NotificationPrefsState(sound = sound, badge = badge)) }
            }
        }
        assertEquals("every combination has its own id", 7 * 2 * 3, all.toSet().size)
        for (id in all) assertTrue(id, NotificationChannels.familyOf(id) != null)
    }

    /** Calls' own channels and the background ones are never pruned (§5.18; 00-plan §1.7.10). */
    @Test
    fun pruningKeepsChannelsThatAreNotOurs() {
        for (id in listOf("calls.incoming", "calls.ongoing", "system", "push.background", "messages", "messages.default", "messages.loud.b", "calls.missed")) {
            assertNull(id, NotificationChannels.familyOf(id))
        }
        val existing = listOf("messages.default.b", "messages.chime.b", "calls.incoming", "calls.ongoing", "system", "push.background", "contact_requests.chime.n")
        assertEquals(listOf("messages.chime.b", "contact_requests.chime.n"), NotificationChannels.staleIds(existing, NotificationPrefsState()))
    }

    @Test
    fun ensureCreatesTheCurrentChannelsOnce() {
        channels.ensure()
        assertEquals(
            setOf("messages.default.b", "contact_requests.default.b", "calls.missed.default.b", "push.background"),
            store.channels.keys,
        )
        val messages = store.channels.getValue("messages.default.b")
        assertEquals("Messages", messages.name)
        assertEquals("New messages and reactions", messages.description)
        assertEquals(NotificationManager.IMPORTANCE_HIGH, messages.importance)
        assertEquals(NotificationSound.Standard, messages.sound)
        assertTrue(messages.badge)
        assertTrue(messages.vibrate)
        assertEquals("Contact requests", store.channels.getValue("contact_requests.default.b").name)
        assertEquals("Missed calls", store.channels.getValue("calls.missed.default.b").name)
        val background = store.channels.getValue("push.background")
        assertEquals(NotificationManager.IMPORTANCE_MIN, background.importance)
        assertNull(background.sound)
        assertFalse(background.badge)
        assertFalse(background.vibrate)

        store.created.clear()
        channels.ensure()
        assertTrue("nothing to do the second time", store.created.isEmpty())
        assertTrue(store.deleted.isEmpty())
    }

    /** The sound picker and the badge toggles (NSV:505-507, 391-402): new ids, old channels gone. */
    @Test
    fun aSoundOrBadgeChangeReplacesTheChannels() {
        store.channels["calls.incoming"] = ChannelSpec("calls.incoming", "Incoming calls", null, NotificationManager.IMPORTANCE_HIGH, null, false, false)
        channels.ensure()
        prefs = prefs.copy(sound = NotificationSound.Chime)
        channels.ensure()
        assertEquals(
            setOf("calls.incoming", "messages.chime.b", "contact_requests.chime.b", "calls.missed.chime.b", "push.background"),
            store.channels.keys,
        )
        assertEquals(NotificationSound.Chime, store.channels.getValue("messages.chime.b").sound)
        prefs = prefs.copy(sound = NotificationSound.None, badge = false)
        channels.ensure()
        assertNull("None is silent", store.channels.getValue("messages.none.n").sound)
        assertFalse(store.channels.getValue("messages.none.n").badge)
        assertEquals("messages.none.n", channels.messages())
        assertEquals("contact_requests.none.n", channels.contactRequests())
        assertEquals("calls.missed.none.n", channels.missedCalls())
    }

    /** A channel the user lowered or turned off keeps that on its replacement (§5.6). */
    @Test
    fun theUsersImportanceCarriesOver() {
        channels.ensure()
        store.blockMessages("messages.default.b")
        store.userSets("contact_requests.default.b", NotificationManager.IMPORTANCE_LOW)
        prefs = prefs.copy(sound = NotificationSound.Glass)
        channels.ensure()
        assertEquals(NotificationManager.IMPORTANCE_NONE, store.importance("messages.glass.b"))
        assertEquals(NotificationManager.IMPORTANCE_LOW, store.importance("contact_requests.glass.b"))
        assertEquals(NotificationManager.IMPORTANCE_HIGH, store.importance("calls.missed.glass.b"))
    }

    @Test
    fun theMessagesChannelCanBeBlocked() {
        channels.ensure()
        assertFalse(channels.messagesChannelBlocked())
        store.blockMessages(channels.messages())
        assertTrue(channels.messagesChannelBlocked())
    }

    @Test
    fun deletingTheFamiliesLeavesTheRest() {
        store.channels["calls.ongoing"] = ChannelSpec("calls.ongoing", "Ongoing call", null, NotificationManager.IMPORTANCE_LOW, null, false, false)
        channels.ensure()
        channels.deleteFamilies()
        assertEquals(setOf("calls.ongoing", "push.background"), store.channels.keys)
    }

    @Test
    fun theBackgroundTasksChannelIsMadeOnDemand() {
        channels.ensure()
        assertFalse("system" in store.channels)
        assertEquals("system", channels.backgroundTasks())
        assertEquals(NotificationManager.IMPORTANCE_MIN, store.importance("system"))
        assertEquals("Background tasks", store.channels.getValue("system").name)
        assertEquals("push.background", channels.backgroundConnection())
    }
}
