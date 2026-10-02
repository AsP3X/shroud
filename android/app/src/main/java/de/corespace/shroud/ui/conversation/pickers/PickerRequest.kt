package de.corespace.shroud.ui.conversation.pickers

import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import de.corespace.shroud.ui.media.MAX_MEDIA_PER_SEND
import kotlin.math.max

/** What the system photo picker may offer (iOS `PHPickerFilter`, `ConversationView.swift:677-682`). */
enum class PickerFilter { ImageAndVideo, ImageOnly, VideoOnly }

/**
 * One opening of the system photo picker (conversation-compose-media §8.1, §8.4): at most
 * [maxItems] items of [filter], in the order the user picked them. The picker needs no permission
 * and hands back the original file (iOS `preferredItemEncoding: .current`: never a silent HEIC → JPEG);
 * the URIs stay readable for the life of the process — nothing is copied or persisted.
 */
data class PickerRequest(val maxItems: Int, val filter: PickerFilter) {
    /** `PickVisualMedia` for a single item: the multiple-item contract refuses fewer than two. */
    val isSingle: Boolean get() = maxItems <= 1

    /** The request the picker contracts take. */
    fun visualMediaRequest(): PickVisualMediaRequest = PickVisualMediaRequest(
        when (filter) {
            PickerFilter.ImageAndVideo -> ActivityResultContracts.PickVisualMedia.ImageAndVideo
            PickerFilter.ImageOnly -> ActivityResultContracts.PickVisualMedia.ImageOnly
            PickerFilter.VideoOnly -> ActivityResultContracts.PickVisualMedia.VideoOnly
        },
    )

    companion object {
        /** "Photos" in the attach sheet: a fresh pick of up to ten photos and videos (`ConversationView.swift:410-414`). */
        fun newPick(): PickerRequest = PickerRequest(MAX_MEDIA_PER_SEND, PickerFilter.ImageAndVideo)

        /**
         * "Add" on a compose screen (`openPickerToAppend`, `ConversationView.swift:670-689`): the room
         * left in that send (at least one) and only that screen's kind — a photo cannot join a video
         * send, and one picked there would open a second compose screen hidden under it.
         */
        fun append(staged: Int, videoComposeOpen: Boolean, photoComposeOpen: Boolean): PickerRequest = PickerRequest(
            maxItems = max(1, MAX_MEDIA_PER_SEND - staged),
            filter = when {
                videoComposeOpen -> PickerFilter.VideoOnly
                photoComposeOpen -> PickerFilter.ImageOnly
                else -> PickerFilter.ImageAndVideo
            },
        )
    }
}
