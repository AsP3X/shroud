package de.corespace.shroud.ui.media.video

import android.net.Uri
import de.corespace.shroud.core.media.ByteCountLabel
import de.corespace.shroud.core.media.video.VideoOutgoingPlan
import de.corespace.shroud.core.media.video.VideoPlanError
import de.corespace.shroud.core.media.video.VideoPlanner
import de.corespace.shroud.core.media.video.VideoProbe
import de.corespace.shroud.core.media.video.VideoSendPlan
import de.corespace.shroud.core.media.video.VideoTrim
import de.corespace.shroud.core.media.video.VideoUploadQuality
import de.corespace.shroud.core.model.Bytes
import java.util.Locale
import java.util.UUID
import kotlin.coroutines.cancellation.CancellationException

/** One clip as the compose rules see it: its id, probe and where it is read from. */
internal data class ComposeClip(val id: UUID, val probe: VideoProbe, val uri: Uri)

/**
 * The video compose screen's rules (`VideoComposeOverlay`, `ios/shroud/ShroudUI/Components/VideoComposeOverlay.swift`;
 * conversation-compose-media §15.2, §15.6, §15.7). Pure: the screen keeps the state and asks these
 * for every label, the Send gate and the plans it hands out. The numbers come from
 * [VideoPlanner.previewPlan] (K1), exactly as the encoder will use them.
 */
internal object VideoComposeRules {
    /** Filmstrip tiles per clip — enough to read the clip, few enough to generate quickly (`:43`). */
    const val STRIP_TILE_COUNT = 14

    /** At most this many clips in one send (`ConversationView.maxPhotosPerSend`). */
    const val MAX_VIDEOS = 10

    /** How long a banner stays (`flash`, `:739-745`). */
    const val BANNER_MS = 1_400L

    /** The poster JPEG quality (`jpegData(compressionQuality: 0.7)`, `:724-729`). */
    const val POSTER_JPEG_QUALITY = 70

    /** "This video is too long to send." — any planning failure that is not a [VideoPlanError] (`:70-72`). */
    const val TOO_LONG = "This video is too long to send."

    /** Every clip starts with its whole length kept (`:52-55`). */
    fun fullTrim(probe: VideoProbe): VideoTrim = VideoTrim(0.0, probe.durationSeconds)

    /** The plan for [probe] at [quality], or the planner's sentence why it cannot fit (`plan(for:quality:)`, `:60-74`). */
    fun plan(probe: VideoProbe, trim: VideoTrim?, removeAudio: Boolean, quality: VideoUploadQuality): Result<VideoOutgoingPlan> = try {
        Result.success(VideoPlanner.previewPlan(probe = probe, trim = trim, removeAudio = removeAudio, quality = quality))
    } catch (e: CancellationException) {
        throw e
    } catch (e: VideoPlanError) {
        Result.failure(e)
    } catch (e: Exception) {
        Result.failure(VideoPlanError(TOO_LONG, 1))
    }

    /** The sentence of a failed [plan]. */
    fun failureMessage(result: Result<VideoOutgoingPlan>): String? =
        result.exceptionOrNull()?.let { it.message ?: TOO_LONG }

    /**
     * Why Send is held back, or null when every clip fits [quality] (`sendBlocked`, `:82-90`): the
     * first clip that does not fit — its own sentence when it is the clip on screen, else
     * "One video won’t fit at this quality. {sentence}" (U+2019).
     */
    fun sendBlocked(
        clips: List<ComposeClip>,
        currentId: UUID?,
        trims: Map<UUID, VideoTrim>,
        muted: Set<UUID>,
        quality: VideoUploadQuality,
    ): String? {
        for (clip in clips) {
            val message = failureMessage(plan(clip.probe, trims[clip.id] ?: fullTrim(clip.probe), clip.id in muted, quality)) ?: continue
            return if (clip.id == currentId) message else "One video won’t fit at this quality. $message"
        }
        return null
    }

    /**
     * The line under the trim strip (`selectionLabel`, `:92-98`) after the kept length: "Too long" or
     * the plan's resolution and estimated size; null without a plan. The screen joins the two with
     * [SELECTION_SEPARATOR] and rolls only the length.
     */
    fun selectionDetail(plan: Result<VideoOutgoingPlan>?, locale: Locale = Locale.getDefault()): String? {
        if (plan == null) return null
        val ready = plan.getOrNull() ?: return "Too long"
        return "${ready.resolutionLabel}$SELECTION_SEPARATOR≈${ByteCountLabel.format(ready.estimatedBytes, locale)}"
    }

    /** Two spaces each side of every middle dot in the line. */
    const val SELECTION_SEPARATOR = "  ·  "

    /** The menu hint: "Full size" when Original keeps the source frame, else the plan's resolution (`hint(for:plan:)`, `:546-549`). */
    fun hint(quality: VideoUploadQuality, plan: VideoOutgoingPlan): String =
        if (quality == VideoUploadQuality.Original && plan.resolutionLabel == "Original") quality.hint else plan.resolutionLabel

    /** One quality menu row (`qualityChoice`, `:522-544`): "{label} · {hint}  ≈{size}", or "{label} · Too long". */
    fun qualityTitle(quality: VideoUploadQuality, plan: Result<VideoOutgoingPlan>?, locale: Locale = Locale.getDefault()): String {
        val ready = plan?.getOrNull() ?: return "${quality.label} · Too long"
        return "${quality.label} · ${hint(quality, ready)}  ≈${ByteCountLabel.format(ready.estimatedBytes, locale)}"
    }

    /** The banner after a quality change (`:510-519`): "{label} · {hint}", or the label alone when it does not fit. */
    fun qualityBanner(quality: VideoUploadQuality, plan: Result<VideoOutgoingPlan>?): String {
        val ready = plan?.getOrNull() ?: return quality.label
        return "${quality.label} · ${hint(quality, ready)}"
    }

    /** The banner after a mute toggle (`toggleMute`, `:661-662`). */
    fun muteBanner(muted: Boolean): String = if (muted) "Sound will be removed" else "Sound will be kept"

    /** "TRIMMED" and Reset trim show once the kept range is shorter than the clip (`:418`, `:580`). */
    fun isTrimmed(trim: VideoTrim, duration: Double): Boolean = trim.duration < duration - 0.05

    /** The tile of the filmstrip nearest the trim start, for the bubble poster (`posterJPEG`, `:720-730`). */
    fun posterIndex(start: Double, duration: Double, frameCount: Int): Int? {
        if (frameCount <= 0 || !(duration > 0)) return null
        return ((start / duration) * frameCount).toInt().coerceIn(0, frameCount - 1)
    }

    /** Full-range trims for new clips; dropped ones forget theirs (`syncTrims`, `:620-628`). */
    fun syncTrims(clips: List<ComposeClip>, trims: Map<UUID, VideoTrim>): Map<UUID, VideoTrim> =
        clips.associate { it.id to (trims[it.id] ?: fullTrim(it.probe)) }

    fun syncMuted(clips: List<ComposeClip>, muted: Set<UUID>): Set<UUID> = muted.intersect(clips.map { it.id }.toSet())

    /**
     * The clip shown after removing the one at [index] (`removeVideo(at:)`, `:286-292`): removing the
     * clip on screen moves to its right-hand neighbour, or the left one at the end; any other
     * removal keeps [selectedId].
     */
    fun selectionAfterRemoval(ids: List<UUID>, index: Int, selectedIndex: Int, selectedId: UUID?): UUID? {
        if (index != selectedIndex) return selectedId
        return ids.getOrNull(index + 1) ?: ids.getOrNull(index - 1)
    }

    /** Index of the clip on screen: the selected one, else the first (`selection`, `:46-48`). */
    fun selection(ids: List<UUID>, selectedId: UUID?): Int = ids.indexOf(selectedId).takeIf { it >= 0 } ?: 0

    /** The Send button's TalkBack label (`:495`). */
    fun sendLabel(count: Int): String = if (count > 1) "Send $count videos" else "Send video"

    /**
     * One [VideoSendPlan] per clip, in order (`send()`, `:674-707`): the caption on the first clip
     * only, the trim and mute of each, one quality for all, the planner's size when it has one.
     */
    fun plans(
        clips: List<ComposeClip>,
        caption: String,
        trims: Map<UUID, VideoTrim>,
        muted: Set<UUID>,
        quality: VideoUploadQuality,
        poster: (ComposeClip, VideoTrim) -> ByteArray?,
    ): List<VideoSendPlan> {
        val text = caption.trim()
        return clips.mapIndexed { index, clip ->
            val trim = trims[clip.id] ?: fullTrim(clip.probe)
            val removeAudio = clip.id in muted
            val preview = plan(clip.probe, trim, removeAudio, quality).getOrNull()
            VideoSendPlan(
                sourceUri = clip.uri,
                caption = if (index == 0) text else "",
                trim = trim,
                removeAudio = removeAudio,
                quality = quality,
                posterJpeg = poster(clip, trim)?.let(Bytes::adopt),
                width = preview?.width ?: clip.probe.width,
                height = preview?.height ?: clip.probe.height,
                durationMs = maxOf(1, (trim.duration * 1000).toInt()),
                estimatedBytes = preview?.estimatedBytes,
            )
        }
    }
}
