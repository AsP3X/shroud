package de.corespace.shroud.core.calls

import android.content.SharedPreferences
import androidx.core.content.edit
import de.corespace.shroud.core.storage.SecurityPreferences
import de.corespace.shroud.core.storage.StorageSeal
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * What calls keep on this phone (calls §11), in `shroud.preferences` (plan §1.5, which Log Out
 * wipes) — plain preferences, not secrets:
 *
 * | key | value | iOS |
 * | --- | --- | --- |
 * | `calls.screenShareResolution` | `"720p"` / `"1080p"` / `"source"` | `CallController.swift:2150, 2159` |
 * | `calls.screenShareFrameRate` | 15 / 30 / 60 | `CallController.swift:2151, 2160` |
 *
 * Unknown or missing parts read as `ScreenShareQuality.Standard`'s, each alone. "Always relay
 * calls" stays [SecurityPreferences.alwaysRelayCalls] (`privacy.alwaysRelayCalls`, W1-KEYS); this
 * class only reads it (`CallController.relayPolicy(for:)`, `CallController.swift:2053-2055`).
 *
 * Writes are dropped while [StorageSeal.isSealed] (a wipe runs) and when nothing changes. A
 * `clear()` of the file (the wipe) resets [screenShareQuality] to the standard. Thread-safe.
 */
class CallPreferences(
    private val prefs: SharedPreferences,
    private val seal: StorageSeal,
    private val security: SecurityPreferences?,
) {
    private val quality = MutableStateFlow(read())

    /** Held strongly: the platform keeps preference listeners in a weak map. */
    private val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == null || key == KEY_RESOLUTION || key == KEY_FRAME_RATE) quality.value = read()
    }

    init {
        prefs.registerOnSharedPreferenceChangeListener(listener)
    }

    /** The resolution and frame rate our screen goes out at, in every call from this phone (CC:94-95). */
    val screenShareQuality: StateFlow<ScreenShareQuality> = quality.asStateFlow()

    /** "Always relay calls" (Privacy and Security), read at every call start (CC:591, 862). */
    val alwaysRelayCalls: Boolean get() = security?.alwaysRelayCalls?.value ?: false

    /** Keeps [value] for the next share too (`ScreenShareQuality.saved` setter, CC:2163-2166). A frame rate not offered is ignored. */
    fun setScreenShareQuality(value: ScreenShareQuality) {
        if (seal.isSealed || value.frameRate !in ScreenShareQuality.FRAME_RATES || quality.value == value) return
        prefs.edit {
            putString(KEY_RESOLUTION, value.resolution.raw)
            putInt(KEY_FRAME_RATE, value.frameRate)
        }
        quality.value = value
    }

    private fun read(): ScreenShareQuality = ScreenShareQuality.fromStored(
        resolution = prefs.getString(KEY_RESOLUTION, null),
        frameRate = if (prefs.contains(KEY_FRAME_RATE)) prefs.getInt(KEY_FRAME_RATE, 0) else null,
    )

    companion object {
        const val KEY_RESOLUTION = "calls.screenShareResolution"
        const val KEY_FRAME_RATE = "calls.screenShareFrameRate"
    }
}
