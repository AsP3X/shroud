package de.corespace.shroud.core.messaging

import de.corespace.shroud.core.media.MediaComposeQuality
import de.corespace.shroud.core.media.MediaImageSource
import de.corespace.shroud.core.media.PlainSource
import de.corespace.shroud.core.media.edit.MediaEdits
import de.corespace.shroud.core.model.Bytes
import de.corespace.shroud.core.model.ChatMessage
import de.corespace.shroud.core.model.ChatMessageKind
import de.corespace.shroud.core.model.MediaTransfer
import de.corespace.shroud.core.net.ContentType
import de.corespace.shroud.core.net.wire.Icu4jTextUnitsRule
import de.corespace.shroud.core.net.wire.LinkPreview
import de.corespace.shroud.core.net.wire.MediaMessagePayload
import de.corespace.shroud.testing.MainDispatcherRule
import de.corespace.shroud.testing.TempDirRule
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * Media on demand (`MediaHydrator`; messaging-core §13, §9.1; MC:2947-4105): downloads attach only
 * what landed for a live message, one job per id, and a payload missing from the cache is recovered
 * from history — an inbound envelope opened as recipient only once (the ratchet is one-shot).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MediaHydratorTest {
    @get:Rule val main = MainDispatcherRule()
    @get:Rule val textUnits = Icu4jTextUnitsRule()
    @get:Rule val temp = TempDirRule()

    private fun TestScope.world(meFirst: Boolean = true) = SendWorld(backgroundScope, main.dispatcher, temp.cacheDir, meFirst)

    private val image = ByteArray(25_000) { (it * 3).toByte() }

    /** The peer sends [bytes] as media of [kind]; the thread shows it as decoded, without bytes. */
    private suspend fun inbound(
        w: SendWorld,
        bytes: ByteArray,
        kind: ChatMessageKind = ChatMessageKind.Image,
        payloadKind: String = MediaMessagePayload.KIND_IMAGE,
        cachePayload: Boolean = true,
        caption: String? = null,
        text: String = "Photo",
        linkPreview: LinkPreview? = null,
    ): ChatMessage {
        val blob = w.transfers.upload(PlainSource.InMemory(bytes), "tok")
        w.transfers.uploads.clear()
        val payload = MediaMessagePayload(
            t = payloadKind, mime = "application/octet-stream", w = 640, h = 480, k = blob.keyBase64,
            c = caption, d = 2_500, s = bytes.size.toLong(), lp = linkPreview,
        ).encoded()
        val dto = w.peerSends(payload, ContentType.MEDIA, blob.mediaObjectId)
        if (cachePayload) w.store.savePlaintext(dto.id, payload)
        val message = ChatMessage(
            id = dto.id, peerUserId = w.peer, senderUserId = w.peer, text = text, createdAt = dto.createdAt,
            isMine = false, kind = kind, mediaObjectId = blob.mediaObjectId, mediaByteCount = bytes.size.toLong(),
            linkPreview = linkPreview,
        )
        w.state.put(w.peer, w.state.messages(w.peer).orEmpty() + message)
        return message
    }

    private fun SendWorld.held(id: java.util.UUID) = state.messages(peer)!!.single { it.id == id }

    @Test
    fun aTappedPhotoDownloadsWithTheCachedPayload() = runTest(main.dispatcher) {
        val w = world()
        val message = inbound(w, image)
        val hydrator = MediaHydrator(w.state, w.deps)
        hydrator.ensureImageLoaded(message)

        assertTrue(w.held(message.id).hasFullMedia)
        assertArrayEquals(image, w.media.files[message.id])
        assertArrayEquals(image, hydrator.mediaBytes(message.id))
        assertEquals(listOf(MediaTransfer.Phase.Transferring, MediaTransfer.Phase.Finishing), w.state.transfers.phases.mapNotNull { it.second })
        assertTrue(w.state.transfers.transfers.value.isEmpty())
        // The cached payload held the key: no history request, no open.
        assertTrue(w.identities.resolved.isEmpty())
    }

    @Test
    fun anInboundPayloadIsRecoveredByOneOpenAndThenFromTheCache() = runTest(main.dispatcher) {
        // The peer has the lower id: its message is a v3 ratchet envelope, readable once.
        val w = world(meFirst = false)
        val message = inbound(w, image, cachePayload = false)
        val hydrator = MediaHydrator(w.state, w.deps)
        val first = hydrator.payloadData(message)
        assertEquals(MediaMessagePayload.KIND_IMAGE, MediaMessagePayload.parse(first!!)!!.t)
        assertArrayEquals(first, w.store.plaintexts[message.id])
        // A second recovery reads the cache: a second ratchet open would fail.
        assertArrayEquals(first, hydrator.payloadData(message))
        assertEquals(1, w.identities.resolved.size)

        hydrator.ensureImageLoaded(message)
        assertTrue(w.held(message.id).hasFullMedia)
        assertArrayEquals(image, w.media.files[message.id])
    }

    @Test
    fun anyCachedPlaintextBlocksASecondInboundOpen() = runTest(main.dispatcher) {
        val w = world(meFirst = false)
        val message = inbound(w, image, cachePayload = false)
        w.store.savePlaintext(message.id, "Photo".toByteArray())
        val hydrator = MediaHydrator(w.state, w.deps)
        assertNull(hydrator.payloadData(message))
        hydrator.ensureImageLoaded(message)
        assertFalse(w.held(message.id).hasFullMedia)
        assertTrue(w.media.files.isEmpty())
        assertEquals(0, w.transfers.downloads)
    }

    @Test
    fun ourOwnPhotoIsRecoveredThroughTheSelfBox() = runTest(main.dispatcher) {
        val w = world()
        w.pipeline().sendImage(MediaImageSource.FileBytes(image), w.peer, "", MediaComposeQuality.Original, MediaEdits.Identity, null)
        val sent = w.state.messages(w.peer)!!.single()
        // A reinstall: neither the payload nor the bytes are on this device.
        w.store.plaintexts.clear()
        w.media.files.clear()
        w.state.put(w.peer, listOf(sent.copy(hasFullMedia = false)))

        MediaHydrator(w.state, w.deps).ensureImageLoaded(w.held(sent.id))
        assertTrue(w.held(sent.id).hasFullMedia)
        assertArrayEquals(image, w.media.files[sent.id])
        assertEquals(MediaMessagePayload.KIND_IMAGE, MediaMessagePayload.parse(w.store.plaintexts[sent.id]!!)!!.t)
        assertTrue(w.identities.resolved.isEmpty())
    }

    @Test
    fun aMessageDeletedWhileDownloadingKeepsNothing() = runTest(main.dispatcher) {
        val w = world()
        val message = inbound(w, image)
        val gate = CompletableDeferred<Unit>()
        w.transfers.gate = gate
        val hydrator = MediaHydrator(w.state, w.deps)
        val download = backgroundScope.async { hydrator.ensureImageLoaded(message) }
        // Deleted for everyone: the tombstone replaces the photo.
        w.state.update(message.id) { ChatMessage(it.id, it.peerUserId, it.senderUserId, "Message deleted", it.createdAt, isMine = false, deleted = true, kind = ChatMessageKind.Image) }
        gate.complete(Unit)
        download.await()

        assertFalse(w.held(message.id).hasFullMedia)
        assertTrue(w.media.files.isEmpty())
        assertTrue(w.state.transfers.transfers.value.isEmpty())
    }

    @Test
    fun cancelStopsTheDownloadAndEndsTheRing() = runTest(main.dispatcher) {
        val w = world()
        val message = inbound(w, image)
        w.transfers.gate = CompletableDeferred()
        val hydrator = MediaHydrator(w.state, w.deps)
        val download = backgroundScope.async { hydrator.ensureImageLoaded(message) }
        assertTrue(message.id in w.state.transfers.transfers.value)
        hydrator.cancel(message.id)
        download.await()
        assertTrue(w.state.transfers.transfers.value.isEmpty())
        assertFalse(w.held(message.id).hasFullMedia)
        assertTrue(w.media.files.isEmpty())
    }

    @Test
    fun concurrentCallersShareOneDownload() = runTest(main.dispatcher) {
        val w = world()
        val message = inbound(w, image)
        val gate = CompletableDeferred<Unit>()
        w.transfers.gate = gate
        val hydrator = MediaHydrator(w.state, w.deps)
        val first = backgroundScope.async { hydrator.ensureImageLoaded(message) }
        val second = backgroundScope.async { hydrator.ensureImageLoaded(message) }
        gate.complete(Unit)
        first.await()
        second.await()
        assertEquals(1, w.transfers.downloads)
        assertTrue(w.held(message.id).hasFullMedia)
    }

    @Test
    fun aPurgeOrALockStopsDownloads() = runTest(main.dispatcher) {
        val w = world()
        val message = inbound(w, image)
        w.transfers.gate = CompletableDeferred()
        val hydrator = MediaHydrator(w.state, w.deps)
        val download = backgroundScope.async { hydrator.ensureImageLoaded(message) }
        hydrator.onPurged(listOf(message.id))
        download.await()
        assertTrue(w.state.transfers.transfers.value.isEmpty())

        val again = backgroundScope.async { hydrator.ensureImageLoaded(message) }
        hydrator.onSensitiveMemoryLocked()
        again.await()
        assertTrue(w.media.files.isEmpty())
    }

    @Test
    fun aCachedFileAttachesWithoutDownloading() = runTest(main.dispatcher) {
        val w = world()
        val message = inbound(w, image)
        w.media.files[message.id] = image
        MediaHydrator(w.state, w.deps).ensureImageLoaded(message)
        assertTrue(w.held(message.id).hasFullMedia)
        assertEquals(0, w.transfers.downloads)
    }

    @Test
    fun aFlagWhoseFileWentDownloadsAgain() = runTest(main.dispatcher) {
        val w = world()
        val message = inbound(w, image)
        w.state.update(message.id) { it.copy(hasFullMedia = true) }
        MediaHydrator(w.state, w.deps).ensureImageLoaded(w.held(message.id))
        assertEquals(1, w.transfers.downloads)
        assertArrayEquals(image, w.media.files[message.id])
    }

    @Test
    fun aVoiceNoteLoadsWithItsDurationAndTranscript() = runTest(main.dispatcher) {
        val w = world()
        val audio = ByteArray(9_000) { 1 }
        val message = inbound(w, audio, ChatMessageKind.Voice, MediaMessagePayload.KIND_VOICE, caption = "on my way", text = "on my way")
        MediaHydrator(w.state, w.deps).ensureVoiceLoaded(message)
        val held = w.held(message.id)
        assertTrue(held.hasFullMedia)
        assertEquals(2_500, held.durationMs)
        assertEquals("on my way", held.transcript)
        // Voice has no ring (MC:3839-3858).
        assertTrue(w.state.transfers.phases.isEmpty())
    }

    @Test
    fun aVideoLoadsWithAPosterAndFillsAGenericCaption() = runTest(main.dispatcher) {
        val w = world()
        val clip = ByteArray(60_000) { 2 }
        val message = inbound(w, clip, ChatMessageKind.Video, MediaMessagePayload.KIND_VIDEO, caption = "The ridge", text = "Video")
        MediaHydrator(w.state, w.deps).ensureVideoLoaded(message)
        val held = w.held(message.id)
        assertTrue(held.hasFullMedia)
        assertEquals(ChatMessageKind.Video, held.kind)
        assertEquals("The ridge", held.text)
        assertEquals(640, held.imageWidth)
        assertEquals(2_500, held.durationMs)
        assertEquals(Bytes.of(w.video.posterFromLocal!!), held.posterJpeg)
        assertEquals(listOf(message.id to MediaHydrator.POSTER_EDGE), w.video.posterRequests)
        assertEquals(listOf(MediaTransfer.Phase.Transferring, MediaTransfer.Phase.Finishing), w.state.transfers.phases.mapNotNull { it.second })
    }

    @Test
    fun aLargeLinkImageLoadsWhenItsBubbleAppears() = runTest(main.dispatcher) {
        val w = world()
        val preview = LinkPreview(url = "https://example.com/a", title = "A page")
        val message = inbound(w, image, ChatMessageKind.Text, MediaMessagePayload.KIND_LINK, caption = "see", text = "see", linkPreview = preview)
        MediaHydrator(w.state, w.deps).ensureLinkImageLoaded(message)
        assertTrue(w.held(message.id).hasFullMedia)
        assertArrayEquals(image, w.media.files[message.id])
    }

    @Test
    fun wrongKindsAndTombstonesAreLeftAlone() = runTest(main.dispatcher) {
        val w = world()
        val message = inbound(w, image)
        val hydrator = MediaHydrator(w.state, w.deps)
        hydrator.ensureVideoLoaded(message)
        hydrator.ensureVoiceLoaded(message)
        hydrator.ensureLinkImageLoaded(message)
        hydrator.ensureImageLoaded(message.copy(deleted = true))
        assertEquals(0, w.transfers.downloads)
    }
}
