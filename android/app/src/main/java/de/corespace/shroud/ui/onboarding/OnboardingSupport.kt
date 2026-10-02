package de.corespace.shroud.ui.onboarding

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.PackageManager
import android.os.Build
import android.view.WindowManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalContext
import de.corespace.shroud.AppContainer
import de.corespace.shroud.LOCAL_NETWORK_PERMISSION
import de.corespace.shroud.core.auth.Session
import de.corespace.shroud.core.auth.SessionController
import de.corespace.shroud.core.crypto.Bip39
import de.corespace.shroud.core.model.userUuid
import de.corespace.shroud.core.net.ApiError
import de.corespace.shroud.core.net.ErrorCodes
import de.corespace.shroud.core.net.ServerConfiguration
import de.corespace.shroud.core.net.ServerConnectionMode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow

/**
 * What Sign Up and Log In need from the rest of the app — the session, the phrase helpers, the
 * crypto unlock, the screen lock, the local-network rule. The screens reach the process's
 * controllers through their modules ([ContainerOnboardingServices]) instead of the `AppContainer`
 * source-compatibility shims (00-plan §1.3; removed by W3-INT); screen tests pass a fake.
 */
interface OnboardingServices {
    /** The signed-in session, if any (`sessionController.session`). */
    val session: StateFlow<Session?>

    /** The BIP39 word list and checks (`EncryptionPhraseGenerator`, `BIP39Seed`, `EncryptionPhraseParser`). */
    val bip39: Bip39

    /** Work that outlives the screen: the clipboard expiry (`PhraseClipboard`). */
    val appScope: CoroutineScope

    /** A PIN, pattern or password is set: the vault needs one (`HistoryKeyVault.canProtectWrapKey`). */
    fun hasScreenLock(): Boolean

    /** Android 17's local-network permission is missing for the configured server ([localNetworkPermissionNeeded]). */
    fun needsLocalNetworkPermission(): Boolean

    /** `POST /auth/register` (`SessionController.register`). */
    suspend fun register(username: String, password: String): Session

    /** `POST /auth/login` (`SessionController.login`). */
    suspend fun login(username: String, password: String): Session

    /** What became of the session after a failed authenticated request (`SessionController.sessionAfterFailure`). */
    fun sessionAfterFailure(): SessionController.Validation

    /** Sign Up's keys: derive, store, publish (`CryptoController.establishFromSignup`). */
    suspend fun establishFromSignup(words: List<String>, session: Session)

    /** Log In's phrase step (`CryptoController.unlockWithPhrase`). */
    suspend fun unlockWithPhrase(words: List<String>, session: Session)

    /**
     * True only when the server says this account has no identity key at all (`GET
     * keys/identity/{me}` → `KEYS_REQUIRED`): it never had a phrase (`LogInFlowView.swift:758-781`;
     * web-parity §14.2, P11b). A key, offline or any other answer is false.
     */
    suspend fun accountHasNoKey(session: Session): Boolean
}

/** The production [OnboardingServices] on the process's modules. */
class ContainerOnboardingServices(private val container: AppContainer) : OnboardingServices {
    private val sessions get() = container.auth.sessionController
    private val crypto get() = container.keys.cryptoController

    override val session: StateFlow<Session?> get() = sessions.session
    override val bip39: Bip39 get() = container.keys.bip39
    override val appScope: CoroutineScope get() = container.appScope

    override fun hasScreenLock(): Boolean = container.keys.deviceSecurity.isDeviceSecure

    override fun needsLocalNetworkPermission(): Boolean =
        localNetworkPermissionNeeded(container.serverConfiguration.configuration.value) {
            container.appContext.checkSelfPermission(LOCAL_NETWORK_PERMISSION) == PackageManager.PERMISSION_GRANTED
        }

    override suspend fun register(username: String, password: String): Session = sessions.register(username, password)
    override suspend fun login(username: String, password: String): Session = sessions.login(username, password)
    override fun sessionAfterFailure(): SessionController.Validation = sessions.sessionAfterFailure()
    override suspend fun establishFromSignup(words: List<String>, session: Session) = crypto.establishFromSignup(words, session)
    override suspend fun unlockWithPhrase(words: List<String>, session: Session) = crypto.unlockWithPhrase(words, session)

    override suspend fun accountHasNoKey(session: Session): Boolean = try {
        container.net.api.identityKey(session.token, session.userUuid)
        false
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        isKeysRequired(e)
    }
}

/** The server's "this account has no keys" answer (`LogInFlowView.isKeysRequired`, `:777-781`). */
fun isKeysRequired(error: Throwable): Boolean = error is ApiError.Server && error.code == ErrorCodes.KEYS_REQUIRED

/** Screen tests provide their fake here; the app leaves it null and the screens use [ContainerOnboardingServices]. */
val LocalOnboardingServices: ProvidableCompositionLocal<OnboardingServices?> = staticCompositionLocalOf { null }

/** The onboarding services for [container], or the fake a test provided. */
@Composable
fun rememberOnboardingServices(container: AppContainer): OnboardingServices =
    LocalOnboardingServices.current ?: remember(container) { ContainerOnboardingServices(container) }

/**
 * Android 17 makes reaching the local network a runtime permission (`ACCESS_LOCAL_NETWORK`) for apps
 * targeting it: needed when the server is a LAN or emulator-host address; the phone's own loopback
 * is exempt. [granted] reads the permission (moved here from the `AppContainer` shim, 00-plan §1.3).
 */
fun localNetworkPermissionNeeded(config: ServerConfiguration, sdk: Int = Build.VERSION.SDK_INT, granted: () -> Boolean): Boolean {
    if (sdk < 37) return false
    if (config.mode != ServerConnectionMode.SelfHosted) return false
    val host = config.host.trim().trim('[', ']').lowercase()
    if (host == "localhost" || host == "::1" || host.startsWith("127.")) return false
    if (!ServerConfiguration.isLocalNetworkHost(host)) return false
    return !granted()
}

/** Asks for local-network access right before a request needs it (Android 17+). */
fun interface LocalNetworkAccess {
    /** True when the server is reachable as far as permissions go. */
    suspend fun ensure(): Boolean

    companion object {
        const val DENIED_MESSAGE =
            "Shroud needs local network access to reach this server. Allow it in Android Settings › Apps › Shroud › Permissions."
    }
}

/** [LocalNetworkAccess] through the system permission dialog, asked only when [needsPermission]. */
@Composable
fun rememberLocalNetworkAccess(needsPermission: () -> Boolean): LocalNetworkAccess {
    val pending = remember { arrayOfNulls<CompletableDeferred<Boolean>>(1) }
    val needs by rememberUpdatedState(needsPermission)
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        pending[0]?.complete(granted)
        pending[0] = null
    }
    return remember(launcher) {
        LocalNetworkAccess {
            if (!needs()) return@LocalNetworkAccess true
            val result = CompletableDeferred<Boolean>()
            pending[0] = result
            launcher.launch(LOCAL_NETWORK_PERMISSION)
            result.await()
        }
    }
}

/**
 * Keeps the phrase out of the Recents thumbnail while a phrase screen is shown. Android 13+ can
 * drop just the thumbnail; older versions need `FLAG_SECURE`, which also blocks screenshots of
 * this screen.
 */
@Composable
fun HidePhraseFromRecents() {
    val activity = LocalContext.current.findActivity() ?: return
    DisposableEffect(activity) {
        if (Build.VERSION.SDK_INT >= 33) {
            activity.setRecentsScreenshotEnabled(false)
        } else {
            activity.window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }
        onDispose {
            if (Build.VERSION.SDK_INT >= 33) {
                activity.setRecentsScreenshotEnabled(true)
            } else {
                activity.window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
            }
        }
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
