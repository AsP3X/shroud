package de.corespace.shroud.core.media.video

import android.content.Context
import android.graphics.BitmapFactory
import android.media.MediaDataSource
import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.FileDataSource
import androidx.media3.datasource.TransferListener
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import de.corespace.shroud.core.model.SystemAppClock
import de.corespace.shroud.core.storage.SensitiveTempFiles
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.RandomAccessFile
import java.util.UUID

/**
 * `ChatVideoPlayer` on ExoPlayer, fed the way W2-MEDIA-STORE's `SealedMediaDataSource` will feed it
 * (plan C7): a `DataSource.Factory` per message that ignores the item's URI and reads its own
 * bytes, and a `MediaDataSource` for the poster of a local video. Here those sources read a plain
 * fixture; the decrypting ones are W2-MEDIA-STORE's and are wired by W2-INT.
 */
@OptIn(UnstableApi::class)
@RunWith(AndroidJUnit4::class)
class ChatVideoPlayerDeviceTest {
    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var dir: File
    private lateinit var clip: File
    private val messageId = UUID.fromString("0b7f2b6c-1e7a-4c55-9a51-3d0c2d6f8a11")

    /** Reads [clip] for [messageId] only, like the sealed sources (no plaintext file in real use). */
    private inner class FileBackedSources : SealedVideoSources {
        val opened = ArrayList<Uri>()

        override fun playerDataSource(messageId: UUID): DataSource.Factory? {
            if (messageId != this@ChatVideoPlayerDeviceTest.messageId) return null
            return DataSource.Factory {
                object : DataSource {
                    private val inner = FileDataSource()
                    private var uri: Uri? = null

                    override fun addTransferListener(transferListener: TransferListener) = inner.addTransferListener(transferListener)

                    override fun open(dataSpec: DataSpec): Long {
                        uri = dataSpec.uri
                        synchronized(opened) { opened += dataSpec.uri }
                        return inner.open(dataSpec.buildUpon().setUri(Uri.fromFile(clip)).build())
                    }

                    override fun read(buffer: ByteArray, offset: Int, length: Int): Int = inner.read(buffer, offset, length)

                    override fun getUri(): Uri? = uri

                    override fun close() = inner.close()
                }
            }
        }

        override fun retrieverDataSource(messageId: UUID): MediaDataSource? {
            if (messageId != this@ChatVideoPlayerDeviceTest.messageId) return null
            val file = RandomAccessFile(clip, "r")
            return object : MediaDataSource() {
                override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int {
                    if (position >= file.length()) return -1
                    file.seek(position)
                    return file.read(buffer, offset, size)
                }

                override fun getSize(): Long = file.length()

                override fun close() = file.close()
            }
        }
    }

    @Before
    fun setUp() {
        dir = File(context.cacheDir, "video-player-test").apply { deleteRecursively(); mkdirs() }
        clip = TestMovies.write(File(dir, "clip.mp4"), TestMovies.Spec(2.0, 640, 360, audio = true))
    }

    @After
    fun tearDown() {
        dir.deleteRecursively()
    }

    private fun <T> onMain(block: suspend () -> T): T = runBlocking { withContext(Dispatchers.Main) { block() } }

    @Test
    fun aLocalVideoPlaysSeeksAndTearsDown() {
        val sources = FileBackedSources()
        onMain {
            val player = ChatVideoPlayer(context, sources)
            player.start(messageId)
            val ready = player.state.value
            assertTrue(ready.isReady)
            assertTrue(ready.isPlaying)
            assertFalse(ready.failed)
            assertEquals(2.0, ready.duration, 0.15)
            assertNotNull(player.player.value)
            // The item's URI only names the message; the source reads its own bytes.
            assertTrue(sources.opened.all { it.scheme == ExoPlaybackEngine.LOCAL_SCHEME && it.lastPathSegment == messageId.toString() })

            withTimeout(5_000) { while (player.state.value.currentTime <= 0.2) delay(50) }
            player.pause()
            assertFalse(player.state.value.isPlaying)
            player.seek(1.0, precise = true)
            assertEquals(1.0, player.state.value.currentTime, 0.0)

            // Plays to the end, then a replay rewinds (`ChatVideoPlayer.swift:155-165, 215-218`).
            player.play()
            withTimeout(5_000) { while (player.state.value.isPlaying) delay(50) }
            assertEquals(player.state.value.duration, player.state.value.currentTime, 0.0)
            player.play()
            assertTrue(player.state.value.currentTime < 0.1)

            player.teardown()
            assertEquals(ChatVideoPlayer.State(), player.state.value)
            assertNull(player.player.value)
        }
    }

    @Test
    fun aPickedFilePlaysFromItsUri() {
        onMain {
            val player = ChatVideoPlayer(context, SealedVideoSources.Unavailable)
            player.start(Uri.fromFile(clip))
            assertTrue(player.state.value.isReady)
            player.teardown()
        }
    }

    @Test
    fun withoutLocalMediaThePlayerFails() {
        onMain {
            val player = ChatVideoPlayer(context, SealedVideoSources.Unavailable)
            player.start(messageId)
            assertTrue(player.state.value.failed)
            player.teardown()
            assertFalse(player.state.value.failed)
        }
    }

    @Test
    fun aDamagedFileFailsWithinTheReadyWait() {
        clip.writeBytes(ByteArray(8192) { (it * 31).toByte() })
        onMain {
            val player = ChatVideoPlayer(context, FileBackedSources())
            player.start(messageId)
            assertTrue(player.state.value.failed)
            assertFalse(player.state.value.isReady)
            player.teardown()
        }
    }

    @Test
    fun thePosterOfALocalVideoIsReadWithoutAFile() {
        val media = VideoMedia.create(context, SensitiveTempFiles(dir), SystemAppClock, FileBackedSources())
        val jpeg = runBlocking { media.posterJpegFromLocal(messageId) }!!
        assertArrayEquals(byteArrayOf(-1, -40), jpeg.copyOf(2))
        val bitmap = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size)
        assertEquals(640 to 360, bitmap.width to bitmap.height)
        val small = runBlocking { media.posterJpegFromLocal(messageId, maxEdgePx = 320) }!!
        assertEquals(320, BitmapFactory.decodeByteArray(small, 0, small.size).width)

        assertNull(runBlocking { media.posterJpegFromLocal(UUID.randomUUID()) })
        val locked = VideoMedia.create(context, SensitiveTempFiles(dir), SystemAppClock, SealedVideoSources.Unavailable)
        assertNull(runBlocking { locked.posterJpegFromLocal(messageId) })
    }
}
