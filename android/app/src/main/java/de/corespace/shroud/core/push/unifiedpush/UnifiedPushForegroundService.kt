package de.corespace.shroud.core.push.unifiedpush

import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.IBinder

/**
 * AND_3.1.0: the distributor may bind for five seconds while it delivers a message, so this
 * process can start a foreground service from the background. The binder does no work and does
 * not touch the container.
 */
class UnifiedPushForegroundService : Service() {
    override fun onBind(intent: Intent?): IBinder? {
        if (intent?.action != UnifiedPushProtocol.ACTION_RAISE_TO_FOREGROUND) return null
        return Binder()
    }
}
