package de.corespace.shroud.ui.shell

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import de.corespace.shroud.ui.onboarding.LocalOnboardingHero
import de.corespace.shroud.ui.onboarding.onboardingHeroDestination
import de.corespace.shroud.ui.onboarding.onboardingHeroSource
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The onboarding stack's host (`RootView.swift:51, 120, 401-423`; settings-lock addendum
 * *OnboardingHeroTransition.swift*, W8 / H.2 option A): Sign Up and Log In zoom out of the root's
 * brand mark under a plain fade, replace each other with the slide, and every route gets the hero
 * scopes; under Reduce Motion nothing zooms.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w412dp-h915dp", application = Application::class)
class OnboardingRouteHostTest {
    private var top by mutableStateOf<OnboardingRoute?>(null)
    private val heroSeen = mutableMapOf<String, Boolean>()

    @After
    fun tearDown() = ShellUiHarness.disposeAll()

    @Test
    fun theRootAndTheAuthScreensZoomWhileSignUpAndLogInSlide() {
        val auth = listOf(OnboardingRoute.SignUp, OnboardingRoute.LogIn)
        for (screen in auth) {
            assertEquals(OnboardingTransition.Zoom, OnboardingTransition.between(null, screen))
            assertEquals(OnboardingTransition.Zoom, OnboardingTransition.between(screen, null))
            assertEquals(OnboardingTransition.Zoom, OnboardingTransition.between(OnboardingRoute.Welcome, screen))
            assertEquals(OnboardingTransition.Zoom, OnboardingTransition.between(screen, OnboardingRoute.Welcome))
        }
        assertEquals(OnboardingTransition.Slide, OnboardingTransition.between(OnboardingRoute.SignUp, OnboardingRoute.LogIn))
        assertEquals(OnboardingTransition.Slide, OnboardingTransition.between(OnboardingRoute.LogIn, OnboardingRoute.SignUp))
    }

    @Test
    fun everyRouteGetsTheHeroScopes() {
        val ui = host(reduceMotion = false)
        top = OnboardingRoute.SignUp
        ui.idle(1_500)
        top = OnboardingRoute.LogIn
        ui.idle(1_500)
        top = null
        ui.idle(1_500)
        assertEquals(mapOf("root" to true, "SignUp" to true, "LogIn" to true), heroSeen)
        assertTrue(ui.has("mark"))
    }

    @Test
    fun signUpGrowsOutOfTheMarkAndFillsTheWindow() {
        val ui = host(reduceMotion = false)
        val width = ui.rootWidthDp()
        top = OnboardingRoute.SignUp
        ui.idle(48)
        val mid = ui.boundsDp(ui.node("screen:SignUp"))
        assertTrue("mid-zoom the screen is still small: $mid", mid.width < width * 0.9f)
        // It grows from the mark: its centre is above the window's.
        assertTrue("mid-zoom it sits around the mark: $mid", mid.center.y < ui.rootHeightDp() / 2)
        ui.idle(1_500)
        assertEquals(width, ui.boundsDp(ui.node("screen:SignUp")).width, 0.5f)
        assertFalse(ui.has("mark"))
    }

    @Test
    fun underReduceMotionNothingZooms() {
        val ui = host(reduceMotion = true)
        val width = ui.rootWidthDp()
        top = OnboardingRoute.SignUp
        ui.idle(48)
        assertEquals(width, ui.boundsDp(ui.node("screen:SignUp")).width, 0.5f)
        ui.idle(1_000)
        assertFalse(ui.has("mark"))
    }

    /** The host with a stand-in root (an 80 dp mark near the top) and full-window auth screens, as Welcome and Sign Up mark themselves. */
    private fun host(reduceMotion: Boolean) = ShellUiHarness(reduceMotion = reduceMotion) {
        OnboardingRouteHost(top = top, lastWasPush = { true }, reduceMotion = reduceMotion) { route ->
            val name = route?.name ?: "root"
            val hero = LocalOnboardingHero.current
            SideEffect { heroSeen[name] = hero != null }
            if (route == null) {
                Box(Modifier.fillMaxSize()) {
                    Box(
                        Modifier
                            .align(Alignment.TopCenter)
                            .padding(top = 120.dp)
                            .size(80.dp)
                            .onboardingHeroSource(80.dp)
                            .semantics { contentDescription = "mark" },
                    )
                }
            } else {
                Box(Modifier.fillMaxSize().onboardingHeroDestination().semantics { contentDescription = "screen:$name" })
            }
        }
    }
}
