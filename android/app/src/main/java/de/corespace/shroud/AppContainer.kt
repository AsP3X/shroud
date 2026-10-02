package de.corespace.shroud

import android.content.Context
import de.corespace.shroud.core.auth.WipeHooks
import de.corespace.shroud.core.lifecycle.AppPhaseMonitor
import de.corespace.shroud.core.model.AppClock
import de.corespace.shroud.core.model.SystemAppClock
import de.corespace.shroud.core.net.AuthOutcomeListener
import de.corespace.shroud.core.net.ServerConfigurationStore
import de.corespace.shroud.core.storage.SensitiveTempFiles
import de.corespace.shroud.core.storage.StorageSeal
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
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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

    /**
     * The process's one wipe write stop (plan §1.4; crypto spec §14). Modules pass this instance to
     * every sealed-record, media and prefs writer and to the wipe controller; nobody else constructs
     * a [StorageSeal], or a writer holding a second one would never see the wipe's seal.
     */
    val storageSeal: StorageSeal = StorageSeal()

    /** Self-hosted or official server, kept across Log Out (00-plan §1.5, prefs `shroud.server`). */
    val serverConfiguration = ServerConfigurationStore(appContext, json)

    /**
     * Where the one `ApiClient` and the one `RealtimeClient` report every authenticated answer
     * (00-plan §1.7.6: "set on ApiClient and RealtimeClient by INT"): the session's
     * [SessionController.authOutcomes], iOS `SessionAuthBridge`. A forwarder, so neither client
     * builds the session; any request with a token means the session already exists.
     */
    val authOutcomes: AuthOutcomeListener = object : AuthOutcomeListener {
        override fun onAuthenticatedSuccess() = auth.sessionController.authOutcomes.onAuthenticatedSuccess()
        override fun onAuthenticationFailure() = auth.sessionController.authOutcomes.onAuthenticationFailure()
        override fun onDeviceRemoved(token: String) = auth.sessionController.authOutcomes.onDeviceRemoved(token)
    }

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

    /** The Log Out / removal wipe's view of every package (00-plan §1.7.6); `DeviceWipeController` (W2-AUTH-WIPE) calls it. */
    val wipeHooks: WipeHooks by lazy { WipeHooksImpl(this) }

    /**
     * Called once per process from [ShroudApplication] after the user unlocked the phone (the
     * pieces iOS runs in `AppDelegate.didFinishLaunching`, `AppDelegate.swift:13-21`, and at the
     * top of `RootView`). The order is owned by the INT package of each wave (00-plan §1.3).
     */
    fun onProcessStart() {
        keys.onProcessStart()            // SensitiveTempFiles.prepareAtLaunch() (W1-KEYS)
        images.onProcessStart()          // MediaEditRenderer bakes later sends (G7)
        notifications.onProcessStart()   // channels ensure (W2-NOTIF)
        calls.onProcessStart()           // the call controller hears socket rings; call secrets follow the unlock (W2-CALLS-CORE)
        voice.bindCallMediaStarting(calls.controller.callMediaStarting)   // a call's media stops voice playback and takes (W2-VOICE)
        push.onProcessStart()            // UnifiedPush restore + background connection (W3-PUSH)
        callsSystem.onProcessStart()     // Telecom registration (W3-CALLS-SYSTEM)
        realtime.onProcessStart()        // AppForegroundCoordinator on appPhase (W1-RT)
        // A returning network reconnects the socket at once (api-realtime §11.14). Collected before
        // the monitor starts: the flow has no replay. Main-confined, like the client.
        appScope.launch { net.connectivity.networkAvailable.collect { realtime.client.onNetworkAvailable() } }
        net.onProcessStart()             // ConnectivityMonitor.start() (W1-NET)
        shell.onProcessStart()           // AppShellController (W3-SHELL; replaces the interim ON_STOP lock)
    }

    /**
     * Locks the chats in memory (`lockChatsInMemory`, `RootView.swift:315-320`): batched saves and
     * queued writes reach disk and decrypted threads leave memory, the keys go, then plaintext
     * `cacheDir/shroud-*` files nobody wrote to for ten minutes are swept (plan §1.1 rule 7, §1.5).
     * Android does not end the process at lock, so without the sweep a leaked temp file would live
     * until the next cold start. The interim ON_STOP lock calls this; W3-SHELL's auto-lock
     * (AppShellController) must keep calling it when it replaces that lock.
     */
    suspend fun lockChatsInMemory() {
        messaging.controllerIfBuilt?.lockSensitiveMemory()
        keys.cryptoController.lock()
        media.sharingIfBuilt?.revokeAll()
        withContext(Dispatchers.IO) { keys.sensitiveTempFiles.sweep(SensitiveTempFiles.STALE_AGE_MS) }
    }
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
