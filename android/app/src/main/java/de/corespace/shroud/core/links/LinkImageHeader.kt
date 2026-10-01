package de.corespace.shroud.core.links

/**
 * The size an image file declares, read from its header alone — a port of web `imageDimensions`
 * (`web/src/linkPreview/images.ts:25-76`), media-voice-links §10.5. Pure, no decoding: a few
 * kilobytes of PNG or WebP can declare a 20 000 × 20 000 canvas, and that is refused before any
 * pixel is touched ([LinkPreviewImages.isDecodableImageSize]).
 *
 * PNG (IHDR), GIF (logical screen), WebP (VP8 / VP8L / VP8X) and JPEG (first start-of-frame
 * marker); null for anything else or a truncated header.
 */
object LinkImageHeader {
    /** Declared pixel size; `Long` because PNG sizes are unsigned 32-bit. */
    data class Size(val width: Long, val height: Long)

    fun dimensions(bytes: ByteArray): Size? {
        // PNG: signature, then the IHDR chunk.
        if (bytes.size >= 24 && u8(bytes, 0) == 0x89 && ascii(bytes, 1, 3) == "PNG" && ascii(bytes, 12, 4) == "IHDR") {
            return Size(u32be(bytes, 16), u32be(bytes, 20))
        }
        // GIF: logical screen size.
        if (bytes.size >= 10 && (ascii(bytes, 0, 6) == "GIF87a" || ascii(bytes, 0, 6) == "GIF89a")) {
            return Size(u16le(bytes, 6).toLong(), u16le(bytes, 8).toLong())
        }
        // WebP: RIFF container, VP8 / VP8L / VP8X.
        if (bytes.size >= 30 && ascii(bytes, 0, 4) == "RIFF" && ascii(bytes, 8, 4) == "WEBP") {
            return when (ascii(bytes, 12, 4)) {
                "VP8X" -> Size(u24le(bytes, 24) + 1L, u24le(bytes, 27) + 1L)
                "VP8 " -> Size((u16le(bytes, 26) and 0x3FFF).toLong(), (u16le(bytes, 28) and 0x3FFF).toLong())
                "VP8L" -> {
                    val bits = u8(bytes, 21) or (u8(bytes, 22) shl 8) or (u8(bytes, 23) shl 16) or (u8(bytes, 24) shl 24)
                    Size((bits and 0x3FFF) + 1L, ((bits ushr 14) and 0x3FFF) + 1L)
                }
                else -> null
            }
        }
        // JPEG: walk the markers to the first start-of-frame.
        if (bytes.size >= 4 && u8(bytes, 0) == 0xFF && u8(bytes, 1) == 0xD8) {
            var offset = 2
            while (offset + 9 <= bytes.size) {
                if (u8(bytes, offset) != 0xFF) return null
                val marker = u8(bytes, offset + 1)
                if (marker == 0xFF) {
                    offset += 1 // fill byte
                    continue
                }
                if (marker == 0xD8 || marker == 0x01 || marker in 0xD0..0xD7) {
                    offset += 2 // markers without a length
                    continue
                }
                val isFrame = marker in 0xC0..0xCF && marker != 0xC4 && marker != 0xC8 && marker != 0xCC
                if (isFrame) return Size(u16be(bytes, offset + 7).toLong(), u16be(bytes, offset + 5).toLong())
                offset += 2 + u16be(bytes, offset + 2)
            }
            return null
        }
        return null
    }

    private fun u8(b: ByteArray, i: Int): Int = b[i].toInt() and 0xFF
    private fun u16be(b: ByteArray, i: Int): Int = (u8(b, i) shl 8) or u8(b, i + 1)
    private fun u16le(b: ByteArray, i: Int): Int = u8(b, i) or (u8(b, i + 1) shl 8)
    private fun u24le(b: ByteArray, i: Int): Int = u8(b, i) or (u8(b, i + 1) shl 8) or (u8(b, i + 2) shl 16)
    private fun u32be(b: ByteArray, i: Int): Long = (u16be(b, i).toLong() shl 16) or u16be(b, i + 2).toLong()
    private fun ascii(b: ByteArray, i: Int, n: Int): String = String(CharArray(n) { (b[i + it].toInt() and 0xFF).toChar() })
}
