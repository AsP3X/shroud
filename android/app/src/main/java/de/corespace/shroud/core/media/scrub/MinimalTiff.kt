package de.corespace.shroud.core.media.scrub

import java.util.Locale

/**
 * The TIFF structure inside Exif blocks (JPEG `APP1 Exif`, PNG `eXIf`, HEIF `Exif` items): reading
 * the orientation, auditing what a block holds against the iOS allow-list
 * (`ios/shroud/Services/Crypto/MediaMetadataScrubber.swift:133-168`), and writing the replacement —
 * a little-endian TIFF whose IFD0 holds only `Orientation` (media-voice-links §5.2).
 *
 * Android has no lossless metadata writer (ExifInterface cannot write HEIC and rewrites files,
 * shifting offsets), so the scrubbers overwrite each Exif block **in place, same length**: the
 * minimal TIFF, then zero padding. Unreferenced padding inside an Exif block is legal.
 */
internal object MinimalTiff {
    const val ORIENTATION = 0x0112
    private const val EXIF_IFD = 0x8769
    private const val GPS_IFD = 0x8825
    private const val INTEROP_IFD = 0xA005
    private const val MAKER_NOTE = 0x927C
    private const val TYPE_SHORT = 3

    /** iOS `allowedTIFF` (`MediaMetadataScrubber.swift:134-137`): image structure, nothing about who, where or when. */
    val ALLOWED_TIFF_TAGS: Map<Int, String> = mapOf(
        0x0112 to "Orientation",
        0x0142 to "TileWidth",
        0x0143 to "TileLength",
        0x011A to "XResolution",
        0x011B to "YResolution",
        0x0128 to "ResolutionUnit",
    )

    /** iOS `allowedExif` (`MediaMetadataScrubber.swift:138-141`). */
    val ALLOWED_EXIF_TAGS: Map<Int, String> = mapOf(
        0xA002 to "PixelXDimension",
        0xA003 to "PixelYDimension",
        0xA001 to "ColorSpace",
        0x9101 to "ComponentsConfiguration",
        0x9000 to "ExifVersion",
        0xA000 to "FlashPixVersion",
    )

    /** Names for the audit's report only (anything else prints as hex). */
    private val NAMES: Map<Int, String> = mapOf(
        0x010E to "ImageDescription", 0x010F to "Make", 0x0110 to "Model", 0x0131 to "Software",
        0x0132 to "DateTime", 0x013B to "Artist", 0x013C to "HostComputer", 0x8298 to "Copyright",
        0x9003 to "DateTimeOriginal", 0x9004 to "DateTimeDigitized", 0x9010 to "OffsetTime",
        0x9011 to "OffsetTimeOriginal", 0x9012 to "OffsetTimeDigitized", 0x9286 to "UserComment",
        0xA420 to "ImageUniqueID", 0xA430 to "CameraOwnerName", 0xA431 to "BodySerialNumber",
        0xA432 to "LensSpecification", 0xA433 to "LensMake", 0xA434 to "LensModel", 0xA435 to "LensSerialNumber",
        MAKER_NOTE to "MakerNote",
    )

    /** Size of the minimal TIFF: header, IFD0 with one entry (orientation ≠ 1) or none, next-IFD 0. */
    fun minimalSize(orientation: Int): Int = if (orientation in 2..8) 26 else 14

    /**
     * Writes the minimal little-endian TIFF at [at] and zero-fills the rest of `[at, end)`. False (and
     * nothing written) when it does not fit.
     */
    fun writeMinimal(out: ByteArray, at: Int, end: Int, orientation: Int): Boolean {
        val keep = orientation in 2..8
        if (at < 0 || at + minimalSize(orientation) > end || end > out.size) return false
        out.fill(ZERO, at, end)
        out[at] = 'I'.code.toByte()
        out[at + 1] = 'I'.code.toByte()
        out[at + 2] = 42
        out[at + 4] = 8 // IFD0 right after the header
        if (keep) {
            out[at + 8] = 1 // one entry
            out[at + 10] = (ORIENTATION and 0xFF).toByte()
            out[at + 11] = (ORIENTATION ushr 8).toByte()
            out[at + 12] = TYPE_SHORT.toByte()
            out[at + 14] = 1 // count
            out[at + 18] = orientation.toByte()
            // value padding and the next-IFD offset stay zero
        }
        return true
    }

    /** EXIF orientation 1…8 of the TIFF block `[start, end)`: 1 when absent or invalid, null when the block is unreadable. */
    fun orientation(b: ByteArray, start: Int, end: Int): Int? {
        val tiff = Reader.open(b, start, end) ?: return null
        val ifd = tiff.readIfd(tiff.firstIfd) ?: return null
        val entry = ifd.entries.firstOrNull { it.tag == ORIENTATION } ?: return 1
        if (entry.type != TYPE_SHORT || entry.count < 1) return 1
        val value = tiff.u16(entry.valueField)
        return if (value in 1..8) value else 1
    }

    /**
     * Everything in the TIFF block that is not on the iOS allow-list, as readable names (`GPS`,
     * `TIFF.Model`, `Exif.BodySerialNumber`, `MakerNote`, …). Empty = clean.
     */
    fun audit(b: ByteArray, start: Int, end: Int, where: String): List<String> {
        val tiff = Reader.open(b, start, end) ?: return listOf("$where.unreadable")
        val ifd0 = tiff.readIfd(tiff.firstIfd) ?: return listOf("$where.unreadable")
        val found = ArrayList<String>()
        for (entry in ifd0.entries) {
            when (entry.tag) {
                in ALLOWED_TIFF_TAGS -> Unit
                GPS_IFD -> found += "GPS"
                EXIF_IFD -> found += auditExif(tiff, tiff.u32(entry.valueField))
                else -> found += "TIFF." + name(entry.tag)
            }
        }
        if (ifd0.next != 0L) found += "TIFF.IFD1"
        // A block that is only the minimal layout must be zero after it: unreferenced bytes can
        // carry anything, and the scrubbers always zero them.
        if (found.isEmpty() && ifd0.entries.all { it.tag == ORIENTATION }) {
            val ifdStart = start + tiff.firstIfd.toInt()
            val structureEnd = ifdStart + 2 + 12 * ifd0.entries.size + 4
            if (!b.allEqual(start + 8, ifdStart, ZERO) || !b.allEqual(structureEnd, end, ZERO)) found += "$where.padding"
        }
        return found
    }

    private fun auditExif(tiff: Reader, offset: Long): List<String> {
        val ifd = tiff.readIfd(offset) ?: return listOf("Exif.unreadable")
        val found = ArrayList<String>()
        for (entry in ifd.entries) {
            when (entry.tag) {
                in ALLOWED_EXIF_TAGS -> Unit
                MAKER_NOTE -> found += "MakerNote"
                INTEROP_IFD -> found += "Exif.InteroperabilityIFD"
                GPS_IFD -> found += "GPS"
                else -> found += "Exif." + name(entry.tag)
            }
        }
        return found
    }

    private fun name(tag: Int): String = NAMES[tag] ?: ALLOWED_TIFF_TAGS[tag] ?: String.format(Locale.ROOT, "0x%04X", tag)

    class Entry(val tag: Int, val type: Int, val count: Long, val valueField: Int) {
        /** Bytes of one value of this entry's TIFF type; 0 for an unknown type. */
        val unitSize: Int
            get() = when (type) {
                1, 2, 6, 7 -> 1
                3, 8 -> 2
                4, 9, 11, 13 -> 4
                5, 10, 12 -> 8
                else -> 0
            }
    }

    /**
     * Where the value of [entry] lives in the block `[start, start + reader.length)`: inline in its
     * value field when it fits in four bytes, else at the offset stored there. Absolute indices;
     * null when the type is unknown or the value runs outside the block.
     */
    fun valueRange(reader: Reader, start: Int, entry: Entry): IntRange? {
        if (entry.unitSize == 0 || entry.count < 0) return null
        val size = entry.count * entry.unitSize
        if (size == 0L) return IntRange.EMPTY
        val at = if (size <= 4) entry.valueField.toLong() else reader.u32(entry.valueField)
        if (at < 0 || at + size > reader.length) return null
        return (start + at).toInt() until (start + at + size).toInt()
    }

    class Ifd(val entries: List<Entry>, val next: Long)

    /** A TIFF block `[start, end)`; offsets are relative to [start]. */
    class Reader private constructor(
        private val b: ByteArray,
        private val start: Int,
        private val end: Int,
        private val little: Boolean,
        val firstIfd: Long,
    ) {
        /** Bytes in the block. */
        val length: Int = end - start

        fun u16(offset: Int): Int = if (little) b.u16le(start + offset) else b.u16be(start + offset)

        fun u32(offset: Int): Long = if (little) b.u32le(start + offset) else b.u32be(start + offset)

        /** The IFD at [offset], or null when it does not fit the block. */
        fun readIfd(offset: Long): Ifd? {
            if (offset < 8 || offset + 2 > length) return null
            val at = offset.toInt()
            val count = u16(at)
            val after = at + 2 + 12 * count
            if (after + 4 > length) return null
            val entries = List(count) { index ->
                val e = at + 2 + 12 * index
                Entry(tag = u16(e), type = u16(e + 2), count = u32(e + 4), valueField = e + 8)
            }
            return Ifd(entries, u32(after))
        }

        companion object {
            fun open(b: ByteArray, start: Int, end: Int): Reader? {
                if (start < 0 || end > b.size || end - start < 8) return null
                val little = when {
                    b.u8(start) == 0x49 && b.u8(start + 1) == 0x49 -> true
                    b.u8(start) == 0x4D && b.u8(start + 1) == 0x4D -> false
                    else -> return null
                }
                val magic = if (little) b.u16le(start + 2) else b.u16be(start + 2)
                if (magic != 42) return null
                val first = if (little) b.u32le(start + 4) else b.u32be(start + 4)
                return Reader(b, start, end, little, first)
            }
        }
    }
}
