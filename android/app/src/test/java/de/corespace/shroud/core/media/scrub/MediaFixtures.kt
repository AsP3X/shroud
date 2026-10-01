package de.corespace.shroud.core.media.scrub

import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.CRC32
import javax.imageio.IIOImage
import javax.imageio.ImageIO
import javax.imageio.ImageWriteParam

/**
 * Test fixtures for the photo scrubbers: the committed ImageIO files (built like the iOS tests build
 * theirs, `app/src/test/resources/media/imageio/generate.swift`) and byte-level builders for the
 * Android camera layouts iOS never produces — Pixel Ultra HDR JPEGs and Motion Photos, Samsung
 * JPEGs with `SEFT` trailers and HEICs with an `mpvd` motion video (media-voice-links §5.2).
 *
 * Every builder plants the same secrets as the iOS fixtures (`MediaMetadataScrubberTests.swift:24`).
 * Pixel data comes from the JDK's own JPEG/PNG writers (`javax.imageio`), so decoders can compare the
 * pixels before and after.
 */
object MediaFixtures {
    /** iOS `removesLocationCameraAndDates` (`MediaMetadataScrubberTests.swift:24`) plus the video and XMP ones. */
    val SECRETS = listOf("SERIAL-0042", "Test Phone", "2026:09:01", "2026-09-01", "Reykjavik", "Iceland", "Test Photographer")

    init {
        System.setProperty("java.awt.headless", "true")
    }

    fun resource(name: String): ByteArray =
        requireNotNull(MediaFixtures::class.java.classLoader!!.getResourceAsStream(name)) { "missing fixture $name" }.use { it.readBytes() }

    /** The bytes as text, for "this secret is gone" checks (Latin-1 maps every byte to one char). */
    fun text(bytes: ByteArray): String = String(bytes, Charsets.ISO_8859_1)

    fun survivingSecrets(bytes: ByteArray, secrets: List<String> = SECRETS): List<String> = secrets.filter { text(bytes).contains(it) }

    // ---- Pixels -------------------------------------------------------------------------------

    /** A small picture with structure (a gradient and a block), so a broken decode would show. */
    fun picture(width: Int, height: Int, seed: Int = 1): BufferedImage {
        val image = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
        for (y in 0 until height) for (x in 0 until width) {
            val r = (x * 255 / maxOf(1, width - 1) + seed * 40) and 0xFF
            val g = (y * 255 / maxOf(1, height - 1)) and 0xFF
            val b = if (x in width / 4 until width / 2 && y in height / 4 until height / 2) 230 else 30
            image.setRGB(x, y, (r shl 16) or (g shl 8) or b)
        }
        return image
    }

    /** A plain JPEG from the JDK writer: SOI, APP0 JFIF, tables, frame, scan(s), EOI. */
    fun jpeg(width: Int, height: Int, seed: Int = 1, progressive: Boolean = false): ByteArray {
        val writer = ImageIO.getImageWritersByFormatName("jpeg").next()
        val out = ByteArrayOutputStream()
        ImageIO.createImageOutputStream(out).use { stream ->
            writer.output = stream
            val param = writer.defaultWriteParam
            param.compressionMode = ImageWriteParam.MODE_EXPLICIT
            param.compressionQuality = 0.9f
            if (progressive) param.progressiveMode = ImageWriteParam.MODE_DEFAULT
            writer.write(null, IIOImage(picture(width, height, seed), null, null), param)
        }
        writer.dispose()
        return out.toByteArray()
    }

    /** Decoded pixels (ARGB ints) of a JPEG or PNG through the JDK, or null when it cannot read them. */
    fun pixels(bytes: ByteArray): IntArray? {
        val image = ImageIO.read(ByteArrayInputStream(bytes)) ?: return null
        return image.getRGB(0, 0, image.width, image.height, null, 0, image.width)
    }

    // ---- JPEG segments ------------------------------------------------------------------------

    fun segment(marker: Int, payload: ByteArray): ByteArray {
        val length = payload.size + 2
        require(length <= 0xFFFF)
        return byteArrayOf(0xFF.toByte(), marker.toByte(), (length ushr 8).toByte(), length.toByte()) + payload
    }

    fun ascii(text: String): ByteArray = text.toByteArray(Charsets.ISO_8859_1)

    /** [jpeg] with [segments] inserted right after its APP0 (or after SOI when it has none). */
    fun withSegments(jpeg: ByteArray, vararg segments: ByteArray): ByteArray {
        var at = 2
        if ((jpeg[2].toInt() and 0xFF) == 0xFF && (jpeg[3].toInt() and 0xFF) == 0xE0) at = 4 + (((jpeg[4].toInt() and 0xFF) shl 8) or (jpeg[5].toInt() and 0xFF))
        val out = ByteArrayOutputStream()
        out.write(jpeg, 0, at)
        for (segment in segments) out.write(segment)
        out.write(jpeg, at, jpeg.size - at)
        return out.toByteArray()
    }

    fun exifSegment(tiff: ByteArray): ByteArray = segment(0xE1, ascii("Exif\u0000\u0000") + tiff)

    fun xmpSegment(packet: String): ByteArray = segment(0xE1, ascii("http://ns.adobe.com/xap/1.0/\u0000") + packet.toByteArray(Charsets.UTF_8))

    /** Photoshop 3.0 IRB with an IPTC block naming the city, country and creator (what cameras write). */
    fun iptcSegment(): ByteArray {
        val iptc = ByteArrayOutputStream()
        fun dataset(record: Int, tag: Int, value: String) {
            val bytes = ascii(value)
            iptc.write(byteArrayOf(0x1C, record.toByte(), tag.toByte(), (bytes.size ushr 8).toByte(), bytes.size.toByte()))
            iptc.write(bytes)
        }
        dataset(2, 90, "Reykjavik")
        dataset(2, 101, "Iceland")
        dataset(2, 80, "Test Photographer")
        val block = iptc.toByteArray()
        val irb = ascii("8BIM") + byteArrayOf(0x04, 0x04, 0, 0) + u32(block.size) + block + (if (block.size % 2 == 1) byteArrayOf(0) else byteArrayOf())
        return segment(0xED, ascii("Photoshop 3.0\u0000") + irb)
    }

    fun u32(value: Int): ByteArray = byteArrayOf((value ushr 24).toByte(), (value ushr 16).toByte(), (value ushr 8).toByte(), value.toByte())

    fun u16(value: Int): ByteArray = byteArrayOf((value ushr 8).toByte(), value.toByte())

    /** A camera-style Exif TIFF: Make/Model/Software/DateTime, GPS, an Exif IFD with dates, serial, lens and a maker note, and an IFD1 thumbnail. */
    fun cameraTiff(orientation: Int = 6, bigEndian: Boolean = true): ByteArray {
        val gps = Tiff.Ifd(
            listOf(
                Tiff.Entry.Ascii(0x0001, "N"),
                Tiff.Entry.Rationals(0x0002, listOf(52L to 1L, 31L to 1L, 12L to 1L)),
                Tiff.Entry.Ascii(0x0003, "E"),
                Tiff.Entry.Rationals(0x0004, listOf(13L to 1L, 24L to 1L, 18L to 1L)),
            ),
        )
        val exif = Tiff.Ifd(
            listOf(
                Tiff.Entry.Undefined(0x9000, ascii("0232")),
                Tiff.Entry.Ascii(0x9003, "2026:09:01 12:00:00"),
                Tiff.Entry.Ascii(0x9011, "+02:00"),
                Tiff.Entry.Undefined(0x927C, ascii("Google\u0000HDRP-SERIAL-0042-maker-note-Test Phone")),
                Tiff.Entry.Short(0xA001, 1),
                Tiff.Entry.Ascii(0xA431, "SERIAL-0042"),
                Tiff.Entry.Ascii(0xA434, "Test Phone back camera"),
            ),
        )
        val thumbnail = Tiff.Ifd(listOf(Tiff.Entry.Short(0x0103, 6), Tiff.Entry.Undefined(0x0201, ascii("thumbnail Test Phone"))))
        val ifd0 = Tiff.Ifd(
            listOf(
                Tiff.Entry.Ascii(0x010F, "Google"),
                Tiff.Entry.Ascii(0x0110, "Test Phone"),
                Tiff.Entry.Short(0x0112, orientation),
                Tiff.Entry.Ascii(0x0131, "HDR+ 1.0"),
                Tiff.Entry.Ascii(0x0132, "2026:09:01 12:00:00"),
                Tiff.Entry.Pointer(0x8769, exif),
                Tiff.Entry.Pointer(0x8825, gps),
            ),
            next = thumbnail,
        )
        return Tiff.build(ifd0, bigEndian)
    }

    /** The XMP a Pixel writes into an Ultra HDR / Motion Photo primary image. */
    fun pixelPrimaryXmp(motionPhoto: Boolean, gainMapLength: Int, videoLength: Int): String = buildString {
        append("<?xpacket begin=\"﻿\" id=\"W5M0MpCehiHzreSzNTczkc9d\"?>")
        append("<x:xmpmeta xmlns:x=\"adobe:ns:meta/\" x:xmptk=\"Adobe XMP Core 5.1.0-jc003\">")
        append("<rdf:RDF xmlns:rdf=\"http://www.w3.org/1999/02/22-rdf-syntax-ns#\">")
        append("<rdf:Description rdf:about=\"\" xmlns:hdrgm=\"http://ns.adobe.com/hdr-gain-map/1.0/\"")
        append(" xmlns:GCamera=\"http://ns.google.com/photos/1.0/camera/\" xmlns:Container=\"http://ns.google.com/photos/1.0/container/\"")
        append(" xmlns:Item=\"http://ns.google.com/photos/1.0/container/item/\" xmlns:photoshop=\"http://ns.adobe.com/photoshop/1.0/\"")
        append(" hdrgm:Version=\"1.0\" photoshop:DateCreated=\"2026-09-01T12:00:00\"")
        if (motionPhoto) append(" GCamera:MotionPhoto=\"1\" GCamera:MotionPhotoVersion=\"1\" GCamera:MotionPhotoPresentationTimestampUs=\"968096\"")
        append(">")
        append("<Container:Directory><rdf:Seq>")
        append("<rdf:li rdf:parseType=\"Resource\"><Container:Item Item:Semantic=\"Primary\" Item:Mime=\"image/jpeg\"/></rdf:li>")
        append("<rdf:li rdf:parseType=\"Resource\"><Container:Item Item:Semantic=\"GainMap\" Item:Mime=\"image/jpeg\" Item:Length=\"$gainMapLength\"/></rdf:li>")
        if (motionPhoto) {
            append("<rdf:li rdf:parseType=\"Resource\"><Container:Item Item:Semantic=\"MotionPhoto\" Item:Mime=\"video/mp4\" Item:Length=\"$videoLength\" Item:Padding=\"0\"/></rdf:li>")
        }
        append("</rdf:Seq></Container:Directory>")
        append("</rdf:Description></rdf:RDF></x:xmpmeta>")
        append("<?xpacket end=\"w\"?>")
    }

    /** The gain map image's XMP (Ultra HDR v1: every `hdrgm` parameter as an attribute). */
    const val GAIN_MAP_XMP: String =
        "<x:xmpmeta xmlns:x=\"adobe:ns:meta/\" x:xmptk=\"Adobe XMP Core 5.1.2\"><rdf:RDF xmlns:rdf=\"http://www.w3.org/1999/02/22-rdf-syntax-ns#\">" +
            "<rdf:Description rdf:about=\"\" xmlns:hdrgm=\"http://ns.adobe.com/hdr-gain-map/1.0/\" hdrgm:Version=\"1.0\"" +
            " hdrgm:GainMapMin=\"0\" hdrgm:GainMapMax=\"2.3\" hdrgm:Gamma=\"1\" hdrgm:OffsetSDR=\"0\" hdrgm:OffsetHDR=\"0\"" +
            " hdrgm:HDRCapacityMin=\"0\" hdrgm:HDRCapacityMax=\"2.3\" hdrgm:BaseRenditionIsHDR=\"False\"/>" +
            "</rdf:RDF></x:xmpmeta>"

    /** An APP2 MPF segment (big-endian) for a primary of [primarySize] bytes and secondary images at the given offsets. */
    fun mpfSegment(primarySize: Int, secondaries: List<Pair<Int, Int>>): ByteArray {
        val count = 1 + secondaries.size
        val ifdEntries = 3
        val ifdSize = 2 + 12 * ifdEntries + 4
        val entriesOffset = 8 + ifdSize
        val tiff = ByteArrayOutputStream()
        tiff.write(ascii("MM")); tiff.write(u16(42)); tiff.write(u32(8))
        tiff.write(u16(ifdEntries))
        tiff.write(u16(0xB000)); tiff.write(u16(7)); tiff.write(u32(4)); tiff.write(ascii("0100"))
        tiff.write(u16(0xB001)); tiff.write(u16(4)); tiff.write(u32(1)); tiff.write(u32(count))
        tiff.write(u16(0xB002)); tiff.write(u16(7)); tiff.write(u32(16 * count)); tiff.write(u32(entriesOffset))
        tiff.write(u32(0))
        tiff.write(u32(0x030000)); tiff.write(u32(primarySize)); tiff.write(u32(0)); tiff.write(u32(0))
        for ((offset, size) in secondaries) {
            tiff.write(u32(0)); tiff.write(u32(size)); tiff.write(u32(offset)); tiff.write(u32(0))
        }
        return segment(0xE2, ascii("MPF\u0000") + tiff.toByteArray())
    }

    /** A tiny MP4 (`ftyp` + `moov/udta` with `©xyz` and `©day`), as a motion photo trailer carries. */
    fun motionVideo(): ByteArray {
        fun box(type: String, payload: ByteArray): ByteArray = u32(8 + payload.size) + ascii(type) + payload
        val xyz = box("©xyz", u16(17) + u16(0x15C7) + ascii("+52.5200+013.4050/"))
        val day = box("©day", u16(24) + u16(0x15C7) + ascii("2026-09-01T12:00:00+0200"))
        val mod = box("©mod", u16(10) + u16(0x15C7) + ascii("Test Phone"))
        return box("ftyp", ascii("isom") + u32(0x200) + ascii("isomiso2mp41")) +
            box("moov", box("udta", xyz + day + mod)) + box("mdat", ByteArray(64) { it.toByte() })
    }

    /**
     * A Pixel-layout Ultra HDR JPEG: primary (Exif with GPS/maker note/thumbnail, XMP with `hdrgm` +
     * GContainer + `GCamera`, MPF), then the gain map JPEG with its `hdrgm` XMP; with [motionPhoto]
     * the MP4 follows the gain map.
     */
    fun pixelUltraHdr(motionPhoto: Boolean = false): UltraHdr {
        val gainMapPixels = jpeg(32, 24, seed = 7)
        val gainMap = withSegments(gainMapPixels, xmpSegment(GAIN_MAP_XMP))
        val video = if (motionPhoto) motionVideo() else ByteArray(0)
        val base = jpeg(64, 48, seed = 3)
        // The MPF size field depends on the primary's own length; build twice to settle it.
        var primary = ByteArray(0)
        var mpfTiffStart = 0
        repeat(2) {
            val exif = exifSegment(cameraTiff())
            val xmp = xmpSegment(pixelPrimaryXmp(motionPhoto, gainMap.size, video.size))
            val mpfProbe = mpfSegment(primary.size, listOf(0 to gainMap.size))
            val draft = withSegments(base, exif, xmp, mpfProbe)
            mpfTiffStart = draft.indexOf(ascii("MPF\u0000"), 0) + 4
            val mpf = mpfSegment(draft.size, listOf((draft.size - mpfTiffStart) to gainMap.size))
            primary = withSegments(base, exif, xmp, mpf)
        }
        return UltraHdr(primary + gainMap + video, primary.size, gainMap.size, video.size)
    }

    class UltraHdr(val bytes: ByteArray, val primarySize: Int, val gainMapSize: Int, val videoSize: Int)

    /** A Samsung-layout JPEG: Exif, IPTC, then a `SEFH…SEFT` trailer with capture times (no MPF). */
    fun samsungJpeg(): ByteArray {
        val base = withSegments(jpeg(64, 48, seed = 5), exifSegment(cameraTiff(orientation = 1, bigEndian = false)), iptcSegment())
        val trailer = ascii("SEFH") + ascii("Image_UTC_Data1756728000000") + ascii("MotionPhoto_Data") + motionVideo() + u32(0x10) + ascii("SEFT")
        return base + trailer
    }

    // ---- PNG ----------------------------------------------------------------------------------

    fun pngChunk(type: String, data: ByteArray): ByteArray {
        val crc = CRC32()
        crc.update(ascii(type))
        crc.update(data)
        return u32(data.size) + ascii(type) + data + u32(crc.value.toInt())
    }

    /** A JDK-written PNG with text chunks, a GPS `eXIf`, `tIME`, a C2PA-style `caBX` and trailing bytes added. */
    fun taggedPng(orientation: Int = 6): ByteArray {
        val out = ByteArrayOutputStream()
        ImageIO.write(picture(40, 30, seed = 2), "png", out)
        val plain = out.toByteArray()
        val ihdrEnd = 8 + 12 + 13
        val extras = pngChunk("tEXt", ascii("Author\u0000Test Photographer")) +
            pngChunk("iTXt", ascii("XML:com.adobe.xmp\u0000\u0000\u0000\u0000\u0000") + pixelPrimaryXmp(false, 0, 0).toByteArray(Charsets.UTF_8)) +
            pngChunk("zTXt", ascii("Comment\u0000\u0000") + byteArrayOf(0x78, 0x9C.toByte(), 0x03, 0x00, 0x00, 0x00, 0x00, 0x01)) +
            pngChunk("tIME", byteArrayOf(0x07, 0xEA.toByte(), 9, 1, 12, 0, 0)) +
            pngChunk("eXIf", cameraTiff(orientation)) +
            pngChunk("caBX", ascii("c2pa manifest Test Phone"))
        return plain.copyOfRange(0, ihdrEnd) + extras + plain.copyOfRange(ihdrEnd, plain.size) + ascii("trailing Reykjavik")
    }

    // ---- TIFF builder -------------------------------------------------------------------------

    object Tiff {
        class Ifd(val entries: List<Entry>, val next: Ifd? = null)

        sealed class Entry(val tag: Int) {
            class Ascii(tag: Int, val text: String) : Entry(tag)
            class Short(tag: Int, val value: Int) : Entry(tag)
            class Undefined(tag: Int, val bytes: ByteArray) : Entry(tag)
            class Rationals(tag: Int, val values: List<Pair<Long, Long>>) : Entry(tag)
            class Pointer(tag: Int, val ifd: Ifd) : Entry(tag)
        }

        /** Serialises [ifd0] (and everything it points to) into a TIFF block. */
        fun build(ifd0: Ifd, bigEndian: Boolean): ByteArray {
            val out = Writer(bigEndian)
            out.bytes(if (bigEndian) ascii("MM") else ascii("II"))
            out.u16(42)
            out.u32(8)
            out.writeIfd(ifd0)
            return out.toByteArray()
        }

        private class Writer(val big: Boolean) {
            private var buffer = ByteArray(0)

            fun bytes(b: ByteArray) {
                buffer += b
            }

            fun u16(v: Int) = bytes(if (big) byteArrayOf((v ushr 8).toByte(), v.toByte()) else byteArrayOf(v.toByte(), (v ushr 8).toByte()))

            fun u32(v: Long) = bytes(
                if (big) {
                    byteArrayOf((v ushr 24).toByte(), (v ushr 16).toByte(), (v ushr 8).toByte(), v.toByte())
                } else {
                    byteArrayOf(v.toByte(), (v ushr 8).toByte(), (v ushr 16).toByte(), (v ushr 24).toByte())
                },
            )

            fun u32(v: Int) = u32(v.toLong())

            fun put32(at: Int, v: Long) {
                val b = if (big) {
                    byteArrayOf((v ushr 24).toByte(), (v ushr 16).toByte(), (v ushr 8).toByte(), v.toByte())
                } else {
                    byteArrayOf(v.toByte(), (v ushr 8).toByte(), (v ushr 16).toByte(), (v ushr 24).toByte())
                }
                b.copyInto(buffer, at)
            }

            fun toByteArray(): ByteArray = buffer

            /** Writes [ifd] at the current position; out-of-line values and sub-IFDs follow it. */
            fun writeIfd(ifd: Ifd) {
                val start = buffer.size
                u16(ifd.entries.size)
                val fields = IntArray(ifd.entries.size)
                for ((index, entry) in ifd.entries.withIndex()) {
                    u16(entry.tag)
                    when (entry) {
                        is Entry.Ascii -> { u16(2); u32(entry.text.length + 1) }
                        is Entry.Short -> { u16(3); u32(1) }
                        is Entry.Undefined -> { u16(7); u32(entry.bytes.size) }
                        is Entry.Rationals -> { u16(5); u32(entry.values.size) }
                        is Entry.Pointer -> { u16(4); u32(1) }
                    }
                    fields[index] = buffer.size
                    u32(0)
                }
                val nextField = buffer.size
                u32(0)
                check(nextField == start + 2 + 12 * ifd.entries.size)
                for ((index, entry) in ifd.entries.withIndex()) {
                    val field = fields[index]
                    when (entry) {
                        is Entry.Ascii -> inlineOrOffset(field, ascii(entry.text) + byteArrayOf(0))
                        is Entry.Short -> {
                            val v = entry.value
                            val b = if (big) byteArrayOf((v ushr 8).toByte(), v.toByte(), 0, 0) else byteArrayOf(v.toByte(), (v ushr 8).toByte(), 0, 0)
                            b.copyInto(buffer, field)
                        }
                        is Entry.Undefined -> inlineOrOffset(field, entry.bytes)
                        is Entry.Rationals -> {
                            put32(field, buffer.size.toLong())
                            for ((n, d) in entry.values) { u32(n); u32(d) }
                        }
                        is Entry.Pointer -> {
                            put32(field, buffer.size.toLong())
                            writeIfd(entry.ifd)
                        }
                    }
                }
                ifd.next?.let {
                    put32(nextField, buffer.size.toLong())
                    writeIfd(it)
                }
            }

            private fun inlineOrOffset(field: Int, value: ByteArray) {
                if (value.size <= 4) {
                    value.copyInto(buffer, field)
                } else {
                    put32(field, buffer.size.toLong())
                    bytes(value)
                    if (buffer.size % 2 == 1) bytes(byteArrayOf(0))
                }
            }
        }
    }
}
