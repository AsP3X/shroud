package de.corespace.shroud.core.messaging

import de.corespace.shroud.core.media.PlainSource
import de.corespace.shroud.core.model.ChatMessage
import de.corespace.shroud.core.model.ChatMessageKind
import de.corespace.shroud.core.model.MediaTransfer
import de.corespace.shroud.core.net.ContentType
import de.corespace.shroud.core.net.wire.Icu4jTextUnitsRule
import de.corespace.shroud.core.net.wire.MediaMessagePayload
import de.corespace.shroud.testing.MainDispatcherRule
import de.corespace.shroud.testing.TempDirRule
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** A file's download on a tap (docs/file-sharing.md §3, §7): SHRF1 with the payload's `s`, cancellable, never for unsupported files. */
@OptIn(ExperimentalCoroutinesApi::class)
class MediaHydratorFileTest {
    @get:Rule val main = MainDispatcherRule()
    @get:Rule val textUnits = Icu4jTextUnitsRule()
    @get:Rule val temp = TempDirRule()

    private fun TestScope.world() = SendWorld(backgroundScope, main.dispatcher, temp.cacheDir)

    private val bytes = ByteArray(140_000) { (it * 7).toByte() }

    private suspend fun inbound(w: SendWorld, name: String, size: Long? = bytes.size.toLong()): ChatMessage {
        val blob = w.transfers.uploadFile(PlainSource.InMemory(bytes), "tok")
        val payload = MediaMessagePayload(t = MediaMessagePayload.KIND_FILE, mime = "application/pdf", w = 0, h = 0, k = blob.keyBase64, s = size, n = name).encoded()
        val dto = w.peerSends(payload, ContentType.MEDIA, blob.mediaObjectId)
        w.store.savePlaintext(dto.id, payload)
        val message = ChatMessage(
            id = dto.id, peerUserId = w.peer, senderUserId = w.peer, text = "", createdAt = dto.createdAt, isMine = false,
            kind = ChatMessageKind.File, mediaObjectId = blob.mediaObjectId, mediaByteCount = size, fileName = name,
        )
        w.state.put(w.peer, w.state.messages(w.peer).orEmpty() + message)
        return message
    }

    private fun SendWorld.held(id: java.util.UUID) = state.messages(peer)!!.single { it.id == id }

    @Test
    fun aTappedFileDownloadsAsShrf1IntoTheCache() = runTest(main.dispatcher) {
        val w = world()
        val message = inbound(w, "report.pdf")
        MediaHydrator(w.state, w.deps).ensureFileLoaded(message)
        assertTrue(w.held(message.id).hasFullMedia)
        assertArrayEquals(bytes, w.media.files[message.id])
        assertEquals(1, w.transfers.fileDownloads)
        assertEquals(0, w.transfers.downloads)
        assertEquals(listOf(MediaTransfer.Phase.Transferring, MediaTransfer.Phase.Finishing), w.state.transfers.phases.mapNotNull { it.second })
        assertTrue(w.state.transfers.transfers.value.isEmpty())
    }

    @Test
    fun anUnsupportedFileOrOneWithoutASizeNeverDownloads() = runTest(main.dispatcher) {
        val w = world()
        val hydrator = MediaHydrator(w.state, w.deps)
        val script = inbound(w, "run.sh")
        hydrator.ensureFileLoaded(script)
        val sizeless = inbound(w, "a.pdf", size = null)
        hydrator.ensureFileLoaded(sizeless)
        assertEquals(0, w.transfers.fileDownloads)
        assertFalse(w.held(script.id).hasFullMedia)
        assertFalse(w.held(sizeless.id).hasFullMedia)
        assertTrue(w.state.transfers.transfers.value.isEmpty())
    }

    @Test
    fun aWrongSizeStoresNothing() = runTest(main.dispatcher) {
        val w = world()
        // The payload claims one byte more than the blob holds: the length check refuses it.
        val message = inbound(w, "report.pdf", size = bytes.size + 1L)
        MediaHydrator(w.state, w.deps).ensureFileLoaded(message)
        assertFalse(w.held(message.id).hasFullMedia)
        assertTrue(w.media.files.isEmpty())
    }

    @Test
    fun theRingsXCancelsTheDownload() = runTest(main.dispatcher) {
        val w = world()
        val message = inbound(w, "report.pdf")
        val hydrator = MediaHydrator(w.state, w.deps)
        w.transfers.gate = CompletableDeferred()
        val download = async { hydrator.ensureFileLoaded(message) }
        runCurrent()
        assertTrue(message.id in w.state.transfers.transfers.value)
        hydrator.cancel(message.id)
        download.await()
        assertFalse(w.held(message.id).hasFullMedia)
        assertTrue(w.media.files.isEmpty())
        assertTrue(w.state.transfers.transfers.value.isEmpty())
    }
}
