package de.corespace.shroud.ui.onboarding

import android.Manifest
import android.os.Build
import android.os.SystemClock
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.BySelector
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiObject2
import de.corespace.shroud.AppContainer
import de.corespace.shroud.LOCAL_NETWORK_PERMISSION
import de.corespace.shroud.MainActivity
import de.corespace.shroud.ShroudApplication
import de.corespace.shroud.core.auth.WipePhase
import de.corespace.shroud.core.auth.WipeReason
import de.corespace.shroud.core.keys.DeviceLock
import de.corespace.shroud.core.net.ServerConfiguration
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.net.HttpURLConnection
import java.net.URI
import java.util.UUID
import java.util.regex.Pattern

/**
 * The W3-LOCK-ONBOARD journeys on a device, through the real `MainActivity` (C4 done-when:
 * "onboarding works end to end on both emulators"; plan §6.4 rows that need these screens). Driven
 * with UiAutomator on what TalkBack reads (labels and descriptions), so the real frame clock, the
 * system credential prompt and the activity lifecycle all take part:
 *
 * 1. Welcome → Sign Up (account, phrase reveal, "I wrote down") → the chats.
 * 2. Lock chats now → the lock screen → "Unlock with screen lock" / "Use screen lock" → the system
 *    prompt answered with the PIN → the chats again (design `yGDcx`/`p5OtXk`, storyboard `dRyqM`).
 * 3. Lock again → "Use encryption phrase" opens Log In's phrase step **over** the lock screen: no
 *    "Log Out" capsule, Back returns to the lock screen (addendum LogIn L1).
 * 4. Log Out → the wipe overlay (`qaRQA` → `ZFJ1m`) → Welcome.
 * 5. Welcome → Log In (credentials, then the 12 words) → the chats.
 *
 * Needs the throwaway stack (`android/e2e/stack-up.sh`) and `android/e2e/emulator-setup.sh` (PIN
 * 1234, Android 17's local-network grant); an unlocked phone (`android/e2e/unlock.sh`). Skipped when
 * the API cannot be reached unless `shroudRequired=true`. Instrumentation arguments: `shroudApi`
 * (default `http://10.0.2.2:8080/api/v1`). Run with `ANDROID_SERIAL` set: never on emulator-5554.
 * Lock and Log Out go through the shell's own actions (`AppActions.lockChatsNow` / `logOut`, what
 * Settings and Privacy run), so this journey does not depend on the Settings screens.
 */
@RunWith(AndroidJUnit4::class)
@LargeTest
class LockOnboardJourneyTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val device: UiDevice = UiDevice.getInstance(instrumentation)
    private val app = instrumentation.targetContext.applicationContext as ShroudApplication
    private val container: AppContainer get() = app.container
    private val arguments = InstrumentationRegistry.getArguments()
    private val baseUrl: String = arguments.getString("shroudApi") ?: "http://10.0.2.2:8080/api/v1"
    private val required = arguments.getString("shroudRequired") == "true"
    private var scenario: ActivityScenario<MainActivity>? = null
    private var previousServer: ServerConfiguration? = null

    private val username = "c4_" + UUID.randomUUID().toString().replace("-", "").take(12)
    private val password = "C4-journey-" + UUID.randomUUID().toString().take(8) + "!7"

    @Before
    fun setUp() {
        if (Build.VERSION.SDK_INT >= 37) instrumentation.uiAutomation.grantRuntimePermission(app.packageName, LOCAL_NETWORK_PERMISSION)
        // P6a asks for notifications on the first unlock; granted up front, its system dialog never covers the journey.
        if (Build.VERSION.SDK_INT >= 33) instrumentation.uiAutomation.grantRuntimePermission(app.packageName, Manifest.permission.POST_NOTIFICATIONS)
        require("the local stack is not reachable at $baseUrl (android/e2e/stack-up.sh)", reachable("$baseUrl/health/live"))
        DeviceLock.ensureUnlocked()
        require("needs a screen lock: android/e2e/emulator-setup.sh", DeviceLock.isSecure)
        // Start signed out, on the stack's server.
        if (container.auth.sessionController.session.value != null) wipeAndWait()
        val server = container.serverConfiguration
        val url = URI(baseUrl)
        if (server.configuration.value.resolvedBaseUrl != baseUrl) {
            previousServer = server.configuration.value
            server.save(ServerConfiguration.localDevelopment(url.host, url.port))
        }
    }

    @After
    fun tearDown() {
        // A failed run leaves nothing of its account on the phone.
        if (container.auth.sessionController.session.value != null) runCatching { wipeAndWait() }
        scenario?.close()
        previousServer?.let { container.serverConfiguration.save(it) }
    }

    @Test
    fun signUpLockUnlockLogOutAndLogInWithThePhrase() {
        scenario = ActivityScenario.launch(MainActivity::class.java)

        // 1. Sign Up. The words arrive in pairs; the card reads "Word 12: <word>" once all are shown.
        val words = signUpThroughTheUi()

        // 2. Lock, then unlock with the screen lock through the system prompt.
        lockNow()
        await("Chats are locked")
        val viaScreenLock = device.findObject(By.desc("Use screen lock")) ?: awaitObject(By.desc("Unlock with screen lock"))
        viaScreenLock.click()
        assertTrue("the system credential prompt", DeviceLock.answerPromptWithPin())
        awaitChats()

        // 3. The phrase path from the lock screen: Log In over it, no Log Out, Back returns.
        lockNow()
        await("Chats are locked")
        tap("Use encryption phrase")
        await("Enter Encryption Phrase")
        await("Signed in as @$username")
        assertNull("no Log Out over the lock screen (design daz2w is for a root without it)", device.findObject(By.desc("Log Out")))
        tap("Back")
        await("Chats are locked")

        // 4. Log Out: the wipe overlay takes the screen, then Welcome.
        runOnMain { container.shell.controller.actions.logOut() }
        awaitAny(listOf("Clearing this phone", "This phone is clear"), timeoutMs = 15_000)
        await("Start Messaging", timeoutMs = 60_000)
        assertEquals(WipePhase.Idle, container.auth.deviceWipe.phase.value)
        assertTrue("leftovers: ${container.auth.deviceWipe.leftovers.value}", container.auth.deviceWipe.leftovers.value.isEmpty())
        assertNull(container.auth.sessionController.session.value)

        // 5. Log In: credentials, then the phrase.
        tap("Log In")
        await("Welcome Back")
        type("Username", username)
        type("Password", password)
        tapButton("Log In")
        await("Enter Encryption Phrase")
        words.forEachIndexed { i, word -> type("Word ${i + 1}", word) }
        tap("Unlock Messages")
        awaitChats()
    }

    @Test
    fun aLogInLeftAtItsPhraseStepOffersLogOutOnTheWayBack() {
        // Design `daz2w`: signed in, the identity not on this phone — Welcome is the root, so the phrase
        // step offers "Log Out" (Back stays, as on iOS).
        scenario = ActivityScenario.launch(MainActivity::class.java)
        signUpThroughTheUi()
        runOnMain { container.shell.controller.actions.logOut() }
        await("Start Messaging", timeoutMs = 60_000)

        tap("Log In")
        await("Welcome Back")
        type("Username", username)
        type("Password", password)
        tapButton("Log In")
        await("Enter Encryption Phrase")
        // Back to the credentials, back to Welcome: the identity was never stored on this phone.
        tap("Back")
        await("Welcome Back")
        tap("Back")
        await("Start Messaging")
        SystemClock.sleep(1_000)
        // A Welcome that reconciles orphaned sessions on appear (iOS `WelcomeView.swift:46-78`,
        // addendum Welcome W4 — the shell's) clears this one with its notice; then no Log Out is needed.
        assumeTrue(
            "Welcome cleared the half-finished login (orphan reconcile)",
            container.auth.sessionController.session.value != null,
        )

        tap("Log In")
        await("Enter Encryption Phrase")
        await("Log Out")
        tap("Log Out")
        awaitAny(listOf("Clearing this phone", "This phone is clear"), timeoutMs = 15_000)
        await("Start Messaging", timeoutMs = 60_000)
        assertNull(container.auth.sessionController.session.value)
    }

    // ---- Steps ----

    /** Welcome → Sign Up → the chats; returns the phrase the card showed. */
    private fun signUpThroughTheUi(): List<String> {
        tap("Start Messaging")
        await("Create Account")
        type("Username", username)
        type("Password", password)
        tap("Continue")
        val words = (1..12).map { n -> awaitObject(By.desc(Pattern.compile("Word $n: .+"))).contentDescription.substringAfter(": ") }
        tap("I wrote down my encryption phrase")
        tap("Create Account")
        awaitChats()
        return words
    }

    private fun lockNow() = runOnMain { container.shell.controller.lockChatsNow() }

    /** The chats are open: keys in memory, the shell revealed, the onboarding layer gone. */
    private fun awaitChats() {
        val unlocked = DeviceLock.waitFor(60_000) { onMain { container.shell.controller.router.isUnlocked } }
        assertTrue("the chats did not open", unlocked)
        assertTrue("the lock screen is still up", DeviceLock.waitFor(10_000) { device.findObject(By.text("Chats are locked")) == null })
    }

    private fun wipeAndWait() {
        val wipe = container.auth.deviceWipe
        runOnMain { wipe.start(WipeReason.Logout) }
        DeviceLock.waitFor(60_000) { wipe.phase.value == WipePhase.Idle && !wipe.isPresented.value }
    }

    // ---- UiAutomator on TalkBack's labels ----

    /** A node whose text or description is [label]. */
    private fun selectors(label: String): List<BySelector> = listOf(By.desc(label), By.text(label))

    private fun awaitObject(selector: BySelector, timeoutMs: Long = 15_000): UiObject2 {
        var found: UiObject2? = null
        DeviceLock.waitFor(timeoutMs) { device.findObject(selector)?.also { found = it } != null }
        return checkNotNull(found) { "nothing matches $selector within $timeoutMs ms" }
    }

    private fun await(label: String, timeoutMs: Long = 15_000) = awaitAny(listOf(label), timeoutMs)

    private fun awaitAny(labels: List<String>, timeoutMs: Long): UiObject2 {
        var found: UiObject2? = null
        DeviceLock.waitFor(timeoutMs) {
            found = labels.asSequence().flatMap(::selectors).firstNotNullOfOrNull { device.findObject(it) }
            found != null
        }
        return checkNotNull(found) { "none of $labels on screen within $timeoutMs ms" }
    }

    /** Taps the control labelled [label] once it is on screen and enabled. */
    private fun tap(label: String) {
        val target = await(label)
        DeviceLock.waitFor(10_000) { target.isEnabled }
        target.click()
        device.waitForIdle()
    }

    /** Taps the clickable control labelled [label] (a title may also be plain text on screen). */
    private fun tapButton(label: String) {
        val target = awaitObject(By.desc(label).clickable(true))
        DeviceLock.waitFor(10_000) { target.isEnabled }
        target.click()
        device.waitForIdle()
    }

    /** Sets the text of the field TalkBack calls [label] (accessibility `ACTION_SET_TEXT`). */
    private fun type(label: String, text: String) {
        val field = awaitObject(By.desc(label))
        field.click()
        field.text = text
        SystemClock.sleep(150)
    }

    // ---- Plumbing ----

    private fun require(message: String, condition: Boolean) {
        if (required) assertTrue(message, condition) else assumeTrue(message, condition)
    }

    private fun reachable(url: String): Boolean = runCatching {
        (URI(url).toURL().openConnection() as HttpURLConnection).run {
            connectTimeout = 3_000
            readTimeout = 3_000
            try {
                responseCode in 200..299
            } finally {
                disconnect()
            }
        }
    }.getOrDefault(false)

    /** The controllers are main-confined (R1). */
    private fun <T> onMain(block: suspend () -> T): T = runBlocking { withContext(Dispatchers.Main) { block() } }

    private fun runOnMain(block: () -> Unit) = instrumentation.runOnMainSync(block)
}
