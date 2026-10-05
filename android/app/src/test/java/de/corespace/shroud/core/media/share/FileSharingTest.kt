package de.corespace.shroud.core.media.share

import android.app.Application
import android.content.ContentProvider
import android.content.ContentValues
import android.content.pm.ProviderInfo
import android.database.Cursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import android.provider.OpenableColumns
import androidx.test.core.app.ApplicationProvider
import de.corespace.shroud.core.media.SealedMediaReader
import de.corespace.shroud.testing.FakeAppClock
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowContentResolver
import java.io.File
import java.io.FileNotFoundException
import java.util.UUID

/**
 * Files through the decrypted-media provider and Save to Downloads (docs/file-sharing.md §4, §6–§8):
 * grants read the sealed reader, never an in-memory copy; `OpenableColumns` give the cleaned name and
 * size; the content check guards Open only; an APK is never opened; revoking drops file grants too.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class FileSharingTest {
    private val app = ApplicationProvider.getApplicationContext<Application>()
    private val files = HashMap<UUID, ByteArray>()
    private val opened = ArrayList<UUID>()
    private val sharing = MemoryMediaSharing(app, { null }, FakeAppClock(), openReader = { id -> files[id]?.let { opened += id; Reader(it) } })

    @After
    fun tearDown() = sharing.revokeAll()

    private val pdf = "%PDF-1.7\n".toByteArray() + ByteArray(150_000) { (it % 251).toByte() }

    @Test(timeout = 15_000)
    fun openGivesACheckedGrantWithTheCleanedNameAndSize() = runBlocking {
        val id = UUID.randomUUID().also { files[it] = pdf }
        val outcome = sharing.openTarget(id, "../Quarterly\u202E report.pdf")
        val ready = outcome as FileOpenOutcome.Ready
        assertEquals("pdf", ready.extension)
        assertEquals("application/pdf", ready.target.mime)
        assertEquals("${app.packageName}.media", ready.target.uri.authority)

        val provider = provider()
        assertEquals("application/pdf", provider.getType(ready.target.uri))
        provider.query(ready.target.uri, null, null, null, null)!!.use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("Quarterly report.pdf", cursor.getString(cursor.getColumnIndexOrThrow(OpenableColumns.DISPLAY_NAME)))
            assertEquals(pdf.size.toLong(), cursor.getLong(cursor.getColumnIndexOrThrow(OpenableColumns.SIZE)))
        }
        provider.query(ready.target.uri, arrayOf(OpenableColumns.SIZE, "_data"), null, null, null)!!.use { cursor ->
            assertEquals(listOf(OpenableColumns.SIZE), cursor.columnNames.toList())
        }
        assertArrayEquals(pdf, read(provider.openFile(ready.target.uri, "r")))
        try {
            provider.openFile(ready.target.uri, "w")
            fail("read only")
        } catch (_: FileNotFoundException) {
        }

        sharing.revokeAll()
        assertNull(provider.query(ready.target.uri, null, null, null, null))
        try {
            provider.openFile(ready.target.uri, "r")
            fail("revoked")
        } catch (_: FileNotFoundException) {
        }
    }

    @Test
    fun aMismatchAnApkAndAMissingFileAreNotOpened() = runBlocking {
        val notPdf = UUID.randomUUID().also { files[it] = "PK\u0003\u0004 not a pdf".toByteArray() }
        assertEquals(
            FileOpenOutcome.Refused("This file doesn't match its .pdf type, so Shroud won't open it."),
            sharing.openTarget(notPdf, "a.pdf"),
        )
        val apk = UUID.randomUUID().also { files[it] = byteArrayOf(0x50, 0x4B, 0x03, 0x04) }
        assertTrue(sharing.openTarget(apk, "app.apk") is FileOpenOutcome.Refused)
        assertEquals(FileOpenOutcome.Refused("Could not open that file."), sharing.openTarget(UUID.randomUUID(), "a.pdf"))
        assertTrue(sharing.openTarget(notPdf, "a.sh") is FileOpenOutcome.Refused)
    }

    @Test(timeout = 15_000)
    fun sharingSkipsTheContentCheckButNeedsTheFile() = runBlocking {
        val id = UUID.randomUUID().also { files[it] = "not ole at all".toByteArray() }
        val target = requireNotNull(sharing.fileShareTarget(id, "old.doc"))
        assertEquals("application/msword", target.mime)
        assertArrayEquals("not ole at all".toByteArray(), read(provider().openFile(target.uri, "r")))
        assertNull(sharing.fileShareTarget(UUID.randomUUID(), "old.doc"))
        assertNull(sharing.fileShareTarget(id, "old.exe"))
    }

    @Test(timeout = 15_000)
    fun aPurgedMessageLosesItsGrantsAndItsOpenReaders() = runBlocking {
        val deleted = UUID.randomUUID().also { files[it] = pdf }
        val kept = UUID.randomUUID().also { files[it] = pdf }
        val gone = (sharing.openTarget(deleted, "a.pdf") as FileOpenOutcome.Ready).target
        val stays = requireNotNull(sharing.fileShareTarget(kept, "b.pdf"))
        // A reader a descriptor still holds: revoking closes it, so the other app reads no further.
        val held = SharedMediaRegistry.open(gone.uri.lastPathSegment) as SharedMediaRegistry.SealedFile
        val reader = requireNotNull(held.reader()) as Reader

        sharing.revoke(listOf(deleted))
        assertTrue(reader.closed)
        val provider = provider()
        assertNull(provider.getType(gone.uri))
        assertNull(provider.query(gone.uri, null, null, null, null))
        try {
            provider.openFile(gone.uri, "r")
            fail("revoked")
        } catch (_: FileNotFoundException) {
        }
        assertArrayEquals(pdf, read(provider.openFile(stays.uri, "r")))
    }

    @Test
    fun theMessagingSinkRevokesWithoutBuildingSharing() {
        val container = de.corespace.shroud.AppContainer(app, de.corespace.shroud.core.lifecycle.AppPhaseMonitor(tracks = { false }))
        container.media.shareArtifactSink.onPurged(listOf(UUID.randomUUID()))
        assertNull(container.media.sharingIfBuilt)
    }

    @Test
    fun saveToDownloadsStreamsIntoAPendingRowThenPublishesIt() = runBlocking {
        val probe = downloads()
        val id = UUID.randomUUID().also { files[it] = pdf }
        assertEquals(SaveOutcome.Saved, sharing.saveToDownloads(id, "C:\\tmp\\Report <final>.PDF"))
        val (uri, inserted) = probe.inserts.single()
        assertTrue(uri.toString(), uri.toString().contains("downloads"))
        assertEquals("Report _final_.PDF", inserted.getAsString(MediaStore.MediaColumns.DISPLAY_NAME))
        assertEquals("application/pdf", inserted.getAsString(MediaStore.MediaColumns.MIME_TYPE))
        assertEquals("Download/Shroud", inserted.getAsString(MediaStore.MediaColumns.RELATIVE_PATH))
        assertEquals(1, inserted.getAsInteger(MediaStore.MediaColumns.IS_PENDING))
        assertEquals(0, probe.updates.single().second.getAsInteger(MediaStore.MediaColumns.IS_PENDING))
        assertArrayEquals(pdf, probe.file(uri).readBytes())

        assertEquals(SaveOutcome.Failed("Could not save that file."), sharing.saveToDownloads(UUID.randomUUID(), "a.pdf"))
        assertEquals(1, probe.inserts.size)
    }

    private fun provider(): DecryptedMediaProvider = DecryptedMediaProvider().apply {
        attachInfo(app, ProviderInfo().apply { authority = "${app.packageName}.media"; grantUriPermissions = true; exported = false })
    }

    private fun downloads(): DownloadsProbe {
        val probe = DownloadsProbe()
        probe.attachInfo(app, ProviderInfo().apply { authority = "media"; exported = false })
        ShadowContentResolver.registerProviderInternal("media", probe)
        return probe
    }

    private fun read(descriptor: ParcelFileDescriptor): ByteArray =
        ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { it.readBytes() }

    /** A plaintext reader as the SHRM1 cache hands out (random access, thread-safe). */
    private class Reader(private val data: ByteArray) : SealedMediaReader {
        override val length: Long = data.size.toLong()

        @Volatile var closed = false

        @Synchronized
        override fun read(position: Long, buffer: ByteArray, offset: Int, size: Int): Int {
            if (size == 0) return 0
            if (position >= data.size) return -1
            val count = minOf(size, data.size - position.toInt())
            System.arraycopy(data, position.toInt(), buffer, offset, count)
            return count
        }

        override fun close() {
            closed = true
        }
    }

    private class DownloadsProbe : ContentProvider() {
        val inserts = ArrayList<Pair<Uri, ContentValues>>()
        val updates = ArrayList<Pair<Uri, ContentValues>>()
        private val stored = HashMap<String, File>()

        fun file(uri: Uri): File = stored.getValue(uri.toString())

        override fun onCreate(): Boolean = true

        override fun insert(uri: Uri, values: ContentValues?): Uri {
            val row = Uri.withAppendedPath(uri, (inserts.size + 1).toString())
            inserts += row to ContentValues(values)
            return row
        }

        override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int {
            updates += uri to ContentValues(values)
            return 1
        }

        override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 1

        override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
            val file = stored.getOrPut(uri.toString()) {
                File(requireNotNull(context).cacheDir, "download-${uri.lastPathSegment}").apply { createNewFile() }
            }
            return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_WRITE or ParcelFileDescriptor.MODE_CREATE or ParcelFileDescriptor.MODE_TRUNCATE)
        }

        override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null

        override fun getType(uri: Uri): String? = null
    }
}
