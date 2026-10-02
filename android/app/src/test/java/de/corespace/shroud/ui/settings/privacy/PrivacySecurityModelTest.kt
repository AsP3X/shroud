package de.corespace.shroud.ui.settings.privacy

import de.corespace.shroud.core.contacts.Privacy
import de.corespace.shroud.core.keys.BiometricLabel
import de.corespace.shroud.core.model.Haptic
import de.corespace.shroud.core.net.BlockItemDto
import de.corespace.shroud.core.net.PrivacySettingsDto
import de.corespace.shroud.core.net.UpdatePrivacySettingsBody
import de.corespace.shroud.core.storage.AutoLockDelay
import de.corespace.shroud.core.storage.SecurityPreferences
import de.corespace.shroud.core.storage.StorageSeal
import de.corespace.shroud.testing.FakeSharedPreferences
import de.corespace.shroud.ui.components.Toast
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.util.UUID

/**
 * Privacy and Security's actions and words (iOS `PrivacySecurityView`,
 * `ios/shroud/Features/Main/PrivacySecurityView.swift`; settings-lock §7; P3c, P5): the server
 * switch pattern (`:397-433` — the flip shows while it saves, then the server's value takes over and
 * reverts a failed write), one field per request, the load status (`:160-200`), Reset QR code
 * (`:537-550`), unblock (`:257-270`), the local switches, Lock chats now (`:627-634`), and the
 * Android copy: the Auto-lock footnote by biometric (`:307-312`), the device-protection subtitle by
 * API level (P5a), "this phone" / "this tablet".
 */
class PrivacySecurityModelTest {
    private val privacy = FakePrivacy()
    private val prefsFile = FakeSharedPreferences()
    private val security = SecurityPreferences(prefsFile, StorageSeal())
    private val calls = ArrayList<String>()
    private val haptics = ArrayList<Haptic>()
    private val toasts = ArrayList<Toast>()
    private var unblockError: String? = null
    private var locks = 0

    private fun TestScope.model(screen: CoroutineScope = this) = PrivacySecurityModel(
        privacy = privacy,
        security = security,
        refreshBlocks = { calls += "blocks" },
        unblockUser = { id ->
            calls += "unblock $id"
            unblockError
        },
        lockChatsNow = { locks++ },
        scope = screen,
        actionScope = this,
        haptic = { haptics += it },
        toast = { toasts += it },
    )

    private val alice = BlockItemDto(UUID.fromString("0f8fad5b-d9cb-469f-a165-70867728950e"), "alice", Instant.parse("2026-09-20T10:15:00Z"))

    // ---- Loading (`loadServerSettings`, `:160-168`) ----

    @Test
    fun loadReadsTheSwitchesThenTheBlockedList() = runTest {
        val model = model()
        model.loadServerSettings()
        assertEquals(listOf("privacy", "blocks"), calls)
        assertFalse(model.state.value.loadFailed)
        assertTrue(privacy.hasLoaded.value)
    }

    /** Both refreshes are silent on failure; whether the values arrived decides the line (`:165-166`). */
    @Test
    fun aFailedLoadOffersTryAgainAndARetryClearsIt() = runTest {
        val model = model()
        privacy.refreshWorks = false
        model.loadServerSettings()
        assertTrue(model.state.value.loadFailed)
        // The blocked list is still asked for.
        assertEquals(listOf("privacy", "blocks"), calls)
        // The switches stay disabled until the values arrive (`:430`, `:506`, `:621`).
        for (switch in PrivacySwitch.entries) assertFalse(model.state.value.isEnabled(switch, hasLoaded = false))

        privacy.refreshWorks = true
        model.retry()
        advanceUntilIdle()
        assertFalse(model.state.value.loadFailed)
        assertEquals(listOf("privacy", "blocks", "privacy", "blocks"), calls)
        for (switch in PrivacySwitch.entries) assertTrue(model.state.value.isEnabled(switch, hasLoaded = true))
    }

    /** A write keeps going when the screen closes meanwhile (an iOS `Task` outlives its view). */
    @Test
    fun aWriteOutlivesTheScreen() = runTest {
        val screen = CoroutineScope(coroutineContext + Job())
        val model = model(screen)
        privacy.loaded()
        val gate = CompletableDeferred<Unit>()
        privacy.gate = gate
        model.flip(PrivacySwitch.Discoverable, false)
        runCurrent()
        screen.cancel()
        gate.complete(Unit)
        advanceUntilIdle()
        assertFalse(privacy.settings.value.discoverableByUsername)
        assertTrue(model.state.value.saving.isEmpty())
        assertTrue(model.state.value.pending.isEmpty())
        assertEquals(listOf(Haptic.Light), haptics)
    }

    // ---- The server switches (`:397-433`) ----

    @Test
    fun aFlipShowsWhileItSavesThenTheServerValueTakesOver() = runTest {
        val model = model()
        privacy.loaded()
        val gate = CompletableDeferred<Unit>()
        privacy.gate = gate

        model.flip(PrivacySwitch.ReadReceipts, false)
        runCurrent()
        val saving = model.state.value
        assertFalse(saving.displayed(PrivacySwitch.ReadReceipts, privacy.settings.value))
        assertFalse(saving.isEnabled(PrivacySwitch.ReadReceipts, hasLoaded = true))
        // The other switches stay usable.
        assertTrue(saving.isEnabled(PrivacySwitch.Typing, hasLoaded = true))
        // A second flip while it saves does nothing.
        model.flip(PrivacySwitch.ReadReceipts, true)

        gate.complete(Unit)
        advanceUntilIdle()
        val done = model.state.value
        assertNull(done.pending[PrivacySwitch.ReadReceipts])
        assertTrue(done.saving.isEmpty())
        assertFalse(done.displayed(PrivacySwitch.ReadReceipts, privacy.settings.value))
        assertEquals(listOf(UpdatePrivacySettingsBody(sendReadReceipts = false)), privacy.updates)
        assertEquals(listOf(Haptic.Light), haptics)
        assertTrue(toasts.isEmpty())
    }

    /** A failed write: the server's value comes back, the reason as a failure toast, an error haptic. */
    @Test
    fun aFailedWriteRevertsTheSwitch() = runTest {
        val model = model()
        privacy.loaded()
        privacy.error = "Can't reach the server. Check your connection."

        model.flip(PrivacySwitch.Presence, false)
        // Where the user flipped it, at once (the write has not run yet).
        assertFalse(model.state.value.displayed(PrivacySwitch.Presence, privacy.settings.value))
        advanceUntilIdle()

        assertTrue(model.state.value.displayed(PrivacySwitch.Presence, privacy.settings.value))
        assertEquals(listOf(Toast.failure("Can't reach the server. Check your connection.")), toasts)
        assertEquals(listOf(Haptic.Error), haptics)
    }

    /** A flip to the value the server already has sends nothing (`guard newValue != …`, `:402`). */
    @Test
    fun aFlipToTheServerValueDoesNothing() = runTest {
        val model = model()
        privacy.loaded()
        model.flip(PrivacySwitch.Typing, true)
        model.flip(PrivacySwitch.ChatDelete, false)
        advanceUntilIdle()
        assertTrue(privacy.updates.isEmpty())
        assertTrue(privacy.chatDeleteWrites.isEmpty())
        assertTrue(haptics.isEmpty())
    }

    /** Each switch writes only its own field; the chat-delete consent goes through its own call (`:597`). */
    @Test
    fun eachSwitchSendsOnlyItsField() = runTest {
        val model = model()
        privacy.loaded()
        model.flip(PrivacySwitch.ReadReceipts, false)
        model.flip(PrivacySwitch.Typing, false)
        model.flip(PrivacySwitch.Presence, false)
        model.flip(PrivacySwitch.Discoverable, false)
        model.flip(PrivacySwitch.ChatDelete, true)
        advanceUntilIdle()
        assertEquals(
            listOf(
                UpdatePrivacySettingsBody(sendReadReceipts = false),
                UpdatePrivacySettingsBody(sendTyping = false),
                UpdatePrivacySettingsBody(sharePresence = false),
                UpdatePrivacySettingsBody(discoverableByUsername = false),
            ),
            privacy.updates,
        )
        assertEquals(listOf(true), privacy.chatDeleteWrites)
        assertEquals(
            PrivacySettingsDto(
                allowPeerChatDelete = true,
                sendReadReceipts = false,
                sendTyping = false,
                sharePresence = false,
                discoverableByUsername = false,
            ),
            privacy.settings.value,
        )
        assertEquals(List(5) { Haptic.Light }, haptics)
    }

    @Test
    fun theSwitchesReadTheirServerFields() {
        val settings = PrivacySettingsDto(
            allowPeerChatDelete = true,
            sendReadReceipts = false,
            sendTyping = true,
            sharePresence = false,
            discoverableByUsername = true,
        )
        assertFalse(PrivacySwitch.ReadReceipts.value(settings))
        assertTrue(PrivacySwitch.Typing.value(settings))
        assertFalse(PrivacySwitch.Presence.value(settings))
        assertTrue(PrivacySwitch.Discoverable.value(settings))
        assertTrue(PrivacySwitch.ChatDelete.value(settings))
        // The Visibility card in iOS order (`VisibilitySwitch`, `:646-649`).
        assertEquals(listOf(PrivacySwitch.ReadReceipts, PrivacySwitch.Typing, PrivacySwitch.Presence), PrivacySwitch.visibility)
    }

    // ---- Reset QR code (`:537-550`) ----

    @Test
    fun resetShareCode() = runTest {
        val model = model()
        val gate = CompletableDeferred<Unit>()
        privacy.gate = gate
        model.resetShareCode()
        runCurrent()
        assertTrue(model.state.value.resettingShareCode)
        model.resetShareCode()
        gate.complete(Unit)
        advanceUntilIdle()
        assertFalse(model.state.value.resettingShareCode)
        assertEquals(1, privacy.rotations)
        assertEquals(listOf(Toast.success("New QR code ready")), toasts)
        assertEquals(listOf(Haptic.Success), haptics)
    }

    @Test
    fun aFailedShareCodeResetSaysWhy() = runTest {
        val model = model()
        privacy.error = "Sign in to reset your QR code."
        model.resetShareCode()
        advanceUntilIdle()
        assertEquals(listOf(Toast.failure("Sign in to reset your QR code.")), toasts)
        assertEquals(listOf(Haptic.Error), haptics)
        assertFalse(model.state.value.resettingShareCode)
    }

    // ---- Blocked (`:257-270`) ----

    @Test
    fun unblock() = runTest {
        val model = model()
        model.unblock(alice)
        assertTrue(alice.userId in model.state.value.unblocking)
        // A second tap while it runs does nothing.
        model.unblock(alice)
        advanceUntilIdle()
        assertEquals(listOf("unblock ${alice.userId}"), calls)
        assertTrue(model.state.value.unblocking.isEmpty())
        assertEquals(listOf(Toast.success("alice unblocked")), toasts)
        assertEquals(listOf(Haptic.Light), haptics)
    }

    @Test
    fun aFailedUnblockSaysWhy() = runTest {
        val model = model()
        unblockError = "Too many requests. Try again later."
        model.unblock(alice)
        advanceUntilIdle()
        assertEquals(listOf(Toast.failure("Too many requests. Try again later.")), toasts)
        assertEquals(listOf(Haptic.Error), haptics)
        assertTrue(model.state.value.unblocking.isEmpty())
    }

    @Test
    fun theBlockedRowsLabel() {
        assertEquals("Unblock alice", PrivacyCopy.unblockLabel("alice"))
    }

    // ---- Local switches (`:301-304`, `:332-335`, `:458-461`, `:575-578`) ----

    @Test
    fun localSwitchesWriteAtOnceWithALightTick() = runTest {
        val model = model()
        model.setAutoLockDelay(AutoLockDelay.FiveMinutes)
        assertEquals(AutoLockDelay.FiveMinutes, security.autoLockDelay.value)
        assertEquals(300, prefsFile.getInt(SecurityPreferences.KEY_AUTO_LOCK, 0))
        model.setHidesDuringScreenCapture(false)
        assertFalse(security.hidesDuringScreenCapture.value)
        model.setGeneratesLinkPreviews(false)
        assertFalse(security.generatesLinkPreviews.value)
        model.setAlwaysRelayCalls(true)
        assertTrue(security.alwaysRelayCalls.value)
        assertEquals(List(4) { Haptic.Light }, haptics)

        // The same value again: nothing written, no tick.
        model.setAutoLockDelay(AutoLockDelay.FiveMinutes)
        model.setAlwaysRelayCalls(true)
        assertEquals(4, haptics.size)
    }

    @Test
    fun theAutoLockPickerOffersTheFiveDelaysInIosOrder() {
        assertEquals(
            listOf("Immediately", "After 1 minute", "After 5 minutes", "After 15 minutes", "Never"),
            AutoLockDelay.entries.map { it.label },
        )
    }

    // ---- Lock chats now (`:627-634`) ----

    @Test
    fun lockNow() = runTest {
        val model = model()
        model.lockNow()
        assertEquals(1, locks)
        assertEquals(listOf(Haptic.Warning), haptics)
        assertEquals(listOf(Toast.success("Chats locked")), toasts)
    }

    // ---- Copy ----

    /** [A] The biometric this phone has, else the screen lock (`autoLockFootnote`, `:307-312`). */
    @Test
    fun autoLockFootnote() {
        val lead = "When you leave the app, decrypted messages are cleared from memory — right away, or once the time you pick has passed."
        assertEquals(
            "$lead Re-open with your fingerprint, screen lock, or your encryption phrase.",
            PrivacyCopy.autoLockFootnote(BiometricLabel.Fingerprint),
        )
        assertEquals("$lead Re-open with your face, screen lock, or your encryption phrase.", PrivacyCopy.autoLockFootnote(BiometricLabel.Face))
        assertEquals(
            "$lead Re-open with your biometrics, screen lock, or your encryption phrase.",
            PrivacyCopy.autoLockFootnote(BiometricLabel.Generic),
        )
        assertEquals("$lead Re-open with your screen lock or your encryption phrase.", PrivacyCopy.autoLockFootnote(null))
    }

    /** P5a: API 35+ covers recordings and still allows screenshots; below it `FLAG_SECURE` blocks both. */
    @Test
    fun deviceProtectionSubtitleByApiLevel() {
        val cover = "While the screen is recorded, cast or shared, Shroud shows only its logo."
        val blocked = "$cover On this Android version, screenshots of your chats are blocked too."
        assertEquals(blocked, PrivacyCopy.hideCaptureDetail(30))
        assertEquals(blocked, PrivacyCopy.hideCaptureDetail(33))
        assertEquals(blocked, PrivacyCopy.hideCaptureDetail(34))
        assertEquals(cover, PrivacyCopy.hideCaptureDetail(35))
        assertEquals(cover, PrivacyCopy.hideCaptureDetail(37))
    }

    /** [A] "this phone" / "this tablet" where iOS says `UIDevice.current.model` (`:447-449`, `:564-566`). */
    @Test
    fun deviceNounCopy() {
        assertEquals(
            "When you send a link, this phone loads the page to build a preview and seals it into the message. The website sees your IP address, as if you had opened the link. People you send it to never contact the website.",
            PrivacyCopy.linkPreviewsDetail("phone"),
        )
        assertEquals(
            "Calls from this tablet go through the Shroud server's relay, so the person you call never sees your IP address. Calls may lag slightly. If the server has no relay, calls won't connect until you turn this off.",
            PrivacyCopy.relayCallsDetail("tablet"),
        )
        assertEquals(
            "Chat history is sealed with a key from your encryption phrase. That key is not kept in plain storage — the Android Keystore wraps it and only unwraps it after you authenticate with biometrics, screen lock, or your 12-word phrase.",
            PrivacyCopy.ENCRYPTED_BODY,
        )
        assertTrue(PrivacyCopy.softwareKeystoreNotice("phone").startsWith("This phone's Android Keystore"))
    }

    /** The server switches' words, verbatim from iOS (`:478-481`, `:610-613`, `:655-670`). */
    @Test
    fun serverSwitchCopy() {
        assertEquals("Read receipts", PrivacySwitch.ReadReceipts.title)
        assertEquals("Contacts see when you've read their messages.", PrivacySwitch.ReadReceipts.detail)
        assertEquals("Contacts see when you're typing or recording a voice message.", PrivacySwitch.Typing.detail)
        assertEquals("Online and last seen", PrivacySwitch.Presence.title)
        assertEquals("Contacts see when you're online and when you were last here.", PrivacySwitch.Presence.detail)
        assertEquals("Find me by username", PrivacySwitch.Discoverable.title)
        assertEquals(
            "When a contact deletes a chat for both of you, your copy is deleted too. Leave this off to keep your own messages — theirs are replaced with “Message deleted” either way. You stay contacts.",
            PrivacySwitch.ChatDelete.detail,
        )
    }

    /** [Privacy] on fixed values, recording the writes; [gate] holds a request until completed. */
    private inner class FakePrivacy : Privacy {
        override val settings = MutableStateFlow(PrivacySettingsDto(allowPeerChatDelete = false))
        override val hasLoaded = MutableStateFlow(false)
        var refreshWorks = true
        var error: String? = null
        var gate: CompletableDeferred<Unit>? = null
        val updates = ArrayList<UpdatePrivacySettingsBody>()
        val chatDeleteWrites = ArrayList<Boolean>()
        var rotations = 0

        fun loaded() {
            hasLoaded.value = true
        }

        override suspend fun refresh() {
            calls += "privacy"
            if (refreshWorks) hasLoaded.value = true
        }

        override suspend fun update(change: UpdatePrivacySettingsBody): String? {
            updates += change
            gate?.await()
            if (error == null) {
                val s = settings.value
                settings.value = s.copy(
                    allowPeerChatDelete = change.allowPeerChatDelete ?: s.allowPeerChatDelete,
                    sendReadReceipts = change.sendReadReceipts ?: s.sendReadReceipts,
                    sendTyping = change.sendTyping ?: s.sendTyping,
                    sharePresence = change.sharePresence ?: s.sharePresence,
                    discoverableByUsername = change.discoverableByUsername ?: s.discoverableByUsername,
                )
            }
            return error
        }

        override suspend fun setAllowsPeerChatDelete(value: Boolean): String? {
            chatDeleteWrites += value
            gate?.await()
            if (error == null) settings.value = settings.value.copy(allowPeerChatDelete = value)
            return error
        }

        override suspend fun rotateShareCode(): String? {
            rotations++
            gate?.await()
            return error
        }

        override fun reset() {
            settings.value = PrivacySettingsDto(allowPeerChatDelete = false)
            hasLoaded.value = false
        }
    }
}
