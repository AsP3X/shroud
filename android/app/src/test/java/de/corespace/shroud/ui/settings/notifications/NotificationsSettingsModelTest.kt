package de.corespace.shroud.ui.settings.notifications

import de.corespace.shroud.core.model.Haptic
import de.corespace.shroud.core.notifications.NotificationAuthorization
import de.corespace.shroud.core.notifications.NotificationPreferences
import de.corespace.shroud.core.notifications.NotificationSound
import de.corespace.shroud.core.storage.StorageSeal
import de.corespace.shroud.testing.FakeSharedPreferences
import de.corespace.shroud.ui.components.Toast
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

/**
 * Notifications and Sounds' switch binding, debounced server save, reset and unmute (iOS
 * `NotificationsSettingsView.swift:349-402`; settings-lock §6.4; notifications-push §5.14), the
 * card priority of the Android system blocks (§5.14.2, N14), the push footer by delivery path and
 * the Sound picker's save (`:502-537`).
 */
class NotificationsSettingsModelTest {
    private val prefsFile = FakeSharedPreferences()
    private val preferences = NotificationPreferences(prefsFile, StorageSeal())
    private val saves = ArrayList<String>()
    private var saveFailure: Exception? = null
    private var badgeUpdates = 0
    private val haptics = ArrayList<Haptic>()
    private val toasts = ArrayList<Toast>()

    private fun TestScope.model(token: String? = "tok", unmute: suspend (UUID) -> String? = { null }) = NotificationsSettingsModel(
        preferences = preferences,
        pushSettings = { bearer ->
            saveFailure?.let { throw it }
            saves += bearer
        },
        sendTest = { "Sent. It should arrive in a moment." },
        token = { token },
        updateBadge = { badgeUpdates++ },
        unmuteChat = unmute,
        noun = "phone",
        scope = this,
        haptic = { haptics += it },
        toast = { toasts += it },
    )

    @Test
    fun aFlippedSwitchWritesAndTicks() = runTest {
        val model = model()
        model.set(NotificationSwitch.Enabled, true)
        assertTrue(haptics.isEmpty())
        assertEquals(0, prefsFile.editCount)

        model.set(NotificationSwitch.Enabled, false)
        assertFalse(preferences.enabled)
        assertEquals(listOf(Haptic.Light), haptics)
        assertEquals(0, badgeUpdates)
    }

    /** A burst of flips sends one request, 500 ms after the last (`saveToServer`, `:375-389`). */
    @Test
    fun serverSwitchesSaveOnceAfterABurst() = runTest {
        val model = model()
        model.set(NotificationSwitch.ShowSender, false)
        advanceTimeBy(300)
        model.set(NotificationSwitch.Reactions, false)
        advanceTimeBy(499)
        runCurrent()
        assertTrue(saves.isEmpty())
        advanceTimeBy(2)
        runCurrent()
        assertEquals(listOf("tok"), saves)
    }

    @Test
    fun inAppSwitchesStayOnThePhone() = runTest {
        val model = model()
        model.set(NotificationSwitch.InAppBanners, false)
        model.set(NotificationSwitch.ShowPreview, false)
        model.set(NotificationSwitch.InAppSounds, false)
        model.set(NotificationSwitch.InAppVibrate, false)
        advanceUntilIdle()
        assertTrue(saves.isEmpty())
        assertFalse(preferences.inAppBanners)
        assertFalse(preferences.showPreview)
        assertEquals(4, haptics.size)
    }

    /** Show Badge and Include Muted Chats recount the badge and reach the server (`:398-399`). */
    @Test
    fun badgeSwitchesRecount() = runTest {
        val model = model()
        model.set(NotificationSwitch.Badge, false)
        model.set(NotificationSwitch.BadgeIncludesMuted, true)
        assertEquals(2, badgeUpdates)
        advanceUntilIdle()
        assertEquals(1, saves.size)
        assertTrue(preferences.badgeIncludesMuted)
    }

    @Test
    fun aFailedSaveSaysItStaysOnThePhone() = runTest {
        saveFailure = IllegalStateException("offline")
        val model = model()
        model.set(NotificationSwitch.Enabled, false)
        advanceUntilIdle()
        val toast = toasts.single()
        assertEquals("Saved on this phone — the server gets it next time", toast.message)
        assertEquals(Toast.Style.Info, toast.style)
        assertEquals(2_400L, toast.durationMillis)
    }

    @Test
    fun signedOutSavesNothing() = runTest {
        val model = model(token = null)
        model.set(NotificationSwitch.Enabled, false)
        advanceUntilIdle()
        assertTrue(saves.isEmpty())
        assertTrue(toasts.isEmpty())
    }

    /** Reset: a fresh install's switches, nothing stored, a recount and a save (`:68-75`). */
    @Test
    fun resetGoesBackToANewInstall() = runTest {
        val model = model()
        model.set(NotificationSwitch.Enabled, false)
        preferences.sound = NotificationSound.Chime
        haptics.clear()
        model.reset()
        assertTrue(preferences.enabled)
        assertEquals(NotificationSound.Standard, preferences.sound)
        assertTrue(prefsFile.keys.none { it.startsWith(NotificationPreferences.PREFIX) })
        assertEquals(1, badgeUpdates)
        assertEquals("Notification settings reset", toasts.single().message)
        assertEquals(listOf(Haptic.Success), haptics)
        advanceUntilIdle()
        assertEquals(1, saves.size)
    }

    @Test
    fun unmuting() = runTest {
        val peer = UUID.fromString("44444444-4444-4444-8444-444444444444")
        val answer = CompletableDeferred<String?>()
        val model = model(unmute = { answer.await() })
        model.unmute(peer)
        runCurrent()
        assertEquals(setOf(peer), model.state.value.unmuting)
        // A second tap while it runs does nothing.
        model.unmute(peer)
        answer.complete(null)
        advanceUntilIdle()
        assertTrue(model.state.value.unmuting.isEmpty())
        assertEquals(listOf(Haptic.Light), haptics)
        assertTrue(toasts.isEmpty())

        val failing = model(unmute = { "A chat can be muted once it has messages." })
        failing.unmute(peer)
        advanceUntilIdle()
        assertEquals(Haptic.Error, haptics.last())
        assertEquals("A chat can be muted once it has messages.", toasts.single().message)
        assertEquals(Toast.Style.Failure, toasts.single().style)
    }

    /** One card, in this order (notifications-push §5.14.2). */
    @Test
    fun systemNoticePriority() {
        val authorized = NotificationAuthorization.Authorized
        assertEquals(SystemNotice.AppBlocked, SystemNotice.of(NotificationAuthorization.Denied, messagesChannelBlocked = true, backgroundRestricted = true))
        assertEquals(SystemNotice.AllowNotifications, SystemNotice.of(NotificationAuthorization.NotDetermined, messagesChannelBlocked = true, backgroundRestricted = true))
        assertEquals(SystemNotice.MessagesChannelOff, SystemNotice.of(authorized, messagesChannelBlocked = true, backgroundRestricted = true))
        assertEquals(SystemNotice.BackgroundRestricted, SystemNotice.of(authorized, messagesChannelBlocked = false, backgroundRestricted = true))
        assertNull(SystemNotice.of(authorized, messagesChannelBlocked = false, backgroundRestricted = false))
    }

    /** No Apple, no Google: the name is only the distributor's concern on the UnifiedPush path. */
    @Test
    fun pushFooterByDeliveryPath() {
        assertEquals(
            "Notifications that arrive while Shroud is closed or locked never contain message text — the server can't read it. " +
                "With Show Sender on, the sender's name travels encrypted to this phone, so your UnifiedPush service can't read it either.",
            NotificationsCopy.pushFooter("phone", unifiedPush = true),
        )
        assertEquals(
            "Notifications that arrive while Shroud is closed or locked never contain message text — the server can't read it.",
            NotificationsCopy.pushFooter("tablet", unifiedPush = false),
        )
        assertEquals("New messages on this tablet while Shroud is closed or locked.", NotificationsCopy.showNotificationsDetail("tablet"))
        assertEquals(
            "Plays for notifications, and for banners while Shroud is open (unless the phone is on silent).",
            NotificationsCopy.soundFooter("phone"),
        )
        assertEquals("Sound, Chime", NotificationsCopy.soundLabel(NotificationSound.Chime))
    }

    /** Each choice is stored, played, and saved 500 ms later with errors ignored (`:502-537`). */
    @Test
    fun soundPicker() = runTest {
        val played = ArrayList<NotificationSound>()
        var failNext = true
        val picker = NotificationSoundModel(
            preferences = preferences,
            play = { played += it },
            pushSettings = { bearer ->
                if (failNext) {
                    failNext = false
                    throw IllegalStateException("offline")
                }
                saves += bearer
            },
            token = { "tok" },
            scope = this,
        )
        picker.pick(NotificationSound.Glass)
        advanceUntilIdle()
        assertEquals(NotificationSound.Glass, preferences.sound)
        assertTrue(saves.isEmpty())
        picker.pick(NotificationSound.Pop)
        picker.pick(NotificationSound.None)
        advanceUntilIdle()
        assertEquals(listOf(NotificationSound.Glass, NotificationSound.Pop, NotificationSound.None), played)
        assertEquals(NotificationSound.None, preferences.sound)
        assertEquals(listOf("tok"), saves)
        assertEquals("none", preferences.serverPatch().sound)
    }
}
