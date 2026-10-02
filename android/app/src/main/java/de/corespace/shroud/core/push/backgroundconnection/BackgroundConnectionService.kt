package de.corespace.shroud.core.push.backgroundconnection

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.os.UserManagerCompat
import de.corespace.shroud.MainActivity
import de.corespace.shroud.R
import de.corespace.shroud.ShroudApplication
import de.corespace.shroud.core.notifications.NotificationChannels

/**
 * Opt-in background connection (plan §1.4, §1.7.10): a `specialUse` foreground service that holds
 * `RealtimeClient.Holder.Background`. The socket then authenticates with `background: true` and
 * does not claim focus, so contacts do not see this phone as online. Notification text is
 * [BackgroundConnectionController.NOTIFICATION_TEXT] on channel `push.background` (importance MIN).
 */
class BackgroundConnectionService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startInForeground()
        val app = application
        if (!UserManagerCompat.isUserUnlocked(this) || app !is ShroudApplication) {
            stopSelf()
            return START_NOT_STICKY
        }
        val push = app.container.push
        if (intent?.action == ACTION_RECONNECT) push.onBackgroundReconnect() else push.onBackgroundServiceStarted()
        return START_STICKY
    }

    override fun onDestroy() {
        val app = application
        if (app is ShroudApplication && UserManagerCompat.isUserUnlocked(this)) {
            runCatching { app.container.push.onBackgroundServiceDestroyed() }
        }
    }

    private fun startInForeground() {
        ensureChannel()
        val notification = notification()
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun ensureChannel() {
        val app = application
        if (app is ShroudApplication && UserManagerCompat.isUserUnlocked(this)) {
            if (runCatching { app.container.notifications.channels.ensure() }.isSuccess) return
        }
        val manager = getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(NotificationChannels.BACKGROUND_CONNECTION) != null) return
        val channel = NotificationChannel(
            NotificationChannels.BACKGROUND_CONNECTION,
            "Background connection",
            NotificationManager.IMPORTANCE_MIN,
        )
        channel.setShowBadge(false)
        manager.createNotificationChannel(channel)
    }

    private fun notification(): Notification {
        val settings = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, NotificationChannels.BACKGROUND_CONNECTION)
            .setSmallIcon(R.drawable.ic_stat_shroud)
            .setContentTitle("Shroud")
            .setContentText(BackgroundConnectionController.NOTIFICATION_TEXT)
            .setOngoing(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .addAction(0, "Settings", settings)
            .build()
    }

    companion object {
        const val ACTION_RECONNECT = "de.corespace.shroud.push.RECONNECT"
        const val NOTIFICATION_ID = 7101
    }
}
