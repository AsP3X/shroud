package de.corespace.shroud.ui.shell

import android.Manifest
import android.annotation.SuppressLint
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.Crossfade
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.zIndex
import de.corespace.shroud.core.update.UpdateLinkOpener
import de.corespace.shroud.core.update.UpdatePrompt
import de.corespace.shroud.ui.LocalAppContainer
import de.corespace.shroud.ui.calls.InCallOverlay
import de.corespace.shroud.ui.calls.callCoversApp
import de.corespace.shroud.ui.components.OverlayHost
import de.corespace.shroud.ui.components.ShroudSheet
import de.corespace.shroud.ui.components.Toast
import de.corespace.shroud.ui.components.ToastHost
import de.corespace.shroud.ui.components.focusBlocked
import de.corespace.shroud.ui.components.rememberToastState
import de.corespace.shroud.ui.lock.LockScreen
import de.corespace.shroud.ui.onboarding.LogInScreen
import de.corespace.shroud.ui.onboarding.ProvideOnboardingHero
import de.corespace.shroud.ui.onboarding.ServerSettingsContent
import de.corespace.shroud.ui.onboarding.SignUpScreen
import de.corespace.shroud.ui.onboarding.WelcomeScreen
import de.corespace.shroud.ui.permissions.rememberPermissionRequest
import de.corespace.shroud.ui.theme.Motion
import de.corespace.shroud.ui.theme.ShroudTheme
import de.corespace.shroud.ui.theme.perform
import de.corespace.shroud.ui.update.UpdatePromptLayer
import de.corespace.shroud.ui.wipe.DeviceWipeOverlay
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch

/** The z-order of the root's layers (`RootView.swift:53-112`; shell-chats §3.1). */
object RootLayers {
    const val MAIN_SHELL = 0f
    const val ONBOARDING = 1f
    const val BANNER = 90f

    /** The update prompts: the blocking "Update required" screen sits under calls, covers and wipes. */
    const val UPDATE = 95f
    const val CALL = 100f
    const val PRIVACY_COVER = 150f

    /** The call over the screen-capture cover (`callAboveCaptureCover`, `RootView.swift:94`). */
    const val CALL_ABOVE_CAPTURE_COVER = 160f
    const val DEVICE_WIPE = 200f
}

/**
 * The app's root, iOS `RootView.body` (`RootView.swift:53-127`; shell-chats §3.1–3.2, §3.10–3.15):
 * one box whose layers are, bottom to top,
 *
 * | z | Layer | Shown |
 * | --- | --- | --- |
 * | 0 | [MainShell] | while [AppRouter.mountsMainShell]; touchable only while unlocked |
 * | 1 | onboarding (Welcome or the lock screen, Sign Up / Log In pushed above) | while locked |
 * | 90 | [InAppNotificationHost] | while unlocked |
 * | 95 | [UpdatePromptLayer] ("Update required"; the "Update available" dialog is an overlay) | while the server asks for it |
 * | 100 / 160 | `InCallOverlay` (calls area; draws nothing without a call) | always |
 * | 150 | [PrivacyCover] | [AppShellController.showsPrivacyCover] |
 * | 200 | `DeviceWipeOverlay` | while a wipe runs |
 *
 * The shell is out of TalkBack's reach while locked, under the capture cover, under the full call
 * screen and under "Update required"; the lock screen while the call screen or "Update required"
 * is up (a call answered on a locked phone covers it). Under "Update required" focus cannot enter
 * the shell, onboarding or the banner either ([focusBlocked]), and its overlays close or hide.
 * A call minimised to its pill covers nothing ([callCoversApp]). `InCallOverlay` is always composed:
 * it also hosts the call permission prompt, with or without a call. Unlocking fades the
 * shell in ([Motion.gentle]) while the onboarding layer fades out over it; the shell is not scaled
 * here — its tab content rises from 0.96 inside [MainShell], the tab bar stays put (`:55-58, 71`).
 * Locking hides the shell at once (iOS fades it): on Android a lock usually lands while the app is
 * stopped and its fade would then play on return, showing the chats for a moment.
 *
 * Also here, because they need the composition: the wipe's router attachment (`deviceWipe.router`,
 * `:143`), the notification haptics, the one-time notification permission dialog (P6a), and the
 * Welcome toasts ("Signed out · this phone was cleared", the orphan reconcile).
 */
@Composable
fun RootScreen(shell: AppShellController) {
    val container = LocalAppContainer.current
    val router = shell.router
    val notifications = container.notifications.controller
    val unlocked by router.isUnlockedFlow.collectAsState()
    val mountsShell by router.mountsMainShell.collectAsState()
    val callActive by shell.callActive.collectAsState()
    // The full call screen covers the app: false while the call is minimised to its pill (C14), so
    // TalkBack reaches the app again after Back; `callActive` alone stays true for the whole call.
    val callCovers = callCoversApp(callActive)
    val coversCapture by shell.coversForScreenCapture.collectAsState()
    val showsCover by shell.showsPrivacyCover.collectAsState()
    val callAboveCover by shell.callAboveCaptureCover.collectAsState()
    val wipePresented by shell.wipePresented.collectAsState()
    val banner by notifications.banner.collectAsState()
    val updates = container.update.checker
    val updatePrompt by updates.prompt.collectAsState()
    val checkingUpdate by updates.isChecking.collectAsState()
    val updateRequired = updatePrompt is UpdatePrompt.Required
    val reduce = ShroudTheme.reduceMotion
    val view = LocalView.current
    val context = LocalContext.current

    // `deviceWipe.router = router` (`RootView.swift:143`): the end of a wipe resets the onboarding
    // stack under the overlay; "a router is attached" also tells the removal wake the UI is alive.
    DisposableEffect(shell) {
        shell.attachUi(true)
        onDispose { shell.attachUi(false) }
    }
    // Vibrate on arrival works with banners off (notifications-push §5.12.2): the controller asks, the root's view plays it.
    LaunchedEffect(notifications, view) { notifications.haptics.collect { view.perform(it) } }
    NotificationPermissionOnFirstUnlock(unlocked)

    CompositionLocalProvider(LocalAppActions provides shell.actions) {
        // Every menu, sheet and dialog draws in this one layer above the app (plan §1.7.12).
        OverlayHost {
            Box(Modifier.fillMaxSize().background(ShroudTheme.colors.background)) {
                if (mountsShell) {
                    MainShellLayer(
                        router = router,
                        unlocked = unlocked,
                        hiddenFromAccessibility = !unlocked || coversCapture || callCovers || updateRequired,
                        focusBlocked = updateRequired,
                        reduceMotion = reduce,
                    )
                }
                AnimatedVisibility(
                    visible = !unlocked,
                    enter = fadeIn(Motion.respecting(reduce, Motion.gentle())),
                    exit = fadeOut(Motion.respecting(reduce, Motion.gentle())),
                    modifier = Modifier.zIndex(RootLayers.ONBOARDING).hiddenFromAccessibility(callCovers || updateRequired).focusBlocked(updateRequired),
                ) {
                    OnboardingStack(shell)
                }
                if (unlocked) {
                    InAppNotificationHost(
                        banner = banner,
                        onOpen = notifications::openBanner,
                        onDismiss = { notifications.dismissBanner(it.id) },
                        modifier = Modifier.zIndex(RootLayers.BANNER).hiddenFromAccessibility(coversCapture || callCovers || updateRequired).focusBlocked(updateRequired),
                    )
                }
                UpdatePromptLayer(
                    prompt = updatePrompt,
                    checking = checkingUpdate,
                    // The offer waits until no call screen, privacy cover or wipe is up.
                    offersDialog = !callCovers && !showsCover && !wipePresented,
                    onUpdate = { UpdateLinkOpener.open(context, it) },
                    onLater = updates::dismissAvailable,
                    onCheckAgain = updates::checkAgain,
                    modifier = Modifier.zIndex(RootLayers.UPDATE).hiddenFromAccessibility(coversCapture || callCovers),
                    // A call's screen above keeps its own menus.
                    blocksOverlays = !callCovers,
                )
                InCallOverlay(Modifier.zIndex(if (callAboveCover) RootLayers.CALL_ABOVE_CAPTURE_COVER else RootLayers.CALL))
                if (showsCover) {
                    // Under a call's screen TalkBack stays on the call (`:102`).
                    PrivacyCover(Modifier.zIndex(RootLayers.PRIVACY_COVER).hiddenFromAccessibility(callAboveCover && callCovers))
                }
                // Above everything, calls included — the switch to Welcome happens under it (`:106-111`).
                AnimatedVisibility(
                    visible = wipePresented,
                    enter = fadeIn(Motion.fade()),
                    exit = fadeOut(Motion.fade()),
                    modifier = Modifier.zIndex(RootLayers.DEVICE_WIPE),
                ) {
                    DeviceWipeOverlay()
                }
            }
        }
    }
}

/**
 * The main shell, built hidden for the lock screen's reveal (prewarm) or shown (`RootView.swift:59-73`).
 * Fades in when the chats unlock; while hidden it takes no touches and TalkBack cannot reach it.
 */
@Composable
private fun MainShellLayer(router: AppRouter, unlocked: Boolean, hiddenFromAccessibility: Boolean, focusBlocked: Boolean, reduceMotion: Boolean) {
    val alpha = remember { Animatable(if (unlocked) 1f else 0f) }
    LaunchedEffect(unlocked) {
        if (unlocked) alpha.animateTo(1f, Motion.respecting(reduceMotion, Motion.gentle())) else alpha.snapTo(0f)
    }
    Box(
        Modifier
            .fillMaxSize()
            .zIndex(RootLayers.MAIN_SHELL)
            .graphicsLayer { this.alpha = alpha.value }
            .then(if (unlocked) Modifier else Modifier.consumeAllPointers())
            .hiddenFromAccessibility(hiddenFromAccessibility)
            .focusBlocked(focusBlocked),
    ) {
        MainShell(router = router, isRevealed = unlocked)
    }
}

/**
 * The signed-out / locked stack (`onboardingStack`, `RootView.swift:401-423`): Welcome, or the lock
 * screen when this account's keys are stored here ([AppShellController.needsChatUnlock]), cross-fading
 * between them ([Motion.fade]); Sign Up and Log In zoom out of the root's brand mark and replace each
 * other with the push slide ([OnboardingRouteHost]). Each route keeps its saveable state while it is
 * under another (Welcome's arrival plays once).
 */
@Composable
private fun OnboardingStack(shell: AppShellController) {
    val container = LocalAppContainer.current
    val router = shell.router
    val needsUnlock by shell.needsChatUnlock.collectAsState()
    val server by container.serverConfiguration.configuration.collectAsState()
    val reduce = ShroudTheme.reduceMotion
    val toast = rememberToastState()
    val saveable = rememberSaveableStateHolder()
    val guard = LocalWindowProtectionGuard.current
    var showsServerSettings by remember { mutableStateOf(false) }

    // Disposed after the screens inside (Compose forgets in reverse order): the phrase screens'
    // Recents helper writes the window flags directly, so the shell's hold is put back after it.
    DisposableEffect(guard) {
        onDispose { guard?.reapply() }
    }
    // The toasts that land on Welcome (`postAuthToast`, `RootView.swift:153-155`; settings-lock §12).
    // The lock screen shows its own (W3-LOCK-ONBOARD).
    LaunchedEffect(router, needsUnlock) {
        if (needsUnlock != false) return@LaunchedEffect
        snapshotFlow { router.postAuthToast }.filterNotNull().collect {
            toast.show(Toast.info(it, POST_AUTH_TOAST_MS))
            router.postAuthToast = null
        }
    }
    BackHandler(enabled = router.canPop && !showsServerSettings) { router.pop() }

    Box(Modifier.fillMaxSize()) {
        OnboardingRouteHost(top = router.top, lastWasPush = { router.lastWasPush }, reduceMotion = reduce) { route ->
            saveable.SaveableStateProvider(route?.name ?: ROOT_ROUTE_KEY) {
                when (route) {
                    null -> Crossfade(needsUnlock, animationSpec = Motion.respecting(reduce, Motion.fade()), label = "onboardingRoot") { lock ->
                        when (lock) {
                            true -> LockScreen(router)
                            false -> WelcomeScreen(
                                server = server,
                                onStartMessaging = router::showSignUp,
                                onLogIn = router::showLogIn,
                                onOpenServerSettings = { showsServerSettings = true },
                            )
                            // The identity record is still being read: the background, not a flash of Welcome.
                            null -> Box(Modifier.fillMaxSize())
                        }
                    }
                    OnboardingRoute.Welcome -> WelcomeScreen(
                        server = server,
                        onStartMessaging = router::showSignUp,
                        onLogIn = router::showLogIn,
                        onOpenServerSettings = { showsServerSettings = true },
                    )
                    OnboardingRoute.SignUp -> SignUpScreen(container, toast, onBack = router::pop, onLogIn = router::showLogIn)
                    OnboardingRoute.LogIn -> LogInScreen(container, onBack = router::pop, onSignUp = router::showSignUp, onLogOut = router::logOut)
                }
            }
        }
        ShroudSheet(visible = showsServerSettings, onDismiss = { showsServerSettings = false }) {
            ServerSettingsContent(
                initial = server,
                onSave = { draft ->
                    shell.chooseServer(draft)
                    showsServerSettings = false
                },
                onCancel = { showsServerSettings = false },
            )
        }
        ToastHost(toast)
    }
}

/**
 * P6a (decided 2026-10-01): the system notification dialog once, the first time the chats are
 * unlocked with notifications on (`PushNotificationService.swift:61-67`; notifications-push N7).
 */
// POST_NOTIFICATIONS is a plain string on every API level; below 33 the helper reports the
// system's notification switch instead (`rememberPermissionRequest`).
@SuppressLint("InlinedApi")
@Composable
private fun NotificationPermissionOnFirstUnlock(unlocked: Boolean) {
    val notifications = LocalAppContainer.current.notifications.controller
    val scope = rememberCoroutineScope()
    val ask = rememberPermissionRequest(Manifest.permission.POST_NOTIFICATIONS) { _, _ ->
        scope.launch { notifications.refreshAuthorization() }
    }
    LaunchedEffect(unlocked) {
        if (!unlocked) return@LaunchedEffect
        notifications.refreshAuthorization()
        if (notifications.shouldRequestPermissionAfterUnlock()) {
            notifications.markPermissionRequested()
            ask()
        }
    }
}

/**
 * The onboarding stack's animated host, iOS `RootView`'s `NavigationStack` with its
 * `onboardingNamespace` (`RootView.swift:51, 120, 401-423`; `OnboardingHeroTransition.swift`): one
 * [SharedTransitionLayout] around the routes' [AnimatedContent], and each route's [content] inside
 * [ProvideOnboardingHero], so Welcome's and the lock screen's brand mark (`onboardingHeroSource`)
 * and the Sign Up / Log In screen (`onboardingHeroDestination`) zoom into each other (W8, H.2
 * option A). The content transition itself is [OnboardingTransition.between]'s: a plain fade under
 * the zoom, the push slide between Sign Up and Log In; under Reduce Motion the hero modifiers do
 * nothing and every change is the reduced fade.
 */
@Composable
internal fun OnboardingRouteHost(
    top: OnboardingRoute?,
    lastWasPush: () -> Boolean,
    reduceMotion: Boolean,
    modifier: Modifier = Modifier,
    content: @Composable (OnboardingRoute?) -> Unit,
) {
    SharedTransitionLayout(modifier) {
        AnimatedContent(
            targetState = top,
            transitionSpec = { onboardingTransition(OnboardingTransition.between(initialState, targetState), lastWasPush(), reduceMotion) },
            label = "onboarding",
        ) { route ->
            ProvideOnboardingHero(this@SharedTransitionLayout, this@AnimatedContent) { content(route) }
        }
    }
}

/** How the onboarding stack moves from one route to the next ([OnboardingRouteHost]). */
internal enum class OnboardingTransition {
    /**
     * The root (Welcome or the lock screen) ↔ Sign Up / Log In: a plain fade; the hero zoom out of
     * the brand mark carries the motion (`navigationTransition(.zoom)`, `OnboardingHeroTransition.swift:46-56`).
     */
    Zoom,

    /** Sign Up ↔ Log In: the push slide (the addendum: a slide reads better than iOS's second zoom). */
    Slide,
    ;

    companion object {
        /** The transition from [from] to [to]; null is the root. */
        fun between(from: OnboardingRoute?, to: OnboardingRoute?): OnboardingTransition =
            if (from.isAuthScreen && to.isAuthScreen) Slide else Zoom

        private val OnboardingRoute?.isAuthScreen: Boolean
            get() = this == OnboardingRoute.SignUp || this == OnboardingRoute.LogIn
    }
}

/** [kind]'s content transform; [push] picks the slide's direction. */
private fun onboardingTransition(kind: OnboardingTransition, push: Boolean, reduce: Boolean): ContentTransform = when {
    reduce -> fadeIn(Motion.reduced()) togetherWith fadeOut(Motion.reduced())
    kind == OnboardingTransition.Zoom -> fadeIn(Motion.fade()) togetherWith fadeOut(Motion.fade())
    else -> onboardingPushPop(push)
}

/** The iOS push: the new screen slides in from the end, the old one drifts back a third (`ShroudApp.kt` interim). */
private fun onboardingPushPop(push: Boolean): ContentTransform {
    return if (push) {
        (slideInHorizontally(Motion.standard()) { it } + fadeIn(Motion.fade())) togetherWith
            (slideOutHorizontally(Motion.standard()) { -it / 3 } + fadeOut(Motion.fade()))
    } else {
        (slideInHorizontally(Motion.standard()) { -it / 3 } + fadeIn(Motion.fade())) togetherWith
            (slideOutHorizontally(Motion.standard()) { it } + fadeOut(Motion.fade()))
    }
}

/** TalkBack skips the subtree while [hidden] (iOS `.accessibilityHidden`). */
internal fun Modifier.hiddenFromAccessibility(hidden: Boolean): Modifier = if (hidden) clearAndSetSemantics {} else this

/** `Toast.info(it, 2400)` for the post-auth notices (shell-chats §3.14). */
private const val POST_AUTH_TOAST_MS = 2_400L

private const val ROOT_ROUTE_KEY = "root"
