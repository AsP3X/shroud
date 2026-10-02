package de.corespace.shroud.core.storage

import android.content.SharedPreferences
import androidx.core.content.edit

/**
 * Opaque boolean flags for the UI, in the after-first-unlock prefs file [PrefsFiles.UI]
 * (`shroud.ui`). No message content, names or keys — the keys themselves are the caller's
 * (a "ui." prefix is a convention, not interpreted here). Log Out deletes the whole file's keys,
 * the same way it deletes every other prefs key that is not on the keep-list, and a write during
 * that wipe is dropped ([StorageSeal]).
 */
interface UiFlags {
    fun get(key: String, default: Boolean = false): Boolean

    fun set(key: String, value: Boolean)
}

/** [UiFlags] on one [SharedPreferences] file. */
class PrefsUiFlags(
    private val prefs: SharedPreferences,
    private val seal: StorageSeal,
) : UiFlags {
    override fun get(key: String, default: Boolean): Boolean = prefs.getBoolean(key, default)

    override fun set(key: String, value: Boolean) {
        if (seal.isSealed) return
        if (prefs.contains(key) && prefs.getBoolean(key, !value) == value) return
        prefs.edit { putBoolean(key, value) }
    }
}
