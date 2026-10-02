package de.corespace.shroud.core.push

import android.content.SharedPreferences

/**
 * `shroud.push` (plan §1.5): background connection (default off), the distributor choice
 * (`null` = not chosen yet, [NONE] = the user chose none), and whether the battery prompt was shown.
 * Wiped by [clear] on Log Out / removal.
 */
interface PushSettings {
    var backgroundConnection: Boolean
    var distributorChoice: String?
    var batteryPromptShown: Boolean
    fun clear()

    companion object {
        const val KEY_BACKGROUND = "push.backgroundConnection"
        const val KEY_BATTERY = "push.batteryPromptShown"
        const val KEY_CHOICE = "push.distributorChoice"
        const val NONE = "none"
    }
}

class PushPreferences(private val prefs: SharedPreferences) : PushSettings {
    override var backgroundConnection: Boolean
        get() = prefs.getBoolean(PushSettings.KEY_BACKGROUND, false)
        set(value) {
            prefs.edit().putBoolean(PushSettings.KEY_BACKGROUND, value).apply()
        }

    override var distributorChoice: String?
        get() = if (prefs.contains(PushSettings.KEY_CHOICE)) prefs.getString(PushSettings.KEY_CHOICE, null) else null
        set(value) {
            val editor = prefs.edit()
            if (value == null) editor.remove(PushSettings.KEY_CHOICE) else editor.putString(PushSettings.KEY_CHOICE, value)
            editor.apply()
        }

    override var batteryPromptShown: Boolean
        get() = prefs.getBoolean(PushSettings.KEY_BATTERY, false)
        set(value) {
            prefs.edit().putBoolean(PushSettings.KEY_BATTERY, value).apply()
        }

    override fun clear() {
        prefs.edit().clear().apply()
    }
}
