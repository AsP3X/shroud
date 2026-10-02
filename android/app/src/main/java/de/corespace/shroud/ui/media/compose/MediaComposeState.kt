package de.corespace.shroud.ui.media.compose

import android.graphics.Bitmap
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import de.corespace.shroud.core.media.MediaComposeQuality
import de.corespace.shroud.core.media.edit.MediaEdits
import de.corespace.shroud.ui.media.ComposeDraft
import de.corespace.shroud.ui.media.PickedPhoto
import java.util.UUID

/** An album holds at most ten photos (Telegram's cap, `ConversationView.swift:667-668`). */
internal const val MAX_PHOTOS_PER_SEND = 10

/** The editor open over the compose screen (`MediaComposeOverlay.swift:119-122`). */
internal enum class ComposeEditor { Crop, Draw, Text }

/**
 * The photo compose screen's state (conversation-compose-media §9.2; `MediaComposeOverlay.swift:94-122,
 * 750-809`). Edits and rendered previews are keyed by photo identity, never by index, so a photo
 * leaving the strip cannot hand its crop to a neighbour. The host owns the photo list; [photos] is
 * updated from it on every recomposition.
 */
@Stable
internal class MediaComposeState(draft: ComposeDraft) {
    var photos: List<PickedPhoto> by mutableStateOf(draft.photos.take(MAX_PHOTOS_PER_SEND))
        private set
    var caption by mutableStateOf(draft.caption)
    var quality by mutableStateOf(draft.quality)
        private set
    var selectedId by mutableStateOf<UUID?>(null)
        private set
    val edits = mutableStateMapOf<UUID, MediaEdits>()

    /** Edits baked into the screen-sized preview, by photo id. */
    val rendered = mutableStateMapOf<UUID, Bitmap>()
    var activeEditor by mutableStateOf<ComposeEditor?>(null)

    /** The draw / text editor's canvas, rendered before the editor rises. */
    var editorBase by mutableStateOf<Bitmap?>(null)
    var showFilters by mutableStateOf(false)
    var confirmClearEdits by mutableStateOf(false)

    /** The ids [photos] was last set from; compared without reading state, so a sync in composition costs nothing. */
    private var syncedIds: List<UUID> = photos.map { it.id }

    /**
     * The host's photos changed (Add, Remove): drop what belonged to photos that left (`syncEdits`,
     * `:776-782`). Called on every composition of the screen; writes only when the list changed.
     */
    fun syncPhotos(next: List<PickedPhoto>) {
        val capped = next.take(MAX_PHOTOS_PER_SEND)
        val ids = capped.map { it.id }
        if (ids == syncedIds) return
        syncedIds = ids
        photos = capped
        val live = capped.mapTo(HashSet()) { it.id }
        edits.keys.retainAll(live)
        rendered.keys.retainAll(live)
    }

    /** Index of the photo on screen: the chosen one, or the first (`selection`, `:114-117`). */
    val selection: Int get() = photos.indexOfFirst { it.id == selectedId }.takeIf { it >= 0 } ?: 0

    val current: PickedPhoto? get() = photos.getOrNull(selection)

    val currentEdits: MediaEdits get() = current?.let { edits[it.id] } ?: MediaEdits.Identity

    /** What the big photo shows: the rendered edit once ready, the raw preview until then (`:754-758`). */
    val displayed: Bitmap? get() = current?.let { rendered[it.id] ?: it.preview }

    /** Add stays usable until the album is full (`ConversationView.swift:534-537`). */
    val canAddMore: Boolean get() = photos.size < MAX_PHOTOS_PER_SEND

    fun select(id: UUID) {
        selectedId = id
    }

    /** The current photo's edits become [value] (`setEdits`, `:765-768`). */
    fun setEdits(value: MediaEdits) {
        val photo = current ?: return
        edits[photo.id] = value
    }

    fun updateEdits(transform: (MediaEdits) -> MediaEdits) = setEdits(transform(currentEdits))

    /**
     * A strip photo is removed: the one on screen hands over to its right-hand neighbour, or the left
     * one at the end (`removePhoto(at:)`, `:391-399`). The host removes it from the draft.
     */
    fun prepareRemoval(index: Int) {
        if (index == selection) selectedId = (photos.getOrNull(index + 1) ?: photos.getOrNull(index - 1))?.id
    }

    /** The quality badge toggles Original ⇄ HD and says what that means (`:686-695`). */
    fun toggleQuality(): String {
        quality = if (quality == MediaComposeQuality.Original) MediaComposeQuality.HD else MediaComposeQuality.Original
        return qualityBanner(quality)
    }

    /**
     * One [MediaEdits] per photo, in order, for the send (`:495`). Identity edits go out as the
     * empty value (a filter switched back to Original keeps no stray intensity), so an untouched
     * photo still ships byte for byte.
     */
    fun sendEdits(): List<MediaEdits> = photos.map { photo ->
        val value = edits[photo.id]
        if (value == null || value.isIdentity) MediaEdits.Identity else value
    }

    /** The edits an annotation editor's canvas bakes in: everything but the stickers, the markup only for Text (`:677-684`). */
    fun annotationEdits(includingDrawing: Boolean): MediaEdits =
        currentEdits.copy(drawing = if (includingDrawing) currentEdits.drawing else null, texts = emptyList())

    companion object {
        /** The banner after a quality tap (`:693-695`). */
        fun qualityBanner(quality: MediaComposeQuality): String = when (quality) {
            MediaComposeQuality.Original -> "Original quality · location removed"
            MediaComposeQuality.HD -> "HD — smaller file"
        }

        /** "Send {n} photos" for an album, "Send" for one (`:505`). */
        fun sendLabel(count: Int): String = if (count > 1) "Send $count photos" else "Send"

        /** "{n} photo" / "{n} photos" (`:544`). */
        fun countLabel(count: Int): String = "$count photo${if (count == 1) "" else "s"}"
    }
}
