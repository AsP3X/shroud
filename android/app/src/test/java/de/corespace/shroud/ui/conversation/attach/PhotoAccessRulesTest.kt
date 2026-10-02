package de.corespace.shroud.ui.conversation.attach

import android.Manifest
import android.app.Application
import android.graphics.Bitmap
import android.net.Uri
import de.corespace.shroud.core.media.library.LibraryAccess
import de.corespace.shroud.core.media.library.LibraryItem
import de.corespace.shroud.ui.conversation.composer.ContainerComposeServices
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The Recents strip's permission states on Android (conversation-compose-media §7.4; P9: images
 * only, `READ_MEDIA_IMAGES` + `READ_MEDIA_VISUAL_USER_SELECTED`, `READ_EXTERNAL_STORAGE` up to 32).
 */
@RunWith(RobolectricTestRunner::class)
// A plain Application: these tests run on fakes; ShroudApplication would start the whole
// container for every test, which piles up in the one test JVM.
@Config(sdk = [35], application = Application::class)
class PhotoAccessRulesTest {
    private val images = Manifest.permission.READ_MEDIA_IMAGES
    private val selected = Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED
    private val storage = Manifest.permission.READ_EXTERNAL_STORAGE

    @Test
    fun `what the sheet asks for per API level, never video or location`() {
        assertArrayEquals(arrayOf(storage), PhotoAccessRules.permissions(30))
        assertArrayEquals(arrayOf(storage), PhotoAccessRules.permissions(32))
        assertArrayEquals(arrayOf(images), PhotoAccessRules.permissions(33))
        assertArrayEquals(arrayOf(images, selected), PhotoAccessRules.permissions(34))
        assertArrayEquals(arrayOf(images, selected), PhotoAccessRules.permissions(37))
        for (sdk in 30..37) {
            val asked = PhotoAccessRules.permissions(sdk).toList()
            assertFalse(Manifest.permission.READ_MEDIA_VIDEO in asked)
            assertFalse(Manifest.permission.ACCESS_MEDIA_LOCATION in asked)
        }
    }

    @Test
    fun `core's grant decides the strip, asked-before turns no access into denied (K4)`() {
        // `PhotoLibrary.access()` reads the permissions per API level (core); the sheet maps it.
        assertEquals(PhotoAccess.Full, PhotoAccessRules.access(LibraryAccess.Full, requestedBefore = true))
        assertEquals(PhotoAccess.Full, PhotoAccessRules.access(LibraryAccess.Full, requestedBefore = false))
        // Android 14 "Select photos" (design Sy9qO).
        assertEquals(PhotoAccess.Partial, PhotoAccessRules.access(LibraryAccess.Partial, requestedBefore = true))
        assertEquals(PhotoAccess.Denied, PhotoAccessRules.access(LibraryAccess.None, requestedBefore = true))
        assertEquals(PhotoAccess.NotAsked, PhotoAccessRules.access(LibraryAccess.None, requestedBefore = false))
    }

    @Test
    fun `the strip shows the twelve newest with thumbnails and skips one that has none`() = runTest {
        val uris = List(14) { Uri.parse("content://media/external/images/media/$it") }
        val items = uris.map { LibraryItem(it, isVideo = false, dateTaken = null, durationMs = null) }
        val asked = ArrayList<Int>()
        val edges = HashSet<Int>()
        val photos = RecentPhotos.load(
            recent = { limit -> asked += limit; items.take(limit) },
            thumbnail = { uri, edge ->
                edges += edge
                if (uri == uris[3]) null else Bitmap.createBitmap(edge, edge, Bitmap.Config.ARGB_8888)
            },
        )
        assertEquals(listOf(12), asked)
        assertEquals(setOf(192), edges)
        assertEquals(uris.take(12) - uris[3], photos.map { it.uri })
        assertTrue(RecentPhotos.load(recent = { emptyList() }, thumbnail = { _, _ -> null }).isEmpty())
    }

    @Test
    fun `the asked-once fact is a K5 UI flag`() {
        // K5 `keys.uiFlags`: keys are Claude's, prefixed "ui."; Log Out clears them with the other non-kept prefs.
        assertEquals("ui.photos.permissionRequested", ContainerComposeServices.PHOTO_ACCESS_REQUESTED_FLAG)
    }

    @Test
    fun `header and link per state (CAS 44-66, design Sy9qO)`() {
        assertEquals("RECENTS", PhotoAccessRules.header(PhotoAccess.Full))
        assertEquals("All Photos", PhotoAccessRules.linkTitle(PhotoAccess.Full))
        assertEquals("SELECTED PHOTOS", PhotoAccessRules.header(PhotoAccess.Partial))
        assertEquals("Manage", PhotoAccessRules.linkTitle(PhotoAccess.Partial))
        assertEquals("RECENTS", PhotoAccessRules.header(PhotoAccess.Denied))
        assertTrue(PhotoAccess.Partial.showsPhotos)
        assertFalse(PhotoAccess.Denied.showsPhotos)
        assertFalse(PhotoAccess.NotAsked.showsPhotos)
    }

    @Test
    fun `the strip reads the twelve newest images by date added (CAS 236-238)`() {
        assertEquals(12, RecentPhotos.LIMIT)
        assertEquals(192, RecentPhotos.THUMBNAIL_PX)
    }

    @Test
    fun `option rows, titles and colours (CAS 33-34, 328-384)`() {
        assertEquals(listOf("Camera", "Photos", "File", "Location"), ChatAttachOption.firstRow.map { it.title })
        assertEquals(listOf("Contact", "Music", "Gift", "Stickers"), ChatAttachOption.secondRow.map { it.title })
    }
}
