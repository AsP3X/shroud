package de.corespace.shroud.core.media.share

import de.corespace.shroud.core.media.scrub.ImageHeader
import java.nio.charset.StandardCharsets

/** Image or video, from the same magic bytes the scrubber trusts. Unknown bytes are treated as JPEG. */
internal object MediaKind {
    class Kind(val mime: String, val video: Boolean, val extension: String)

    fun of(data: ByteArray): Kind {
        val image = ImageHeader.container(data)
        if (image != null) return Kind(image.mime, video = false, extension = extension(image.mime))
        val video = videoMime(data)
        if (video != null) return Kind(video, video = true, extension = extension(video))
        return Kind("image/jpeg", video = false, extension = "jpg")
    }

    private fun videoMime(data: ByteArray): String? {
        if (data.size >= 4 && data[0] == 0x1A.toByte() && data[1] == 0x45.toByte() && data[2] == 0xDF.toByte() && data[3] == 0xA3.toByte()) {
            return "video/webm"
        }
        if (data.size < 12) return null
        if (data[4] != 'f'.code.toByte() || data[5] != 't'.code.toByte() || data[6] != 'y'.code.toByte() || data[7] != 'p'.code.toByte()) {
            return null
        }
        val brand = String(data, 8, 4, StandardCharsets.ISO_8859_1)
        return when (brand) {
            "qt  " -> "video/quicktime"
            "3gp4", "3gp5", "3gp6", "3ge6", "3gg6" -> "video/3gpp"
            else -> "video/mp4"
        }
    }

    private fun extension(mime: String): String = when (mime.substringBefore(';')) {
        "image/jpeg" -> "jpg"
        "image/png" -> "png"
        "image/heic" -> "heic"
        "image/heif" -> "heif"
        "image/webp" -> "webp"
        "image/gif" -> "gif"
        "video/quicktime" -> "mov"
        "video/webm" -> "webm"
        "video/3gpp" -> "3gp"
        else -> if (mime.startsWith("video/")) "mp4" else "jpg"
    }
}
