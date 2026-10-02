package de.corespace.shroud.ui.onboarding

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.view.inputmethod.EditorInfo
import androidx.compose.ui.autofill.ContentType
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.test.core.app.ApplicationProvider
import de.corespace.shroud.core.auth.Session
import de.corespace.shroud.core.crypto.CryptoException
import de.corespace.shroud.core.net.ApiError
import de.corespace.shroud.ui.components.ComposeHarness
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Log In on a fake K2, read and driven the way TalkBack and the keyboard do (`LogInFlowView.swift`;
 * settings-lock addendum *LogInFlowView.swift* L.6 and L1, L3, L7; web-parity §14.2 / P11b; P5).
 * iOS has no view tests: these are the addendum's Compose UI tests.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
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
