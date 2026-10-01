package de.corespace.shroud.core.transcription

import de.corespace.shroud.core.net.ApiClient
import de.corespace.shroud.testing.TempDirRule
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.SocketEffect
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import kotlin.random.Random

/**
 * The model store (media-voice-links §9.9: resumable download with `Range`, SHA-256 check, rename,
 * `noBackupFilesDir/whisper`) against a MockWebServer standing in for Hugging Face. Tiny fake
 * models stand in for the 60 MB and 190 MB weights; [pinnedCatalogMatchesTheSpec] checks the real
 * catalog against the spec's values.
 */
class WhisperModelStoreTest {
    @get:Rule
    val temp = TempDirRule()

    private val server = MockWebServer()
    private val bytes = Random(42).nextBytes(200_000)
    private val model = FakeModel("ggml-test.bin", bytes)
    private lateinit var directory: File

    @Before
    fun setUp() {
        server.start()
        directory = File(temp.noBackupFilesDir, WhisperModelStore.DIRECTORY)
    }

    @After
    fun tearDown() = server.close()

    private fun store(space: Long = Long.MAX_VALUE, catalog: List<WhisperModelSpec> = listOf(model)) = WhisperModelStore(
        directory = directory,
        http = ApiClient.defaultHttpClient(),
        baseUrl = server.url("/ggerganov/whisper.cpp/resolve/rev/"),
        usableSpace = { space },
        catalog = catalog,
    )

    @Test
    fun downloadsVerifiesAndInstallsTheModel() = runBlocking {
        server.enqueue(MockResponse.Builder().code(200).body(Buffer().write(bytes)).build())
        val fractions = mutableListOf<Double>()

        val file = store().ensure(model) { fractions += it }

        assertEquals(File(directory, "ggml-test.bin"), file)
        assertArrayEquals(bytes, file.readBytes())
        assertTrue(store().isInstalled(model))
        assertFalse("no partial file is left", File(directory, "ggml-test.bin.part").exists())
        assertEquals(1.0, fractions.last(), 0.0)
        assertEquals("progress only grows", fractions, fractions.sorted())
        val request = server.takeRequest()
        assertEquals("/ggerganov/whisper.cpp/resolve/rev/ggml-test.bin", request.url.encodedPath)
        assertNull("no Shroud token goes to Hugging Face", request.headers["Authorization"])
        assertNull(request.headers["Cookie"])
        assertNull("a fresh download asks for the whole file", request.headers["Range"])
    }

    @Test
    fun anInstalledModelIsNotDownloadedAgain() = runBlocking {
        directory.mkdirs()
        File(directory, model.fileName).writeBytes(bytes)

        store().ensure(model)

        assertEquals(0, server.requestCount)
    }

    @Test
    fun aPartialDownloadResumesWithARange() = runBlocking {
        directory.mkdirs()
        File(directory, "ggml-test.bin.part").writeBytes(bytes.copyOf(80_000))
        server.enqueue(
            MockResponse.Builder().code(206)
                .addHeader("Content-Range", "bytes 80000-199999/200000")
                .body(Buffer().write(bytes, 80_000, 120_000))
                .build(),
        )
        val fractions = mutableListOf<Double>()

        val file = store().ensure(model) { fractions += it }

        assertEquals("bytes=80000-", server.takeRequest().headers["Range"])
        assertArrayEquals(bytes, file.readBytes())
        assertTrue("progress counts the bytes already on disk", fractions.first() > 0.4)
    }

    @Test
    fun aServerThatIgnoresTheRangeStartsOver() = runBlocking {
        directory.mkdirs()
        File(directory, "ggml-test.bin.part").writeBytes(Random(7).nextBytes(50_000))
        server.enqueue(MockResponse.Builder().code(200).body(Buffer().write(bytes)).build())

        val file = store().ensure(model)

        assertEquals("bytes=50000-", server.takeRequest().headers["Range"])
        assertArrayEquals(bytes, file.readBytes())
    }

    @Test
    fun aRangeThatStartsElsewhereIsRefusedAndTheNextAttemptStartsOver() = runBlocking {
        directory.mkdirs()
        File(directory, "ggml-test.bin.part").writeBytes(bytes.copyOf(80_000))
        server.enqueue(
            MockResponse.Builder().code(206)
                .addHeader("Content-Range", "bytes 0-199999/200000")
                .body(Buffer().write(bytes))
                .build(),
        )
        server.enqueue(MockResponse.Builder().code(200).body(Buffer().write(bytes)).build())

        expectFailure { store().ensure(model) }
        val file = store().ensure(model)

        assertEquals("bytes=80000-", server.takeRequest().headers["Range"])
        assertNull("the second attempt asks for the whole file", server.takeRequest().headers["Range"])
        assertArrayEquals(bytes, file.readBytes())
    }

    @Test
    fun aChecksumMismatchDeletesTheFileAndFails() = runBlocking {
        val wrong = bytes.copyOf().also { it[100] = (it[100] + 1).toByte() }
        server.enqueue(MockResponse.Builder().code(200).body(Buffer().write(wrong)).build())

        val error = expectFailure { store().ensure(model) }

        assertEquals("the downloaded model failed its checksum", error.message)
        assertFalse(store().isInstalled(model))
        assertEquals("nothing is left to resume from", emptyList<String>(), directory.list()!!.toList())
    }

    @Test
    fun aDroppedConnectionKeepsThePartForTheNextAttempt() = runBlocking {
        server.enqueue(
            MockResponse.Builder().code(200).body(Buffer().write(bytes))
                .onResponseBody(SocketEffect.CloseSocket())
                .build(),
        )

        val error = expectFailure { store().ensure(model) }

        assertEquals("the model download was interrupted", error.message)
        val kept = File(directory, "ggml-test.bin.part").length()
        assertTrue("half the body arrived and stays: $kept", kept in 1 until bytes.size)
        server.enqueue(
            MockResponse.Builder().code(206)
                .addHeader("Content-Range", "bytes $kept-199999/200000")
                .body(Buffer().write(bytes, kept.toInt(), bytes.size - kept.toInt()))
                .build(),
        )

        val file = store().ensure(model)

        server.takeRequest()
        assertEquals("bytes=$kept-", server.takeRequest().headers["Range"])
        assertArrayEquals(bytes, file.readBytes())
    }

    @Test
    fun aLargerBodyThanPinnedIsRefused() = runBlocking {
        server.enqueue(MockResponse.Builder().code(200).body(Buffer().write(bytes).write(ByteArray(10))).build())

        val error = expectFailure { store().ensure(model) }

        assertEquals("the model download is larger than expected", error.message)
        assertFalse(File(directory, "ggml-test.bin.part").exists())
    }

    @Test
    fun anHttpErrorFails() = runBlocking {
        server.enqueue(MockResponse(code = 404, body = "Entry not found"))

        val error = expectFailure { store().ensure(model) }

        assertEquals("the model download failed (HTTP 404)", error.message)
        assertFalse(store().isInstalled(model))
    }

    @Test
    fun theCdnRedirectIsFollowed() = runBlocking {
        server.enqueue(MockResponse.Builder().code(302).addHeader("Location", server.url("/cdn/blob?sig=1")).build())
        server.enqueue(MockResponse.Builder().code(200).body(Buffer().write(bytes)).build())

        val file = store().ensure(model)

        assertEquals("/ggerganov/whisper.cpp/resolve/rev/ggml-test.bin", server.takeRequest().url.encodedPath)
        assertEquals("/cdn/blob", server.takeRequest().url.encodedPath)
        assertArrayEquals(bytes, file.readBytes())
    }

    @Test
    fun tooLittleSpaceFailsBeforeAnyRequest() = runBlocking {
        val error = expectFailure { store(space = 1_000).ensure(model) }

        assertEquals("not enough free space for the transcription model", error.message)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun concurrentEnsuresShareOneDownload() = runBlocking {
        server.enqueue(
            MockResponse.Builder().code(200).body(Buffer().write(bytes))
                .throttleBody(50_000, 20, TimeUnit.MILLISECONDS)
                .build(),
        )
        val store = store()

        val files = (1..3).map { async(Dispatchers.IO) { store.ensure(model) } }.awaitAll()

        assertEquals(1, server.requestCount)
        files.forEach { assertArrayEquals(bytes, it.readBytes()) }
    }

    @Test
    fun cancellingStopsTheDownloadAndKeepsThePart() = runBlocking {
        server.enqueue(
            MockResponse.Builder().code(200).body(Buffer().write(bytes))
                .throttleBody(10_000, 100, TimeUnit.MILLISECONDS)
                .build(),
        )
        val store = store()
        val job = launch(Dispatchers.IO) { store.ensure(model) }
        withTimeout(5_000) {
            while (File(directory, "ggml-test.bin.part").length() < 10_000) delay(10)
        }

        val started = System.nanoTime()
        job.cancel()
        job.join()

        assertTrue("cancel stops the transfer at once", System.nanoTime() - started < TimeUnit.SECONDS.toNanos(1))
        val kept = File(directory, "ggml-test.bin.part").length()
        assertTrue("the part stays for a resume: $kept", kept in 1 until bytes.size)
        assertFalse(store.isInstalled(model))
    }

    @Test
    fun deleteRemovesTheModelAndItsPart() = runBlocking {
        directory.mkdirs()
        File(directory, model.fileName).writeBytes(bytes)
        File(directory, model.fileName + ".part").writeBytes(bytes)

        store().delete(model)

        assertEquals(emptyList<String>(), directory.list()!!.toList())
    }

    @Test
    fun aDownloadPrunesFilesOutsideTheCatalog() = runBlocking {
        directory.mkdirs()
        File(directory, "ggml-base-q5_0.bin").writeBytes(ByteArray(10))
        File(directory, "stray.part").writeBytes(ByteArray(10))
        server.enqueue(MockResponse.Builder().code(200).body(Buffer().write(bytes)).build())

        store().ensure(model)

        assertEquals(listOf("ggml-test.bin"), directory.list()!!.sorted())
    }

    @Test
    fun aModelOutsideTheCatalogIsRejected() = runBlocking {
        try {
            store().ensure(FakeModel("other.bin", bytes))
            fail("expected a refusal")
        } catch (expected: IllegalArgumentException) {
            assertEquals("other.bin is not in this store's catalog", expected.message)
        }
    }

    /** Values of media-voice-links §9.9, confirmed against Hugging Face on 2026-10-01. */
    @Test
    fun pinnedCatalogMatchesTheSpec() {
        assertEquals(listOf("base", "small"), WhisperModelFile.entries.map { it.id })
        assertEquals(WhisperModelFile.BaseQ5_1, WhisperModelFile.DEFAULT)
        with(WhisperModelFile.BaseQ5_1) {
            assertEquals("ggml-base-q5_1.bin", fileName)
            assertEquals(59_707_625L, sizeBytes)
            assertEquals("422f1ae452ade6f30a004d7e5c6a43195e4433bc370bf23fac9cc591f01a8898", sha256)
        }
        with(WhisperModelFile.SmallQ5_1) {
            assertEquals("ggml-small-q5_1.bin", fileName)
            assertEquals(190_085_487L, sizeBytes)
            assertEquals("ae85e4a935d7a567bd102fe55afc16bb595bdb618e11b2fc7591bc08120411bb", sha256)
        }
        assertEquals(WhisperModelFile.SmallQ5_1, WhisperModelFile.forId("small"))
        assertNull(WhisperModelFile.forId("medium"))
        assertEquals(
            "https://huggingface.co/ggerganov/whisper.cpp/resolve/5359861c739e955e79d9a303bcbc70fb988958b1/",
            WhisperModelStore.PINNED_BASE_URL,
        )
        assertEquals("whisper", WhisperModelStore.DIRECTORY)
    }

    @Test
    fun contentRangeStartIsParsed() {
        assertEquals(80_000L, WhisperModelStore.rangeStart("bytes 80000-199999/200000"))
        assertEquals(0L, WhisperModelStore.rangeStart("bytes 0-9/*"))
        assertNull(WhisperModelStore.rangeStart("bytes */200000"))
        assertNull(WhisperModelStore.rangeStart("items 0-9/10"))
    }

    private suspend fun expectFailure(block: suspend () -> Unit): WhisperModelException {
        try {
            block()
        } catch (expected: WhisperModelException) {
            return expected
        } catch (cancelled: CancellationException) {
            throw cancelled
        }
        fail("expected a WhisperModelException")
        throw AssertionError()
    }

    private class FakeModel(override val fileName: String, content: ByteArray) : WhisperModelSpec {
        override val sizeBytes: Long = content.size.toLong()
        override val sha256: String = MessageDigest.getInstance("SHA-256").digest(content).joinToString("") { "%02x".format(it) }
    }
}
