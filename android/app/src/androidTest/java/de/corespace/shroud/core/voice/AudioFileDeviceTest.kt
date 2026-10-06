package de.corespace.shroud.core.voice

import android.graphics.BitmapFactory
import android.util.Log
import androidx.core.content.FileProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import de.corespace.shroud.core.keys.SealedLocalState
import de.corespace.shroud.core.media.LocalMediaCache
import de.corespace.shroud.core.media.SealedMediaDataSource
import de.corespace.shroud.core.media.files.AudioCoverThumb
import de.corespace.shroud.core.media.files.AudioMetadataReader
import de.corespace.shroud.core.media.files.AudioMetadataSource
import de.corespace.shroud.core.media.files.FileCategory
import de.corespace.shroud.core.media.files.FileIntake
import de.corespace.shroud.core.storage.StorageSeal
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

/**
 * Audio files on a real device (docs/file-sharing.md §11): the platform paths the unit tests fake.
 *
 * - The sender's §11.2 read: [FileIntake] over a `content://` URI with the real
 *   [AudioMetadataReader] (`MediaMetadataRetriever`): duration, title, artist, a square cover of at
 *   most 6 KB.
 * - Playback: each file lands in a real SHRM1 [LocalMediaCache] the way a download lands (a
 *   streamed cache writer), then plays through [ExoAudioFilePlayer] over [SealedMediaDataSource]:
 *   ready with its length, the position moves, a seek to the middle lands and plays on, the end is
 *   reported, an AIFF (no Media3 extractor) fails as unsupported. No plaintext copy is left behind.
 * - One sound at a time: an audio file starting stops a playing voice note and the other way round,
 *   wired as `VoiceModule` wires them, with the real ExoPlayers holding audio focus.
 *
 * Fixtures (`androidTest/assets/audio-files/`, 3 s sine tones, a 480 × 320 JPEG cover of 10 KB so the
 * cover ladder has to shrink it) were made with ffmpeg:
 * ```
 * ffmpeg -f lavfi -i "testsrc2=s=480x320:d=1" -frames:v 1 -q:v 6 cover.jpg
 * ffmpeg -f lavfi -i "sine=f=440:d=3" -i cover.jpg -map 0:a -map 1:v -ac 1 -ar 22050 -c:a libmp3lame -b:a 32k -c:v copy \
 *   -id3v2_version 3 -metadata title="Midnight City" -metadata artist="M83" -metadata:s:v comment="Cover (front)" song.mp3
 * ffmpeg -f lavfi -i "sine=f=523:d=3" -i cover.jpg -map 0:a -map 1:v -ac 1 -ar 22050 -c:a aac -b:a 32k -c:v copy \
 *   -disposition:v attached_pic -metadata title="Holocene" -metadata artist="Bon Iver" song.m4a
 * ffmpeg -f lavfi -i "sine=f=660:d=3" -i cover.jpg -map 0:a -map 1:v -ac 1 -ar 8000 -sample_fmt s16 -c:a flac -c:v copy \
 *   -disposition:v attached_pic -metadata title="Interview raw take" -metadata artist="Studio B" song.flac
 * ffmpeg -f lavfi -i "sine=f=330:d=3" -ac 1 -ar 8000 -c:a pcm_s16le song.wav
 * ffmpeg -f lavfi -i "sine=f=220:d=3" -ac 1 -ar 8000 -c:a pcm_s16be song.aiff
 * ```
 */
@RunWith(AndroidJUnit4::class)
class AudioFileDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val state = SealedLocalState()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var dir: File
    private lateinit var picks: File
    private lateinit var cache: LocalMediaCache

    private class Fixture(val name: String, val title: String?, val artist: String?, val hasCover: Boolean, val playable: Boolean = true)

    private val fixtures = listOf(
        Fixture("song.mp3", "Midnight City", "M83", hasCover = true),
        Fixture("song.m4a", "Holocene", "Bon Iver", hasCover = true),
        Fixture("song.flac", "Interview raw take", "Studio B", hasCover = true),
        Fixture("song.wav", null, null, hasCover = false),
        Fixture("song.aiff", null, null, hasCover = false, playable = false),
    )

    private fun asset(name: String): ByteArray = instrumentation.context.assets.open("audio-files/$name").use { it.readBytes() }

    @Before
    fun setUp() {
        state.unlock(ByteArray(32) { 0x3C })
        dir = File(context.noBackupFilesDir, "audio-file-test-" + UUID.randomUUID())
        cache = LocalMediaCache(dir, state, StorageSeal())
        picks = File(context.cacheDir, "audio-file-picks-" + UUID.randomUUID()).apply { mkdirs() }
    }

    @After
    fun tearDown() {
        scope.cancel()
        cache.clearAll()
        cache.close()
        state.lock()
        dir.deleteRecursively()
        picks.deleteRecursively()
    }

    // ---- §11.2: the sender's read through a ContentResolver URI ----

    @Test
    fun intakeReadsDurationTagsAndCoverThroughAContentUri() = runBlocking {
        val reader = AudioMetadataReader(context)
        val intake = FileIntake(context.contentResolver) { uri -> reader.read(AudioMetadataSource.Picked(uri)) }
        val uris = fixtures.map { fixture ->
            val file = File(picks, fixture.name).apply { writeBytes(asset(fixture.name)) }
            FileProvider.getUriForFile(context, context.packageName + ".cache", file)
        }
        val result = intake.inspect(uris)
        assertEquals(emptyList<String>(), result.refusals)
        assertEquals(fixtures.map { it.name }, result.files.map { it.name })
        for ((fixture, picked) in fixtures.zip(result.files)) {
            assertEquals(FileCategory.Audio, picked.type.category)
            val audio = picked.audio
            Log.i(TAG, "${fixture.name}: $audio ${audio?.cover}")
            if (!fixture.playable) continue // AIFF: whatever the retriever makes of it, the file still goes.
            assertNotNull(fixture.name, audio)
            audio!!
            assertTrue("${fixture.name} duration ${audio.durationMs}", audio.durationMs!! in 2_900..3_100)
            if (fixture.title != null) {
                assertEquals(fixture.name, fixture.title, audio.title)
                assertEquals(fixture.name, fixture.artist, audio.artist)
            }
            if (fixture.hasCover) {
                assertNotNull(fixture.name, audio.cover)
                val cover = audio.cover!!
                assertTrue("${fixture.name} cover ${cover.jpeg.size} B", cover.jpeg.size <= AudioCoverThumb.MAX_BYTES)
                assertEquals(cover.width, cover.height)
                assertTrue(cover.width in AudioCoverThumb.MIN_SIZE..AudioCoverThumb.START_SIZE)
                val decoded = BitmapFactory.decodeByteArray(cover.jpeg, 0, cover.jpeg.size)
                assertEquals(cover.width to cover.height, decoded.width to decoded.height)
            } else {
                assertNull(fixture.name, audio.cover)
            }
        }
    }

    @Test
    fun theSealedCopyOfAQueuedSendReadsTheSameWay() = runBlocking {
        val id = UUID.randomUUID()
        land(id, asset("song.mp3"))
        val audio = AudioMetadataReader(context).read(AudioMetadataSource.Sealed { de.corespace.shroud.core.media.SealedMediaDataSourceMdr.open(cache, id) })!!
        assertTrue(audio.durationMs!! in 2_900..3_100)
        assertEquals("Midnight City", audio.title)
        assertEquals("M83", audio.artist)
        assertTrue(audio.cover!!.jpeg.size <= AudioCoverThumb.MAX_BYTES)
    }

    // ---- §11.5: playback over the SHRM1 cache ----

    private class Events : AudioFilePlayer.Listener {
        @Volatile var readyMs: Long? = null
        @Volatile var ready = false
        @Volatile var ended = false
        @Volatile var error: Boolean? = null
        override fun onReady(durationMs: Long?) {
            readyMs = durationMs
            ready = true
        }
        override fun onEnded() {
            ended = true
        }
        override fun onError(unsupported: Boolean) {
            error = unsupported
        }
        override fun onPausedBySystem() = Unit
    }

    @Test
    fun everyPlayableFilePlaysSeeksAndEndsAndAnAiffFailsAsUnsupported() = runBlocking {
        for (fixture in fixtures) {
            val id = UUID.randomUUID()
            land(id, asset(fixture.name))
            withContext(Dispatchers.Main) {
                val player = ExoAudioFilePlayer(context) { SealedMediaDataSource.Factory(cache, it) }
                val events = Events()
                player.listener = events
                try {
                    player.load(id)
                    withTimeout(5_000) { while (!events.ready && events.error == null) delay(20) }
                    if (!fixture.playable) {
                        assertEquals("${fixture.name} fails as unsupported", true, events.error)
                        assertFalse(events.ready)
                        return@withContext
                    }
                    assertNull("${fixture.name} error", events.error)
                    assertTrue("${fixture.name} length ${events.readyMs}", events.readyMs!! in 2_900..3_100)

                    player.play()
                    withTimeout(3_000) { while (player.positionMs < 300) delay(20) }
                    assertTrue(player.isAdvancing)
                    val before = player.positionMs
                    delay(300)
                    assertTrue("${fixture.name} moves on", player.positionMs > before)

                    player.seekTo(1_500)
                    withTimeout(3_000) { while (player.positionMs < 1_500) delay(20) }
                    val landed = player.positionMs
                    assertTrue("${fixture.name} seek landed at $landed", landed in 1_500..2_000)
                    delay(300)
                    assertTrue("${fixture.name} plays on after the seek", player.positionMs > landed)

                    withTimeout(4_000) { while (!events.ended) delay(20) }
                    assertNull(events.error)
                    Log.i(TAG, "${fixture.name}: ready ${events.readyMs} ms, seek landed $landed ms, ended")
                } finally {
                    player.stop()
                }
            }
        }
        // No plaintext copy anywhere under the app's files (the picks folder is not used here).
        val needle = asset("song.flac").let { it.copyOfRange(it.size / 2, it.size / 2 + 64) }
        val leaks = listOf(context.filesDir, context.noBackupFilesDir, context.cacheDir).flatMap { root ->
            root.walkTopDown().filter { it.isFile && it.length() in needle.size..50_000_000 }.filter { contains(it.readBytes(), needle) }.map { it.name }.toList()
        }
        assertEquals(emptyList<String>(), leaks)
    }

    @Test
    fun theCoordinatorAdvancesToTheNextFileAndMarksAnAiffUnplayable() = runBlocking {
        val first = UUID.randomUUID()
        val aiff = UUID.randomUUID()
        val second = UUID.randomUUID()
        land(first, asset("song.wav"))
        land(aiff, asset("song.aiff"))
        land(second, asset("song.m4a"))
        val queue = mapOf(first to aiff, aiff to second)
        withContext(Dispatchers.Main) {
            val files = AudioFilePlaybackCoordinator(ExoAudioFilePlayer(context) { SealedMediaDataSource.Factory(cache, it) }, scope, nextAfter = { queue[it] })
            try {
                files.play(aiff)
                withTimeout(5_000) { while (!files.isUnplayable(aiff)) delay(20) }
                assertNull(files.state.value.activeId)
                files.play(first)
                withTimeout(5_000) { while (files.state.value.duration <= 0) delay(20) }
                files.seek(first, 0.8)
                // The end skips the unplayable AIFF and plays the M4A.
                withTimeout(6_000) { while (files.state.value.activeId != second) delay(20) }
                withTimeout(5_000) { while (files.liveProgress(second) <= 0.05) delay(20) }
                assertTrue(files.isPlaying(second))
            } finally {
                files.stop()
            }
        }
    }

    // ---- §11.5: one sound at a time ----

    @Test
    fun anAudioFileStopsAVoiceNoteAndTheOtherWayRound() = runBlocking {
        val file = UUID.randomUUID()
        val note = UUID.randomUUID()
        land(file, asset("song.mp3"))
        val noteBytes = asset("song.wav")
        withContext(Dispatchers.Main) {
            // As VoiceModule wires them.
            lateinit var files: AudioFilePlaybackCoordinator
            val voicePlayer = ExoVoicePlayer(context)
            val voice = VoicePlaybackCoordinator(voicePlayer, scope, onStarting = { files.stopForOtherSound() })
            val filePlayer = ExoAudioFilePlayer(context) { SealedMediaDataSource.Factory(cache, it) }
            files = AudioFilePlaybackCoordinator(filePlayer, scope, onStarting = { voice.stop() })
            try {
                voice.toggle(note, noteBytes)
                withTimeout(5_000) { while (!voicePlayer.isAdvancing) delay(20) }

                files.play(file)
                withTimeout(5_000) { while (!filePlayer.isAdvancing) delay(20) }
                assertNull("the note stopped", voice.state.value.activeId)
                assertFalse(voicePlayer.isAdvancing)
                delay(300)
                assertFalse("and stays stopped", voicePlayer.isAdvancing)
                assertTrue(filePlayer.isAdvancing)

                voice.toggle(note, noteBytes)
                withTimeout(5_000) { while (!voicePlayer.isAdvancing) delay(20) }
                assertNull("the file stopped", files.state.value.activeId)
                assertFalse(filePlayer.isAdvancing)
                delay(300)
                assertFalse(filePlayer.isAdvancing)
                assertTrue(voicePlayer.isAdvancing)
            } finally {
                voice.stop()
                files.stop()
            }
        }
    }

    /** [bytes] into the SHRM1 cache through a streamed writer, 64 KiB at a time — how a downloaded file lands. */
    private fun land(id: UUID, bytes: ByteArray) {
        val writer = cache.writer(id)
        try {
            var offset = 0
            while (offset < bytes.size) {
                val count = minOf(64 * 1024, bytes.size - offset)
                writer.write(bytes, offset, count)
                offset += count
            }
            writer.commit()
        } finally {
            writer.close()
        }
        assertTrue(cache.has(id))
    }

    private fun contains(hay: ByteArray, needle: ByteArray): Boolean {
        outer@ for (i in 0..hay.size - needle.size) {
            for (j in needle.indices) if (hay[i + j] != needle[j]) continue@outer
            return true
        }
        return false
    }

    private companion object {
        const val TAG = "AudioFileDeviceTest"
    }
}
