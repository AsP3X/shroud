package de.corespace.shroud.di

import android.Manifest
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import de.corespace.shroud.AppContainer
import de.corespace.shroud.AppModule
import de.corespace.shroud.core.media.EnvelopePreview
import de.corespace.shroud.core.media.LocalMediaCache
import de.corespace.shroud.core.media.LocalMediaStore
import de.corespace.shroud.core.media.MediaTransferService
import de.corespace.shroud.core.media.MediaTransfers
import de.corespace.shroud.core.media.SealedMediaDataSource
import de.corespace.shroud.core.media.SealedMediaDataSourceMdr
import de.corespace.shroud.core.media.capture.CameraCapture
import de.corespace.shroud.core.media.capture.CameraXSession
import de.corespace.shroud.core.media.capture.ShroudCameraCapture
import de.corespace.shroud.core.media.library.MediaStorePhotoLibrary
import de.corespace.shroud.core.media.library.PhotoLibrary
import de.corespace.shroud.core.media.files.FileIntake
import de.corespace.shroud.core.media.share.FileSharing
import de.corespace.shroud.core.media.share.MediaSharing
import de.corespace.shroud.core.media.share.MemoryMediaSharing
import de.corespace.shroud.core.messaging.MessageArtifactSinks
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
 * - [sharing] — in-memory share grants and MediaStore saves. [sharingIfBuilt] is null until the
 *   first read, so a wipe or a lock does not build it just to revoke. [fileSharing] is the same
 *   object's file half; [fileIntake] reads picked files.
 * - [camera] — CameraX capture into `cacheDir/shroud-*` (never MediaStore).
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

    private val sharingLazy = lazy {
        MemoryMediaSharing(
            context = container.appContext,
            load = { id -> localMedia.readAll(id) },
            clock = container.clock,
            openReader = { id -> localMedia.openReader(id) },
        )
    }

    /** Decrypted bytes for the share sheet, and Save to Gallery. */
    val sharing: MediaSharing by sharingLazy

    /**
     * Open, share and Save to Downloads of files (docs/file-sharing.md §7, §8): the same grants as
     * [sharing], so [MediaSharing.revokeAll] on a lock or a wipe drops these too.
     */
    val fileSharing: FileSharing get() = sharingLazy.value

    /** What the file picker handed back, named, sized and checked (docs/file-sharing.md §2, §4, §5). */
    val fileIntake: FileIntake by lazy { FileIntake(container.appContext.contentResolver) }

    /** [sharing] when something already built it. A lock or a wipe must not construct it just to revoke. */
    val sharingIfBuilt: MediaSharing? get() = if (sharingLazy.isInitialized()) sharing else null

    /**
     * For `MessagingController.registerArtifactSink`: a purged message (deleted for me or for
     * everyone, its chat deleted, a tombstone) loses its share and file grants at once, open
     * descriptors included (docs/file-sharing.md §8). Never builds [sharing] just to revoke.
     */
    val shareArtifactSink: MessageArtifactSinks = object : MessageArtifactSinks {
        override fun onPurged(messageIds: Collection<UUID>) {
            sharingIfBuilt?.revoke(messageIds)
        }
    }

    /** Photo and video capture into [de.corespace.shroud.core.storage.SensitiveTempFiles]. */
    val camera: CameraCapture by lazy {
        ShroudCameraCapture(
            temps = container.keys.sensitiveTempFiles,
            unlocked = { container.keys.cryptoController.isUnlocked },
            session = CameraXSession(container.appContext),
            uriFor = { file ->
                FileProvider.getUriForFile(
                    container.appContext,
                    container.appContext.packageName + ".cache",
                    file,
                )
            },
            audioGranted = {
                ContextCompat.checkSelfPermission(container.appContext, Manifest.permission.RECORD_AUDIO) ==
                    PackageManager.PERMISSION_GRANTED
            },
        )
    }

    companion object {
        /** Under `noBackupFilesDir` (plan §1.5). */
        const val MEDIA_DIR = "shroud/media"
    }
}
