package de.corespace.shroud.core.push

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.os.UserManagerCompat
import de.corespace.shroud.ShroudApplication

/**
 * `BOOT_COMPLETED`: restarts the opt-in background connection after a reboot when the preference
 * is on (plan §1.7.10). Not direct-boot aware: a locked user does not touch the container.
 */
class BootCompletedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        if (!UserManagerCompat.isUserUnlocked(context)) return
        val app = context.applicationContext as? ShroudApplication ?: return
        app.container.push.onBootCompleted()
    }
}
