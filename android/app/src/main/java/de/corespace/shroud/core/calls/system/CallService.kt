package de.corespace.shroud.core.calls.system

import android.app.Service
import android.content.Intent
import android.os.IBinder

/**
 * The foreground service that carries a call from ring to end (calls §6.1, §6.7; manifest type
 * `phoneCall|microphone|mediaProjection`, never `camera`, calls D3).
 *
 * Manifest stub created by W0-A; W3-CALLS-SYSTEM replaces the body (ring order of the calls
 * REVISION 2026-10-01: notification first, then `startForeground(PHONE_CALL)`). Nothing starts
 * it before then; if anything does, it stops at once instead of running without a notification.
 */
class CallService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        stopSelf(startId)
        return START_NOT_STICKY
    }
}
