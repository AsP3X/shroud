package de.corespace.shroud.di

import de.corespace.shroud.AppContainer
import de.corespace.shroud.AppModule

/**
 * Shell (00-plan §1.2, shell-chats §3). Owner: W3-SHELL — `AppShellController` (routing,
 * auto-lock, covers, session probe) on [AppContainer.appPhase], started in [onProcessStart],
 * where it replaces `ShroudApplication`'s interim `ON_STOP` crypto lock (shell-chats §3.7).
 *
 * Created empty by W0-A; only the owner fills it (00-plan §2.0 rule 3, §2.6). Nobody else
 * constructs this package's classes: other packages reach them through this module.
 */
class ShellModule(container: AppContainer) : AppModule(container) {
    /** Filled by the owner: `AppShellController` start (W3-SHELL). */
    override fun onProcessStart() = Unit
}
