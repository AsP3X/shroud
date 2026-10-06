package de.corespace.shroud.core.calls.media

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import kotlin.math.abs

/**
 * The cut our camera goes out in: its shape, where it goes for faces, and how it moves
 * (docs/calls.md, "Framing and Center Stage"). The same cases as the web's
 * `framing.selftest.ts` and the iPhone's `CallFramingTests`, then Android's own: the cut in the
 * camera buffer's orientation, and the face detector's input and output.
 */
class CallFramingTest {
    private val uhd = FrameSize(3840, 2160)
    private val fhd = FrameSize(1920, 1080)
    private val phone = FrameSize(1179, 2556)

    private fun near(a: Double, b: Double, tolerance: Double = 0.5): Boolean = abs(a - b) <= tolerance

    private fun check(ok: Boolean, what: String) = assertTrue(what, ok)

    // ---- the output's shape ----

    @Test
    fun theOutputTakesTheirShapeAsLargeAsTheCameraAllows() {
        val tall = outputSize(uhd, phone)
        check(tall.width == 886 && tall.height == 1920, "a 4K camera seen on a phone: 886×1920 ($tall)")
        val small = outputSize(fhd, phone)
        check(small.width == 498 && small.height == 1080, "a 1080p camera can only give 498×1080 ($small)")
        assertEquals("without their view: the camera's own shape, 1080p at most", fhd, outputSize(uhd, null))
        assertEquals("never enlarged", FrameSize(1280, 720), outputSize(FrameSize(1280, 720), null))
        val portraitPhone = outputSize(FrameSize(1080, 1920), phone)
        check(portraitPhone.height == 1920 && portraitPhone.width == 886, "a phone held upright, seen on a phone")
        val sliver = outputSize(uhd, FrameSize(100, 1000))
        check(near(sliver.width.toDouble() / sliver.height, 0.4, 0.01), "no narrower than 0.4")
        val banner = outputSize(uhd, FrameSize(1000, 100))
        check(near(banner.width.toDouble() / banner.height, 2.5, 0.01), "no wider than 2.5")
        assertEquals("an empty view counts as none", 1920, outputSize(uhd, FrameSize(0, 0)).width)
        val odd = outputSize(FrameSize(1001, 999), null)
        check(odd.width % 2 == 0 && odd.height % 2 == 0, "even sides")
    }

    // ---- when a new view counts ----

    @Test
    fun onlyARealNewShapeCounts() {
        assertFalse("a few pixels are no new shape", shapeChanged(FrameSize(1179, 2556), FrameSize(1180, 2540)))
        assertTrue("a rotation is", shapeChanged(FrameSize(1179, 2556), FrameSize(2556, 1179)))
        check(shapeChanged(null, phone) && shapeChanged(phone, null) && !shapeChanged(null, null), "appearing and going away are")
    }

    // ---- where the cut goes ----

    @Test
    fun theCutFramesTheFacesInsideThePicture() {
        val output = outputSize(uhd, null)
        val base = targetCrop(uhd, output, null)
        check(base.x == 0.0 && base.y == 0.0 && base.width == 3840.0 && base.height == 2160.0, "no faces: the whole picture")
        val face = FrameRect(1770.0, 900.0, 300.0, 300.0)
        val cut = targetCrop(uhd, output, face)
        check(near(cut.height, 1000.0) && near(cut.width, 1000.0 * (16.0 / 9.0)), "head and shoulders ($cut)")
        check(near(cut.x + cut.width / 2, 1920.0) && near(cut.y, 1050.0 - 420.0), "the face in the middle, a little above it")
        val tiny = targetCrop(uhd, output, FrameRect(1900.0, 1000.0, 40.0, 40.0))
        check(near(tiny.height, 864.0), "never tighter than the output allows (${tiny.height})")
        val edge = targetCrop(uhd, output, FrameRect(3700.0, 2000.0, 140.0, 140.0))
        check(edge.x + edge.width <= 3840 + 1e-6 && edge.y + edge.height <= 2160 + 1e-6, "always inside the picture")
        val group = targetCrop(
            uhd,
            output,
            faceUnion(listOf(FrameRect(600.0, 900.0, 250.0, 250.0), FrameRect(2900.0, 950.0, 250.0, 250.0))),
        )
        check(near(group.width, 3840.0) || group.width >= 2550 / 0.6 - 1, "several people side by side all fit (${group.width})")
        assertNull("no faces, no union", faceUnion(emptyList()))
        val tall = outputSize(uhd, phone)
        val tallCut = targetCrop(uhd, tall, face)
        check(near(tallCut.width / tallCut.height, tall.width.toDouble() / tall.height, 0.002), "the cut keeps the output's shape")
        val inside = largestInside(uhd, 1.0)
        check(inside.width == 2160.0 && inside.x == 840.0, "the largest square in the middle")
    }

    // ---- how it moves ----

    @Test
    fun theCutGlidesHoldsAndLetsGo() {
        val output = outputSize(uhd, null)
        val framer = Framer()
        framer.configure(uhd, output, true)
        val start = framer.next(0)
        check(start.width == 3840.0, "it starts on the whole picture")
        val face = FrameRect(1770.0, 900.0, 300.0, 300.0)
        framer.faces(listOf(face), 0)
        var cut = framer.next(33)
        check(cut.height < 2160 && cut.height > 1500, "it glides rather than jumps (${cut.height})")
        var t = 66L
        while (t <= 5_000) {
            cut = framer.next(t)
            t += 33
        }
        check(near(cut.height, 1000.0, 2.0) && near(cut.x + cut.width / 2, 1920.0, 2.0), "and settles on the face")
        framer.faces(listOf(face.copy(x = face.x + 20)), 5_000)
        t = 5_033
        while (t <= 8_000) {
            cut = framer.next(t)
            t += 33
        }
        check(near(cut.x + cut.width / 2, 1920.0, 2.0), "a small move is not followed")
        framer.faces(listOf(face.copy(x = face.x + 600)), 8_000)
        t = 8_033
        while (t <= 12_000) {
            cut = framer.next(t)
            t += 33
        }
        check(near(cut.x + cut.width / 2, 2520.0, 2.0), "a real move is")
        framer.faces(listOf(face.copy(x = face.x + 600)), 11_800)
        framer.faces(emptyList(), 12_000)
        t = 12_033
        while (t <= 13_000) {
            cut = framer.next(t)
            t += 33
        }
        check(near(cut.height, 1000.0, 2.0), "a face lost for a moment holds the cut")
        framer.faces(emptyList(), 14_000)
        t = 14_033
        while (t <= 20_000) {
            cut = framer.next(t)
            t += 33
        }
        check(near(cut.height, 2160.0, 2.0), "gone for longer: back to the whole picture")
        framer.faces(listOf(face), 20_000)
        framer.configure(uhd, output, false)
        t = 20_033
        while (t <= 26_000) {
            cut = framer.next(t)
            t += 33
        }
        check(near(cut.height, 2160.0, 2.0), "Center Stage off: the whole picture")
        framer.configure(uhd, outputSize(uhd, phone), true)
        cut = framer.next(26_033)
        check(near(cut.width / cut.height, 886.0 / 1920.0, 0.002) && near(cut.height, 2160.0), "a new shape starts again from the whole picture")
        val late = framer.next(26_033 + 10_000)
        check(late.height <= 2160 && late.y >= 0, "a long pause between frames is not a jump past the target")
    }

    // ---- Android: the cut in the camera buffer ----

    @Test
    fun anUprightCutLandsOnTheSamePixelsOfTheBufferInEveryRotation() {
        // A 1920×1080 buffer; upright it is 1920×1080 (0°, 180°) or 1080×1920 (90°, 270°).
        val bw = 1920
        val bh = 1080
        val upright = FrameRect(100.0, 200.0, 300.0, 400.0)
        assertEquals(upright, uprightToBuffer(upright, 0, bw, bh))
        assertEquals(FrameRect(200.0, 1080.0 - 400.0, 400.0, 300.0), uprightToBuffer(upright, 90, bw, bh))
        assertEquals(FrameRect(1920.0 - 400.0, 1080.0 - 600.0, 300.0, 400.0), uprightToBuffer(upright, 180, bw, bh))
        assertEquals(FrameRect(1920.0 - 600.0, 100.0, 400.0, 300.0), uprightToBuffer(upright, 270, bw, bh))
        // The whole upright picture is the whole buffer, whichever way it turns.
        for (rotation in listOf(0, 90, 180, 270)) {
            val size = bufferOriented(FrameSize(bw, bh), rotation)
            val whole = FrameRect(0.0, 0.0, size.width.toDouble(), size.height.toDouble())
            assertEquals("rotation $rotation", FrameRect(0.0, 0.0, bw.toDouble(), bh.toDouble()), uprightToBuffer(whole, rotation, bw, bh))
        }
        // The same pixel, read both ways: the upright cut's corner pixel is the buffer's.
        for (rotation in listOf(0, 90, 180, 270)) {
            val pixels = pixelsOf(4, 2)
            val gray = uprightGray565(pixels, 4, 4, 2, rotation)
            val size = bufferOriented(FrameSize(4, 2), rotation)
            val cut = uprightToBuffer(FrameRect(0.0, 0.0, 1.0, 1.0), rotation, 4, 2)
            val expected = lumaAt(pixels, 4, cut.x.toInt(), cut.y.toInt())
            assertEquals("rotation $rotation, upright top-left", expected, (gray[0].toInt() and 0xffff) ushr 11 shl 3)
            assertEquals(size.width * size.height, gray.size)
        }
    }

    @Test
    fun aBufferCutIsEvenAndInside() {
        assertEquals(BufferCrop(100, 202, 300, 400), evenCrop(FrameRect(100.4, 201.2, 299.6, 400.9), 1920, 1080))
        assertEquals(BufferCrop(0, 0, 1920, 1080), evenCrop(FrameRect(-3.0, -1.0, 1920.7, 1080.9), 1920, 1080))
        val edge = evenCrop(FrameRect(1800.9, 1000.6, 121.0, 81.0), 1920, 1080)
        check(edge.x % 2 == 0 && edge.y % 2 == 0 && edge.width % 2 == 0 && edge.height % 2 == 0, "even ($edge)")
        check(edge.x + edge.width <= 1920 && edge.y + edge.height <= 1080, "inside ($edge)")
        assertEquals(FrameSize(1080, 1920), bufferOriented(FrameSize(1920, 1080), 90))
        assertEquals(FrameSize(1920, 1080), bufferOriented(FrameSize(1920, 1080), 180))
        assertEquals(FrameSize(1080, 1920), bufferOriented(FrameSize(1920, 1080), 270))
    }

    /**
     * A camera switch: front and back give pictures of the same size, so the zoomed cut and the
     * faces found in the last camera's picture must not carry over (CallCamera's generation).
     */
    @Test
    fun anotherCameraStartsTheCutAgainAndDropsItsFaces() {
        val output = outputSize(fhd, phone)
        val cut = CameraCut()
        val face = FrameRect(900.0, 400.0, 150.0, 150.0)
        assertTrue("the first frame starts a generation", cut.frame(1, fhd, output, follow = true))
        assertTrue(cut.faces(listOf(face), fhd, 1, 0))
        var rect = cut.next(0)
        var t = 33L
        while (t <= 5_000) {
            assertFalse(cut.frame(1, fhd, output, follow = true))
            rect = cut.next(t)
            t += 33
        }
        val whole = targetCrop(fhd, output, null)
        check(rect.height < whole.height * 0.9, "zoomed in on the face (${rect.height})")

        // The other camera's first frame: the whole picture again at once, not a glide from the zoom.
        assertTrue("a switch starts a new generation", cut.frame(2, fhd, output, follow = true))
        assertSameRect("the whole picture at once", whole, cut.next(t))
        // A detection offered on the last camera and finished after the switch is dropped.
        assertFalse("faces from the last camera are dropped", cut.faces(listOf(face), fhd, 1, t))
        t += 33
        while (t <= 8_000) {
            assertFalse(cut.frame(2, fhd, output, follow = true))
            rect = cut.next(t)
            t += 33
        }
        assertSameRect("and the cut stays on the whole picture", whole, rect)
        // Faces from a picture of another size are dropped too; this camera's own are taken.
        assertFalse(cut.faces(listOf(face), uhd, 2, t))
        assertTrue(cut.faces(listOf(face), fhd, 2, t))
    }

    private fun assertSameRect(what: String, expected: FrameRect, actual: FrameRect) {
        val same = near(expected.x, actual.x, 1e-6) && near(expected.y, actual.y, 1e-6) &&
            near(expected.width, actual.width, 1e-6) && near(expected.height, actual.height, 1e-6)
        assertTrue("$what: $expected, got $actual", same)
    }

    @Test
    fun aResetFramerForgetsTheLastFace() {
        val output = outputSize(uhd, null)
        val framer = Framer()
        framer.configure(uhd, output, true)
        framer.next(0)
        framer.faces(listOf(FrameRect(1770.0, 900.0, 300.0, 300.0)), 0)
        framer.next(1_000)
        framer.reset()
        framer.configure(uhd, output, true)
        assertSameRect("it starts on the whole picture", targetCrop(uhd, output, null), framer.next(1_033))
        // No face: no hold from the last camera's face, the cut stays whole.
        framer.faces(emptyList(), 1_100)
        assertSameRect("no hold on the last camera's face", targetCrop(uhd, output, null), framer.next(1_133))
    }

    @Test
    fun theFramedSizeIsTheLaddersCeiling() {
        assertEquals(FrameSize(886, 1920), framedSize(uhd, phone))
        assertEquals(5, ceilingFor(framedSize(uhd, phone)))
        // A landscape 1080p camera cut for a portrait phone: 498×1080, 540p's pixels.
        assertEquals(FrameSize(498, 1080), framedSize(fhd, phone))
        assertEquals(3, ceilingFor(framedSize(fhd, phone)))
        assertEquals(FrameSize(886, 1920), framedSize(FrameSize(1080, 1920), phone))
        assertEquals(FrameSize(1920, 1080), framedSize(FrameSize(2560, 1440), null))
        assertEquals(FrameSize(1280, 960), framedSize(FrameSize(1280, 960), null))
        assertNull(framedSize(FrameSize(0, 0), null))
    }

    // ---- Android: the face detector's input and output ----

    @Test
    fun theDetectorSeesTheLumaUpright() {
        // Buffer 3×2: rows [0 1 2] / [3 4 5] (times 16 for luma). Turned 90° clockwise it reads
        // [3 0] / [4 1] / [5 2]; 180°: [5 4 3] / [2 1 0]; 270°: [2 5] / [1 4] / [0 3].
        val luma = pixelsOf(3, 2, stride = 4)
        fun upright(rotation: Int) = uprightGray565(luma, 4, 3, 2, rotation).map { (it.toInt() and 0xffff) ushr 11 shl 3 }.map { it / 16 }
        assertEquals(listOf(0, 1, 2, 3, 4, 5), upright(0))
        assertEquals(listOf(3, 0, 4, 1, 5, 2), upright(90))
        assertEquals(listOf(5, 4, 3, 2, 1, 0), upright(180))
        assertEquals(listOf(2, 5, 1, 4, 0, 3), upright(270))
        // Grey: red, green and blue carry the same luma.
        val white = uprightGray565(ByteBuffer.wrap(byteArrayOf(0xff.toByte(), 0xff.toByte())), 2, 2, 1, 0)
        assertArrayEquals(shortArrayOf(0xffff.toShort(), 0xffff.toShort()), white)
    }

    @Test
    fun aFaceBoxIsBuiltRoundTheEyesInFullFramePixels() {
        val box = faceBoxFromEyes(midX = 160.0, midY = 80.0, eyesDistance = 20.0, scaleX = 6.0, scaleY = 6.0)
        // Centre (160, 85), 44 × 52 in the small picture: 138…182 × 59…111, six times as large.
        check(near(box.x, 138.0 * 6, 1e-9) && near(box.y, 59.0 * 6, 1e-9), "corner ($box)")
        check(near(box.width, 44.0 * 6, 1e-9) && near(box.height, 52.0 * 6, 1e-9), "size ($box)")
        val stretched = faceBoxFromEyes(10.0, 10.0, 10.0, 2.0, 3.0)
        check(near(stretched.width, 44.0, 1e-9) && near(stretched.height, 78.0, 1e-9), "each axis its own scale ($stretched)")
    }

    /** A luma plane [width] × [height] whose pixel i is 16·i, rows [stride] bytes apart. */
    private fun pixelsOf(width: Int, height: Int, stride: Int = width): ByteBuffer {
        val bytes = ByteArray(stride * height)
        for (y in 0 until height) for (x in 0 until width) bytes[y * stride + x] = ((y * width + x) * 16).toByte()
        return ByteBuffer.wrap(bytes)
    }

    private fun lumaAt(plane: ByteBuffer, stride: Int, x: Int, y: Int): Int = (plane.get(y * stride + x).toInt() and 0xff) ushr 3 shl 3
}
