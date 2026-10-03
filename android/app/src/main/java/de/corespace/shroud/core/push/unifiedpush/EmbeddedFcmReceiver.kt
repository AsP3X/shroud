package de.corespace.shroud.core.push.unifiedpush

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import de.corespace.shroud.ShroudApplication
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlin.coroutines.intrinsics.suspendCoroutineUninterceptedOrReturn

/**
 * A missing sender is still this app: the receiver is not exported, so another app cannot
 * deliver the broadcast. A present sender must be this app.
 */
internal fun allowsEmbeddedSender(sender: String?, ourPackage: String): Boolean =
    sender == null || sender == ourPackage

/**
 * Asks Play Services for a Web Push endpoint and tells this app.
 *
 * Called directly for Google Play. The library's own receiver reads the sender after [onReceive]
 * returns, which is empty on API 34+, so a broadcast to it never registers and never fails.
 */
internal object EmbeddedFcmRegistration {
    private const val TAG = "EmbeddedFcm"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun register(context: Context, token: String, vapid: String) {
        val appContext = context.applicationContext
        if (vapid.isBlank()) {
            EmbeddedFcmBridge.registrationFailed(appContext, token, UnifiedPushProtocol.REASON_VAPID_REQUIRED)
            Log.w(TAG, "Play registration has no VAPID key")
            return
        }
        scope.launch {
            try {
                Log.i(TAG, "Play registration started")
                val onSuccess: (String) -> Unit = { fcmToken ->
                    // The library would broadcast this. That broadcast is not delivered to our
                    // manifest receiver, so the endpoint is handed to the registrar here.
                    deliver(
                        appContext,
                        DistributorEvent(
                            action = UnifiedPushProtocol.ACTION_NEW_ENDPOINT,
                            token = token,
                            endpoint = "https://fcm.googleapis.com/fcm/send/$fcmToken",
                            bytes = null,
                            id = null,
                            reason = null,
                            useDistributor = null,
                        ),
                    )
                    Log.e(TAG, "Play registration completed")
                }
                val onFailure: () -> Unit = {
                    deliver(
                        appContext,
                        DistributorEvent(
                            action = UnifiedPushProtocol.ACTION_REGISTRATION_FAILED,
                            token = token,
                            endpoint = null,
                            bytes = null,
                            id = null,
                            reason = UnifiedPushProtocol.REASON_INTERNAL_ERROR,
                            useDistributor = null,
                        ),
                    )
                    Log.e(TAG, "Play registration failed")
                }
                suspendCoroutineUninterceptedOrReturn<Unit> { cont ->
                    EmbeddedFcmBridge.register(appContext, token, vapid, onSuccess, onFailure, cont)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                deliver(
                    appContext,
                    DistributorEvent(
                        action = UnifiedPushProtocol.ACTION_REGISTRATION_FAILED,
                        token = token,
                        endpoint = null,
                        bytes = null,
                        id = null,
                        reason = UnifiedPushProtocol.REASON_INTERNAL_ERROR,
                        useDistributor = null,
                    ),
                )
                Log.e(TAG, "Play registration threw ${e.javaClass.simpleName}")
            }
        }
    }

    /** Main thread. The registrar's state is main-confined. */
    private fun deliver(context: Context, event: DistributorEvent) {
        val app = context.applicationContext as? ShroudApplication ?: return
        app.container.appScope.launch {
            app.container.push.onDistributorEvent(event)
        }
    }

    fun unregister(context: Context, token: String) {
        val appContext = context.applicationContext
        scope.launch {
            try {
                suspendCoroutineUninterceptedOrReturn<Unit> { cont ->
                    EmbeddedFcmBridge.unregister(appContext, token, cont)
                }
                EmbeddedFcmBridge.unregistered(appContext, token)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                Log.w(TAG, "Play unregistration failed")
            }
        }
    }
}

/**
 * Embedded FCM registration for this app, if a broadcast still arrives.
 * Google Play registration from this app does not use the broadcast: see [EmbeddedFcmRegistration].
 */
class EmbeddedFcmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val sender = if (Build.VERSION.SDK_INT >= 34) sentFromPackage else creatorPackage(intent)
        if (!allowsEmbeddedSender(sender, context.packageName)) {
            Log.w(TAG, "Ignored a push registration from another app")
            return
        }
        val token = intent.getStringExtra(UnifiedPushProtocol.EXTRA_TOKEN) ?: return
        val pending = goAsync()
        try {
            when (intent.action) {
                UnifiedPushProtocol.ACTION_REGISTER ->
                    EmbeddedFcmRegistration.register(
                        context,
                        token,
                        intent.getStringExtra(UnifiedPushProtocol.EXTRA_VAPID).orEmpty(),
                    )
                UnifiedPushProtocol.ACTION_UNREGISTER ->
                    EmbeddedFcmRegistration.unregister(context, token)
            }
        } finally {
            pending?.finish()
        }
    }

    /** `creatorPackage` of the spec's `pi` extra, below API 34. */
    private fun creatorPackage(intent: Intent): String? {
        val pending: PendingIntent? = if (Build.VERSION.SDK_INT >= 33) {
            intent.getParcelableExtra(UnifiedPushProtocol.EXTRA_PI, PendingIntent::class.java)
        } else {
            intent.getParcelableExtra(UnifiedPushProtocol.EXTRA_PI)
        }
        return pending?.creatorPackage
    }

    private companion object {
        const val TAG = "EmbeddedFcm"
    }
}
