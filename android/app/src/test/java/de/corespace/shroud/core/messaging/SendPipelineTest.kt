package de.corespace.shroud.core.messaging

import de.corespace.shroud.core.crypto.B64
import de.corespace.shroud.core.crypto.MediaCrypto
import de.corespace.shroud.core.links.LinkPreviewAttachment
import de.corespace.shroud.core.media.MediaComposeQuality
import de.corespace.shroud.core.media.MediaImageSource
import de.corespace.shroud.core.media.PlainSource
import de.corespace.shroud.core.media.edit.MediaEdits
import de.corespace.shroud.core.model.Bytes
import de.corespace.shroud.core.model.ChatMessage
import de.corespace.shroud.core.model.ChatMessageKind
import de.corespace.shroud.core.model.NOTES_PEER_ID
import de.corespace.shroud.core.model.PeerIdentityChangedException
import de.corespace.shroud.core.model.ReceiptStatus
import de.corespace.shroud.core.net.ApiError
import de.corespace.shroud.core.net.ContentType
import de.corespace.shroud.core.net.wire.Icu4jTextUnitsRule
import de.corespace.shroud.core.net.wire.LinkPreview
import de.corespace.shroud.core.net.wire.MediaMessagePayload
import de.corespace.shroud.core.net.wire.MessageAnnotation
import de.corespace.shroud.core.net.wire.MessageReplyReference
import de.corespace.shroud.core.net.wire.MessageTextPayload
import de.corespace.shroud.testing.MainDispatcherRule
import de.corespace.shroud.testing.TempDirRule
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.util.UUID

/**
 * The send paths of `SendPipeline` (messaging-core §11; iOS `MessagingController.swift`, MC below):
 * every path ends with one bubble under the server's id and nothing cached under the client id
 * (memory: *Server re-keys sent messages*), retries reuse `client_message_id`, the envelope and
 * payload budgets give the iOS texts, and what was sealed opens on the other side.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SendPipelineTest {
    @get:Rule val main = MainDispatcherRule()
    @get:Rule val textUnits = Icu4jTextUnitsRule()
    @get:Rule val temp = TempDirRule()

    private fun TestScope.world(meFirst: Boolean = true) = SendWorld(backgroundScope, main.dispatcher, temp.cacheDir, meFirst)

    private val photo = ByteArray(40_000) { (it * 7).toByte() }

    // ---- text ----

    @Test
    fun textIsRekeyedToTheServerIdAndItsWireCached() = runTest(main.dispatcher) {
        val w = world()
        w.pipeline().sendText("  hello there \n", w.peer, null, null)

        val request = w.server.lastRequest()
        val dto = w.server.dtoFor(request)
        assertEquals(ContentType.TEXT, request.contentType)
        assertEquals(w.peer, request.peerUserId)
        assertNotEquals(request.clientMessageId, dto.id)
        val sent = w.state.messages(w.peer)!!.single()
        assertEquals(dto.id, sent.id)
        assertEquals("hello there", sent.text)
        assertEquals(ReceiptStatus.Sent, sent.receipt)
        assertFalse(sent.pendingSync)
        assertEquals(dto.createdAtWire, sent.createdAtWire)
        // What was sealed is cached under the server id; nothing stays under the client id (MC:4851-4852).
        assertEquals("hello there", w.store.plaintextString(dto.id))
        assertNull(w.store.plaintexts[request.clientMessageId])
        assertEquals(listOf(request.clientMessageId to dto.id), w.state.rekeys)
        assertEquals("hello there", String(w.openAsPeer(request)))
        assertEquals(1, w.host.refreshes)
        assertNull(w.state.shownError)
    }

    @Test
    fun theHigherIdSealsV2AndTheLowerV3() = runTest(main.dispatcher) {
        for (meFirst in listOf(true, false)) {
            val w = world(meFirst)
            w.pipeline().sendText("hi", w.peer, null, null)
            val envelope = String(MessageCryptoFromWire(w.server.lastRequest().ciphertext))
            assertTrue(envelope, envelope.contains(if (meFirst) "\"v\":3" else "\"v\":2"))
            assertEquals("hi", String(w.openAsPeer(w.server.lastRequest())))
        }
    }

    @Test
    fun aReplyAndALinkPreviewAreSealedInTheEnvelope() = runTest(main.dispatcher) {
        val w = world()
        val quoted = MessageReplyReference(UUID.randomUUID(), w.peer, MessageReplyReference.Kind.Image, "At the trailhead")
        val preview = LinkPreview(url = "https://komoot.com/tour/1398273", siteName = "komoot", title = "Herzogstand – Heimgarten ridge walk",
            thumbnail = Bytes.of(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte())))
        w.pipeline().sendText("look https://komoot.com/tour/1398273", w.peer, quoted, LinkPreviewAttachment(preview, null, null, null))

        val parsed = MessageTextPayload.parse(w.openAsPeer(w.server.lastRequest()))
        assertEquals("look https://komoot.com/tour/1398273", parsed.body)
        assertEquals(quoted, parsed.replyTo)
        assertEquals(preview, parsed.linkPreview)
        val sent = w.state.messages(w.peer)!!.single()
        assertEquals(quoted, sent.replyTo)
        assertEquals(preview, sent.linkPreview)
        // The cache holds the same wire, so a reload rebuilds the same bubble.
        assertEquals(MessageTextPayload.wire("look https://komoot.com/tour/1398273", quoted, preview), w.store.plaintextString(sent.id))
    }

    @Test
    fun aLongTextLosesThePreviewThumbnailFirst() = runTest(main.dispatcher) {
        // `textWire` budget (LinkPreviewPayloadTests.testTextWireDropsThumbnailThenPreviewForLongMessages).
        val w = world()
        val preview = LinkPreview(url = "https://example.com/a", title = "Title", thumbnail = Bytes.of(ByteArray(5 * 1024) { 1 }))
        w.pipeline().sendText("a".repeat(7 * 1024), w.peer, null, LinkPreviewAttachment(preview, null, null, null))
        assertEquals(preview.withoutThumbnail(), MessageTextPayload.parse(w.openAsPeer(w.server.lastRequest())).linkPreview)

        w.pipeline().sendText("b".repeat(13 * 1024), w.peer, null, LinkPreviewAttachment(preview, null, null, null))
        assertEquals("b".repeat(13 * 1024), String(w.openAsPeer(w.server.lastRequest())))
        assertNull(w.state.messages(w.peer)!!.last().linkPreview)
    }

    @Test
    fun offlineTextWaitsQueuedAndTheFlushReusesItsClientId() = runTest(main.dispatcher) {
        val w = world()
        val pipeline = w.pipeline()
        w.online = false
        pipeline.sendText("later", w.peer, null, null)

        val queued = w.state.messages(w.peer)!!.single()
        assertTrue(queued.pendingSync)
        assertEquals(ReceiptStatus.Sending, queued.receipt)
        assertTrue(w.host.wentOffline)
        assertTrue(w.server.requests.isEmpty())

        w.online = true
        pipeline.flushOutbox()
        val request = w.server.lastRequest()
        assertEquals(queued.id, request.clientMessageId)
        val sent = w.state.messages(w.peer)!!.single()
        assertEquals(w.server.dtoFor(request).id, sent.id)
        assertFalse(sent.pendingSync)
    }

    @Test
    fun aRetryAfterALostAnswerIsIdempotentAndLeavesOneBubble() = runTest(main.dispatcher) {
        val w = world()
        val pipeline = w.pipeline()
        // The server stored the message, but its answer never came back.
        w.server.loseNextAnswer = true
        pipeline.sendText("once", w.peer, null, null)

        val queued = w.state.messages(w.peer)!!.single()
        assertTrue(queued.pendingSync)
        assertEquals(ReceiptStatus.Sending, queued.receipt)
        assertNull(queued.sendError)
        assertEquals("The request timed out.", w.state.shownError)
        assertTrue(w.host.wentOffline)

        pipeline.flushOutbox()
        assertEquals(2, w.server.requests.size)
        assertEquals(w.server.requests[0].clientMessageId, w.server.requests[1].clientMessageId)
        // One row on the server, one bubble here.
        assertEquals(1, w.server.messages.size)
        assertEquals(listOf(w.server.messages.single().id), w.state.messages(w.peer)!!.map { it.id })
    }

    @Test
    fun overlappingFlushesSendOnce() = runTest(main.dispatcher) {
        val w = world()
        val pipeline = w.pipeline()
        w.online = false
        pipeline.sendText("one", w.peer, null, null)
        pipeline.sendText("two", w.peer, null, null)
        w.online = true

        val gate = CompletableDeferred<Unit>()
        w.server.sendFailures.clear()
        val first = backgroundScope.async { gate.await(); pipeline.flushOutbox() }
        val second = backgroundScope.async { gate.await(); pipeline.flushOutbox() }
        gate.complete(Unit)
        first.await()
        second.await()

        assertEquals(listOf("one", "two"), w.state.messages(w.peer)!!.map { it.text })
        assertEquals(2, w.server.requests.size)
        assertTrue(w.state.messages(w.peer)!!.none { it.pendingSync })
    }

    @Test
    fun aKeyChangeKeepsTheMessageQueuedWithTheIosText() = runTest(main.dispatcher) {
        val w = world()
        w.identities.changed += w.peer
        w.pipeline().sendText("hello", w.peer, null, null)

        assertEquals(PeerIdentityChangedException.MESSAGE, w.state.shownError)
        assertEquals("This contact's encryption key changed. Verify their safety number before sending.", w.state.shownError)
        assertTrue(w.state.messages(w.peer)!!.single().pendingSync)
        assertTrue(w.server.requests.isEmpty())
    }

    @Test
    fun aSendThatLandsAfterALockPublishesNothing() = runTest(main.dispatcher) {
        val w = world()
        val pipeline = w.pipeline()
        val gate = CompletableDeferred<Unit>()
        w.server.sendGate = gate
        val send = backgroundScope.async { pipeline.sendText("hello", w.peer, null, null) }
        assertEquals(1, w.server.requests.size)
        // Chats lock while the request is out; the answer comes after.
        w.state.lock()
        gate.complete(Unit)
        send.await()
        assertNull(w.state.messages(w.peer))
        assertTrue(w.state.rekeys.isEmpty())
    }

    @Test
    fun aSendOutlivesTheScreenThatStartedIt() = runTest(main.dispatcher) {
        val w = world()
        val pipeline = w.pipeline()
        val gate = CompletableDeferred<Unit>()
        w.server.sendGate = gate
        // The composer's scope goes away while the request is out (an iOS `Task` keeps going too).
        val screen = backgroundScope.async { pipeline.sendText("still sent", w.peer, null, null) }
        screen.cancel()
        gate.complete(Unit)
        assertEquals(w.server.dtoFor(w.server.lastRequest()).id, w.state.messages(w.peer)!!.single().id)
    }

    @Test
    fun blankTextSendsNothing() = runTest(main.dispatcher) {
        val w = world()
        w.pipeline().sendText(" \n\t ", w.peer, null, null)
        assertNull(w.state.messages(w.peer))
        assertTrue(w.server.requests.isEmpty())
    }

    // ---- link with a large image ----

    @Test
    fun aLargeLinkImageGoesAsALinkMediaMessage() = runTest(main.dispatcher) {
        val w = world()
        val image = ByteArray(30_000) { 5 }
        val preview = LinkPreview(url = "https://www.example.com/a", title = "A page", thumbnail = Bytes.of(ByteArray(100) { 2 }))
        w.pipeline().sendText("see https://www.example.com/a", w.peer, null, LinkPreviewAttachment(preview, Bytes.of(image), 1200, 630))

        val request = w.server.lastRequest()
        assertEquals(ContentType.MEDIA, request.contentType)
        val payload = MediaMessagePayload.parse(w.openAsPeer(request))!!
        assertEquals(MediaMessagePayload.KIND_LINK, payload.t)
        assertEquals("image/jpeg", payload.mime)
        assertEquals(1200, payload.w)
        assertEquals(630, payload.h)
        assertEquals("see https://www.example.com/a", payload.c)
        assertEquals(30_000L, payload.s)
        // The blob is the picture; the `lp` loses its thumbnail, `th` is the blurred placeholder (MC:1727-1729).
        assertEquals(preview.withoutThumbnail(), payload.lp)
        assertArrayEquals(w.images.preview, payload.previewJpeg)
        assertArrayEquals(image, w.transfers.open(request.mediaObjectId!!, payload.k))

        val sent = w.state.messages(w.peer)!!.single()
        val dto = w.server.dtoFor(request)
        assertEquals(dto.id, sent.id)
        assertEquals(ChatMessageKind.Text, sent.kind)
        assertEquals(request.mediaObjectId, sent.mediaObjectId)
        assertTrue(sent.hasFullMedia)
        assertArrayEquals(image, w.media.files[dto.id])
        assertNull(w.media.files[request.clientMessageId])
        assertNotNull(MediaMessagePayload.parse(w.store.plaintexts[dto.id]!!))
    }

    @Test
    fun aFailedLargeImageUploadFallsBackToTheInlineThumbnail() = runTest(main.dispatcher) {
        val w = world()
        w.transfers.failUploads = 1
        val preview = LinkPreview(url = "https://example.com/a", title = "A page", thumbnail = Bytes.of(ByteArray(100) { 2 }))
        w.pipeline().sendText("see this", w.peer, null, LinkPreviewAttachment(preview, Bytes.of(ByteArray(30_000) { 5 }), 1200, 630))

        val request = w.server.lastRequest()
        assertEquals(ContentType.TEXT, request.contentType)
        assertEquals(preview, MessageTextPayload.parse(w.openAsPeer(request)).linkPreview)
        val sent = w.state.messages(w.peer)!!.single()
        assertFalse(sent.hasFullMedia)
        assertNull(sent.mediaObjectId)
        assertNull(sent.imageWidth)
        assertTrue(w.media.files.isEmpty())
    }

    @Test
    fun offlineALinkMessageQueuesWithItsThumbnailOnly() = runTest(main.dispatcher) {
        val w = world()
        w.online = false
        val preview = LinkPreview(url = "https://example.com/a", title = "A page")
        w.pipeline().sendText("see this", w.peer, null, LinkPreviewAttachment(preview, Bytes.of(ByteArray(30_000) { 5 }), 1200, 630))
        val queued = w.state.messages(w.peer)!!.single()
        assertFalse(queued.hasFullMedia)
        assertNull(queued.imageWidth)
        assertTrue(w.media.files.isEmpty())
    }

    // ---- Notes ----

    @Test
    fun aNoteSyncsToOurselvesAndIsRekeyedInsideNotes() = runTest(main.dispatcher) {
        val w = world()
        w.pipeline().sendText("remember the milk", NOTES_PEER_ID, null, null)

        val request = w.server.lastRequest()
        val dto = w.server.dtoFor(request)
        assertEquals(w.me, request.peerUserId)
        val note = w.state.messages(NOTES_PEER_ID)!!.single()
        assertEquals(dto.id, note.id)
        assertEquals(NOTES_PEER_ID, note.peerUserId)
        assertEquals(ReceiptStatus.Sent, note.receipt)
        assertEquals("remember the milk", note.text)
        // Never a second chat under our own id (MC:4799-4807).
        assertNull(w.state.messages(w.me))
        assertEquals("remember the milk", w.store.plaintextString(dto.id))
        assertNull(w.store.plaintexts[request.clientMessageId])
        assertEquals("remember the milk", String(w.openAsSender(request)))
    }

    @Test
    fun aNoteOfflineStaysLocalAndIsNotQueued() = runTest(main.dispatcher) {
        val w = world()
        w.online = false
        w.pipeline().sendText("local", NOTES_PEER_ID, null, null)
        val note = w.state.messages(NOTES_PEER_ID)!!.single()
        assertFalse(note.pendingSync)
        assertEquals("local", w.store.plaintextString(note.id))
        w.online = true
        w.pipeline().flushOutbox()
        assertTrue(w.server.requests.isEmpty())
    }

    @Test
    fun aNoteWhoseSyncFailedIsMarkedPendingButNeverFlushed() = runTest(main.dispatcher) {
        // messaging-core D6: iOS parity.
        val w = world()
        w.server.sendFailures += ApiError.Transport("offline")
        val pipeline = w.pipeline()
        pipeline.sendText("x", NOTES_PEER_ID, null, null)
        assertTrue(w.state.messages(NOTES_PEER_ID)!!.single().pendingSync)
        pipeline.flushOutbox()
        assertEquals(1, w.server.requests.size)
    }

    @Test
    fun todosSyncWithTheirMarkerAndToggleLocally() = runTest(main.dispatcher) {
        val w = world()
        val pipeline = w.pipeline()
        pipeline.sendTodo("  Buy milk ")
        val request = w.server.lastRequest()
        assertEquals("[todo:0]Buy milk", String(w.openAsSender(request)))
        val todo = w.state.messages(NOTES_PEER_ID)!!.single()
        assertEquals(ChatMessageKind.Todo, todo.kind)
        assertEquals("Buy milk", todo.text)
        assertEquals(false, todo.todoDone)
        assertEquals("[todo:0]Buy milk", w.store.plaintextString(todo.id))

        pipeline.toggleTodo(todo.id)
        assertEquals(true, w.state.messages(NOTES_PEER_ID)!!.single().todoDone)
        pipeline.toggleTodo(todo.id)
        assertEquals(false, w.state.messages(NOTES_PEER_ID)!!.single().todoDone)
        // Local only: no re-send.
        assertEquals(1, w.server.requests.size)
    }

    @Test
    fun deletingALocalNotePurgesItsArtifactsEvenWhenTheRowIsGone() = runTest(main.dispatcher) {
        val w = world()
        w.online = false
        val pipeline = w.pipeline()
        pipeline.sendText("gone", NOTES_PEER_ID, null, null)
        val note = w.state.messages(NOTES_PEER_ID)!!.single()
        pipeline.deleteLocalNote(note.id)
        assertTrue(w.state.messages(NOTES_PEER_ID)!!.isEmpty())
        assertNull(w.store.plaintexts[note.id])
        assertTrue(note.id in w.state.purged)

        val stray = UUID.randomUUID()
        pipeline.deleteLocalNote(stray)
        assertTrue(stray in w.state.purged)
    }

    @Test
    fun aNotesPhotoIsRekeyedIntoNotes() = runTest(main.dispatcher) {
        val w = world()
        val error = w.pipeline().sendImage(MediaImageSource.FileBytes(photo), NOTES_PEER_ID, "", MediaComposeQuality.Original, MediaEdits.Identity, null)
        assertNull(error)

        val request = w.server.lastRequest()
        val dto = w.server.dtoFor(request)
        assertEquals(w.me, request.peerUserId)
        val note = w.state.messages(NOTES_PEER_ID)!!.single()
        assertEquals(dto.id, note.id)
        assertEquals(ChatMessageKind.Image, note.kind)
        assertEquals(ReceiptStatus.Sent, note.receipt)
        assertEquals(request.mediaObjectId, note.mediaObjectId)
        assertTrue(note.hasFullMedia)
        assertNull(w.state.messages(w.me))
        assertArrayEquals(photo, w.media.files[dto.id])
        assertNull(w.media.files[request.clientMessageId])
        assertEquals(MediaMessagePayload.KIND_IMAGE, MediaMessagePayload.parse(w.store.plaintexts[dto.id]!!)!!.t)
    }

    @Test
    fun aNotesPhotoOfflineStaysLocal() = runTest(main.dispatcher) {
        val w = world()
        w.online = false
        w.pipeline().sendImage(MediaImageSource.FileBytes(photo), NOTES_PEER_ID, "", MediaComposeQuality.Original, MediaEdits.Identity, null)
        val note = w.state.messages(NOTES_PEER_ID)!!.single()
        assertFalse(note.pendingSync)
        assertEquals(ReceiptStatus.Sent, note.receipt)
        assertArrayEquals(photo, w.media.files[note.id])
    }

    // ---- photos ----

    @Test
    fun aPhotoIsUploadedSealedAndRekeyed() = runTest(main.dispatcher) {
        val w = world()
        val quoted = MessageReplyReference(UUID.randomUUID(), w.peer, MessageReplyReference.Kind.Text, "earlier")
        val error = w.pipeline().sendImage(MediaImageSource.FileBytes(photo), w.peer, "  Sunset ", MediaComposeQuality.HD, MediaEdits.Identity, quoted)
        assertNull(error)

        val request = w.server.lastRequest()
        val dto = w.server.dtoFor(request)
        assertEquals(ContentType.MEDIA, request.contentType)
        val payload = MediaMessagePayload.parse(w.openAsPeer(request))!!
        assertEquals(MediaMessagePayload.KIND_IMAGE, payload.t)
        assertEquals("image/jpeg", payload.mime)
        assertEquals(4032, payload.w)
        assertEquals(3024, payload.h)
        assertEquals("Sunset", payload.c)
        assertEquals(photo.size.toLong(), payload.s)
        assertEquals(quoted, payload.re)
        assertArrayEquals(w.images.preview, payload.previewJpeg)
        assertEquals(32, B64.decodeStrict(payload.k)!!.size)
        assertArrayEquals(photo, w.transfers.open(request.mediaObjectId!!, payload.k))

        val sent = w.state.messages(w.peer)!!.single()
        assertEquals(dto.id, sent.id)
        assertEquals("Sunset", sent.text)
        assertEquals(ChatMessageKind.Image, sent.kind)
        assertEquals(request.mediaObjectId, sent.mediaObjectId)
        assertEquals(Bytes.of(w.images.preview!!), sent.previewJpeg)
        assertEquals(photo.size.toLong(), sent.mediaByteCount)
        assertFalse(sent.pendingSync)
        assertArrayEquals(photo, w.media.files[dto.id])
        assertNull(w.media.files[request.clientMessageId])
        assertArrayEquals(w.openAsPeer(request), w.store.plaintexts[dto.id])
        assertEquals(1, w.host.refreshes)
    }

    @Test
    fun aPhotoWithoutCaptionReadsPhotoAndSealsNoCaption() = runTest(main.dispatcher) {
        val w = world()
        w.pipeline().sendImage(MediaImageSource.FileBytes(photo), w.peer, "   ", MediaComposeQuality.Original, MediaEdits.Identity, null)
        assertNull(MediaMessagePayload.parse(w.openAsPeer(w.server.lastRequest()))!!.c)
        assertEquals("Photo", w.state.messages(w.peer)!!.single().text)
    }

    @Test
    fun aPhotoThatCannotBePreparedSaysSo() = runTest(main.dispatcher) {
        val w = world()
        w.images.failure = IllegalStateException("decode")
        val error = w.pipeline().sendImage(MediaImageSource.FileBytes(photo), w.peer, "", MediaComposeQuality.Original, MediaEdits.Identity, null)
        assertEquals("Could not prepare that photo.", error)
        assertNull(w.state.messages(w.peer))
    }

    @Test
    fun signedOutPhotosAreRefused() = runTest(main.dispatcher) {
        val w = world()
        w.keyring.locked = true
        val error = w.pipeline().sendImage(MediaImageSource.FileBytes(photo), w.peer, "", MediaComposeQuality.Original, MediaEdits.Identity, null)
        assertEquals("Not signed in.", error)
    }

    @Test
    fun anOfflinePhotoWaitsForTheConnectionAndFlushesFromTheCache() = runTest(main.dispatcher) {
        val w = world()
        w.online = false
        val pipeline = w.pipeline()
        assertNull(pipeline.sendImage(MediaImageSource.FileBytes(photo), w.peer, "Later", MediaComposeQuality.Original, MediaEdits.Identity, null))

        val failed = w.state.messages(w.peer)!!.single()
        assertEquals(ReceiptStatus.Failed, failed.receipt)
        assertEquals("Waiting for connection…", failed.sendError)
        assertTrue(failed.pendingSync)
        assertTrue(w.host.wentOffline)

        w.online = true
        pipeline.flushOutbox()
        val request = w.server.lastRequest()
        assertEquals(failed.id, request.clientMessageId)
        assertEquals("Later", MediaMessagePayload.parse(w.openAsPeer(request))!!.c)
        val sent = w.state.messages(w.peer)!!.single()
        assertEquals(w.server.dtoFor(request).id, sent.id)
        assertEquals(ReceiptStatus.Sent, sent.receipt)
        assertNull(sent.sendError)
    }

    @Test
    fun aCancelledFlushCallerDoesNotLetASecondFlushUploadAgain() = runTest(main.dispatcher) { // review W2: flushJob
        val w = world()
        w.online = false
        val pipeline = w.pipeline()
        pipeline.sendImage(MediaImageSource.FileBytes(photo), w.peer, "Later", MediaComposeQuality.Original, MediaEdits.Identity, null)
        w.online = true
        val before = w.transfers.uploads.size
        val gate = CompletableDeferred<Unit>()
        w.transfers.uploadGate = gate

        // pollWithoutSocket's flush, whose loop leaveForeground stops while the upload runs.
        val poll = backgroundScope.launch { pipeline.flushOutbox() }
        assertEquals(before + 1, w.transfers.uploads.size)
        poll.cancel()
        // Back in front: the next flush joins the one still uploading instead of starting another.
        val again = backgroundScope.async { pipeline.flushOutbox() }
        gate.complete(Unit)
        again.await()

        assertEquals(before + 1, w.transfers.uploads.size)
        assertEquals(1, w.server.requests.size)
        val sent = w.state.messages(w.peer)!!.single()
        assertFalse(sent.pendingSync)
        assertEquals(w.server.dtoFor(w.server.lastRequest()).id, sent.id)
        // Once it ended, a later flush runs again (nothing left to send).
        pipeline.flushOutbox()
        assertEquals(1, w.server.requests.size)
    }

    @Test
    fun aReplayedPhotoCachesThePayloadOfTheRowTheServerKept() = runTest(main.dispatcher) { // review W2: idempotent replay
        val w = world()
        val pipeline = w.pipeline()
        // The server stored the first attempt, but its answer never came back.
        w.server.loseNextAnswer = true
        pipeline.sendImage(MediaImageSource.FileBytes(photo), w.peer, "Twice", MediaComposeQuality.Original, MediaEdits.Identity, null)
        val failed = w.state.messages(w.peer)!!.single()
        val firstBlob = w.server.requests.single().mediaObjectId!!

        // The retry uploads a fresh blob under a fresh key; the server replays the first row.
        assertNull(pipeline.retryFailedImage(failed.id, w.peer))
        assertEquals(2, w.transfers.uploads.size)
        assertNotEquals(firstBlob, w.server.lastRequest().mediaObjectId)
        val row = w.server.messages.single()
        assertEquals(firstBlob, row.mediaObjectId)

        val sent = w.state.messages(w.peer)!!.single()
        assertEquals(row.id, sent.id)
        assertEquals(firstBlob, sent.mediaObjectId)
        // The cached key opens the blob the server linked, so a later re-download works.
        val cached = MediaMessagePayload.parse(w.store.plaintexts.getValue(row.id))!!
        assertArrayEquals(photo, w.transfers.open(firstBlob, cached.k))
        assertEquals("Twice", cached.c)
    }

    @Test
    fun aFailedPhotoRetriesWithTheSameIdAndBytes() = runTest(main.dispatcher) {
        val w = world()
        val pipeline = w.pipeline()
        w.transfers.failUploads = 1
        val error = pipeline.sendImage(MediaImageSource.FileBytes(photo), w.peer, "", MediaComposeQuality.Original, MediaEdits.Identity, null)
        assertEquals("The network connection was lost.", error)
        val failed = w.state.messages(w.peer)!!.single()
        assertEquals(ReceiptStatus.Failed, failed.receipt)
        assertEquals("The network connection was lost.", failed.sendError)

        assertNull(pipeline.retryFailedImage(failed.id, w.peer))
        val request = w.server.lastRequest()
        assertEquals(failed.id, request.clientMessageId)
        assertArrayEquals(photo, w.transfers.open(request.mediaObjectId!!, MediaMessagePayload.parse(w.openAsPeer(request))!!.k))
        assertEquals(w.server.dtoFor(request).id, w.state.messages(w.peer)!!.single().id)
        // A fresh blob key per attempt (MC:2793).
        assertEquals(2, w.transfers.uploads.size)
    }

    @Test
    fun retryWithoutLocalBytesHasNothingToRetry() = runTest(main.dispatcher) {
        val w = world()
        assertEquals("Nothing to retry.", w.pipeline().retryFailedImage(UUID.randomUUID(), w.peer))
        assertEquals("Nothing to retry.", w.pipeline().retryFailedVideo(UUID.randomUUID(), w.peer))
    }

    @Test
    fun aPreviewOver6KiBIsNeverSealed() = runTest(main.dispatcher) {
        val w = world()
        w.images.preview = ByteArray(MediaCrypto.MAX_ENVELOPE_PREVIEW_BYTES + 1) { 1 }
        w.pipeline().sendImage(MediaImageSource.FileBytes(photo), w.peer, "", MediaComposeQuality.Original, MediaEdits.Identity, null)
        assertNull(MediaMessagePayload.parse(w.openAsPeer(w.server.lastRequest()))!!.th)
        assertNull(w.state.messages(w.peer)!!.single().previewJpeg)
    }

    @Test
    fun aPayloadOver12KiBDropsThePreview() = runTest(main.dispatcher) {
        val w = world()
        w.images.preview = ByteArray(6 * 1024) { 1 }
        val caption = "c".repeat(5 * 1024)
        assertNull(w.pipeline().sendImage(MediaImageSource.FileBytes(photo), w.peer, caption, MediaComposeQuality.Original, MediaEdits.Identity, null))
        val payload = MediaMessagePayload.parse(w.openAsPeer(w.server.lastRequest()))!!
        assertNull(payload.th)
        assertEquals(caption, payload.c)
    }

    @Test
    fun anEnvelopeOverTheBudgetFailsWithTheIosText() = runTest(main.dispatcher) {
        val w = world()
        val error = w.pipeline().sendImage(MediaImageSource.FileBytes(photo), w.peer, "x".repeat(40 * 1024), MediaComposeQuality.Original, MediaEdits.Identity, null)
        assertEquals("Media message is too large to send. Try a shorter video or smaller photo.", error)
        val failed = w.state.messages(w.peer)!!.single()
        assertEquals(ReceiptStatus.Failed, failed.receipt)
        assertEquals(error, failed.sendError)
        assertTrue(w.server.requests.isEmpty())
    }

    // ---- voice ----

    private val voice = ByteArray(12_000) { (it % 97).toByte() }
    private val waveform = ByteArray(40) { (it * 6).toByte() }

    @Test
    fun aVoiceNoteIsSentAsAudioMp4AndRekeyed() = runTest(main.dispatcher) {
        val w = world()
        val error = w.pipeline().sendVoice(voice, 3_400, w.peer, waveform, "  hello from the park  ", null, null)
        assertNull(error)

        val request = w.server.lastRequest()
        val dto = w.server.dtoFor(request)
        val payload = MediaMessagePayload.parse(w.openAsPeer(request))!!
        assertEquals(MediaMessagePayload.KIND_VOICE, payload.t)
        assertEquals("audio/mp4", payload.mime)
        assertEquals(0, payload.w)
        assertEquals(0, payload.h)
        assertEquals(3_400, payload.d)
        assertEquals("hello from the park", payload.c)
        assertEquals(B64.encode(waveform), payload.wf)
        // iOS voice payloads carry no `s` (messaging-core §11.7).
        assertNull(payload.s)
        assertArrayEquals(voice, w.transfers.open(request.mediaObjectId!!, payload.k))

        val sent = w.state.messages(w.peer)!!.single()
        assertEquals(dto.id, sent.id)
        assertEquals(ChatMessageKind.Voice, sent.kind)
        assertEquals("hello from the park", sent.text)
        assertEquals("hello from the park", sent.transcript)
        assertEquals(Bytes.of(waveform), sent.voiceWaveform)
        assertEquals(3_400, sent.durationMs)
        assertTrue(sent.hasFullMedia)
        assertArrayEquals(voice, w.media.files[dto.id])
        assertNull(w.media.files[request.clientMessageId])
    }

    @Test
    fun aVoiceNoteWithoutTranscriptReadsVoiceMessage() = runTest(main.dispatcher) {
        val w = world()
        w.pipeline().sendVoice(voice, 1_000, w.peer, ByteArray(0), null, null, null)
        val payload = MediaMessagePayload.parse(w.openAsPeer(w.server.lastRequest()))!!
        assertNull(payload.c)
        assertNull(payload.wf)
        assertEquals("Voice message", w.state.messages(w.peer)!!.single().text)
    }

    @Test
    fun aTranscriptTooLargeForThePayloadIsDroppedNotTheNote() = runTest(main.dispatcher) {
        val w = world()
        val long = "w".repeat(MessageAnnotation.MAX_TRANSCRIPT_BYTES)
        assertNull(w.pipeline().sendVoice(voice, 1_000, w.peer, waveform, long, null, null))
        assertNull(MediaMessagePayload.parse(w.openAsPeer(w.server.lastRequest()))!!.c)
        // Shown here all the same.
        assertEquals(long, w.state.messages(w.peer)!!.single().transcript)
    }

    @Test
    fun aVoiceEnvelopeOverTheBudgetFailsWithTheVoiceText() = runTest(main.dispatcher) {
        // Without its transcript the payload is still too large (an outsized waveform): the voice
        // wording of the iOS error (MC:3724-3730), the note kept as failed with its transcript.
        val w = world()
        val error = w.pipeline().sendVoice(voice, 1_000, w.peer, ByteArray(50_000) { 1 }, "kept here", null, null)
        assertEquals("Media message is too large to send. Try a shorter voice note.", error)
        val failed = w.state.messages(w.peer)!!.single()
        assertEquals(ReceiptStatus.Failed, failed.receipt)
        assertEquals(error, failed.sendError)
        assertEquals("kept here", failed.transcript)
        assertTrue(failed.pendingSync)
        assertTrue(w.server.requests.isEmpty())
    }

    @Test
    fun voiceInNotesStaysLocal() = runTest(main.dispatcher) {
        val w = world()
        assertNull(w.pipeline().sendVoice(voice, 1_000, NOTES_PEER_ID, waveform, "memo", null, null))
        val note = w.state.messages(NOTES_PEER_ID)!!.single()
        assertEquals(ReceiptStatus.Sent, note.receipt)
        assertFalse(note.pendingSync)
        assertEquals("memo", note.text)
        assertTrue(w.server.requests.isEmpty())
        assertTrue(w.transfers.uploads.isEmpty())
    }

    @Test
    fun anOfflineVoiceNoteWaitsAndFlushesFromTheCache() = runTest(main.dispatcher) {
        val w = world()
        w.online = false
        val pipeline = w.pipeline()
        pipeline.sendVoice(voice, 2_000, w.peer, waveform, "later", null, null)
        val failed = w.state.messages(w.peer)!!.single()
        assertEquals(ReceiptStatus.Failed, failed.receipt)
        assertEquals("Waiting for connection…", failed.sendError)
        assertEquals("later", failed.text)

        w.online = true
        pipeline.flushOutbox()
        val request = w.server.lastRequest()
        assertEquals(failed.id, request.clientMessageId)
        assertTrue(w.transfers.uploads.last() is PlainSource.LocalMedia)
        val payload = MediaMessagePayload.parse(w.openAsPeer(request))!!
        assertEquals("later", payload.c)
        assertEquals(2_000, payload.d)
        assertEquals(w.server.dtoFor(request).id, w.state.messages(w.peer)!!.single().id)
    }

    @Test
    fun aTranscriptMadeBesideTheSendFollowsAsAnAnnotation() = runTest(main.dispatcher) {
        val w = world()
        val transcript = CompletableDeferred<String?>()
        var askedFor: UUID? = null
        val error = w.pipeline().sendVoice(voice, 2_000, w.peer, waveform, null, null) { id ->
            askedFor = id
            transcript.await()
        }
        assertNull(error)
        // The note went out at once, without a transcript.
        val noteRequest = w.server.requests.single()
        assertNull(MediaMessagePayload.parse(w.openAsPeer(noteRequest))!!.c)
        assertEquals(noteRequest.clientMessageId, askedFor)
        val sentId = w.server.dtoFor(noteRequest).id

        transcript.complete("  see you at eight  ")
        val annotation = w.server.lastRequest()
        assertEquals(ContentType.ANNOTATION, annotation.contentType)
        assertNotEquals(noteRequest.clientMessageId, annotation.clientMessageId)
        val shared = MessageAnnotation.parseTranscript(w.openAsPeer(annotation))!!
        // The annotation points at the server's id of the note, not the client id.
        assertEquals(sentId, shared.messageId)
        assertEquals("see you at eight", shared.text)
        assertEquals("see you at eight", w.state.messages(w.peer)!!.single().transcript)
        // Cached (our own v3 cannot be reopened) and indexed under the note (D5).
        val annotationId = w.server.dtoFor(annotation).id
        assertNotNull(w.store.plaintexts[annotationId])
        assertEquals(setOf(annotationId), w.store.annotationsFor(sentId))
    }

    @Test
    fun aTranscriptThatLandsWhileSendingWaitsForTheRekey() = runTest(main.dispatcher) {
        val w = world()
        w.online = false
        val pipeline = w.pipeline()
        pipeline.sendVoice(voice, 2_000, w.peer, waveform, null, null) { "made offline" }
        // Queued: the transcript shows, nothing is shared yet.
        val queued = w.state.messages(w.peer)!!.single()
        assertEquals("made offline", queued.transcript)
        assertTrue(w.server.requests.isEmpty())

        w.online = true
        pipeline.flushOutbox()
        // The flush sealed the transcript in the payload: no annotation follows.
        assertEquals(1, w.server.requests.size)
        assertEquals("made offline", MediaMessagePayload.parse(w.openAsPeer(w.server.lastRequest()))!!.c)
    }

    @Test
    fun sharingATranscriptOfAReceivedNoteSendsAnAnnotation() = runTest(main.dispatcher) {
        val w = world()
        val received = ChatMessage(
            id = UUID.randomUUID(), peerUserId = w.peer, senderUserId = w.peer, text = "Voice message",
            createdAt = w.clock.now(), isMine = false, kind = ChatMessageKind.Voice,
        )
        w.state.put(w.peer, listOf(received))
        w.pipeline().shareTranscript("  hi there ", received.id, w.peer)

        assertEquals("hi there", w.state.messages(w.peer)!!.single().transcript)
        val shared = MessageAnnotation.parseTranscript(w.openAsPeer(w.server.lastRequest()))!!
        assertEquals(received.id, shared.messageId)
        // A second share of the same note does nothing: it has a transcript now.
        w.pipeline().shareTranscript("again", received.id, w.peer)
        assertEquals(1, w.server.requests.size)
    }

    @Test
    fun sharedTranscriptsAreFoldedIntoASentNote() = runTest(main.dispatcher) {
        // An annotation that arrived before its note (MC:3776).
        val w = world()
        w.host.sharedForAny = "from the other side"
        w.pipeline().sendVoice(voice, 2_000, w.peer, waveform, null, null, null)
        val sent = w.state.messages(w.peer)!!.single()
        assertEquals(w.server.dtoFor(w.server.lastRequest()).id, sent.id)
        assertEquals("from the other side", sent.transcript)
    }

    // ---- flush order ----

    @Test
    fun theFlushSendsInChronologicalOrderAndSkipsDeletedBubbles() = runTest(main.dispatcher) {
        val w = world()
        w.online = false
        val pipeline = w.pipeline()
        pipeline.sendText("first", w.peer, null, null)
        w.clock.advanceBy(1_000)
        pipeline.sendImage(MediaImageSource.FileBytes(photo), w.peer, "second", MediaComposeQuality.Original, MediaEdits.Identity, null)
        w.clock.advanceBy(1_000)
        pipeline.sendText("third", w.peer, null, null)
        w.clock.advanceBy(1_000)
        pipeline.sendText("deleted", w.peer, null, null)
        val deleted = w.state.messages(w.peer)!!.last()
        w.state.edit(w.peer) { list -> list.filterNot { it.id == deleted.id } }

        w.online = true
        pipeline.flushOutbox()
        assertEquals(listOf(ContentType.TEXT, ContentType.MEDIA, ContentType.TEXT), w.server.requests.map { it.contentType })
        assertEquals("first", String(w.openAsPeer(w.server.requests[0])))
        assertEquals("third", String(w.openAsPeer(w.server.requests[2])))
        assertEquals(listOf("first", "second", "third"), w.state.messages(w.peer)!!.map { it.text })
        assertTrue(w.state.messages(w.peer)!!.none { it.pendingSync })
    }

    private fun MessageCryptoFromWire(ciphertext: String): ByteArray = B64.decodeStrict(ciphertext)!!
}
