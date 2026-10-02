package de.corespace.shroud.ui.shell

import android.os.Build
import de.corespace.shroud.core.auth.WipeReason
import de.corespace.shroud.core.lifecycle.AppPhase
import de.corespace.shroud.core.net.ServerConfiguration
import de.corespace.shroud.core.storage.AutoLockDelay
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The process-wide half of iOS `RootView` (`RootView.swift:128-441`; shell-chats §3.3–3.11,
 * settings-lock §11.5, §12, notifications-push §5.19): the launch sequence, the session probe while
 * locked, the reactions to the session / unlock / wipe / call state, the app lifecycle with the
 * auto-lock, and the cover state the root draws. It replaces W2's interim `InterimSession` and
 * `ShroudApplication`'s unconditional `ON_STOP` lock (shell-chats §3.7).
 *
 * One per process (`di/ShellModule`), started from `AppContainer.onProcessStart`; collects on the
 * app scope (main), so it keeps working while the activity is stopped. The scene-phase socket work
 * (`handleAppBecameActive`, `stepAway`) is `AppForegroundCoordinator`'s (W1-RT) on the same
 * [AppPhase], so it is not repeated here.
 *
 * @param sdk the platform level the window protection is decided for (tests pass 30, 33, 35).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AppShellController(
    private val env: ShellEnvironment,
    private val scope: CoroutineScope,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    sdk: Int = Build.VERSION.SDK_INT,
) {
    /** The root's router (onboarding stack, unlock state, Log Out). */
    val router = AppRouter(env, scope, io)

    private val armed = MutableStateFlow(false)
    private val screenCaptured = MutableStateFlow(false)
    private val locking = MutableStateFlow(false)
    private val needsUnlock = MutableStateFlow<Boolean?>(null)
    private val launched = MutableStateFlow(false)
    private val lockMutex = Mutex()

    /** Background start on the monotonic clock while a delayed auto-lock is pending (`leftForBackgroundAt`, `RootView.swift:49-50`). */
    private var leftForBackgroundAt: Long? = null
    private var autoLockJob: Job? = null
    private var started = false

    /** The scene left `.active` with the chats showing (`privacyCoverArmed`, `RootView.swift:45-46, 257`). */
    val privacyCoverArmed: StateFlow<Boolean> = armed.asStateFlow()

    /**
     * Signed in with a local identity while the shell is not shown → the lock screen instead of
     * Welcome (`needsChatUnlock`, `RootView.swift:391-399`). Deliberately not keyed on the vault (that
     * flashed Welcome during the unlock hand-over). Null until first read: the identity record is read
     * off the main thread, and the root draws its background meanwhile rather than flash Welcome.
     */
    val needsChatUnlock: StateFlow<Boolean?> = needsUnlock.asStateFlow()

    /** The launch sequence finished. */
    val launchCompleted: StateFlow<Boolean> = launched.asStateFlow()

    /** Recorded, cast or mirrored, unlocked, and "Hide chats during screen recording" on (`coversForScreenCapture`, `:360-367`). */
    val coversForScreenCapture: StateFlow<Boolean> =
        combine(router.isUnlockedFlow, screenCaptured, env.hidesDuringScreenCapture) { unlocked, captured, hides -> unlocked && captured && hides }
            .stateIn(scope, SharingStarted.Eagerly, false)

    /**
     * The privacy cover (`showsPrivacyCover`, `RootView.swift:347-358`): over the chats while they are
     * captured, or after the app left the front with them showing — never over the lock screen. Also
     * while an auto-lock is still emptying memory on the way back in, so the chats never flash before
     * the lock screen (Android's lock is asynchronous, iOS's is not).
     */
    val showsPrivacyCover: StateFlow<Boolean> =
        combine(router.isUnlockedFlow, coversForScreenCapture, armed, env.phase, locking) { unlocked, capture, isArmed, phase, isLocking ->
            unlocked && (capture || (isArmed && (phase != AppPhase.Active || isLocking)))
        }.stateIn(scope, SharingStarted.Eagerly, false)

    /**
     * The call screen goes over the capture cover — sharing your screen in a call must show the
     * call — but the app-switcher cover still goes over a call (`callAboveCaptureCover`, `:369-376`).
     */
    val callAboveCaptureCover: StateFlow<Boolean> =
        combine(coversForScreenCapture, armed, env.phase) { capture, isArmed, phase -> capture && !(isArmed && phase != AppPhase.Active) }
            .stateIn(scope, SharingStarted.Eagerly, false)

    /** What the window does for Recents and captures (P5; [WindowProtection]). */
    val windowProtection: StateFlow<WindowProtection> =
        combine(router.isUnlockedFlow, env.hidesDuringScreenCapture) { unlocked, hides -> WindowProtection.decide(sdk, unlocked, hides) }
            .stateIn(scope, SharingStarted.Eagerly, WindowProtection.None)

    /** A call screen is up. */
    val callActive: StateFlow<Boolean> get() = env.callActive

    /** The wipe overlay is up. */
    val wipePresented: StateFlow<Boolean> get() = env.wipePresented

    /** The app-wide actions screens trigger ([LocalAppActions]). */
    val actions: AppActions = object : AppActions {
        override fun logOut() = router.logOut()
        override fun logOut(switchingTo: ServerConfiguration) = router.logOut(switchingTo)
        override fun lockChatsNow() = this@AppShellController.lockChatsNow()
        override val isLoggingOut: StateFlow<Boolean> get() = env.wipePresented
    }

    /** Starts everything once per process (`AppContainer.onProcessStart` → `ShellModule`). */
    fun start() {
        if (started) return
        started = true
        observeSession()
        observeUnlock()
        observeWipe()
        observeCalls()
        observePhase()
        observeIdentity()
        observeSignUpAndLogInUnlocks()
        runSessionProbe()
        scope.launch { runLaunchSequence() }
    }

    // ---- Launch (RootView.swift:128-180; shell-chats §3.3) ----

    /**
     * Steps 1–4 happen elsewhere on Android: `SensitiveTempFiles.prepareAtLaunch()` runs in
     * `KeysModule.onProcessStart`, the auth bridge and the controller bindings are `AppContainer`
     * wiring. The rest, in iOS's order.
     */
    private suspend fun runLaunchSequence() {
        if (env.finishInterruptedWipeIfNeeded()) router.postAuthToast = interruptedWipeToast(env.deviceNoun())
        if (env.session.value != null) env.validateSession()
        if (env.session.value != null) {
            if (!router.reconcileOrphanedSessionIfNeeded()) router.restoreUnlockedSessionIfNeeded()
        } else {
            router.hasUnlockedMessaging = false
        }
        val signedIn = env.session.value != null
        env.setNotificationsUnlocked(router.isUnlocked)
        env.setNotificationsSignedIn(signedIn)
        // A tap that launched a signed-out app belongs to no one here (`:169-170`).
        if (!signedIn) env.clearPendingOpen()
        // Pushes, a call included, must reach a signed-in phone that is still locked (`:171-175`).
        if (signedIn) env.startPush()
        if (router.isUnlocked) {
            env.startMessaging()
            env.syncDeviceName()
            env.feedNotificationNames(true)
        }
        launched.value = true
    }

    // ---- Session probe while locked (RootView.swift:181-186, 381-440; shell-chats §3.4) ----

    /**
     * Signed in but the shell not shown: messaging's polls are stopped, so `/auth/me` is probed
     * every 4 s — three 401s force the sign-out within ~12 s, offline never counts (`:386-389`).
     * Cancelled and restarted when the condition flips, like `.task(id:)`. Starts once the launch
     * sequence is through, so an interrupted wipe is finished before anything reads the session
     * (`:151-152`).
     */
    private fun runSessionProbe() {
        scope.launch {
            combine(env.session, router.isUnlockedFlow, launched) { session, unlocked, ready -> ready && session != null && !unlocked }
                .distinctUntilChanged()
                .collectLatest { active ->
                    if (!active) return@collectLatest
                    while (true) {
                        if (env.session.value == null || router.isUnlocked) return@collectLatest
                        env.validateSession()
                        if (env.session.value == null) return@collectLatest
                        delay(SESSION_PROBE_INTERVAL_MS)
                    }
                }
        }
    }

    // ---- Reactions (RootView.swift:187-255; shell-chats §3.5) ----

    private fun observeSession() {
        scope.launch {
            env.pendingFullLocalWipe.collect { pending ->
                // Consumed by the wipe first: its own logout would otherwise start a second wipe (`:187-194`).
                if (pending && !env.wipePresented.value) env.startWipeIfSessionEnded()
            }
        }
        scope.launch {
            var signedIn = env.session.value != null
            env.session.map { it != null }.distinctUntilChanged().collect { now ->
                if (now == signedIn) return@collect
                signedIn = now
                if (now) env.setNotificationsSignedIn(true) else onSignedOut()
            }
        }
    }

    /** `onChange(of: sessionController.isSignedIn)` to false (`RootView.swift:202-221`). */
    private suspend fun onSignedOut() {
        env.setNotificationsSignedIn(false)
        router.hasUnlockedMessaging = false
        if (env.pendingFullLocalWipe.value) {
            // The server ended the session: clear the phone exactly like Log Out (`:205-214`).
            if (!env.startWipeIfSessionEnded()) env.consumePendingFullLocalWipe()
            return
        }
        // A wipe in progress tears down messaging, keys, calls and push itself, in its own order.
        if (env.wipePresented.value) return
        env.lockCrypto(wipeStore = false)
        env.stopMessaging(wipeDisk = true)
        env.clearCalls()
        env.stopPush()
    }

    private fun observeUnlock() {
        scope.launch {
            var unlocked = router.isUnlockedFlow.value
            router.isUnlockedFlow.collect { now ->
                if (now == unlocked) return@collect
                unlocked = now
                onUnlockChanged(now)
            }
        }
    }

    /**
     * `onChange(of: router.isUnlocked)` (`RootView.swift:222-239`). Deviations: a lock while signed in
     * does not call `push.stop()` — iOS's is a no-op (`PushNotificationService.swift:84-86`) and
     * Android's stops delivery, which a locked phone still needs (`PushRegistration.stop`: "signed out
     * or the session ended"); a wipe in progress stops messaging itself (it halted it first).
     */
    private suspend fun onUnlockChanged(unlocked: Boolean) {
        env.setNotificationsUnlocked(unlocked)
        if (unlocked) {
            if (env.wipePresented.value) return
            router.showWelcome()
            env.startMessaging()
            env.startPush()
            env.syncDeviceName()
            env.feedNotificationNames(true)
            return
        }
        env.dismissBanner()
        env.feedNotificationNames(false)
        val signedIn = env.session.value != null
        if (!env.wipePresented.value) {
            // Keep the sealed cache and Notes when only the chats are locked (`:231-234`).
            env.stopMessaging(wipeDisk = !signedIn)
        }
        if (!signedIn) env.stopPush()
    }

    private fun observeWipe() {
        scope.launch {
            env.wipePresented.collect { presented ->
                if (presented) return@collect
                // A server picked while signed in takes effect once its Log Out is over (`:195-201`).
                val next = router.pendingServerConfiguration ?: return@collect
                router.pendingServerConfiguration = null
                env.saveServer(next)
            }
        }
    }

    private fun observeCalls() {
        scope.launch {
            var active = env.callActive.value
            env.callActive.collect { now ->
                if (now == active) return@collect
                active = now
                // A call that ended: the lock screen waited for it (`:246-252`). In the background the
                // socket steps away through `AppForegroundCoordinator.onCallEnded` (CallsModule wires it).
                if (!now && env.phase.value == AppPhase.Active && !router.isCryptoUnlockedForSession) {
                    router.hasUnlockedMessaging = false
                }
            }
        }
    }

    // ---- App lifecycle (RootView.swift:256-304; shell-chats §3.6) ----

    private fun observePhase() {
        scope.launch {
            var previous: AppPhase? = null
            env.phase.collect { phase ->
                val before = previous
                previous = phase
                // The phase a process starts in is not a departure (AppForegroundCoordinator does the same).
                if (before == null) return@collect
                armed.value = phase != AppPhase.Active && router.isUnlocked
                if (phase != AppPhase.Background) {
                    autoLockJob?.cancel()
                    autoLockJob = null
                }
                when (phase) {
                    AppPhase.Background -> onBackground()
                    AppPhase.Active -> onActive()
                    // Back in goes background → inactive → active: locking here happens while the
                    // privacy cover still hides the chats (`:297-300`).
                    AppPhase.Inactive -> lockIfAutoLockDue()
                }
            }
        }
    }

    /** `.background` (`RootView.swift:259-276`). The "away" frame is `AppForegroundCoordinator`'s. */
    private fun onBackground() {
        env.dismissBanner()
        if (!router.isUnlocked && env.unlockedUserId.value == null) return
        autoLockJob = scope.launch {
            // The vault's own system prompt can stop our activity on some skins (Samsung One UI):
            // locking under it would undo the unlock in progress (crypto §10.7, settings-lock §11.5).
            if (env.vaultPromptInFlight.value) {
                env.vaultPromptInFlight.first { !it }
                val returned = withTimeoutOrNull(PROMPT_RETURN_GRACE_MS) { env.phase.first { it != AppPhase.Background } }
                if (returned != null) return@launch
                if (!router.isUnlocked && env.unlockedUserId.value == null) return@launch
            }
            when (val autoLock = env.autoLockDelay.value) {
                AutoLockDelay.Immediately -> lockChatsInMemory()
                AutoLockDelay.Never -> leftForBackgroundAt = null
                else -> {
                    leftForBackgroundAt = env.clock.elapsedMillis()
                    // Android keeps a stopped process running: without this the keys would sit in
                    // memory for the whole delay. The check on return stays the source of truth (D12).
                    delay(autoLock.seconds * 1_000L)
                    if (env.phase.value == AppPhase.Background) lockIfAutoLockDue()
                }
            }
        }
    }

    /** `.active` (`RootView.swift:277-296`). `handleAppBecameActive` is `AppForegroundCoordinator`'s. */
    private suspend fun onActive() {
        lockIfAutoLockDue()
        scope.launch { env.refreshNotificationAuthorization() }
        env.pushSettingsMaybeChanged()
        // A wipe (one a removal push started in the background) owns the stores: nothing may reconnect them.
        if (env.session.value == null || env.wipePresented.value) return
        // Never an automatic biometric prompt here (it raced with Welcome and left the sheet stuck).
        if (!router.isCryptoUnlockedForSession && !env.isInCall) {
            // A call answered on the lock screen keeps it: dropping into the chat lock then tore the call down.
            router.hasUnlockedMessaging = false
            // Immediate probe when returning to the lock screen (`:291-292`).
            scope.launch { env.validateSession() }
        }
    }

    /** Back from the background: locks when the chosen delay has passed (`lockIfAutoLockDue`, `:322-330`). */
    private suspend fun lockIfAutoLockDue() {
        val leftAt = leftForBackgroundAt ?: return
        leftForBackgroundAt = null
        if (env.autoLockDelay.value.isDue(leftAt, env.clock.elapsedMillis())) lockChatsInMemory()
    }

    /**
     * `lockChatsInMemory` (`RootView.swift:315-320`) — `AppContainer.lockChatsInMemory()`: messaging
     * memory, the keys, the ten-minute temp-file sweep. Never cancelled half-way.
     */
    private suspend fun lockChatsInMemory() {
        leftForBackgroundAt = null
        lockMutex.withLock {
            locking.value = true
            try {
                withContext(NonCancellable) { env.lockChatsInMemory() }
            } finally {
                locking.value = false
            }
        }
    }

    /**
     * Settings › Privacy and Security › Lock chats now (`lockChatsNow`, `PrivacySecurityView.swift:627-634`;
     * settings-lock §7.10): messaging's memory and the keys go (plus the stale temp-file sweep of
     * [lockChatsInMemory]), then the lock screen takes over. Messaging stops with `wipeDisk = false`
     * through the unlock reaction, as iOS's `messaging.stop(wipeDisk: false)`. The haptic and the
     * "Chats locked" toast are the Privacy screen's (W3-SETTINGS-B), as on iOS.
     */
    fun lockChatsNow() {
        scope.launch {
            lockChatsInMemory()
            router.hasUnlockedMessaging = false
        }
    }

    // ---- Routing helpers ----

    /**
     * Recomputes [needsChatUnlock] whenever the session, the keys or the wipe change: sign-up and
     * log-in store the identity just before they unlock, a wipe deletes it.
     */
    private fun observeIdentity() {
        scope.launch {
            combine(env.session, env.unlockedUserId, env.wipePresented) { session, _, _ -> session }
                .collectLatest { session ->
                    needsUnlock.value = if (session == null) false else withContext(io) { env.hasLocalIdentity(session.userId) }
                }
        }
    }

    /**
     * Sign Up and Log In call `router.unlockMessages()` on iOS as soon as their keys are in memory
     * (`SignUpView`, `LogInFlowView`). Android's onboarding screens (W3-LOCK-ONBOARD) take no router,
     * so the shell does it: keys for this session arrive while an onboarding screen is pushed. The
     * lock screen itself (empty path) reveals with its own choreography.
     */
    private fun observeSignUpAndLogInUnlocks() {
        scope.launch {
            env.unlockedUserId.collect {
                if (router.path.isNotEmpty() && router.isCryptoUnlockedForSession && !router.hasUnlockedMessaging) router.unlockMessages()
            }
        }
    }

    /** The root's composition is alive: the wipe resets the router through it (`deviceWipe.router`, `RootView.swift:143`). */
    fun attachUi(attached: Boolean) {
        env.attachWipeRouter(if (attached) router else null)
    }

    /** [ScreenCaptureMonitor.captured]. */
    fun setScreenCaptured(captured: Boolean) {
        screenCaptured.value = captured
    }

    /**
     * Server settings saved from Welcome or the lock screen (`ServerSettingsSheet`): a session belongs
     * to its server, so another endpoint while signed in logs out first and the new server is saved
     * once that wipe is over (`AppRouter.swift:177-183`, `RootView.swift:195-201`).
     */
    fun chooseServer(next: ServerConfiguration) {
        val current = env.currentServer()
        if (env.session.value != null && next.resolvedBaseUrl != current.resolvedBaseUrl) {
            router.logOut(switchingTo = next)
        } else {
            env.saveServer(next)
        }
    }

    companion object {
        /** `lockScreenSessionProbeInterval` (`RootView.swift:387-389`). */
        const val SESSION_PROBE_INTERVAL_MS = 4_000L

        /** After the vault prompt ends, how long the activity gets to come back before a background lock proceeds. */
        const val PROMPT_RETURN_GRACE_MS = 1_000L

        /** `RootView.swift:154` ("this \(UIDevice.current.model)"; Platform Notes: "this phone"). */
        fun interruptedWipeToast(noun: String): String = "Signed out · this $noun was cleared"
    }
}
