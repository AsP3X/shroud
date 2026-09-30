package de.corespace.shroud.ui.navigation

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** Screens of the signed-out stack (`AppRoute` in `AppRouter.swift`). */
enum class OnboardingRoute { Welcome, SignUp, LogIn }

/**
 * The onboarding back stack. Sign Up and Log In always sit directly on Welcome, and switching
 * between them replaces the top (as `path = [.signUp]` does on iOS).
 */
@Stable
class AppRouter(initial: List<OnboardingRoute> = listOf(OnboardingRoute.Welcome)) {
    val stack = mutableStateListOf<OnboardingRoute>().apply { addAll(initial) }

    /** Whether the last change moved forward (push) or back (pop) — picks the transition. */
    var lastWasPush by mutableStateOf(true)
        private set

    /** A notice for Welcome to show once ("Signed out", …). */
    var postAuthToast by mutableStateOf<String?>(null)

    val top: OnboardingRoute get() = stack.last()
    val canPop: Boolean get() = stack.size > 1

    fun showSignUp() = replaceAboveWelcome(OnboardingRoute.SignUp)

    fun showLogIn() = replaceAboveWelcome(OnboardingRoute.LogIn)

    fun pop() {
        if (!canPop) return
        lastWasPush = false
        stack.removeAt(stack.lastIndex)
    }

    /** Back to Welcome alone, e.g. after Log Out. */
    fun reset(toast: String? = null) {
        lastWasPush = false
        stack.clear()
        stack.add(OnboardingRoute.Welcome)
        postAuthToast = toast
    }

    /** Signed in but locked at launch: the phrase step is the only screen. */
    fun resetToPhrase() {
        lastWasPush = true
        stack.clear()
        stack.add(OnboardingRoute.LogIn)
    }

    private fun replaceAboveWelcome(route: OnboardingRoute) {
        lastWasPush = true
        val base = if (stack.first() == OnboardingRoute.Welcome) listOf(OnboardingRoute.Welcome) else emptyList()
        stack.clear()
        stack.addAll(base + route)
    }
}
