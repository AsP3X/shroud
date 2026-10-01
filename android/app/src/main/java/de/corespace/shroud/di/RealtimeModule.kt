package de.corespace.shroud.di

import de.corespace.shroud.AppContainer
import de.corespace.shroud.AppModule

/**
 * Realtime (00-plan §1.7.3, C4, C13). Owner: W1-RT — the process's only `RealtimeClient` (typed
 * `events`, holders `Messaging` / `Call` / `Background`) and the `AppForegroundCoordinator` on
 * [AppContainer.appPhase], started in [onProcessStart].
 *
 * Created empty by W0-A; only the owner fills it (00-plan §2.0 rule 3, §2.6). Nobody else
 * constructs this package's classes: other packages reach them through this module.
 */
class RealtimeModule(container: AppContainer) : AppModule(container) {
    /** Filled by the owner: `AppForegroundCoordinator.start()` (W1-RT). */
    override fun onProcessStart() = Unit
}
