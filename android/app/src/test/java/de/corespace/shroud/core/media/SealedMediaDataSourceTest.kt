package de.corespace.shroud.core.media

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSourceException
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import de.corespace.shroud.core.keys.SealedLocalState
import de.corespace.shroud.core.storage.StorageSeal
import de.corespace.shroud.testing.SealedTestKey
import de.corespace.shroud.testing.TempDirRule
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID
import kotlin.random.Random

/**
 * The decrypting data sources over SHRM1 (media-voice-links D4, §6.5, §7.1; plan C7): Media3's
 * `DataSource` contract (positions, lengths, end of input, the error codes ExoPlayer maps) and the
 * platform `MediaDataSource` that `MediaMetadataRetriever` reads. Robolectric for `android.net.Uri`;
 * the real player and retriever run in `MediaStoreDeviceTest` on a device.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@OptIn(markerClass = [UnstableApi::class])
class SealedMediaDataSourceTest {
    @get:Rule val temp = TempDirRule()

    private val state = SealedLocalState()
    private lateinit var cache: LocalMediaCache
    private val video = UUID.fromString("0f8fad5b-d9cb-469f-a165-70867728950e")
    private val data = Random(1).nextBytes(200_000)

    @Before
    fun setUp() {
        state.unlock(SealedTestKey.bytes())
        cache = LocalMediaCache(File(temp.noBackupFilesDir, "shroud/media"), state, StorageSeal())
        cache.saveBlocking(video, data)
    }

    @After
    fun tearDown() = cache.close()

    private fun source() = SealedMediaDataSource.Factory(cache, video).createDataSource() as SealedMediaDataSource

    private fun readToEnd(source: SealedMediaDataSource, chunk: Int = 7_000): ByteArray {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(chunk)
        while (true) {
            val read = source.read(buffer, 0, buffer.size)
            if (read == C.RESULT_END_OF_INPUT) break
            out.write(buffer, 0, read)
        }
        return out.toByteArray()
    }

    @Test
    fun readsTheWholeMedia() {
        val source = source()
        assertEquals(200_000L, source.open(DataSpec(SealedMediaDataSource.URI)))
        assertEquals(SealedMediaDataSource.URI, source.uri)
        assertArrayEquals(data, readToEnd(source))
        assertEquals(C.RESULT_END_OF_INPUT, source.read(ByteArray(10), 0, 10))
        assertEquals(0, source.read(ByteArray(10), 0, 0))
        source.close()
        assertNull(source.uri)
    }

    @Test
    fun opensAtAPositionAndForALength() {
        val source = source()
        // ExoPlayer seeks by re-opening at a position (across a segment boundary here).
        assertEquals(200_000L - 65_530, source.open(DataSpec(SealedMediaDataSource.URI, 65_530, C.LENGTH_UNSET.toLong())))
        assertArrayEquals(data.copyOfRange(65_530, 200_000), readToEnd(source, chunk = 4_096))
        source.close()

        assertEquals(1_000L, source.open(DataSpec(SealedMediaDataSource.URI, 131_000, 1_000)))
        assertArrayEquals(data.copyOfRange(131_000, 132_000), readToEnd(source))
        source.close()

        // At the very end: nothing to read.
        assertEquals(0L, source.open(DataSpec(SealedMediaDataSource.URI, 200_000, C.LENGTH_UNSET.toLong())))
        assertEquals(C.RESULT_END_OF_INPUT, source.read(ByteArray(1), 0, 1))
        source.close()
    }

    @Test
    fun aLengthPastTheEndStopsAtTheEnd() {
        val source = source()
        source.open(DataSpec(SealedMediaDataSource.URI, 199_000, 5_000))
        assertArrayEquals(data.copyOfRange(199_000, 200_000), readToEnd(source))
        source.close()
    }

    @Test
    fun aPositionPastTheEndIsOutOfRange() {
        val source = source()
        val error = assertThrows(DataSourceException::class.java) {
            source.open(DataSpec(SealedMediaDataSource.URI, 200_001, C.LENGTH_UNSET.toLong()))
        }
        assertEquals(PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE, error.reason)
        source.close()
    }

    @Test
    fun missingOrLockedMediaIsFileNotFound() {
        val missing = SealedMediaDataSource.Factory(cache, UUID.randomUUID()).createDataSource()
        assertEquals(
            PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND,
            assertThrows(DataSourceException::class.java) { missing.open(DataSpec(SealedMediaDataSource.URI)) }.reason,
        )
        missing.close()
        state.lock()
        val locked = source()
        assertEquals(
            PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND,
            assertThrows(DataSourceException::class.java) { locked.open(DataSpec(SealedMediaDataSource.URI)) }.reason,
        )
        locked.close()
    }

    @Test
    fun aLockDuringPlaybackFailsTheNextRead() {
        val source = source()
        source.open(DataSpec(SealedMediaDataSource.URI))
        assertEquals(100, source.read(ByteArray(100), 0, 100))
        state.lock()
        val error = assertThrows(DataSourceException::class.java) { source.read(ByteArray(100), 0, 100) }
        assertEquals(PlaybackException.ERROR_CODE_IO_UNSPECIFIED, error.reason)
        source.close()
    }

    @Test
    fun transferListenersHearOpenReadAndClose() {
        val events = ArrayList<String>()
        val source = source()
        source.addTransferListener(object : TransferListener {
            override fun onTransferInitializing(source: androidx.media3.datasource.DataSource, dataSpec: DataSpec, isNetwork: Boolean) {
                events += "init network=$isNetwork"
            }
            override fun onTransferStart(source: androidx.media3.datasource.DataSource, dataSpec: DataSpec, isNetwork: Boolean) {
                events += "start"
            }
            override fun onBytesTransferred(source: androidx.media3.datasource.DataSource, dataSpec: DataSpec, isNetwork: Boolean, bytesTransferred: Int) {
                events += "bytes $bytesTransferred"
            }
            override fun onTransferEnd(source: androidx.media3.datasource.DataSource, dataSpec: DataSpec, isNetwork: Boolean) {
                events += "end"
            }
        })
        source.open(DataSpec(SealedMediaDataSource.URI, 0, 10))
        source.read(ByteArray(10), 0, 10)
        source.close()
        source.close() // a second close reports nothing
        assertEquals(listOf("init network=false", "start", "bytes 10", "end"), events)
    }

    @Test
    fun theUriSaysNothingAboutTheMessage() {
        val uri = SealedMediaDataSource.URI.toString()
        assertEquals("shroud-media:sealed", uri)
        assertEquals(SealedMediaDataSource.URI, SealedMediaDataSource.mediaItem().localConfiguration!!.uri)
        assertTrue(!uri.contains(video.toString()))
    }

    // ---- android.media.MediaDataSource ----

    @Test
    fun theMediaDataSourceReadsRanges() {
        val source = SealedMediaDataSourceMdr.open(cache, video)!!
        assertEquals(200_000L, source.size)
        val buffer = ByteArray(1_000)
        assertEquals(1_000, source.readAt(65_000, buffer, 0, 1_000))
        assertArrayEquals(data.copyOfRange(65_000, 66_000), buffer)
        assertEquals(500, source.readAt(199_500, buffer, 0, 1_000))
        assertEquals(-1, source.readAt(200_000, buffer, 0, 1_000))
        assertEquals(0, source.readAt(0, buffer, 0, 0))
        source.close()
        assertNull(SealedMediaDataSourceMdr.open(cache, UUID.randomUUID()))
    }

    @Test
    fun theMediaDataSourceFailsAfterALock() {
        val source = SealedMediaDataSourceMdr.open(cache, video)!!
        state.lock()
        val error = assertThrows(java.io.IOException::class.java) { source.readAt(0, ByteArray(1), 0, 1) }
        assertSame(de.corespace.shroud.core.crypto.CryptoError.Locked, error.cause)
        source.close()
        assertNull(SealedMediaDataSourceMdr.open(cache, video))
    }
}
