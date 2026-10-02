package de.corespace.shroud.ui.theme

import android.content.res.Configuration
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.core.view.WindowCompat
import de.corespace.shroud.core.appearance.ColorTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Duration

/**
 * Settings › Appearance's theme on the system bars (settings-lock §8.2, shell-chats §3.12; W3-SETTINGS-A
 * acceptance "theme switch incl. system bar icons"), on the real androidx.activity edge-to-edge code:
 * the icons follow the theme the app shows, not the phone's, and stay on it across the
 * configuration changes `MainActivity` handles itself (rotation, the phone's dark mode), which
 * re-apply the first `enableEdgeToEdge` call's styles.
 *
 * A plain activity stands in for `MainActivity`: a second `MainActivity` in one test JVM leaves
 * every later composition without recompositions, so the suite never launches it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ThemedSystemBarsTest {
    private var theme = ColorTheme.System
    private val bars = ThemedSystemBars { theme }

    @Test
    fun theShownThemeIsTheChoiceOrThePhonesForSystem() {
        for (systemDark in listOf(false, true)) {
            assertFalse(ThemedSystemBars.showsDark(ColorTheme.Light, systemDark))
            assertTrue(ThemedSystemBars.showsDark(ColorTheme.Dark, systemDark))
            assertEquals(systemDark, ThemedSystemBars.showsDark(ColorTheme.System, systemDark))
        }
    }

    @Test
    fun onALightPhoneDarkGetsLightIconsAndKeepsThemAcrossARotation() {
        val activity = launch()
        assertEquals("System on a light phone: dark icons", Icons.DARK, icons(activity))

        choose(ColorTheme.Dark, activity)
        assertEquals("Dark: light icons", Icons.LIGHT, icons(activity))

        rotate(activity)
        assertEquals("still light icons after a rotation", Icons.LIGHT, icons(activity))

        choose(ColorTheme.Light, activity)
        assertEquals(Icons.DARK, icons(activity))

        choose(ColorTheme.Dark, activity)
        choose(ColorTheme.System, activity)
        assertEquals("System hands the icons back to the phone", Icons.DARK, icons(activity))
        rotate(activity)
        assertEquals(Icons.DARK, icons(activity))
    }

    @Test
    @Config(qualifiers = "night")
    fun onADarkPhoneLightGetsDarkIconsAndKeepsThemAcrossARotation() {
        val activity = launch()
        assertEquals(Configuration.UI_MODE_NIGHT_YES, activity.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK)
        assertEquals("System on a dark phone: light icons", Icons.LIGHT, icons(activity))

        choose(ColorTheme.Light, activity)
        assertEquals("Light: dark icons", Icons.DARK, icons(activity))
        rotate(activity)
        assertEquals("still dark icons after a rotation", Icons.DARK, icons(activity))

        choose(ColorTheme.System, activity)
        assertEquals(Icons.LIGHT, icons(activity))
    }

    @Test
    fun theFirstApplyAlreadyUsesTheChoice() {
        theme = ColorTheme.Dark
        assertEquals("a Dark choice made before the window opens", Icons.LIGHT, icons(launch()))
    }

    /** What the bars draw their icons in: [LIGHT] over a dark app, [DARK] over a light one. */
    private data class Icons(val lightStatus: Boolean, val lightNavigation: Boolean) {
        companion object {
            val LIGHT = Icons(lightStatus = false, lightNavigation = false)
            val DARK = Icons(lightStatus = true, lightNavigation = true)
        }
    }

    /** An activity whose first `enableEdgeToEdge` is ours, as `MainActivity.onCreate` does it. */
    private fun launch(): ComponentActivity {
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        bars.apply(activity)
        idle()
        return activity
    }

    /** A choice in Settings › Appearance: the root re-applies the bars when the shown theme changes. */
    private fun choose(choice: ColorTheme, activity: ComponentActivity) {
        theme = choice
        bars.apply(activity)
        idle()
    }

    /** A rotation the activity handles itself: the decor view hears it, and androidx re-applies its styles. */
    private fun rotate(activity: ComponentActivity) {
        val current = activity.resources.configuration
        val rotated = Configuration(current).apply {
            orientation = if (current.orientation == Configuration.ORIENTATION_LANDSCAPE) Configuration.ORIENTATION_PORTRAIT else Configuration.ORIENTATION_LANDSCAPE
        }
        activity.window.decorView.dispatchConfigurationChanged(rotated)
        idle()
    }

    private fun icons(activity: ComponentActivity): Icons {
        val insets = WindowCompat.getInsetsController(activity.window, activity.window.decorView)
        return Icons(insets.isAppearanceLightStatusBars, insets.isAppearanceLightNavigationBars)
    }

    private fun idle() {
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(50))
    }
}
