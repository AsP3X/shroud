package de.corespace.shroud

import android.app.KeyguardManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import de.corespace.shroud.core.auth.SessionController
import de.corespace.shroud.core.auth.SessionStore
import de.corespace.shroud.core.crypto.Bip39
import de.corespace.shroud.core.crypto.CryptoController
import de.corespace.shroud.core.net.ApiClient
import de.corespace.shroud.core.net.ServerConfiguration
import de.corespace.shroud.core.net.ServerConfigurationStore
import de.corespace.shroud.core.net.ServerConnectionMode
import de.corespace.shroud.core.net.ShroudApi
import de.corespace.shroud.core.storage.KeystoreSealer
import de.corespace.shroud.core.storage.SealedFile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.serialization.json.Json
import java.io.File

/** Hand-rolled dependency container: one per process, built in [ShroudApplication]. */
class AppContainer(context: Context) {
    val appContext: Context = context.applicationContext

    val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = true
    }

    /** Work that must outlive a screen (Log Out revoke, clipboard expiry). */
    val appScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    val serverConfiguration = ServerConfigurationStore(appContext, json)

    val api = ShroudApi(ApiClient(baseUrl = { serverConfiguration.configuration.value.resolvedBaseUrl }, json = json))

    /** Session files live in no-backup storage, sealed by a Keystore key (see [KeystoreSealer]). */
    private val sessionSealer = KeystoreSealer("shroud.session.v1")
    val sessionController = SessionController(
        api,
        SessionStore(
            sessionFile = SealedFile(File(appContext.noBackupFilesDir, "session.sealed"), sessionSealer),
            anchorFile = SealedFile(File(appContext.noBackupFilesDir, "device-anchor.sealed"), sessionSealer),
            json = json,
        ),
        appScope = appScope,
        onSignedOut = { cryptoController.lock() },
    )

    val bip39: Bip39 by lazy {
        Bip39(appContext.assets.open("bip39-english.txt").bufferedReader().readLines().filter { it.isNotBlank() })
    }

    val cryptoController: CryptoController by lazy { CryptoController(api, bip39) }

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

    /** A screen lock (PIN, pattern, password) is what the history-key vault will be gated on. */
    fun hasScreenLock(): Boolean =
        (appContext.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager).isDeviceSecure
}

const val LOCAL_NETWORK_PERMISSION = "android.permission.ACCESS_LOCAL_NETWORK"
