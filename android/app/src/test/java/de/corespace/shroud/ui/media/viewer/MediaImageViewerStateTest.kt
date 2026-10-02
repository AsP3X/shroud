package de.corespace.shroud.ui.media.viewer

import android.app.Application
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import de.corespace.shroud.core.media.MediaImageSource
import de.corespace.shroud.core.media.share.SaveOutcome
import de.corespace.shroud.core.media.share.ShareTarget
import de.corespace.shroud.ui.components.ComposeHarness
import de.corespace.shroud.ui.media.HarnessRule
import de.corespace.shroud.ui.media.ViewerItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.util.UUID

/**
 * The photo viewer's states and actions (`MediaImageViewerOverlay`,
 * `ios/shroud/ShroudUI/Components/MediaImageViewerOverlay.swift`; conversation-compose-media §18;
 * K10): the header and caption of the page on screen, the pager's TalkBack position, onLoad for a
 * page without bytes, delete with and without a handler, closing when the photo leaves the list,
 * Share as a granted `ACTION_SEND` chooser, Save's outcome as the banner, Copy as a sensitive clip,
 * and every grant revoked when the viewer leaves.
 */
@RunWith(RobolectricTestRunner::class)
// A plain Application: these screens run on fakes, and ShroudApplication would start the whole
// container (network, push) for every test, which piles up in the one test JVM.
@Config(sdk = [35], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MediaImageViewerStateTest {
    @get:Rule
    val harness = HarnessRule()

    private class FakeServices : ViewerServices {
        val bytes = mutableMapOf<UUID, ByteArray>()
        var share: ShareTarget? = ShareTarget(Uri.parse("content://de.corespace.shroud.media/0b6f"), "image/heic")
        var save: SaveOutcome = SaveOutcome.Saved
        var revoked = 0
        val shared = mutableListOf<UUID>()
        val saved = mutableListOf<UUID>()

        override suspend fun mediaBytes(messageId: UUID): ByteArray? = bytes[messageId]

        override suspend fun decodePreview(source: MediaImageSource, maxEdge: Int): Bitmap? = Bitmap.createBitmap(30, 40, Bitmap.Config.ARGB_8888)

        override suspend fun shareTarget(messageId: UUID): ShareTarget? {
            shared += messageId
            return share
        }

        override fun revokeShares() {
            revoked++
        }

        override suspend fun saveToGallery(messageId: UUID): SaveOutcome {
            saved += messageId
            return save
        }
    }

    private val items = listOf(
        ViewerItem(UUID.randomUUID(), "You", "30.09.26", null, 0.75f, isLoaded = true),
        ViewerItem(UUID.randomUUID(), "ann", "01.10.26", "Found this in the fridge aisle", 1.5f, isLoaded = true),
        ViewerItem(UUID.randomUUID(), "ann", "02.10.26", null, 1f, isLoaded = false),
    )

    private fun click(ui: ComposeHarness, description: String) {
        ui.node(description).config[SemanticsActions.OnClick].action!!.invoke()
        ui.idle()
    }

    @Test
    fun headerCaptionAndPagerPositionFollowThePageOnScreen() {
        val services = FakeServices().apply { items.forEach { bytes[it.id] = byteArrayOf(1) } }
        val ui = harness.compose {
            MediaImageViewerContent(items, items[1].id, onClose = {}, onLoad = null, onDelete = null, services = services)
        }
        assertTrue(ui.describe(), ui.nodesWithText("ann").isNotEmpty())
        assertTrue(ui.nodesWithText("01.10.26").isNotEmpty())
        assertTrue(ui.nodesWithText("Found this in the fridge aisle").isNotEmpty())
        val pager = ui.node("Photo")
        assertEquals("2 of 3", pager.config[SemanticsProperties.StateDescription])
        val actions = pager.config[SemanticsActions.CustomActions].map { it.label }
        assertEquals(listOf("Next photo", "Previous photo"), actions)
        // The chrome's controls, by their TalkBack names.
        listOf("Close", "More", "Share", "Draw", "Text recognition", "Delete").forEach { assertNotNull(ui.node(it)) }
    }

    @Test
    fun aPageWithoutBytesAsksTheHostToLoadIt() {
        val loads = mutableListOf<UUID>()
        val ui = harness.compose {
            MediaImageViewerContent(items, items[2].id, onClose = {}, onLoad = { loads += it }, onDelete = null, services = FakeServices())
        }
        ui.idle()
        assertTrue(loads.contains(items[2].id))
        assertTrue(loads.none { it == items[0].id })
    }

    @Test
    fun deleteGoesToTheHostOrSaysItIsComingSoon() {
        val deleted = mutableListOf<UUID>()
        val withHandler = harness.compose {
            MediaImageViewerContent(items, items[0].id, onClose = {}, onLoad = null, onDelete = { deleted += it }, services = FakeServices())
        }
        click(withHandler, "Delete")
        assertEquals(listOf(items[0].id), deleted)

        val without = harness.compose {
            MediaImageViewerContent(items, items[0].id, onClose = {}, onLoad = null, onDelete = null, services = FakeServices())
        }
        click(without, "Delete")
        assertTrue(without.describe(), without.nodesWithText("Delete coming soon").isNotEmpty())
        click(without, "Draw")
        assertTrue(without.nodesWithText("Drawing coming soon").isNotEmpty())
    }

    @Test
    fun thePhotoLeavingTheListClosesTheViewerAndClosingRevokesEveryGrant() {
        var shown by mutableStateOf(items)
        var open by mutableStateOf(true)
        var closes = 0
        val services = FakeServices()
        val ui = harness.compose {
            if (open) MediaImageViewerContent(shown, items[1].id, onClose = { closes++ }, onLoad = null, onDelete = null, services = services)
        }
        shown = items.filter { it.id != items[0].id } // another photo leaves: stays open
        ui.idle()
        assertEquals(0, closes)
        shown = items.filter { it.id != items[1].id } // the one on screen leaves
        ui.idle()
        assertEquals(1, closes)
        assertEquals(0, services.revoked)
        open = false
        ui.idle()
        assertEquals(1, services.revoked)
    }

    @Test
    fun shareOpensTheSystemSheetWithAReadGrant() {
        val services = FakeServices()
        val ui = harness.compose {
            MediaImageViewerContent(items, items[1].id, onClose = {}, onLoad = null, onDelete = null, services = services)
        }
        click(ui, "Share")
        assertEquals(listOf(items[1].id), services.shared)
        val chooser = shadowOf(ui.activity).nextStartedActivity
        assertEquals(Intent.ACTION_CHOOSER, chooser.action)
        assertTrue(chooser.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
        val send = requireNotNull(chooser.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java))
        assertEquals(Intent.ACTION_SEND, send.action)
        // The type K10 stored with the grant (gap #15), not a ContentResolver lookup.
        assertEquals("image/heic", send.type)
        assertEquals(services.share?.uri, send.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java))
        assertTrue(send.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
        assertEquals(services.share?.uri, send.clipData?.getItemAt(0)?.uri)
    }

    @Test
    fun aPhotoThatCannotBeSharedSaysSo() {
        val services = FakeServices().apply { share = null }
        val ui = harness.compose {
            MediaImageViewerContent(items, items[1].id, onClose = {}, onLoad = null, onDelete = null, services = services)
        }
        click(ui, "Share")
        assertTrue(ui.nodesWithText("Could not share that photo.").isNotEmpty())
    }

    @Test
    fun moreMenuSavesToTheGalleryAndShowsTheOutcome() {
        val services = FakeServices().apply { save = SaveOutcome.Failed("Could not save that photo.") }
        val ui = harness.compose {
            MediaImageViewerContent(items, items[1].id, onClose = {}, onLoad = null, onDelete = null, services = services)
        }
        click(ui, "More")
        val rows = listOf("Save to Gallery", "Share", "Copy").map { title -> ui.nodesWithText(title).filter { SemanticsActions.OnClick in it.config } }
        assertTrue(ui.describe(), rows.all { it.size == 1 })
        rows[0].single().config[SemanticsActions.OnClick].action!!.invoke()
        ui.idle()
        assertEquals(listOf(items[1].id), services.saved)
        assertTrue(ui.describe(), ui.nodesWithText("Could not save that photo.").isNotEmpty())

        services.save = SaveOutcome.Saved
        click(ui, "More")
        ui.nodesWithText("Save to Gallery").single { SemanticsActions.OnClick in it.config }.config[SemanticsActions.OnClick].action!!.invoke()
        ui.idle()
        assertTrue(ui.nodesWithText("Saved to Gallery").isNotEmpty())
    }

    @Test
    fun copyPutsTheGrantOnTheClipboard() {
        val services = FakeServices()
        val ui = harness.compose {
            MediaImageViewerContent(items, items[1].id, onClose = {}, onLoad = null, onDelete = null, services = services)
        }
        click(ui, "More")
        ui.nodesWithText("Copy").single { SemanticsActions.OnClick in it.config }.config[SemanticsActions.OnClick].action!!.invoke()
        ui.idle()
        val clipboard = ui.activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = requireNotNull(clipboard.primaryClip)
        assertEquals(services.share?.uri, clip.getItemAt(0).uri)
        assertEquals(listOf("image/heic"), (0 until clip.description.mimeTypeCount).map { clip.description.getMimeType(it) })
        assertTrue(clip.description.extras?.getBoolean(android.content.ClipDescription.EXTRA_IS_SENSITIVE) == true)
        // API 35 confirms the copy itself: no second "Copied".
        assertTrue(ui.nodesWithText("Copied").isEmpty())
    }

    @Test
    fun aPhotoThatCannotBeCopiedSaysSo() {
        val services = FakeServices().apply { share = null }
        val ui = harness.compose {
            MediaImageViewerContent(items, items[1].id, onClose = {}, onLoad = null, onDelete = null, services = services)
        }
        click(ui, "More")
        ui.nodesWithText("Copy").single { SemanticsActions.OnClick in it.config }.config[SemanticsActions.OnClick].action!!.invoke()
        ui.idle()
        assertEquals(listOf(items[1].id), services.shared)
        assertTrue(ui.describe(), ui.nodesWithText("Could not copy that photo.").isNotEmpty())
        val clipboard = ui.activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        assertEquals(null, clipboard.primaryClip)
    }
}
