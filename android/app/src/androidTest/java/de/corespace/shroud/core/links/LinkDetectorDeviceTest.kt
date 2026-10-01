package de.corespace.shroud.core.links

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The shared detection vectors on the phone's own regex engine (media-voice-links §12.1, R7):
 * Android's `java.util.regex` is ICU-backed, the JVM's is not, and both must underline the same
 * UTF-16 ranges as iOS (`LinkDetectorTests.swift:16-69`) and the web (`links.selftest.ts:31-70`).
 * A copy of `LinkDetectorVectors` (JVM test sources are not visible here); keep both in step.
 *
 * `./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=de.corespace.shroud.core.links.LinkDetectorDeviceTest`
 */
@RunWith(AndroidJUnit4::class)
class LinkDetectorDeviceTest {
    private data class Expected(val start: Int, val length: Int, val url: String)

    private val vectors: List<Pair<String, List<Expected>>> = listOf(
        "Plain text, no links." to listOf(),
        "see https://example.com/path?q=1." to listOf(Expected(4, 28, "https://example.com/path?q=1")),
        "www.example.org" to listOf(Expected(0, 15, "https://www.example.org")),
        "Route: komoot.com/tour/1398273" to listOf(Expected(7, 23, "https://komoot.com/tour/1398273")),
        "(see en.wikipedia.org/wiki/Foo_(bar))" to listOf(Expected(5, 31, "https://en.wikipedia.org/wiki/Foo_(bar)")),
        "mail bob@example.com please" to listOf(Expected(5, 15, "mailto:bob@example.com")),
        "Ende.Da geht es weiter" to listOf(),
        "file.txt and v1.2.3" to listOf(),
        "HTTPS://Example.COM" to listOf(Expected(0, 19, "HTTPS://Example.COM")),
        "two links: a.com and https://b.org/x" to listOf(Expected(11, 5, "https://a.com"), Expected(21, 15, "https://b.org/x")),
        "user@host (no tld)" to listOf(),
        "https://" to listOf(),
        "end of sentence www.test.de!" to listOf(Expected(16, 11, "https://www.test.de")),
        "\"https://quoted.com/a\"" to listOf(Expected(1, 20, "https://quoted.com/a")),
        "emoji 👍https://x.io" to listOf(Expected(8, 12, "https://x.io")),
        "a.b.c and example.com." to listOf(Expected(10, 11, "https://example.com")),
        "localhost:3000 and http://localhost:3000/x" to listOf(Expected(19, 23, "http://localhost:3000/x")),
        "Link:https://colon.com" to listOf(Expected(5, 17, "https://colon.com")),
    )

    @Test
    fun sharedVectors() {
        for ((text, expected) in vectors) {
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
    fun previewsSkipEmailAndInternationalDomainsAreFound() {
        assertEquals("https://example.com", LinkDetector.firstPreviewableUrl("write bob@example.com or see example.com"))
        assertNull(LinkDetector.firstPreviewableUrl("bob@example.com"))
        val straße = LinkDetector.links("Visit straße.de today").single()
        assertEquals(6, straße.start)
        assertEquals(9, straße.length)
        assertEquals("see |example.com| now", LinkDetector.splitLinks("see example.com now").joinToString("|") { it.text })
    }

    @Test
    fun unicodeWhitespaceEndsALinkOnIcuToo() {
        assertEquals("https://a.com/x", LinkDetector.links("https://a.com/x more").single().url)
        assertEquals("https://a.com/x", LinkDetector.links("https://a.com/x\u0085more").single().url)
    }
}
