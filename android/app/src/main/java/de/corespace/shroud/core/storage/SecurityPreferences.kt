package de.corespace.shroud.core.storage

import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Settings → Privacy and Security → Auto-lock (iOS `AutoLockDelay`,
 * `ios/shroud/Services/Crypto/SecurityPreferences.swift:99-127`; crypto spec §17.4). How long
 * after leaving the app the chats lock: the history key and decrypted threads leave RAM, and
 * coming back needs the fingerprint, the screen lock or the phrase. Default [Immediately].
 *
 * [seconds] is the stored raw value (iOS `rawValue`); [label] the picker copy, verbatim.
 */
enum class AutoLockDelay(val seconds: Int, val label: String) {
    Immediately(0, "Immediately"),
    OneMinute(60, "After 1 minute"),
    FiveMinutes(300, "After 5 minutes"),
    FifteenMinutes(900, "After 15 minutes"),
    Never(-1, "Never"),
    ;

    /**
     * Whether chats left at [leftAtMillis] must be locked by [nowMillis] (`SecurityPreferences.swift:119-126`).
     * Both are `SystemClock.elapsedRealtime()` readings (plan C12): a wall-clock change neither
     * locks early nor keeps chats open.
     */
    fun isDue(leftAtMillis: Long, nowMillis: Long): Boolean = when (this) {
        Never -> false
        Immediately -> true
        else -> nowMillis - leftAtMillis >= seconds * 1000L
    }

    companion object {
        val DEFAULT = Immediately

        /** The delay stored as [raw] seconds, or null for a value no build writes. */
        fun fromSeconds(raw: Int): AutoLockDelay? = entries.firstOrNull { it.seconds == raw }
    }
}

/**
 * User-facing security and privacy switches (iOS `SecurityPreferences`,
 * `ios/shroud/Services/Crypto/SecurityPreferences.swift:4-97`; crypto spec §17.4), in the
 * `shroud.preferences` file ([PrefsFiles.PREFERENCES], plan C11) that Log Out deletes.
 *
 * | key | default | meaning |
 * | --- | --- | --- |
 * | `security.autoLockDelay` | 0 ([AutoLockDelay.Immediately]) | raw seconds, −1 never (`:40-69`) |
 * | `privacy.generateLinkPreviews` | **true** (absent = true, `:23-38`) | the sender builds link previews on this phone |
 * | `privacy.alwaysRelayCalls` | false (`:12-21`) | TURN-only calls |
 * | `privacy.hideDuringScreenCapture` | **true** (absent = true, `:71-79`) | cover chats while recorded/mirrored (P5) |
 *
 * Not ported: `privacy.blockThirdPartyKeyboards` (dropped on Android, P5 — the composer, search and
 * phrase fields set `IME_FLAG_NO_PERSONALIZED_LEARNING` instead) and the retired
 * `security.lockChatsOnBackground` / `requireUserPresence` / `vaultNeedsRewrap` migrations (no older
 * Android build wrote them, `:5-8`, `:49-69`, `:91-96`).
 *
 * Every setter is dropped while [StorageSeal.isSealed] (a wipe is running, crypto spec §14) and
 * when the value is unchanged. The flows follow the file: a `clear()` of the file (the wipe)
 * resets them to the defaults. Thread-safe.
 */
class SecurityPreferences(private val prefs: SharedPreferences, private val seal: StorageSeal) {
    private val autoLock = MutableStateFlow(readAutoLockDelay())
    private val linkPreviews = MutableStateFlow(prefs.getBoolean(KEY_LINK_PREVIEWS, true))
    private val relayCalls = MutableStateFlow(prefs.getBoolean(KEY_RELAY_CALLS, false))
    private val hideCapture = MutableStateFlow(prefs.getBoolean(KEY_HIDE_CAPTURE, true))

    /** Held strongly: the platform keeps preference listeners in a weak map. */
    private val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        when (key) {
            null -> reload()
            KEY_AUTO_LOCK -> autoLock.value = readAutoLockDelay()
            KEY_LINK_PREVIEWS -> linkPreviews.value = prefs.getBoolean(KEY_LINK_PREVIEWS, true)
            KEY_RELAY_CALLS -> relayCalls.value = prefs.getBoolean(KEY_RELAY_CALLS, false)
            KEY_HIDE_CAPTURE -> hideCapture.value = prefs.getBoolean(KEY_HIDE_CAPTURE, true)
        }
    }

    init {
        prefs.registerOnSharedPreferenceChangeListener(listener)
    }

    val autoLockDelay: StateFlow<AutoLockDelay> = autoLock.asStateFlow()
    val generatesLinkPreviews: StateFlow<Boolean> = linkPreviews.asStateFlow()
    val alwaysRelayCalls: StateFlow<Boolean> = relayCalls.asStateFlow()
    val hidesDuringScreenCapture: StateFlow<Boolean> = hideCapture.asStateFlow()

    fun setAutoLockDelay(v: AutoLockDelay) {
        if (seal.isSealed || autoLock.value == v) return
        prefs.edit().putInt(KEY_AUTO_LOCK, v.seconds).apply()
        autoLock.value = v
    }

    fun setGeneratesLinkPreviews(v: Boolean) {
        if (seal.isSealed || linkPreviews.value == v) return
        prefs.edit().putBoolean(KEY_LINK_PREVIEWS, v).apply()
        linkPreviews.value = v
    }

    fun setAlwaysRelayCalls(v: Boolean) {
        if (seal.isSealed || relayCalls.value == v) return
        prefs.edit().putBoolean(KEY_RELAY_CALLS, v).apply()
        relayCalls.value = v
    }

    fun setHidesDuringScreenCapture(v: Boolean) {
        if (seal.isSealed || hideCapture.value == v) return
        prefs.edit().putBoolean(KEY_HIDE_CAPTURE, v).apply()
        hideCapture.value = v
    }

    /** Re-reads every value from the file (after the wipe cleared it). */
    fun reload() {
        autoLock.value = readAutoLockDelay()
        linkPreviews.value = prefs.getBoolean(KEY_LINK_PREVIEWS, true)
        relayCalls.value = prefs.getBoolean(KEY_RELAY_CALLS, false)
        hideCapture.value = prefs.getBoolean(KEY_HIDE_CAPTURE, true)
    }

    /** An absent or unknown raw value reads as the default (`SecurityPreferences.swift:49-64`). */
    private fun readAutoLockDelay(): AutoLockDelay =
        if (prefs.contains(KEY_AUTO_LOCK)) {
            AutoLockDelay.fromSeconds(prefs.getInt(KEY_AUTO_LOCK, AutoLockDelay.DEFAULT.seconds)) ?: AutoLockDelay.DEFAULT
        } else {
            AutoLockDelay.DEFAULT
        }

    companion object {
        const val KEY_AUTO_LOCK = "security.autoLockDelay"
        const val KEY_LINK_PREVIEWS = "privacy.generateLinkPreviews"
        const val KEY_RELAY_CALLS = "privacy.alwaysRelayCalls"
        const val KEY_HIDE_CAPTURE = "privacy.hideDuringScreenCapture"
    }
}
