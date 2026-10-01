package de.corespace.shroud.core.media.scrub

/**
 * HEIF/HEIC metadata scrubbing without moving a byte (media-voice-links §5.2, D5). iOS copies HEIC
 * losslessly with replaced metadata and then blanks the XMP packet ImageIO leaves behind
 * (`ios/shroud/Services/Crypto/MediaMetadataScrubber.swift:22-43, :192-210`); Android has no HEIC
 * metadata writer, so this works on the ISO-BMFF items in place:
 *
 * - `Exif` items: the bytes after the 4-byte `exif_tiff_header_offset` (and its `Exif\0\0` prefix)
 *   become the minimal orientation-only TIFF plus zeros ([MinimalTiff]; HEIF orientation itself
 *   lives in the kept `irot`/`imir` properties);
 * - `mime` items of type `application/rdf+xml` (XMP): [XmpBlanker] in place;
 * - item names: spaces (same length; decoders never read them);
 * - bytes of `mdat`/`idat` that no item references, and `free`/`skip` payloads: zeros;
 * - a trailing top-level box after all referenced data (Samsung's `mpvd` motion video): cut off.
 *
 * Items in `idat` (construction method 1 — iPhone grids, ISO gain-map `tmap` items) are handled like
 * file-offset items. Refused (null → the caller re-encodes): another handler than `pict`, an unknown
 * box at the top level before the data ends or inside `meta`, an unknown item type, a `mime` item
 * that is not XMP, `uri ` items, protected items, data in other files, metadata items built from
 * other items (construction method 2), `infe` versions 0/1. When in doubt, never the original.
 */
internal object HeifScrubber {
    private val TOP_LEVEL: Set<String> = setOf("ftyp", "meta", "mdat", "free", "skip")
    private val META_CHILDREN: Set<String> = setOf("hdlr", "pitm", "iloc", "iinf", "iref", "iprp", "idat", "dinf", "grpl", "free", "skip")

    /** Coded and derived image items, kept untouched. */
    private val IMAGE_ITEMS: Set<String> = setOf(
        "hvc1", "hev1", "hvt1", "lhv1", "av01", "avc1", "jpeg", "j2k1", "vvc1", "unci", "grid", "iden", "iovl", "tmap",
    )
    private const val XMP_CONTENT_TYPE = "application/rdf+xml"
    private const val EXIF_PREFIX = "Exif\u0000\u0000"

    private enum class Kind { Image, Exif, Xmp, Unsupported }

    /** True when [data] is an ISO-BMFF file of the HEIF family (`ftyp` first). */
    fun isHeif(data: ByteArray): Boolean = IsoBmff.brands(data) != null

    /** The clean file, or null when it could not be cleaned without re-encoding. */
    fun scrub(data: ByteArray): ByteArray? {
        val out = data.copyOf()
        val heif = IsoBmff.parseHeif(out) ?: return null
        if (heif.handler != "pict") return null
        if (heif.metaChildren.any { it.type !in META_CHILDREN }) return null
        if (heif.locations.values.any { it.dataReferenceIndex != 0 }) return null
        for (item in heif.items) {
            if (item.protectionIndex != 0) return null
            val location = heif.locations[item.id]
            when (kind(item)) {
                Kind.Image -> Unit
                Kind.Exif -> if (location != null && !scrubExif(out, location)) return null
                Kind.Xmp -> if (location != null) {
                    if (location.constructionMethod == 2) return null
                    val bytes = gather(out, location.extents)
                    XmpBlanker.blank(bytes)
                    scatter(bytes, out, location.extents)
                }
                Kind.Unsupported -> return null
            }
            out.fill(SPACE, item.nameStart, item.nameEnd)
        }
        val cut = cutPoint(heif) ?: return null
        zeroUnreferenced(out, heif, cut)
        return if (cut == out.size) out else out.copyOf(cut)
    }

    /** Everything in [data] that is not on the allow-list, as readable names. Empty = clean. */
    fun leftovers(data: ByteArray): List<String> {
        val heif = IsoBmff.parseHeif(data) ?: return listOf("HEIF.unreadable")
        val found = ArrayList<String>()
        for (box in heif.topLevel) if (box.type !in TOP_LEVEL) found += "HEIF." + box.type
        for (box in heif.metaChildren) if (box.type !in META_CHILDREN) found += "HEIF.meta." + box.type
        if (heif.handler != "pict") found += "HEIF.handler"
        if (heif.locations.values.any { it.dataReferenceIndex != 0 }) found += "HEIF.externalData"
        for (item in heif.items) {
            if (item.protectionIndex != 0) found += "HEIF.protectedItem"
            if (!data.allEqual(item.nameStart, item.nameEnd, SPACE)) found += "HEIF.itemName"
            val location = heif.locations[item.id]
            when (kind(item)) {
                Kind.Image -> Unit
                Kind.Exif -> if (location != null) found += auditExif(data, location)
                Kind.Xmp -> if (location != null) {
                    if (location.constructionMethod == 2) found += "HEIF.xmpReference" else found += XmpBlanker.audit(gather(data, location.extents))
                }
                Kind.Unsupported -> found += "HEIF.item." + item.type.trim() + (item.contentType?.let { "($it)" } ?: "")
            }
        }
        if (!unreferencedIsZero(data, heif, data.size)) found += "HEIF.unreferenced"
        return found
    }

    private fun kind(item: IsoBmff.ItemInfo): Kind = when (item.type) {
        in IMAGE_ITEMS -> Kind.Image
        "Exif" -> Kind.Exif
        "mime" -> if (item.contentType == XMP_CONTENT_TYPE) Kind.Xmp else Kind.Unsupported
        else -> Kind.Unsupported
    }

    private fun scrubExif(out: ByteArray, location: IsoBmff.ItemLocation): Boolean {
        if (location.constructionMethod == 2) return false
        val bytes = gather(out, location.extents)
        if (bytes.size < 4) return false
        val offset = bytes.u32be(0)
        if (offset > bytes.size - 4L) return false
        val tiff = 4 + offset.toInt()
        val orientation = MinimalTiff.orientation(bytes, tiff, bytes.size) ?: return false
        val keepPrefix = offset == EXIF_PREFIX.length.toLong() && bytes.hasAscii(4, EXIF_PREFIX)
        if (!keepPrefix) bytes.fill(ZERO, 4, tiff)
        if (!MinimalTiff.writeMinimal(bytes, tiff, bytes.size, orientation)) return false
        scatter(bytes, out, location.extents)
        return true
    }

    private fun auditExif(data: ByteArray, location: IsoBmff.ItemLocation): List<String> {
        if (location.constructionMethod == 2) return listOf("HEIF.exifReference")
        val bytes = gather(data, location.extents)
        if (bytes.size < 4) return listOf("Exif.unreadable")
        val offset = bytes.u32be(0)
        if (offset > bytes.size - 4L) return listOf("Exif.unreadable")
        val tiff = 4 + offset.toInt()
        val found = ArrayList<String>()
        val prefixClean = (offset == EXIF_PREFIX.length.toLong() && bytes.hasAscii(4, EXIF_PREFIX)) || bytes.allEqual(4, tiff, ZERO)
        if (!prefixClean) found += "Exif.prefix"
        found += MinimalTiff.audit(bytes, tiff, bytes.size, "Exif")
        return found
    }

    /**
     * Where the file may end: after the last top-level box we keep. A box we do not know that starts
     * after all referenced data (and `meta`) is cut off with everything after it; one before → null.
     */
    private fun cutPoint(heif: IsoBmff.Heif): Int? {
        var referencedEnd = heif.meta.end.toLong()
        for (location in heif.locations.values) for (extent in location.extents) referencedEnd = maxOf(referencedEnd, extent.last + 1)
        for (box in heif.topLevel) {
            if (box.type in TOP_LEVEL) continue
            return if (box.start >= referencedEnd) box.start else null
        }
        return heif.topLevel.last().end
    }

    /** Zeroes `mdat`/`idat` bytes no item references, and `free`/`skip` payloads, before [cut]. */
    private fun zeroUnreferenced(out: ByteArray, heif: IsoBmff.Heif, cut: Int) {
        forEachUnreferenced(heif, cut) { from, to -> out.fill(ZERO, from, to) }
    }

    private fun unreferencedIsZero(data: ByteArray, heif: IsoBmff.Heif, cut: Int): Boolean {
        var clean = true
        forEachUnreferenced(heif, cut) { from, to -> if (!data.allEqual(from, to, ZERO)) clean = false }
        return clean
    }

    private inline fun forEachUnreferenced(heif: IsoBmff.Heif, cut: Int, action: (Int, Int) -> Unit) {
        val covered = heif.locations.values.flatMap { it.extents }.sortedBy { it.first }
        val containers = ArrayList<IsoBmff.Box>()
        for (box in heif.topLevel) if (box.end <= cut && box.type == "mdat") containers += box
        heif.idat?.let { containers += it }
        for (box in containers) {
            var cursor = box.payload.toLong()
            for (extent in covered) {
                if (extent.last < cursor || extent.first >= box.end) continue
                if (extent.first > cursor) action(cursor.toInt(), extent.first.toInt())
                cursor = maxOf(cursor, extent.last + 1)
            }
            if (cursor < box.end) action(cursor.toInt(), box.end)
        }
        for (box in heif.topLevel) if (box.end <= cut && (box.type == "free" || box.type == "skip")) action(box.payload, box.end)
        for (box in heif.metaChildren) if (box.type == "free" || box.type == "skip") action(box.payload, box.end)
    }

    private fun gather(data: ByteArray, extents: List<LongRange>): ByteArray {
        val total = extents.sumOf { it.last - it.first + 1 }
        val out = ByteArray(total.toInt())
        var at = 0
        for (extent in extents) {
            val length = (extent.last - extent.first + 1).toInt()
            System.arraycopy(data, extent.first.toInt(), out, at, length)
            at += length
        }
        return out
    }

    private fun scatter(bytes: ByteArray, out: ByteArray, extents: List<LongRange>) {
        var at = 0
        for (extent in extents) {
            val length = (extent.last - extent.first + 1).toInt()
            System.arraycopy(bytes, at, out, extent.first.toInt(), length)
            at += length
        }
    }
}
