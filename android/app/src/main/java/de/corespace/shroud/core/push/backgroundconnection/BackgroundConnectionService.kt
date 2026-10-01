package de.corespace.shroud.core.push.backgroundconnection

import android.app.Service
import android.content.Intent
import android.os.IBinder

/**
 * The opt-in "Background connection" (decision record 2026-10-01, P1 (d); 00-plan §1.4, §1.7.10):
 * a `specialUse` foreground service that holds the WebSocket with
 * `RealtimeClient.hold(Holder.Background, token)` while the app is in the background, for phones
 * without a UnifiedPush distributor. Permanent low-importance notification on channel
 * `push.background`.
 *
 * Manifest stub created by W0-A; W3-PUSH replaces the body. Nothing starts it before then; if
 * anything does, it stops at once instead of running without a notification.
 */
class BackgroundConnectionService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        stopSelf(startId)
        return START_NOT_STICKY
    }
}
