package de.corespace.shroud.di

import de.corespace.shroud.AppContainer
import de.corespace.shroud.AppModule
import de.corespace.shroud.ui.shell.AppShellController
import de.corespace.shroud.ui.shell.ContainerShellEnvironment
import de.corespace.shroud.ui.shell.ScreenCaptureMonitor
import de.corespace.shroud.ui.shell.ShellEnvironment
import kotlinx.coroutines.launch

/**
 * Shell (00-plan §1.2, §1.3; shell-chats §3). Owner: W3-SHELL.
 *
 * - [controller] — `AppShellController`: launch sequence, session probe while locked, the reactions
 *   iOS `RootView` runs on session / unlock / wipe / call changes, the app lifecycle with the
 *   auto-lock, and the cover state the root draws. It replaces W2's interim `InterimSession`.
 * - [screenCapture] — recording / mirroring detection, fed into the controller.
 *
 * The shell starts with the first `MainActivity` of the process ([startShell]), as W2's interim
 * root did and as iOS's `RootView.task` runs with the window, and then keeps running on the app
 * scope while the activity is stopped or gone. It deliberately does not start in [onProcessStart]:
 * a process a push, the boot receiver or `CallActivity` started must not run the launch sequence —
 * `finishInterruptedWipeIfNeeded` there would end the session of a phone that is only being woken,
 * and the system e2e starts `CallActivity` on purpose to clear the package's stopped flag without
 * it (docs/android-handover-from-grok.md §3 G8, §4 C14). Before any `MainActivity` the chats are
 * locked, so there is nothing for the auto-lock to do.
 *
 * Nobody else constructs these classes (00-plan §2.0 rule 3).
 */
class ShellModule(container: AppContainer) : AppModule(container) {
    /** The shell's view of every package. */
    val environment: ShellEnvironment by lazy { ContainerShellEnvironment(container) }

    /** The process's one shell controller. */
    val controller: AppShellController by lazy { AppShellController(environment, container.appScope) }

    /** Screen recording (API 35+, attached by `MainActivity`) and presentation displays (all APIs). */
    val screenCapture: ScreenCaptureMonitor by lazy { ScreenCaptureMonitor() }

    /** [startShell] ran in this process. */
    var isStarted = false
        private set

    /** Starts the shell once per process (main thread, `MainActivity.onCreate`) and returns it. */
    fun startShell(): AppShellController {
        val shell = controller
        if (isStarted) return shell
        isStarted = true
        shell.start()
        screenCapture.startDisplays(container.appContext)
        container.appScope.launch { screenCapture.captured.collect(shell::setScreenCaptured) }
        return shell
    }
}
