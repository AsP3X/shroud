package de.corespace.shroud.core.media.share

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import java.io.FileNotFoundException
import java.io.IOException

/**
 * Hands decrypted media to a share target on demand (conversation-compose-media §18.5, Q6;
 * `exported=false`, `grantUriPermissions=true`, authority `<applicationId>.media`). The bytes stay
 * in [SharedMediaRegistry]; [openFile] pipes them out and writes nothing to disk.
 *
 * Providers are created at process start, before `ShroudApplication.onCreate`: [onCreate] must
 * never touch the container. [MediaSharing.revokeAll] clears the registry so a later open fails.
 */
class DecryptedMediaProvider : ContentProvider() {
    override fun onCreate(): Boolean = true

    override fun getType(uri: Uri): String? = SharedMediaRegistry.open(uri.lastPathSegment)?.mime

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor? = null

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        if (mode.indexOf('w') >= 0 || mode.indexOf('a') >= 0) throw FileNotFoundException("shared media is read only")
        val item = SharedMediaRegistry.open(uri.lastPathSegment) ?: throw FileNotFoundException("no shared media")
        val pipe = try {
            ParcelFileDescriptor.createPipe()
        } catch (_: IOException) {
            throw FileNotFoundException("no shared media")
        }
        try {
            feed(pipe[1], item.bytes)
        } catch (e: FileNotFoundException) {
            closeQuietly(pipe[0])
            throw e
        }
        return pipe[0]
    }

    /**
     * A kernel pipe blocks once its buffer fills, and the reader has not started yet, so a large
     * photo is written with [OsConstants.O_NONBLOCK] and only the tail moves to [SHARE_THREAD].
     * A pipe that is really a file (unit tests) accepts the whole array before this returns, so
     * the read side does not observe an empty file.
     */
    private fun feed(writeEnd: ParcelFileDescriptor, bytes: ByteArray) {
        if (bytes.isEmpty()) {
            closeQuietly(writeEnd)
            return
        }
        // Robolectric's pipe is two handles on one file: a reader that starts at once sees a
        // length of 0 unless the write finishes first. A real pipe fails lseek with ESPIPE.
        if (isFileBacked(writeEnd) || !markNonBlocking(writeEnd)) {
            writeAll(writeEnd, bytes, 0)
            return
        }
        var offset = 0
        try {
            while (offset < bytes.size) {
                val wrote = Os.write(writeEnd.fileDescriptor, bytes, offset, bytes.size - offset)
                if (wrote <= 0) break
                offset += wrote
            }
        } catch (e: ErrnoException) {
            if (e.errno != OsConstants.EAGAIN) {
                closeQuietly(writeEnd)
                throw FileNotFoundException("no shared media")
            }
        } catch (_: Exception) {
            if (offset == 0) {
                writeAll(writeEnd, bytes, 0)
                return
            }
            closeQuietly(writeEnd)
            throw FileNotFoundException("no shared media")
        }
        if (offset >= bytes.size) {
            closeQuietly(writeEnd)
            return
        }
        val start = offset
        val writer = Thread({
            try {
                clearNonBlocking(writeEnd)
                writeAll(writeEnd, bytes, start)
            } catch (_: IOException) {
                closeQuietly(writeEnd)
            }
        }, SHARE_THREAD)
        writer.isDaemon = true
        writer.start()
    }

    private fun writeAll(writeEnd: ParcelFileDescriptor, bytes: ByteArray, offset: Int) {
        ParcelFileDescriptor.AutoCloseOutputStream(writeEnd).use { stream ->
            stream.write(bytes, offset, bytes.size - offset)
        }
    }

    private fun isFileBacked(writeEnd: ParcelFileDescriptor): Boolean = try {
        Os.lseek(writeEnd.fileDescriptor, 0, OsConstants.SEEK_CUR)
        true
    } catch (_: ErrnoException) {
        false
    }

    private fun markNonBlocking(writeEnd: ParcelFileDescriptor): Boolean = try {
        val flags = Os.fcntlInt(writeEnd.fileDescriptor, OsConstants.F_GETFL, 0)
        Os.fcntlInt(writeEnd.fileDescriptor, OsConstants.F_SETFL, flags or OsConstants.O_NONBLOCK)
        true
    } catch (_: Exception) {
        false
    }

    private fun clearNonBlocking(writeEnd: ParcelFileDescriptor) {
        try {
            val flags = Os.fcntlInt(writeEnd.fileDescriptor, OsConstants.F_GETFL, 0)
            Os.fcntlInt(writeEnd.fileDescriptor, OsConstants.F_SETFL, flags and OsConstants.O_NONBLOCK.inv())
        } catch (_: Exception) {
        }
    }

    private fun closeQuietly(descriptor: ParcelFileDescriptor) {
        try {
            descriptor.close()
        } catch (_: IOException) {
        }
    }

    private companion object {
        const val SHARE_THREAD = "shroud-share"
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0

    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0
}
