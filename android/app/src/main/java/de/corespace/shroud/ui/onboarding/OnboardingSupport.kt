package de.corespace.shroud.ui.onboarding

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
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
import de.corespace.shroud.core.auth.OnboardingService
import de.corespace.shroud.core.auth.Session
import de.corespace.shroud.core.auth.SessionController
import de.corespace.shroud.core.crypto.Bip39
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow

/**
 * What Sign Up and Log In need from the rest of the app — the session, the phrase helpers, the
 * crypto unlock, the screen lock, the local-network rule. Production is [ContainerOnboardingServices],
 * a 1:1 forward to core's `auth.onboarding` ([OnboardingService], K2) plus the session, the word list
 * and the app scope; screen tests pass a fake.
 */
interface OnboardingServices {
    /** The signed-in session, if any (`sessionController.session`). */
    val session: StateFlow<Session?>

    /** The BIP39 word list and checks (`EncryptionPhraseGenerator`, `BIP39Seed`, `EncryptionPhraseParser`). */
    val bip39: Bip39

    /** Work that outlives the screen: the clipboard expiry (`PhraseClipboard`). */
    val appScope: CoroutineScope

    /** A PIN, pattern or password is set: the vault needs one ([OnboardingService.hasScreenLock]). */
    fun hasScreenLock(): Boolean

    /** Android 17's local-network permission is missing for the configured server ([OnboardingService.needsLocalNetworkPermission]). */
    fun needsLocalNetworkPermission(): Boolean

    /** `POST /auth/register` ([OnboardingService.register]). */
    suspend fun register(username: String, password: String): Session

    /** `POST /auth/login` ([OnboardingService.login]). */
    suspend fun login(username: String, password: String): Session

    /** What became of the session after a failed authenticated request ([OnboardingService.sessionAfterFailure]). */
    fun sessionAfterFailure(): SessionController.Validation

    /** Sign Up's keys: derive, store, publish ([OnboardingService.establishFromSignup]). */
    suspend fun establishFromSignup(words: List<String>, session: Session)

    /** Log In's phrase step ([OnboardingService.unlockWithPhrase]). */
    suspend fun unlockWithPhrase(words: List<String>, session: Session)

    /**
     * True only when the account has no identity key at all (`KEYS_REQUIRED` or a 404 from `GET
     * keys/identity/{me}`): it never had a phrase (`LogInFlowView.swift:758-781`; web-parity §14.2,
     * P11b). Throws the [de.corespace.shroud.core.net.ApiError] of any other answer
     * ([OnboardingService.accountHasNoKey]); Log In reads that as "has a key" ([LogInActions.accountHasNoKey]).
     */
    suspend fun accountHasNoKey(session: Session): Boolean
}

/**
 * The production [OnboardingServices]: forwards 1:1 to [onboarding] (core `auth.onboarding`, K2),
 * with the session from `auth.sessionController`, the word list from `keys.bip39` and the app scope
 * (rule R4: no logic here).
 */
class ContainerOnboardingServices(
    private val onboarding: OnboardingService,
    override val session: StateFlow<Session?>,
    override val bip39: Bip39,
    override val appScope: CoroutineScope,
) : OnboardingServices {
    constructor(container: AppContainer) : this(
        onboarding = container.auth.onboarding,
        session = container.auth.sessionController.session,
        bip39 = container.keys.bip39,
        appScope = container.appScope,
    )

    override fun hasScreenLock(): Boolean = onboarding.hasScreenLock()
    override fun needsLocalNetworkPermission(): Boolean = onboarding.needsLocalNetworkPermission()
    override suspend fun register(username: String, password: String): Session = onboarding.register(username, password)
    override suspend fun login(username: String, password: String): Session = onboarding.login(username, password)
    override fun sessionAfterFailure(): SessionController.Validation = onboarding.sessionAfterFailure()
    override suspend fun establishFromSignup(words: List<String>, session: Session) = onboarding.establishFromSignup(words, session)
    override suspend fun unlockWithPhrase(words: List<String>, session: Session) = onboarding.unlockWithPhrase(words, session)
    override suspend fun accountHasNoKey(session: Session): Boolean = onboarding.accountHasNoKey(session)
}

/** Screen tests provide their fake here; the app leaves it null and the screens use [ContainerOnboardingServices]. */
val LocalOnboardingServices: ProvidableCompositionLocal<OnboardingServices?> = staticCompositionLocalOf { null }

/** The onboarding services for [container], or the fake a test provided. */
@Composable
fun rememberOnboardingServices(container: AppContainer): OnboardingServices =
    LocalOnboardingServices.current ?: remember(container) { ContainerOnboardingServices(container) }

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
