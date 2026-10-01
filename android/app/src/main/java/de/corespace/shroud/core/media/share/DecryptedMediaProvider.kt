package de.corespace.shroud.core.media.share

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import java.io.FileNotFoundException

/**
 * Hands decrypted media to a share target on demand (conversation-compose-media §18.5, Q6;
 * `exported=false`, `grantUriPermissions=true`, authority `<applicationId>.media`): the receiving
 * app reads through a one-off URI grant, and no decrypted copy is left on disk beyond the
 * short-lived `cacheDir/shroud-*` share buffers (00-plan §1.5).
 *
 * Providers are created at process start, before `ShroudApplication.onCreate`: [onCreate] must
 * never touch the container.
 *
 * Manifest stub created by W0-A; W3-MEDIA-VIEW replaces the body. Until then it serves nothing.
 */
class DecryptedMediaProvider : ContentProvider() {
    override fun onCreate(): Boolean = true

    override fun getType(uri: Uri): String? = null

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor? = null

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor? = throw FileNotFoundException("no shared media")

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0

    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0
}
