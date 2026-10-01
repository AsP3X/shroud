package de.corespace.shroud.core.media

import de.corespace.shroud.core.crypto.B64
import de.corespace.shroud.core.crypto.CryptoError
import de.corespace.shroud.core.crypto.MediaCrypto
import de.corespace.shroud.core.crypto.ScriptedEntropy
import de.corespace.shroud.core.crypto.utf8
import de.corespace.shroud.core.keys.SealedLocalState
import de.corespace.shroud.core.net.ApiClient
import de.corespace.shroud.core.net.ApiError
import de.corespace.shroud.core.net.ErrorCodes
import de.corespace.shroud.core.net.ProgressThrottle
import de.corespace.shroud.core.net.ShroudApi
import de.corespace.shroud.core.storage.SensitiveTempFiles
import de.corespace.shroud.core.storage.StorageSeal
import de.corespace.shroud.testing.SealedTestKey
import de.corespace.shroud.testing.TempDirRule
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.io.FileNotFoundException
import java.io.RandomAccessFile
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.random.Random

/**
 * Sealed media transfers against a fake server (media-voice-links §1.1, §1.2, §2; api-realtime
 * §2.8, §4.14; crypto §13.1): iOS `MediaService` (`ios/shroud/Services/API/MediaService.swift:7-84`)
 * with `MediaCrypto.sealFile` / `openFile` around it (`MessagingController.swift:2834-2848`,
 * `3058-3069`).
 *
 * The cross-client vector is crypto §16.3's (key `0x01 × 32`, nonce `0x02 × 12`, "media bytes"),
 * copied from `MediaCryptoTest` (generated with the web client's own libraries by
 * `gen_message_crypto_vectors.mjs`): what Android uploads opens on iOS and the web, and what they
 * upload opens here.
 */
class MediaTransferServiceTest {
    @get:Rule val temp = TempDirRule()

    private lateinit var server: MockWebServer
    private lateinit var api: ShroudApi
    private val state = SealedLocalState()
    private val seal = StorageSeal()
    private lateinit var cache: LocalMediaCache
    private lateinit var tempFiles: SensitiveTempFiles
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; explicitNulls = true }

    private val media = UUID.fromString("3b241101-e2bb-4255-8caf-4136c566a962")
    private val message = UUID.fromString("0f8fad5b-d9cb-469f-a165-70867728950e")
    private val key = ByteArray(32) { 0x01 }
    private val nonce = ByteArray(12) { 0x02 }
    private val vector = "AgICAgICAgICAgICarOtICt3o4Snqc8K7wU25kC+cfCidpttzp1L"

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        api = ShroudApi(ApiClient(baseUrl = { server.url("/api/v1").toString() }, json = json))
        state.unlock(SealedTestKey.bytes())
        cache = LocalMediaCache(File(temp.noBackupFilesDir, "shroud/media"), state, seal)
        tempFiles = SensitiveTempFiles(temp.cacheDir)
    }

    @After
    fun tearDown() {
        cache.close()
        server.close()
    }

    private fun service(vararg entropy: ByteArray) =
        MediaTransferService(api, cache, tempFiles, if (entropy.isEmpty()) ScriptedEntropy(key, nonce) else ScriptedEntropy(*entropy))

    private fun created(id: UUID = media) = MockResponse(
        code = 201,
        body = """{"media_object_id":"$id","upload_url":"media/$id/content","object_key":"media/3b/$id","expires_at":"2026-10-01T13:00:00Z"}""",
    )

    // ---- Upload ----

    @Test
    fun anUploadIsTheCrossClientVector() = runTest {
        server.enqueue(created())
        server.enqueue(MockResponse(code = 204))
        val entropy = ScriptedEntropy(key, nonce)
        val blob = MediaTransferService(api, cache, tempFiles, entropy).upload(PlainSource.InMemory(utf8("media bytes")), "tok")

        assertEquals(media, blob.mediaObjectId)
        assertEquals(B64.encode(key), blob.keyBase64)
        assertEquals(11L, blob.plainSize)
        assertEquals(39L, blob.sealedSize)
        assertEquals(0, entropy.remaining)
        assertFalse("the key never shows in toString", blob.toString().contains(blob.keyBase64))

        val create = server.takeRequest()
        assertEquals("POST", create.method)
        assertEquals("/api/v1/media/uploads", create.url.encodedPath)
        assertEquals("""{"size_bytes":39,"content_type":"application/octet-stream"}""", create.body!!.utf8())
        assertEquals("Bearer tok", create.headers["Authorization"])
        val put = server.takeRequest()
        assertEquals("PUT", put.method)
        assertEquals("/api/v1/media/$media/content", put.url.encodedPath)
        assertEquals("application/octet-stream", put.headers["Content-Type"])
        assertEquals("39", put.headers["Content-Length"])
        assertEquals("Bearer tok", put.headers["Authorization"])
        assertEquals(vector, B64.encode(put.body!!.toByteArray()))
    }

    @Test
    fun oneShotAndStreamingUploadsAreByteIdentical() = runTest {
        // Either side of the 16 MiB switch (media D2): the JCA one-shot and BouncyCastle streaming.
        for (size in listOf(MediaTransferService.ONE_SHOT_BYTES, MediaTransferService.ONE_SHOT_BYTES + 1)) {
            val plaintext = Random(size.toInt()).nextBytes(size.toInt())
            val file = tempFiles.create("export", "mp4").apply { writeBytes(plaintext) }
            server.enqueue(created())
            server.enqueue(MockResponse(code = 204))
            val progress = CopyOnWriteArrayList<Double>()
            val blob = withContext(Dispatchers.Default) { service().upload(PlainSource.TempFile(file), "tok") { progress += it } }
            assertEquals(size + 28, blob.sealedSize)
            server.takeRequest()
            val body = server.takeRequest().body!!.toByteArray()
            val expected = MediaCrypto.sealFile(plaintext, ScriptedEntropy(key, nonce)).sealed
            assertArrayEquals("size $size", expected, body)
            assertMonotonicEndingAtOne(progress)
            file.delete()
        }
    }

    @Test
    fun aBodyWrittenTwiceIsByteIdentical() {
        // OkHttp may write a repeatable body again on a fresh connection; the same key and nonce
        // must give the same bytes (never a second nonce for other bytes).
        for (size in listOf(1_000, MediaTransferService.ONE_SHOT_BYTES.toInt() + 5)) {
            val plaintext = Random(size).nextBytes(size)
            val file = tempFiles.create("export", "bin").apply { writeBytes(plaintext) }
            val body = MediaTransferService.SealingBody(MediaTransferService.Plain.TempFile(file), key, nonce, size + 28L)
            assertFalse(body.isOneShot())
            assertEquals(size + 28L, body.contentLength())
            assertEquals("application/octet-stream", body.contentType().toString())
            val first = Buffer().also { body.writeTo(it) }.readByteArray()
            val second = Buffer().also { body.writeTo(it) }.readByteArray()
            assertArrayEquals(first, second)
            assertArrayEquals(plaintext, MediaCrypto.openFile(first, key))
        }
    }

    @Test
    fun aSourceThatGrewIsRefusedBeforeItsExtraBytesAreSealed() {
        val size = MediaTransferService.ONE_SHOT_BYTES.toInt() + 10
        val file = tempFiles.create("export", "bin").apply { writeBytes(Random(1).nextBytes(size)) }
        val body = MediaTransferService.SealingBody(MediaTransferService.Plain.TempFile(file), key, nonce, size + 28L)
        file.appendBytes(ByteArray(3))
        val partial = Buffer()
        try {
            body.writeTo(partial)
            fail("a grown source must not be sent under the old length")
        } catch (_: java.io.IOException) {
        }
        // Nonce + the 16 whole MiB that were unchanged at most: not the last chunk, not the tag.
        assertTrue("stopped before the last chunk and the tag", partial.size <= 12L + 16 * 1024 * 1024)
    }

    @Test
    fun aReplayNeverSealsDifferentPlaintextUnderTheSameNonce() {
        // GCM nonce reuse: a body written again (OkHttp retry) after its source changed must stop
        // before the changed chunk, and a shorter source must not get a tag. What went out the
        // second time is then a prefix of what went out the first time.
        val size = MediaTransferService.ONE_SHOT_BYTES.toInt() + 3 * 1024 * 1024 + 17
        val plaintext = Random(2).nextBytes(size)
        val file = tempFiles.create("export", "bin").apply { writeBytes(plaintext) }
        val body = MediaTransferService.SealingBody(MediaTransferService.Plain.TempFile(file), key, nonce, size + 28L)
        val first = Buffer().also { body.writeTo(it) }.readByteArray()

        val changedAt = 5 * 1024 * 1024 + 123
        file.writeBytes(plaintext.copyOf().also { it[changedAt] = (it[changedAt].toInt() xor 1).toByte() })
        val changed = Buffer()
        assertThrows(java.io.IOException::class.java) { body.writeTo(changed) }
        val changedBytes = changed.readByteArray()
        assertTrue("nothing of the changed MiB went out", changedBytes.size <= 12 + 5 * 1024 * 1024)
        assertArrayEquals(first.copyOf(changedBytes.size), changedBytes)

        file.writeBytes(plaintext.copyOf(size - 10))
        val shorter = Buffer()
        assertThrows(java.io.IOException::class.java) { body.writeTo(shorter) }
        val shorterBytes = shorter.readByteArray()
        assertTrue(shorterBytes.size < first.size - 16)
        assertArrayEquals(first.copyOf(shorterBytes.size), shorterBytes)

        // Restored: the replay is byte-identical again.
        file.writeBytes(plaintext)
        assertArrayEquals(first, Buffer().also { body.writeTo(it) }.readByteArray())
    }

    @Test
    fun aDestroyedBodyNoLongerSeals() {
        for (size in listOf(100, MediaTransferService.ONE_SHOT_BYTES.toInt() + 1)) {
            val file = tempFiles.create("export", "bin").apply { writeBytes(ByteArray(size)) }
            val body = MediaTransferService.SealingBody(MediaTransferService.Plain.TempFile(file), key, nonce, size + 28L)
            body.writeTo(Buffer())
            body.destroy()
            val after = Buffer()
            assertThrows(java.io.IOException::class.java) { body.writeTo(after) }
            assertEquals(0L, after.size)
        }
    }

    @Test
    fun anUploadFromTheLocalCacheReadsTheSealedFile() = runTest {
        val plaintext = Random(5).nextBytes(300_000)
        cache.save(message, plaintext)
        server.enqueue(created())
        server.enqueue(MockResponse(code = 204))
        val blob = service().upload(PlainSource.LocalMedia(message), "tok")
        assertEquals(300_000L, blob.plainSize)
        server.takeRequest()
        assertArrayEquals(plaintext, MediaCrypto.openFile(server.takeRequest().body!!.toByteArray(), key))
    }

    @Test
    fun aMissingSourceFailsBeforeAnyRequest() = runTest {
        for (source in listOf(PlainSource.LocalMedia(message), PlainSource.TempFile(File(temp.cacheDir, "shroud-gone.mp4")))) {
            try {
                service().upload(source, "tok")
                fail("$source")
            } catch (_: FileNotFoundException) {
            }
        }
        assertEquals(0, server.requestCount)
    }

    @Test
    fun theTwoGibibyteGuardRefusesBeforeAnyRequestWithTheIosText() = runTest {
        // A sparse file: sealed = plaintext + 28 = 2 GiB + 1.
        val file = sparse(MediaCrypto.MAX_SEALED_BYTES - 27)
        val entropy = ScriptedEntropy(key, nonce)
        val error = try {
            MediaTransferService(api, cache, tempFiles, entropy).upload(PlainSource.TempFile(file), "tok")
            null
        } catch (e: ApiError.Server) {
            e
        }!!
        assertEquals(ErrorCodes.VALIDATION_ERROR, error.code)
        assertEquals(400, error.status)
        assertEquals("This media is too large after encryption (2048 MB). Try a shorter video or lower photo quality.", error.userMessage)
        assertEquals(0, server.requestCount)
        assertEquals("no key is drawn for a refused upload", 2, entropy.remaining)
        file.delete()
    }

    @Test
    fun exactlyTwoGibibytesSealedIsAllowed() = runTest {
        val file = sparse(MediaCrypto.MAX_SEALED_BYTES - 28)
        server.enqueue(MockResponse(code = 400, body = """{"error":{"code":"VALIDATION_ERROR","message":"stop here"}}"""))
        val error = try {
            service().upload(PlainSource.TempFile(file), "tok")
            null
        } catch (e: ApiError.Server) {
            e
        }!!
        assertEquals("stop here", error.userMessage)
        assertEquals("""{"size_bytes":2147483648,"content_type":"application/octet-stream"}""", server.takeRequest().body!!.utf8())
        file.delete()
    }

    @Test
    fun aServerRefusalOfTheUploadIsTheServersMessage() = runTest {
        server.enqueue(created())
        server.enqueue(MockResponse(code = 409, body = """{"error":{"code":"ALREADY_EXISTS","message":"Media is already linked to a message and cannot be overwritten."}}"""))
        val error = try {
            service().upload(PlainSource.InMemory(ByteArray(10)), "tok")
            null
        } catch (e: ApiError.Server) {
            e
        }!!
        assertEquals("Media is already linked to a message and cannot be overwritten.", error.userMessage)
    }

    // ---- Download ----

    @Test
    fun aDownloadOfTheCrossClientVectorLandsInTheCache() = runTest {
        server.enqueue(MockResponse.Builder().code(200).body(Buffer().write(B64.decodeStrict(vector)!!)).build())
        val progress = CopyOnWriteArrayList<Double>()
        service().downloadInto(media, B64.encode(key), "tok", message) { progress += it }
        assertEquals("media bytes", String(cache.readAll(message)!!, Charsets.UTF_8))
        val request = server.takeRequest()
        assertEquals("GET", request.method)
        assertEquals("/api/v1/media/$media/content", request.url.encodedPath)
        assertEquals("Bearer tok", request.headers["Authorization"])
        assertMonotonicEndingAtOne(progress)
        assertNoTempFiles()
    }

    @Test
    fun largeDownloadsStreamAndReplaceWhatWasThere() = runTest {
        cache.save(message, ByteArray(10) { 9 })
        val plaintext = Random(6).nextBytes(MediaTransferService.ONE_SHOT_BYTES.toInt() + 100)
        val sealed = MediaCrypto.sealFile(plaintext, ScriptedEntropy(key, nonce)).sealed
        server.enqueue(MockResponse.Builder().code(200).body(Buffer().write(sealed)).throttleBody(4L * 1024 * 1024, 20, TimeUnit.MILLISECONDS).build())
        val progress = CopyOnWriteArrayList<Double>()
        withContext(Dispatchers.Default) { service().downloadInto(media, B64.encode(key), "tok", message) { progress += it } }
        assertArrayEquals(plaintext, cache.readAll(message))
        assertMonotonicEndingAtOne(progress)
        assertNoTempFiles()
    }

    @Test
    fun aBlobThatDoesNotOpenStoresNothing() = runTest {
        // Wrong key (small path), and a tampered large blob (streaming path: the tag fails at the end).
        server.enqueue(MockResponse.Builder().code(200).body(Buffer().write(B64.decodeStrict(vector)!!)).build())
        try {
            service().downloadInto(media, B64.encode(ByteArray(32) { 9 }), "tok", message)
            fail("wrong key")
        } catch (_: MediaCrypto.MediaError.DecryptFailed) {
        }
        val big = MediaCrypto.sealFile(ByteArray(MediaTransferService.ONE_SHOT_BYTES.toInt() + 1), ScriptedEntropy(key, nonce)).sealed
        big[big.size - 1] = (big[big.size - 1].toInt() xor 1).toByte()
        server.enqueue(MockResponse.Builder().code(200).body(Buffer().write(big)).build())
        try {
            withContext(Dispatchers.Default) { service().downloadInto(media, B64.encode(key), "tok", message) }
            fail("tampered")
        } catch (_: MediaCrypto.MediaError.DecryptFailed) {
        }
        assertFalse(cache.has(message))
        assertTrue("no pending file", cache.directory.listFiles().orEmpty().isEmpty())
        assertNoTempFiles()
    }

    @Test
    fun aKeyThatIsNotStrictBase64Of32BytesFailsBeforeAnyRequest() = runTest {
        val bad = listOf("", "AQID", "not base64!", B64.encode(ByteArray(31)), B64.encode(key).trimEnd('='), " " + B64.encode(key))
        for (k in bad) {
            try {
                service().downloadInto(media, k, "tok", message)
                fail("key '$k'")
            } catch (_: MediaCrypto.MediaError.InvalidKey) {
            }
        }
        assertEquals(0, server.requestCount)
    }

    @Test
    fun whileChatsAreLockedNothingIsDownloaded() = runTest {
        state.lock()
        try {
            service().downloadInto(media, B64.encode(key), "tok", message)
            fail("locked")
        } catch (_: CryptoError.Locked) {
        }
        assertEquals(0, server.requestCount)
    }

    @Test
    fun aServerRefusalStoresNothing() = runTest {
        server.enqueue(MockResponse(code = 403, body = """{"error":{"code":"FORBIDDEN","message":"This media was deleted for everyone."}}"""))
        val error = try {
            service().downloadInto(media, B64.encode(key), "tok", message)
            null
        } catch (e: ApiError.Server) {
            e
        }!!
        assertEquals("This media was deleted for everyone.", error.userMessage)
        assertFalse(cache.has(message))
        assertNoTempFiles()
    }

    @Test
    fun aCancelledDownloadLeavesNothing() = runBlocking {
        val sealed = MediaCrypto.sealFile(ByteArray(2 * 1024 * 1024), ScriptedEntropy(key, nonce)).sealed
        server.enqueue(MockResponse.Builder().code(200).body(Buffer().write(sealed)).throttleBody(64L * 1024, 50, TimeUnit.MILLISECONDS).build())
        val started = CountDownLatch(1)
        val job = async(Dispatchers.Default) {
            service().downloadInto(media, B64.encode(key), "tok", message) { started.countDown() }
        }
        assertTrue(started.await(10, TimeUnit.SECONDS))
        job.cancelAndJoin()
        assertFalse(cache.has(message))
        assertTrue(cache.directory.listFiles().orEmpty().isEmpty())
        assertNoTempFiles()
    }

    private fun sparse(length: Long): File = tempFiles.create("export", "mp4").also { RandomAccessFile(it, "rw").use { f -> f.setLength(length) } }

    private fun assertNoTempFiles() {
        assertTrue("no shroud-dl leftovers", temp.cacheDir.listFiles().orEmpty().none { it.name.startsWith("shroud-dl-") })
    }

    private fun assertMonotonicEndingAtOne(progress: List<Double>) {
        assertTrue("some progress", progress.isNotEmpty())
        assertEquals(1.0, progress.last(), 0.0)
        assertEquals("1.0 once", 1, progress.count { it == 1.0 })
        progress.zipWithNext().forEach { (a, b) -> assertTrue("$a then $b in $progress", b > a && b - a >= ProgressThrottle.MIN_STEP || b == 1.0) }
    }
}
