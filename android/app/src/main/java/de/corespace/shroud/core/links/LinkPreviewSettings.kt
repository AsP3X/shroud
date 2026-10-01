package de.corespace.shroud.core.links

import de.corespace.shroud.core.storage.SecurityPreferences
import kotlinx.coroutines.flow.StateFlow

/**
 * Settings → Privacy and Security → Link previews (iOS `SecurityPreferences.generatesLinkPreviews`,
 * `ios/shroud/Services/Crypto/SecurityPreferences.swift:23-38`; media-voice-links §10.4, §11):
 * whether this phone builds link previews for the links its user sends. On by default; off, links
 * go out bare and no website is contacted.
 *
 * A view over [SecurityPreferences] (prefs `shroud.preferences`, key
 * `privacy.generateLinkPreviews`, plan C11) — the one owner of that key, so the Privacy screen and
 * the composer never disagree, and Log Out's wipe of the file resets it to on.
 */
class LinkPreviewSettings(private val preferences: SecurityPreferences) {
    /** The switch, live. */
    val changes: StateFlow<Boolean> get() = preferences.generatesLinkPreviews

    /** The switch now; setting it is dropped while a wipe runs (`StorageSeal`). */
    var enabled: Boolean
        get() = preferences.generatesLinkPreviews.value
        set(value) = preferences.setGeneratesLinkPreviews(value)
}
