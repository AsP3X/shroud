package de.corespace.shroud.core.media

import de.corespace.shroud.core.crypto.CryptoError
import de.corespace.shroud.core.crypto.LocalHistoryCrypto
import de.corespace.shroud.core.crypto.Primitives
import de.corespace.shroud.core.crypto.ScriptedEntropy
import de.corespace.shroud.core.crypto.hexToBytes
import de.corespace.shroud.core.crypto.utf8
import de.corespace.shroud.core.keys.LocalNames
import de.corespace.shroud.core.keys.SealedLocalState
import de.corespace.shroud.core.storage.StorageSeal
import de.corespace.shroud.testing.SealedTestKey
import de.corespace.shroud.testing.TempDirRule
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.util.UUID
import kotlin.random.Random

/**
 * The SHRM1 media cache (media-voice-links §7.1, D3; plan §1.5, C7, C10), the Android form of iOS
 * `LocalMediaCache` (`ios/shroud/Services/Messaging/LocalMediaCache.swift:11-97`): round trips at
 * every segment edge, the byte layout of the spec, random access across segment boundaries,
 * truncation / reordering / splicing / tampering failing closed, empty media, rename on re-key,
 * the chat lock, the wipe's storage seal, atomic commits and keyed file names.
 */
class LocalMediaCacheTest {
    @get:Rule val temp = TempDirRule()

    private val state = SealedLocalState()
    private val seal = StorageSeal()
    private lateinit var dir: File
    private lateinit var cache: LocalMediaCache

    private val photo = UUID.fromString("0f8fad5b-d9cb-469f-a165-70867728950e")
    private val other = UUID.fromString("7c9e6679-7425-40de-944b-e07fc1f90ae7")

    @Before
    fun setUp() {
        state.unlock(SealedTestKey.bytes())
        dir = File(temp.noBackupFilesDir, "shroud/media")
        cache = LocalMediaCache(dir, state, seal)
    }

    @After
    fun tearDown() = cache.close()

    // ---- Round trips and the format ----

    @Test
    fun roundTripsAtEverySegmentEdge() {
        val sizes = listOf(0, 1, 15, 16, 17, 65_535, 65_536, 65_537, 131_071, 131_072, 131_073, 200_000, 3 * 65_536 + 5)
        for (size in sizes) {
            val data = random(size, seed = size)
            cache.saveBlocking(photo, data)
            assertTrue(cache.has(photo))
            assertArrayEquals("size $size", data, cache.readAllBlocking(photo))
            val segments = maxOf(1, (size + 65_535) / 65_536)
            assertEquals("file size for $size", 32L + segments * 16L + size, sealedFile(photo).length())
            cache.openReader(photo)!!.use { assertEquals(size.toLong(), it.length) }
        }
    }

    @Test
    fun theFileIsTheSpecsLayoutByteForByte() {
        val salt = hexToBytes("000102030405060708090a0b0c0d0e0f")
        val prefix = hexToBytes("a0a1a2a3a4a5a6")
        val scripted = LocalMediaCache(dir, state, seal, ScriptedEntropy(salt, prefix))
        val plaintext = random(150_000, seed = 1)
        try {
            scripted.saveBlocking(photo, plaintext)
        } finally {
            scripted.close()
        }

        // Re-derived here from media-voice-links §7.1, independently of the implementation.
        val header = utf8("SHRM1") + salt + prefix + hexToBytes("00010000")
        assertEquals(32, header.size)
        val subkey = LocalHistoryCrypto.subkey(SealedTestKey.bytes(), LocalHistoryCrypto.Context.MediaFile)
        val fileKey = Primitives.hkdf(subkey, salt, utf8("shroud-local-media-stream-v1"), 32)
        var expected = header
        val chunks = listOf(0 until 65_536, 65_536 until 131_072, 131_072 until 150_000)
        chunks.forEachIndexed { index, range ->
            val last = index == chunks.lastIndex
            val nonce = prefix + byteArrayOf(0, 0, 0, index.toByte()) + byteArrayOf(if (last) 1 else 0)
            val combined = Primitives.aesGcmSeal(fileKey, nonce, plaintext.copyOfRange(range.first, range.last + 1), header)
            expected += combined.copyOfRange(12, combined.size) // ct ‖ tag, no nonce on disk
        }
        assertArrayEquals(expected, sealedFile(photo).readBytes())
        assertArrayEquals(plaintext, cache.readAllBlocking(photo))
    }

    @Test
    fun emptyMediaIsOneEmptyLastSegment() {
        cache.saveBlocking(photo, ByteArray(0))
        assertEquals(48L, sealedFile(photo).length())
        assertArrayEquals(ByteArray(0), cache.readAllBlocking(photo))
        cache.openReader(photo)!!.use { reader ->
            assertEquals(0L, reader.length)
            assertEquals(-1, reader.read(0, ByteArray(4), 0, 4))
            assertEquals(0, reader.read(0, ByteArray(4), 0, 0))
        }
    }

    @Test
    fun randomAccessAcrossSegmentBoundaries() {
        val data = random(300_000, seed = 3)
        cache.saveBlocking(photo, data)
        cache.openReader(photo)!!.use { reader ->
            assertEquals(300_000L, reader.length)
            fun check(position: Long, size: Int) {
                val buffer = ByteArray(size + 7)
                val read = reader.read(position, buffer, 7, size)
                val expected = minOf(size.toLong(), 300_000 - position).toInt()
                assertEquals("read at $position", expected, read)
                assertArrayEquals(data.copyOfRange(position.toInt(), position.toInt() + expected), buffer.copyOfRange(7, 7 + read))
            }
            check(0, 10)
            check(65_535, 2) // the last byte of segment 0 and the first of segment 1
            check(65_530, 70_000) // spans three segments
            check(131_071, 10)
            check(131_072, 1)
            check(299_990, 100) // short read at the end
            check(0, 300_000) // everything at once
            val random = Random(9)
            repeat(200) {
                val position = random.nextLong(300_000)
                check(position, random.nextInt(1, 140_000))
            }
            // Backwards after forwards (ExoPlayer seeks both ways).
            check(250_000, 50)
            check(10, 50)
            assertEquals(-1, reader.read(300_000, ByteArray(1), 0, 1))
            assertEquals(-1, reader.read(400_000, ByteArray(1), 0, 1))
        }
    }

    @Test
    fun aReaderRefusesBadArguments() {
        cache.saveBlocking(photo, random(10, seed = 4))
        cache.openReader(photo)!!.use { reader ->
            assertThrows(IndexOutOfBoundsException::class.java) { reader.read(0, ByteArray(4), 2, 3) }
            assertThrows(IndexOutOfBoundsException::class.java) { reader.read(0, ByteArray(4), -1, 1) }
            assertThrows(IllegalArgumentException::class.java) { reader.read(-1, ByteArray(4), 0, 1) }
        }
    }

    @Test
    fun aClosedReaderThrows() {
        cache.saveBlocking(photo, random(10, seed = 5))
        val reader = cache.openReader(photo)!!
        reader.close()
        reader.close() // idempotent
        assertThrows(IOException::class.java) { reader.read(0, ByteArray(4), 0, 4) }
    }

    @Test
    fun theStreamReadsEverythingInOrder() {
        val data = random(140_000, seed = 6)
        cache.saveBlocking(photo, data)
        cache.openStream(photo)!!.use { stream ->
            assertEquals(140_000, stream.available())
            assertEquals(data[0].toInt() and 0xFF, stream.read())
            assertEquals(10L, stream.skip(10))
            val rest = stream.readBytes()
            assertArrayEquals(data.copyOfRange(11, data.size), rest)
            assertEquals(-1, stream.read())
        }
    }

    @Test
    fun suspendingReadAndSaveRunOffTheCaller() = runTest {
        val data = random(70_000, seed = 7)
        cache.save(photo, data)
        assertArrayEquals(data, cache.readAll(photo))
        assertNull(cache.readAll(other))
    }

    // ---- Failing closed ----

    @Test
    fun truncationFailsClosed() {
        val data = random(150_000, seed = 8)
        cache.saveBlocking(photo, data)
        val original = sealedFile(photo).readBytes()
        // Within the last segment.
        writeFile(original.copyOf(original.size - 1))
        assertNull(cache.openReader(photo))
        assertNull(cache.readAllBlocking(photo))
        // The whole last segment dropped: the previous one was sealed as "not last".
        writeFile(original.copyOf(32 + 2 * (65_536 + 16)))
        assertNull(cache.openReader(photo))
        // Shorter than one tag after the header, the header alone, less than a header, nothing.
        for (size in listOf(32 + 15, 32, 20, 0)) {
            writeFile(original.copyOf(size))
            assertNull("size $size", cache.openReader(photo))
        }
        // A last segment shorter than a tag.
        writeFile(original.copyOf(32 + 65_552 + 5))
        assertNull(cache.openReader(photo))
    }

    @Test
    fun appendingASegmentFailsClosed() {
        cache.saveBlocking(photo, random(70_000, seed = 9))
        val original = sealedFile(photo).readBytes()
        val last = original.copyOfRange(32 + 65_552, original.size)
        writeFile(original + last)
        assertNull(cache.openReader(photo))
    }

    @Test
    fun reorderedSegmentsFailToOpen() {
        val data = random(3 * 65_536 + 100, seed = 10)
        cache.saveBlocking(photo, data)
        val original = sealedFile(photo).readBytes()
        val swapped = original.copyOf()
        val first = 32
        val second = 32 + 65_552
        original.copyInto(swapped, first, second, second + 65_552)
        original.copyInto(swapped, second, first, first + 65_552)
        writeFile(swapped)
        // The last segment is intact, so the file opens; the swapped ones do not.
        cache.openReader(photo)!!.use { reader ->
            assertThrows(IOException::class.java) { reader.read(0, ByteArray(16), 0, 16) }
            assertThrows(IOException::class.java) { reader.read(65_536, ByteArray(16), 0, 16) }
            val tail = ByteArray(100)
            assertEquals(100, reader.read(3 * 65_536L, tail, 0, 100))
            assertArrayEquals(data.copyOfRange(3 * 65_536, data.size), tail)
        }
        assertNull(cache.readAllBlocking(photo))
    }

    @Test
    fun aSegmentFromAnotherFileFailsToOpen() {
        cache.saveBlocking(photo, random(140_000, seed = 11))
        cache.saveBlocking(other, random(140_000, seed = 12))
        val mine = sealedFile(photo).readBytes()
        val theirs = sealedFile(other).readBytes()
        theirs.copyInto(mine, 32, 32, 32 + 65_552) // same index, same history key, other salt
        writeFile(mine)
        cache.openReader(photo)!!.use { reader ->
            assertThrows(IOException::class.java) { reader.read(0, ByteArray(1), 0, 1) }
        }
    }

    @Test
    fun tamperingFailsClosed() {
        val data = random(150_000, seed = 13)
        cache.saveBlocking(photo, data)
        val original = sealedFile(photo).readBytes()

        // A byte of segment 1: segments 0 and 2 still read, segment 1 does not.
        writeFile(original.copyOf().also { it[32 + 65_552 + 100] = (it[32 + 65_552 + 100].toInt() xor 1).toByte() })
        cache.openReader(photo)!!.use { reader ->
            val head = ByteArray(10)
            assertEquals(10, reader.read(0, head, 0, 10))
            assertArrayEquals(data.copyOf(10), head)
            assertThrows(IOException::class.java) { reader.read(65_536, ByteArray(10), 0, 10) }
            val buffer = ByteArray(10)
            assertEquals(10, reader.read(140_000, buffer, 0, 10))
        }
        assertNull(cache.readAllBlocking(photo))

        // The header is every segment's AAD: magic, salt, prefix and segment size are all bound.
        for (at in listOf(0, 4, 5, 20, 21, 27, 28, 31)) {
            writeFile(original.copyOf().also { it[at] = (it[at].toInt() xor 0x40).toByte() })
            assertNull("header byte $at", cache.openReader(photo))
        }
        // A tag byte of the last segment.
        writeFile(original.copyOf().also { it[it.size - 1] = (it[it.size - 1].toInt() xor 1).toByte() })
        assertNull(cache.openReader(photo))
    }

    @Test
    fun anotherHistoryKeyCannotOpenTheFile() {
        cache.saveBlocking(photo, random(1000, seed = 14))
        val sealed = sealedFile(photo).readBytes()
        state.unlock(ByteArray(32) { 0x11 })
        // Under the other key the name is different; put the file there to try opening it.
        File(dir, LocalNames.derive(ByteArray(32) { 0x11 }).name("media", photo) + ".sealed").writeBytes(sealed)
        assertNull(cache.openReader(photo))
    }

    @Test
    fun aLargeFileOpensAtItsLastSegmentAndReadAllRefusesIt() {
        // 300 MiB of plaintext as a sparse file: only the last segment is real, sealed with the file key.
        val segments = 300L * 1024 * 1024 / 65_536
        val salt = ByteArray(16) { 3 }
        val prefix = ByteArray(7) { 4 }
        val header = Shrm1.header(salt, prefix)
        val fileKey = state.withSubkey(LocalHistoryCrypto.Context.MediaFile) { Shrm1.fileKey(it, salt) }!!
        val tail = random(65_536, seed = 15)
        val combined = Primitives.aesGcmSeal(fileKey, Shrm1.nonce(prefix, segments - 1, last = true), tail, header)
        dir.mkdirs()
        RandomAccessFile(sealedFile(photo), "rw").use { file ->
            file.write(header)
            file.seek(32 + (segments - 1) * 65_552)
            file.write(combined, 12, combined.size - 12)
        }
        cache.openReader(photo)!!.use { reader ->
            assertEquals(segments * 65_536, reader.length)
            val buffer = ByteArray(65_536)
            assertEquals(65_536, reader.read((segments - 1) * 65_536, buffer, 0, 65_536))
            assertArrayEquals(tail, buffer)
            // The zeros before it are not a valid segment.
            assertThrows(IOException::class.java) { reader.read(0, buffer, 0, 1) }
        }
        assertTrue(segments * 65_536 > LocalMediaCache.READ_ALL_LIMIT_BYTES)
        assertNull(cache.readAllBlocking(photo))
    }

    // ---- Names, rename, remove, inventory ----

    @Test
    fun filesAreNamedByKeyedHashesAndStartWithTheMagic() {
        cache.saveBlocking(photo, random(10, seed = 16))
        cache.saveBlocking(other, random(10, seed = 17))
        val names = LocalNames.derive(SealedTestKey.bytes())
        val expected = setOf(names.name("media", photo) + ".sealed", names.name("media", other) + ".sealed")
        val files = dir.listFiles()!!.map { it.name }.toSet()
        assertEquals(expected, files)
        for (file in dir.listFiles()!!) {
            assertTrue(file.name.matches(Regex("[0-9a-f]{32}\\.sealed")))
            assertFalse(file.name.contains(photo.toString().substring(0, 8)))
            assertArrayEquals(utf8("SHRM1"), file.readBytes().copyOf(5))
        }
    }

    @Test
    fun renameMovesTheFileForAServerReKey() {
        val data = random(90_000, seed = 18)
        cache.saveBlocking(photo, data)
        cache.rename(photo, other)
        assertFalse(cache.has(photo))
        assertArrayEquals(data, cache.readAllBlocking(other))

        // Onto an existing file: replaced.
        val newer = random(100, seed = 19)
        cache.saveBlocking(photo, newer)
        cache.rename(photo, other)
        assertArrayEquals(newer, cache.readAllBlocking(other))

        // Nothing to move, or the same id: no change.
        cache.rename(photo, other)
        cache.rename(other, other)
        assertArrayEquals(newer, cache.readAllBlocking(other))
        assertEquals(1, dir.listFiles()!!.size)
    }

    @Test
    fun removeDeletesOnlyTheGivenIds() {
        cache.saveBlocking(photo, random(10, seed = 20))
        cache.saveBlocking(other, random(10, seed = 21))
        cache.remove(listOf(photo, UUID.randomUUID()))
        assertFalse(cache.has(photo))
        assertTrue(cache.has(other))
        cache.remove(emptyList())
        assertTrue(cache.has(other))
    }

    @Test
    fun inventoryCountsFilesAndBytes() {
        assertEquals(0 to 0L, cache.inventory())
        cache.saveBlocking(photo, ByteArray(100))
        cache.saveBlocking(other, ByteArray(70_000))
        assertEquals(2 to (32L + 16 + 100) + (32L + 32 + 70_000), cache.inventory())
    }

    @Test
    fun clearAllDeletesEverythingEvenWhileLocked() {
        cache.saveBlocking(photo, random(10, seed = 22))
        val reader = cache.openReader(photo)!!
        state.lock()
        cache.clearAll()
        assertFalse(dir.exists())
        assertEquals(0 to 0L, cache.inventory())
        assertThrows(IOException::class.java) { reader.read(0, ByteArray(1), 0, 1) }
        reader.close()
        // The directory comes back with the next write (iOS `save` re-creates it).
        state.unlock(SealedTestKey.bytes())
        cache.saveBlocking(photo, ByteArray(3))
        assertTrue(cache.has(photo))
    }

    // ---- The chat lock ----

    @Test
    fun nothingReadsOrWritesWhileLocked() {
        cache.saveBlocking(photo, random(10, seed = 23))
        state.lock()
        assertFalse(cache.has(photo))
        assertNull(cache.openReader(photo))
        assertNull(cache.readAllBlocking(photo))
        assertThrows(CryptoError.Locked::class.java) { cache.writer(other) }
        cache.saveBlocking(other, ByteArray(10)) // silent, as iOS
        assertEquals(1, dir.listFiles()!!.size)
        state.unlock(SealedTestKey.bytes())
        assertTrue(cache.has(photo))
        assertFalse(cache.has(other))
    }

    @Test
    fun lockingZeroesOpenReaders() {
        cache.saveBlocking(photo, random(100_000, seed = 24))
        val reader = cache.openReader(photo)!!
        assertEquals(10, reader.read(0, ByteArray(10), 0, 10))
        state.lock()
        val error = assertThrows(IOException::class.java) { reader.read(0, ByteArray(10), 0, 10) }
        assertSame(CryptoError.Locked, error.cause)
        reader.close()
    }

    @Test
    fun lockingDropsUnfinishedWriters() {
        val writer = cache.writer(photo)
        writer.write(ByteArray(100_000), 0, 100_000)
        assertEquals(1, dir.listFiles()!!.count { it.name.endsWith(".pending") })
        state.lock()
        assertTrue(dir.listFiles()!!.isEmpty())
        assertThrows(CryptoError.Locked::class.java) { writer.write(ByteArray(1), 0, 1) }
        assertThrows(CryptoError.Locked::class.java) { writer.commit() }
        writer.close()
        state.unlock(SealedTestKey.bytes())
        assertFalse(cache.has(photo))
    }

    @Test
    fun renamesAndRemovalsWhileLockedWaitForTheUnlock() {
        cache.saveBlocking(photo, random(10, seed = 25))
        cache.saveBlocking(other, random(10, seed = 26))
        val third = UUID.randomUUID()
        state.lock()
        cache.rename(photo, third)
        cache.remove(listOf(other))
        assertEquals(2, dir.listFiles()!!.size)
        state.unlock(SealedTestKey.bytes())
        assertFalse(cache.has(photo))
        assertFalse(cache.has(other))
        assertTrue(cache.has(third))
    }

    @Test
    fun clearAllForgetsWaitingChanges() {
        cache.saveBlocking(photo, random(10, seed = 27))
        state.lock()
        cache.rename(photo, other)
        cache.clearAll()
        state.unlock(SealedTestKey.bytes())
        cache.saveBlocking(photo, ByteArray(1))
        assertTrue(cache.has(photo))
        assertFalse(cache.has(other))
    }

    // ---- Writers: atomic commit, abort, the wipe's seal ----

    @Test
    fun nothingIsVisibleBeforeCommit() {
        val data = random(200_000, seed = 28)
        val writer = cache.writer(photo)
        writer.write(data, 0, 70_000)
        writer.write(data, 70_000, 130_000)
        assertFalse(cache.has(photo))
        writer.commit()
        assertTrue(cache.has(photo))
        assertArrayEquals(data, cache.readAllBlocking(photo))
        assertTrue(dir.listFiles()!!.none { it.name.endsWith(".pending") })
        assertThrows(IOException::class.java) { writer.write(ByteArray(1), 0, 1) }
        assertThrows(IOException::class.java) { writer.commit() }
    }

    @Test
    fun manySmallWritesMakeTheSameFile() {
        val data = random(140_001, seed = 29)
        val writer = cache.writer(photo)
        var at = 0
        val random = Random(30)
        while (at < data.size) {
            val count = minOf(random.nextInt(1, 9_000), data.size - at)
            writer.write(data, at, count)
            at += count
        }
        writer.commit()
        assertArrayEquals(data, cache.readAllBlocking(photo))
    }

    @Test
    fun abortAndCloseLeaveNothing() {
        cache.writer(photo).apply {
            write(ByteArray(10), 0, 10)
            abort()
            abort()
        }
        cache.writer(photo).use { it.write(ByteArray(70_000), 0, 70_000) } // closed without commit
        assertFalse(cache.has(photo))
        assertTrue(dir.listFiles()!!.isEmpty())
    }

    @Test
    fun aCommitReplacesTheFileWhileOpenReadersKeepTheirs() {
        val first = random(100_000, seed = 31)
        val second = random(5_000, seed = 32)
        cache.saveBlocking(photo, first)
        cache.openReader(photo)!!.use { old ->
            cache.saveBlocking(photo, second)
            val buffer = ByteArray(100_000)
            assertEquals(100_000, old.read(0, buffer, 0, 100_000) + 0)
            assertArrayEquals(first, buffer)
        }
        assertArrayEquals(second, cache.readAllBlocking(photo))
    }

    @Test
    fun theWipesSealDropsEveryWrite() {
        val before = cache.writer(photo)
        before.write(ByteArray(10), 0, 10)
        seal.seal()
        before.commit() // sealed meanwhile: dropped
        cache.saveBlocking(other, ByteArray(10))
        cache.writer(other).apply {
            write(ByteArray(10), 0, 10)
            commit()
        }
        cache.saveBlocking(photo, ByteArray(10))
        cache.rename(photo, other)
        assertTrue(!dir.exists() || dir.listFiles()!!.isEmpty())
        seal.unseal()
        cache.saveBlocking(photo, ByteArray(10))
        assertTrue(cache.has(photo))
    }

    @Test
    fun pendingFilesOfAnEarlierProcessAreSwept() {
        dir.mkdirs()
        val stale = File(dir, "0123456789abcdef0123456789abcdef.deadbeef.pending").apply { writeBytes(ByteArray(10)) }
        val keep = File(dir, "unrelated.sealed").apply { writeBytes(ByteArray(3)) }
        val writer = cache.writer(photo)
        assertFalse(stale.exists())
        assertTrue(keep.exists())
        // A second writer does not sweep the first one's pending file.
        val second = cache.writer(other)
        writer.write(ByteArray(5), 0, 5)
        writer.commit()
        second.commit()
        assertTrue(cache.has(photo))
        assertTrue(cache.has(other))
    }

    @Test
    fun writerArgumentsAreChecked() {
        cache.writer(photo).use { writer ->
            assertThrows(IndexOutOfBoundsException::class.java) { writer.write(ByteArray(4), 3, 2) }
            assertThrows(IndexOutOfBoundsException::class.java) { writer.write(ByteArray(4), 0, -1) }
            writer.write(ByteArray(4), 4, 0)
        }
    }

    @Test
    fun concurrentReadersAgree() {
        val data = random(500_000, seed = 33)
        cache.saveBlocking(photo, data)
        val failures = java.util.concurrent.atomic.AtomicInteger()
        val threads = (0 until 4).map { t ->
            Thread {
                cache.openReader(photo)!!.use { reader ->
                    val random = Random(t)
                    repeat(100) {
                        val position = random.nextInt(500_000)
                        val size = random.nextInt(1, 100_000)
                        val buffer = ByteArray(size)
                        val read = reader.read(position.toLong(), buffer, 0, size)
                        if (!buffer.copyOf(read).contentEquals(data.copyOfRange(position, position + read))) failures.incrementAndGet()
                    }
                }
            }.apply { start() }
        }
        threads.forEach { it.join() }
        assertEquals(0, failures.get())
        assertNotNull(cache.openReader(photo)?.also { it.close() })
    }

    private fun sealedFile(id: UUID) = File(dir, LocalNames.derive(SealedTestKey.bytes()).name("media", id) + ".sealed")

    private fun writeFile(bytes: ByteArray) = sealedFile(photo).writeBytes(bytes)

    private fun random(size: Int, seed: Int): ByteArray = Random(seed).nextBytes(size)
}
