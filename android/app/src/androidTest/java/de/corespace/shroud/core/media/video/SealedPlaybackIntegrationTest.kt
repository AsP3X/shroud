package de.corespace.shroud.core.media.video

import android.graphics.BitmapFactory
import android.media.MediaDataSource
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import de.corespace.shroud.core.keys.SealedLocalState
import de.corespace.shroud.core.media.LocalMediaCache
import de.corespace.shroud.core.media.SealedMediaDataSource
import de.corespace.shroud.core.media.SealedMediaDataSourceMdr
import de.corespace.shroud.core.model.SystemAppClock
import de.corespace.shroud.core.storage.SensitiveTempFiles
import de.corespace.shroud.core.storage.StorageSeal
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

/** Throwaway W2-VIDEO × W2-MEDIA-STORE check: ChatVideoPlayer and posters over real SHRM1 media. */
@OptIn(UnstableApi::class)
@RunWith(AndroidJUnit4::class)
class SealedPlaybackIntegrationTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val state = SealedLocalState()
    private val video = UUID.randomUUID()
    private lateinit var dir: File
    private lateinit var cache: LocalMediaCache
    private lateinit var plain: ByteArray

    /** Exactly what W2-INT puts in VideoModule.sealedSources, over a test cache. */
    private val sources = object : SealedVideoSources {
        override fun playerDataSource(messageId: UUID): DataSource.Factory? =
            if (cache.has(messageId)) SealedMediaDataSource.Factory(cache, messageId) else null

        override fun retrieverDataSource(messageId: UUID): MediaDataSource? = SealedMediaDataSourceMdr.open(cache, messageId)
    }

    @Before
    fun setUp() {
        state.unlock(ByteArray(32) { 0x5A })
        dir = File(context.noBackupFilesDir, "video-int-test-" + UUID.randomUUID())
        cache = LocalMediaCache(dir, state, StorageSeal())
        val clip = TestMovies.write(File(context.cacheDir, "int-clip.mp4"), TestMovies.Spec(2.0, 640, 360, audio = true))
        plain = clip.readBytes()
        clip.delete()
        cache.saveBlocking(video, plain)
    }

    @After
    fun tearDown() {
        cache.clearAll()
        cache.close()
        state.lock()
        dir.deleteRecursively()
    }

    @Test
    fun chatVideoPlayerPlaysShrm1Media() = runBlocking {
        withContext(Dispatchers.Main) {
            val player = ChatVideoPlayer(context, sources)
            player.start(video)
            assertTrue(player.state.value.isReady)
            assertFalse(player.state.value.failed)
            assertEquals(2.0, player.state.value.duration, 0.15)
            withTimeout(5_000) { while (player.state.value.currentTime <= 0.3) delay(50) }
            player.seek(1.5, precise = true)
            player.play()
            withTimeout(5_000) { while (player.state.value.isPlaying) delay(50) }
            assertEquals(player.state.value.duration, player.state.value.currentTime, 0.0)
            player.teardown()

            val missing = ChatVideoPlayer(context, sources)
            missing.start(UUID.randomUUID())
            assertTrue(missing.state.value.failed)
            missing.teardown()
        }
        // No plaintext copy anywhere under the app's files or cache.
        val needle = plain.copyOfRange(plain.size / 2, plain.size / 2 + 64)
        val leaks = listOf(context.filesDir, context.noBackupFilesDir, context.cacheDir).flatMap { root ->
            root.walkTopDown().filter { it.isFile && it.length() >= needle.size }.filter { contains(it.readBytes(), needle) }.map { it.name }.toList()
        }
        assertEquals(emptyList<String>(), leaks)
    }

    @Test
    fun posterOfSealedMedia() = runBlocking {
        val media = VideoMedia.create(context, SensitiveTempFiles(context.cacheDir), SystemAppClock, sources)
        val jpeg = media.posterJpegFromLocal(video)!!
        assertArrayEquals(byteArrayOf(-1, -40), jpeg.copyOf(2))
        val bitmap = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size)
        assertEquals(640 to 360, bitmap.width to bitmap.height)
        assertNull(media.posterJpegFromLocal(UUID.randomUUID()))
        state.lock()
        assertNull(media.posterJpegFromLocal(video))
    }

    private fun contains(hay: ByteArray, needle: ByteArray): Boolean {
        outer@ for (i in 0..hay.size - needle.size) {
            for (j in needle.indices) if (hay[i + j] != needle[j]) continue@outer
            return true
        }
        return false
    }
}
