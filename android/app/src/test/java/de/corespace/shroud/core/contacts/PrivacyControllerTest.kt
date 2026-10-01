package de.corespace.shroud.core.contacts

import de.corespace.shroud.core.auth.Session
import de.corespace.shroud.core.net.ApiError
import de.corespace.shroud.core.net.ErrorCodes
import de.corespace.shroud.core.net.PrivacySettingsDto
import de.corespace.shroud.core.net.UpdatePrivacySettingsBody
import de.corespace.shroud.testing.MainDispatcherRule
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.util.UUID

/** `PrivacyController` (iOS `MessagingController.swift:86-92, 2093-2180`; messaging-core §20.2). */
@OptIn(ExperimentalCoroutinesApi::class)
class PrivacyControllerTest {
    @get:Rule
    val main = MainDispatcherRule(StandardTestDispatcher())

    private val me = UUID.fromString("8f14e45f-ceea-467a-9575-3a6b7a1e6c0e")
    private val backend = FakeContactsBackend()
    private var currentSession: Session? = session(me, shareCode = "OLDCODE234")
    private var serverCode = "OLDCODE234"
    private val presenceChanges = mutableListOf<Boolean>()
    private val lastErrors = mutableListOf<String?>()

    private fun TestScope.controller() = PrivacyController(
        backend = backend,
        session = { currentSession },
        revalidate = { currentSession = currentSession?.copy(shareCode = serverCode) },
        scope = backgroundScope,
        main = main.dispatcher,
        onSharePresenceChanged = { presenceChanges += it },
        setLastError = { lastErrors += it },
    )

    private val everythingOn = PrivacySettingsDto(allowPeerChatDelete = true)

    @Test
    fun beforeTheFirstLoadTheServersDefaultsStand() = runTest(main.dispatcher) {
        val privacy = controller()
        assertEquals(PrivacySettingsDto(false, true, true, true, true), privacy.settings.value)
        assertFalse(privacy.hasLoaded.value)
    }

    @Test
    fun aRefreshAdoptsTheServersSwitchesAndAFailureKeepsThem() = runTest(main.dispatcher) {
        val hidden = everythingOn.copy(sharePresence = false)
        backend.onPrivacy = { hidden }
        val privacy = controller()
        privacy.refresh()
        assertEquals(hidden, privacy.settings.value)
        assertTrue(privacy.hasLoaded.value)
        assertEquals(listOf(false), presenceChanges)
        backend.onPrivacy = { throw ApiError.Transport("offline") }
        privacy.refresh()
        assertEquals(hidden, privacy.settings.value)
        assertTrue(lastErrors.isEmpty())
    }

    @Test
    fun overlappingRefreshesShareOneRequest() = runTest(main.dispatcher) {
        val gate = CompletableDeferred<PrivacySettingsDto>()
        backend.onPrivacy = { gate.await() }
        val privacy = controller()
        val one = launch { privacy.refresh() }
        val two = launch { privacy.refresh() }
        runCurrent()
        gate.complete(everythingOn)
        one.join()
        two.join()
        assertEquals(1, backend.count("privacy"))
        assertEquals(everythingOn, privacy.settings.value)
    }

    @Test
    fun anUpdateSendsOnlyTheChangeAndAdoptsWhatTheServerStored() = runTest(main.dispatcher) {
        var sent: UpdatePrivacySettingsBody? = null
        backend.onUpdatePrivacy = { change ->
            sent = change
            everythingOn
        }
        val privacy = controller()
        assertNull(privacy.setAllowsPeerChatDelete(true))
        assertEquals(UpdatePrivacySettingsBody(allowPeerChatDelete = true), sent)
        assertEquals(everythingOn, privacy.settings.value)
        assertTrue(privacy.hasLoaded.value)
        assertEquals(listOf<String?>(null), lastErrors)
        assertTrue(presenceChanges.isEmpty())
    }

    @Test
    fun aFailedUpdateLeavesTheSwitchesAndSaysWhy() = runTest(main.dispatcher) {
        backend.onUpdatePrivacy = { throw ApiError.Server(ErrorCodes.RATE_LIMITED, "Too many requests. Try again later.", 429) }
        val privacy = controller()
        assertEquals("Too many requests. Try again later.", privacy.update(UpdatePrivacySettingsBody(sharePresence = false)))
        assertEquals(PrivacySettingsDto(allowPeerChatDelete = false), privacy.settings.value)
        assertEquals(listOf<String?>("Too many requests. Try again later."), lastErrors)
        currentSession = null
        assertEquals("Sign in to change privacy settings.", privacy.update(UpdatePrivacySettingsBody(sendTyping = false)))
    }

    @Test
    fun turningPresenceSharingOffAndOnTellsTheContacts() = runTest(main.dispatcher) {
        val privacy = controller()
        backend.onUpdatePrivacy = { PrivacySettingsDto(allowPeerChatDelete = false, sharePresence = false) }
        privacy.update(UpdatePrivacySettingsBody(sharePresence = false))
        backend.onUpdatePrivacy = { PrivacySettingsDto(allowPeerChatDelete = false, sharePresence = true) }
        privacy.update(UpdatePrivacySettingsBody(sharePresence = true))
        assertEquals(listOf(false, true), presenceChanges)
    }

    @Test
    fun rotatingTheShareCodeBringsTheNewCodeIntoTheSession() = runTest(main.dispatcher) {
        backend.onRotate = {
            serverCode = "NEWCODE234"
            serverCode
        }
        val privacy = controller()
        assertNull(privacy.rotateShareCode())
        assertEquals("NEWCODE234", currentSession?.shareCode)
        assertEquals(listOf<String?>(null), lastErrors)
    }

    @Test
    fun aRotationTheSessionDidNotPickUpSaysSo() = runTest(main.dispatcher) {
        // `/auth/me` did not answer: the session still holds the old code.
        backend.onRotate = { "NEWCODE234" }
        val privacy = controller()
        assertEquals("Your QR code was reset. It shows here once Shroud reconnects.", privacy.rotateShareCode())
        backend.onRotate = { throw ApiError.Server(ErrorCodes.RATE_LIMITED, "Too many requests. Try again later.", 429) }
        assertEquals("Too many requests. Try again later.", privacy.rotateShareCode())
        currentSession = null
        assertEquals("Sign in to reset your QR code.", privacy.rotateShareCode())
    }

    @Test
    fun resetGoesBackToTheDefaultsAndDropsALateAnswer() = runTest(main.dispatcher) {
        backend.onPrivacy = { everythingOn }
        val privacy = controller()
        privacy.onContactsActive()
        runCurrent()
        assertEquals(everythingOn, privacy.settings.value)
        val gate = CompletableDeferred<PrivacySettingsDto>()
        backend.onPrivacy = { gate.await() }
        val late = launch { privacy.refresh() }
        runCurrent()
        privacy.onContactsStopped(wipe = false)
        assertEquals(PrivacyController.DEFAULT, privacy.settings.value)
        assertFalse(privacy.hasLoaded.value)
        gate.complete(everythingOn.copy(sendTyping = false))
        late.join()
        assertEquals(PrivacyController.DEFAULT, privacy.settings.value)
        assertFalse(privacy.hasLoaded.value)
    }
}
