package de.corespace.shroud.core.media

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.net.Uri
import de.corespace.shroud.core.crypto.MediaCrypto
import de.corespace.shroud.core.media.edit.MediaEdits
import de.corespace.shroud.core.media.scrub.ImageContainer
import de.corespace.shroud.core.media.scrub.ImageHeader
import de.corespace.shroud.core.media.scrub.JpegScrubber
import de.corespace.shroud.core.media.scrub.MediaFixtures
import de.corespace.shroud.core.media.scrub.MediaMetadataScrubber
import java.io.ByteArrayOutputStream
import java.util.Random
import javax.imageio.ImageIO
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The photo encoder (`MediaCrypto.encode`, `MediaCrypto.swift:80-107`; `sendImage`'s edit baking,
 * `MessagingController.swift:2403-2427`) on Robolectric's native graphics (real Skia `ImageDecoder`
 * and JPEG encoder; HEIC decoding is device-only, so HEIC is only exercised on the passthrough path).
 * Ports `originalSendCarriesNoMetadata` and `reencodedSendCarriesNoMetadata`
 * (`ios/shroudTests/MediaMetadataScrubberTests.swift:80-101`).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ImageEncoderTest {
    private val images = MediaImages(RuntimeEnvironment.getApplication().contentResolver)
    private val encoder = ImageEncoder(images, cpu = Dispatchers.Unconfined, io = Dispatchers.Unconfined)

    private fun fixture(name: String) = MediaFixtures.resource("media/imageio/$name")

    private fun bytes(image: EncodedImage) = image.data.toByteArray()

    /** `originalSendCarriesNoMetadata` (`:80-91`). */
    @Test
    fun originalSendCarriesNoMetadata() = runBlocking {
        val encoded = encoder.encode(MediaImageSource.FileBytes(fixture("tagged.heic")), MediaComposeQuality.Original, MediaEdits.Identity)

        assertEquals("image/heic", encoded.mime)
        assertEquals(emptyList<String>(), MediaMetadataScrubber.leftoverMetadata(bytes(encoded)))
        // Orientation 6 swaps the axes for the recipient.
        assertEquals(48, encoded.width)
        assertEquals(64, encoded.height)
    }

    /** `reencodedSendCarriesNoMetadata` (`:93-101`): maxEdge 32, compression 0.8, no passthrough. */
    @Test
    fun reencodedSendCarriesNoMetadata() = runBlocking {
        val encoded = encoder.encode(MediaImageSource.FileBytes(fixture("tagged.jpg")), maxEdge = 32, jpegQuality = 80, allowsPassthrough = false)

        assertEquals(emptyList<String>(), MediaMetadataScrubber.leftoverMetadata(bytes(encoded)))
        assertEquals(emptyList<String>(), MediaFixtures.survivingSecrets(bytes(encoded)))
        assertEquals("image/jpeg", encoded.mime)
        // Upright (orientation baked in) and the long edge on the cap.
        assertEquals(24 to 32, encoded.width to encoded.height)
        assertEquals(24 to 32, ImageHeader.pixelSize(bytes(encoded)))
    }

    @Test
    fun originalJpegKeepsItsCodedPixels() = runBlocking {
        val original = fixture("tagged.jpg")
        val encoded = encoder.encode(MediaImageSource.FileBytes(original), MediaComposeQuality.Original, MediaEdits.Identity)

        assertEquals("image/jpeg", encoded.mime)
        assertEquals(48 to 64, encoded.width to encoded.height)
        assertFalse("never the original bytes", original.contentEquals(bytes(encoded)))
        assertArrayEquals("same scans", scans(original), scans(bytes(encoded)))
        assertEquals(emptyList<String>(), MediaFixtures.survivingSecrets(bytes(encoded)))
    }

    @Test
    fun originalPngAndGainMapJpegPassThrough() = runBlocking {
        for ((name, mime) in listOf("tagged.png" to "image/png", "hdr-gainmap.jpg" to "image/jpeg", "animated.png" to "image/png")) {
            val encoded = encoder.encode(MediaImageSource.FileBytes(fixture(name)), MediaComposeQuality.Original, MediaEdits.Identity)
            assertEquals(name, mime, encoded.mime)
            assertEquals(name, emptyList<String>(), MediaMetadataScrubber.leftoverMetadata(bytes(encoded)))
        }
    }

    @Test
    fun hdReencodesAndCapsTheLongEdge() = runBlocking {
        val big = MediaFixtures.jpeg(3000, 2000)
        val encoded = encoder.encode(MediaImageSource.FileBytes(big), MediaComposeQuality.HD, MediaEdits.Identity)

        assertEquals("image/jpeg", encoded.mime)
        assertEquals("2000 × 2560 / 3000, rounded down", 2560 to 1706, encoded.width to encoded.height)
        val decoded = BitmapFactory.decodeByteArray(bytes(encoded), 0, encoded.data.size)
        assertEquals(2560, decoded.width)
        assertEquals(1706, decoded.height)
    }

    @Test
    fun formatsOutsideThePassthroughListAreReencoded() = runBlocking {
        val gif = ByteArrayOutputStream().also { ImageIO.write(MediaFixtures.picture(40, 30), "gif", it) }.toByteArray()
        assertEquals(ImageContainer.Gif, ImageHeader.container(gif))

        val encoded = encoder.encode(MediaImageSource.FileBytes(gif), MediaComposeQuality.Original, MediaEdits.Identity)

        assertEquals("image/jpeg", encoded.mime)
        assertEquals(40 to 30, encoded.width to encoded.height)
    }

    /** "Unknown layout → re-encode, never the original" (W2-MEDIA-IMAGE card). */
    @Test
    fun anOriginalThatCannotBeProvenCleanIsReencoded() = runBlocking {
        val base = MediaFixtures.jpeg(40, 30)
        val unprovable = MediaFixtures.withSegments(
            base,
            MediaFixtures.segment(0xE1, MediaFixtures.ascii("Exif\u0000\u0000garbage Test Phone")),
            MediaFixtures.segment(0xFE, MediaFixtures.ascii("SERIAL-0042")),
        )
        assertEquals(null, MediaMetadataScrubber.scrubImage(unprovable))

        val encoded = encoder.encode(MediaImageSource.FileBytes(unprovable), MediaComposeQuality.Original, MediaEdits.Identity)

        assertEquals("image/jpeg", encoded.mime)
        assertEquals(emptyList<String>(), MediaFixtures.survivingSecrets(bytes(encoded)))
        assertEquals(emptyList<String>(), MediaMetadataScrubber.leftoverMetadata(bytes(encoded)))
        assertEquals(40 to 30, encoded.width to encoded.height)
    }

    @Test
    fun aCameraBitmapIsScaledAndLeftToItsOwner() = runBlocking {
        val capture = Bitmap.createBitmap(4000, 3000, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.rgb(20, 120, 220)) }

        val encoded = encoder.encode(MediaImageSource.Decoded(capture), MediaComposeQuality.HD, MediaEdits.Identity)

        assertEquals(2560 to 1920, encoded.width to encoded.height)
        assertFalse("the caller's bitmap is not recycled", capture.isRecycled)
        val original = encoder.encode(MediaImageSource.Decoded(capture), MediaComposeQuality.Original, MediaEdits.Identity)
        assertEquals("a capture never passes through", 4000 to 3000, original.width to original.height)
        assertEquals("image/jpeg", original.mime)
    }

    @Test
    fun editsAreBakedFromACappedDecodeAndNeverPassThrough() = runBlocking {
        val codec = FakeCodec()
        val baked = ArrayList<Pair<Int, Int>>()
        val baker = MediaEditBaker { image, _ ->
            baked += image.width to image.height
            Bitmap.createBitmap(image.height, image.width, Bitmap.Config.ARGB_8888) // a quarter turn
        }
        val encoder = ImageEncoder(codec, editBaker = { baker }, cpu = Dispatchers.Unconfined, io = Dispatchers.Unconfined)

        val encoded = encoder.encode(MediaImageSource.ContentUri(PICKED), MediaComposeQuality.Original, MediaEdits(rotationQuarters = 1))

        assertEquals("no read for passthrough", 0, codec.reads)
        assertEquals(listOf(ImageEncoder.EDITED_DECODE_CAP), codec.decodeEdges)
        assertEquals(listOf(80 to 60), baked)
        assertEquals(60 to 80, encoded.width to encoded.height)
        assertEquals("image/jpeg", encoded.mime)
        assertEquals(listOf(100), codec.qualities)
    }

    @Test
    fun runningOutOfMemoryRetriesAtHalfTheEdge() = runBlocking {
        val codec = FakeCodec(oomAbove = 2048)
        val encoder = ImageEncoder(codec, cpu = Dispatchers.Unconfined, io = Dispatchers.Unconfined)

        val encoded = encoder.encode(MediaImageSource.ContentUri(PICKED), MediaComposeQuality.HD, MediaEdits.Identity)

        assertEquals(listOf(2560, 1280), codec.decodeEdges)
        assertEquals(80 to 60, encoded.width to encoded.height)

        val hopeless = FakeCodec(oomAbove = 0)
        try {
            ImageEncoder(hopeless, cpu = Dispatchers.Unconfined, io = Dispatchers.Unconfined)
                .encode(MediaImageSource.ContentUri(PICKED), MediaComposeQuality.Original, MediaEdits(mirrored = true))
            fail("expected ImageEncodeException")
        } catch (_: ImageEncodeException) {
        }
        assertEquals(listOf(8192, 4096, 2048, 1024, 512), hopeless.decodeEdges)
    }

    @Test
    fun aPickedPhotoIsReadForPassthroughOnlyWhenItFitsInMemory() = runBlocking {
        val small = FakeCodec(original = fixture("tagged.jpg"))
        val passed = ImageEncoder(small, cpu = Dispatchers.Unconfined, io = Dispatchers.Unconfined)
            .encode(MediaImageSource.ContentUri(PICKED), MediaComposeQuality.Original, MediaEdits.Identity)
        assertEquals(1, small.reads)
        assertEquals(ImageEncoder.IN_MEMORY_ORIGINAL_LIMIT, small.readLimit)
        assertEquals(emptyList<Int>(), small.decodeEdges)
        assertEquals("image/jpeg", passed.mime)
        assertEquals(48 to 64, passed.width to passed.height)

        val large = FakeCodec(original = null)
        ImageEncoder(large, cpu = Dispatchers.Unconfined, io = Dispatchers.Unconfined)
            .encode(MediaImageSource.ContentUri(PICKED), MediaComposeQuality.Original, MediaEdits.Identity)
        assertEquals(1, large.reads)
        assertEquals("decoded from the URI instead", listOf(16_384), large.decodeEdges)
        assertTrue(large.decodedSources.single() is MediaImageSource.ContentUri)

        val hd = FakeCodec(original = fixture("tagged.jpg"))
        ImageEncoder(hd, cpu = Dispatchers.Unconfined, io = Dispatchers.Unconfined)
            .encode(MediaImageSource.ContentUri(PICKED), MediaComposeQuality.HD, MediaEdits.Identity)
        assertEquals("HD never reads the original", 0, hd.reads)
        assertEquals(listOf(85), hd.qualities)
    }

    /** `chatPreviewJPEG` (`MediaCrypto.swift:151-171`): the ladder's edges and qualities. */
    @Test
    fun previewLadderStepsLikeIos() {
        val tries = ArrayList<Pair<Int, Int>>()
        val result = ImageEncoder.previewLadder { edge, quality ->
            tries += edge to quality
            ByteArray(7000)
        }
        assertEquals(listOf(160 to 42, 112 to 34, 80 to 26, 80 to 18, 80 to 15, 80 to 15), tries)
        assertEquals("last resort, even if over", 7000, result?.size)

        tries.clear()
        val second = ImageEncoder.previewLadder { edge, quality ->
            tries += edge to quality
            ByteArray(if (edge == 112) MediaCrypto.MAX_ENVELOPE_PREVIEW_BYTES else 9000)
        }
        assertEquals(listOf(160 to 42, 112 to 34), tries)
        assertEquals(MediaCrypto.MAX_ENVELOPE_PREVIEW_BYTES, second?.size)
        assertEquals(null, ImageEncoder.previewLadder { _, _ -> null })
    }

    @Test
    fun chatPreviewFitsTheEnvelope() {
        val noisy = noisyJpeg(1200, 900)
        val preview = requireNotNull(encoder.chatPreviewJpeg(noisy))
        assertTrue("${preview.size} bytes", preview.size <= MediaCrypto.MAX_ENVELOPE_PREVIEW_BYTES)
        val decoded = BitmapFactory.decodeByteArray(preview, 0, preview.size)
        assertTrue(maxOf(decoded.width, decoded.height) <= 160)
        assertEquals(null, encoder.chatPreviewJpeg(byteArrayOf(1, 2, 3)))
        // Orientation is baked into the preview.
        val small = requireNotNull(encoder.chatPreviewJpeg(fixture("tagged.jpg")))
        val upright = BitmapFactory.decodeByteArray(small, 0, small.size)
        assertEquals("never scaled up", 48 to 64, upright.width to upright.height)
    }

    @Test
    fun previewsAreDecodedAtTheirTargetSize() = runBlocking {
        val big = MediaFixtures.jpeg(3000, 2000)
        val preview = requireNotNull(images.decodePreview(MediaImageSource.FileBytes(big), 2048))
        assertEquals(2048 to 1365, preview.width to preview.height)
        val turned = requireNotNull(images.decodePreview(MediaImageSource.FileBytes(fixture("tagged.jpg")), 32))
        assertEquals(24 to 32, turned.width to turned.height)
        assertEquals(null, images.decodePreview(MediaImageSource.FileBytes(byteArrayOf(9, 9, 9)), 100))
    }

    @Test
    fun sizesAndTypesComeFromTheHeader() {
        assertEquals(48 to 64, encoder.pixelSize(fixture("tagged.heic")))
        assertEquals("image/heic", encoder.mimeType(fixture("tagged.heic")))
        assertEquals("image/png", encoder.mimeType(fixture("tagged.png")))
        assertEquals("image/jpeg", encoder.mimeType(byteArrayOf(0, 0, 0)))
        val bmp = ByteArrayOutputStream().also { ImageIO.write(MediaFixtures.picture(12, 7), "bmp", it) }.toByteArray()
        assertEquals("platform fallback for formats the header reader skips", 12 to 7, encoder.pixelSize(bmp))
    }

    @Test
    fun fitKeepsTheLongEdgeOnTheCap() {
        assertEquals(2560 to 1920, MediaImages.fit(4032, 3024, 2560))
        assertEquals(1920 to 2560, MediaImages.fit(3024, 4032, 2560))
        assertEquals(100 to 50, MediaImages.fit(100, 50, 2560))
        assertEquals(160 to 1, MediaImages.fit(10_000, 10, 160))
        assertEquals(16_384 to 8_192, MediaImages.fit(20_000, 10_000, 16_384))
        val bitmap = Bitmap.createBitmap(10, 10, Bitmap.Config.ARGB_8888)
        assertSame(bitmap, MediaImages.scaledToFit(bitmap, 10))
    }

    private fun scans(jpeg: ByteArray): ByteArray {
        val layout = JpegScrubber.parse(jpeg, 0, jpeg.size)!!
        val sos = layout.segments.first { it.marker == 0xDA }
        return jpeg.copyOfRange(sos.start, layout.eoiEnd)
    }

    private fun noisyJpeg(width: Int, height: Int): ByteArray {
        val random = Random(42)
        val image = java.awt.image.BufferedImage(width, height, java.awt.image.BufferedImage.TYPE_INT_RGB)
        for (y in 0 until height) for (x in 0 until width) image.setRGB(x, y, random.nextInt(0xFFFFFF))
        return ByteArrayOutputStream().also { ImageIO.write(image, "jpeg", it) }.toByteArray()
    }

    /** Records what the encoder asks of the platform; decodes to an 80×60 bitmap scaled to the edge. */
    private class FakeCodec(private val oomAbove: Int = Int.MAX_VALUE, private val original: ByteArray? = null) : ImageCodec {
        val decodeEdges = ArrayList<Int>()
        val decodedSources = ArrayList<MediaImageSource>()
        val qualities = ArrayList<Int>()
        var reads = 0
        var readLimit = 0L

        override fun decode(source: MediaImageSource, maxEdge: Int): Bitmap {
            decodeEdges += maxEdge
            decodedSources += source
            if (maxEdge > oomAbove) throw OutOfMemoryError("fake")
            val (width, height) = MediaImages.fit(80, 60, maxEdge)
            return Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        }

        override fun compressJpeg(bitmap: Bitmap, quality: Int): ByteArray {
            qualities += quality
            return ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.JPEG, quality, it) }.toByteArray()
        }

        override fun readOriginal(uri: Uri, limit: Long): ByteArray? {
            reads++
            readLimit = limit
            return original
        }

        override fun boundsSize(data: ByteArray): Pair<Int, Int>? = null
    }

    private companion object {
        val PICKED: Uri = Uri.parse("content://media/picker/0/photo/1")
    }
}
