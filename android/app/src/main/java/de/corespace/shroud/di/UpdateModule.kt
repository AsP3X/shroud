package de.corespace.shroud.di

import de.corespace.shroud.AppContainer
import de.corespace.shroud.AppModule
import de.corespace.shroud.BuildConfig
import de.corespace.shroud.core.lifecycle.AppPhase
import de.corespace.shroud.core.update.ClientUpdateChecker
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * App updates: the process's one [ClientUpdateChecker], which the root's update prompts read.
 *
 * [onProcessStart] wires when it asks: each time one of our activities comes up from the
 * background (the app's start included; a process a push or the boot receiver started asks
 * nothing until then), and at once when the server settings point somewhere else. The version
 * sent is `versionName` (`apk.sh`'s `VERSION_NAME`), never `versionCode`, which carries the ABI
 * offsets.
 */
class UpdateModule(container: AppContainer) : AppModule(container) {
    val checker: ClientUpdateChecker by lazy {
        ClientUpdateChecker(
            currentVersion = BuildConfig.VERSION_NAME,
            fetch = { version -> container.net.api.clientVersion(ClientUpdateChecker.PLATFORM, version) },
            clock = container.clock,
            scope = container.appScope,
        )
    }

    override fun onProcessStart() {
        val scope = container.appScope
        scope.launch {
            container.appPhase.phase
                .map { it != AppPhase.Background }
                .distinctUntilChanged()
                .filter { it }
                .collect { checker.onForeground() }
        }
        scope.launch {
            container.serverConfiguration.configuration
                .map { it.resolvedBaseUrl }
                .distinctUntilChanged()
                .drop(1)
                .collect { checker.onServerChanged() }
        }
    }
}
