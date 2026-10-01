package de.corespace.shroud.core.media.video

import java.util.Locale

/**
 * Small pure helpers shared by the planner, the probe and the encoder (media-voice-links §6.2–6.3,
 * conversation-compose-media §15.8). No Android types, so the JVM tests cover them.
 */
internal object VideoFormats {
    /** H.264 — the only video codec a passthrough may carry (media D7). */
    const val MIME_H264 = "video/avc"

    /** AAC — the only audio codec a passthrough may carry (media D7). */
    const val MIME_AAC = "audio/mp4a-latm"

    /** What every encode writes (`VideoMedia.swift:251, 341, 419`). */
    const val MIME_MP4 = "video/mp4"

    /**
     * The "file extension" the plan checks, from a MIME type: content URIs carry no reliable
     * extension on Android, so it comes from the container type (conversation-compose-media §15.8;
     * media-voice-links §6.3). Unknown or missing → "" (never passes through).
     */
    fun extensionForMime(mime: String?): String {
        val type = mime?.substringBefore(';')?.trim()?.lowercase(Locale.ROOT) ?: return ""
        return when (type) {
            "video/mp4", "audio/mp4", "application/mp4" -> "mp4"
            "video/x-m4v", "video/m4v" -> "m4v"
            "video/quicktime" -> "mov"
            "video/3gpp" -> "3gp"
            "video/3gpp2" -> "3g2"
            "video/webm" -> "webm"
            "video/x-matroska", "video/matroska" -> "mkv"
            "video/mp2t" -> "ts"
            "video/avi", "video/x-msvideo" -> "avi"
            else -> ""
        }
    }

    /**
     * The extension at the end of a file name or path, lower-case, or "" — the fallback when a
     * content URI's type is unknown (a `file://` camera capture).
     */
    fun extensionOfName(name: String?): String {
        val last = name?.substringAfterLast('/')?.substringAfterLast('\\') ?: return ""
        val dot = last.lastIndexOf('.')
        if (dot <= 0 || dot == last.length - 1) return ""
        val ext = last.substring(dot + 1).lowercase(Locale.ROOT)
        return if (ext.all { it in 'a'..'z' || it in '0'..'9' }) ext else ""
    }

    /**
     * Android adds a codec rule to passthrough (media D7): the video track must be H.264 and the
     * audio AAC or absent, because web recipients cannot play HEVC everywhere. An unknown video
     * codec, or a clip that has sound but no known audio codec, never passes through.
     */
    fun isPassthroughCodec(videoMime: String?, audioMime: String?, hasAudio: Boolean): Boolean {
        if (!videoMime.equals(MIME_H264, ignoreCase = true)) return false
        if (audioMime == null) return !hasAudio
        return audioMime.equals(MIME_AAC, ignoreCase = true)
    }

    /**
     * Display size from the stored size and the track rotation (`VideoMedia.swift:955-958`): a
     * quarter turn swaps the edges. Each edge is at least 1.
     */
    fun displaySize(width: Int, height: Int, rotationDegrees: Int): Pair<Int, Int> {
        val w = maxOf(1, width)
        val h = maxOf(1, height)
        val quarter = Math.floorMod(rotationDegrees, 180) == 90
        return if (quarter) h to w else w to h
    }

    /**
     * The box a still is drawn into: the longer display edge, no larger than [maxEdgePx] and never
     * larger than the frame (iOS `maximumSize` never upscales, `VideoMedia.swift:441-442`).
     */
    fun frameBox(displayWidth: Int, displayHeight: Int, maxEdgePx: Int): Int {
        val longEdge = maxOf(1, displayWidth, displayHeight)
        return maxOf(1, minOf(longEdge, maxOf(1, maxEdgePx)))
    }
}
