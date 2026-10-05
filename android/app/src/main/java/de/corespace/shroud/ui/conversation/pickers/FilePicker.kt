package de.corespace.shroud.ui.conversation.pickers

import android.content.ActivityNotFoundException
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import de.corespace.shroud.core.media.files.FileTypes

/** The system document picker for the attach sheet's File option (docs/file-sharing.md §7 "Attach"). */
@Stable
class FilePicker internal constructor(private val onOpen: () -> Unit) {
    fun open() = onOpen()
}

/**
 * Registers `ACTION_OPEN_DOCUMENT` (multiple, the §4 MIME types — `FileTypes.pickerMimeTypes`) and
 * returns the opener. [onResult] gets the picked URIs in the order picked, an empty list when the
 * picker was closed. No grant is persisted: the URIs are read once, while the send copies them into
 * the sealed cache. Without a document UI the result is empty.
 */
@Composable
fun rememberFilePicker(onResult: (List<Uri>) -> Unit): FilePicker {
    val currentOnResult by rememberUpdatedState(onResult)
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris -> currentOnResult(uris) }
    return remember(launcher) {
        FilePicker {
            try {
                launcher.launch(FileTypes.pickerMimeTypes)
            } catch (_: ActivityNotFoundException) {
                currentOnResult(emptyList())
            }
        }
    }
}
