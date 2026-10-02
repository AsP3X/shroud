package de.corespace.shroud.ui.onboarding

import android.app.Application
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.ui.autofill.ContentType
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.test.core.app.ApplicationProvider
import de.corespace.shroud.core.net.ApiError
import de.corespace.shroud.ui.components.ComposeHarness
import de.corespace.shroud.ui.components.ToastState
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Sign Up on a fake K2, read and driven the way TalkBack and the keyboard do (`SignUpView.swift`;
 * settings-lock addendum *SignUpView.swift* S.6-S.9 and S1-S4; addendum *PasswordStrengthMeter.swift*;
 * crypto §17.1). S.9's three Compose UI tests plus the strength panel, the autofill hints and the
 * phrase copy.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class SignUpScreenTest {
    private val services = FakeOnboardingServices()
    private val toast = ToastState()
    private val hosts = HarnessHosts()
    private var unlocked = 0
    private var backs = 0
    private var logIns = 0

    @After
    fun tearDown() = hosts.disposeAll()

    private fun screen() = hosts.host {
        SignUpContent(services, toast, onBack = { backs++ }, onLogIn = { logIns++ }, onUnlocked = { unlocked++ })
    }

    private fun ComposeHarness.account(username: String = "alice", password: String = STRONG) {
        type(field("Username"), username)
        type(field("Password"), password)
    }

    /** The account step, filled and confirmed: the phrase step with all twelve words shown. */
    private fun ComposeHarness.toPhrase() {
        account()
        click(button("Continue"))
        waitUntil { button("Copy").isEnabled }
    }

    /** The one node TalkBack reads as [label] (its own content description). */
    private fun ComposeHarness.described(label: String): SemanticsNode =
        nodes().single { it.config.getOrNull(SemanticsProperties.ContentDescription)?.contains(label) == true }

    private val SemanticsNode.state: String? get() = config.getOrNull(SemanticsProperties.StateDescription)

    private fun cardWords(ui: ComposeHarness) = (1..12).map { n ->
        ui.nodes().firstNotNullOf { node -> node.config.getOrNull(SemanticsProperties.ContentDescription)?.firstOrNull { it.startsWith("Word $n: ") } }
    }

    @Test
    fun theNewAccountsFieldsCarryTheirAutofillHints() {
        // S3: iOS `.username` / `.newPassword` — the password manager suggests a strong password and saves it.
        val ui = screen()
        assertEquals(ContentType.NewUsername, ui.field("Username").autofillType)
        assertEquals(ContentType.NewPassword, ui.field("Password").autofillType)
    }

    @Test
    fun continueWaitsForTwelveCharactersAndASymbolWithANumber() {
        // S.9: Continue disabled until "12+ characters" and "Symbol & number" are met.
        val ui = screen()
        assertFalse(ui.button("Continue").isEnabled)
        assertEquals("None", ui.described("Password strength").state)
        ui.account(password = "short1!")
        assertFalse(ui.button("Continue").isEnabled)
        assertEquals("Not met", ui.described("12+ characters").state)
        assertEquals("Met", ui.described("Symbol & number").state)
        ui.account(password = "longenoughpassword")
        assertFalse(ui.button("Continue").isEnabled)
        assertEquals("Met", ui.described("12+ characters").state)
        assertEquals("Not met", ui.described("Symbol & number").state)
        ui.account(password = STRONG)
        assertTrue(ui.button("Continue").isEnabled)
        assertEquals("Met", ui.described("Symbol & number").state)
        assertTrue(ui.described("Password strength").state in setOf("Good", "Strong"))
        // A name is needed too.
        ui.account(username = "  ")
        assertFalse(ui.button("Continue").isEnabled)
    }

    @Test
    fun theUsernameRuleIsCheckedBeforeThePhraseShows() {
        val ui = screen()
        ui.account(username = "ab")
        ui.click(ui.button("Continue"))
        assertTrue(ui.shows("Username must be between 3 and 32 characters."))
        assertTrue(ui.shows("Create Account"))
        assertFalse(ui.hasButton("Copy"))
    }

    @Test
    fun withoutAScreenLockThePhraseIsNeverShown() {
        // Design `zV24H`: checked at Continue, before any phrase exists on screen.
        services.screenLock = false
        val ui = screen()
        ui.account()
        ui.click(ui.button("Continue"))
        assertTrue(ui.shows("Set a screen lock to use Shroud."))
        assertFalse(ui.hasButton("Copy"))
    }

    @Test
    fun createWaitsForTheRevealAndTheWroteDownBox() {
        val ui = screen()
        ui.account()
        ui.click(ui.button("Continue"))
        assertTrue(ui.shows("Save Your Phrase"))
        assertFalse(ui.button("Create Account").isEnabled)
        ui.waitUntil { ui.button("Copy").isEnabled }
        assertFalse(ui.button("Create Account").isEnabled)
        val box = ui.button("I wrote down my encryption phrase")
        assertEquals("Not checked", box.state)
        ui.click(box)
        assertEquals("Checked", ui.button("I wrote down my encryption phrase").state)
        assertTrue(ui.button("Create Account").isEnabled)
    }

    @Test
    fun aTakenUsernameSendsTheUserBackToTheAccountStep() {
        // S.9: a USERNAME_TAKEN answer on Create returns to the Account step with the server's words.
        services.registerError = ApiError.Server("USERNAME_TAKEN", "That username is already taken.", 409)
        val ui = screen()
        ui.toPhrase()
        ui.click(ui.button("I wrote down my encryption phrase"))
        ui.click(ui.button("Create Account"))
        assertTrue(ui.shows("That username is already taken."))
        assertTrue(ui.shows("Create Account"))
        assertTrue(ui.hasButton("Continue"))
        assertEquals(listOf("register:alice"), services.calls)
        assertEquals(0, unlocked)
    }

    @Test
    fun aKeyPublishFailureRetriesWithoutRegisteringAgain() {
        // S.9: a key-publish failure followed by a retry sends POST /auth/register once (the iOS gap S.6).
        services.establishError = ApiError.Transport("The Internet connection appears to be offline.")
        val ui = screen()
        ui.toPhrase()
        ui.click(ui.button("I wrote down my encryption phrase"))
        ui.click(ui.button("Create Account"))
        assertTrue(ui.shows("The Internet connection appears to be offline."))
        assertTrue(ui.shows("Save Your Phrase"))
        services.establishError = null
        ui.click(ui.button("Create Account"))
        assertEquals(listOf("register:alice", "establish:new-1", "establish:new-1"), services.calls)
        assertEquals(1, unlocked)
    }

    @Test
    fun theAccountsPhraseIsTheOneOnScreen() {
        val ui = screen()
        ui.toPhrase()
        val shown = cardWords(ui).map { it.substringAfter(": ") }
        ui.click(ui.button("I wrote down my encryption phrase"))
        ui.click(ui.button("Create Account"))
        assertEquals(shown, services.lastWords)
    }

    @Test
    fun goingBackToFixTheNameKeepsThePhrase() {
        // S.7: one phrase per visit; back on the account step and forward again shows the same words.
        val ui = screen()
        ui.toPhrase()
        val first = cardWords(ui)
        ui.click(ui.button("Back"))
        assertTrue(ui.shows("Create Account"))
        assertEquals(0, backs)
        ui.click(ui.button("Continue"))
        ui.waitUntil { ui.button("Copy").isEnabled }
        assertEquals(first, cardWords(ui))
        ui.click(ui.button("Back"))
        ui.click(ui.button("Back"))
        assertEquals(1, backs)
    }

    @Test
    fun copyPutsTheTrimmedPhraseOnTheClipboardAndSaysSo() {
        // S.5 / crypto §17.1: success toast; the clip is the phrase, labelled for the wipe.
        val ui = screen()
        ui.toPhrase()
        val words = cardWords(ui).map { it.substringAfter(": ") }
        ui.click(ui.button("Copy"))
        assertEquals("Encryption phrase copied", toast.current?.message)
        val clipboard = ApplicationProvider.getApplicationContext<Context>().getSystemService(ClipboardManager::class.java)
        assertEquals(words.joinToString(" "), clipboard.primaryClip!!.getItemAt(0).text.toString())
        assertEquals(PhraseClipboard.LABEL, clipboard.primaryClipDescription!!.label)
    }

    @Test
    fun logInIsOfferedOnTheAccountStepOnly() {
        val ui = screen()
        ui.click(ui.button("Log In"))
        assertEquals(1, logIns)
        ui.toPhrase()
        assertFalse(ui.hasButton("Log In"))
    }

    private companion object {
        /** 16 characters, a symbol and a number: "Strong" (`PasswordStrengthEvaluatorTests`). */
        const val STRONG = "Correct-horse-42"
    }
}
