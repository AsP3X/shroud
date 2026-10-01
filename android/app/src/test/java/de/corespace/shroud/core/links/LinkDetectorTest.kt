package de.corespace.shroud.core.links

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Link detection must underline the same characters on Android, the iPhone and the web. Ports
 * `ios/shroudTests/LinkDetectorTests.swift:16-69` and the detection part of
 * `web/src/links.selftest.ts:31-74`; the vectors are the same strings, offsets are UTF-16 units.
 * The same table runs on a device (`LinkDetectorDeviceTest`): the JVM's regex engine and Android's
 * ICU-backed one must agree (media-voice-links R7).
 */
class LinkDetectorTest {
    @Test
    fun sharedVectors() { // LinkDetectorTests.swift:16-53, links.selftest.ts:31-61
        for ((text, expected) in LinkDetectorVectors.vectors) {
            val found = LinkDetector.links(text)
            assertEquals("count for $text", expected.size, found.size)
            found.zip(expected).forEach { (link, want) ->
                assertEquals("location in $text", want.start, link.start)
                assertEquals("length in $text", want.length, link.length)
                assertEquals("url in $text", want.url, link.url)
            }
        }
    }

    @Test
    fun emailIsTappableButNeverPreviewed() { // :55-59
        val text = "write bob@example.com or see example.com"
        assertEquals(true, LinkDetector.links(text).first().isEmail)
        assertEquals("https://example.com", LinkDetector.firstPreviewableUrl(text))
    }

    @Test
    fun onlyEmailMeansNothingToPreview() { // :61-63
        assertNull(LinkDetector.firstPreviewableUrl("bob@example.com"))
    }

    @Test
    fun internationalDomainIsFound() { // :65-69
        val links = LinkDetector.links("Visit straße.de today")
        assertEquals(1, links.size)
        assertEquals(6, links[0].start)
        assertEquals(9, links[0].length)
    }

    @Test
    fun emailLinksKeepTheAddressAsTyped() {
        val link = LinkDetector.links("mail bob@example.com please").single()
        assertEquals("mailto:bob@example.com", link.url)
        assertTrue(link.isEmail)
    }

    @Test
    fun splitLinksCutsPlainAndLinkRuns() { // links.selftest.ts:71-74
        val parts = LinkDetector.splitLinks("see example.com now")
        assertEquals("see |example.com| now", parts.joinToString("|") { it.text })
        assertNotNull(parts[1].link)
        assertNull(parts[0].link)
        assertNull(parts[2].link)
    }

    @Test
    fun splitLinksOfPlainTextIsOneRun() {
        assertEquals(listOf(LinkDetector.TextPart("no links", null)), LinkDetector.splitLinks("no links"))
        assertEquals(listOf(LinkDetector.TextPart("", null)), LinkDetector.splitLinks(""))
    }

    @Test
    fun whitespaceEndsAUrlLikeICU() {
        // ICU's \s is Unicode White_Space: a no-break space or NEL ends the link on iOS, so it must here.
        assertEquals("https://a.com/x", LinkDetector.links("https://a.com/x more").single().url)
        assertEquals("https://a.com/x", LinkDetector.links("https://a.com/x\u0085more").single().url)
        assertEquals("https://a.com/x", LinkDetector.links("https://a.com/x　more").single().url)
    }

    @Test
    fun aBareHostWithAPortAndPathIsHttps() {
        val link = LinkDetector.links("on example.com:8080/a?b=1#c ok").single()
        assertEquals("https://example.com:8080/a?b=1#c", link.url)
        assertEquals(3, link.start)
    }

    @Test
    fun linksNeverPrintTheirText() {
        val link = LinkDetector.links("see secret.example.com now").single()
        assertTrue(!link.toString().contains("secret"))
        assertTrue(LinkDetector.splitLinks("see secret.example.com now").none { it.toString().contains("secret") })
    }

    @Test
    fun emptyTextHasNoLinks() {
        assertTrue(LinkDetector.links("").isEmpty())
        assertNull(LinkDetector.firstPreviewableUrl(""))
    }
}
