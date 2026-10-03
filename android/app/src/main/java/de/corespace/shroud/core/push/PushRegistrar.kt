package de.corespace.shroud.core.push

import android.util.Log
import de.corespace.shroud.core.net.ApiError
import de.corespace.shroud.core.net.ErrorCodes
import de.corespace.shroud.core.net.ShroudApi
import de.corespace.shroud.core.net.WebPushSubscriptionBody
import de.corespace.shroud.core.notifications.NotificationKind
import de.corespace.shroud.core.notifications.PushContents
import de.corespace.shroud.core.push.backgroundconnection.BackgroundConnectionController
import de.corespace.shroud.core.push.unifiedpush.DistributorDirectory
import de.corespace.shroud.core.push.unifiedpush.DistributorEvent
import de.corespace.shroud.core.push.unifiedpush.P256
import de.corespace.shroud.core.push.unifiedpush.UnifiedPushBroadcaster
import de.corespace.shroud.core.push.unifiedpush.UnifiedPushProtocol
import de.corespace.shroud.core.push.unifiedpush.UnifiedPushSubscriptionStore
import de.corespace.shroud.core.push.unifiedpush.WebPushDecryptor
import de.corespace.shroud.core.realtime.RealtimeEvent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * UnifiedPush registration and the background-connection switch (plan §1.7.10, contract K6).
 * Never throws: a problem is [UnifiedPushState.Unavailable]. The subscription store is touched
 * only once a registration actually runs, so building this object does not open the keystore.
 */
class PushRegistrar(
    private val scope: CoroutineScope,
    private val sessionToken: () -> String?,
    private val api: ShroudApi,
    private val directory: DistributorDirectory,
    private val store: () -> UnifiedPushSubscriptionStore,
    private val prefs: PushSettings,
    private val broadcast: UnifiedPushBroadcaster,
    private val background: BackgroundConnectionController,
    private val notificationsEnabled: () -> Boolean,
    private val ourPackage: String,
    private val dispatcher: PushDispatcher,
    /**
     * The decrypted text of a message push, clipped, or null. Called only for a message, and only
     * while Message Preview is on. It must not prompt, and it must not send the text anywhere.
     */
    private val messagePreview: suspend (PushContents) -> String? = { null },
) : PushRegistration {
    private val deliveryState = MutableStateFlow(
        PushDelivery(UnifiedPushState.Unknown, backgroundConnection = false, batteryUnrestricted = false),
    )
    override val delivery: StateFlow<PushDelivery> = deliveryState

    private var internal: UnifiedPushState = UnifiedPushState.Unknown
    private var delivering = false
    private var forgetting = false
    private var endpointAccepted = false
    private var label: String = ""
    private var registerJob: Job? = null

    /** How long a distributor may stay silent before "Connecting…" becomes a failure. Tests shorten it. */
    internal var registrationWaitMs: Long = REGISTRATION_WAIT_MS

    init {
        background.onChanged = { publish() }
        publish()
    }

    override fun start() {
        if (sessionToken().isNullOrEmpty()) {
            stop()
            return
        }
        delivering = true
        register()
        if (prefs.backgroundConnection) background.enable() else publish()
    }

    override fun stop() {
        delivering = false
        registerJob?.cancel()
        background.stopKeepPreference()
        publish()
    }

    override fun register() {
        if (forgetting) return
        registerJob?.cancel()
        registerJob = scope.launch {
            try {
                registerNow()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (!forgetting && internal is UnifiedPushState.Registering) {
                    Log.e(TAG, "registration threw ${e.javaClass.simpleName}: ${e.message}")
                    internal = UnifiedPushState.Unavailable(NoPushReason.DistributorFailed)
                    publish()
                }
            }
        }
    }

    override fun onSystemSettingsMaybeChanged() {
        publish()
        if (delivering && sessionToken() != null && internal !is UnifiedPushState.Registered) register()
    }

    override fun distributors(): List<Distributor> =
        runCatching { directory.distributors() }.getOrDefault(emptyList())

    override fun chooseDistributor(packageName: String?) {
        // A finished registration is left alone. One still saying "Connecting…" is retried:
        // the picker can be tapped again after a distributor that never answered.
        if (packageName != null && packageName == prefs.distributorChoice &&
            internal is UnifiedPushState.Registered
        ) {
            return
        }
        if (packageName == null) {
            prefs.distributorChoice = PushSettings.NONE
            scope.launch {
                runCatching { unregisterStored() }
                if (!forgetting) {
                    internal = UnifiedPushState.Unavailable(NoPushReason.NoneChosen)
                    endpointAccepted = false
                    publish()
                }
            }
            return
        }
        prefs.distributorChoice = packageName
        register()
    }

    override fun setBackgroundConnection(enabled: Boolean) {
        if (sessionToken().isNullOrEmpty()) {
            prefs.backgroundConnection = enabled
            background.stopKeepPreference()
            publish()
            return
        }
        if (enabled) background.enable() else background.disable()
        publish()
    }

    override suspend fun forgetRegistration() {
        if (forgetting) return
        forgetting = true
        registerJob?.cancel()
        try {
            val record = runCatching { store().load() }.getOrNull()
            val token = sessionToken()
            if (record != null && record.token.isNotEmpty() && record.distributorPackage.isNotEmpty()) {
                runCatching { broadcast.unregister(record.distributorPackage, record.token) }
            }
            if (!token.isNullOrEmpty()) {
                runCatching { api.deleteWebPushSubscription(token) }
            }
            background.forget()
            delivering = false
            runCatching { store().forget() }
            prefs.clear()
            internal = UnifiedPushState.Unknown
            endpointAccepted = false
            label = ""
            publish()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
        } finally {
            forgetting = false
        }
    }

    suspend fun onDistributorEvent(event: DistributorEvent) {
        if (forgetting) return
        val record = runCatching { store().load() }.getOrNull()
        if (!UnifiedPushProtocol.tokenMatches(record?.token, event.token)) return
        when (event.action) {
            UnifiedPushProtocol.ACTION_NEW_ENDPOINT -> onNewEndpoint(record!!, event)
            UnifiedPushProtocol.ACTION_MESSAGE -> onMessage(record!!, event)
            UnifiedPushProtocol.ACTION_REGISTRATION_FAILED -> onRegistrationFailed(record!!)
            UnifiedPushProtocol.ACTION_UNREGISTERED -> onUnregistered(event.useDistributor)
            UnifiedPushProtocol.ACTION_TEMP_UNAVAILABLE -> onTempUnavailable()
        }
    }

    fun onBackgroundSocket(event: RealtimeEvent) = dispatcher.onSocket(event)

    /** The service is up: hold `Holder.Background` once. A start we did not ask for is stopped. */
    fun onBackgroundServiceStarted() = background.onServiceStarted()

    /** The keep-alive alarm: reconnect, then hold again if the connection is still wanted. */
    fun onBackgroundReconnect() = background.onReconnectWake()

    /** The service is gone. Releases the socket hold when this side stopped it. */
    fun onBackgroundServiceDestroyed() = background.onServiceDestroyed()

    /** After boot, and only once the user is unlocked (the receiver already checked). */
    fun onBootCompleted() = background.restartAfterBoot(userUnlocked = true)

    /** `haltWriters`: stop the service without clearing the preference or building anything new. */
    fun stopBackgroundKeepPreference() = background.stopKeepPreference()

    private suspend fun registerNow() {
        val session = sessionToken()
        if (session.isNullOrEmpty()) {
            stop()
            return
        }
        delivering = true
        val installed = distributors()
        val choice = prefs.distributorChoice
        val embedded = installed.firstOrNull { it.embedded }
        val selected = when {
            installed.isEmpty() -> {
                internal = UnifiedPushState.Unavailable(NoPushReason.NoDistributorInstalled)
                publish()
                return
            }
            choice == PushSettings.NONE -> {
                internal = UnifiedPushState.Unavailable(NoPushReason.NoneChosen)
                publish()
                return
            }
            // Play Services: the embedded distributor, unless the user already picked another.
            choice == null && embedded != null -> embedded.also { prefs.distributorChoice = it.packageName }
            choice == null && installed.size > 1 -> {
                internal = UnifiedPushState.Unavailable(NoPushReason.NoneChosen)
                publish()
                return
            }
            choice == null -> installed.single().also { prefs.distributorChoice = it.packageName }
            else -> installed.firstOrNull { it.packageName == choice } ?: run {
                Log.e(TAG, "registration choice is not installed (${installed.size} distributors)")
                internal = UnifiedPushState.Unavailable(NoPushReason.DistributorFailed)
                publish()
                return
            }
        }
        label = selected.label
        // A previous attempt may have accepted an endpoint and then failed to save it.
        // Leaving that flag set makes this wait return while the screen still says "Connecting…".
        endpointAccepted = false
        internal = UnifiedPushState.Registering(selected.packageName)
        publish()
        val record = store().loadOrCreate(selected.packageName)
        val vapid = try {
            api.webPushKey(session).publicKey
        } catch (e: ApiError) {
            val reason = failureToFetchKey(e)
            Log.e(TAG, "registration key ${describe(e)} -> $reason")
            internal = UnifiedPushState.Unavailable(reason)
            publish()
            return
        }
        val extra = UnifiedPushProtocol.vapidExtra(vapid)
        if (extra == null) {
            Log.e(TAG, "registration vapid rejected, length ${vapid.length}")
            internal = UnifiedPushState.Unavailable(NoPushReason.DistributorFailed)
            publish()
            return
        }
        Log.e(TAG, "registration asking ${if (selected.embedded) "play" else "distributor"}")
        broadcast.register(selected.packageName, record.token, extra)
        delay(registrationWaitMs)
        if (forgetting || endpointAccepted || internal !is UnifiedPushState.Registering) return
        Log.e(TAG, "registration timed out")
        internal = UnifiedPushState.Unavailable(NoPushReason.DistributorFailed)
        publish()
    }

    private fun describe(error: ApiError): String = when (error) {
        is ApiError.Server -> "server ${error.status} ${error.code}"
        is ApiError.Transport -> "transport"
        is ApiError.Decoding -> "decoding"
    }

    private fun onNewEndpoint(record: UnifiedPushSubscriptionStore.Record, event: DistributorEvent) {
        val endpoint = event.endpoint
        if (endpoint.isNullOrBlank()) return
        ack(record, event.id)
        if (record.publicKey.size != 65 || record.authSecret.size != 16) {
            internal = UnifiedPushState.Unavailable(NoPushReason.DistributorCannotEncrypt)
            publish()
            return
        }
        val saved = record.copy(endpoint = endpoint)
        runCatching { store().save(saved) }
        endpointAccepted = true
        scope.launch {
            try {
                putSubscription(saved, endpoint)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                if (!forgetting && internal is UnifiedPushState.Registering) {
                    endpointAccepted = false
                    internal = UnifiedPushState.Unavailable(NoPushReason.DistributorFailed)
                    publish()
                }
            }
        }
    }

    private suspend fun putSubscription(record: UnifiedPushSubscriptionStore.Record, endpoint: String) {
        val session = sessionToken() ?: return
        val body = WebPushSubscriptionBody(
            endpoint = endpoint,
            keys = WebPushSubscriptionBody.Keys(P256.b64Url(record.publicKey), P256.b64Url(record.authSecret)),
        )
        try {
            api.putWebPushSubscription(session, body)
            val pkg = record.distributorPackage
            internal = UnifiedPushState.Registered(pkg, label.ifBlank { pkg })
            publish()
        } catch (e: ApiError.Server) {
            if (e.status == 400 && e.code == ErrorCodes.VALIDATION_ERROR) {
                internal = UnifiedPushState.Unavailable(NoPushReason.ServerRefusedHost)
                endpointAccepted = false
                publish()
                runCatching { broadcast.unregister(record.distributorPackage, record.token) }
            } else {
                internal = UnifiedPushState.Unavailable(failureToSaveSubscription(e))
                publish()
            }
        } catch (e: ApiError) {
            internal = UnifiedPushState.Unavailable(failureToSaveSubscription(e))
            publish()
        }
    }

    /** A missing push key is the server. Anything else while asking for it is not the distributor. */
    private fun failureToFetchKey(error: ApiError): NoPushReason = when {
        error.isNotFound -> NoPushReason.ServerHasNoWebPush
        error.serverUnreachable -> NoPushReason.ServerUnreachable
        else -> NoPushReason.DistributorFailed
    }

    /** Saving the endpoint failed after Play answered. A dead server is not a distributor failure. */
    private fun failureToSaveSubscription(error: ApiError): NoPushReason =
        if (error.serverUnreachable) NoPushReason.ServerUnreachable else NoPushReason.DistributorFailed

    private val ApiError.serverUnreachable: Boolean
        get() = this is ApiError.Transport || (this is ApiError.Server && status >= 500)

    private suspend fun onMessage(record: UnifiedPushSubscriptionStore.Record, event: DistributorEvent) {
        ack(record, event.id)
        val bytes = event.bytes ?: return
        val plain = WebPushDecryptor.open(bytes, record.privateKey, record.publicKey, record.authSecret) ?: return
        try {
            dispatcher.dispatchPlaintext(plain, messageText(plain))
        } finally {
            plain.fill(0)
        }
    }

    /**
     * The body to show for this push, or null. A message push names the message and carries no
     * text; this phone opens it when Message Preview is on and the chats are still unlocked.
     * A slow fetch gives up and the notification keeps the generic line. The bytes are not logged.
     */
    private suspend fun messageText(plain: ByteArray): String? {
        val contents = PushContents.fromWebPushJson(plain) ?: return null
        if (contents.kind != NotificationKind.Message) return null
        return try {
            withTimeoutOrNull(PREVIEW_WAIT_MS) { messagePreview(contents) }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
    }

    private fun onRegistrationFailed(record: UnifiedPushSubscriptionStore.Record) {
        if (endpointAccepted) return
        runCatching { store().rotateToken() }
        internal = UnifiedPushState.Unavailable(NoPushReason.DistributorFailed)
        publish()
        record.privateKey.fill(0)
    }

    private fun onUnregistered(useDistributor: String?) {
        if (forgetting) return
        endpointAccepted = false
        if (!useDistributor.isNullOrBlank() && useDistributor != ourPackage &&
            distributors().any { it.packageName == useDistributor }
        ) {
            prefs.distributorChoice = useDistributor
        }
        register()
    }

    private fun onTempUnavailable() {
        val pkg = when (val current = internal) {
            is UnifiedPushState.Registered -> current.distributorPackage
            is UnifiedPushState.Registering -> current.distributorPackage
            else -> return
        }
        internal = UnifiedPushState.Registering(pkg)
        publish()
    }

    private fun ack(record: UnifiedPushSubscriptionStore.Record, id: String?) {
        if (id.isNullOrEmpty() || record.distributorPackage.isEmpty()) return
        runCatching { broadcast.acknowledge(record.distributorPackage, record.token, id) }
    }

    private fun unregisterStored() {
        val record = runCatching { store().load() }.getOrNull() ?: return
        if (record.token.isEmpty() || record.distributorPackage.isEmpty()) return
        broadcast.unregister(record.distributorPackage, record.token)
    }

    private companion object {
        const val TAG = "ShroudPush"
        const val REGISTRATION_WAIT_MS = 25_000L

        /** How long a notification will wait to open the message before showing the generic line. */
        const val PREVIEW_WAIT_MS = 8_000L
    }

    private fun publish() {
        val signedIn = !sessionToken().isNullOrEmpty()
        val up = when {
            !delivering || !signedIn -> UnifiedPushState.Unknown
            !notificationsEnabled() && (internal is UnifiedPushState.Registered || internal is UnifiedPushState.Registering) ->
                UnifiedPushState.Unavailable(NoPushReason.NotificationsOff)
            else -> internal
        }
        deliveryState.value = PushDelivery(
            unifiedPush = up,
            backgroundConnection = delivering && signedIn && background.requested,
            batteryUnrestricted = background.batteryUnrestricted(),
        )
    }
}
