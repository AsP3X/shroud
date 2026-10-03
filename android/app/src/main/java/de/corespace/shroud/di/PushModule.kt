package de.corespace.shroud.di

import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationManagerCompat
import de.corespace.shroud.AppContainer
import de.corespace.shroud.AppModule
import de.corespace.shroud.core.notifications.NotificationKind
import de.corespace.shroud.core.notifications.NotificationPreview
import de.corespace.shroud.core.notifications.PushContents
import de.corespace.shroud.core.notifications.PushDeliveryHooks
import de.corespace.shroud.core.push.DeviceRemovalWorker
import de.corespace.shroud.core.push.PushCopy
import de.corespace.shroud.core.push.PushDedup
import de.corespace.shroud.core.push.PushDispatcher
import de.corespace.shroud.core.push.PushPreferences
import de.corespace.shroud.core.push.PushRegistrar
import de.corespace.shroud.core.push.PushRegistration
import de.corespace.shroud.core.push.backgroundconnection.BackgroundConnectionController
import de.corespace.shroud.core.push.backgroundconnection.BackgroundConnectionService
import de.corespace.shroud.core.push.backgroundconnection.BatteryOptimization
import de.corespace.shroud.core.push.backgroundconnection.KeepAlive
import de.corespace.shroud.core.push.unifiedpush.AndroidDistributorDirectory
import de.corespace.shroud.core.push.unifiedpush.AndroidUnifiedPushBroadcaster
import de.corespace.shroud.core.push.unifiedpush.DistributorEvent
import de.corespace.shroud.core.push.unifiedpush.UnifiedPushSubscriptionStore
import de.corespace.shroud.core.realtime.RealtimeClient
import de.corespace.shroud.core.storage.KeystoreSealer
import de.corespace.shroud.core.storage.PrefsFiles
import de.corespace.shroud.core.storage.SealedFile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.io.File
import java.util.UUID

/**
 * Push (00-plan §1.7.10; decision record 2026-10-01: UnifiedPush and the opt-in background
 * connection, never Google code). Owner: W3-PUSH — [registration] is the process's one
 * [PushRegistrar]. [onProcessStart] restores it when a session exists and listens to the socket
 * only while the vault is locked, so an unlocked chat is announced by messaging.
 *
 * The Delivery screen's other copy stays in `ui/settings/push`. This module owns
 * [PushDeliveryHooks.noDeliveryReason] and the background-connection notification text.
 * Nobody else constructs this package's classes (00-plan §2.0 rule 3).
 */
class PushModule(container: AppContainer) : AppModule(container) {
    private val app: Context get() = container.appContext

    private val registrarLazy: Lazy<PushRegistrar> = lazy { createRegistrar() }

    /** Both delivery paths (UnifiedPush, background connection) and the Log Out forget. */
    val registration: PushRegistration get() = registrarLazy.value

    /** The notification test's view of push: register again, and why nothing could arrive. */
    val deliveryHooks: PushDeliveryHooks = object : PushDeliveryHooks {
        override fun register() = registration.register()

        override fun noDeliveryReason(): String? = PushCopy.noDeliveryReason(registration.delivery.value)
    }

    /**
     * Session restore, and socket events while chats are locked (`unlockedUserId == null`).
     * The background socket is [RealtimeClient.Holder.Background] only — no second presence path.
     */
    override fun onProcessStart() {
        val scope = container.appScope
        scope.launch {
            container.auth.sessionController.session.collect { session ->
                if (session == null) registrar().stop() else registrar().start()
            }
        }
        scope.launch {
            container.realtime.client.events.collect { event ->
                if (container.keys.cryptoController.unlockedUserId.value != null) return@collect
                registrar().onBackgroundSocket(event)
            }
        }
    }

    /** Distributor → app, after the user is unlocked (the receiver drops direct boot). A message waits for its preview. */
    suspend fun onDistributorEvent(event: DistributorEvent) = registrar().onDistributorEvent(event)

    fun onBackgroundServiceStarted() = registrar().onBackgroundServiceStarted()

    fun onBackgroundReconnect() = registrar().onBackgroundReconnect()

    fun onBackgroundServiceDestroyed() = registrar().onBackgroundServiceDestroyed()

    fun onBootCompleted() = registrar().onBootCompleted()

    /**
     * Synchronous stop for [de.corespace.shroud.di.WipeHooksImpl.haltWriters]. Always asks the
     * service to stop. The socket hold is released only when the registrar was already built, so a
     * wipe at launch does not construct it, the realtime client, or a call controller.
     */
    fun stopBackgroundSynchronously() {
        runCatching { app.stopService(Intent(app, BackgroundConnectionService::class.java)) }
        if (registrarLazy.isInitialized()) registrar().stopBackgroundKeepPreference()
    }

    private fun registrar(): PushRegistrar = registrarLazy.value

    private fun createRegistrar(): PushRegistrar {
        val prefs = PushPreferences(app.getSharedPreferences(PrefsFiles.PUSH, Context.MODE_PRIVATE))
        val background = BackgroundConnectionController(
            host = object : BackgroundConnectionController.Host {
                override fun start() {
                    app.startForegroundService(Intent(app, BackgroundConnectionService::class.java))
                }

                override fun stop() {
                    app.stopService(Intent(app, BackgroundConnectionService::class.java))
                }
            },
            prefs = prefs,
            sessionToken = { sessionToken() },
            hold = { token -> container.realtime.client.hold(RealtimeClient.Holder.Background, token) },
            releaseHold = { container.realtime.client.release(RealtimeClient.Holder.Background) },
            onEnded = { container.realtime.foregroundCoordinator.onBackgroundConnectionEnded() },
            battery = BatteryOptimization(app, prefs),
            reconnect = KeepAlive(app),
            onReconnect = { runCatching { container.realtime.client.onNetworkAvailable() } },
        )
        val dispatcher = PushDispatcher(
            dedup = PushDedup(),
            clock = container.clock,
            post = { contents, name -> container.notifications.systemNotifier.post(contents, name) },
            cancelChat = { id -> container.notifications.systemNotifier.cancelChat(id) },
            onPushWhileRunning = { contents, name, messageText ->
                container.notifications.controller.onPushWhileRunning(contents, name, messageText)
            },
            calls = { push -> container.calls.controller.handleCallPush(push) },
            scheduleRemoval = { DeviceRemovalWorker.enqueue(app) },
            nameFor = { id -> container.notifications.nameCache.name(id) },
            selfUserId = { container.auth.sessionController.session.value?.userId },
            rememberName = { id, name -> container.notifications.nameCache.remember(id, name) },
            rememberPreview = { id, text -> container.notifications.systemNotifier.rememberPreview(id, text) },
            shadePreview = {
                val prefs = container.notifications.preferences
                prefs.enabled && prefs.showContent
            },
        )
        return PushRegistrar(
            scope = container.appScope,
            sessionToken = { sessionToken() },
            api = container.net.api,
            directory = AndroidDistributorDirectory(app),
            store = { subscriptionStore() },
            prefs = prefs,
            broadcast = AndroidUnifiedPushBroadcaster(app),
            background = background,
            notificationsEnabled = {
                runCatching { NotificationManagerCompat.from(app).areNotificationsEnabled() }.getOrDefault(true)
            },
            ourPackage = app.packageName,
            dispatcher = dispatcher,
            messagePreview = { contents -> decryptedPreview(contents) },
        )
    }

    /**
     * The clipped body of a message push. Null when Show Content is off, or the message cannot
     * be opened. The text stays on this phone.
     */
    private suspend fun decryptedPreview(contents: PushContents): String? {
        if (contents.kind != NotificationKind.Message) return null
        if (!container.notifications.preferences.showContent) return null
        val peer = contents.peerUserId ?: return null
        val messageId = contents.messageId ?: return null
        val text = try {
            openNotificationText(peer, messageId)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
        return NotificationPreview.clip(text)
    }

    /**
     * The message body, decrypted here. Chats that are still unlocked open it directly. After
     * auto-lock, Show Content's sealed history key opens them for this one message and is wiped
     * again. The lock screen does not come down. Null when that key is absent or the phone itself
     * is locked.
     */
    private suspend fun openNotificationText(peer: UUID, messageId: UUID): String? {
        val crypto = container.keys.cryptoController
        val messaging = container.messaging.controller
        if (crypto.isUnlocked) return messaging.notificationText(peer, messageId)
        val history = container.notifications.contentKey.open() ?: return null
        return try {
            crypto.withKeysForNotification(history) {
                messaging.notificationText(peer, messageId)
            }
        } finally {
            history.fill(0)
        }
    }

    /** AFU alias `shroud.unifiedpush.v1`. The key is generated on the first seal, not here. */
    private val subscriptionSealer by lazy {
        KeystoreSealer(UnifiedPushSubscriptionStore.ALIAS, unlockedDeviceRequired = false)
    }

    private fun subscriptionStore() = UnifiedPushSubscriptionStore(
        file = SealedFile(File(app.noBackupFilesDir, UnifiedPushSubscriptionStore.FILE_NAME), subscriptionSealer),
        deleteKey = { subscriptionSealer.deleteKey() },
    )

    private fun sessionToken(): String? = container.auth.sessionController.session.value?.token
}
