package de.corespace.shroud.di

import de.corespace.shroud.AppContainer
import de.corespace.shroud.AppModule
import de.corespace.shroud.core.net.ApiClient
import de.corespace.shroud.core.net.ConnectivityMonitor
import de.corespace.shroud.core.net.ShroudApi
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient

/**
 * HTTP (00-plan §1.7.2, C3, C5). Owner: W1-NET. The process's only [ApiClient] and [ShroudApi],
 * the base [OkHttpClient] every network client derives from, and the [ConnectivityMonitor]
 * (started in [onProcessStart]). Nobody else constructs these.
 *
 * Wiring by the INT package: `apiClient.authOutcomes` is the session's listener
 * ([AppContainer.authOutcomes]), `apiClient.updateRequired` runs the client-version check
 * (`UpdateModule`), and `AppContainer.onProcessStart` forwards
 * `connectivity.networkAvailable` to `RealtimeClient.onNetworkAvailable()`.
 */
class NetModule(container: AppContainer) : AppModule(container) {
    /**
     * The base client: 20 s idle timeouts, no cache, no redirects. The media client
     * ([ApiClient.mediaHttp]) and the WebSocket client (W1-RT, `pingInterval` 25 s) are derived
     * from it with `newBuilder()`, so all three share one connection pool and dispatcher
     * (api-realtime §2.3).
     */
    val http: OkHttpClient by lazy { ApiClient.defaultHttpClient() }

    /** The process's only HTTP client; the base URL follows the server settings live. */
    val apiClient: ApiClient by lazy {
        ApiClient(baseUrl = { container.serverConfiguration.configuration.value.resolvedBaseUrl }, json = container.json, http = http)
            .apply {
                authOutcomes = container.authOutcomes
                // 426 UPDATE_REQUIRED, reported on an IO thread; the checker is main-confined.
                updateRequired = { container.appScope.launch { container.update.checker.onUpdateRequired() } }
            }
    }

    val api: ShroudApi by lazy { ShroudApi(apiClient) }

    /** Partial-update encoder (`explicitNulls = false`), the same instance as [ShroudApi.patchJson]. */
    val patchJson: Json get() = api.patchJson

    /** Online / offline for messaging's banner and polling, and the reconnect hook of the socket. */
    val connectivity: ConnectivityMonitor by lazy { ConnectivityMonitor(container.appContext) }

    override fun onProcessStart() {
        connectivity.start()
    }
}
