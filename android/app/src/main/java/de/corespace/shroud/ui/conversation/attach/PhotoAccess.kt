package de.corespace.shroud.ui.conversation.attach

import android.Manifest
import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import de.corespace.shroud.core.media.library.LibraryAccess

/**
 * What the Recents strip may show (conversation-compose-media §7.4; P9 decided: images only,
 * `READ_MEDIA_IMAGES` + `READ_MEDIA_VISUAL_USER_SELECTED`). iOS asks for Photos access when the sheet
 * opens if it was never decided and shows the newest twelve images, or only the chosen ones under
 * limited access (`ChatAttachSheet.swift:183-185, 225-270, 315-325`). The permission only feeds
 * this strip: "Photos" opens the system picker, which needs none (Platform Notes uROl2).
 */
enum class PhotoAccess {
    /** Never asked: the sheet asks as it opens (design lsqsv). */
    NotAsked,

    /** Every image: "RECENTS" + "All Photos ›". */
    Full,

    /** Android 14+ "Select photos": "SELECTED PHOTOS" + "Manage ›" (design Sy9qO). */
    Partial,

    /** Asked and refused: the "Recent photos are hidden" card (design vZsy8). */
    Denied,
    ;

    /** The strip lists images. */
    val showsPhotos: Boolean get() = this == Full || this == Partial
}

/** The permission rules of [PhotoAccess], pure (per API level). */
@SuppressLint("InlinedApi") // String constants compared on every API level; requests pick by SDK.
object PhotoAccessRules {
    /** What the sheet requests: images (+ the 14+ partial grant) on 33+, the storage permission on 30–32 (manifest `maxSdkVersion=32`). */
    fun permissions(sdk: Int): Array<String> = when {
        sdk >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE -> arrayOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)
        sdk >= Build.VERSION_CODES.TIRAMISU -> arrayOf(Manifest.permission.READ_MEDIA_IMAGES)
        else -> arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
    }

    /**
     * The strip's state from core's grant (K4 `PhotoLibrary.access()`, which reads the permissions
     * per API level) and whether the sheet asked before: no access reads as refused once asked (a
     * refusal looks the same as "never asked" to Android), else the sheet asks as it opens.
     */
    fun access(library: LibraryAccess, requestedBefore: Boolean): PhotoAccess = when (library) {
        LibraryAccess.Full -> PhotoAccess.Full
        LibraryAccess.Partial -> PhotoAccess.Partial
        LibraryAccess.None -> if (requestedBefore) PhotoAccess.Denied else PhotoAccess.NotAsked
    }

    /** The Recents header (`ChatAttachSheet.swift:45`; design Sy9qO). */
    fun header(access: PhotoAccess): String = if (access == PhotoAccess.Partial) "SELECTED PHOTOS" else "RECENTS"

    /** The header's link: the picker, or on partial access the system's selection again. */
    fun linkTitle(access: PhotoAccess): String = if (access == PhotoAccess.Partial) "Manage" else "All Photos"
}

/**
 * The photo permission request of the strip: [onResult] says whether anything was granted and
 * whether the system showed its dialog at all — it answers at once, without one, once it no longer
 * asks (the "Allow Access" card then opens Settings instead; §7.4). The dialog is an activity over
 * ours: our `ON_PAUSE` proves it was shown (the rule of `ui/permissions`, without timing guesses).
 */
@Composable
internal fun rememberPhotoAccessRequest(onResult: (anyGranted: Boolean, dialogShown: Boolean) -> Unit): () -> Unit {
    val lifecycleOwner = LocalLifecycleOwner.current
    val currentOnResult by rememberUpdatedState(onResult)
    var inFlight by rememberSaveable { mutableStateOf(false) }
    var paused by rememberSaveable { mutableStateOf(false) }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_PAUSE && inFlight) paused = true
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { results ->
        val shown = paused
        inFlight = false
        paused = false
        currentOnResult(results.values.any { it }, shown)
    }
    return remember(launcher) {
        request@{
            if (inFlight) return@request
            inFlight = true
            paused = false
            try {
                launcher.launch(PhotoAccessRules.permissions(Build.VERSION.SDK_INT))
            } catch (_: ActivityNotFoundException) {
                inFlight = false
                currentOnResult(false, false)
            }
        }
    }
}
