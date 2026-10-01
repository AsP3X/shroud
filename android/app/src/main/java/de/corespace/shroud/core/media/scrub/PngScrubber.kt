package de.corespace.shroud.core.media.scrub

import java.io.ByteArrayOutputStream
import java.util.zip.CRC32

/**
 * PNG metadata scrubbing (media-voice-links §5.2). iOS re-encodes every PNG frame losslessly because
 * ImageIO copies PNGs unchanged, keeping orientation and APNG loop count and frame delays
 * (`ios/shroud/Services/Crypto/MediaMetadataScrubber.swift:94-129`). PNG has no cross-chunk offsets,
 * so Android rebuilds the chunk list instead — the pixels (`IDAT`/`fdAT`) are copied untouched:
 *
 * - kept: `IHDR PLTE IDAT IEND tRNS gAMA cHRM sRGB iCCP sBIT pHYs bKGD hIST sPLT cICP mDCv cLLi`
 *   and the APNG chunks `acTL fcTL fdAT` (an animated PNG keeps its frames and timing);
 * - dropped: `tEXt zTXt iTXt` (author, comment, creation time, XMP `XML:com.adobe.xmp`…), `tIME`,
 *   every unknown ancillary chunk (C2PA `caBX`, Apple `iDOT`, …), anything after `IEND`;
 * - `eXIf`: dropped, and a minimal one holding only the orientation re-emitted right after `IHDR`
 *   when the source's orientation is not 1 (CRC recomputed);
 * - an unknown **critical** chunk (upper-case first letter): null — the caller re-encodes.
 */
internal object PngScrubber {
    private val SIGNATURE = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)

    private val KEPT: Set<String> = setOf(
        "IHDR", "PLTE", "IDAT", "IEND", "tRNS", "gAMA", "cHRM", "sRGB", "iCCP", "sBIT", "pHYs", "bKGD",
        "hIST", "sPLT", "cICP", "mDCv", "cLLi", "acTL", "fcTL", "fdAT",
    )

    /** One chunk: [start] is its length field, data `[data, data + length)`, then 4 CRC bytes. */
    class Chunk(val type: String, val start: Int, val length: Int) {
        val data: Int get() = start + 8
        val end: Int get() = start + 12 + length
        val isCritical: Boolean get() = type[0].isUpperCase()
    }

    /** True when [data] starts with the PNG signature. */
    fun isPng(data: ByteArray): Boolean = data.hasBytes(0, SIGNATURE)

    /** The chunks up to and including `IEND`, or null when the structure is broken. */
    fun chunks(b: ByteArray): List<Chunk>? {
        if (!isPng(b)) return null
        val result = ArrayList<Chunk>()
        var i = SIGNATURE.size
        while (i + 12 <= b.size) {
            val length = b.u32be(i)
            if (length > Int.MAX_VALUE.toLong() || i + 12L + length > b.size) return null
            val chunk = Chunk(b.fourCc(i + 4), i, length.toInt())
            result += chunk
            if (result.size == 1 && chunk.type != "IHDR") return null
            if (chunk.type == "IEND") return result
            i = chunk.end
        }
        return null
    }

    /** The clean file, or null when it could not be cleaned without re-encoding. */
    fun scrub(data: ByteArray): ByteArray? {
        val chunks = chunks(data) ?: return null
        var orientation = 1
        for (chunk in chunks) {
            if (chunk.type == "eXIf") {
                orientation = MinimalTiff.orientation(data, chunk.data, chunk.data + chunk.length) ?: return null
            }
            if (chunk.isCritical && chunk.type !in KEPT) return null
        }
        val out = ByteArrayOutputStream(data.size)
        out.write(SIGNATURE)
        for (chunk in chunks) {
            if (chunk.type in KEPT) out.write(data, chunk.start, chunk.end - chunk.start)
            if (chunk.type == "IHDR" && orientation != 1) writeChunk(out, "eXIf", minimalExif(orientation))
        }
        return out.toByteArray()
    }

    /** Everything in [data] that is not on the allow-list, as readable names. Empty = clean. */
    fun leftovers(data: ByteArray): List<String> {
        val chunks = chunks(data) ?: return listOf("PNG.unreadable")
        val found = ArrayList<String>()
        for (chunk in chunks) {
            when {
                chunk.type == "eXIf" -> found += MinimalTiff.audit(data, chunk.data, chunk.data + chunk.length, "PNG.eXIf")
                chunk.type !in KEPT -> found += "PNG." + chunk.type
            }
        }
        if (chunks.last().end < data.size) found += "PNG.trailing"
        return found
    }

    private fun minimalExif(orientation: Int): ByteArray {
        val tiff = ByteArray(MinimalTiff.minimalSize(orientation))
        MinimalTiff.writeMinimal(tiff, 0, tiff.size, orientation)
        return tiff
    }

    private fun writeChunk(out: ByteArrayOutputStream, type: String, body: ByteArray) {
        val header = ByteArray(8)
        header.putU32be(0, body.size.toLong())
        for (k in 0 until 4) header[4 + k] = type[k].code.toByte()
        val crc = CRC32()
        crc.update(header, 4, 4)
        crc.update(body)
        val tail = ByteArray(4)
        tail.putU32be(0, crc.value)
        out.write(header)
        out.write(body)
        out.write(tail)
    }
}
