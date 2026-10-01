package de.corespace.shroud.core.links

/**
 * The shared detection vectors, verbatim from `ios/shroudTests/LinkDetectorTests.swift:16-41` and
 * `web/src/links.selftest.ts:31-50`: (UTF-16 location, UTF-16 length, URL) per expected link. A
 * copy lives in `androidTest` (`LinkDetectorDeviceTest`), which cannot see JVM test sources; keep
 * both in step with the iOS file.
 */
object LinkDetectorVectors {
    data class Expected(val start: Int, val length: Int, val url: String)

    val vectors: List<Pair<String, List<Expected>>> = listOf(
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
}
