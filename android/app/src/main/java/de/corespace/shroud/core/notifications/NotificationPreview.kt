package de.corespace.shroud.core.notifications

import de.corespace.shroud.core.net.wire.TextUnits
import de.corespace.shroud.core.net.wire.WireText

/** A message body short enough for a notification. Decrypted on this phone; never sent. */
object NotificationPreview {
    const val MAX_GRAPHEMES = 120

    /** Trimmed text, or null when nothing remains. Longer text keeps the first [MAX_GRAPHEMES] and an ellipsis. */
    fun clip(raw: String?): String? {
        val trimmed = WireText.trimWhitespacesAndNewlines(raw ?: return null)
        if (trimmed.isEmpty()) return null
        // A string no longer than the cap cannot hold more graphemes than the cap.
        if (trimmed.length <= MAX_GRAPHEMES) return trimmed
        val graphemes = TextUnits.current.graphemes(trimmed)
        if (graphemes.size <= MAX_GRAPHEMES) return trimmed
        return graphemes.take(MAX_GRAPHEMES).joinToString("").trimEnd() + "…"
    }
}
