package de.corespace.shroud.ui.media.viewer

import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipDescription
import android.content.Intent
import android.graphics.Bitmap
import android.os.Build
import android.os.PersistableBundle
import de.corespace.shroud.AppContainer
import de.corespace.shroud.core.media.MediaImageSource
import de.corespace.shroud.core.media.share.SaveOutcome
import de.corespace.shroud.core.media.share.ShareTarget
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

    /**
     * An in-memory `content://` grant over the photo and the MIME type stored with it, or null when
     * it is not loaded (`media.sharing.shareTarget`, K10, gap #15).
     */
    suspend fun shareTarget(messageId: UUID): ShareTarget?

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

    override suspend fun shareTarget(messageId: UUID): ShareTarget? = container.media.sharing.shareTarget(messageId)

    // `sharingIfBuilt`: closing a viewer that never shared must not build the sharing module just to revoke.
    override fun revokeShares() {
        container.media.sharingIfBuilt?.revokeAll()
    }

    override suspend fun saveToGallery(messageId: UUID): SaveOutcome = container.media.sharing.saveToGallery(messageId)
}

/**
 * The share sheet and the clipboard for a [ViewerServices.shareTarget] grant (conversation-compose-media
 * §18.5; K10 leaves the `Intent` to the UI). The MIME type is the one K10 stored with the grant, so
 * nothing here asks a `ContentResolver`.
 */
internal object MediaShareIntents {
    /**
     * `ACTION_SEND` of [target] with a read grant: the grant rides on the stream extra and on the
     * clip data, which is what carries it through the chooser to the target.
     */
    fun send(target: ShareTarget): Intent = Intent(Intent.ACTION_SEND).apply {
        type = target.mime
        putExtra(Intent.EXTRA_STREAM, target.uri)
        clipData = ClipData.newRawUri(null, target.uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }

    /**
     * `ACTION_VIEW` of a checked file grant (docs/file-sharing.md §7): the app the system picks for
     * [ShareTarget.mime] reads it through the provider with a read grant, nothing else.
     */
    fun view(target: ShareTarget): Intent = Intent(Intent.ACTION_VIEW).apply {
        setDataAndType(target.uri, target.mime)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }

    /** The system share sheet over [send] (iOS `UIActivityViewController`). */
    fun chooser(send: Intent): Intent = Intent.createChooser(send, null).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)

    /**
     * The clipboard entry for a copied photo: a content URI of [ShareTarget.mime] (Android copies
     * images only that way), marked sensitive on Android 13+ so the system's clipboard preview does
     * not show it.
     */
    @SuppressLint("InlinedApi") // A string constant, only put on API 33+ (guarded by sdk).
    fun clip(target: ShareTarget, sdk: Int = Build.VERSION.SDK_INT): ClipData =
        ClipData(CLIP_LABEL, arrayOf(target.mime), ClipData.Item(target.uri)).also { clip ->
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
