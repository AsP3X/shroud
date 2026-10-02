package de.corespace.shroud.ui.theme

import android.content.res.Configuration
import android.content.res.Resources
import android.graphics.Color
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import de.corespace.shroud.core.appearance.ColorTheme

/**
 * The status and navigation bar icons on the theme the app shows, not the phone's (shell-chats §3.12;
 * settings-lock §8.2; iOS puts the chosen theme on the window, `RootView.swift:118-119`,
 * `ColorThemePreference.swift:85-126`): light icons over Dark on a light phone, dark icons over
 * Light on a dark phone, and the phone's own setting for System. The bars stay edge to edge.
 *
 * androidx.activity (1.13) keeps the **first** `enableEdgeToEdge` call's styles on a hidden child of
 * the decor view and re-applies them on every configuration change the activity handles itself
 * (rotation, font size, the phone's dark mode — `MainActivity` declares them all). Styles fixed to a
 * value, or the default system-following ones, would hand a Dark choice on a light phone dark icons
 * again at the next rotation. So these styles are built once and read [theme] whenever they are
 * applied: [apply] once before `super.onCreate`, then again whenever the shown theme changes.
 */
class ThemedSystemBars(private val theme: () -> ColorTheme) {
    val statusBarStyle: SystemBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT) { showsDark(it) }
    val navigationBarStyle: SystemBarStyle = SystemBarStyle.auto(LIGHT_NAVIGATION_SCRIM, DARK_NAVIGATION_SCRIM) { showsDark(it) }

    /** Puts [activity]'s bars on the current theme (and, the first time, edge to edge). */
    fun apply(activity: ComponentActivity) {
        activity.enableEdgeToEdge(statusBarStyle, navigationBarStyle)
    }

    private fun showsDark(resources: Resources): Boolean =
        showsDark(theme(), (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES)

    companion object {
        /** `enableEdgeToEdge`'s own scrims for three-button navigation (androidx.activity `EdgeToEdge.kt`). */
        val LIGHT_NAVIGATION_SCRIM = Color.argb(0xe6, 0xFF, 0xFF, 0xFF)
        val DARK_NAVIGATION_SCRIM = Color.argb(0x80, 0x1b, 0x1b, 0x1b)

        /** Whether the window shows dark: the choice, or the phone's setting ([systemDark]) for System. */
        fun showsDark(theme: ColorTheme, systemDark: Boolean): Boolean = when (theme) {
            ColorTheme.Light -> false
            ColorTheme.Dark -> true
            ColorTheme.System -> systemDark
        }
    }
}
