package de.corespace.shroud.ui.onboarding

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.os.PersistableBundle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * The phrase on the clipboard (`EncryptionPhrasePasteboard.swift`; crypto §17.1, C36): marked
 * sensitive so the system hides it in its copy preview and keyboards, and taken off again after a
 * minute.
 *
 * [copy] trims the phrase and refuses a blank one (`:17-19`, `EncryptionPhrasePasteboardTests
 * .copyRejectsEmptyPhrase`); [read] returns the first clip's text trimmed, or null when there is
 * none (`:48-74`).
 *
 * The expiry runs in the app scope, so leaving Sign Up does not cancel it. From Android 10 an app
 * without focus cannot read the clipboard, so when the label cannot be checked (the user is in
 * their password manager) the clip is cleared anyway: losing something copied in the last minute
 * is better than leaving the phrase there. The label is what the Log Out wipe looks for
 * (`AndroidSystemWipe`).
 */
object PhraseClipboard {
    const val LABEL = "Shroud encryption phrase"
    private const val EXPIRY_MS = 60_000L

    /** The text [copy] puts on the clipboard: [phrase] trimmed, or null for a blank one. */
    fun prepared(phrase: String): String? = phrase.trim().takeIf { it.isNotEmpty() }

    fun copy(context: Context, phrase: String, appScope: CoroutineScope): Boolean {
        val text = prepared(phrase) ?: return false
        return runCatching {
            val clipboard = context.applicationContext.getSystemService(ClipboardManager::class.java)
            val clip = ClipData.newPlainText(LABEL, text).apply {
                description.extras = PersistableBundle().apply {
                    val key = if (Build.VERSION.SDK_INT >= 33) ClipDescription.EXTRA_IS_SENSITIVE else "android.content.extra.IS_SENSITIVE"
                    putBoolean(key, true)
                }
            }
            clipboard.setPrimaryClip(clip)
            appScope.launch {
                delay(EXPIRY_MS)
                runCatching {
                    val label = clipboard.primaryClipDescription?.label
                    if (label == null || label == LABEL) clipboard.clearPrimaryClip()
                }
            }
            true
        }.getOrDefault(false)
    }

    fun read(context: Context): String? = runCatching {
        val clipboard = context.getSystemService(ClipboardManager::class.java)
        clipboard.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(context)?.toString()?.trim()?.takeIf { it.isNotEmpty() }
    }.getOrNull()
}
