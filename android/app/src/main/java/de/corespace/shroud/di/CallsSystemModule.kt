package de.corespace.shroud.di

import de.corespace.shroud.AppContainer
import de.corespace.shroud.AppModule

/**
 * Call system integration (00-plan §1.7.11). Owner: W3-CALLS-SYSTEM — the `CallSystem`
 * (self-managed Telecom, `CallService`, CallStyle notifications, ringer, audio routes);
 * registers with Telecom in [onProcessStart].
 *
 * Created empty by W0-A; only the owner fills it (00-plan §2.0 rule 3, §2.6). Nobody else
 * constructs this package's classes: other packages reach them through this module.
 */
class CallsSystemModule(container: AppContainer) : AppModule(container) {
    /** Filled by the owner: Telecom registration (W3-CALLS-SYSTEM). */
    override fun onProcessStart() = Unit
}
