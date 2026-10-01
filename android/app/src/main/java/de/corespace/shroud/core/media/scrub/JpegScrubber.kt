package de.corespace.shroud.core.media.scrub

/**
 * JPEG metadata scrubbing without moving a byte (media-voice-links §5.2, D5): Android's counterpart of
 * iOS's ImageIO lossless copy plus `blankJPEGIPTC` and `blankXMP`
 * (`ios/shroud/Services/Crypto/MediaMetadataScrubber.swift:22-43, :212-238`). Every segment of the
 * primary image (from SOI through every scan to EOI) and of every MPF secondary image (Ultra HDR /
 * ISO gain maps, large thumbnails) is classified:
 *
 * - kept as is: `APP0` JFIF/JFXX, `APP2` `ICC_PROFILE`, `APP2` `MPF`, `APP2` ISO 21496-1 gain-map
 *   metadata, `APP14` `Adobe`, and every non-APP marker (tables, frames, scans);
 * - `APP1 Exif`: rewritten in place, same length — a little-endian TIFF whose IFD0 holds only the
 *   source's `Orientation` ([MinimalTiff]), then zeros; maker notes, GPS, dates, serials and the
 *   embedded thumbnail go;
 * - `APP1` XMP: [XmpBlanker] in place;
 * - extended XMP, every other `APP1`…`APP15` (IPTC `APP13` exactly as iOS, C2PA `APP11`, FlashPix…)
 *   and `COM`: a `COM` segment of the same length filled with spaces.
 *
 * Bytes after the image (Android only — iOS has no such photos): motion photos (Google "MVIMG" /
 * Motion Photo, Samsung) append an MP4 with its own location and time, Samsung JPEGs a `SEFT` trailer
 * with capture times. The file is cut at `end = max(primary EOI, every MPF image's end)`; nothing
 * before `end` points past it, and the blanked `GCamera:`/`Container:` XMP no longer tells a reader
 * to look there. Bytes inside `[EOI, end)` that belong to no MPF image are zeroed.
 *
 * [leftovers] is the verification (iOS `leftoverMetadata`, `:50-68`): a file whose leftovers are not
 * empty is never sent as is — the caller re-encodes it.
 */
internal object JpegScrubber {
    private const val EXIF = "Exif\u0000\u0000"
    private const val XMP = "http://ns.adobe.com/xap/1.0/\u0000"
    private const val ICC = "ICC_PROFILE\u0000"
    private const val MPF = "MPF\u0000"
    private const val ISO_GAIN_MAP = "urn:iso:std:iso:ts:21496:-1\u0000"
    private const val JFIF = "JFIF\u0000"
    private const val JFXX = "JFXX\u0000"
    private const val ADOBE = "Adobe"
    private const val MP_ENTRY = 0xB002

    private const val SOI = 0xD8
    private const val EOI = 0xD9
    private const val SOS = 0xDA
    private const val COM = 0xFE

    /** One marker segment: `FF xx`, a 2-byte length, the payload. [end] is exclusive. */
    class Segment(val marker: Int, val start: Int, val end: Int) {
        val payload: Int get() = start + 4
    }

    /** One JPEG image: its segments in order and the index just past its EOI. */
    class Layout(val start: Int, val segments: List<Segment>, val eoiEnd: Int)

    private enum class Kind { Keep, Exif, Xmp, Comment, Blank }

    /** True when [data] starts like a JPEG. */
    fun isJpeg(data: ByteArray): Boolean = data.size >= 3 && data.u8(0) == 0xFF && data.u8(1) == SOI && data.u8(2) == 0xFF

    /** The clean file, or null when it could not be cleaned without re-encoding. */
    fun scrub(data: ByteArray): ByteArray? {
        val out = data.copyOf()
        val primary = parse(out, 0, out.size) ?: return null
        if (!scrubImage(out, primary)) return null
        val images = secondaryImages(out, primary) ?: return null
        var end = primary.eoiEnd
        for (range in images) {
            val image = parse(out, range.first, range.last + 1) ?: return null
            if (!scrubImage(out, image)) return null
            out.fill(ZERO, image.eoiEnd, range.last + 1)
            end = maxOf(end, range.last + 1)
        }
        zeroGaps(out, primary.eoiEnd, end, images)
        return if (end == out.size) out else out.copyOf(end)
    }

    /** Everything in [data] that is not on the allow-list, as readable names. Empty = clean. */
    fun leftovers(data: ByteArray): List<String> {
        val primary = parse(data, 0, data.size) ?: return listOf("JPEG.unreadable")
        val found = ArrayList<String>()
        audit(data, primary, "JPEG", found)
        val images = secondaryImages(data, primary)
        if (images == null) {
            found += "JPEG.MPF.unreadable"
            return found
        }
        var end = primary.eoiEnd
        images.forEachIndexed { index, range ->
            val where = "JPEG.image${index + 2}"
            val image = parse(data, range.first, range.last + 1)
            if (image == null) {
                found += "$where.unreadable"
            } else {
                audit(data, image, where, found)
                if (!data.allEqual(image.eoiEnd, range.last + 1, ZERO)) found += "$where.trailing"
            }
            end = maxOf(end, range.last + 1)
        }
        if (!gapsAreZero(data, primary.eoiEnd, end, images)) found += "JPEG.gap"
        if (end < data.size) found += "JPEG.trailing"
        return found
    }

    /**
     * Walks one JPEG from its SOI at [start] through every scan to its EOI, all inside [limit].
     * Null when the structure is broken or there is no EOI.
     */
    fun parse(b: ByteArray, start: Int, limit: Int): Layout? {
        if (start < 0 || start + 4 > limit || limit > b.size) return null
        if (b.u8(start) != 0xFF || b.u8(start + 1) != SOI) return null
        val segments = ArrayList<Segment>()
        var i = start + 2
        while (i + 1 < limit) {
            if (b.u8(i) != 0xFF) return null
            val marker = b.u8(i + 1)
            when {
                marker == 0xFF -> i += 1 // fill byte
                marker == EOI -> return Layout(start, segments, i + 2)
                marker == SOI || marker == 0x01 || marker in 0xD0..0xD7 -> i += 2 // no length
                else -> {
                    if (i + 4 > limit) return null
                    val length = b.u16be(i + 2)
                    if (length < 2 || i + 2 + length > limit) return null
                    segments += Segment(marker, i, i + 2 + length)
                    i += 2 + length
                    if (marker == SOS) i = endOfEntropyData(b, i, limit) ?: return null
                }
            }
        }
        return null
    }

    /** The next marker after entropy-coded data: skips stuffed `FF 00`, restart markers and fill bytes. */
    private fun endOfEntropyData(b: ByteArray, from: Int, limit: Int): Int? {
        var j = from
        while (j + 1 < limit) {
            if (b.u8(j) != 0xFF) {
                j++
                continue
            }
            val next = b.u8(j + 1)
            when {
                next == 0x00 || next in 0xD0..0xD7 -> j += 2
                next == 0xFF -> j += 1
                else -> return j
            }
        }
        return null
    }

    private fun classify(b: ByteArray, segment: Segment): Kind {
        val p = segment.payload
        val end = segment.end
        return when (segment.marker) {
            0xE0 -> if (b.hasAscii(p, JFIF, end) || b.hasAscii(p, JFXX, end)) Kind.Keep else Kind.Blank
            0xE1 -> when {
                b.hasAscii(p, EXIF, end) -> Kind.Exif
                b.hasAscii(p, XMP, end) -> Kind.Xmp
                else -> Kind.Blank // extended XMP and anything else
            }
            0xE2 -> if (b.hasAscii(p, ICC, end) || b.hasAscii(p, MPF, end) || b.hasAscii(p, ISO_GAIN_MAP, end)) Kind.Keep else Kind.Blank
            0xEE -> if (b.hasAscii(p, ADOBE, end)) Kind.Keep else Kind.Blank
            in 0xE3..0xEF -> Kind.Blank
            COM -> Kind.Comment
            else -> Kind.Keep
        }
    }

    private fun scrubImage(out: ByteArray, layout: Layout): Boolean {
        for (segment in layout.segments) {
            when (classify(out, segment)) {
                Kind.Keep -> Unit
                Kind.Exif -> {
                    val tiff = segment.payload + EXIF.length
                    val orientation = MinimalTiff.orientation(out, tiff, segment.end) ?: return false
                    if (!MinimalTiff.writeMinimal(out, tiff, segment.end, orientation)) return false
                }
                Kind.Xmp -> XmpBlanker.blank(out, segment.payload + XMP.length, segment.end)
                Kind.Comment, Kind.Blank -> {
                    // iOS `blankJPEGIPTC` (`:231-234`): same length, a comment of spaces.
                    out[segment.start + 1] = COM.toByte()
                    out.fill(SPACE, segment.payload, segment.end)
                }
            }
        }
        return true
    }

    private fun audit(data: ByteArray, layout: Layout, where: String, found: MutableList<String>) {
        for (segment in layout.segments) {
            when (classify(data, segment)) {
                Kind.Keep -> Unit
                Kind.Exif -> found += MinimalTiff.audit(data, segment.payload + EXIF.length, segment.end, "Exif")
                Kind.Xmp -> found += XmpBlanker.audit(data, segment.payload + XMP.length, segment.end)
                Kind.Comment -> if (!data.allEqual(segment.payload, segment.end, SPACE)) found += "$where.COM"
                Kind.Blank -> found += "$where.APP${segment.marker - 0xE0}"
            }
        }
    }

    /**
     * The MPF secondary images (`[start, end]` ranges, sorted) located by the primary's `APP2 MPF`
     * segment; empty without MPF. Null when the MP entries are malformed or point outside the file
     * or into the primary image. Offsets are relative to the MP endian field (CIPA DC-007).
     */
    private fun secondaryImages(b: ByteArray, primary: Layout): List<IntRange>? {
        val segment = primary.segments.firstOrNull { it.marker == 0xE2 && b.hasAscii(it.payload, MPF, it.end) }
            ?: return emptyList()
        val tiffStart = segment.payload + MPF.length
        val tiff = MinimalTiff.Reader.open(b, tiffStart, segment.end) ?: return null
        val ifd = tiff.readIfd(tiff.firstIfd) ?: return null
        val entry = ifd.entries.firstOrNull { it.tag == MP_ENTRY } ?: return null
        if (entry.count <= 0 || entry.count % 16 != 0L || entry.count > tiff.length) return null
        val count = (entry.count / 16).toInt()
        val table = tiff.u32(entry.valueField)
        if (table + entry.count > tiff.length) return null
        val ranges = ArrayList<IntRange>()
        for (index in 1 until count) { // entry 0 is the primary image itself (offset 0)
            val at = (table + 16L * index).toInt()
            val size = tiff.u32(at + 4)
            val offset = tiff.u32(at + 8)
            if (size == 0L && offset == 0L) continue // an absent image
            val absolute = tiffStart + offset
            if (offset == 0L || size < 4 || absolute < primary.eoiEnd || absolute + size > b.size) return null
            ranges += absolute.toInt()..(absolute + size - 1).toInt()
        }
        ranges.sortBy { it.first }
        for (k in 1 until ranges.size) if (ranges[k].first <= ranges[k - 1].last) return null
        return ranges
    }

    private fun zeroGaps(out: ByteArray, from: Int, to: Int, images: List<IntRange>) {
        var cursor = from
        for (range in images) {
            if (range.first > cursor) out.fill(ZERO, cursor, range.first)
            cursor = maxOf(cursor, range.last + 1)
        }
        if (cursor < to) out.fill(ZERO, cursor, to)
    }

    private fun gapsAreZero(data: ByteArray, from: Int, to: Int, images: List<IntRange>): Boolean {
        var cursor = from
        for (range in images) {
            if (range.first > cursor && !data.allEqual(cursor, range.first, ZERO)) return false
            cursor = maxOf(cursor, range.last + 1)
        }
        return cursor >= to || data.allEqual(cursor, to, ZERO)
    }
}
