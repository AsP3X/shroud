package de.corespace.shroud.ui.onboarding

import android.app.Application
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.view.inputmethod.EditorInfo
import androidx.compose.ui.autofill.ContentType
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.test.core.app.ApplicationProvider
import de.corespace.shroud.core.auth.Session
import de.corespace.shroud.core.crypto.CryptoException
import de.corespace.shroud.core.crypto.DeviceNameSeal
import de.corespace.shroud.core.net.ApiError
import de.corespace.shroud.ui.components.ComposeHarness
import de.corespace.shroud.ui.components.OverlayHost
import kotlinx.coroutines.CompletableDeferred
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.UUID

/**
 * Log In on a fake K2, read and driven the way TalkBack and the keyboard do (`LogInFlowView.swift`;
 * settings-lock addendum *LogInFlowView.swift* L.6 and L1, L3, L7; web-parity §14.2 / P11b; P5).
 * iOS has no view tests: these are the addendum's Compose UI tests.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class LogInScreenTest {
    private val services = FakeOnboardingServices()
    private val hosts = HarnessHosts()
    private var unlocked = 0
    private var loggedOut = 0
    private var backs = 0
    private var signUps = 0

    @After
    fun tearDown() = hosts.disposeAll()

    private fun screen() = hosts.host {
        LogInContent(services, onBack = { backs++ }, onSignUp = { signUps++ }, onUnlocked = { unlocked++ }, onLogOut = { loggedOut++ })
    }

    private fun signedIn() {
        services.session.value = Session("token-device", FakeOnboardingServices.USER, "alice", null, "device")
    }

    private fun clipboard(text: String?) {
        val clipboard = ApplicationProvider.getApplicationContext<Context>().getSystemService(ClipboardManager::class.java)
        if (text == null) clipboard.clearPrimaryClip() else clipboard.setPrimaryClip(ClipData.newPlainText("Notes", text))
    }

    private fun words(ui: ComposeHarness) = (1..12).map { ui.tagged("login.word$it").editableText }

    /** What TalkBack reads of the new phrase's card: "Word 3: abandon". */
    private fun cardWords(ui: ComposeHarness) = (1..12).map { n ->
        ui.nodes().firstNotNullOf { node -> node.config.getOrNull(SemanticsProperties.ContentDescription)?.firstOrNull { it.startsWith("Word $n: ") } }
    }

    @Test
    fun theCredentialsCarryTheirAutofillHints() {
        // L7: `.username` / `.password` (`:431-444`).
        val ui = screen()
        assertEquals(ContentType.Username, ui.field("Username").autofillType)
        assertEquals(ContentType.Password, ui.field("Password").autofillType)
    }

    @Test
    fun logInMovesOnToThePhraseAndTheSignUpCapsuleLeavesTalkBack() {
        val ui = screen()
        assertTrue(ui.hasButton("Sign Up"))
        assertFalse(ui.button("Log In").isEnabled)
        ui.type(ui.field("Username"), " Alice ")
        ui.type(ui.field("Password"), "pw")
        ui.click(ui.button("Log In"))
        // The login, then the phrase step's one `GET keys/identity/{me}` (`:126-130`).
        assertEquals(listOf("login:alice", "identity"), services.calls)
        assertTrue(ui.shows("Enter Encryption Phrase"))
        assertTrue(ui.shows("Signed in as @alice"))
        // L3: out of the semantics tree on the phrase step, not just faded.
        assertFalse(ui.hasButton("Sign Up"))
        assertFalse(ui.hasTag("login.signUp"))
        // A session alone never opens the chats (`:706-712`).
        assertEquals(0, unlocked)
    }

    @Test
    fun theServersWordsShowUnderTheCredentials() {
        services.loginError = ApiError.Server("INVALID_CREDENTIALS", "Invalid username or password.", 401)
        val ui = screen()
        ui.type(ui.field("Username"), "alice")
        ui.type(ui.field("Password"), "nope")
        ui.click(ui.button("Log In"))
        assertTrue(ui.shows("Invalid username or password."))
        assertTrue(ui.shows("Welcome Back"))
    }

    // ---- A full account: "Log out your oldest device?" ----

    /** In the app's overlay layer, as the shell hosts it: the dialog hides the screen from TalkBack. */
    private fun overlaid() = hosts.host {
        OverlayHost { LogInContent(services, onBack = { backs++ }, onSignUp = { signUps++ }, onUnlocked = { unlocked++ }, onLogOut = { loggedOut++ }) }
    }

    private fun ComposeHarness.logInAsAlice() {
        type(field("Username"), "alice")
        type(field("Password"), "pw")
        click(button("Log In"))
    }

    private fun ComposeHarness.showsTextStartingWith(prefix: String) =
        unmergedNodes().any { node -> node.config.getOrNull(SemanticsProperties.Text)?.any { it.text.startsWith(prefix) } == true }

    /** The phrase step after a full account's 409: twelve words typed and Unlock pressed. */
    private fun ComposeHarness.enterPhrase(phrase: List<String>) {
        phrase.forEachIndexed { i, word -> type(tagged("login.word${i + 1}"), word) }
        click(button("Unlock Messages"))
    }

    @Test
    fun aFullAccountMovesOnToThePhraseWithoutASession() {
        services.loginFailures += LogInTest.deviceLimit()
        val ui = overlaid()
        ui.logInAsAlice()
        // The phrase first: no dialog, no inline error, no session (so no `GET keys/identity`).
        assertTrue(ui.shows("Enter Encryption Phrase"))
        assertFalse(ui.shows(DeviceLimitCopy.TITLE))
        assertFalse(ui.shows("full"))
        assertEquals(null, services.session.value)
        assertFalse(ui.shows("Signed in as @alice"))
        assertEquals(listOf("login:alice"), services.calls)
    }

    @Test
    fun aWrongPhraseAsksNothingAndSendsNothing() {
        services.loginFailures += LogInTest.deviceLimit()
        services.checkError = CryptoException.PhraseDoesNotMatchAccount()
        val ui = overlaid()
        ui.logInAsAlice()
        ui.enterPhrase(services.bip39.generate())
        assertTrue(ui.shows(LogInRules.WRONG_PHRASE))
        assertFalse(ui.shows(DeviceLimitCopy.TITLE))
        assertEquals(listOf("login:alice", "check:${LogInTest.KEY}"), services.calls)
        assertEquals(0, unlocked)
    }

    @Test
    fun aRightPhraseAsksAboutTheNamedOldestThenLogsItOutAndUnlocksWithTheSameWords() {
        services.loginFailures += LogInTest.deviceLimit()
        services.names = mapOf(LogInTest.OLDEST.id to DeviceNameSeal.Label("Noah’s iPhone", DeviceNameSeal.Kind.IPhone))
        val ui = overlaid()
        ui.logInAsAlice()
        val phrase = services.bip39.generate()
        ui.enterPhrase(phrase)
        assertTrue(ui.shows(DeviceLimitCopy.TITLE))
        assertTrue(ui.shows(DeviceLimitCopy.MESSAGE))
        assertTrue(ui.shows(DeviceLimitCopy.NOTE))
        // The name the phrase opened, and "Last active … · Linked …".
        assertEquals(phrase, services.namesWords)
        assertTrue(ui.shows("Noah’s iPhone"))
        assertTrue(ui.showsTextStartingWith("Last active "))
        assertTrue(ui.unmergedNodes().any { node -> node.config.getOrNull(SemanticsProperties.Text)?.any { " · Linked " in it.text } == true })
        assertTrue(ui.button(DeviceLimitCopy.CHOOSE_ANOTHER).isEnabled)
        assertTrue(ui.button("Cancel").isEnabled)
        // The phrase step under it is out of TalkBack's reach.
        assertFalse(ui.hasButton("Unlock Messages"))
        val gate = CompletableDeferred<Unit>()
        services.loginGate = gate
        ui.click(ui.button("Log Out and Continue"))
        // Busy: "Logging Out…" with its spinner, and no way out of the dialog meanwhile.
        assertTrue(ui.shows(DeviceLimitCopy.CONFIRMING))
        assertFalse(ui.button(DeviceLimitCopy.CONFIRMING).isEnabled)
        assertFalse(ui.button("Cancel").isEnabled)
        assertFalse(ui.button(DeviceLimitCopy.CHOOSE_ANOTHER).isEnabled)
        gate.complete(Unit)
        ui.waitUntil { unlocked == 1 }
        assertFalse(ui.shows(DeviceLimitCopy.TITLE))
        // Signed in now: the banner comes back.
        assertTrue(ui.shows("Signed in as @alice"))
        // The phrase step's own ending, with the words already checked — not asked for again.
        assertEquals(
            listOf("login:alice", "check:${LogInTest.KEY}", "login:alice:replace=${LogInTest.OLDEST.id}", "unlock:device"),
            services.calls,
        )
        assertEquals(phrase, services.lastWords)
    }

    private fun ComposeHarness.pickerRow(index: Int) = tagged("login.pickDevice$index")

    private val SemanticsNode.isSelected: Boolean get() = config.getOrNull(SemanticsProperties.Selected) == true

    @Test
    fun anotherDeviceIsPickedFromTheListAndLoggedOutInstead() {
        services.loginFailures += LogInTest.deviceLimit()
        services.names = mapOf(LogInTest.THIRD.id to DeviceNameSeal.Label("Work laptop", DeviceNameSeal.Kind.Other))
        val ui = overlaid()
        ui.logInAsAlice()
        ui.enterPhrase(services.bip39.generate())
        ui.click(ui.button(DeviceLimitCopy.CHOOSE_ANOTHER))
        // The picker in place of the question: every device, oldest first, the oldest tagged and ticked.
        assertTrue(ui.shows(DeviceLimitCopy.PICKER_TITLE))
        assertTrue(ui.shows(DeviceLimitCopy.PICKER_MESSAGE))
        assertFalse(ui.shows(DeviceLimitCopy.TITLE))
        assertEquals(LogInTest.DEVICES.size, LogInTest.DEVICES.indices.count { ui.hasTag("login.pickDevice$it") })
        assertTrue(ui.pickerRow(0).isSelected)
        // TalkBack: name, the tag, the last activity.
        assertTrue(ui.pickerRow(0).config[SemanticsProperties.ContentDescription].single().startsWith("Unnamed device, Oldest, Last active "))
        assertEquals("Unnamed device, Never active", ui.pickerRow(1).config[SemanticsProperties.ContentDescription].single())
        assertFalse(ui.pickerRow(2).isSelected)
        ui.click(ui.pickerRow(2))
        // Back to the question, about the picked device.
        assertFalse(ui.shows(DeviceLimitCopy.PICKER_TITLE))
        assertTrue(ui.shows(DeviceLimitCopy.TITLE_OTHER))
        assertTrue(ui.shows("Work laptop"))
        ui.click(ui.button("Log Out and Continue"))
        ui.waitUntil { unlocked == 1 }
        assertEquals(
            listOf("login:alice", "check:${LogInTest.KEY}", "login:alice:replace=${LogInTest.THIRD.id}", "unlock:device"),
            services.calls,
        )
    }

    @Test
    fun thePickersCancelKeepsTheChoice() {
        services.loginFailures += LogInTest.deviceLimit()
        val ui = overlaid()
        ui.logInAsAlice()
        ui.enterPhrase(services.bip39.generate())
        ui.click(ui.button(DeviceLimitCopy.CHOOSE_ANOTHER))
        ui.click(ui.pickerRow(2))
        assertTrue(ui.shows(DeviceLimitCopy.TITLE_OTHER))
        ui.click(ui.button(DeviceLimitCopy.CHOOSE_ANOTHER))
        assertTrue(ui.pickerRow(2).isSelected)
        assertFalse(ui.pickerRow(0).isSelected)
        ui.click(ui.button("Cancel"))
        // The question again, still about the third device.
        assertTrue(ui.shows(DeviceLimitCopy.TITLE_OTHER))
        ui.click(ui.button("Log Out and Continue"))
        ui.waitUntil { unlocked == 1 }
        assertEquals("login:alice:replace=${LogInTest.THIRD.id}", services.calls[2])
    }

    @Test
    fun cancelStaysOnThePhraseWithTheWordsAndUnlockAsksAgain() {
        services.loginFailures += LogInTest.deviceLimit()
        val ui = overlaid()
        ui.logInAsAlice()
        val phrase = services.bip39.generate()
        ui.enterPhrase(phrase)
        ui.click(ui.button("Cancel"))
        assertFalse(ui.shows(DeviceLimitCopy.TITLE))
        assertTrue(ui.shows("Enter Encryption Phrase"))
        assertEquals(phrase, words(ui))
        assertFalse(ui.shows(LogInRules.WRONG_PHRASE))
        assertEquals(listOf("login:alice", "check:${LogInTest.KEY}"), services.calls)
        ui.click(ui.button("Unlock Messages"))
        assertTrue(ui.shows(DeviceLimitCopy.TITLE))
        assertEquals(listOf("login:alice", "check:${LogInTest.KEY}", "check:${LogInTest.KEY}"), services.calls)
    }

    @Test
    fun aRetryThatFindsTheAccountFullAgainShowsTheNewOldest() {
        // The oldest was gone already and another device took the slot: the new list, the new oldest.
        val next = LogInTest.DEVICES - LogInTest.OLDEST + LogInTest.FIFTH.copy(id = UUID.fromString("ffffffff-ffff-4fff-8fff-ffffffffffff"))
        services.loginFailures += LogInTest.deviceLimit()
        services.loginFailures += LogInTest.deviceLimit(next)
        val ui = overlaid()
        ui.logInAsAlice()
        val phrase = services.bip39.generate()
        ui.enterPhrase(phrase)
        assertFalse(ui.showsTextStartingWith(DeviceLimitCopy.NEVER_ACTIVE))
        services.namesWords = null
        ui.click(ui.button("Log Out and Continue"))
        // Still open, idle again, about the new oldest ("Never active"), its names opened again.
        assertTrue(ui.shows(DeviceLimitCopy.TITLE))
        assertTrue(ui.showsTextStartingWith(DeviceLimitCopy.NEVER_ACTIVE))
        assertEquals(phrase, services.namesWords)
        assertTrue(ui.button("Log Out and Continue").isEnabled)
        ui.click(ui.button("Log Out and Continue"))
        ui.waitUntil { unlocked == 1 }
        assertEquals(
            listOf(
                "login:alice",
                "check:${LogInTest.KEY}",
                "login:alice:replace=${LogInTest.OLDEST.id}",
                "login:alice:replace=${LogInTest.SECOND.id}",
                "unlock:device",
            ),
            services.calls,
        )
    }

    @Test
    fun aRetryThatFindsTheAccountFullAgainKeepsAPickStillListed() {
        services.loginFailures += LogInTest.deviceLimit()
        services.loginFailures += LogInTest.deviceLimit(LogInTest.DEVICES - LogInTest.OLDEST)
        val ui = overlaid()
        ui.logInAsAlice()
        ui.enterPhrase(services.bip39.generate())
        ui.click(ui.button(DeviceLimitCopy.CHOOSE_ANOTHER))
        ui.click(ui.pickerRow(3))
        ui.click(ui.button("Log Out and Continue"))
        // The fourth device is still listed: still the question about it.
        assertTrue(ui.shows(DeviceLimitCopy.TITLE_OTHER))
        ui.click(ui.button("Log Out and Continue"))
        ui.waitUntil { unlocked == 1 }
        assertEquals(
            listOf("login:alice:replace=${LogInTest.FOURTH.id}", "login:alice:replace=${LogInTest.FOURTH.id}"),
            services.calls.filter { it.contains("replace") },
        )
    }

    @Test
    fun anOlderServersOldestDeviceOffersNoOther() {
        services.loginFailures += LogInTest.deviceLimit(listed = false)
        val ui = overlaid()
        ui.logInAsAlice()
        ui.enterPhrase(services.bip39.generate())
        assertTrue(ui.shows(DeviceLimitCopy.TITLE))
        assertTrue(ui.shows("Unnamed device"))
        assertFalse(ui.hasButton(DeviceLimitCopy.CHOOSE_ANOTHER))
    }

    @Test
    fun anotherErrorOnTheRetryClosesTheDialogAndShowsOnThePhraseStep() {
        services.loginFailures += LogInTest.deviceLimit()
        services.loginFailures += ApiError.Transport("The request timed out.")
        val ui = overlaid()
        ui.logInAsAlice()
        ui.enterPhrase(services.bip39.generate())
        ui.click(ui.button("Log Out and Continue"))
        assertFalse(ui.shows(DeviceLimitCopy.TITLE))
        assertTrue(ui.shows("The request timed out."))
        assertTrue(ui.shows("Enter Encryption Phrase"))
        assertEquals(0, unlocked)
    }

    @Test
    fun backFromTheWaitingPhraseStepDropsTheLimit() {
        services.loginFailures += LogInTest.deviceLimit()
        val ui = overlaid()
        ui.logInAsAlice()
        ui.click(ui.button("Back"))
        assertTrue(ui.shows("Welcome Back"))
        assertEquals(0, backs)
        // A fresh attempt, no session to have ended.
        ui.click(ui.button("Log In"))
        assertEquals(listOf("login:alice", "login:alice", "identity"), services.calls)
    }

    @Test
    fun aLimitWithoutAKeyOrADeviceKeepsTheInlineError() {
        services.loginError = LogInTest.deviceLimit(identityKey = null)
        val ui = overlaid()
        ui.logInAsAlice()
        assertTrue(ui.shows("full"))
        assertTrue(ui.shows("Welcome Back"))
        services.loginError = ApiError.from(409, """{"error":{"code":"DEVICE_LIMIT","message":"This account already has the maximum number of devices (5). Remove a device and try again."}}""")
        ui.click(ui.button("Log In"))
        assertTrue(ui.shows("This account already has the maximum number of devices (5). Remove a device and try again."))
        assertFalse(ui.shows(DeviceLimitCopy.TITLE))
        assertFalse(ui.shows("Enter Encryption Phrase"))
    }

    @Test
    fun pastingSomethingElseSaysSoAndLeavesTheWordsEmpty() {
        // L.6: "The clipboard doesn’t hold a valid 12-word phrase." (`:520-527`).
        signedIn()
        clipboard("hello world, not a phrase")
        val ui = screen()
        ui.click(ui.button("Paste"))
        assertTrue(ui.shows(PASTE_FAILED))
        assertEquals(List(12) { "" }, words(ui))
    }

    @Test
    fun pastingAPhraseFillsTheTwelveWords() {
        signedIn()
        val phrase = services.bip39.generate()
        clipboard("  " + phrase.joinToString("\n") + " ")
        val ui = screen()
        ui.click(ui.button("Paste"))
        // The pairs come back 45 ms apart (`EncryptionPhraseReveal`).
        ui.waitUntil { ui.hasTag("login.word12") && words(ui) == phrase }
        assertFalse(ui.shows(PASTE_FAILED))
        assertTrue(ui.button("Unlock Messages").isEnabled)
    }

    @Test
    fun returnOnTheLastWordWithAllTwelveFilledUnlocks() {
        // L.6: Return on word 12 starts the unlock (`:588-598`).
        signedIn()
        val ui = screen()
        val phrase = services.bip39.generate()
        assertFalse(ui.button("Unlock Messages").isEnabled)
        phrase.forEachIndexed { i, word -> ui.type(ui.tagged("login.word${i + 1}"), word) }
        ui.imeAction(ui.tagged("login.word12"))
        assertEquals("unlock:device", services.calls.last())
        assertEquals(phrase, services.lastWords)
        assertEquals(1, unlocked)
    }

    @Test
    fun returnOnAnEarlierWordOnlyMovesOn() {
        signedIn()
        val ui = screen()
        val phrase = services.bip39.generate()
        phrase.take(11).forEachIndexed { i, word -> ui.type(ui.tagged("login.word${i + 1}"), word) }
        ui.imeAction(ui.tagged("login.word11"))
        ui.type(ui.tagged("login.word12"), phrase.last())
        // Typing the last word does not submit either: only Return on it, or the button.
        assertTrue(services.calls.none { it.startsWith("unlock") })
        assertTrue(ui.button("Unlock Messages").isEnabled)
    }

    @Test
    fun aPhraseErrorIsShownOnThePhraseStep() {
        signedIn()
        services.unlockError = CryptoException.PhraseDoesNotMatchAccount()
        val ui = screen()
        services.bip39.generate().forEachIndexed { i, word -> ui.type(ui.tagged("login.word${i + 1}"), word) }
        ui.click(ui.button("Unlock Messages"))
        assertTrue(ui.shows("That phrase doesn’t match this account on this device."))
        assertEquals(0, unlocked)
    }

    @Test
    fun backOnThePhraseStepReturnsToTheCredentialsWithTheErrorCleared() {
        // L.6: not signed in at the start, Back morphs back (`:143-153`).
        clipboard(null)
        val ui = screen()
        ui.type(ui.field("Username"), "alice")
        ui.type(ui.field("Password"), "pw")
        ui.click(ui.button("Log In"))
        ui.click(ui.button("Paste"))
        assertTrue(ui.shows(PASTE_FAILED))
        ui.click(ui.button("Back"))
        assertTrue(ui.shows("Welcome Back"))
        assertFalse(ui.shows(PASTE_FAILED))
        assertTrue(ui.hasButton("Sign Up"))
        assertEquals(0, backs)
    }

    @Test
    fun openedSignedInOverTheLockScreenBackReturnsThereWithoutLogOut() {
        // L1: "Use encryption phrase" pushes Log In over the lock screen; Back pops to it (`:143-146`).
        signedIn()
        services.identityHere = true
        val ui = screen()
        assertTrue(ui.shows("Enter Encryption Phrase"))
        assertFalse(ui.hasButton("Log Out"))
        ui.click(ui.button("Back"))
        assertEquals(1, backs)
    }

    @Test
    fun openedSignedInWithoutTheLockScreenUnderneathOffersLogOut() {
        // Design `daz2w`: the session's identity is not on this phone — Welcome is the root.
        signedIn()
        services.identityHere = false
        val ui = screen()
        ui.waitUntil { ui.hasButton("Log Out") }
        assertTrue(ui.hasTag("login.logOut"))
        assertFalse(ui.hasButton("Sign Up"))
        ui.click(ui.button("Log Out"))
        assertEquals(1, loggedOut)
        // Back stays, as on iOS (`:143-146`).
        ui.click(ui.button("Back"))
        assertEquals(1, backs)
    }

    @Test
    fun theLogOutCapsuleRule() {
        assertTrue(LogInRules.showsLogOut(startedSignedIn = true, signedIn = true, overLockScreen = false))
        assertFalse(LogInRules.showsLogOut(startedSignedIn = true, signedIn = true, overLockScreen = true))
        // Not read yet: nothing flashes.
        assertFalse(LogInRules.showsLogOut(startedSignedIn = true, signedIn = true, overLockScreen = null))
        // The session went ("Session expired. Log in again."): the credentials, with Sign Up.
        assertFalse(LogInRules.showsLogOut(startedSignedIn = true, signedIn = false, overLockScreen = false))
        assertFalse(LogInRules.showsLogOut(startedSignedIn = false, signedIn = true, overLockScreen = false))
    }

    @Test
    fun thePhraseFieldsKeepTheirWordsOutOfTheKeyboardsLearning() {
        // P5 decided: `IME_FLAG_NO_PERSONALIZED_LEARNING` on the phrase fields, no suggestions.
        signedIn()
        val ui = screen()
        for (index in listOf(1, 7, 12)) {
            val info = ui.editorInfoOf(ui.tagged("login.word$index"))
            assertTrue("word $index", info.imeOptions and EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING != 0)
            assertEquals(0, info.inputType and EditorInfo.TYPE_TEXT_FLAG_AUTO_CORRECT)
        }
    }

    @Test
    fun anAccountWithAKeyIsNotOfferedANewPhrase() {
        signedIn()
        services.hasNoKey = false
        val ui = screen()
        ui.waitUntil { services.calls.contains("identity") }
        assertFalse(ui.hasTag("login.phraseSource"))
        assertFalse(ui.shows("I never got a 12-word phrase"))
    }

    @Test
    fun anAccountThatNeverHadAPhraseCanSaveANewOne() {
        // P11b / X1-IOS: offered only after the server said KEYS_REQUIRED (`:257-357, 756-830`).
        signedIn()
        services.hasNoKey = true
        val ui = screen()
        ui.waitUntil { ui.hasTag("login.phraseSource") }
        ui.click(ui.button("I never got a 12-word phrase"))
        assertTrue(ui.shows("Your New Phrase"))
        assertTrue(ui.shows("Write these 12 words down. They become this account’s encryption phrase."))
        assertTrue(
            ui.shows("This is only for an account that has never had a phrase. If you already set one on another device, go back and enter that phrase. A different one is not saved."),
        )
        ui.waitUntil { ui.button("Copy").isEnabled }
        assertFalse(ui.button("Save Phrase and Continue").isEnabled)
        ui.click(ui.button("I wrote down my encryption phrase"))
        ui.click(ui.button("Save Phrase and Continue"))
        // The new phrase goes through the same unlock (its published-key check makes it the first key only).
        val saved = services.lastWords!!
        assertEquals(saved, services.bip39.validate(saved))
        assertEquals("unlock:device", services.calls.last())
        assertEquals(1, unlocked)
    }

    @Test
    fun goingBackToTypingDropsTheUnusedPhrase() {
        signedIn()
        services.hasNoKey = true
        val ui = screen()
        ui.waitUntil { ui.hasTag("login.phraseSource") }
        ui.click(ui.button("I never got a 12-word phrase"))
        ui.waitUntil { ui.button("Copy").isEnabled }
        val first = cardWords(ui)
        ui.click(ui.button("I do have a phrase — let me type it"))
        assertTrue(ui.shows("Enter Encryption Phrase"))
        assertEquals(List(12) { "" }, words(ui))
        // A second visit draws a fresh phrase.
        ui.click(ui.button("I never got a 12-word phrase"))
        ui.waitUntil { ui.button("Copy").isEnabled }
        val second = cardWords(ui)
        assertNotEquals(first, second)
    }
}
