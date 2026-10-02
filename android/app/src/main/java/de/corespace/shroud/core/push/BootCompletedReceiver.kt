package de.corespace.shroud.core.push

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.os.UserManagerCompat
import de.corespace.shroud.ShroudApplication

/**
 * Restarts the opt-in background connection after a reboot when the preference is on
 * (plan §1.7.10). `BOOT_COMPLETED` often arrives while the lock screen is still up, and that
 * start does not stay running. `USER_UNLOCKED` starts the process again once credential storage
 * is available. Not direct-boot aware: a locked user does not touch the container.
 */
class BootCompletedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        if (action != Intent.ACTION_BOOT_COMPLETED && action != Intent.ACTION_USER_UNLOCKED) return
        if (!UserManagerCompat.isUserUnlocked(context)) return
        val app = context.applicationContext as? ShroudApplication ?: return
        app.container.push.onBootCompleted()
    }
}
