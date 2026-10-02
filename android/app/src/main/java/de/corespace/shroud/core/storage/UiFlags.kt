package de.corespace.shroud.core.storage

import android.content.SharedPreferences
import androidx.core.content.edit

/**
 * Opaque boolean flags for the UI, in the after-first-unlock prefs file [PrefsFiles.UI]
 * (`shroud.ui`). No message content, names or keys — the keys themselves are the caller's
 * (a "ui." prefix is a convention, not interpreted here). Log Out deletes the whole file's keys,
 * the same way it deletes every other prefs key that is not on the keep-list, and a write during
 * that wipe is dropped ([StorageSeal]).
 *
 * [kept] is the variant that survives Log Out: the same flags, stored in [PrefsFiles.DEVICE]
 * (the whole file is already on the wipe keep-list, like `notifications.permissionAsked`).
 * The kept instance's [kept] is itself.
 */
interface UiFlags {
    fun get(key: String, default: Boolean = false): Boolean

    fun set(key: String, value: Boolean)

    /**
     * Flags in [PrefsFiles.DEVICE], kept across Log Out. [shroud.ui][PrefsFiles.UI] itself is
     * still wiped. This instance, when it is already the kept one.
     */
    val kept: UiFlags
}

/**
 * [UiFlags] on one [SharedPreferences] file. [surviving] is the Log Out-stable instance
 * ([PrefsFiles.DEVICE]); null means this file is that instance, so [kept] is this object.
 */
class PrefsUiFlags(
    private val prefs: SharedPreferences,
    private val seal: StorageSeal,
    private val surviving: UiFlags? = null,
) : UiFlags {
    override fun get(key: String, default: Boolean): Boolean = prefs.getBoolean(key, default)

    override fun set(key: String, value: Boolean) {
        if (seal.isSealed) return
        if (prefs.contains(key) && prefs.getBoolean(key, !value) == value) return
        prefs.edit { putBoolean(key, value) }
    }

    override val kept: UiFlags get() = surviving ?: this
}
