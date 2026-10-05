package de.corespace.shroud.core.media.share

import android.os.Handler
import android.os.ParcelFileDescriptor
import android.os.ProxyFileDescriptorCallback
import android.os.storage.StorageManager
import android.system.ErrnoException
import android.system.OsConstants
import de.corespace.shroud.core.media.SealedMediaReader
import java.io.IOException

/**
 * A seekable, read-only descriptor over an SHRM1 reader (docs/file-sharing.md §8): every read the
 * other side makes decrypts just the segments it covers, so no plaintext touches the disk. Shared by
 * [DecryptedMediaProvider] (another app reads a file grant) and the in-app PDF renderer (§10).
 */
internal object SealedReaderProxy {
    /**
     * [reader] behind [StorageManager.openProxyFileDescriptor], its callbacks on [handler] (never the
     * thread that reads the descriptor: a read blocks until the callback answers). [onRelease] runs
     * once the descriptor's last holder closed it and must close [reader]. Null when the platform
     * cannot make one (Robolectric, a device without AppFuse); the caller still owns [reader] then.
     */
    fun open(storage: StorageManager, reader: SealedMediaReader, handler: Handler, onRelease: () -> Unit): ParcelFileDescriptor? = try {
        storage.openProxyFileDescriptor(ParcelFileDescriptor.MODE_READ_ONLY, Callback(reader, onRelease), handler)
    } catch (_: Exception) {
        null
    }

    /** Serves the proxy descriptor's reads from the SHRM1 reader; a segment that does not open is `EIO`. */
    private class Callback(private val reader: SealedMediaReader, private val onRelease: () -> Unit) : ProxyFileDescriptorCallback() {
        override fun onGetSize(): Long = reader.length

        override fun onRead(offset: Long, size: Int, data: ByteArray): Int {
            var done = 0
            try {
                while (done < size) {
                    val read = reader.read(offset + done, data, done, size - done)
                    if (read <= 0) break
                    done += read
                }
            } catch (_: IOException) {
                throw ErrnoException("onRead", OsConstants.EIO)
            }
            return done
        }

        override fun onRelease() = onRelease.invoke()
    }
}
