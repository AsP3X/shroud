package de.corespace.shroud.core.media.share

import android.content.Intent
import android.provider.OpenableColumns
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import de.corespace.shroud.core.media.SealedMediaReader
import de.corespace.shroud.core.model.SystemAppClock
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import java.io.FileInputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.util.UUID

/**
 * A file grant on a real device (docs/file-sharing.md §8): `DecryptedMediaProvider` serves it as a
 * seekable proxy descriptor straight from the reader — what PDF and Office viewers need, and what
 * Robolectric cannot make — with the cleaned name and size in `OpenableColumns`.
 */
@RunWith(AndroidJUnit4::class)
class FileSharingDeviceTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val bytes = "%PDF-1.7\n".toByteArray() + ByteArray(700_000) { (it % 251).toByte() }
    private val closed = ArrayList<Int>()
    private val sharing = MemoryMediaSharing(context, { null }, SystemAppClock, openReader = { Reader(bytes, closed) })

    @After
    fun tearDown() = sharing.revokeAll()

    @Test
    fun aFileGrantIsSeekableAndNamed() = runBlocking {
        val ready = sharing.openTarget(UUID.randomUUID(), "Quarterly report 2026.pdf") as FileOpenOutcome.Ready
        val resolver = context.contentResolver
        assertEquals("application/pdf", resolver.getType(ready.target.uri))
        resolver.query(ready.target.uri, null, null, null, null)!!.use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("Quarterly report 2026.pdf", cursor.getString(cursor.getColumnIndexOrThrow(OpenableColumns.DISPLAY_NAME)))
            assertEquals(bytes.size.toLong(), cursor.getLong(cursor.getColumnIndexOrThrow(OpenableColumns.SIZE)))
        }
        val readersBefore = synchronized(closed) { closed.size }
        resolver.openFileDescriptor(ready.target.uri, "r")!!.use { descriptor ->
            // A proxy descriptor knows its size and seeks; a pipe would answer −1 and fail the seek.
            assertEquals(bytes.size.toLong(), descriptor.statSize)
            FileInputStream(descriptor.fileDescriptor).channel.use { channel ->
                val tail = ByteBuffer.allocate(5_000)
                channel.position(500_000)
                while (tail.hasRemaining()) {
                    if (channel.read(tail) <= 0) break
                }
                assertArrayEquals(bytes.copyOfRange(500_000, 505_000), tail.array())
                val head = ByteBuffer.allocate(9)
                channel.position(0)
                channel.read(head)
                assertArrayEquals("%PDF-1.7\n".toByteArray(), head.array())
            }
        }
        // The other app closing the descriptor closes the reader it opened.
        Thread.sleep(500)
        assertTrue(synchronized(closed) { closed.size } > readersBefore)
    }

    @Test
    fun aDeleteStopsADescriptorAnotherAppAlreadyHolds() = runBlocking {
        val id = UUID.randomUUID()
        val ready = sharing.openTarget(id, "a.pdf") as FileOpenOutcome.Ready
        context.contentResolver.openFileDescriptor(ready.target.uri, "r")!!.use { descriptor ->
            FileInputStream(descriptor.fileDescriptor).channel.use { channel ->
                assertTrue(channel.read(ByteBuffer.allocate(4_096)) > 0)
                // Deleted for everyone meanwhile: messaging's purge revokes the message's grants.
                sharing.revoke(listOf(id))
                channel.position(600_000)
                try {
                    channel.read(ByteBuffer.allocate(4_096))
                    fail("read after the delete")
                } catch (_: IOException) {
                }
            }
        }
    }

    @Test
    fun anOpenIntentCarriesTheTypeAndAReadGrant() = runBlocking {
        val ready = sharing.openTarget(UUID.randomUUID(), "a.pdf") as FileOpenOutcome.Ready
        val intent = de.corespace.shroud.ui.media.viewer.MediaShareIntents.view(ready.target)
        assertEquals(Intent.ACTION_VIEW, intent.action)
        assertEquals("application/pdf", intent.type)
        assertTrue(intent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
    }

    private class Reader(private val data: ByteArray, private val closed: MutableList<Int>) : SealedMediaReader {
        override val length: Long = data.size.toLong()

        @Synchronized
        override fun read(position: Long, buffer: ByteArray, offset: Int, size: Int): Int {
            // As a SHRM1 reader: closed means no more plaintext.
            if (isClosed) throw IOException("reader closed")
            if (size == 0) return 0
            if (position >= data.size) return -1
            val count = minOf(size, data.size - position.toInt())
            System.arraycopy(data, position.toInt(), buffer, offset, count)
            return count
        }

        @Volatile private var isClosed = false

        override fun close() {
            isClosed = true
            synchronized(closed) { closed += 1 }
        }
    }
}
