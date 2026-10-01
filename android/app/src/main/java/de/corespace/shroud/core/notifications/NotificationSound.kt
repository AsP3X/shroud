package de.corespace.shroud.core.notifications

import androidx.annotation.RawRes
import de.corespace.shroud.R

/**
 * The notification sounds (iOS `NotificationSound`, `ios/shroud/Services/Notifications/NotificationSound.swift:9-41`;
 * notifications-push §5.5, settings-lock §6.3): the phone's own tone, the five tones
 * `scripts/gen_notification_sounds.py` makes (bundled as `res/raw/<raw>.wav`, the same bytes the
 * iOS app and the web play), or none.
 *
 * [raw] is the stored preference value and the server's name for it (`serverName`,
 * `NotificationSound.swift:33`; `PUT /notifications/settings` `sound`). [title] is the picker
 * copy. Declaration order is the picker order (`:9-17`).
 */
enum class NotificationSound(val raw: String, val title: String, @param:RawRes val resId: Int?) {
    /** The phone's notification sound (`Settings.System.DEFAULT_NOTIFICATION_URI`); iOS plays its tri-tone (`:48-50`). */
    Standard("default", "Default", null),
    Note("note", "Note", R.raw.note),
    Chime("chime", "Chime", R.raw.chime),
    Glass("glass", "Glass", R.raw.glass),
    Pop("pop", "Pop", R.raw.pop),
    Pulse("pulse", "Pulse", R.raw.pulse),

    /** Silent. */
    None("none", "None", null),
    ;

    /** What `PUT /notifications/settings` stores (`serverName`, `:33`). */
    val serverName: String get() = raw

    companion object {
        /** The sound stored as [raw]; an unknown or absent value reads as [Standard] (`NotificationPreferences.swift:63`). */
        fun fromRaw(raw: String?): NotificationSound = entries.firstOrNull { it.raw == raw } ?: Standard
    }
}
