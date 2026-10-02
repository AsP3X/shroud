package de.corespace.shroud.core.push.backgroundconnection

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import de.corespace.shroud.core.push.PushSettings

/**
 * Asks once, when the background connection is turned on, to ignore battery optimisations.
 * A failure to show the system screen is swallowed: registration never throws.
 */
class BatteryOptimization(
    private val context: Context,
    private val prefs: PushSettings,
    private val launch: (Intent) -> Unit = { intent ->
        context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    },
) : BackgroundConnectionController.BatteryGate {
    override fun unrestricted(): Boolean = runCatching {
        val power = context.getSystemService(PowerManager::class.java) ?: return false
        power.isIgnoringBatteryOptimizations(context.packageName)
    }.getOrDefault(false)

    override fun askOnceIfNeeded() {
        if (unrestricted() || prefs.batteryPromptShown) return
        prefs.batteryPromptShown = true
        val intent = Intent(
            Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
            Uri.parse("package:${context.packageName}"),
        )
        runCatching { launch(intent) }
    }
}
