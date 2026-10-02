package de.corespace.shroud.core.calls.system

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import androidx.core.app.ServiceCompat
import de.corespace.shroud.core.model.Ids
import de.corespace.shroud.core.notifications.SystemNotifier
import java.util.UUID

/**
 * The phoneCall foreground service for one call (calls §6.1, §6.7; manifest type
 * `phoneCall|microphone|mediaProjection`, never `camera`).
 *
 * Only a promote start calls [ServiceCompat.startForeground]. Decline, hang up and Speaker are
 * ordinary starts: calling `startForeground` from those throws while the process is backgrounded.
 * [START_NOT_STICKY]: a killed process rings again from the notification, not from a restarted service.
 */
class CallService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val system = CallSystemRegistry.current
        if (system == null) {
            if (intent?.getBooleanExtra(EXTRA_PROMOTE, false) == true) {
                try {
                    NotificationManagerShade(applicationContext).ensureChannels()
                    ServiceCompat.startForeground(
                        this,
                        SystemNotifier.ID_CALL,
                        CallNotices(applicationContext).minimal(),
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_PHONE_CALL,
                    )
                } catch (_: Exception) {
                }
            }
            stopSelf(startId)
            return START_NOT_STICKY
        }
        system.onServiceStart(this, intent, startId)
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        CallSystemRegistry.current?.onServiceDestroyed()
        super.onDestroy()
    }

    companion object {
        const val ACTION_DECLINE = "de.corespace.shroud.calls.DECLINE"
        const val ACTION_HANGUP = "de.corespace.shroud.calls.HANGUP"
        const val ACTION_SPEAKER = "de.corespace.shroud.calls.SPEAKER"
        const val EXTRA_PROMOTE = "promote"

        fun promoteIntent(context: Context, callId: UUID): Intent =
            Intent(context, CallService::class.java)
                .putExtra(EXTRA_PROMOTE, true)
                .putExtra(CallIntents.EXTRA_CALL_ID, Ids.wire(callId))
    }
}
