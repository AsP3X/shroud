package de.corespace.shroud.di

import de.corespace.shroud.AppContainer
import de.corespace.shroud.AppModule
import de.corespace.shroud.core.net.ApiClient
import de.corespace.shroud.core.net.ShroudApi

/**
 * HTTP (00-plan §1.7.2). Owner: W1-NET — the one `ApiClient` (its `authOutcomes` is set by INT
 * once `SessionController` reports them), the `ShroudApi` facade and `ConnectivityMonitor`
 * (started in [onProcessStart]).
 */
class NetModule(container: AppContainer) : AppModule(container) {
    /** The process's only HTTP client; the base URL follows the server settings live. */
    val apiClient: ApiClient by lazy {
        ApiClient(baseUrl = { container.serverConfiguration.configuration.value.resolvedBaseUrl }, json = container.json)
    }

    val api: ShroudApi by lazy { ShroudApi(apiClient) }

    /** Filled by the owner: `ConnectivityMonitor.start()` (W1-NET). */
    override fun onProcessStart() = Unit
}
