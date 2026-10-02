package de.corespace.shroud.ui.media.viewer

import android.app.Application
import android.content.ClipDescription
import android.content.Intent
import android.net.Uri
import de.corespace.shroud.core.media.share.ShareTarget
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The share sheet and clipboard over a K10 grant (conversation-compose-media §18.5): `ACTION_SEND`
 * with the stream, its type and a read grant that survives the chooser; the clip marked sensitive
 * on Android 13+ only; "Copied" only where Android does not confirm a copy itself.
 */
@RunWith(RobolectricTestRunner::class)
// A plain Application: these screens run on fakes, and ShroudApplication would start the whole
// container (network, push) for every test, which piles up in the one test JVM.
@Config(sdk = [35], application = Application::class)
class MediaShareIntentsTest {
    private val uri = Uri.parse("content://de.corespace.shroud.media/0b6f")
    private val target = ShareTarget(uri, "image/heic")

    @Test
    fun sendCarriesTheStreamTypeAndGrant() {
        val send = MediaShareIntents.send(target)
        assertEquals(Intent.ACTION_SEND, send.action)
        // K10's stored type (gap #15).
        assertEquals("image/heic", send.type)
        assertEquals("video/mp4", MediaShareIntents.send(ShareTarget(uri, "video/mp4")).type)
        assertEquals(uri, send.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java))
        assertEquals(uri, send.clipData?.getItemAt(0)?.uri)
        assertTrue(send.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
        assertFalse(send.flags and Intent.FLAG_GRANT_WRITE_URI_PERMISSION != 0)
        val chooser = MediaShareIntents.chooser(send)
        assertEquals(Intent.ACTION_CHOOSER, chooser.action)
        assertTrue(chooser.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
    }

    @Test
    fun theClipIsSensitiveFromAndroid13() {
        val modern = MediaShareIntents.clip(target, sdk = 33)
        assertEquals("Photo", modern.description.label)
        assertEquals(uri, modern.getItemAt(0).uri)
        assertEquals(1, modern.description.mimeTypeCount)
        assertEquals("image/heic", modern.description.getMimeType(0))
        assertTrue(modern.description.extras!!.getBoolean(ClipDescription.EXTRA_IS_SENSITIVE))
        val older = MediaShareIntents.clip(target, sdk = 30)
        assertEquals("image/heic", older.description.getMimeType(0))
        assertNull(older.description.extras)
        assertTrue(MediaShareIntents.showsCopiedBanner(32))
        assertFalse(MediaShareIntents.showsCopiedBanner(33))
    }
}
