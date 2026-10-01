package de.corespace.shroud.core.media.video

import android.net.Uri
import de.corespace.shroud.core.model.Bytes

// Video planning types (media-voice-links §6.1, `VideoMedia.swift:17-102`, `PickedMovie.swift:58-74`).
// Published by W1-INT; W2-VIDEO plans, encodes and plays.

/** What the compose screen knows about a picked movie before any work (`VideoMedia.swift:38-49`). */
data class VideoProbe(
    val durationSeconds: Double,
    /** Display size after rotation. */
    val width: Int,
    val height: Int,
    val fileSizeBytes: Long,
    val hasAudio: Boolean,
    val fileExtension: String,
    val videoMime: String?,
    val audioMime: String?,
) {
    /** Width / height, 16:9 when unknown. */
    val aspect: Float get() = if (width > 0 && height > 0) width.toFloat() / height else 16f / 9f
}

/** The slice of a movie the user kept, in seconds (`VideoMedia.swift:18-35`). */
data class VideoTrim(val start: Double, val end: Double) {
    val duration: Double get() = maxOf(0.0, end - start)

    /** The handles still cover (effectively) the whole clip: nothing to cut. */
    fun isFullRange(d: Double): Boolean = start <= 0.05 && end >= d - 0.05

    /** The range to export: at least 0.1 s. */
    val effectiveEnd: Double get() = maxOf(start + 0.1, end)
}

/** What the sender picks; [High] is the default (`VideoMedia.swift:56-82`). */
enum class VideoUploadQuality(val label: String, val hint: String) {
    Original("Original", "Full size"),
    High("High", "720p"),
    Medium("Medium", "540p"),
    Small("Small", "360p"),
}

/** What the compose sheet can promise before an export starts (`VideoMedia.swift:85-96`). */
data class VideoOutgoingPlan(
    val width: Int,
    val height: Int,
    val estimatedBytes: Long,
    /** "720p", or "Original" when that choice keeps the source frame. */
    val resolutionLabel: String,
    val passthrough: Boolean,
    val videoBitrate: Int,
    val audioBitrate: Int,
    /** 0 keeps the source frame rate. */
    val frameRate: Int,
)

/** The chosen quality cannot fit under the media cap (`VideoMedia.swift:99-102`). */
class VideoPlanError(message: String, val maxSeconds: Int) : Exception(message)

/**
 * Why an encode gave up (`VideoMedia.VideoError`, `VideoMedia.swift:110-115`). Thrown by
 * `VideoPipeline.encode` (W2-VIDEO); the send path maps [Reason.TooLarge] to its two "too large"
 * lines and everything else to "Could not prepare that video." (`MessagingController.swift:2630-2639`).
 *
 * A cancelled caller gets its own `CancellationException`, as Kotlin's structured concurrency
 * requires; [Reason.Cancelled] is only thrown when the platform ended an export as cancelled while
 * the caller was still waiting for it.
 */
class VideoException(val reason: Reason, cause: Throwable? = null) : Exception(reason.name, cause) {
    enum class Reason { Unreadable, ExportFailed, TooLarge, Cancelled }

    companion object {
        /**
         * Whether an encode failure is iOS's `VideoError.tooLarge` (`MessagingController.swift:2630`),
         * the send path's "too large" lines; the predicate W2-MSG-SEND's `videoTooLarge` takes.
         */
        fun isTooLarge(error: Throwable): Boolean = error is VideoException && error.reason == Reason.TooLarge
    }
}

/** One video to send, built by the compose screen (`PickedMovie.swift:58-74`). */
data class VideoSendPlan(
    val sourceUri: Uri,
    val caption: String = "",
    val trim: VideoTrim? = null,
    val removeAudio: Boolean = false,
    val quality: VideoUploadQuality = VideoUploadQuality.High,
    val posterJpeg: Bytes? = null,
    val width: Int? = null,
    val height: Int? = null,
    val durationMs: Int? = null,
    val estimatedBytes: Long? = null,
) {
    /** Never prints the caption or the source. */
    override fun toString(): String = "VideoSendPlan(quality=$quality, trim=$trim, removeAudio=$removeAudio)"
}
