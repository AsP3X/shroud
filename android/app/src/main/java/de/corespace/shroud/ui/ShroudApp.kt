package de.corespace.shroud.ui

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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Modifier
import de.corespace.shroud.AppContainer
import de.corespace.shroud.core.auth.SessionController
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
import de.corespace.shroud.ui.theme.Motion

/**
 * The root (`RootView.swift`). Messaging unlocked → the signed-in shell; otherwise the onboarding
 * stack. A session without unlocked keys at launch opens straight on the phrase step.
 */
@Composable
fun ShroudApp(container: AppContainer) {
    val session by container.sessionController.session.collectAsState()
    val unlockedUserId by container.cryptoController.unlockedUserId.collectAsState()
    // Unlocked means this session's account: keys of another account never open a shell.
    val unlocked = unlockedUserId != null && unlockedUserId == session?.userId
    val server by container.serverConfiguration.configuration.collectAsState()
    val toast = rememberToastState()
    val router = remember {
        AppRouter(if (container.sessionController.session.value != null) listOf(OnboardingRoute.LogIn) else listOf(OnboardingRoute.Welcome))
    }
    var showServerSettings by remember { mutableStateOf(false) }

    // Launch check of the session (`validateSessionIfNeeded`).
    LaunchedEffect(Unit) {
        if (container.sessionController.session.value == null) return@LaunchedEffect
        when (container.sessionController.validate()) {
            SessionController.Validation.DeviceRemoved -> router.reset("This phone was removed from your account.")
            SessionController.Validation.SignedOut -> router.reset("Signed out. Log in again.")
            else -> Unit
        }
    }
    // Locked again (background) while still signed in: back to the phrase step, whichever
    // screen unlocked. Log Out locks too, but by then the session is gone.
    var wasUnlocked by remember { mutableStateOf(false) }
    // Log Out: Welcome first, so the old stack never shows while the revoke is in flight.
    val logOut = {
        router.reset("Signed out")
        container.sessionController.logOut()
    }
    LaunchedEffect(unlocked) {
        if (wasUnlocked && !unlocked && container.sessionController.session.value != null) router.resetToPhrase()
        wasUnlocked = unlocked
    }
    LaunchedEffect(router.postAuthToast) {
        router.postAuthToast?.let {
            toast.show(Toast.info(it, 2400))
            router.postAuthToast = null
        }
    }

    Box(Modifier.fillMaxSize()) {
        if (unlocked && session != null) {
            SignedInPlaceholder(username = session!!.username, onLogOut = logOut)
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
                        onSessionEnded = { router.reset(it) },
                    )
                }
            }
        }
        ShroudSheet(visible = showServerSettings, onDismiss = { showServerSettings = false }) {
            ServerSettingsContent(
                initial = server,
                onSave = { draft ->
                    // A session belongs to its server: another endpoint signs this phone out
                    // (the revoke goes to the old server, built before the switch).
                    if (session != null && draft.resolvedBaseUrl != server.resolvedBaseUrl) {
                        container.sessionController.logOut()
                    }
                    container.serverConfiguration.save(draft)
                    showServerSettings = false
                },
                onCancel = { showServerSettings = false },
            )
        }
        ToastHost(toast)
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
