package de.corespace.shroud.ui.conversation.pickers

import android.content.ActivityNotFoundException
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import de.corespace.shroud.ui.media.MAX_MEDIA_PER_SEND

/**
 * The system photo picker, ready to open from the composer (iOS `.photosPicker`,
 * `ConversationView.swift:405-421`; conversation-compose-media §8.1). One open request at a time.
 */
@Stable
class MediaPicker internal constructor(private val onOpen: (PickerRequest) -> Unit) {
    /** Opens the picker for [request]; ignored while one is already up. */
    fun open(request: PickerRequest) = onOpen(request)
}

/** One opening, by identity (the same request twice is two openings). */
private class PickerLaunch(val request: PickerRequest) {
    var launched = false
}

/**
 * Registers the picker's launchers and returns the opener. [onResult] gets the picked URIs in the
 * order picked — an empty list when the picker was closed without a pick.
 *
 * Android: `PickVisualMedia` for one item (the multiple-item contract refuses fewer than two),
 * `PickMultipleVisualMedia(maxItems)` otherwise. The launcher keeps the newest contract, so the
 * multiple contract is rebuilt for the request's room before it launches. No media capabilities
 * are passed, so the picker hands over the original file, never a transcoded one. On API 30–32
 * without the photo picker's system update, the AndroidX contract falls back to the document UI
 * (`ACTION_OPEN_DOCUMENT`): the Play-services backport entry of the AndroidX docs is a
 * `com.google.android.gms` manifest component, which the Google-free build refuses (decision record 8).
 */
@Composable
fun rememberMediaPicker(onResult: (List<Uri>) -> Unit): MediaPicker {
    val currentOnResult by rememberUpdatedState(onResult)
    var launch by remember { mutableStateOf<PickerLaunch?>(null) }
    val multipleMax = launch?.request?.maxItems?.takeIf { it >= 2 } ?: MAX_MEDIA_PER_SEND
    val multipleContract = remember(multipleMax) { ActivityResultContracts.PickMultipleVisualMedia(multipleMax) }
    val multiple = rememberLauncherForActivityResult(multipleContract) { uris ->
        launch = null
        currentOnResult(uris)
    }
    val single = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        launch = null
        currentOnResult(listOfNotNull(uri))
    }
    // Runs after the composition that registered the contract for this request's room.
    LaunchedEffect(launch) {
        val pending = launch ?: return@LaunchedEffect
        if (pending.launched) return@LaunchedEffect
        pending.launched = true
        try {
            if (pending.request.isSingle) {
                single.launch(pending.request.visualMediaRequest())
            } else {
                multiple.launch(pending.request.visualMediaRequest())
            }
        } catch (_: ActivityNotFoundException) {
            launch = null
            currentOnResult(emptyList())
        }
    }
    return remember {
        MediaPicker { request ->
            if (launch == null) launch = PickerLaunch(request)
        }
    }
}
