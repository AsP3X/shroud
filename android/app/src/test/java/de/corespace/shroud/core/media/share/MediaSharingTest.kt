package de.corespace.shroud.core.media.share

import android.app.Application
import android.content.ContentProvider
import android.content.ContentValues
import android.content.pm.ProviderInfo
import android.database.Cursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import androidx.test.core.app.ApplicationProvider
import de.corespace.shroud.AppContainer
import de.corespace.shroud.core.lifecycle.AppPhaseMonitor
import de.corespace.shroud.testing.FakeAppClock
import java.io.File
import java.io.FileNotFoundException
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowContentResolver

/** In-memory share grants, and MediaStore saves that publish only after `IS_PENDING` clears. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class MediaSharingTest {
    private val app = ApplicationProvider.getApplicationContext<Application>()
    private val clock = FakeAppClock()

    @Test(timeout = 15_000)
    fun openFileReturnsTheBytesUntilRevokeAll() = runBlocking {
        val payload = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 1, 2, 3, 0xD9.toByte())
        val messageId = UUID.randomUUID()
        val sharing = sharing(mapOf(messageId to payload))
        val provider = provider()
        val uri = requireNotNull(sharing.shareUri(messageId))

        assertEquals("content", uri.scheme)
        assertEquals("${app.packageName}.media", uri.authority)
        assertNotEquals(messageId.toString(), uri.lastPathSegment)
        assertEquals("image/jpeg", provider.getType(uri))
        assertArrayEquals(payload, read(provider.openFile(uri, "r")))

        sharing.revokeAll()
        try {
            provider.openFile(uri, "r")
            fail("expected FileNotFoundException")
        } catch (_: FileNotFoundException) {
        }

        val again = requireNotNull(sharing.shareUri(messageId))
        assertNotEquals(uri, again)
        assertArrayEquals(payload, read(provider.openFile(again, "r")))
        try {
            provider.openFile(again, "w")
            fail("expected FileNotFoundException")
        } catch (_: FileNotFoundException) {
        }
    }

    @Test
    fun shareUriIsNullWhenTheBytesAreNotLoaded() = runBlocking {
        val sharing = sharing(emptyMap())
        assertNull(sharing.shareUri(UUID.randomUUID()))
        val failing = MemoryMediaSharing(app, { throw java.io.IOException("disk") }, clock)
        assertNull(failing.shareUri(UUID.randomUUID()))
    }

    @Test
    fun saveToGalleryPublishesAPendingImageThenClearsTheFlag() = runBlocking {
        val probe = gallery()
        val messageId = UUID.randomUUID()
        val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 9, 9, 0xD9.toByte())
        val outcome = sharing(mapOf(messageId to jpeg)).saveToGallery(messageId)

        assertEquals(SaveOutcome.Saved, outcome)
        assertEquals(1, probe.inserts.size)
        val (uri, inserted) = probe.inserts.single()
        assertTrue(uri.toString().contains("images"))
        assertEquals(1, inserted.getAsInteger(MediaStore.MediaColumns.IS_PENDING))
        assertEquals("image/jpeg", inserted.getAsString(MediaStore.MediaColumns.MIME_TYPE))
        assertEquals("Pictures/Shroud", inserted.getAsString(MediaStore.MediaColumns.RELATIVE_PATH))
        assertEquals(displayName("jpg"), inserted.getAsString(MediaStore.MediaColumns.DISPLAY_NAME))
        assertEquals(0, probe.updates.single().second.getAsInteger(MediaStore.MediaColumns.IS_PENDING))
        assertArrayEquals(jpeg, probe.file(uri).readBytes())
        assertEquals(0, probe.deletes)
    }

    @Test
    fun saveToGalleryPublishesAPendingVideo() = runBlocking {
        val probe = gallery()
        val messageId = UUID.randomUUID()
        val outcome = sharing(mapOf(messageId to mp4())).saveToGallery(messageId)

        assertEquals(SaveOutcome.Saved, outcome)
        val (uri, inserted) = probe.inserts.single()
        assertTrue(uri.toString().contains("video"))
        assertEquals("video/mp4", inserted.getAsString(MediaStore.MediaColumns.MIME_TYPE))
        assertEquals("Movies/Shroud", inserted.getAsString(MediaStore.MediaColumns.RELATIVE_PATH))
        assertTrue(inserted.getAsString(MediaStore.MediaColumns.DISPLAY_NAME).endsWith(".mp4"))
        assertEquals(1, inserted.getAsInteger(MediaStore.MediaColumns.IS_PENDING))
        assertEquals(0, probe.updates.single().second.getAsInteger(MediaStore.MediaColumns.IS_PENDING))
    }

    @Test
    fun saveFailureIsASentenceAndDoesNotThrow() = runBlocking {
        val probe = gallery()
        val missing = sharing(emptyMap()).saveToGallery(UUID.randomUUID())
        assertEquals(SaveOutcome.Failed("Could not save that photo."), missing)
        assertTrue(probe.inserts.isEmpty())

        val broken = MemoryMediaSharing(app, { throw java.io.IOException("disk") }, clock)
            .saveToGallery(UUID.randomUUID())
        assertTrue(broken is SaveOutcome.Failed)
        assertTrue((broken as SaveOutcome.Failed).message.isNotBlank())
    }

    @Test
    fun haltWritersDoesNotBuildSharingJustToRevoke() {
        val container = AppContainer(app, AppPhaseMonitor(tracks = { false }))
        container.wipeHooks.haltWriters()
        assertNull(container.media.sharingIfBuilt)
    }

    private fun sharing(loaded: Map<UUID, ByteArray>) =
        MemoryMediaSharing(app, { id -> loaded[id] }, clock)

    private fun provider(): DecryptedMediaProvider {
        val provider = DecryptedMediaProvider()
        provider.attachInfo(
            app,
            ProviderInfo().apply {
                authority = "${app.packageName}.media"
                grantUriPermissions = true
                exported = false
            },
        )
        return provider
    }

    private fun gallery(): GalleryProbe {
        val probe = GalleryProbe()
        probe.attachInfo(app, ProviderInfo().apply { authority = "media"; exported = false })
        ShadowContentResolver.registerProviderInternal("media", probe)
        return probe
    }

    private fun displayName(extension: String): String {
        val local = clock.now().atZone(ZoneId.systemDefault()).toLocalDateTime()
        val stamp = DateTimeFormatter.ofPattern("yyyy-MM-dd HH.mm.ss", Locale.US).format(local)
        return "Shroud $stamp.$extension"
    }

    private fun read(descriptor: ParcelFileDescriptor): ByteArray =
        ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { it.readBytes() }

    /** 20-byte `ftyp` / `isom`, enough for the video sniff. */
    private fun mp4(): ByteArray {
        val bytes = ByteArray(20)
        bytes[3] = 20
        "ftyp".toByteArray(Charsets.US_ASCII).copyInto(bytes, 4)
        "isom".toByteArray(Charsets.US_ASCII).copyInto(bytes, 8)
        return bytes
    }

    private class GalleryProbe : ContentProvider() {
        val inserts = ArrayList<Pair<Uri, ContentValues>>()
        val updates = ArrayList<Pair<Uri, ContentValues>>()
        var deletes = 0
        private val files = HashMap<String, File>()

        fun file(uri: Uri): File = files.getValue(uri.toString())

        override fun onCreate(): Boolean = true

        override fun insert(uri: Uri, values: ContentValues?): Uri {
            val stored = Uri.withAppendedPath(uri, (inserts.size + 1).toString())
            inserts += stored to ContentValues(values)
            return stored
        }

        override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int {
            updates += uri to ContentValues(values)
            return 1
        }

        override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int {
            deletes += 1
            return 1
        }

        override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
            val file = files.getOrPut(uri.toString()) {
                File(requireNotNull(context).cacheDir, "gallery-${uri.lastPathSegment}").apply { createNewFile() }
            }
            val access = if (mode.contains("w")) {
                ParcelFileDescriptor.MODE_READ_WRITE or ParcelFileDescriptor.MODE_CREATE or ParcelFileDescriptor.MODE_TRUNCATE
            } else {
                ParcelFileDescriptor.MODE_READ_ONLY
            }
            return ParcelFileDescriptor.open(file, access)
        }

        override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null

        override fun getType(uri: Uri): String? = null
    }
}
