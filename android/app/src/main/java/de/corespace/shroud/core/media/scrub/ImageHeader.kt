package de.corespace.shroud.core.media.scrub

/** Image container formats told apart by their first bytes. */
enum class ImageContainer(val mime: String, val allowsPassthrough: Boolean) {
    Jpeg("image/jpeg", true),
    Png("image/png", true),
    Heic("image/heic", true),
    Heif("image/heif", true),
    Avif("image/avif", false),
    Gif("image/gif", false),
    WebP("image/webp", false),
    Bmp("image/bmp", false),
    Tiff("image/tiff", false),
}

/**
 * What an encoded image is and how big it will be drawn, read from its header without decoding:
 * the Android side of iOS `MediaCrypto.mimeType(for:)` / `pixelSize(for:)`
 * (`ios/shroud/Services/Crypto/MediaCrypto.swift:109-123, :203-212`) and of the web's
 * `displayPixelSize` / `imageDimensions` (`web/src/media/prepareImage.ts:95-103`,
 * `web/src/linkPreview/images.ts:35-76`). Pure; JVM-testable.
 */
object ImageHeader {
    private val HEIC_BRANDS = setOf("heic", "heix", "heim", "heis", "hevc", "hevx", "hevm", "hevs")
    private val HEIF_BRANDS = setOf("mif1", "msf1", "mif2", "miaf")
    private val AVIF_BRANDS = setOf("avif", "avis")

    /**
     * The container of [data] by magic bytes (media-voice-links §4.1): JPEG `FF D8 FF`, PNG signature,
     * HEIF by `ftyp` brand (major brand first, then the compatible ones), GIF, WebP, BMP, TIFF.
     */
    fun container(data: ByteArray): ImageContainer? {
        if (data.size >= 3 && data.u8(0) == 0xFF && data.u8(1) == 0xD8 && data.u8(2) == 0xFF) return ImageContainer.Jpeg
        if (PngScrubber.isPng(data)) return ImageContainer.Png
        IsoBmff.brands(data)?.let { return heifContainer(it) }
        if (data.hasAscii(0, "GIF87a") || data.hasAscii(0, "GIF89a")) return ImageContainer.Gif
        if (data.size >= 12 && data.hasAscii(0, "RIFF") && data.hasAscii(8, "WEBP")) return ImageContainer.WebP
        if (data.hasAscii(0, "BM") && data.size >= 26) return ImageContainer.Bmp
        if (data.hasAscii(0, "II*\u0000") || data.hasAscii(0, "MM\u0000*")) return ImageContainer.Tiff
        return null
    }

    private fun heifContainer(brands: List<String>): ImageContainer? {
        val major = brands.first()
        return when {
            major in HEIC_BRANDS -> ImageContainer.Heic
            major in AVIF_BRANDS -> ImageContainer.Avif
            major in HEIF_BRANDS -> when {
                brands.any { it in AVIF_BRANDS } -> ImageContainer.Avif
                brands.any { it in HEIC_BRANDS } -> ImageContainer.Heic
                else -> ImageContainer.Heif
            }
            else -> null
        }
    }

    /** Container MIME type of encoded bytes, `image/jpeg` when unknown (`MediaCrypto.swift:110-117`). */
    fun mimeType(data: ByteArray): String = container(data)?.mime ?: "image/jpeg"

    /**
     * Pixel size as the recipient will draw it, from the header alone (`MediaCrypto.swift:203-212`):
     * EXIF orientations 5…8 (HEIF: `irot` 90°/270°) swap the axes. Null when the header does not say.
     */
    fun pixelSize(data: ByteArray): Pair<Int, Int>? {
        val size = when (container(data)) {
            ImageContainer.Jpeg -> jpegSize(data)
            ImageContainer.Png -> pngSize(data)
            ImageContainer.Heic, ImageContainer.Heif, ImageContainer.Avif -> heifSize(data)
            ImageContainer.Gif -> if (data.size >= 10) data.u16le(6) to data.u16le(8) else null
            ImageContainer.WebP -> webpSize(data)
            else -> null
        } ?: return null
        return if (size.first > 0 && size.second > 0) size else null
    }

    /** First start-of-frame size, swapped for orientations 5…8 of the first `APP1 Exif`. */
    private fun jpegSize(b: ByteArray): Pair<Int, Int>? {
        var orientation = 1
        var offset = 2
        while (offset + 4 <= b.size) {
            if (b.u8(offset) != 0xFF) return null
            val marker = b.u8(offset + 1)
            if (marker == 0xFF) {
                offset += 1
                continue
            }
            if (marker == 0xD8 || marker == 0x01 || marker in 0xD0..0xD7) {
                offset += 2
                continue
            }
            // Like the web's `imageDimensions`, a start-of-frame needs only its size fields to be present.
            val isFrame = marker in 0xC0..0xCF && marker != 0xC4 && marker != 0xC8 && marker != 0xCC
            if (isFrame) {
                if (offset + 9 > b.size) return null
                return oriented(b.u16be(offset + 7), b.u16be(offset + 5), orientation)
            }
            if (marker == 0xDA || marker == 0xD9) return null
            val length = b.u16be(offset + 2)
            if (length < 2 || offset + 2 + length > b.size) return null
            if (marker == 0xE1 && orientation == 1 && b.hasAscii(offset + 4, "Exif\u0000\u0000", offset + 2 + length)) {
                orientation = MinimalTiff.orientation(b, offset + 10, offset + 2 + length) ?: 1
            }
            offset += 2 + length
        }
        return null
    }

    /** `IHDR` size, swapped for orientations 5…8 of an `eXIf` chunk. */
    private fun pngSize(b: ByteArray): Pair<Int, Int>? {
        val chunks = PngScrubber.chunks(b) ?: return null
        val header = chunks.first()
        if (header.length < 8) return null
        val width = b.u32be(header.data)
        val height = b.u32be(header.data + 4)
        if (width > Int.MAX_VALUE || height > Int.MAX_VALUE) return null
        val exif = chunks.firstOrNull { it.type == "eXIf" }
        val orientation = exif?.let { MinimalTiff.orientation(b, it.data, it.data + it.length) } ?: 1
        return oriented(width.toInt(), height.toInt(), orientation)
    }

    /** The primary item's `ispe`, swapped when its `irot` turns it by 90° or 270°. */
    private fun heifSize(b: ByteArray): Pair<Int, Int>? {
        val heif = IsoBmff.parseHeif(b) ?: return null
        val primary = heif.primaryItem ?: return null
        var size: Pair<Int, Int>? = null
        var quarterTurns = 0
        for (property in IsoBmff.propertiesOf(heif, primary)) {
            when (property.type) {
                "ispe" -> if (property.payload + 12 <= property.end) {
                    val width = b.u32be(property.payload + 4)
                    val height = b.u32be(property.payload + 8)
                    if (width <= Int.MAX_VALUE && height <= Int.MAX_VALUE) size = width.toInt() to height.toInt()
                }
                "irot" -> if (property.payload < property.end) quarterTurns = b.u8(property.payload) and 0x03
            }
        }
        val (width, height) = size ?: return null
        return if (quarterTurns % 2 == 1) height to width else width to height
    }

    private fun webpSize(b: ByteArray): Pair<Int, Int>? {
        if (b.size < 30) return null
        return when (b.fourCc(12)) {
            "VP8X" -> (b.u24le(24) + 1) to (b.u24le(27) + 1)
            "VP8 " -> (b.u16le(26) and 0x3FFF) to (b.u16le(28) and 0x3FFF)
            "VP8L" -> {
                val bits = b.u32le(21)
                ((bits and 0x3FFF) + 1).toInt() to (((bits ushr 14) and 0x3FFF) + 1).toInt()
            }
            else -> null
        }
    }

    private fun oriented(width: Int, height: Int, orientation: Int): Pair<Int, Int> =
        if (orientation in 5..8) height to width else width to height
}
