package de.corespace.shroud.core.messaging

import de.corespace.shroud.core.media.PlainSource
import de.corespace.shroud.core.media.files.AudioCover
import de.corespace.shroud.core.media.files.AudioFileMetadata
import de.corespace.shroud.core.media.files.FileTypes
import de.corespace.shroud.core.media.files.PickedFile
import de.corespace.shroud.core.media.pdf.PdfEnvelopePreview
import de.corespace.shroud.core.model.ChatMessageKind
import de.corespace.shroud.core.model.MediaTransfer
import de.corespace.shroud.core.model.NOTES_PEER_ID
import de.corespace.shroud.core.model.ReceiptStatus
import de.corespace.shroud.core.model.replyReference
import de.corespace.shroud.core.net.ContentType
import de.corespace.shroud.core.net.wire.Icu4jTextUnitsRule
import de.corespace.shroud.core.net.wire.MediaMessagePayload
import de.corespace.shroud.core.net.wire.MessageReplyReference
import de.corespace.shroud.testing.MainDispatcherRule
import de.corespace.shroud.testing.TempDirRule
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.util.UUID

/**
 * Files (docs/file-sharing.md §1–§3, §7): the picked bytes stream into the sealed cache under the
 * optimistic id, go up as SHRF1 from that cache, and the `t: "file"` payload carries the cleaned
 * name, the canonical MIME and the size; one ring moves preparing → transferring → finishing; the
 * bubble is re-keyed. Robolectric only for the shared test kit.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SendPipelineFileTest {
    @get:Rule val main = MainDispatcherRule()
    @get:Rule val textUnits = Icu4jTextUnitsRule()
    @get:Rule val temp = TempDirRule()

    private fun TestScope.world() = SendWorld(backgroundScope, main.dispatcher, temp.cacheDir)

    private val bytes = ByteArray(150_000) { (it % 251).toByte() }

    private fun picked(name: String, data: ByteArray = bytes, size: Long = data.size.toLong(), open: () -> InputStream? = { ByteArrayInputStream(data) }) =
        PickedFile(name, size, requireNotNull(FileTypes.forName(name)), opener = open)

    @Test
    fun aFileGoesUpAsShrf1WithItsNameAndCanonicalMime() = runTest(main.dispatcher) {
        val w = world()
        assertNull(w.pipeline().sendFile(picked("Quarterly report 2026.pdf"), w.peer, " For you ", null))

        val request = w.server.lastRequest()
        val optimisticId = request.clientMessageId
        assertEquals(ContentType.MEDIA, request.contentType)
        assertEquals(PlainSource.LocalMedia(optimisticId), w.transfers.fileUploads.single())
        assertTrue("no single-GCM upload", w.transfers.uploads.isEmpty())

        val payload = MediaMessagePayload.parse(w.openAsPeer(request))!!
        assertEquals(MediaMessagePayload.KIND_FILE, payload.t)
        assertEquals("Quarterly report 2026.pdf", payload.n)
        assertEquals("application/pdf", payload.mime)
        assertEquals(bytes.size.toLong(), payload.s)
        assertEquals("For you", payload.c)
        assertEquals(0, payload.w)
        assertNull(payload.th)
        assertArrayEquals(bytes, w.transfers.openFile(request.mediaObjectId!!, payload.k, payload.s!!))

        val dto = w.server.dtoFor(request)
        val sent = w.state.messages(w.peer)!!.single()
        assertEquals(dto.id, sent.id)
        assertEquals(ChatMessageKind.File, sent.kind)
        assertEquals("For you", sent.text)
        assertEquals("Quarterly report 2026.pdf", sent.fileName)
        assertEquals(bytes.size.toLong(), sent.mediaByteCount)
        assertTrue(sent.hasFullMedia)
        assertArrayEquals(bytes, w.media.files[dto.id])
        assertNull(w.media.files[optimisticId])
        assertEquals(
            listOf(MediaTransfer.Phase.Preparing, MediaTransfer.Phase.Transferring, MediaTransfer.Phase.Finishing),
            w.state.transfers.phases.mapNotNull { it.second }.distinct(),
        )
        assertTrue(w.state.transfers.transfers.value.isEmpty())
        // Reloads decode from the cached payload (it holds the blob key).
        assertEquals(MediaMessagePayload.KIND_FILE, MediaMessagePayload.parse(w.store.plaintexts.getValue(dto.id))!!.t)
    }

    @Test
    fun aPdfCarriesTheTopOfItsFirstPageAndItsPageCount() = runTest(main.dispatcher) {
        val w = world()
        val thumb = ByteArray(4_000) { 7 }
        w.pdfPreview = { PdfEnvelopePreview(thumb, 480, 240, 12) }
        assertNull(w.pipeline().sendFile(picked("Quarterly report 2026.pdf"), w.peer, "", null))

        assertTrue(w.pdfPreviewSources.single() is PdfPreviewSource.Picked)
        val payload = MediaMessagePayload.parse(w.openAsPeer(w.server.lastRequest()))!!
        assertArrayEquals(thumb, payload.previewJpeg)
        assertEquals(480, payload.w)
        assertEquals(240, payload.h)
        assertEquals(12, payload.pg)
        val sent = w.state.messages(w.peer)!!.single()
        assertEquals(12, sent.pageCount)
        assertArrayEquals(thumb, sent.previewJpeg!!.toByteArray())
    }

    @Test
    fun aPdfThatDoesNotRenderStillGoesWithoutThOrPg() = runTest(main.dispatcher) {
        val w = world()
        w.pipeline().sendFile(picked("locked.pdf"), w.peer, "", null)
        val payload = MediaMessagePayload.parse(w.openAsPeer(w.server.lastRequest()))!!
        assertNull(payload.th)
        assertNull(payload.pg)
        assertEquals(0, payload.w)
        // Other types never ask for one.
        w.pipeline().sendFile(picked("notes.txt", "hi".toByteArray()), w.peer, "", null)
        assertEquals(1, w.pdfPreviewSources.size)
    }

    @Test
    fun aCaptionlessFileHasAnEmptyTextAndQuotesByName() = runTest(main.dispatcher) {
        val w = world()
        w.pipeline().sendFile(picked("deck.pptx"), w.peer, "", null)
        val sent = w.state.messages(w.peer)!!.single()
        assertEquals("", sent.text)
        assertNull(MediaMessagePayload.parse(w.openAsPeer(w.server.lastRequest()))!!.c)
        val quote = sent.replyReference!!
        assertEquals(MessageReplyReference.Kind.File, quote.kind)
        assertEquals("deck.pptx", quote.snippet)
    }

    @Test
    fun theReplyRidesInThePayload() = runTest(main.dispatcher) {
        val w = world()
        val reply = MessageReplyReference(UUID.randomUUID(), w.peer, MessageReplyReference.Kind.Text, "hi")
        w.pipeline().sendFile(picked("a.txt", "hello".toByteArray()), w.peer, "", reply)
        assertEquals(reply, MediaMessagePayload.parse(w.openAsPeer(w.server.lastRequest()))!!.re)
        assertEquals(reply, w.state.messages(w.peer)!!.single().replyTo)
    }

    @Test
    fun unsupportedEmptyAndTooLargeFilesAreRefusedWithoutABubble() = runTest(main.dispatcher) {
        val w = world()
        val pipeline = w.pipeline()
        // The provider said nothing about the size: the copy finds out.
        assertEquals("“empty.txt” is empty.", pipeline.sendFile(picked("empty.txt", ByteArray(0), size = PickedFile.UNKNOWN_SIZE), w.peer, "", null))
        assertEquals("“empty.txt” is empty.", pipeline.sendFile(picked("empty.txt", ByteArray(0), size = 0), w.peer, "", null))
        assertEquals(
            "“big.mkv” is larger than 2 GB.",
            pipeline.sendFile(picked("big.mkv", size = 3L * 1024 * 1024 * 1024), w.peer, "", null),
        )
        val unsupported = PickedFile("run.sh", 10, FileTypes.forExtension("txt")!!) { ByteArrayInputStream(ByteArray(10)) }
        assertEquals("Shroud can't send “run.sh”: this file type isn't supported.", pipeline.sendFile(unsupported, w.peer, "", null))
        assertTrue(w.state.messages(w.peer).orEmpty().isEmpty())
        assertTrue(w.media.files.isEmpty())
        assertTrue(w.server.requests.isEmpty())
        assertTrue(w.state.transfers.transfers.value.isEmpty())
    }

    @Test
    fun anUnreadableFileFailsAndKeepsItsBubble() = runTest(main.dispatcher) {
        val w = world()
        val broken = picked("a.pdf") { throw IOException("revoked") }
        assertEquals("Could not read that file.", w.pipeline().sendFile(broken, w.peer, "", null))
        val bubble = w.state.messages(w.peer)!!.single()
        assertEquals(ReceiptStatus.Failed, bubble.receipt)
        assertEquals("Could not read that file.", bubble.sendError)
        assertTrue(w.media.files.isEmpty())
        assertTrue(w.state.transfers.transfers.value.isEmpty())
    }

    @Test
    fun anOfflineFileWaitsAndTheFlushSendsItFromTheCache() = runTest(main.dispatcher) {
        val w = world()
        w.online = false
        val pipeline = w.pipeline()
        assertNull(pipeline.sendFile(picked("notes.csv"), w.peer, "later", null))
        val failed = w.state.messages(w.peer)!!.single()
        assertEquals("Waiting for connection…", failed.sendError)
        assertTrue(failed.hasFullMedia)
        assertArrayEquals(bytes, w.media.files[failed.id])

        w.online = true
        pipeline.flushOutbox()
        val request = w.server.lastRequest()
        assertEquals(failed.id, request.clientMessageId)
        val payload = MediaMessagePayload.parse(w.openAsPeer(request))!!
        assertEquals("later", payload.c)
        assertEquals("notes.csv", payload.n)
        assertEquals("text/csv", payload.mime)
        assertEquals(w.server.dtoFor(request).id, w.state.messages(w.peer)!!.single().id)
    }

    @Test
    fun aFailedFileRetriesFromTheCacheWithItsClientIdAndAFreshKey() = runTest(main.dispatcher) {
        val w = world()
        val pipeline = w.pipeline()
        w.transfers.failUploads = 1
        assertEquals("The network connection was lost.", pipeline.sendFile(picked("budget.xlsm"), w.peer, "", null))
        val failed = w.state.messages(w.peer)!!.single()
        assertEquals(ReceiptStatus.Failed, failed.receipt)
        assertEquals("budget.xlsm", failed.fileName)

        assertNull(pipeline.retryFailedFile(failed.id, w.peer))
        val request = w.server.lastRequest()
        assertEquals(failed.id, request.clientMessageId)
        assertEquals(2, w.transfers.fileUploads.size)
        assertEquals(w.server.dtoFor(request).id, w.state.messages(w.peer)!!.single().id)
        assertEquals("Nothing to retry.", pipeline.retryFailedFile(UUID.randomUUID(), w.peer))
    }

    @Test
    fun aNotesFileIsRekeyedIntoNotes() = runTest(main.dispatcher) {
        val w = world()
        assertNull(w.pipeline().sendFile(picked("todo.txt", "buy milk".toByteArray()), NOTES_PEER_ID, "", null))
        val request = w.server.lastRequest()
        assertEquals(w.me, request.peerUserId)
        val note = w.state.messages(NOTES_PEER_ID)!!.single()
        assertEquals(w.server.dtoFor(request).id, note.id)
        assertEquals(ChatMessageKind.File, note.kind)
        assertEquals(ReceiptStatus.Sent, note.receipt)
        assertEquals("todo.txt", note.fileName)
        assertTrue(w.state.transfers.transfers.value.isEmpty())
    }

    // ---- Audio files (docs/file-sharing.md §11.2) ----

    private val cover = ByteArray(3_000) { 9 }
    private val tags = AudioFileMetadata(durationMs = 243_400, title = "Midnight City", artist = "M83", cover = AudioCover(cover, 160, 160))

    @Test
    fun anAudioFileCarriesItsLengthTagsAndCover() = runTest(main.dispatcher) {
        val w = world()
        assertNull(w.pipeline().sendFile(picked("track01.mp3").withAudio(tags), w.peer, "", null))
        val payload = MediaMessagePayload.parse(w.openAsPeer(w.server.lastRequest()))!!
        assertEquals(MediaMessagePayload.KIND_FILE, payload.t)
        assertEquals("audio/mpeg", payload.mime)
        assertEquals(243_400, payload.d)
        assertEquals("Midnight City", payload.ti)
        assertEquals("M83", payload.ar)
        assertArrayEquals(cover, payload.previewJpeg)
        assertEquals(160, payload.w)
        assertEquals(160, payload.h)
        assertNull(payload.pg)
        val sent = w.state.messages(w.peer)!!.single()
        assertEquals(243_400, sent.durationMs)
        assertEquals("Midnight City", sent.audioTitle)
        assertEquals("M83", sent.audioArtist)
        assertArrayEquals(cover, sent.previewJpeg!!.toByteArray())
        val quote = sent.replyReference!!
        assertEquals(MessageReplyReference.Kind.Audio, quote.kind)
        assertEquals("Midnight City \u2013 M83", quote.snippet)
        assertTrue("a fresh pick brings its own tags", w.audioMetadataReads.isEmpty())
    }

    @Test
    fun anAudioFileWithoutTagsGoesWithoutThem() = runTest(main.dispatcher) {
        val w = world()
        w.pipeline().sendFile(picked("memo.wav"), w.peer, "", null)
        val payload = MediaMessagePayload.parse(w.openAsPeer(w.server.lastRequest()))!!
        assertNull(payload.d)
        assertNull(payload.ti)
        assertNull(payload.ar)
        assertNull(payload.th)
        assertEquals(0, payload.w)
        // Tags on a pick of another type are ignored.
        w.pipeline().sendFile(picked("notes.txt", "hi".toByteArray()).withAudio(tags), w.peer, "", null)
        val text = MediaMessagePayload.parse(w.openAsPeer(w.server.lastRequest()))!!
        assertNull(text.ti)
        assertNull(text.d)
    }

    @Test
    fun aRetriedAudioFileKeepsItsTagsAndReadsALostCoverFromTheSealedCopy() = runTest(main.dispatcher) {
        val w = world()
        val pipeline = w.pipeline()
        w.transfers.failUploads = 1
        pipeline.sendFile(picked("track01.mp3").withAudio(tags), w.peer, "", null)
        val failed = w.state.messages(w.peer)!!.single()
        assertEquals(ReceiptStatus.Failed, failed.receipt)
        // A restart keeps the row's tags and length but no picture.
        w.state.update(failed.id) { it.copy(previewJpeg = null, imageWidth = null, imageHeight = null) }
        val reread = ByteArray(2_000) { 4 }
        w.audioMetadata = { AudioFileMetadata(durationMs = 1, title = "Other", artist = null, cover = AudioCover(reread, 128, 128)) }

        assertNull(pipeline.retryFailedFile(failed.id, w.peer))
        assertEquals(listOf(failed.id), w.audioMetadataReads)
        val payload = MediaMessagePayload.parse(w.openAsPeer(w.server.lastRequest()))!!
        assertEquals("the bubble's tags win", "Midnight City", payload.ti)
        assertEquals("M83", payload.ar)
        assertEquals(243_400, payload.d)
        assertArrayEquals(reread, payload.previewJpeg)
        assertEquals(128, payload.w)
    }
}
