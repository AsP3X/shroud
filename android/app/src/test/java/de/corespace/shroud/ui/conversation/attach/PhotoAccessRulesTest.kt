package de.corespace.shroud.ui.conversation.attach

import android.Manifest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Recents strip's permission states on Android (conversation-compose-media §7.4; P9: images
 * only, `READ_MEDIA_IMAGES` + `READ_MEDIA_VISUAL_USER_SELECTED`, `READ_EXTERNAL_STORAGE` up to 32).
 */
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
    fun `full access lists every image`() {
        assertEquals(PhotoAccess.Full, PhotoAccessRules.access(30, { it == storage }, requestedBefore = true))
        assertEquals(PhotoAccess.Full, PhotoAccessRules.access(33, { it == images }, requestedBefore = false))
        assertEquals(PhotoAccess.Full, PhotoAccessRules.access(35, { it == images || it == selected }, requestedBefore = true))
    }

    @Test
    fun `Android 14 'Select photos' is partial access (design Sy9qO)`() {
        assertEquals(PhotoAccess.Partial, PhotoAccessRules.access(34, { it == selected }, requestedBefore = true))
        // The partial grant does not exist below 34.
        assertEquals(PhotoAccess.Denied, PhotoAccessRules.access(33, { it == selected }, requestedBefore = true))
    }

    @Test
    fun `nothing granted - asked before reads as denied, else it is asked when the sheet opens`() {
        assertEquals(PhotoAccess.Denied, PhotoAccessRules.access(34, { false }, requestedBefore = true))
        assertEquals(PhotoAccess.NotAsked, PhotoAccessRules.access(34, { false }, requestedBefore = false))
        // The storage grant means nothing on 33+.
        assertEquals(PhotoAccess.NotAsked, PhotoAccessRules.access(33, { it == storage }, requestedBefore = false))
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
