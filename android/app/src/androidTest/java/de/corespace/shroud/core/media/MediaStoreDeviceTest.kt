package de.corespace.shroud.core.media

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.os.SystemClock
import androidx.annotation.OptIn
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import de.corespace.shroud.core.keys.SealedLocalState
import de.corespace.shroud.core.storage.StorageSeal
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.random.Random

/**
 * W2-MEDIA-STORE acceptance on a device (media-voice-links D4, §6.5, §7.1; plan C7): ExoPlayer
 * plays and seeks an MP4 straight out of an SHRM1 file through `SealedMediaDataSource`, the
 * platform's `MediaMetadataRetriever` and `MediaExtractor` read it through
 * `SealedMediaDataSourceMdr`, and afterwards no file anywhere in the app's data contains the
 * plaintext — iOS decrypts to `tmp/shroud-play-*.mp4` for this (`ChatVideoPlayer.swift:39-51`),
 * Android never writes one. Also `EnvelopePreview` on the platform's real decoders.
 *
 * The fixture `assets/media/sealed-playback.mp4` (34 720 bytes, SHA-256 `618db49a…bd4ae3`) is 4 s
 * of 160×120 H.264 Constrained Baseline at 15 fps (a key frame every second) with 44.1 kHz mono
 * AAC-LC, made with ffmpeg 8 (`testsrc` + `sine`, `-movflags +faststart -map_metadata -1
 * -fflags +bitexact`). The cache lives in its own directory under `noBackupFilesDir` with a test
 * key, so the app's real media are never touched.
 *
 * ```
 * adb shell am instrument -w -e class de.corespace.shroud.core.media.MediaStoreDeviceTest \
 *     de.corespace.shroud.test/androidx.test.runner.AndroidJUnitRunner
 * ```
 */
@RunWith(AndroidJUnit4::class)
@OptIn(markerClass = [UnstableApi::class])
class MediaStoreDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val target = instrumentation.targetContext
    private val state = SealedLocalState()
    private val video = UUID.randomUUID()
    private lateinit var dir: File
    private lateinit var cache: LocalMediaCache
    private lateinit var fixture: ByteArray

    @Before
    fun setUp() {
        state.unlock(ByteArray(32) { 0x5A })
        dir = File(target.noBackupFilesDir, "media-store-test-" + UUID.randomUUID())
        cache = LocalMediaCache(dir, state, StorageSeal())
        fixture = instrumentation.context.assets.open("media/sealed-playback.mp4").use { it.readBytes() }
        cache.saveBlocking(video, fixture)
    }

    @After
    fun tearDown() {
        cache.clearAll()
        cache.close()
        state.lock()
    }

    @Test
    fun exoPlayerPlaysAndSeeksStraightFromTheSealedFile() {
        var player: ExoPlayer? = null
        // Written on the main thread by the listener, read by this test thread.
        val failure = AtomicReference<PlaybackException?>(null)
        val reachedEnd = AtomicBoolean(false)
        instrumentation.runOnMainSync {
            player = ExoPlayer.Builder(target).build().apply {
                addListener(object : Player.Listener {
                    override fun onPlayerError(e: PlaybackException) {
                        failure.set(e)
                    }

                    override fun onPlaybackStateChanged(playbackState: Int) {
                        if (playbackState == Player.STATE_ENDED) reachedEnd.set(true)
                    }
                })
                volume = 0f
                setMediaSource(
                    ProgressiveMediaSource.Factory(SealedMediaDataSource.Factory(cache, video))
                        .createMediaSource(SealedMediaDataSource.mediaItem()),
                )
                prepare()
            }
        }
        try {
            awaitState(player!!, Player.STATE_READY) { failure.get() }
            val duration = onMain { player!!.duration }
            assertTrue("duration $duration ms", duration in 3_800L..4_200L)

            // Seek forward past two key frames and play to the end.
            onMain {
                player!!.seekTo(2_500)
                player!!.playWhenReady = true
            }
            waitFor("played past 3 s after the seek") {
                failure.get() != null || reachedEnd.get() || onMain { player!!.currentPosition } >= 3_000
            }
            assertNull("no playback error: ${failure.get()?.errorCodeName}", failure.get())
            waitFor("ended") { failure.get() != null || reachedEnd.get() }
            assertNull(failure.get())

            // Seek backwards (ExoPlayer re-opens the source at a position) and play again.
            onMain {
                player!!.seekTo(500)
                player!!.playWhenReady = true
            }
            waitFor("playing from 0.5 s") {
                val position = onMain { player!!.currentPosition }
                failure.get() != null || position in 600L..2_000L
            }
            assertNull(failure.get())
        } finally {
            onMain { player!!.release() }
        }
        assertNoPlaintextOnDisk()
    }

    @Test
    fun theMetadataRetrieverReadsThroughTheMediaDataSource() {
        val source = SealedMediaDataSourceMdr.open(cache, video)
        assertNotNull(source)
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(source)
            val duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)!!.toLong()
            assertTrue("duration $duration", duration in 3_800L..4_200L)
            assertEquals("160", retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH))
            assertEquals("120", retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT))
            val frame = retriever.getFrameAtTime(1_000_000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
            assertNotNull("a poster frame", frame)
            assertEquals(160, frame!!.width)
        } finally {
            retriever.release()
            source!!.close()
        }
        assertNoPlaintextOnDisk()
    }

    @Test
    fun theExtractorSeesBothTracks() {
        val source = SealedMediaDataSourceMdr.open(cache, video)!!
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(source)
            val mimes = (0 until extractor.trackCount).map { extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME) }.toSet()
            assertEquals(setOf("video/avc", "audio/mp4a-latm"), mimes)
        } finally {
            extractor.release()
            source.close()
        }
    }

    @Test
    fun theEnvelopePreviewFitsOnRealPixels() {
        val bitmap = Bitmap.createBitmap(3_000, 2_000, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint()
        val random = Random(3)
        repeat(400) {
            paint.color = Color.rgb(random.nextInt(256), random.nextInt(256), random.nextInt(256))
            val x = random.nextInt(3_000).toFloat()
            val y = random.nextInt(2_000).toFloat()
            canvas.drawCircle(x, y, random.nextInt(20, 300).toFloat(), paint)
        }
        val photo = ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.JPEG, 95, it) }.toByteArray()
        bitmap.recycle()
        val preview = EnvelopePreview.chatPreviewJpeg(photo)!!
        assertTrue("≤ 6 KiB, got ${preview.size}", preview.size <= MediaEnvelopeBudget.MAX_THUMB)
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(preview, 0, preview.size, bounds)
        assertEquals("image/jpeg", bounds.outMimeType)
        assertTrue(maxOf(bounds.outWidth, bounds.outHeight) <= 160)
        assertEquals(1.5, bounds.outWidth.toDouble() / bounds.outHeight, 0.05)
    }

    /** No file in the app's data or cache contains a stretch of the fixture's plaintext. */
    private fun assertNoPlaintextOnDisk() {
        // A stretch from the middle of the file (inside the media data), long enough to be unique.
        val needle = fixture.copyOfRange(fixture.size / 2, fixture.size / 2 + 48)
        val roots = listOfNotNull(target.dataDir, target.cacheDir, target.externalCacheDir, target.codeCacheDir)
        val hits = roots.flatMap { root -> root.walkTopDown().filter { it.isFile && it.length() < 64L * 1024 * 1024 }.toList() }
            .distinct()
            .filter { file -> runCatching { contains(file.readBytes(), needle) }.getOrDefault(false) }
        assertTrue("plaintext found in ${hits.map { it.name }}", hits.isEmpty())
    }

    private fun contains(haystack: ByteArray, needle: ByteArray): Boolean {
        outer@ for (i in 0..haystack.size - needle.size) {
            for (j in needle.indices) if (haystack[i + j] != needle[j]) continue@outer
            return true
        }
        return false
    }

    private fun <T> onMain(block: () -> T): T {
        var result: Result<T>? = null
        instrumentation.runOnMainSync { result = runCatching(block) }
        return result!!.getOrThrow()
    }

    private fun awaitState(player: ExoPlayer, wanted: Int, error: () -> PlaybackException?) {
        waitFor("state $wanted") { error() != null || onMain { player.playbackState } == wanted }
        assertNull("no playback error: ${error()?.errorCodeName}", error())
    }

    private fun waitFor(what: String, timeoutMs: Long = 15_000, condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (!condition()) {
            if (SystemClock.elapsedRealtime() > deadline) throw AssertionError("timed out waiting for $what")
            SystemClock.sleep(50)
        }
    }
}
