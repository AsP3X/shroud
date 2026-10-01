package de.corespace.shroud.core.links

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.browser.customtabs.CustomTabsClient
import androidx.browser.customtabs.CustomTabsIntent

/**
 * Opens a tapped link the way Telegram does by default — iOS `InAppBrowser`
 * (`ios/shroud/ShroudUI/Components/InAppBrowser.swift:4-44`), plan C32, conversation-thread §6.3
 * and D2, media-voice-links §10.2, web-parity §11 / R-5.
 *
 * - `http`/`https`: a **Custom Tab** in the user's own default browser (out of process — Shroud
 *   cannot read the page and the page cannot see Shroud; never a WebView), with the page title,
 *   the URL bar hiding on scroll (iOS `barCollapsingEnabled`), sharing off (`SHARE_STATE_OFF`) and
 *   **ephemeral browsing** where the browser supports it — the closest Android gets to
 *   SFSafariViewController's own cookie jar (risk R10: elsewhere the tab shares the browser's
 *   profile). No Custom Tabs provider → a plain `ACTION_VIEW`.
 * - `mailto`: `ACTION_SENDTO` to the user's mail app.
 * - anything else: refused (the detector only ever produces these three schemes).
 *
 * No `Referer`: every intent carries an empty `EXTRA_REFERRER`, so a browser that would otherwise
 * derive `android-app://de.corespace.shroud` from the launching package (`Activity.getReferrer()`)
 * has nothing to send — the web opens links with `noreferrer` too (web-parity R-5; verified on a
 * device in acceptance).
 *
 * The scheme is lower-cased before matching (intent filters compare schemes case-sensitively, and
 * `HTTPS://Example.COM` is a link as typed). No network access of its own; URLs are never logged.
 */
object LinkOpener {
    /**
     * Opens [url]; false when it is not a scheme Shroud opens or nothing on the phone can open it
     * (the caller shows its failure toast). [colorScheme] is a `CustomTabsIntent.COLOR_SCHEME_*`
     * matching the app's appearance setting.
     */
    fun open(context: Context, url: String, colorScheme: Int = CustomTabsIntent.COLOR_SCHEME_SYSTEM): Boolean {
        val uri = Uri.parse(url.trim()).normalizeScheme()
        return when (uri.scheme) {
            "http", "https" -> openWeb(context, uri, colorScheme)
            "mailto" -> start(context, Intent(Intent.ACTION_SENDTO, uri).withoutReferrer())
            else -> false
        }
    }

    private fun openWeb(context: Context, uri: Uri, colorScheme: Int): Boolean {
        val provider = try {
            // The default browser when it supports Custom Tabs; null otherwise.
            CustomTabsClient.getPackageName(context, null)
        } catch (_: SecurityException) {
            null
        }
        if (provider != null) {
            val intent = customTabIntent(
                uri = uri,
                provider = provider,
                ephemeral = isEphemeralSupported(context, provider),
                colorScheme = colorScheme,
            )
            if (start(context, intent)) return true
        }
        return start(context, Intent(Intent.ACTION_VIEW, uri).addCategory(Intent.CATEGORY_BROWSABLE).withoutReferrer())
    }

    /** The Custom Tab for [uri] in [provider] (internal for tests). */
    internal fun customTabIntent(uri: Uri, provider: String, ephemeral: Boolean, colorScheme: Int): Intent {
        val builder = CustomTabsIntent.Builder()
            .setShowTitle(true)
            .setUrlBarHidingEnabled(true)
            .setShareState(CustomTabsIntent.SHARE_STATE_OFF)
            .setColorScheme(colorScheme)
        if (ephemeral) builder.setEphemeralBrowsingEnabled(true)
        return builder.build().intent.apply {
            data = uri
            setPackage(provider)
            withoutReferrer()
        }
    }

    private fun isEphemeralSupported(context: Context, provider: String): Boolean =
        try {
            CustomTabsClient.isEphemeralBrowsingSupported(context, provider)
        } catch (_: Exception) {
            false
        }

    private fun Intent.withoutReferrer(): Intent = putExtra(Intent.EXTRA_REFERRER, Uri.EMPTY)

    private fun start(context: Context, intent: Intent): Boolean {
        if (context !is Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            context.startActivity(intent)
            true
        } catch (_: ActivityNotFoundException) {
            false
        } catch (_: SecurityException) {
            false
        }
    }
}
