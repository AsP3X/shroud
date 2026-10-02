package de.corespace.shroud.ui.settings

import android.content.res.Configuration
import android.os.Looper
import androidx.core.view.WindowCompat
import androidx.test.core.app.ApplicationProvider
import de.corespace.shroud.MainActivity
import de.corespace.shroud.ShroudApplication
import de.corespace.shroud.core.appearance.ColorTheme
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import java.time.Duration

/**
 * Settings › Appearance's theme on the real window (settings-lock §8.2, shell-chats §3.12; W3-SETTINGS-A
 * acceptance "theme switch incl. system bar icons"): the status and navigation bar icons follow the
 * theme the app shows, not the phone's — light icons over Dark on a light phone, dark icons over
 * Light on a dark phone — and System hands them back to the phone's setting. The choice goes
 * through `auth.colorTheme`, as the Appearance rows do, and the activity is never recreated for it.
 *
 * Each test lets the launch sequence finish first: a signed-out launch forgets a stored theme, as
 * iOS's does (`DeviceWipeController.swift:325-327`), so a choice made before it would be swept.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AppearanceSystemBarsTest {
    private val app = ApplicationProvider.getApplicationContext<ShroudApplication>()
    private val themes get() = app.container.auth.colorTheme
    private var controller: ActivityController<MainActivity>? = null

    @After
    fun tearDown() {
        themes.forget()
        controller?.pause()?.stop()?.destroy()
    }

    @Test
    fun theBarIconsFollowTheChosenThemeOnALightPhone() {
        val activity = launch()
        assertEquals("System on a light phone: dark icons", Bars(lightStatus = true, lightNavigation = true), bars(activity))

        themes.choose(ColorTheme.Dark)
        idle()
        assertEquals("Dark: light icons", Bars(lightStatus = false, lightNavigation = false), bars(activity))

        themes.choose(ColorTheme.Light)
        idle()
        assertEquals(Bars(lightStatus = true, lightNavigation = true), bars(activity))

        themes.choose(ColorTheme.Dark)
        idle()
        themes.choose(ColorTheme.System)
        idle()
        assertEquals("System hands the icons back to the phone", Bars(lightStatus = true, lightNavigation = true), bars(activity))
        assertSame("never recreated for a theme", activity, controller!!.get())
    }

    @Test
    @Config(qualifiers = "night")
    fun theBarIconsFollowTheChosenThemeOnADarkPhone() {
        val activity = launch()
        assertEquals(Configuration.UI_MODE_NIGHT_YES, activity.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK)
        assertEquals("System on a dark phone: light icons", Bars(lightStatus = false, lightNavigation = false), bars(activity))

        themes.choose(ColorTheme.Light)
        idle()
        assertEquals("Light: dark icons", Bars(lightStatus = true, lightNavigation = true), bars(activity))

        themes.choose(ColorTheme.System)
        idle()
        assertEquals(Bars(lightStatus = false, lightNavigation = false), bars(activity))
    }

    @Test
    fun aNewActivityStartsWithTheChosenIcons() {
        launch()
        themes.choose(ColorTheme.Dark)
        idle()
        val recreated = controller!!.recreate().get()
        idle()
        assertEquals(ColorTheme.Dark, themes.theme.value)
        assertEquals(Bars(lightStatus = false, lightNavigation = false), bars(recreated))
    }

    private data class Bars(val lightStatus: Boolean, val lightNavigation: Boolean)

    private fun launch(): MainActivity {
        val built = Robolectric.buildActivity(MainActivity::class.java).setup()
        controller = built
        // The launch sequence runs in the app scope, partly off the main thread.
        val launched = app.container.shell.controller.launchCompleted
        repeat(200) {
            if (launched.value) return@repeat
            idle(50)
            Thread.sleep(5)
        }
        assertTrue("the launch sequence finished", launched.value)
        idle()
        return built.get()
    }

    private fun bars(activity: MainActivity): Bars {
        val insets = WindowCompat.getInsetsController(activity.window, activity.window.decorView)
        return Bars(insets.isAppearanceLightStatusBars, insets.isAppearanceLightNavigationBars)
    }

    private fun idle(ms: Long = 500) {
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))
    }
}
