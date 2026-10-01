package de.corespace.shroud

import android.app.KeyguardManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import de.corespace.shroud.core.auth.SessionController
import de.corespace.shroud.core.crypto.Bip39
import de.corespace.shroud.core.crypto.CryptoController
import de.corespace.shroud.core.lifecycle.AppPhaseMonitor
import de.corespace.shroud.core.model.AppClock
import de.corespace.shroud.core.model.SystemAppClock
import de.corespace.shroud.core.net.ServerConfiguration
import de.corespace.shroud.core.net.ServerConfigurationStore
import de.corespace.shroud.core.net.ServerConnectionMode
import de.corespace.shroud.core.net.ShroudApi
import de.corespace.shroud.di.AuthModule
import de.corespace.shroud.di.CallsMediaModule
import de.corespace.shroud.di.CallsModule
import de.corespace.shroud.di.CallsSystemModule
import de.corespace.shroud.di.ContactsModule
import de.corespace.shroud.di.ImageModule
import de.corespace.shroud.di.KeysModule
import de.corespace.shroud.di.LinksModule
import de.corespace.shroud.di.MediaModule
import de.corespace.shroud.di.MessagingModule
import de.corespace.shroud.di.MessagingSendModule
import de.corespace.shroud.di.MessagingStoreModule
import de.corespace.shroud.di.NetModule
import de.corespace.shroud.di.NotificationsModule
import de.corespace.shroud.di.PushModule
import de.corespace.shroud.di.RealtimeModule
import de.corespace.shroud.di.ShellModule
import de.corespace.shroud.di.TranscriptionModule
import de.corespace.shroud.di.VideoModule
import de.corespace.shroud.di.VoiceModule
import de.corespace.shroud.di.WipeHooksImpl
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.serialization.json.Json

/**
 * Hand-rolled dependency container (00-plan §1.3): a registry of lazily built, per-package module
 * objects (`di/<Name>Module.kt`, one file per package, filled by that package). Nothing constructs
 * a controller outside its module; there is exactly one `ApiClient`, one `RealtimeClient` and one
 * `CryptoController` per process.
 *
 * One per **process**, not per activity (iOS keeps its controllers as `@State` in `RootView`,
 * `RootView.swift:33-51`; shell-chats addendum *ShroudApp.swift*): the push receiver, the
 * background connection, Telecom and the removal wake run with no activity, and an activity
 * recreation must not rebuild them.
 *
 * Built by [ShroudApplication.container] only once the user has unlocked the phone after boot:
 * it reads credential-encrypted storage (`ServerConfigurationStore` would throw in direct boot).
 * After W0 only the wave's INT package edits this file (00-plan §2.6).
 *
 * @param appPhase the process-wide phase (00-plan §1.4). [ShroudApplication] owns and installs it
 *   before the container exists, so it sees every activity from the first one; `AppContainer(context)`
 *   (the plan's signature) picks that instance up.
 */
class AppContainer(
    context: Context,
    val appPhase: AppPhaseMonitor = (context.applicationContext as ShroudApplication).appPhase,
) {
    val appContext: Context = context.applicationContext

    /** Work that must outlive a screen (Log Out revoke, clipboard expiry). Main-confined (00-plan §1.1 rule 3). */
    val appScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /** API JSON. Partial updates use the patch encoder of `ShroudApi` (W1-NET). */
    val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = true
    }

    /** Wall and monotonic time for every controller; tests pass a `FakeAppClock` (00-plan §1.7.1). */
    val clock: AppClock = SystemAppClock

    /** Self-hosted or official server, kept across Log Out (00-plan §1.5, prefs `shroud.server`). */
    val serverConfiguration = ServerConfigurationStore(appContext, json)

    val net by lazy { NetModule(this) }                                   // W1-NET
    val realtime by lazy { RealtimeModule(this) }                         // W1-RT
    val keys by lazy { KeysModule(this) }                                 // W1-KEYS (also builds W1-CRYPTO objects)
    val auth by lazy { AuthModule(this) }                                 // W2-AUTH-WIPE
    val messagingStore by lazy { MessagingStoreModule(this) }             // W2-MSG-STORE
    val messagingSend by lazy { MessagingSendModule(this) }               // W2-MSG-SEND
    val messaging by lazy { MessagingModule(this) }                       // W2-MSG-CORE
    val contacts by lazy { ContactsModule(this) }                         // W2-CONTACTS
    val media by lazy { MediaModule(this) }                               // W2-MEDIA-STORE
    val images by lazy { ImageModule(this) }                              // W2-MEDIA-IMAGE
    val video by lazy { VideoModule(this) }                               // W2-VIDEO
    val voice by lazy { VoiceModule(this) }                               // W2-VOICE
    val links by lazy { LinksModule(this) }                               // W2-LINKS
    val notifications by lazy { NotificationsModule(this) }               // W2-NOTIF
    val calls by lazy { CallsModule(this) }                               // W2-CALLS-CORE
    val callsMedia by lazy { CallsMediaModule(this) }                     // W3-CALLS-MEDIA
    val callsSystem by lazy { CallsSystemModule(this) }                   // W3-CALLS-SYSTEM
    val push by lazy { PushModule(this) }                                 // W3-PUSH
    val transcription by lazy { TranscriptionModule(this) }               // W2-WHISPER → W3-TRANSCRIPTION

    val shell by lazy { ShellModule(this) }                               // W3-SHELL

    /**
     * The Log Out / removal wipe's view of every package (00-plan §1.7.6). Typed as the
     * implementation until W1-INT publishes the `core/auth/WipeHooks` seam; then
     * `val wipeHooks: WipeHooks by lazy { WipeHooksImpl(this) }`.
     */
    val wipeHooks: WipeHooksImpl by lazy { WipeHooksImpl(this) }

    /**
     * Called once per process from [ShroudApplication] after the user unlocked the phone (the
     * pieces iOS runs in `AppDelegate.didFinishLaunching`, `AppDelegate.swift:13-21`, and at the
     * top of `RootView`). The order is owned by the INT package of each wave (00-plan §1.3).
     */
    fun onProcessStart() {
        keys.onProcessStart()            // SensitiveTempFiles.prepareAtLaunch() (W1-KEYS)
        notifications.onProcessStart()   // channels ensure (W2-NOTIF)
        push.onProcessStart()            // UnifiedPush restore + background connection (W3-PUSH)
        callsSystem.onProcessStart()     // Telecom registration (W3-CALLS-SYSTEM)
        realtime.onProcessStart()        // AppForegroundCoordinator on appPhase (W1-RT)
        net.onProcessStart()             // ConnectivityMonitor.start() (W1-NET)
        shell.onProcessStart()           // AppShellController (W3-SHELL; replaces the interim ON_STOP lock)
    }

    // Source-compatibility shims for the onboarding code (removed by W3-INT, 00-plan §1.3).

    val api: ShroudApi get() = net.api
    val sessionController: SessionController get() = auth.sessionController
    val cryptoController: CryptoController get() = keys.cryptoController
    val bip39: Bip39 get() = keys.bip39

    /**
     * Android 17 makes reaching the local network a runtime permission (`ACCESS_LOCAL_NETWORK`)
     * for apps targeting it. Needed when the server is a LAN or emulator-host address; the phone's
     * own loopback is exempt.
     */
    fun needsLocalNetworkPermission(): Boolean {
        if (Build.VERSION.SDK_INT < 37) return false
        val config = serverConfiguration.configuration.value
        if (config.mode != ServerConnectionMode.SelfHosted) return false
        val host = config.host.trim().trim('[', ']').lowercase()
        if (host == "localhost" || host == "::1" || host.startsWith("127.")) return false
        if (!ServerConfiguration.isLocalNetworkHost(host)) return false
        return appContext.checkSelfPermission(LOCAL_NETWORK_PERMISSION) != PackageManager.PERMISSION_GRANTED
    }

    /**
     * A screen lock (PIN, pattern, password) is what the history-key vault will be gated on.
     * W1-KEYS moves this to `keys.deviceSecurity.isDeviceSecure`.
     */
    fun hasScreenLock(): Boolean =
        (appContext.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager).isDeviceSecure
}

/**
 * Base class of every `di/` module (00-plan §1.3). A module builds its package's objects lazily
 * from [container] and exposes what other packages need; nobody else constructs them.
 */
abstract class AppModule(protected val container: AppContainer) {
    /** Process start, in the order [AppContainer.onProcessStart] gives. Must be cheap and must not throw. */
    open fun onProcessStart() {}
}

const val LOCAL_NETWORK_PERMISSION = "android.permission.ACCESS_LOCAL_NETWORK"
