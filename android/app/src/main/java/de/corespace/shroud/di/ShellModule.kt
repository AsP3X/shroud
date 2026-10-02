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
 *   auto-lock, and the cover state the root draws. Built and started in [onProcessStart], so the
 *   lifecycle and the auto-lock work from the first activity on, also while no activity exists.
 *   It replaces `ShroudApplication`'s interim `ON_STOP` crypto lock (shell-chats §3.7; that
 *   observer's removal is W3-INT's, see the package report).
 * - [screenCapture] — recording / mirroring detection, fed into the controller.
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

    override fun onProcessStart() {
        val shell = controller
        shell.start()
        screenCapture.startDisplays(container.appContext)
        container.appScope.launch { screenCapture.captured.collect(shell::setScreenCaptured) }
    }
}
