package de.corespace.shroud.core.push.backgroundconnection

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.PowerManager
import android.os.SystemClock

/**
 * Reconnect alarm (15 min, `setAndAllowWhileIdle`) and a short partial wake lock around the
 * reconnect. The server's own pings wake the phone between alarms (plan §1.7.10).
 */
class KeepAlive(private val context: Context) : BackgroundConnectionController.ReconnectSchedule {
    override fun arm() {
        val alarm = context.getSystemService(AlarmManager::class.java) ?: return
        val trigger = SystemClock.elapsedRealtime() + INTERVAL_MS
        runCatching { alarm.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, trigger, pending()) }
    }

    override fun cancel() {
        val alarm = context.getSystemService(AlarmManager::class.java) ?: return
        runCatching { alarm.cancel(pending()) }
    }

    override fun pulse(reconnect: () -> Unit) {
        val power = context.getSystemService(PowerManager::class.java)
        val lock = power?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, LOCK_TAG)?.apply { setReferenceCounted(false) }
        runCatching { lock?.acquire(HOLD_MS) }
        try {
            reconnect()
        } finally {
            if (lock?.isHeld == true) lock.release()
            arm()
        }
    }

    private fun pending(): PendingIntent {
        val intent = Intent(context, BackgroundConnectionService::class.java).setAction(BackgroundConnectionService.ACTION_RECONNECT)
        return PendingIntent.getService(context, 0, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    companion object {
        const val INTERVAL_MS = 15 * 60 * 1000L
        private const val HOLD_MS = 10_000L
        private const val LOCK_TAG = "shroud:push-reconnect"
    }
}
