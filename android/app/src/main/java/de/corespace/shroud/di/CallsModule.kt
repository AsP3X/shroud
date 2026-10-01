package de.corespace.shroud.di

import de.corespace.shroud.AppContainer
import de.corespace.shroud.AppModule

/**
 * Calls (00-plan §1.7.11, C29). Owner: W2-CALLS-CORE — the one `CallController` (the
 * `ActiveCallProbe`), `CallSecrets`, `CallHistory`, `CallPreferences`.
 *
 * Created empty by W0-A; only the owner fills it (00-plan §2.0 rule 3, §2.6). Nobody else
 * constructs this package's classes: other packages reach them through this module.
 */
class CallsModule(container: AppContainer) : AppModule(container)
