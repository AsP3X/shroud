package de.corespace.shroud.core.media

import de.corespace.shroud.core.crypto.B64
import de.corespace.shroud.core.crypto.MediaCrypto
import de.corespace.shroud.core.crypto.ScriptedEntropy
import de.corespace.shroud.core.keys.SealedLocalState
import de.corespace.shroud.core.media.files.Shrf1
import de.corespace.shroud.core.net.ApiClient
import de.corespace.shroud.core.net.ShroudApi
import de.corespace.shroud.core.storage.SensitiveTempFiles
import de.corespace.shroud.core.storage.StorageSeal
import de.corespace.shroud.testing.SealedTestKey
import de.corespace.shroud.testing.TempDirRule
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.UUID

/**
 * File blobs over the wire (docs/file-sharing.md §3, §9): the PUT body is SHRF1 sealed as OkHttp
 * writes it — byte for byte the cross-client vectors — with `application/octet-stream` and the
 * exact sealed size, and a download is opened segment by segment into the cache, committed only
 * when every tag and the size checked out.
 */
class MediaTransferServiceFileTest {
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
    private val key = ByteArray(32) { it.toByte() }
    private val prefix = byteArrayOf(0xa0.toByte(), 0xa1.toByte(), 0xa2.toByte(), 0xa3.toByte(), 0xa4.toByte(), 0xa5.toByte(), 0xa6.toByte())
    private val hello = "5348524631a0a1a2a3a4a5a6000100001f042572d7a195d96a199b78b66a618521c6b35bc9"

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

    private fun service() = MediaTransferService(api, cache, tempFiles, ScriptedEntropy(key, prefix))

    private fun created() = MockResponse(
        code = 201,
        body = """{"media_object_id":"$media","upload_url":"media/$media/content","object_key":"media/3b/$media","expires_at":"2026-10-01T13:00:00Z"}""",
    )

    private fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it) }

    private fun pattern(n: Int) = ByteArray(n) { (it % 251).toByte() }

    @Test
    fun aFileUploadIsTheHelloVector() = runTest {
        server.enqueue(created())
        server.enqueue(MockResponse(code = 204))
        val blob = service().uploadFile(PlainSource.InMemory("hello".toByteArray()), "tok")
        assertEquals(B64.encode(key), blob.keyBase64)
        assertEquals(5L, blob.plainSize)
        assertEquals(37L, blob.sealedSize)
        val create = server.takeRequest()
        // Nothing about the type reaches the server in the clear.
        assertEquals("""{"size_bytes":37,"content_type":"application/octet-stream"}""", create.body!!.utf8())
        val put = server.takeRequest()
        assertEquals("application/octet-stream", put.headers["Content-Type"])
        assertEquals("37", put.headers["Content-Length"])
        assertEquals(hello, hex(put.body!!.toByteArray()))
    }

    @Test
    fun aCachedFileStreamsUpAsThe200000ByteVector() = runTest {
        cache.save(message, pattern(200_000))
        server.enqueue(created())
        server.enqueue(MockResponse(code = 204))
        val blob = service().uploadFile(PlainSource.LocalMedia(message), "tok")
        assertEquals(200_080L, blob.sealedSize)
        server.takeRequest()
        val body = server.takeRequest().body!!.toByteArray()
        assertEquals("b59a0b0206599c2fb139a31cc5a82d0feb6e8e6a715f5e4dbc03081275f924a5", hex(MessageDigest.getInstance("SHA-256").digest(body)))
    }

    @Test
    fun aBodyWrittenTwiceIsByteIdenticalAndADestroyedOneNoLongerSeals() {
        for (size in listOf(0, 1, 65_536, 65_537, 2_000_000)) {
            val plain = pattern(size)
            val body = MediaTransferService.Shrf1SealingBody(MediaTransferService.Plain.Memory(plain), key, prefix, Shrf1.sealedSize(size.toLong()))
            assertEquals(Shrf1.sealedSize(size.toLong()), body.contentLength())
            assertEquals("application/octet-stream", body.contentType().toString())
            val first = Buffer().also { body.writeTo(it) }.readByteArray()
            val second = Buffer().also { body.writeTo(it) }.readByteArray()
            assertArrayEquals("size $size", first, second)
            assertEquals("size $size", first.size.toLong(), body.contentLength())
            body.destroy()
            try {
                body.writeTo(Buffer())
                fail("destroyed")
            } catch (_: IOException) {
            }
        }
    }

    @Test
    fun aSourceThatChangedBetweenWritesIsRefused() {
        val plain = pattern(3_000_000)
        val body = MediaTransferService.Shrf1SealingBody(MediaTransferService.Plain.Memory(plain), key, prefix, Shrf1.sealedSize(plain.size.toLong()))
        Buffer().also { body.writeTo(it) }
        plain[2_500_000] = (plain[2_500_000].toInt() xor 1).toByte()
        try {
            body.writeTo(Buffer())
            fail("changed source")
        } catch (_: IOException) {
        }
    }

    @Test
    fun aDownloadOpensIntoTheCacheAndLeavesNoTempFile() = runTest {
        val sealed = ByteArrayOutputStream().also { Shrf1.seal(pattern(200_000).inputStream(), 200_000, it, key, prefix) }.toByteArray()
        server.enqueue(MockResponse.Builder().code(200).body(Buffer().write(sealed)).build())
        service().downloadFileInto(media, B64.encode(key), 200_000, "tok", message)
        assertArrayEquals(pattern(200_000), cache.readAll(message))
        assertNoTempFiles()
    }

    @Test
    fun aBlobThatIsTheWrongSizeOrDoesNotOpenStoresNothing() = runTest {
        val sealed = ByteArrayOutputStream().also { Shrf1.seal(pattern(70_000).inputStream(), 70_000, it, key, prefix) }.toByteArray()
        // Claimed one byte larger; truncated; tampered in the last segment.
        val cases = listOf(
            70_001L to sealed,
            70_000L to sealed.copyOf(sealed.size - 1),
            70_000L to sealed.copyOf().also { it[it.size - 1] = (it[it.size - 1].toInt() xor 1).toByte() },
        )
        for ((size, blob) in cases) {
            server.enqueue(MockResponse.Builder().code(200).body(Buffer().write(blob)).build())
            try {
                service().downloadFileInto(media, B64.encode(key), size, "tok", message)
                fail("size $size, ${blob.size} bytes")
            } catch (_: MediaCrypto.MediaError.DecryptFailed) {
            }
            assertFalse(cache.has(message))
        }
        assertTrue("no pending file", cache.directory.listFiles().orEmpty().isEmpty())
        assertNoTempFiles()
    }

    @Test
    fun aBadKeyOrAnImpossibleSizeFailsBeforeAnyRequest() = runTest {
        try {
            service().downloadFileInto(media, "not base64", 10, "tok", message)
            fail("bad key")
        } catch (_: MediaCrypto.MediaError.InvalidKey) {
        }
        try {
            service().downloadFileInto(media, B64.encode(key), MediaCrypto.MAX_PLAINTEXT_BYTES + 1, "tok", message)
            fail("too large")
        } catch (_: MediaCrypto.MediaError.DecryptFailed) {
        }
        assertEquals(0, server.requestCount)
    }

    private fun assertNoTempFiles() {
        assertTrue("no shroud-dl leftovers", temp.cacheDir.listFiles().orEmpty().none { it.name.startsWith("shroud-dl-") })
    }
}
