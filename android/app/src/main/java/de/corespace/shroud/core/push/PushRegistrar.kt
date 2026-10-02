package de.corespace.shroud.core.push

import de.corespace.shroud.core.net.ApiError
import de.corespace.shroud.core.net.ErrorCodes
import de.corespace.shroud.core.net.ShroudApi
import de.corespace.shroud.core.net.WebPushSubscriptionBody
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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

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
            } catch (_: Exception) {
            }
        }
    }

    override fun onSystemSettingsMaybeChanged() {
        publish()
        if (delivering && sessionToken() != null && internal !is UnifiedPushState.Registered) register()
    }

    override fun distributors(): List<Distributor> =
        runCatching { directory.distributors() }.getOrDefault(emptyList()).filter { it.packageName != ourPackage }

    override fun chooseDistributor(packageName: String?) {
        if (packageName == ourPackage) return
        if (packageName != null && packageName == prefs.distributorChoice &&
            (internal is UnifiedPushState.Registered || internal is UnifiedPushState.Registering)
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

    fun onDistributorEvent(event: DistributorEvent) {
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
        val selected = when {
            installed.isEmpty() -> {
                internal = UnifiedPushState.Unavailable(NoPushReason.NoDistributorInstalled)
                publish()
                return
            }
            choice == PushSettings.NONE || (choice == null && installed.size > 1) -> {
                internal = UnifiedPushState.Unavailable(NoPushReason.NoneChosen)
                publish()
                return
            }
            choice == null -> installed.single().also { prefs.distributorChoice = it.packageName }
            else -> installed.firstOrNull { it.packageName == choice } ?: run {
                internal = UnifiedPushState.Unavailable(NoPushReason.DistributorFailed)
                publish()
                return
            }
        }
        if (selected.packageName == ourPackage) return
        label = selected.label
        internal = UnifiedPushState.Registering(selected.packageName)
        publish()
        val record = store().loadOrCreate(selected.packageName)
        val vapid = try {
            api.webPushKey(session).publicKey
        } catch (e: ApiError) {
            if (e.isNotFound) {
                internal = UnifiedPushState.Unavailable(NoPushReason.ServerHasNoWebPush)
                publish()
            }
            return
        }
        val extra = UnifiedPushProtocol.vapidExtra(vapid) ?: return
        broadcast.register(selected.packageName, record.token, extra)
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
            }
        }
    }

    private fun onMessage(record: UnifiedPushSubscriptionStore.Record, event: DistributorEvent) {
        ack(record, event.id)
        val bytes = event.bytes ?: return
        val plain = WebPushDecryptor.open(bytes, record.privateKey, record.publicKey, record.authSecret) ?: return
        try {
            dispatcher.dispatchPlaintext(plain)
        } finally {
            plain.fill(0)
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
