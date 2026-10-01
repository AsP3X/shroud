package de.corespace.shroud.ui.permissions

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner

/** What a runtime-permission request came to. */
enum class PermissionOutcome {
    Granted,

    /** Not now; the system will show its dialog again next time. */
    Denied,

    /** The system no longer asks ("Don't allow" twice, or "Don't ask again"): only Settings can change it. */
    PermanentlyDenied,
}

/**
 * Tells a first denial from a permanent one — pure, from what Android exposes around a request
 * (conversation-compose-media §4.7 and §7.4, contacts §8 "Permission permanently denied",
 * notifications-push §6.2).
 *
 * - The system answers a permission it will no longer ask for at once, without its dialog: our
 *   activity is never paused ([dialogShown] false) → permanent.
 * - After a first "Don't allow" Android starts asking for a rationale ([rationaleAfter] true) → it
 *   will ask again.
 * - The rationale disappearing after it was there before ([rationaleBefore]) means the second
 *   "Don't allow" → permanent.
 * - A dialog closed without an answer (back, tap outside) leaves no rationale before or after → it
 *   will ask again. (No timing heuristics: notifications-push §6.2.)
 */
object PermissionDecision {
    fun outcome(granted: Boolean, dialogShown: Boolean, rationaleBefore: Boolean, rationaleAfter: Boolean): PermissionOutcome = when {
        granted -> PermissionOutcome.Granted
        !dialogShown -> PermissionOutcome.PermanentlyDenied
        rationaleAfter -> PermissionOutcome.Denied
        rationaleBefore -> PermissionOutcome.PermanentlyDenied
        else -> PermissionOutcome.Denied
    }

    /**
     * Whether [permission] is a runtime permission on API [sdk]. `POST_NOTIFICATIONS` exists from
     * Android 13; before that notifications are on unless the user turned them off in Settings.
     */
    @SuppressLint("InlinedApi") // A string constant compared on every API level.
    fun isRuntimePermission(permission: String, sdk: Int): Boolean =
        !(permission == Manifest.permission.POST_NOTIFICATIONS && sdk < Build.VERSION_CODES.TIRAMISU)
}

/**
 * A runtime-permission request, ready to fire from a tap (00-plan §1.7.12, `ui/permissions`). iOS
 * asks inside the feature (`AVCaptureDevice.requestAccess`, `VoiceRecorder.start()`; contacts §6,
 * conversation-compose-media §4.7); Android shows the system dialog from here.
 *
 * Human: Already allowed → answers at once. Otherwise the system dialog shows; [onResult] then says
 * whether it was granted and, when not, whether Android will still ask next time — if
 * `permanentlyDenied`, the screen should offer Settings ([openAppSettings]; e.g. the "Microphone
 * access is off" toast with "Settings", design u3il8T).
 *
 * Agent: Call the returned function from a click. Requests do not overlap (a second call while
 * the dialog is up does nothing). The request survives the activity being recreated behind the
 * dialog (rememberSaveable, booleans only). `POST_NOTIFICATIONS` below Android 13 reports the
 * system's notification switch instead (off → `permanentlyDenied`: only Settings helps). Pick
 * SDK-specific permissions (`READ_MEDIA_IMAGES` vs `READ_EXTERNAL_STORAGE`) before calling.
 */
@Composable
fun rememberPermissionRequest(
    permission: String,
    onResult: (granted: Boolean, permanentlyDenied: Boolean) -> Unit,
): () -> Unit {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val currentOnResult by rememberUpdatedState(onResult)
    var inFlight by rememberSaveable(permission) { mutableStateOf(false) }
    var rationaleBefore by rememberSaveable(permission) { mutableStateOf(false) }
    var paused by rememberSaveable(permission) { mutableStateOf(false) }

    // The system dialog is an activity over ours: our ON_PAUSE proves it was shown.
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_PAUSE && inFlight) paused = true
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val outcome = PermissionDecision.outcome(
            granted = granted,
            dialogShown = paused,
            rationaleBefore = rationaleBefore,
            rationaleAfter = context.findActivity()?.shouldShowRequestPermissionRationale(permission) == true,
        )
        inFlight = false
        paused = false
        currentOnResult(outcome == PermissionOutcome.Granted, outcome == PermissionOutcome.PermanentlyDenied)
    }
    return remember(launcher, permission, context) {
        request@{
            if (!PermissionDecision.isRuntimePermission(permission, Build.VERSION.SDK_INT)) {
                val on = NotificationManagerCompat.from(context).areNotificationsEnabled()
                currentOnResult(on, !on)
                return@request
            }
            if (isPermissionGranted(context, permission)) {
                currentOnResult(true, false)
                return@request
            }
            if (inFlight) return@request
            rationaleBefore = context.findActivity()?.shouldShowRequestPermissionRationale(permission) == true
            paused = false
            inFlight = true
            try {
                launcher.launch(permission)
            } catch (_: ActivityNotFoundException) {
                inFlight = false
                currentOnResult(false, true)
            }
        }
    }
}

/** Whether [permission] is granted now. */
fun isPermissionGranted(context: Context, permission: String): Boolean =
    ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

/**
 * Whether [permission] is granted, re-read every time the screen resumes — after the user comes
 * back from Settings or a system dialog (iOS re-reads on `scenePhase` active, NSV:58-62).
 */
@Composable
fun rememberPermissionGranted(permission: String): State<Boolean> {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val granted = remember(permission, context) { mutableStateOf(isPermissionGranted(context, permission)) }
    DisposableEffect(lifecycleOwner, permission, context) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) granted.value = isPermissionGranted(context, permission)
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    return granted
}

/**
 * Opens this app's page in system Settings (where permissions are switched back on) — iOS
 * `UIApplication.openSettingsURLString`; `ACTION_APPLICATION_DETAILS_SETTINGS` (contacts §6).
 */
fun openAppSettings(context: Context) {
    val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
    startSettings(context, intent)
}

/**
 * Opens this app's notification settings, or one channel's when [channelId] is given (a blocked
 * channel) — iOS `openNotificationSettingsURLString` (settings-lock §6.1, notifications-push §6.2).
 * Falls back to the app's details page where a device has no such screen.
 */
fun openNotificationSettings(context: Context, channelId: String? = null) {
    val intent = if (channelId != null) {
        Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
            .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
            .putExtra(Settings.EXTRA_CHANNEL_ID, channelId)
    } else {
        Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
    }
    if (!startSettings(context, intent)) openAppSettings(context)
}

/** Starts a Settings screen; false when the device has none for [intent]. */
private fun startSettings(context: Context, intent: Intent): Boolean {
    if (context.findActivity() == null) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    return try {
        context.startActivity(intent)
        true
    } catch (_: ActivityNotFoundException) {
        false
    }
}

/** The activity behind a (possibly wrapped) context, if any. */
internal fun Context.findActivity(): Activity? {
    var current: Context? = this
    while (current is ContextWrapper) {
        if (current is Activity) return current
        current = current.baseContext
    }
    return null
}
