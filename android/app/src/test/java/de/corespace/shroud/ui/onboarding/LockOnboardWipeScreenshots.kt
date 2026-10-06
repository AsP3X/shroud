package de.corespace.shroud.ui.onboarding

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import de.corespace.shroud.core.auth.DeviceDataWipe
import de.corespace.shroud.core.auth.Session
import de.corespace.shroud.core.auth.WipePhase
import de.corespace.shroud.core.auth.WipeReason
import de.corespace.shroud.core.auth.WipeStep
import de.corespace.shroud.core.keys.BiometricLabel
import de.corespace.shroud.core.keys.UnlockMethod
import de.corespace.shroud.core.keys.VaultState
import de.corespace.shroud.core.net.ApiError
import de.corespace.shroud.core.net.ServerConfiguration
import de.corespace.shroud.testing.FakeAppClock
import de.corespace.shroud.ui.components.ComposeHarness
import de.corespace.shroud.ui.components.OverlayHost
import de.corespace.shroud.ui.components.ShroudSheet
import de.corespace.shroud.ui.components.ToastState
import de.corespace.shroud.ui.lock.FakePorts
import de.corespace.shroud.ui.lock.FakeRouter
import de.corespace.shroud.ui.lock.LockFixtures
import de.corespace.shroud.ui.lock.LockProbe
import de.corespace.shroud.ui.lock.LockScreenContent
import de.corespace.shroud.ui.lock.LockScreenModel
import de.corespace.shroud.ui.lock.LockScreenState
import de.corespace.shroud.ui.lock.UnlockPhase
import de.corespace.shroud.ui.theme.ShroudTheme
import de.corespace.shroud.ui.wipe.DeviceWipeOverlayContent
import de.corespace.shroud.ui.wipe.WipeOverlayState
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * Renders every lock-screen, wipe-overlay and onboarding state of W3-LOCK-ONBOARD to a PNG under
 * `build/outputs/c4-screens/` (not committed), for the design pass (C16) to set beside the `.pen`
 * frames `yGDcx`, `o5GgZ`, `LRnnR`, `p5OtXk`, `dRyqM`, `Q7aTH`, `uqmy8`, `qaRQA`, `ZFJ1m`, `oOR2X`,
 * `KZKiT`, `xM2mP`, `uUvuy`, `I7yD0G`, `daz2w` and `aNX3S`. A 412 × 915 dp phone at 2× (the design's
 * artboard), 360 × 800 for the compact frame. Each render must hold a picture, not a blank window.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w412dp-h915dp-xhdpi", application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class LockOnboardWipeScreenshots {
    private val hosts = HarnessHosts()
    private val server = MutableStateFlow(ServerConfiguration.official)

    @Before
    fun onlyWhenAsked() {
        // Renders, not checks: ~40 full-screen native-graphics frames would cost the one shared test
        // JVM's heap in every gate run (GAPS #9), so they run when asked for.
        assumeTrue("set C4_SCREENS=1 to render the C4 screens", System.getenv(SWITCH) == "1")
    }

    @After
    fun tearDown() {
        hosts.disposeAll()
        LockScreenState.set(false)
    }

    private fun ComposeHarness.save(name: String) {
        // The arrivals wait 50 ms of real time (`delay` runs on kotlinx's real clock here), then settle on frames.
        repeat(3) {
            idle()
            Thread.sleep(ARRIVAL_WAIT_MS)
        }
        idle()
        val view = root
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        view.draw(Canvas(bitmap))
        OUT.mkdirs()
        File(OUT, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        // A real picture, not a background with a lone button: enough of it differs from the most common colour.
        val samples = ArrayList<Int>()
        for (y in 0 until bitmap.height step 7) for (x in 0 until bitmap.width step 7) samples += bitmap.getPixel(x, y)
        val background = samples.groupingBy { it }.eachCount().maxBy { it.value }.key
        val drawn = samples.count { it != background }.toFloat() / samples.size
        assertTrue("$name looks blank (${"%.1f".format(drawn * 100)} % drawn)", drawn > MIN_DRAWN)
    }

    // ---- Lock screen ----

    private fun lock(probe: LockProbe, dark: Boolean = false, ports: FakePorts = FakePorts()): ComposeHarness {
        ports.probeResult = probe
        val router = FakeRouter(ports)
        return hosts.host(dark = dark) {
            OverlayHost { LockScreenContent(router, ports, server, saveServer = {}, switchServer = {}) }
        }
    }

    @Test
    fun lockScreenModes() {
        lock(LockFixtures.READY).save("lock-biometric-fingerprint_yGDcx")
        lock(LockFixtures.READY.copy(biometricLabel = BiometricLabel.Face)).save("lock-biometric-face")
        lock(LockFixtures.READY.copy(strongBiometric = false)).save("lock-screen-lock-only")
        lock(LockFixtures.READY.copy(isDeviceSecure = false, strongBiometric = false, vaultState = VaultState.NoScreenLock)).save("lock-no-screen-lock_o5GgZ")
        lock(LockFixtures.READY.copy(vaultState = VaultState.KeyInvalidated)).save("lock-phrase-needed_LRnnR")
        lock(LockFixtures.READY.copy(softwareKeystore = true)).save("lock-software-keystore-notice")
        lock(LockFixtures.READY, dark = true).save("lock-dark_Q7aTH")
    }

    @Test
    @Config(qualifiers = "w360dp-h800dp-xhdpi")
    fun lockScreenCompact() {
        lock(LockFixtures.READY).save("lock-360_uqmy8")
    }

    @Test
    fun lockScreenUnderTheSystemPrompt() {
        // Design `p5OtXk`: what shows under BiometricPrompt (the sheet itself is the system's).
        val ports = FakePorts().apply { unlockAnswer = CompletableDeferred() }
        val ui = lock(LockFixtures.READY, ports = ports)
        ui.click(ui.button("Unlock with fingerprint"))
        ui.save("lock-checking_p5OtXk")
        ports.unlockAnswer.complete(false)
    }

    @Test
    fun lockScreenUnlockChoreography() {
        // Storyboard `dRyqM`: Verified (0-300 ms), Release (300-640 ms), Reveal — stepped on virtual time.
        val ports = FakePorts().apply { probeResult = LockFixtures.READY }
        val router = FakeRouter(ports)
        val model = LockScreenModel(ports, router)
        val ui = hosts.host(reduceMotion = false) {
            OverlayHost { LockScreenContent(router, ports, server, saveServer = {}, switchServer = {}, driven = model) }
        }
        ui.save("lock-idle-motion")
        val clock = TestScope(StandardTestDispatcher())
        clock.launch { model.unlock(UnlockMethod.BiometryPreferred, reduceMotion = false) }
        clock.runCurrent()
        assertEquals(UnlockPhase.Verified, model.phase)
        ui.save("lock-verified_dRyqM-2")
        clock.advanceTimeBy(300)
        clock.runCurrent()
        assertEquals(UnlockPhase.Releasing, model.phase)
        ui.save("lock-releasing_dRyqM-3")
    }

    @Test
    fun lockScreenServerSheet() {
        val ui = lock(LockFixtures.READY)
        ui.click(ui.button("Server settings"))
        ui.save("lock-server-sheet-account")
    }

    // ---- Wipe overlay ----

    private fun wipe(state: WipeOverlayState, dark: Boolean = false) = hosts.host(dark = dark) {
        DeviceWipeOverlayContent(state, presented = true, noun = "phone", onRetry = {}, onContinue = {})
    }

    @Test
    fun wipeOverlayStates() {
        val running = WipeOverlayState(
            WipePhase.Running,
            active = WipeStep.Keys,
            details = mapOf(WipeStep.Session to "Session ended", WipeStep.Messages to "214 removed", WipeStep.Media to "37 files · 18.2 MB"),
            handle = "@niklas_v",
        )
        wipe(running).save("wipe-running_qaRQA")
        wipe(running, dark = true).save("wipe-running-dark")
        wipe(
            WipeOverlayState(
                WipePhase.Done,
                details = mapOf(
                    WipeStep.Session to "Session ended",
                    WipeStep.Messages to "214 removed",
                    WipeStep.Media to "37 files · 18.2 MB",
                    WipeStep.Keys to "11 removed",
                    WipeStep.Settings to "Cleared",
                    WipeStep.Verify to "Nothing left",
                ),
                handle = "@niklas_v",
            ),
        ).save("wipe-done_ZFJ1m")
        wipe(
            WipeOverlayState(
                WipePhase.Failed,
                details = mapOf(WipeStep.Session to "Session ended", WipeStep.Messages to "214 removed", WipeStep.Keys to "11 removed"),
                leftovers = listOf(DeviceDataWipe.Leftover(WipeStep.Media, "media and cached files"), DeviceDataWipe.Leftover(WipeStep.Settings, "settings")),
                handle = "@niklas_v",
            ),
        ).save("wipe-failed")
        wipe(WipeOverlayState(WipePhase.Running, reason = WipeReason.SessionEnded, active = WipeStep.Session, handle = "@niklas_v"))
            .save("wipe-running-session-ended")
    }

    // ---- Onboarding ----

    @Test
    fun welcomeStates() {
        hosts.host { WelcomeScreen(ServerConfiguration.official, {}, {}, {}) }.save("welcome_oOR2X")
        hosts.host { WelcomeScreen(ServerConfiguration.localDevelopment("10.0.2.2", 8080), {}, {}, {}) }.save("welcome-self-hosted")
    }

    private fun signUp(services: FakeOnboardingServices) = hosts.host {
        SignUpContent(services, ToastState(), onBack = {}, onLogIn = {})
    }

    @Test
    fun signUpStates() {
        signUp(FakeOnboardingServices()).save("signup-account-empty_KZKiT")
        val filled = signUp(FakeOnboardingServices())
        filled.type(filled.field("Username"), "noah")
        filled.type(filled.field("Password"), "Correct-horse-42")
        filled.save("signup-account-strong")
        val weak = signUp(FakeOnboardingServices())
        weak.type(weak.field("Username"), "noah")
        weak.type(weak.field("Password"), "password")
        weak.save("signup-account-fair")
        filled.click(filled.button("Continue"))
        filled.waitUntil { filled.button("Copy").isEnabled }
        filled.save("signup-phrase_xM2mP")
        filled.click(filled.button("I wrote down my encryption phrase"))
        filled.save("signup-phrase-confirmed")

        val taken = FakeOnboardingServices().apply { registerError = ApiError.Server("USERNAME_TAKEN", "That username is already taken.", 409) }
        val error = signUp(taken)
        error.type(error.field("Username"), "noah")
        error.type(error.field("Password"), "Correct-horse-42")
        error.click(error.button("Continue"))
        error.waitUntil { error.button("Copy").isEnabled }
        error.click(error.button("I wrote down my encryption phrase"))
        error.click(error.button("Create Account"))
        error.save("signup-account-username-taken")

        val noLock = FakeOnboardingServices().apply { screenLock = false }
        val locked = signUp(noLock)
        locked.type(locked.field("Username"), "noah")
        locked.type(locked.field("Password"), "Correct-horse-42")
        locked.click(locked.button("Continue"))
        locked.save("signup-no-screen-lock_zV24H")
    }

    private fun logIn(services: FakeOnboardingServices) = hosts.host {
        LogInContent(services, onBack = {}, onSignUp = {})
    }

    private fun FakeOnboardingServices.signedIn() = apply {
        session.value = Session("token-device", FakeOnboardingServices.USER, "noah", null, "device")
    }

    @Test
    fun logInStates() {
        logIn(FakeOnboardingServices()).save("login-credentials_uUvuy")
        val wrong = FakeOnboardingServices().apply { loginError = ApiError.Server("INVALID_CREDENTIALS", "Invalid username or password.", 401) }
        val error = logIn(wrong)
        error.type(error.field("Username"), "noah")
        error.type(error.field("Password"), "nope")
        error.click(error.button("Log In"))
        error.save("login-credentials-invalid")

        val services = FakeOnboardingServices()
        val ui = logIn(services)
        ui.type(ui.field("Username"), "noah")
        ui.type(ui.field("Password"), "secret")
        ui.click(ui.button("Log In"))
        ui.save("login-phrase_I7yD0G")
        services.bip39.generate().take(5).forEachIndexed { i, word -> ui.type(ui.tagged("login.word${i + 1}"), word) }
        ui.save("login-phrase-typing")

        val noKey = FakeOnboardingServices().signedIn().apply { hasNoKey = true }
        val fresh = logIn(noKey)
        fresh.waitUntil { fresh.hasTag("login.phraseSource") }
        fresh.save("login-phrase-never-had-one")
        fresh.click(fresh.button("I never got a 12-word phrase"))
        fresh.waitUntil { fresh.button("Copy").isEnabled }
        fresh.save("login-new-phrase")

        val atLaunch = FakeOnboardingServices().signedIn().apply { identityHere = false }
        val launch = logIn(atLaunch)
        launch.waitUntil { launch.hasButton("Log Out") }
        launch.save("login-phrase-log-out_daz2w")

        val overLock = logIn(FakeOnboardingServices().signedIn())
        overLock.save("login-phrase-over-lock-screen")
    }

    /** "Log out your oldest device?" over the checked phrase step, light, dark and busy, the device last seen yesterday. */
    @Test
    fun logInDeviceLimitStates() {
        val clock = FakeAppClock(java.time.Instant.parse("2026-10-02T09:00:00Z").toEpochMilli())
        fun full(dark: Boolean, busy: Boolean, name: String) {
            val services = FakeOnboardingServices()
            services.loginFailures += LogInTest.deviceLimit(LogInTest.OLDEST)
            val ui = hosts.host(dark = dark) { OverlayHost { LogInContent(services, onBack = {}, onSignUp = {}, clock = clock) } }
            ui.type(ui.field("Username"), "noah")
            ui.type(ui.field("Password"), "secret")
            ui.click(ui.button("Log In"))
            // A full account: the phrase step first; the question only once the phrase checked out.
            services.bip39.generate().forEachIndexed { i, word -> ui.type(ui.tagged("login.word${i + 1}"), word) }
            ui.click(ui.button("Unlock Messages"))
            if (busy) {
                services.loginGate = CompletableDeferred()
                ui.click(ui.button("Log Out and Continue"))
            }
            ui.save(name)
            services.loginGate?.complete(Unit)
        }
        full(dark = false, busy = false, "v2-login-device-limit")
        full(dark = true, busy = false, "v2-login-device-limit-dark")
        full(dark = false, busy = true, "v2-login-device-limit-busy")
        full(dark = true, busy = true, "v2-login-device-limit-busy-dark")
    }

    @Test
    fun serverSheetStates() {
        hosts.host {
            OverlayHost {
                Box(Modifier.fillMaxSize().background(ShroudTheme.colors.backgroundGrouped))
                ShroudSheet(visible = true, onDismiss = {}) {
                    ServerSettingsContent(ServerConfiguration.official, onSave = {}, onCancel = {})
                }
            }
        }.save("server-sheet-official_aNX3S")
        val selfHosted = hosts.host {
            OverlayHost {
                Box(Modifier.fillMaxSize().background(ShroudTheme.colors.backgroundGrouped))
                ShroudSheet(visible = true, onDismiss = {}) {
                    ServerSettingsContent(ServerConfiguration.localDevelopment("10.0.2.2", 8080), onSave = {}, onCancel = {})
                }
            }
        }
        selfHosted.save("server-sheet-self-hosted")
        selfHosted.type(selfHosted.field("Address / host"), "")
        selfHosted.click(selfHosted.button("Save"))
        selfHosted.save("server-sheet-error")
    }

    private companion object {
        val OUT = File("build/outputs/c4-screens")

        /** `C4_SCREENS=1 gw :app:testDebugUnitTest --tests '*.LockOnboardWipeScreenshots'` renders them. */
        const val SWITCH = "C4_SCREENS"
        const val ARRIVAL_WAIT_MS = 80L

        /** The share of sampled pixels a screen draws beyond its background (the bare gear is ≈ 1 %). */
        const val MIN_DRAWN = 0.04f
    }
}
