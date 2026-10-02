package de.corespace.shroud.di

import de.corespace.shroud.AppContainer
import de.corespace.shroud.AppModule
import de.corespace.shroud.core.media.EnvelopePreview
import de.corespace.shroud.core.media.LocalMediaCache
import de.corespace.shroud.core.media.LocalMediaStore
import de.corespace.shroud.core.media.MediaTransferService
import de.corespace.shroud.core.media.MediaTransfers
import de.corespace.shroud.core.media.SealedMediaDataSource
import de.corespace.shroud.core.media.SealedMediaDataSourceMdr
import de.corespace.shroud.core.media.library.MediaStorePhotoLibrary
import de.corespace.shroud.core.media.library.PhotoLibrary
import java.io.File
import java.util.UUID

/**
 * Media transfers and the local media cache (00-plan §1.7.9, C7). Owner: W2-MEDIA-STORE.
 *
 * - [localMedia] — the SHRM1 media cache, `noBackupFilesDir/shroud/media/` (plan §1.5), the
 *   [LocalMediaStore] of the messaging engines; it follows the chat lock through
 *   `KeysModule.sealedLocalState` and stops writing while [AppContainer.storageSeal] is sealed.
 * - [transfers] — sealed uploads and downloads ([MediaTransfers]) on the one `ShroudApi`, with
 *   the downloaded ciphertext passing through `KeysModule.sensitiveTempFiles`.
 * - [dataSourceFactory] / [metadataSource] — players and retrievers read media without
 *   decrypted files (`SealedMediaDataSource`, `SealedMediaDataSourceMdr`).
 * - `EnvelopePreview.chatPreviewJpeg`, `ByteCountLabel.format` and `MediaEnvelopeBudget` are
 *   stateless objects; [chatPreviewJpeg] is here for packages that take it as a function.
 *
 * Nobody else constructs these classes (00-plan §2.0 rule 3). The wipe deletes [mediaDirectory]
 * (settings-lock §14; the INT package forwards `localMedia.clearAll()` / `inventory()`).
 */
class MediaModule(container: AppContainer) : AppModule(container) {
    /** `noBackupFilesDir/shroud/media` — the location the wipe's media step deletes. */
    val mediaDirectory: File by lazy { File(container.appContext.noBackupFilesDir, MEDIA_DIR) }

    val localMedia: LocalMediaCache by lazy {
        LocalMediaCache(mediaDirectory, container.keys.sealedLocalState, container.storageSeal)
    }

    val transfers: MediaTransferService by lazy {
        MediaTransferService(container.net.api, localMedia, container.keys.sensitiveTempFiles)
    }

    /** The phone's photos for the recents strip (K4). Images only; empty when access is none. */
    val photoLibrary: PhotoLibrary by lazy { MediaStorePhotoLibrary(container.appContext) }

    /** ExoPlayer source factory for [messageId]'s sealed media (pair it with `SealedMediaDataSource.mediaItem()`). */
    fun dataSourceFactory(messageId: UUID): SealedMediaDataSource.Factory = SealedMediaDataSource.Factory(localMedia, messageId)

    /** `MediaMetadataRetriever` / `MediaExtractor` source for [messageId]; null when there is none or chats are locked. */
    fun metadataSource(messageId: UUID): SealedMediaDataSourceMdr? = SealedMediaDataSourceMdr.open(localMedia, messageId)

    /** `th` for encoded image bytes (`EnvelopePreview.chatPreviewJpeg`); blocking, call it on `Dispatchers.Default`. */
    fun chatPreviewJpeg(image: ByteArray): ByteArray? = EnvelopePreview.chatPreviewJpeg(image)

    companion object {
        /** Under `noBackupFilesDir` (plan §1.5). */
        const val MEDIA_DIR = "shroud/media"
    }
}
