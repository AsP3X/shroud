package de.corespace.shroud.core.media.share

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Handler
import android.os.HandlerThread
import android.os.ParcelFileDescriptor
import android.os.storage.StorageManager
import android.provider.OpenableColumns
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import de.corespace.shroud.core.media.SealedMediaReader
import java.io.FileNotFoundException
import java.io.IOException

/**
 * Hands decrypted media to a share target on demand (conversation-compose-media §18.5, Q6;
 * `exported=false`, `grantUriPermissions=true`, authority `<applicationId>.media`). The bytes stay
 * in [SharedMediaRegistry]; [openFile] pipes them out and writes nothing to disk.
 *
 * A file's grant (docs/file-sharing.md §8) is served straight from its SHRM1 reader: a seekable
 * proxy descriptor ([StorageManager.openProxyFileDescriptor]) whose reads decrypt the segments the
 * other app asks for — PDF and Office viewers seek — or, where the platform cannot make one, a
 * pipe fed segment by segment from a background thread. [query] answers `OpenableColumns` with the
 * cleaned name and the size, so the receiving app shows the real name.
 *
 * Providers are created at process start, before `ShroudApplication.onCreate`: [onCreate] must
 * never touch the container. [MediaSharing.revokeAll] clears the registry so a later open fails.
 */
class DecryptedMediaProvider : ContentProvider() {
    override fun onCreate(): Boolean = true

    override fun getType(uri: Uri): String? = SharedMediaRegistry.open(uri.lastPathSegment)?.mime

    /** `DISPLAY_NAME` and `SIZE` of a file's grant (only the columns asked for, both by default); null for a photo's. */
    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor? {
        val file = SharedMediaRegistry.open(uri.lastPathSegment) as? SharedMediaRegistry.SealedFile ?: return null
        val columns = (projection ?: arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE))
            .filter { it == OpenableColumns.DISPLAY_NAME || it == OpenableColumns.SIZE }
            .toTypedArray()
        return MatrixCursor(columns, 1).apply {
            addRow(columns.map { if (it == OpenableColumns.DISPLAY_NAME) file.name else file.size })
        }
    }

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        if (mode.indexOf('w') >= 0 || mode.indexOf('a') >= 0) throw FileNotFoundException("shared media is read only")
        val item = when (val found = SharedMediaRegistry.open(uri.lastPathSegment)) {
            is SharedMediaRegistry.Bytes -> found
            is SharedMediaRegistry.SealedFile -> return openSealedFile(found)
            null -> throw FileNotFoundException("no shared media")
        }
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

    /**
     * A file's grant: a fresh SHRM1 reader behind a seekable proxy descriptor, else a pipe. The
     * reader closes when the other app closes the descriptor, or when the grants are revoked.
     */
    private fun openSealedFile(file: SharedMediaRegistry.SealedFile): ParcelFileDescriptor {
        val reader = file.reader() ?: throw FileNotFoundException("no shared media")
        proxy(file, reader)?.let { return it }
        return try {
            pipeFile(file, reader)
        } catch (e: FileNotFoundException) {
            file.release(reader)
            throw e
        }
    }

    /** Null when the platform cannot make a proxy descriptor (Robolectric, a device without AppFuse). */
    private fun proxy(file: SharedMediaRegistry.SealedFile, reader: SealedMediaReader): ParcelFileDescriptor? {
        val storage = context?.getSystemService(StorageManager::class.java) ?: return null
        return SealedReaderProxy.open(storage, reader, proxyHandler) { file.release(reader) }
    }

    /** The reader streamed into a pipe, one segment at a time, on [SHARE_THREAD] (synchronously into a file-backed pipe). */
    private fun pipeFile(file: SharedMediaRegistry.SealedFile, reader: SealedMediaReader): ParcelFileDescriptor {
        val pipe = try {
            ParcelFileDescriptor.createPipe()
        } catch (_: IOException) {
            throw FileNotFoundException("no shared media")
        }
        val copy = Runnable {
            try {
                ParcelFileDescriptor.AutoCloseOutputStream(pipe[1]).use { stream ->
                    val buffer = ByteArray(PIPE_CHUNK_BYTES)
                    try {
                        var position = 0L
                        while (true) {
                            val read = reader.read(position, buffer, 0, buffer.size)
                            if (read <= 0) break
                            stream.write(buffer, 0, read)
                            position += read
                        }
                    } finally {
                        buffer.fill(0)
                    }
                }
            } catch (_: IOException) {
                closeQuietly(pipe[1])
            } finally {
                file.release(reader)
            }
        }
        if (isFileBacked(pipe[1])) {
            copy.run()
        } else {
            Thread(copy, SHARE_THREAD).apply { isDaemon = true }.start()
        }
        return pipe[0]
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
        const val PROXY_THREAD = "shroud-share-proxy"

        /** One SHRM1 segment per write into a file's pipe. */
        const val PIPE_CHUNK_BYTES = 64 * 1024

        /** Where proxy descriptor callbacks run: one looper for every grant, started on first use. */
        val proxyHandler: Handler by lazy {
            val thread = HandlerThread(PROXY_THREAD).apply { start() }
            Handler(thread.looper)
        }
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0

    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0
}
