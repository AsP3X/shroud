package de.corespace.shroud.core.media.library

import android.app.Application
import android.graphics.Bitmap
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Library access by API level, and an empty recents list when the query fails.
 * The API-level matrix is the same function [MediaStorePhotoLibrary.access] uses; SDK 35 is this process.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], application = Application::class)
class PhotoLibraryTest {
    private lateinit var app: Application

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
    }

    @Test
    fun accessFollowsTheApiLevel() {
        val lib = library()
        val images = MediaStorePhotoLibrary.READ_MEDIA_IMAGES
        val selected = MediaStorePhotoLibrary.READ_MEDIA_VISUAL_USER_SELECTED
        val storage = MediaStorePhotoLibrary.READ_EXTERNAL_STORAGE
        fun granted(vararg names: String): (String) -> Boolean = { it in names }

        assertEquals(LibraryAccess.Full, lib.access(34, granted(images)))
        assertEquals(LibraryAccess.Full, lib.access(37, granted(images, selected)))
        assertEquals(LibraryAccess.Partial, lib.access(34, granted(selected)))
        assertEquals(LibraryAccess.None, lib.access(34, granted()))
        assertEquals(LibraryAccess.None, lib.access(34, granted(storage)))

        assertEquals(LibraryAccess.Full, lib.access(33, granted(images)))
        assertEquals(LibraryAccess.None, lib.access(33, granted(selected)))
        assertEquals(LibraryAccess.None, lib.access(33, granted(storage)))

        assertEquals(LibraryAccess.Full, lib.access(32, granted(storage)))
        assertEquals(LibraryAccess.Full, lib.access(30, granted(storage)))
        assertEquals(LibraryAccess.None, lib.access(32, granted(images)))
        assertEquals(LibraryAccess.None, lib.access(30, granted()))
    }

    @Test
    fun accessOnThisSdkFollowsTheGrantedPermissions() {
        val lib = MediaStorePhotoLibrary(app)
        assertEquals(LibraryAccess.None, lib.access())
        shadowOf(app).grantPermissions(MediaStorePhotoLibrary.READ_MEDIA_VISUAL_USER_SELECTED)
        assertEquals(LibraryAccess.Partial, lib.access())
        shadowOf(app).grantPermissions(MediaStorePhotoLibrary.READ_MEDIA_IMAGES)
        assertEquals(LibraryAccess.Full, lib.access())
    }

    @Test
    fun recentIsEmptyWhenAccessIsNoneOrTheQueryFails() = runTest {
        var calls = 0
        val denied = library(images = {
            calls++
            throw SecurityException("denied")
        })
        assertEquals(emptyList<LibraryItem>(), denied.recent(12))
        assertEquals(emptyList<LibraryItem>(), denied.recent(0))
        assertEquals(0, calls)

        shadowOf(app).grantPermissions(MediaStorePhotoLibrary.READ_MEDIA_IMAGES)
        val failing = library(images = { throw SecurityException("denied") })
        assertEquals(LibraryAccess.Full, failing.access())
        assertEquals(emptyList<LibraryItem>(), failing.recent(12))

        val item = LibraryItem(Uri.parse("content://media/external/images/media/7"), false, null, null)
        val ok = library(images = { listOf(item) })
        assertEquals(listOf(item), ok.recent(4))
        assertFalse(ok.recent(4).single().isVideo)
    }

    @Test
    fun thumbnailIsNullWhenItCannotBeDecoded() = runTest {
        shadowOf(app).grantPermissions(MediaStorePhotoLibrary.READ_MEDIA_IMAGES)
        val uri = Uri.parse("content://media/external/images/media/7")
        var calls = 0
        val lib = library(thumbs = { _, edge ->
            calls++
            if (edge < 32) throw IllegalArgumentException("edge")
            Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
        })
        assertNull(lib.thumbnail(uri, 0))
        assertEquals(0, calls)
        assertNull(lib.thumbnail(uri, 16))
        val decoded = lib.thumbnail(uri, 64)
        assertTrue(decoded != null && !decoded.isRecycled)
        val cancelled = library(thumbs = { _, _ -> throw CancellationException("stop") })
        val thrown = runCatching { cancelled.thumbnail(uri, 32) }.exceptionOrNull()
        assertTrue(thrown is CancellationException)
        assertEquals("stop", thrown!!.message)
    }

    private fun library(
        images: (Int) -> List<LibraryItem> = { emptyList() },
        thumbs: (Uri, Int) -> Bitmap? = { _, _ -> null },
    ) = MediaStorePhotoLibrary(app, images, thumbs, Dispatchers.Unconfined)
}
