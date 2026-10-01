package de.corespace.shroud.di

import de.corespace.shroud.AppContainer
import de.corespace.shroud.AppModule
import de.corespace.shroud.core.net.AuthOutcomeListener
import de.corespace.shroud.core.realtime.AppForegroundCoordinator
import de.corespace.shroud.core.realtime.RealtimeClient

/**
 * Realtime (00-plan §1.7.3, C4, C13). Owner: W1-RT — the process's only [RealtimeClient] (typed
 * `events`, holders `Messaging` / `Call` / `Background`) and the [AppForegroundCoordinator] on
 * [AppContainer.appPhase], started in [onProcessStart].
 *
 * Nobody else constructs this package's classes (00-plan §2.0 rule 3): two clients would evict
 * each other's socket in a loop (api-realtime §17.2). Messaging, calls, contacts and the
 * background connection reach the one [client] through this module.
 */
class RealtimeModule(container: AppContainer) : AppModule(container) {
    /**
     * Where the socket reports `DEVICE_REMOVED` (with its token): the session's listener, the same
     * one `ApiClient.authOutcomes` gets. Set by INT (plan §1.7.6: "set on ApiClient and
     * RealtimeClient by INT"); while null a removal seen on the socket wipes nothing and waits for
     * the next REST call to report it.
     */
    @Volatile
    var authOutcomes: AuthOutcomeListener? = null

    /**
     * The device's one socket. Built on first use, on the main thread (its state is main-confined).
     * The base URL follows the server settings live; the first focus comes from the app phase.
     */
    val client: RealtimeClient by lazy {
        RealtimeClient(
            baseUrl = { container.serverConfiguration.configuration.value.resolvedBaseUrl },
            json = container.json,
            // NetModule's base client: same settings as REST (no cache, no redirects) and one
            // connection pool and dispatcher; the client adds its 25 s ping with newBuilder().
            baseHttp = container.net.http,
            scope = container.appScope,
            authOutcomes = { authOutcomes },
            isForeground = { container.appPhase.isStarted },
        )
    }

    /** The scene-phase glue (api-realtime §11.13); [onProcessStart] starts it. */
    val foregroundCoordinator: AppForegroundCoordinator by lazy {
        AppForegroundCoordinator(
            // W2-INT points these at the controllers (`MessagingController` implements
            // MessagingForeground, `CallController` ActiveCallProbe); until then nothing happens.
            messaging = { null },
            calls = { null },
            // iOS `router.isUnlocked` until W3-SHELL's AppShellController owns it: chats unlocked.
            // Through the module, not the onboarding shim `container.cryptoController` (W3-INT removes it).
            isUnlocked = { container.keys.cryptoController.unlockedUserId.value != null },
            phase = container.appPhase,
            scope = container.appScope,
            backgroundConnectionHeld = { client.isHeld(RealtimeClient.Holder.Background) },
        )
    }

    override fun onProcessStart() {
        foregroundCoordinator.start()
    }
}
