package de.corespace.shroud.core.media.files

import java.text.Normalizer
import java.util.Locale

// Audio files (docs/file-sharing.md §11): the rules every client shares — tag cleaning, the display
// title, the duration labels and the copy. Pure, so the `== audio … ==` vectors of §9 pin them on the JVM.

/**
 * §11.2 tag text (`ti`, `ar`), cleaned on the sender before sealing and again on the receiver: NFC;
 * the §5 step-3 code points dropped; each run of §5 step-5 spaces one U+0020 (none at the start); cut
 * to [MAX_CODE_POINTS] code points; trailing spaces trimmed; empty → null (absent).
 */
object AudioTags {
    const val MAX_CODE_POINTS = 200

    private const val SPACE = 0x20

    fun clean(raw: String?): String? {
        if (raw == null) return null
        val text = Normalizer.normalize(raw, Normalizer.Form.NFC)
        val points = ArrayList<Int>(minOf(text.length, MAX_CODE_POINTS + 1))
        var index = 0
        while (index < text.length) {
            val cp = text.codePointAt(index)
            index += Character.charCount(cp)
            when {
                FileNames.isRemoved(cp) -> Unit
                FileNames.isSpace(cp) -> if (points.isNotEmpty() && points.last() != SPACE) points += SPACE
                else -> points += cp
            }
        }
        val cut = if (points.size > MAX_CODE_POINTS) points.subList(0, MAX_CODE_POINTS) else points
        var end = cut.size
        while (end > 0 && cut[end - 1] == SPACE) end--
        if (end == 0) return null
        return buildString { for (i in 0 until end) appendCodePoint(cut[i]) }
    }
}

/**
 * §11.2 durations: `m:ss`, from one hour `h:mm:ss`. A total (`d`) rounds to whole seconds (half up,
 * as JavaScript's `Math.round`), an elapsed time floors.
 */
object AudioDurations {
    /** The label of a whole length in ms, rounded: `499 ms` → `0:00`, `500 ms` → `0:01`. */
    fun total(ms: Long): String = format((ms.coerceAtLeast(0) + 500) / 1000)

    /** The label of a playhead in ms, floored: `59 999 ms` → `0:59`. */
    fun elapsed(ms: Long): String = format(ms.coerceAtLeast(0) / 1000)

    /** `m:ss` or `h:mm:ss` of whole [seconds] (negative reads as 0). */
    fun format(seconds: Long): String {
        val s = seconds.coerceAtLeast(0)
        val h = s / 3600
        val m = (s % 3600) / 60
        val r = s % 60
        return if (h > 0) String.format(Locale.ROOT, "%d:%02d:%02d", h, m, r) else String.format(Locale.ROOT, "%d:%02d", m, r)
    }
}

/** The words of audio files (§1, §7, §11), word for word. */
object AudioFileCopy {
    /** The reply quote's label and the bubble's spoken kind. */
    const val AUDIO = "Audio"

    /** The fallback bubble's line (§11.4) for a file this phone's player can't open. */
    const val CANT_PLAY = "Can't play on this phone"

    /** The now-playing bar's labels (§11.6). */
    const val PLAY = "Play"
    const val PAUSE = "Pause"
    const val PLAYBACK_SPEED = "Playback speed"
    const val STOP = "Stop"

    /** The chat list's and the notification's mark before an audio file's display title (§7 "Elsewhere"). */
    const val PREVIEW_MARK = "🎵"

    /** `{ti} – {ar}` when both are there, else `ti`, else the cleaned file name (§11.2). Both tags are cleaned here again. */
    fun displayTitle(title: String?, artist: String?, fileName: String): String {
        val t = AudioTags.clean(title)
        val a = AudioTags.clean(artist)
        if (t != null && a != null) return "$t – $a"
        return t ?: FileNames.clean(fileName)
    }

    /** The chat list's and the notification's line for an audio file without a caption: `🎵 {display title}`. */
    fun preview(displayTitle: String): String = "$PREVIEW_MARK $displayTitle"

    /** The composer row's line (§11.3): `{ar} · {duration} · {size} · {EXT}`, leaving out what is missing. */
    fun composerMeta(artist: String?, durationMs: Long?, size: String?, extensionLabel: String): String =
        listOfNotNull(AudioTags.clean(artist), durationMs?.takeIf { it >= 1 }?.let(AudioDurations::total), size, extensionLabel)
            .joinToString(" · ")

    /** `Audio, {title}, {artist}, {duration}` without the missing parts (§11.4). */
    fun accessibilityLabel(title: String, artist: String?, durationMs: Long?): String =
        listOfNotNull(AUDIO, title, AudioTags.clean(artist), durationMs?.takeIf { it >= 1 }?.let(AudioDurations::total)).joinToString(", ")

    /** The now-playing bar's second line (§11.6): `{ar} · {elapsed} / {duration}`, leaving out what is missing. */
    fun nowPlayingDetail(artist: String?, elapsedMs: Long, durationMs: Long?): String =
        listOfNotNull(AudioTags.clean(artist), progress(elapsedMs, durationMs)).joinToString(" · ")

    /** `{elapsed} / {duration}`, or just the elapsed time without a duration. */
    fun progress(elapsedMs: Long, durationMs: Long?): String {
        val elapsed = AudioDurations.elapsed(elapsedMs)
        val total = durationMs?.takeIf { it >= 1 } ?: return elapsed
        return "$elapsed / ${AudioDurations.total(total)}"
    }
}
