package de.corespace.shroud.ui.media

import android.Manifest
import android.content.ContentResolver
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.provider.MediaStore
import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.core.content.FileProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import de.corespace.shroud.ShroudApplication
import de.corespace.shroud.core.crypto.CryptoError
import de.corespace.shroud.core.media.ImageEncodeException
import de.corespace.shroud.core.media.MediaImageSource
import de.corespace.shroud.core.media.capture.CameraCapture
import de.corespace.shroud.core.media.capture.CameraXSession
import de.corespace.shroud.core.media.capture.ShroudCameraCapture
import de.corespace.shroud.core.media.share.MemoryMediaSharing
import de.corespace.shroud.core.media.share.SaveOutcome
import de.corespace.shroud.ui.camera.CameraCaptureContent
import de.corespace.shroud.ui.camera.CameraServices
import de.corespace.shroud.ui.media.viewer.MediaImageViewerContent
import de.corespace.shroud.ui.media.viewer.ViewerServices
import de.corespace.shroud.ui.theme.ShroudTheme
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileNotFoundException
import java.util.UUID

/**
 * C13 device checks (W3-MEDIA-VIEW acceptance, plan §2.4: "captures never reach the gallery …
 * grants revoked on close … Save to Gallery via MediaStore IS_PENDING"), through the C13 screens:
 *
 * - the camera screen, on the emulator's camera, hands out a photo whose source is a
 *   `content://<app>.cache/…` URI of a `cacheDir/shroud-*` file, and MediaStore gains nothing;
 * - the photo viewer's Share hands out a grant that opens while the viewer is up and fails once it
 *   closes (`revokeAll`);
 * - the viewer's Save to Gallery adds exactly one MediaStore item and says "Saved to Gallery".
 *
 * Core's capture and sharing classes are the production ones (`CameraXSession`,
 * `ShroudCameraCapture` over the app's `SensitiveTempFiles` and FileProvider authority,
 * `MemoryMediaSharing` on the `.media` provider); only the chats-unlocked check and the bytes source
 * stand in for a signed-in, unlocked account. The production `media.camera` itself is checked to
 * refuse a capture while the chats are locked.
 */
@RunWith(AndroidJUnit4::class)
class MediaViewDeviceTest {
    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private val context: Context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val app get() = context.applicationContext as ShroudApplication
    private val resolver: ContentResolver get() = context.contentResolver
    private val inserted = ArrayList<Uri>()

    @Before
    fun grantCamera() {
        InstrumentationRegistry.getInstrumentation().uiAutomation.grantRuntimePermission(context.packageName, Manifest.permission.CAMERA)
    }

    @After
    fun removeTestRows() {
        // Only the rows this test added (its own inserts).
        inserted.forEach { runCatching { resolver.delete(it, null, null) } }
    }

    @Test
    fun theProductionCameraRefusesWhileTheChatsAreLocked() = runBlocking {
        val camera = app.container.media.camera
        assertTrue("this check needs a locked phone", !app.container.keys.cryptoController.isUnlocked)
        val refused = runCatching { camera.takePhoto() }.exceptionOrNull()
        assertTrue("got $refused", refused is CryptoError || refused is ImageEncodeException)
    }

    @Test
    fun aCapturedPhotoIsACacheContentUriAndNeverReachesMediaStore() {
        val startSeconds = System.currentTimeMillis() / 1000 - 1
        val before = ownImagesSince(startSeconds)
        val capture = productionCapture()
        var photo: PickedPhoto? = null
        rule.setContent {
            ShroudTheme(dark = true) {
                CameraCaptureContent(
                    onPhoto = { photo = it },
                    onVideo = {},
                    onClose = {},
                    services = object : CameraServices {
                        override val camera: CameraCapture = capture
                        override suspend fun decodePreview(source: MediaImageSource, maxEdge: Int): Bitmap? =
                            app.container.images.mediaImages.decodePreview(source, maxEdge)
                    },
                )
            }
        }
        rule.waitUntil(15_000) {
            runCatching { rule.onNodeWithContentDescription("Take photo").assertIsEnabled() }.isSuccess
        }
        rule.onNodeWithContentDescription("Take photo").performClick()
        rule.waitUntil(20_000) { photo != null }

        val captured = requireNotNull(photo)
        val source = captured.source as MediaImageSource.ContentUri
        val uri = source.uri
        assertEquals(ContentResolver.SCHEME_CONTENT, uri.scheme)
        assertEquals("${context.packageName}.cache", uri.authority)
        val name = requireNotNull(uri.lastPathSegment)
        assertTrue(name, name.startsWith("shroud-cam-") && name.endsWith(".jpg"))
        val file = File(context.cacheDir, name)
        assertTrue("the capture is a cacheDir file", file.isFile && file.length() > 0)
        val head = ByteArray(2).also { buffer -> resolver.openInputStream(uri)!!.use { it.read(buffer) } }
        assertArrayEquals(byteArrayOf(0xFF.toByte(), 0xD8.toByte()), head)
        assertTrue(captured.preview.width > 0)
        // Nothing reached the gallery: no new image of ours, and no MediaStore row by that name.
        assertEquals(before, ownImagesSince(startSeconds))
        assertEquals(0, filesNamed(name))
        file.delete()
    }

    @Test
    fun aShareGrantOpensWhileTheViewerIsUpAndFailsOnceItCloses() {
        val bytes = jpeg()
        val id = UUID.randomUUID()
        val sharing = MemoryMediaSharing(context, load = { if (it == id) bytes else null }, clock = app.container.clock)
        val services = RealSharingServices(sharing, bytes)
        var open by mutableStateOf(true)
        rule.setContent {
            ShroudTheme(dark = true) {
                if (open) {
                    MediaImageViewerContent(
                        items = listOf(ViewerItem(id, "You", "02.10.26", null, 4f / 3f, isLoaded = true)),
                        initialId = id,
                        onClose = { open = false },
                        onLoad = null,
                        onDelete = null,
                        services = services,
                    )
                }
            }
        }
        rule.onNodeWithContentDescription("Share").performClick()
        rule.waitUntil(10_000) { services.shared.isNotEmpty() }
        val uri = services.shared.single()
        assertEquals("${context.packageName}.media", uri.authority)
        // The system share sheet is up over the viewer; the grant reads while the viewer stays.
        val read = resolver.openInputStream(uri)!!.use { it.readBytes() }
        assertArrayEquals(bytes, read)
        UiDevice.getInstance(InstrumentationRegistry.getInstrumentation()).pressBack()
        rule.waitForIdle()
        rule.runOnIdle { open = false }
        rule.waitForIdle()
        try {
            resolver.openInputStream(uri)?.close()
            fail("the grant must be revoked once the viewer closed")
        } catch (_: FileNotFoundException) {
            // revoked
        } catch (_: SecurityException) {
            // revoked
        }
    }

    @Test
    fun saveToGalleryAddsExactlyOneItem() {
        val bytes = jpeg()
        val id = UUID.randomUUID()
        val sharing = MemoryMediaSharing(context, load = { if (it == id) bytes else null }, clock = app.container.clock)
        val services = RealSharingServices(sharing, bytes)
        val before = ownShroudImages()
        rule.setContent {
            ShroudTheme(dark = true) {
                MediaImageViewerContent(
                    items = listOf(ViewerItem(id, "You", "02.10.26", null, 4f / 3f, isLoaded = true)),
                    initialId = id,
                    onClose = {},
                    onLoad = null,
                    onDelete = null,
                    services = services,
                )
            }
        }
        rule.onNodeWithContentDescription("More").performClick()
        rule.onNodeWithText("Save to Gallery").performClick()
        rule.waitUntil(10_000) { services.saves.isNotEmpty() }
        assertEquals(SaveOutcome.Saved, services.saves.single())
        rule.waitUntil(5_000) { runCatching { rule.onNodeWithText("Saved to Gallery").assertExists() }.isSuccess }
        val after = ownShroudImages()
        assertEquals(before.size + 1, after.size)
        inserted += after - before.toSet()
        val row = (after - before.toSet()).single()
        assertNotNull(resolver.openInputStream(row)?.use { it.readBytes() }?.takeIf { it.contentEquals(bytes) })
    }

    /** The viewer's port on the real in-memory sharing (K10); the bytes stand in for `mediaBytes`. */
    private class RealSharingServices(private val sharing: MemoryMediaSharing, private val bytes: ByteArray) : ViewerServices {
        val shared = ArrayList<Uri>()
        val saves = ArrayList<SaveOutcome>()

        override suspend fun mediaBytes(messageId: UUID): ByteArray = bytes

        override suspend fun decodePreview(source: MediaImageSource, maxEdge: Int): Bitmap? {
            val data = (source as MediaImageSource.FileBytes).bytes
            return android.graphics.BitmapFactory.decodeByteArray(data, 0, data.size)
        }

        override suspend fun shareUri(messageId: UUID): Uri? = sharing.shareUri(messageId)?.also { shared += it }

        override fun revokeShares() = sharing.revokeAll()

        override suspend fun saveToGallery(messageId: UUID): SaveOutcome = sharing.saveToGallery(messageId).also { saves += it }
    }

    /** `media.camera`'s wiring (`MediaModule`), with the chats-unlocked check standing in for an unlocked account. */
    private fun productionCapture(): CameraCapture = ShroudCameraCapture(
        temps = app.container.keys.sensitiveTempFiles,
        unlocked = { true },
        session = CameraXSession(context),
        audioGranted = { false },
        uriFor = { file -> FileProvider.getUriForFile(context, context.packageName + ".cache", file) },
    )

    private fun jpeg(): ByteArray {
        val bitmap = Bitmap.createBitmap(64, 48, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.rgb(51, 144, 236)) }
        return ByteArrayOutputStream().use { out ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, 90, out)
            out.toByteArray()
        }
    }

    /** Images this app owns that MediaStore added after [sinceSeconds]. */
    private fun ownImagesSince(sinceSeconds: Long): Int =
        resolver.query(
            MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL),
            arrayOf(MediaStore.MediaColumns._ID),
            "${MediaStore.MediaColumns.DATE_ADDED} >= ? AND ${MediaStore.MediaColumns.OWNER_PACKAGE_NAME} = ?",
            arrayOf(sinceSeconds.toString(), context.packageName),
            null,
        )?.use { it.count } ?: 0

    /** MediaStore rows (any kind) with this display name. */
    private fun filesNamed(name: String): Int =
        resolver.query(
            MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL),
            arrayOf(MediaStore.MediaColumns._ID),
            "${MediaStore.MediaColumns.DISPLAY_NAME} = ?",
            arrayOf(name),
            null,
        )?.use { it.count } ?: 0

    /** This app's images under `Pictures/Shroud`. */
    private fun ownShroudImages(): List<Uri> {
        val collection = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
        val rows = ArrayList<Uri>()
        resolver.query(
            collection,
            arrayOf(MediaStore.MediaColumns._ID),
            "${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ? AND ${MediaStore.MediaColumns.OWNER_PACKAGE_NAME} = ?",
            arrayOf("Pictures/Shroud%", context.packageName),
            null,
        )?.use { cursor ->
            while (cursor.moveToNext()) rows += Uri.withAppendedPath(collection, cursor.getLong(0).toString())
        }
        return rows
    }
}
