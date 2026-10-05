package de.corespace.shroud.ui.shell

import androidx.core.os.UserManagerCompat
import de.corespace.shroud.AppContainer
import de.corespace.shroud.core.auth.Session
import de.corespace.shroud.core.auth.WipeReason
import de.corespace.shroud.core.auth.WipeRouter
import de.corespace.shroud.core.devices.DeviceNoun
import de.corespace.shroud.core.keys.IdentityPresence
import de.corespace.shroud.core.lifecycle.AppPhase
import de.corespace.shroud.core.model.AppClock
import de.corespace.shroud.core.net.ServerConfiguration
import de.corespace.shroud.core.storage.AutoLockDelay
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * [ShellEnvironment] over the process's [AppContainer] (`di/ShellModule`). Every port reaches the
 * owning package's module; nothing here constructs a controller another package owns (00-plan §2.0
 * rule 3). Ports that only stop something touch a controller only when it was built, so a launch or
 * a sign-out never builds messaging just to stop it.
 */
class ContainerShellEnvironment(private val container: AppContainer) : ShellEnvironment {
    private val sessions get() = container.auth.sessionController
    private val crypto get() = container.keys.cryptoController
    private val wipe get() = container.auth.deviceWipe
    private val notifications get() = container.notifications.controller
    private val push get() = container.push.registration
    private var namesFeed: Job? = null

    override val clock: AppClock get() = container.clock
    override val phase: StateFlow<AppPhase> get() = container.appPhase.phase

    override val session: StateFlow<Session?> get() = sessions.session
    override val pendingFullLocalWipe: StateFlow<Boolean> get() = sessions.pendingFullLocalWipe

    override suspend fun validateSession() {
        sessions.validate()
    }

    override fun consumePendingFullLocalWipe(): Boolean = sessions.consumePendingFullLocalWipe()

    override suspend fun endLocalSession() = sessions.logout()

    override val unlockedUserId: StateFlow<String?> get() = crypto.unlockedUserId
    override val vaultPromptInFlight: StateFlow<Boolean> get() = crypto.vaultPromptInFlight
    override val systemPickerInFlight: StateFlow<Boolean> get() = container.appPhase.systemPickerInFlight

    override fun identityPresence(userId: String): IdentityPresence = crypto.identityPresence(userId)

    override fun lockCrypto(wipeStore: Boolean) = crypto.lock(wipeStore = wipeStore)

    override suspend fun lockChatsInMemory() = container.lockChatsInMemory()

    override val wipePresented: StateFlow<Boolean> get() = wipe.isPresented

    override suspend fun finishInterruptedWipeIfNeeded(): Boolean = wipe.finishInterruptedWipeIfNeeded()

    override fun startWipe(reason: WipeReason) = wipe.start(reason)

    override fun startWipeIfSessionEnded(): Boolean = wipe.startIfSessionEnded()

    override fun attachWipeRouter(router: WipeRouter?) {
        if (router == null) {
            if (wipe.router != null) wipe.router = null
        } else {
            wipe.router = router
        }
    }

    override fun startMessaging() = container.messaging.controller.start()

    override suspend fun stopMessaging(wipeDisk: Boolean) {
        container.messaging.controllerIfBuilt?.stop(wipeDisk)
    }

    /** Built once, on first use: the call controller exists from process start (`CallsModule.onProcessStart`). */
    override val callActive: StateFlow<Boolean> by lazy {
        container.calls.controller.ui.map { it.active != null }.distinctUntilChanged()
            .stateIn(container.appScope, SharingStarted.Eagerly, container.calls.controller.ui.value.active != null)
    }

    override val isInCall: Boolean get() = container.calls.controllerIfBuilt?.isInCall == true

    override fun clearCalls() {
        container.calls.controllerIfBuilt?.clearLocalState()
    }

    override fun setNotificationsUnlocked(unlocked: Boolean) {
        notifications.isUnlocked = unlocked
    }

    override fun setNotificationsSignedIn(signedIn: Boolean) {
        notifications.isSignedIn = signedIn
    }

    override fun clearPendingOpen() {
        notifications.pendingOpen.value = null
    }

    override fun dismissBanner() = notifications.dismissBanner()

    override suspend fun refreshNotificationAuthorization() = notifications.refreshAuthorization()

    override fun startPush() = push.start()

    override fun stopPush() = push.stop()

    override fun pushSettingsMaybeChanged() = push.onSystemSettingsMaybeChanged()

    override val autoLockDelay: StateFlow<AutoLockDelay> get() = container.keys.securityPreferences.autoLockDelay
    override val hidesDuringScreenCapture: StateFlow<Boolean> get() = container.keys.securityPreferences.hidesDuringScreenCapture

    override fun isUserUnlocked(): Boolean = UserManagerCompat.isUserUnlocked(container.appContext)

    /**
     * Detached, as iOS does (`RootView.swift:312`): two round trips must not hold up the unlock
     * reaction, or a lock arriving meanwhile would wait for them. `DeviceNameSync` is single-flight
     * and swallows its own errors.
     */
    override fun syncDeviceName() {
        container.appScope.launch { container.auth.syncDeviceName() }
    }

    override fun currentServer(): ServerConfiguration = container.serverConfiguration.configuration.value

    override fun saveServer(configuration: ServerConfiguration) = container.serverConfiguration.save(configuration)

    override fun deviceNoun(): String = DeviceNoun.current(container.appContext)

    /**
     * Names for notifications announced while the chats are locked (W2-NOTIF; the cache writes only
     * while names are on): forwards to core's `NotificationNameCache.follow` (chats first, then
     * requests, then contacts win), which replaced the shell's own `combine` (GAPS #7).
     */
    override fun feedNotificationNames(on: Boolean) {
        namesFeed?.cancel()
        namesFeed = null
        if (!on) return
        val contacts = container.contacts.controller
        val messaging = container.messaging.controller
        namesFeed = container.notifications.nameCache.follow(
            scope = container.appScope,
            contacts = contacts.contacts,
            incomingRequests = contacts.incomingRequests,
            conversations = messaging.conversations,
        )
    }
}
