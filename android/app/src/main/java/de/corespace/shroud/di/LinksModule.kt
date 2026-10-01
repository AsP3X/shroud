package de.corespace.shroud.di

import android.content.Context
import androidx.browser.customtabs.CustomTabsIntent
import de.corespace.shroud.AppContainer
import de.corespace.shroud.AppModule
import de.corespace.shroud.core.links.LinkOpener
import de.corespace.shroud.core.links.LinkPreviewComposer
import de.corespace.shroud.core.links.LinkPreviewFetcher
import de.corespace.shroud.core.links.LinkPreviewFetching
import de.corespace.shroud.core.links.LinkPreviewImages
import de.corespace.shroud.core.links.LinkPreviewSettings
import de.corespace.shroud.core.links.PublicAddressDns
import kotlinx.coroutines.CoroutineScope
import okhttp3.OkHttpClient

/**
 * Links (00-plan §1.7.9, C2, C32; media-voice-links §10, §13.1). Owner: W2-LINKS.
 *
 * - [settings] — Settings → Privacy → Link previews (a view over `SecurityPreferences`).
 * - [fetcher] — builds previews on this phone, directly from the website (P18, no relay), over
 *   [previewHttp]: its own resolver ([PublicAddressDns]), no cookies, no cache, no proxy, https only.
 * - [newComposer] — one [LinkPreviewComposer] per open conversation (the screen owns and resets it).
 * - [openLink] — Custom Tabs / `mailto` ([LinkOpener]).
 * - Detection is the pure `LinkDetector` object; nothing to build.
 *
 * Nothing here is persisted or holds key material, so there is nothing to wipe: the switch lives in
 * `shroud.preferences`, which the Log Out wipe deletes; previews and dismissed links live in each
 * composer's memory and go with the screen.
 *
 * Only the owner fills this file (00-plan §2.0 rule 3, §2.6); other packages reach these objects
 * through `AppContainer.links` and never construct them.
 */
class LinksModule(container: AppContainer) : AppModule(container) {
    val settings: LinkPreviewSettings by lazy { LinkPreviewSettings(container.keys.securityPreferences) }

    /**
     * The link-preview HTTP client, derived from the app's base client (shared dispatcher) with the
     * rules of [LinkPreviewFetcher.httpClient]. Its connections never mix with the API's: OkHttp
     * pools by address, and the resolver is part of the address.
     */
    val previewHttp: OkHttpClient by lazy { LinkPreviewFetcher.httpClient(container.net.http, PublicAddressDns()) }

    val fetcher: LinkPreviewFetching by lazy { LinkPreviewFetcher(previewHttp, LinkPreviewImages) }

    /**
     * A composer for one conversation screen. [scope] is the screen's main-thread scope (the
     * composer is main-confined, like iOS's `@MainActor` model); the switch is read on every draft
     * change, so turning previews off in Settings takes effect at the next keystroke.
     */
    fun newComposer(scope: CoroutineScope): LinkPreviewComposer =
        LinkPreviewComposer(fetcher = fetcher, scope = scope, isEnabled = { settings.enabled })

    /**
     * Opens a tapped link or a preview's page; false when it is not `http`/`https`/`mailto` or
     * nothing can open it (the caller shows its toast). Pass the activity as [context].
     */
    fun openLink(context: Context, url: String, colorScheme: Int = CustomTabsIntent.COLOR_SCHEME_SYSTEM): Boolean =
        LinkOpener.open(context, url, colorScheme)
}
