package de.corespace.shroud.core.media.scrub

/**
 * Removes location, capture time and device details from photos before they are sent — Android's
 * `MediaMetadataScrubber` (`ios/shroud/Services/Crypto/MediaMetadataScrubber.swift:6-277`,
 * media-voice-links §5, D5).
 *
 * Human: an "Original" photo is the file itself, and a camera photo carries where it was taken
 * (GPS), when, and which phone, lens and software took it. None of that belongs in a chat. What stays
 * is only what a viewer needs to draw the picture right: orientation, the colour profile and HDR gain
 * maps. Pixels are never re-compressed on this path.
 *
 * Agent: pure functions on bytes, no Android APIs, no I/O, no logging. [scrubImage] returns null when
 * the result could not be proven clean — callers must then re-encode (which carries no metadata)
 * instead of shipping the original. Unlike iOS (ImageIO's lossless copy), Android rewrites metadata
 * byte by byte with the same lengths ([JpegScrubber], [HeifScrubber]) or rebuilds the chunk list
 * ([PngScrubber]), then verifies the result with [leftoverMetadata] exactly as iOS does (`:38-42`).
 * Video metadata is the video area's job (W2-VIDEO, media-voice-links §5.3).
 */
object MediaMetadataScrubber {
    /** The image with only rendering metadata left, or null if that could not be achieved (`:22-43`). */
    fun scrubImage(data: ByteArray): ByteArray? {
        val clean = when {
            JpegScrubber.isJpeg(data) -> JpegScrubber.scrub(data)
            PngScrubber.isPng(data) -> PngScrubber.scrub(data)
            HeifScrubber.isHeif(data) -> HeifScrubber.scrub(data)
            else -> null
        } ?: return null
        return if (leftoverMetadata(clean).isEmpty()) clean else null
    }

    /**
     * Everything in [data] that isn't on the allow-list, as readable names (`GPS`, `TIFF.Model`,
     * `Exif.BodySerialNumber`, `MakerNote`, `XMP.photoshop:City`, `JPEG.APP13`, `PNG.tEXt`,
     * `HEIF.mpvd`, …). Empty = clean (`:45-68`). Never contains metadata values, only names.
     */
    fun leftoverMetadata(data: ByteArray): List<String> = when {
        JpegScrubber.isJpeg(data) -> JpegScrubber.leftovers(data)
        PngScrubber.isPng(data) -> PngScrubber.leftovers(data)
        HeifScrubber.isHeif(data) -> HeifScrubber.leftovers(data)
        else -> listOf("unreadable")
    }

    /**
     * `prefix:Name[0].field` → allowed when it describes the picture itself (`:170-182`): the
     * qualified name up to the first `[`, `.` or `/` needs a prefix; allowed for `hdrgm`, `HDRGainMap`
     * and `iio`, for `tiff:` plus an allowed TIFF key and `exif:` plus an allowed Exif key.
     */
    fun isAllowedTagPath(path: String): Boolean {
        val qualified = path.takeWhile { it != '[' && it != '.' && it != '/' }
        val colon = qualified.indexOf(':')
        if (colon < 0) return false
        val prefix = qualified.substring(0, colon)
        if (prefix in XmpBlanker.KEPT_PREFIXES || prefix == "iio") return true
        val local = qualified.substring(colon + 1)
        return when (prefix) {
            "tiff" -> local in MinimalTiff.ALLOWED_TIFF_TAGS.values
            "exif" -> local in MinimalTiff.ALLOWED_EXIF_TAGS.values
            else -> false
        }
    }

    /**
     * Overwrites every disallowed property in every XMP packet of [data] with spaces, in place
     * (`blankXMP`, `:192-210`). Byte length never changes.
     */
    fun blankXmp(data: ByteArray) = XmpBlanker.blank(data)

    /**
     * Turns the primary image's `APP13` segments (Photoshop resources: IPTC city, country, creator,
     * dates) into comments of spaces, in place (`blankJPEGIPTC`, `:212-238`). [JpegScrubber] does this
     * for every non-allowed segment; kept as the iOS entry point for its tests.
     */
    fun blankJpegIptc(data: ByteArray) {
        if (data.size <= 4 || data.u8(0) != 0xFF || data.u8(1) != 0xD8) return
        var index = 2
        while (index + 4 <= data.size && data.u8(index) == 0xFF) {
            val marker = data.u8(index + 1)
            if (marker == 0xDA || marker == 0xD9) break
            if (marker == 0xFF) {
                index += 1
                continue
            }
            if (marker in 0xD0..0xD7 || marker == 0x01) {
                index += 2
                continue
            }
            val length = data.u16be(index + 2)
            if (length < 2 || index + 2 + length > data.size) break
            if (marker == 0xED) {
                data[index + 1] = 0xFE.toByte()
                data.fill(SPACE, index + 4, index + 2 + length)
            }
            index += 2 + length
        }
    }
}
