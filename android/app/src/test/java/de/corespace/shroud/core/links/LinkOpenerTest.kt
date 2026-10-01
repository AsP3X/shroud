package de.corespace.shroud.core.links

import android.app.Application
import android.content.ComponentName
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import androidx.browser.customtabs.CustomTabsIntent
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Opening links (iOS `InAppBrowser.swift:11-33`; plan C32, conversation-thread §6.3 D2,
 * media-voice-links §10.2, web-parity R-5): web links go to a Custom Tab of the default browser —
 * title shown, sharing off, ephemeral where the browser offers it, no referrer — or a plain
 * `ACTION_VIEW` without a Custom Tabs browser; `mailto:` goes to `ACTION_SENDTO`; nothing else
 * opens. Robolectric checks the intents; that a real browser honours them is device acceptance.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class LinkOpenerTest {
    private val app: Application = ApplicationProvider.getApplicationContext()
    private val browser = "org.example.browser"

    @Before
    fun setUp() {
        shadowOf(app).clearNextStartedActivities()
    }

    /** A default browser for `http://` that offers Custom Tabs, and optionally ephemeral browsing. */
    private fun installCustomTabsBrowser(ephemeral: Boolean) {
        val pm = shadowOf(app.packageManager)
        val activity = ComponentName(browser, "$browser.Main")
        pm.addActivityIfNotPresent(activity)
        pm.addIntentFilterForActivity(
            activity,
            IntentFilter(Intent.ACTION_VIEW).apply {
                addCategory(Intent.CATEGORY_DEFAULT)
                addCategory(Intent.CATEGORY_BROWSABLE)
                addDataScheme("http")
                addDataScheme("https")
            },
        )
        val service = ComponentName(browser, "$browser.CustomTabs")
        pm.addServiceIfNotPresent(service)
        pm.addIntentFilterForService(
            service,
            IntentFilter("android.support.customtabs.action.CustomTabsService").apply {
                if (ephemeral) addCategory("androidx.browser.customtabs.category.EphemeralBrowsing")
            },
        )
    }

    private fun started(): Intent? = shadowOf(app).nextStartedActivity

    @Test
    fun mailtoGoesToTheMailApp() {
        assertTrue(LinkOpener.open(app, "mailto:bob@example.com"))
        val intent = started()!!
        assertEquals(Intent.ACTION_SENDTO, intent.action)
        assertEquals("mailto:bob@example.com", intent.dataString)
        assertEquals(Uri.EMPTY, intent.getParcelableExtra(Intent.EXTRA_REFERRER, Uri::class.java))
    }

    @Test
    fun otherSchemesAreRefused() {
        for (url in listOf("ftp://example.com/", "javascript:alert(1)", "file:///etc/passwd", "tel:123", "intent://x#Intent;end", "")) {
            assertFalse(url, LinkOpener.open(app, url))
        }
        assertNull(started())
    }

    @Test
    fun withoutACustomTabsBrowserAPlainViewOpens() {
        assertTrue(LinkOpener.open(app, "https://example.com/a"))
        val intent = started()!!
        assertEquals(Intent.ACTION_VIEW, intent.action)
        assertEquals("https://example.com/a", intent.dataString)
        assertTrue(intent.hasCategory(Intent.CATEGORY_BROWSABLE))
        assertEquals("no referrer", Uri.EMPTY, intent.getParcelableExtra(Intent.EXTRA_REFERRER, Uri::class.java))
        assertFalse(intent.hasExtra(CustomTabsIntent.EXTRA_SESSION))
        assertTrue("started from the application", intent.flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0)
    }

    @Test
    fun aCustomTabsBrowserGetsACustomTab() {
        installCustomTabsBrowser(ephemeral = false)
        assertTrue(LinkOpener.open(app, "https://example.com/a?b=1"))
        val intent = started()!!
        assertEquals(Intent.ACTION_VIEW, intent.action)
        assertEquals("https://example.com/a?b=1", intent.dataString)
        assertEquals(browser, intent.`package`)
        assertTrue("a Custom Tabs intent", intent.hasExtra(CustomTabsIntent.EXTRA_SESSION))
        assertEquals(CustomTabsIntent.SHOW_PAGE_TITLE, intent.getIntExtra(CustomTabsIntent.EXTRA_TITLE_VISIBILITY_STATE, -1))
        assertTrue(intent.getBooleanExtra(CustomTabsIntent.EXTRA_ENABLE_URLBAR_HIDING, false))
        assertEquals(CustomTabsIntent.SHARE_STATE_OFF, intent.getIntExtra(CustomTabsIntent.EXTRA_SHARE_STATE, -1))
        assertFalse("not offered: not asked", intent.getBooleanExtra(CustomTabsIntent.EXTRA_ENABLE_EPHEMERAL_BROWSING, false))
        assertEquals(Uri.EMPTY, intent.getParcelableExtra(Intent.EXTRA_REFERRER, Uri::class.java))
    }

    @Test
    fun ephemeralBrowsingIsAskedForWhereTheBrowserOffersIt() {
        installCustomTabsBrowser(ephemeral = true)
        assertTrue(LinkOpener.open(app, "https://example.com/"))
        val intent = started()!!
        assertTrue(intent.getBooleanExtra(CustomTabsIntent.EXTRA_ENABLE_EPHEMERAL_BROWSING, false))
    }

    @Test
    fun theColourSchemeFollowsTheApp() {
        installCustomTabsBrowser(ephemeral = false)
        LinkOpener.open(app, "https://example.com/", CustomTabsIntent.COLOR_SCHEME_DARK)
        assertEquals(CustomTabsIntent.COLOR_SCHEME_DARK, started()!!.getIntExtra(CustomTabsIntent.EXTRA_COLOR_SCHEME, -1))
    }

    @Test
    fun anUpperCaseSchemeStillOpens() {
        // Intent filters match schemes case-sensitively; the detector keeps "HTTPS://Example.COM" as typed.
        assertTrue(LinkOpener.open(app, "HTTPS://Example.COM"))
        assertEquals("https", started()!!.data?.scheme)
        assertTrue(LinkOpener.open(app, "MAILTO:bob@example.com"))
        assertEquals("mailto", started()!!.data?.scheme)
    }

    @Test
    fun plainHttpOpensToo() {
        assertTrue(LinkOpener.open(app, "http://localhost:3000/x"))
        assertEquals("http://localhost:3000/x", started()!!.dataString)
    }
}
