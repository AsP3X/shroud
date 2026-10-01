package de.corespace.shroud.di

import de.corespace.shroud.AppContainer
import de.corespace.shroud.AppModule

/**
 * Push (00-plan §1.7.10; decision record 2026-10-01: UnifiedPush and the opt-in background
 * connection, never Google code). Owner: W3-PUSH — `PushRegistrar`, `PushDispatcher`,
 * `PushDedup`, `BackgroundConnectionController`. [onProcessStart] restores the UnifiedPush state,
 * re-registers when needed and restarts the background connection when it is on and the user
 * is signed in.
 *
 * Created empty by W0-A; only the owner fills it (00-plan §2.0 rule 3, §2.6). Nobody else
 * constructs this package's classes: other packages reach them through this module.
 */
class PushModule(container: AppContainer) : AppModule(container) {
    /** Filled by the owner: UnifiedPush restore and background connection restart (W3-PUSH). */
    override fun onProcessStart() = Unit
}
