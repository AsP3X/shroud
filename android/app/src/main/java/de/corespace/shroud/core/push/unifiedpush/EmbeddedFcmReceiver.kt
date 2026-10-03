package de.corespace.shroud.core.push.unifiedpush

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import org.unifiedpush.android.embedded_fcm_distributor.EmbeddedDistributorReceiver

/**
 * Embedded FCM registration for this app.
 *
 * The library's own receiver reads [android.content.BroadcastReceiver.getSentFromPackage] from a
 * coroutine after [onReceive] has returned. On API 34+ that value is cleared when the broadcast
 * finishes, the origin check fails, and Play Services is never asked — with no failure sent back.
 * Holding the result keeps the sender visible until that check has run.
 *
 * The library receiver also stays quiet when any other distributor has a higher priority, so an
 * explicit choice of Google Play did nothing while ntfy was installed. This receiver is not
 * exported, so only this app's own registration reaches it, and that registration always runs.
 */
class EmbeddedFcmReceiver : EmbeddedDistributorReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        try {
            super.onReceive(context, intent)
        } finally {
            MAIN.postDelayed({ pending.finish() }, ORIGIN_HOLD_MS)
        }
    }

    private companion object {
        val MAIN: Handler = Handler(Looper.getMainLooper())

        /** Long enough for a busy IO thread to start the library's registration coroutine. */
        const val ORIGIN_HOLD_MS = 10_000L
    }
}
