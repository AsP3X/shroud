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
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import de.corespace.shroud.AppContainer
import de.corespace.shroud.LOCAL_NETWORK_PERMISSION
import kotlinx.coroutines.CompletableDeferred

/** Asks for local-network access right before a request needs it (Android 17+). */
fun interface LocalNetworkAccess {
    /** True when the server is reachable as far as permissions go. */
    suspend fun ensure(): Boolean

    companion object {
        const val DENIED_MESSAGE =
            "Shroud needs local network access to reach this server. Allow it in Android Settings › Apps › Shroud › Permissions."
    }
}

@Composable
fun rememberLocalNetworkAccess(container: AppContainer): LocalNetworkAccess {
    val pending = remember { arrayOfNulls<CompletableDeferred<Boolean>>(1) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        pending[0]?.complete(granted)
        pending[0] = null
    }
    return remember(launcher) {
        LocalNetworkAccess {
            if (!container.auth.onboarding.needsLocalNetworkPermission()) return@LocalNetworkAccess true
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
 * this screen. (Blocking screenshots app-wide is an open product decision, android-plan.md G.)
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
