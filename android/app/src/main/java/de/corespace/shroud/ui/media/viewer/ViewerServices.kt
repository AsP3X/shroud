package de.corespace.shroud.ui.media.viewer

import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipDescription
import android.content.ContentResolver
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.PersistableBundle
import de.corespace.shroud.AppContainer
import de.corespace.shroud.core.media.MediaImageSource
import de.corespace.shroud.core.media.share.SaveOutcome
import java.util.UUID

/**
 * What the photo viewer reads from core (R4: UI-side port, forwarded 1:1 by
 * [ContainerViewerServices]). Tests pass a fake.
 */
internal interface ViewerServices {
    /** The decrypted bytes on this phone, or null (`messaging.controller.mediaBytes`, K1). */
    suspend fun mediaBytes(messageId: UUID): ByteArray?

    /** A screen-sized decode, orientation applied; null when it does not decode (`images.mediaImages`, K1). */
    suspend fun decodePreview(source: MediaImageSource, maxEdge: Int): Bitmap?

    /** An in-memory `content://` grant over the photo, or null when it is not loaded (`media.sharing`, K10). */
    suspend fun shareUri(messageId: UUID): Uri?

    /** Drops every share grant (`media.sharing.revokeAll`, K10); a no-op when nothing was shared yet. */
    fun revokeShares()

    /** Save to Gallery (`media.sharing.saveToGallery`, K10). */
    suspend fun saveToGallery(messageId: UUID): SaveOutcome
}

/** [ViewerServices] on the app's modules, 1:1 (R4). */
internal class ContainerViewerServices(private val container: AppContainer) : ViewerServices {
    override suspend fun mediaBytes(messageId: UUID): ByteArray? = container.messaging.controller.mediaBytes(messageId)

    override suspend fun decodePreview(source: MediaImageSource, maxEdge: Int): Bitmap? =
        container.images.mediaImages.decodePreview(source, maxEdge)

    override suspend fun shareUri(messageId: UUID): Uri? = container.media.sharing.shareUri(messageId)

    // `sharingIfBuilt`: closing a viewer that never shared must not build the sharing module just to revoke.
    override fun revokeShares() {
        container.media.sharingIfBuilt?.revokeAll()
    }

    override suspend fun saveToGallery(messageId: UUID): SaveOutcome = container.media.sharing.saveToGallery(messageId)
}

/**
 * The share sheet and the clipboard for a [ViewerServices.shareUri] grant (conversation-compose-media
 * §18.5; K10 leaves the `Intent` to the UI).
 */
internal object MediaShareIntents {
    /** Used when the provider does not report a type. */
    const val FALLBACK_MIME = "image/*"

    /**
     * `ACTION_SEND` of [uri] with a read grant: the grant rides on the stream extra and on the
     * clip data, which is what carries it through the chooser to the target.
     */
    fun send(uri: Uri, mime: String?): Intent = Intent(Intent.ACTION_SEND).apply {
        type = mime ?: FALLBACK_MIME
        putExtra(Intent.EXTRA_STREAM, uri)
        clipData = ClipData.newRawUri(null, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }

    /** The system share sheet over [send] (iOS `UIActivityViewController`). */
    fun chooser(send: Intent): Intent = Intent.createChooser(send, null).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)

    /**
     * The clipboard entry for a copied photo: a content URI (Android copies images only that way),
     * marked sensitive on Android 13+ so the system's clipboard preview does not show it.
     */
    @SuppressLint("InlinedApi") // A string constant, only put on API 33+ (guarded by sdk).
    fun clip(resolver: ContentResolver, uri: Uri, sdk: Int = Build.VERSION.SDK_INT): ClipData =
        ClipData.newUri(resolver, CLIP_LABEL, uri).also { clip ->
            if (sdk >= Build.VERSION_CODES.TIRAMISU) {
                clip.description.extras = PersistableBundle().apply { putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true) }
            }
        }

    /**
     * Android 13+ confirms a copy itself (the clipboard editor overlay); the app adds its own
     * "Copied" only below that, so the user is not told twice.
     */
    fun showsCopiedBanner(sdk: Int = Build.VERSION.SDK_INT): Boolean = sdk < Build.VERSION_CODES.TIRAMISU

    const val CLIP_LABEL = "Photo"
}
