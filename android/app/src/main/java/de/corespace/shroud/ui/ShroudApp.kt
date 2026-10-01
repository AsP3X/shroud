package de.corespace.shroud.ui

import android.Manifest
import android.annotation.SuppressLint
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import de.corespace.shroud.AppContainer
import de.corespace.shroud.core.auth.Session
import de.corespace.shroud.core.auth.WipeReason
import de.corespace.shroud.core.auth.WipeRouter
import de.corespace.shroud.core.devices.DeviceNoun
import de.corespace.shroud.core.model.Ids
import de.corespace.shroud.core.net.ServerConfiguration
import de.corespace.shroud.ui.components.OverlayHost
import de.corespace.shroud.ui.components.ShroudSheet
import de.corespace.shroud.ui.components.Toast
import de.corespace.shroud.ui.components.ToastHost
import de.corespace.shroud.ui.components.rememberToastState
import de.corespace.shroud.ui.navigation.AppRouter
import de.corespace.shroud.ui.navigation.OnboardingRoute
import de.corespace.shroud.ui.onboarding.LogInScreen
import de.corespace.shroud.ui.onboarding.ServerSettingsContent
import de.corespace.shroud.ui.onboarding.SignUpScreen
import de.corespace.shroud.ui.onboarding.WelcomeScreen
import de.corespace.shroud.ui.permissions.rememberPermissionRequest
import de.corespace.shroud.ui.theme.Motion
import de.corespace.shroud.ui.wipe.DeviceWipeOverlay
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * The interim root (`RootView.swift`), W2-INT. Messaging unlocked → the signed-in placeholder;
 * otherwise the onboarding stack. A session without unlocked keys at launch opens straight on the
 * phrase step. The device wipe overlay covers everything while a wipe runs.
 *
 * The process-wide half of iOS `RootView`'s lifecycle wiring lives in [InterimSession]; this
 * composable owns only what needs the UI: the wipe's [WipeRouter], the overlay, toasts and the
 * one-time notification permission dialog. W3-SHELL replaces both with `RootScreen` and
 * `AppShellController` and deletes this file (00-plan §1.2, §2.6).
 */
// POST_NOTIFICATIONS is a string compared on every API level; below 33 the request helper reports
// the system's notification switch instead (`rememberPermissionRequest`).
@SuppressLint("InlinedApi")
@Composable
fun ShroudApp(container: AppContainer) {
    val glue = remember(container) { InterimSession.of(container) }
    val wipe = container.auth.deviceWipe
    val session by container.auth.sessionController.session.collectAsState()
    val unlocked by glue.isUnlocked.collectAsState()
    val wipePresented by wipe.isPresented.collectAsState()
    val server by container.serverConfiguration.configuration.collectAsState()
    val toast = rememberToastState()
    val router = remember {
        AppRouter(if (container.auth.sessionController.session.value != null) listOf(OnboardingRoute.LogIn) else listOf(OnboardingRoute.Welcome))
    }
    var showServerSettings by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    // `deviceWipe.router = router` (`RootView.swift:143`): the end of a wipe resets the stack to
    // Welcome under the overlay. "A router is attached" also tells the removal wake the UI is alive.
    DisposableEffect(wipe, router) {
        val attached = WipeRouter { router.reset(null) }
        wipe.router = attached
        onDispose { if (wipe.router === attached) wipe.router = null }
    }
    // Launch: a wipe the app was killed in (`RootView.swift:160-162`) says so on Welcome.
    val context = LocalContext.current
    LaunchedEffect(glue) {
        if (glue.awaitLaunchChecks()) router.postAuthToast = "Signed out · this ${DeviceNoun.current(context)} was cleared"
    }
    // Locked again (background) while still signed in: back to the phrase step, whichever screen
    // unlocked. A wipe resets the stack itself.
    var wasUnlocked by remember { mutableStateOf(false) }
    LaunchedEffect(unlocked) {
        if (wasUnlocked && !unlocked && container.auth.sessionController.session.value != null && !wipe.isPresented.value) router.resetToPhrase()
        wasUnlocked = unlocked
    }
    // P6a (decided): the system dialog once, the first time the chats are unlocked with
    // notifications on (`PushNotificationService.swift:61-67`).
    val notifications = container.notifications.controller
    val askNotifications = rememberPermissionRequest(Manifest.permission.POST_NOTIFICATIONS) { _, _ ->
        scope.launch { notifications.refreshAuthorization() }
    }
    LaunchedEffect(unlocked) {
        if (!unlocked) return@LaunchedEffect
        notifications.refreshAuthorization()
        if (notifications.shouldRequestPermissionAfterUnlock()) {
            notifications.markPermissionRequested()
            askNotifications()
        }
    }
    LaunchedEffect(router.postAuthToast) {
        router.postAuthToast?.let {
            toast.show(Toast.info(it, 2400))
            router.postAuthToast = null
        }
    }
    val logOut: () -> Unit = { glue.logOut() }

    // Every menu, sheet and dialog draws in this one layer above the app (plan §1.7.12): toasts
    // stay under them, as on iOS.
    OverlayHost {
        Box(Modifier.fillMaxSize()) {
            val signedIn = session
            if (unlocked && signedIn != null) {
                SignedInPlaceholder(username = signedIn.username, onLogOut = logOut)
            } else {
                BackHandler(enabled = router.canPop && !showServerSettings) { router.pop() }
                AnimatedContent(
                    targetState = router.top,
                    transitionSpec = { pushPop(router.lastWasPush) },
                    label = "onboarding",
                ) { route ->
                    when (route) {
                        OnboardingRoute.Welcome -> WelcomeScreen(
                            server = server,
                            onStartMessaging = router::showSignUp,
                            onLogIn = router::showLogIn,
                            onOpenServerSettings = { showServerSettings = true },
                        )
                        OnboardingRoute.SignUp -> SignUpScreen(container, toast, onBack = router::pop, onLogIn = router::showLogIn)
                        OnboardingRoute.LogIn -> LogInScreen(
                            container,
                            onBack = if (router.canPop) router::pop else null,
                            onSignUp = router::showSignUp,
                            onLogOut = logOut,
                        )
                    }
                }
            }
            ShroudSheet(visible = showServerSettings, onDismiss = { showServerSettings = false }) {
                ServerSettingsContent(
                    initial = server,
                    onSave = { draft ->
                        glue.chooseServer(draft)
                        showServerSettings = false
                    },
                    onCancel = { showServerSettings = false },
                )
            }
            ToastHost(toast)
            // Above everything — the switch to Welcome happens under it (`RootView.swift:106-111`).
            if (wipePresented) DeviceWipeOverlay()
        }
    }
}

/** iOS-style push: the new screen slides in from the end, the old one drifts back a third. */
private fun pushPop(push: Boolean): ContentTransform =
    if (push) {
        (slideInHorizontally(Motion.standard()) { it } + fadeIn(Motion.fade())) togetherWith
            (slideOutHorizontally(Motion.standard()) { -it / 3 } + fadeOut(Motion.fade()))
    } else {
        (slideInHorizontally(Motion.standard()) { -it / 3 } + fadeIn(Motion.fade())) togetherWith
            (slideOutHorizontally(Motion.standard()) { it } + fadeOut(Motion.fade()))
    }

/**
 * The process-wide half of iOS `RootView`'s wiring (`RootView.swift:129-300`), interim until
 * W3-SHELL's `AppShellController`. One per process ([of]); collects on `AppContainer.appScope`, so
 * it keeps working while the activity is stopped (a composition pauses then):
 *
 * - launch ([awaitLaunchChecks]): finish an interrupted wipe, then validate the session;
 * - a session the server ended (`pendingFullLocalWipe`, 401 streak or `DEVICE_REMOVED`) → the wipe;
 * - [isUnlocked] (signed in, chats unlocked for this account, no wipe running) → messaging
 *   `prepareCachedState` + `start`, the device name, the notification flags; locked again →
 *   messaging `stop(wipeDisk = false)` (a wipe stops it itself);
 * - while unlocked, contact and chat names feed the sealed notification name cache (W2-NOTIF), so the
 *   background connection can name a sender while the chats are locked;
 * - a server picked while signed in takes effect once that Log Out's wipe is over (`:194-199`).
 *
 * The interim background lock (`ShroudApplication`) locks messaging's memory before it drops the keys.
 */
class InterimSession private constructor(private val container: AppContainer) {
    private val scope = container.appScope
    private val sessions = container.auth.sessionController
    private val wipe = container.auth.deviceWipe
    private val notifications = container.notifications.controller
    private val launchDone = MutableStateFlow<Boolean?>(null)

    /** A server chosen while signed in: saved once the Log Out wipe it started is over. */
    private var pendingServer: ServerConfiguration? = null
    private var namesFeed: Job? = null

    /** iOS `router.isUnlocked`: signed in, the chats unlocked for this session's account, no wipe running. */
    val isUnlocked: StateFlow<Boolean> = MutableStateFlow(false).also { flow ->
        scope.launch {
            combine(sessions.session, container.keys.cryptoController.unlockedUserId, wipe.isPresented) { session, unlockedId, wiping ->
                !wiping && isOwnAccount(session, unlockedId)
            }.distinctUntilChanged().collect { flow.value = it }
        }
    }

    private fun start() {
        scope.launch {
            val cleared = wipe.finishInterruptedWipeIfNeeded()
            if (sessions.session.value != null) sessions.validate()
            // A tap that launched a signed-out app belongs to no one here (`RootView.swift:175`).
            if (sessions.session.value == null) notifications.pendingOpen.value = null
            launchDone.value = cleared
        }
        scope.launch {
            // `RootView.swift:187-194`: consumed by the wipe so its own logout cannot start a second one.
            sessions.pendingFullLocalWipe.collect { pending -> if (pending) wipe.startIfSessionEnded() }
        }
        scope.launch {
            sessions.session.map { it != null }.distinctUntilChanged().collect { signedIn ->
                notifications.isSignedIn = signedIn
            }
        }
        scope.launch {
            var wasUnlocked = false
            isUnlocked.collect { unlocked ->
                notifications.isUnlocked = unlocked
                if (unlocked) {
                    // Wait for the launch checks: an interrupted wipe finishes before anything reads the session.
                    launchDone.first { it != null }
                    unlocked()
                } else {
                    notifications.dismissBanner()
                    if (wasUnlocked) locked()
                }
                wasUnlocked = unlocked
            }
        }
        scope.launch {
            wipe.isPresented.collect { presented ->
                if (presented) return@collect
                pendingServer?.let { next ->
                    pendingServer = null
                    container.serverConfiguration.save(next)
                }
            }
        }
    }

    /** True once the launch checks ran and an interrupted wipe was finished by them. */
    suspend fun awaitLaunchChecks(): Boolean = launchDone.first { it != null } == true

    /** Log Out: the wipe behind its overlay (`DeviceWipeController.start(.logout)`, settings-lock §14). */
    fun logOut() = wipe.start(WipeReason.Logout)

    /**
     * Server settings saved. A session belongs to its server: another endpoint while signed in
     * logs this phone out first, and the new server is saved only once that wipe is over, so the
     * revoke reaches the old one (`RootView.swift:194-199`).
     */
    fun chooseServer(next: ServerConfiguration) {
        val current = container.serverConfiguration.configuration.value
        if (sessions.session.value != null && next.resolvedBaseUrl != current.resolvedBaseUrl) {
            pendingServer = next
            logOut()
        } else {
            container.serverConfiguration.save(next)
        }
    }

    private suspend fun unlocked() {
        val messaging = container.messaging.controller
        messaging.prepareCachedState()
        if (!isUnlocked.value) return
        messaging.start()
        feedNotificationNames()
        // Detached, as iOS does (`RootView.swift:312`): two network round trips must not hold up
        // this collector, or a lock arriving meanwhile would wait for them (or be conflated away).
        // DeviceNameSync is single-flight and swallows its own errors.
        scope.launch { container.auth.syncDeviceName() }
    }

    private suspend fun locked() {
        namesFeed?.cancel()
        namesFeed = null
        // A wipe halted messaging already and stops it with the disk (its `endLocalSession`).
        if (wipe.isPresented.value) return
        val messaging = container.messaging.controllerIfBuilt ?: return
        // Keep the sealed cache and Notes when only the chats are locked (`RootView.swift:229-236`).
        messaging.stop(wipeDisk = sessions.session.value == null)
    }

    /** Names for notifications announced while the chats are locked (W2-NOTIF; written only while names are on). */
    private fun feedNotificationNames() {
        namesFeed?.cancel()
        val contacts = container.contacts.controller
        val messaging = container.messaging.controller
        namesFeed = scope.launch {
            combine(contacts.contacts, contacts.incomingRequests, messaging.conversations) { roster, requests, chats ->
                buildMap<UUID, String> {
                    chats.forEach { put(it.peer.id, it.peer.username) }
                    requests.forEach { request -> request.user?.let { put(request.fromUserId, it.username) } }
                    roster.forEach { put(it.userId, it.username) }
                }
            }.distinctUntilChanged().collect { names ->
                if (names.isNotEmpty()) container.notifications.nameCache.rememberAll(names)
            }
        }
    }

    companion object {
        @Volatile private var instance: InterimSession? = null

        /** The process's one instance, started on first use (main thread). */
        fun of(container: AppContainer): InterimSession =
            instance ?: synchronized(this) {
                instance ?: InterimSession(container).also {
                    instance = it
                    it.start()
                }
            }

        private fun isOwnAccount(session: Session?, unlockedUserId: String?): Boolean {
            if (session == null || unlockedUserId == null) return false
            return Ids.parse(session.userId) == Ids.parse(unlockedUserId)
        }
    }
}
