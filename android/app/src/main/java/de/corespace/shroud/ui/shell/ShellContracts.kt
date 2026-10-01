package de.corespace.shroud.ui.shell

import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import de.corespace.shroud.core.net.ServerConfiguration
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.UUID

/**
 * What screens may ask of the shell's navigation (iOS `AppRouter` / the tab stacks, shell-chats §5;
 * plan §1.7.13).
 *
 * **Seam (W2-INT), owner W3-SHELL** (`ShellNavigator` implements it and provides [LocalShellNavigation]).
 * Screens never hold a stack of their own: pushes and pops go through this, so predictive back, the
 * two-pane layout and notification opens stay in one place.
 */
interface ShellNavigation {
    val selection: StateFlow<MainTab>
    fun select(tab: MainTab)
    fun push(route: ChatRoute)
    fun push(route: SettingsRoute)

    /** Pops the selected tab's top screen; false when it was at its root. */
    fun pop(): Boolean
    fun popToRoot()

    /** Chats tab, its stack reset to [peerId]'s conversation (notification opens, New Chat, profile "Message"). */
    fun openChat(peerId: UUID, username: String)

    /** No shell (previews, screen tests): navigation does nothing. */
    object Detached : ShellNavigation {
        override val selection: StateFlow<MainTab> = MutableStateFlow(MainTab.Chats)
        override fun select(tab: MainTab) = Unit
        override fun push(route: ChatRoute) = Unit
        override fun push(route: SettingsRoute) = Unit
        override fun pop(): Boolean = false
        override fun popToRoot() = Unit
        override fun openChat(peerId: UUID, username: String) = Unit
    }
}

/** The shell's navigation; [ShellNavigation.Detached] outside the shell. */
val LocalShellNavigation: ProvidableCompositionLocal<ShellNavigation> = staticCompositionLocalOf { ShellNavigation.Detached }

/**
 * Space the floating tab bar covers at the bottom; scrolling screens pad their content by it
 * (iOS `\.tabBarClearance`; memory note: NavigationStack blocks the outer safe area, so it travels
 * as an environment value). 0 on pushed screens and outside the shell.
 */
val LocalTabBarClearance: ProvidableCompositionLocal<Dp> = compositionLocalOf { 0.dp }

/** The tab bar's search field is active (the searchable tab hides its large title, shell-chats §4.4). */
val LocalIsTabBarSearchActive: ProvidableCompositionLocal<Boolean> = compositionLocalOf { false }

/** Compact (one stack) or two panes side by side on wide windows (P12a decided: every tab, width based). */
enum class WindowLayout { Compact, TwoPane }

val LocalWindowLayout: ProvidableCompositionLocal<WindowLayout> = compositionLocalOf { WindowLayout.Compact }

/**
 * A pushed screen that must keep Back for itself (an unsaved draft, a recording take) turns the
 * shell's back handling off while it needs to (shell-chats §6.4 predictive back).
 */
interface BackGate {
    fun setEnabled(enabled: Boolean)

    /** Outside the shell: nothing to gate. */
    object None : BackGate {
        override fun setEnabled(enabled: Boolean) = Unit
    }
}

val LocalPushedBackGate: ProvidableCompositionLocal<BackGate> = staticCompositionLocalOf { BackGate.None }

/**
 * App-wide actions screens trigger (iOS `RootView` closures handed down; settings-lock §5, §13):
 * Log Out (the device wipe), Log Out to switch servers, Lock Chats Now.
 */
interface AppActions {
    fun logOut()

    /** Log Out, then use [switchingTo] once the wipe is over (the revoke goes to the old server). */
    fun logOut(switchingTo: ServerConfiguration)
    fun lockChatsNow()
    val isLoggingOut: StateFlow<Boolean>

    /** Outside the shell: the actions do nothing. */
    object None : AppActions {
        override fun logOut() = Unit
        override fun logOut(switchingTo: ServerConfiguration) = Unit
        override fun lockChatsNow() = Unit
        override val isLoggingOut: StateFlow<Boolean> = MutableStateFlow(false)
    }
}

val LocalAppActions: ProvidableCompositionLocal<AppActions> = staticCompositionLocalOf { AppActions.None }

/**
 * What the lock screen asks of the shell (iOS `AppRouter` members the lock screen calls,
 * settings-lock §11; W3-LOCK-ONBOARD's `LockScreen` takes it, W3-SHELL implements it).
 */
interface LockScreenRouter {
    /** Builds the main shell behind the lock screen while it still says "Checking…" (messaging `prepareCachedState`). */
    suspend fun prewarmMainShell()
    fun cancelMainShellPrewarm()

    /** The vault opened: reveal the chats. */
    fun unlockMessages()

    /** A session without local keys (app data wiped, interrupted login): clears it; true when it did. */
    suspend fun reconcileOrphanedSessionIfNeeded(): Boolean
    fun showPhraseEntry()
    fun logOut()

    /** The toast the next screen shows once ("Signed out", …). */
    var postAuthToast: String?
}
