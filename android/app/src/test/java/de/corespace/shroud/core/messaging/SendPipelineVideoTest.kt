package de.corespace.shroud.core.messaging

import android.net.Uri
import de.corespace.shroud.core.media.PlainSource
import de.corespace.shroud.core.media.video.VideoSendPlan
import de.corespace.shroud.core.media.video.VideoUploadQuality
import de.corespace.shroud.core.model.Bytes
import de.corespace.shroud.core.model.ChatMessageKind
import de.corespace.shroud.core.model.MediaTransfer
import de.corespace.shroud.core.model.NOTES_PEER_ID
import de.corespace.shroud.core.model.ReceiptStatus
import de.corespace.shroud.core.net.ContentType
import de.corespace.shroud.core.net.wire.Icu4jTextUnitsRule
import de.corespace.shroud.core.net.wire.MediaMessagePayload
import de.corespace.shroud.testing.MainDispatcherRule
import de.corespace.shroud.testing.TempDirRule
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Videos (`sendVideo` / `finishVideoSend` / `retryFailedVideo`, MC:2571-2941; media-voice-links §3.2)
 * through a file-backed `VideoPipeline` fake (plan C28): the bubble lands before the encode, the
 * encoder's `shroud-*` file goes into the sealed cache and is deleted, the upload reads the cache, one
 * ring moves preparing → transferring → finishing, and the bubble is re-keyed. Robolectric only for
 * `android.net.Uri` in `VideoSendPlan`.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SendPipelineVideoTest {
    @get:Rule val main = MainDispatcherRule()
    @get:Rule val textUnits = Icu4jTextUnitsRule()
    @get:Rule val temp = TempDirRule()

    private fun TestScope.world() = SendWorld(backgroundScope, main.dispatcher, temp.cacheDir)

    private val planPoster = Bytes.of(ByteArray(700) { 4 })

    private fun plan(quality: VideoUploadQuality = VideoUploadQuality.High, caption: String = "") = VideoSendPlan(
        sourceUri = Uri.parse("content://media/picker/0/com.android.providers.media.photopicker/media/1000000042"),
        caption = caption,
        quality = quality,
        posterJpeg = planPoster,
        width = 1920,
        height = 1080,
        durationMs = 4_000,
        estimatedBytes = 2_000_000,
    )

    @Test
    fun aVideoIsSealedFromTheCacheAndRekeyed() = runTest(main.dispatcher) {
        val w = world()
        val error = w.pipeline().sendVideo(plan(caption = " Ridge "), w.peer, null)
        assertNull(error)

        val request = w.server.lastRequest()
        val dto = w.server.dtoFor(request)
        val optimisticId = request.clientMessageId
        assertEquals(ContentType.MEDIA, request.contentType)
        // The upload read the sealed cache, never a plaintext file; the encoder's file is gone (§1.1 rule 7).
        assertEquals(PlainSource.LocalMedia(optimisticId), w.transfers.uploads.single())
        assertFalse(w.video.lastFile!!.exists())

        val payload = MediaMessagePayload.parse(w.openAsPeer(request))!!
        assertEquals(MediaMessagePayload.KIND_VIDEO, payload.t)
        assertEquals("video/mp4", payload.mime)
        assertEquals(1280, payload.w)
        assertEquals(720, payload.h)
        assertEquals(4_200, payload.d)
        assertEquals("Ridge", payload.c)
        assertEquals(w.video.output.size.toLong(), payload.s)
        assertArrayEquals(w.images.preview, payload.previewJpeg)
        assertArrayEquals(w.video.output, w.transfers.open(request.mediaObjectId!!, payload.k))

        val sent = w.state.messages(w.peer)!!.single()
        assertEquals(dto.id, sent.id)
        assertEquals(ChatMessageKind.Video, sent.kind)
        assertEquals("Ridge", sent.text)
        assertEquals(1280, sent.imageWidth)
        assertEquals(4_200, sent.durationMs)
        assertEquals(w.video.output.size.toLong(), sent.mediaByteCount)
        assertEquals(Bytes.of(w.images.preview!!), sent.posterJpeg)
        assertEquals(Bytes.of(w.images.preview!!), sent.previewJpeg)
        assertTrue(sent.hasFullMedia)
        assertArrayEquals(w.video.output, w.media.files[dto.id])
        assertNull(w.media.files[optimisticId])

        // One ring: preparing → transferring (sealed size) → finishing, then gone (MC:2611-2616, 2835-2853).
        assertEquals(
            listOf(MediaTransfer.Phase.Preparing, MediaTransfer.Phase.Transferring, MediaTransfer.Phase.Finishing),
            w.state.transfers.phases.mapNotNull { it.second },
        )
        assertTrue(w.state.transfers.transfers.value.isEmpty())
        assertTrue(w.state.transfers.fractions.isNotEmpty())
    }

    @Test
    fun withoutAnEncoderPosterOneIsSampledFromTheCache() = runTest(main.dispatcher) {
        val w = world()
        w.video.poster = null
        w.pipeline().sendVideo(plan(), w.peer, null)
        val optimisticId = w.server.lastRequest().clientMessageId
        assertEquals(listOf(optimisticId to 320), w.video.posterRequests)
        assertArrayEquals(w.images.preview, MediaMessagePayload.parse(w.openAsPeer(w.server.lastRequest()))!!.previewJpeg)
    }

    @Test
    fun tooLargeAndUnreadableVideosSayWhy() = runTest(main.dispatcher) {
        val w = world()
        w.video.failure = SendFakeVideoTooLarge()
        assertEquals(
            "This video is too large to send at original quality. Trim it or choose a lower quality.",
            w.pipeline().sendVideo(plan(VideoUploadQuality.Original), w.peer, null),
        )
        assertEquals(
            "This video is too large even after compression. Try a shorter clip.",
            w.pipeline().sendVideo(plan(VideoUploadQuality.Medium), w.peer, null),
        )
        w.video.failure = IllegalStateException("codec")
        assertEquals("Could not prepare that video.", w.pipeline().sendVideo(plan(), w.peer, null))

        val bubbles = w.state.messages(w.peer)!!
        assertEquals(3, bubbles.size)
        assertTrue(bubbles.all { it.receipt == ReceiptStatus.Failed && it.pendingSync })
        assertEquals("Could not prepare that video.", bubbles.last().sendError)
        assertTrue(w.state.transfers.transfers.value.isEmpty())
        assertTrue(w.server.requests.isEmpty())
    }

    @Test
    fun anOfflineVideoWaitsAndTheFlushSendsItFromTheCache() = runTest(main.dispatcher) {
        val w = world()
        w.online = false
        val pipeline = w.pipeline()
        assertNull(pipeline.sendVideo(plan(caption = "later"), w.peer, null))
        val failed = w.state.messages(w.peer)!!.single()
        assertEquals("Waiting for connection…", failed.sendError)
        assertTrue(failed.hasFullMedia)
        assertArrayEquals(w.video.output, w.media.files[failed.id])
        assertTrue(w.state.transfers.transfers.value.isEmpty())

        w.online = true
        pipeline.flushOutbox()
        val request = w.server.lastRequest()
        assertEquals(failed.id, request.clientMessageId)
        val payload = MediaMessagePayload.parse(w.openAsPeer(request))!!
        assertEquals("later", payload.c)
        assertEquals(4_200, payload.d)
        assertEquals(w.server.dtoFor(request).id, w.state.messages(w.peer)!!.single().id)
    }

    @Test
    fun aFailedVideoRetriesFromTheCacheWithItsClientId() = runTest(main.dispatcher) {
        val w = world()
        val pipeline = w.pipeline()
        w.transfers.failUploads = 1
        assertEquals("The network connection was lost.", pipeline.sendVideo(plan(), w.peer, null))
        val failed = w.state.messages(w.peer)!!.single()
        assertEquals(ReceiptStatus.Failed, failed.receipt)

        assertNull(pipeline.retryFailedVideo(failed.id, w.peer))
        val request = w.server.lastRequest()
        assertEquals(failed.id, request.clientMessageId)
        assertEquals(w.server.dtoFor(request).id, w.state.messages(w.peer)!!.single().id)
        assertTrue(w.state.transfers.transfers.value.isEmpty())
    }

    @Test
    fun aNotesVideoIsRekeyedIntoNotes() = runTest(main.dispatcher) {
        val w = world()
        assertNull(w.pipeline().sendVideo(plan(), NOTES_PEER_ID, null))
        val request = w.server.lastRequest()
        assertEquals(w.me, request.peerUserId)
        val note = w.state.messages(NOTES_PEER_ID)!!.single()
        assertEquals(w.server.dtoFor(request).id, note.id)
        assertEquals(ChatMessageKind.Video, note.kind)
        assertEquals(ReceiptStatus.Sent, note.receipt)
        assertEquals(4_200, note.durationMs)
        assertNull(w.state.messages(w.me))
        assertTrue(w.state.transfers.transfers.value.isEmpty())
    }

    @Test
    fun aPassthroughFileOutsideOurTempFilesIsKept() = runTest(main.dispatcher) {
        val w = world()
        val passthrough = temp.file("DCIM/clip.mp4").apply { writeBytes(w.video.output) }
        val pipeline = SendPipeline(
            w.state,
            { w.host },
            w.deps.let { deps ->
                SendDependencies(
                    deps.api, deps.keyring, deps.crypto, deps.peerLocks, deps.peerIdentities, deps.store, deps.media, deps.transfers,
                    deps.images, { PassthroughVideo(passthrough) }, deps.isOnline, deps.clock, deps.scope, deps.compute, deps.io,
                )
            },
        )
        assertNull(pipeline.sendVideo(plan(), w.peer, null))
        assertTrue(passthrough.exists())
    }

    /** A pipeline handing back a file it does not own (a passthrough). */
    private class PassthroughVideo(private val file: java.io.File) : de.corespace.shroud.core.media.VideoPipeline {
        override suspend fun encode(plan: VideoSendPlan, onProgress: ((Double) -> Unit)?) =
            de.corespace.shroud.core.media.EncodedVideo(file, 1280, 720, 1_000, "video/mp4", null, file.length())
        override suspend fun posterJpegFromLocal(messageId: java.util.UUID, maxEdgePx: Int): ByteArray? = null
        override suspend fun durationMs(messageId: java.util.UUID): Int? = null
        override val maxSealedBytes: Long = Long.MAX_VALUE
    }
}
