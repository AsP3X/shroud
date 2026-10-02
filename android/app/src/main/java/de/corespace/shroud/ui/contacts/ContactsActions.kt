package de.corespace.shroud.ui.contacts

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import de.corespace.shroud.core.messaging.ChatListFormatting
import de.corespace.shroud.core.net.PresenceDto
import de.corespace.shroud.ui.permissions.findActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import java.time.Instant
import java.time.ZoneId
import java.util.Locale

/**
 * Runs [block] in this (the app's) scope and waits for it: an Accept, a block or a chat delete the
 * user started finishes even when the screen that started it goes away meanwhile (iOS `Task {}`
 * outlives its view); only the waiting is cancelled with the screen.
 */
internal suspend fun <T> CoroutineScope.runDetached(block: suspend () -> T): T = async { block() }.await()

/**
 * Puts an invite (link or share code) on the clipboard as plain text (`MyQRCodeSheet.swift:139-143`).
 * Not sensitive — it is meant to be handed out — so no `EXTRA_IS_SENSITIVE` flag and no expiry
 * (contacts §1, unlike the encryption phrase).
 */
internal fun copyInvite(context: Context, value: String) {
    val clipboard = context.getSystemService(ClipboardManager::class.java) ?: return
    clipboard.setPrimaryClip(ClipData.newPlainText(ContactsCopy.CLIP_LABEL, value))
}

/** The share sheet for the invite link, the link only (iOS `ShareLink(item: url)`, `MyQRCodeSheet.swift:65`). */
internal fun shareInvite(context: Context, url: String) {
    val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, url)
    val chooser = Intent.createChooser(send, null)
    if (context.findActivity() == null) chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    try {
        context.startActivity(chooser)
    } catch (_: ActivityNotFoundException) {
        // No app takes text: nothing to share to.
    }
}

/** Presence wording shared by the Contacts rows and the profile (`ChatListFormatting.presenceLabel`). */
internal object ContactStatus {
    /** A row's subtitle: "online", "last seen …", "offline", or "contact" before the server answered (`ContactsView.swift:272-275`). */
    fun row(presence: PresenceDto?, now: Instant, zone: ZoneId, locale: Locale, is24h: Boolean): String =
        ChatListFormatting.presenceLabel(presence, now, zone, locale, is24h) ?: ContactsCopy.CONTACT_FALLBACK_STATUS

    /** The profile's status line: the same words, "Shroud contact" before the server answered (`ContactProfileView.swift:35-37`). */
    fun profile(presence: PresenceDto?, now: Instant, zone: ZoneId, locale: Locale, is24h: Boolean): String =
        ChatListFormatting.presenceLabel(presence, now, zone, locale, is24h) ?: ContactsCopy.PROFILE_FALLBACK_STATUS
}
