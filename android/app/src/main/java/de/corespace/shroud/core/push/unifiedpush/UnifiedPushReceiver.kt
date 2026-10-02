package de.corespace.shroud.core.push.unifiedpush

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.os.UserManagerCompat
import de.corespace.shroud.ShroudApplication
import kotlinx.coroutines.launch

/**
 * Distributor → app half of AND_3.1.0. Exported, so every intent whose `token` is not the stored
 * connection token is dropped, and a message counts only when it decrypts. Direct boot does not
 * touch [ShroudApplication.container]: credential storage is unreadable until the user unlocks.
 */
class UnifiedPushReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (!UserManagerCompat.isUserUnlocked(context)) return
        val app = context.applicationContext as? ShroudApplication ?: return
        val event = DistributorEvent(
            action = intent.action,
            token = intent.getStringExtra(UnifiedPushProtocol.EXTRA_TOKEN),
            endpoint = intent.getStringExtra(UnifiedPushProtocol.EXTRA_ENDPOINT),
            bytes = intent.getByteArrayExtra(UnifiedPushProtocol.EXTRA_BYTES_MESSAGE),
            id = intent.getStringExtra(UnifiedPushProtocol.EXTRA_ID),
            reason = intent.getStringExtra(UnifiedPushProtocol.EXTRA_REASON),
            useDistributor = intent.getStringExtra(UnifiedPushProtocol.EXTRA_USE_DISTRIBUTOR),
        )
        val pending = goAsync()
        app.container.appScope.launch {
            try {
                app.container.push.onDistributorEvent(event)
            } finally {
                pending.finish()
            }
        }
    }
}
