package de.corespace.shroud.core.push.unifiedpush

import android.content.BroadcastReceiver
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.util.Log
import androidx.core.os.UserManagerCompat
import de.corespace.shroud.ShroudApplication
import kotlinx.coroutines.launch

/**
 * Play Services → this app: a push, or a new or dropped FCM token. Replaces the library's
 * `FirebaseReceiver` in the manifest.
 *
 * The library reads the push and acknowledges it, then reports it with a broadcast to this
 * package. That broadcast is not delivered to our manifest receiver, so pushes were lost unless
 * the open app's socket brought the same event. [InProcessConnector] takes those broadcasts and
 * hands them to the registrar here, while this receiver keeps the process alive.
 */
class PlayPushReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (!UserManagerCompat.isUserUnlocked(context)) return
        val app = context.applicationContext as? ShroudApplication ?: return
        // The application context: the library binds to Play Services for its acknowledgements,
        // which a receiver's own context refuses.
        val connector = InProcessConnector(app)
        try {
            EmbeddedFcmBridge.receive(connector, intent)
        } catch (e: Exception) {
            // Anything already reported still counts.
            Log.w(TAG, "Play push handling threw ${e.javaClass.simpleName}")
        }
        val events = connector.events.toList()
        if (events.isEmpty()) return
        val pending = goAsync()
        app.container.appScope.launch {
            try {
                for (event in events) app.container.push.onDistributorEvent(event)
            } finally {
                pending.finish()
            }
        }
    }

    private companion object {
        const val TAG = "EmbeddedFcm"
    }
}

/**
 * Collects the connector broadcasts the embedded distributor sends to this package, instead of
 * sending them. Every other broadcast goes out as usual.
 */
internal class InProcessConnector(base: Context) : ContextWrapper(base) {
    val events = mutableListOf<DistributorEvent>()

    override fun sendBroadcast(intent: Intent) {
        if (intent.`package` == packageName && intent.action in CONNECTOR_ACTIONS) {
            events += distributorEvent(intent)
            return
        }
        super.sendBroadcast(intent)
    }

    private companion object {
        val CONNECTOR_ACTIONS = setOf(
            UnifiedPushProtocol.ACTION_NEW_ENDPOINT,
            UnifiedPushProtocol.ACTION_REGISTRATION_FAILED,
            UnifiedPushProtocol.ACTION_UNREGISTERED,
            UnifiedPushProtocol.ACTION_MESSAGE,
        )
    }
}
