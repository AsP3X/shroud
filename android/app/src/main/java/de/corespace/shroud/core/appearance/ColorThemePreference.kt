package de.corespace.shroud.core.appearance

import android.content.SharedPreferences
import androidx.core.content.edit
import de.corespace.shroud.core.storage.StorageSeal
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Light, dark, or whatever this phone is set to (`ColorTheme`, `ios/shroud/ShroudUI/Theme/ColorThemePreference.swift:4-44`).
 *
 * @property raw the stored value — the web client's (`shroud.theme` in localStorage).
 * @property title the row title in Settings › Appearance.
 */
enum class ColorTheme(val raw: String, val title: String) {
    System("system", "System"),
    Light("light", "Light"),
    Dark("dark", "Dark"),
    ;

    companion object {
        /** The theme stored as [raw]; unknown or missing values are [System]. */
        fun fromRaw(raw: String?): ColorTheme = entries.firstOrNull { it.raw == raw } ?: System
    }
}

/**
 * The colour theme picked in Settings › Appearance, kept on this phone only
 * (`ColorThemePreference`, `ColorThemePreference.swift:46-83`; settings-lock §8.2).
 *
 * SharedPreferences `shroud.appearance`, key [KEY] — the web client's key and values. System is the
 * default, so it stores nothing (the key is removed). The device wipe's Settings step removes the
 * stored choice with every other setting; [forget] drops the one in memory, so Welcome follows the
 * system again. The shell applies it to the root theme (shell §3.12).
 *
 * @param seal the wipe write stop: while a wipe runs a choice changes memory only (removing the
 *   key — System — is a deletion and always allowed).
 */
class ColorThemePreference(private val prefs: SharedPreferences, private val seal: StorageSeal? = null) {
    private val state = MutableStateFlow(ColorTheme.fromRaw(prefs.getString(KEY, null)))

    val theme: StateFlow<ColorTheme> = state.asStateFlow()

    /** `choose(_:)` (`:69-77`). */
    fun choose(theme: ColorTheme) {
        state.value = theme
        if (theme == ColorTheme.System) {
            prefs.edit { remove(KEY) }
        } else if (seal?.isSealed != true) {
            prefs.edit { putString(KEY, theme.raw) }
        }
    }

    /** Back to System after a Log Out (`forget()`, `:79-82`). */
    fun forget() {
        choose(ColorTheme.System)
    }

    companion object {
        /** `ColorThemePreference.defaultsKey` (`:59`), in prefs `PrefsFiles.APPEARANCE`. */
        const val KEY = "shroud.theme"
    }
}
