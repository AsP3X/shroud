package de.corespace.shroud.core.push.unifiedpush

import android.app.BroadcastOptions
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import de.corespace.shroud.core.push.EmbeddedFcm

/** App → distributor half of AND_3.1.0. Explicit broadcasts to the distributor package. */
interface UnifiedPushBroadcaster {
    fun register(distributorPackage: String, token: String, vapid: String)
    fun unregister(distributorPackage: String, token: String)
    fun acknowledge(distributorPackage: String, token: String, id: String)
}

class AndroidUnifiedPushBroadcaster(private val context: Context) : UnifiedPushBroadcaster {
    override fun register(distributorPackage: String, token: String, vapid: String) {
        // Google Play is this app. A broadcast back to our own manifest receiver is not delivered,
        // so Play Services is asked here. Another distributor still gets the spec's broadcast.
        if (distributorPackage == context.packageName) {
            EmbeddedFcmRegistration.register(context.applicationContext, token, vapid)
            return
        }
        val intent = Intent(UnifiedPushProtocol.ACTION_REGISTER).setPackage(distributorPackage)
        intent.putExtra(UnifiedPushProtocol.EXTRA_TOKEN, token)
        intent.putExtra(UnifiedPushProtocol.EXTRA_VAPID, vapid)
        intent.putExtra(UnifiedPushProtocol.EXTRA_MESSAGE, UnifiedPushProtocol.REGISTRATION_MESSAGE)
        if (Build.VERSION.SDK_INT < 34) intent.putExtra(UnifiedPushProtocol.EXTRA_PI, dummyIdentity())
        send(intent)
    }

    override fun unregister(distributorPackage: String, token: String) {
        if (distributorPackage == context.packageName) {
            EmbeddedFcmRegistration.unregister(context.applicationContext, token)
            return
        }
        val intent = Intent(UnifiedPushProtocol.ACTION_UNREGISTER).setPackage(distributorPackage)
        intent.putExtra(UnifiedPushProtocol.EXTRA_TOKEN, token)
        if (Build.VERSION.SDK_INT < 34) intent.putExtra(UnifiedPushProtocol.EXTRA_PI, dummyIdentity())
        send(intent)
    }

    override fun acknowledge(distributorPackage: String, token: String, id: String) {
        val intent = Intent(UnifiedPushProtocol.ACTION_MESSAGE_ACK).setPackage(distributorPackage)
        intent.putExtra(UnifiedPushProtocol.EXTRA_TOKEN, token)
        intent.putExtra(UnifiedPushProtocol.EXTRA_ID, id)
        send(intent)
    }

    private fun send(intent: Intent) {
        // The embedded receiver is not exported. Naming it makes the broadcast explicit;
        // a package-only broadcast is not delivered to that manifest receiver.
        if (intent.`package` == context.packageName) {
            intent.component = ComponentName(context.packageName, EmbeddedFcm.RECEIVER)
        }
        if (Build.VERSION.SDK_INT >= 34) {
            val options = BroadcastOptions.makeBasic().setShareIdentityEnabled(true)
            context.sendBroadcast(intent, null, options.toBundle())
        } else {
            context.sendBroadcast(intent)
        }
    }

    /** Immutable broadcast the distributor reads with `creatorPackage` on API 33 and below. */
    private fun dummyIdentity(): PendingIntent {
        val dummy = Intent(UnifiedPushProtocol.DUMMY_PI_ACTION).setPackage(UnifiedPushProtocol.DUMMY_PI_PACKAGE)
        return PendingIntent.getBroadcast(context, 0, dummy, PendingIntent.FLAG_IMMUTABLE)
    }
}
