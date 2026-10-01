package de.corespace.shroud.core.contacts

import de.corespace.shroud.core.contacts.ContactInviteParser.Invite
import de.corespace.shroud.core.net.ServerConfiguration
import de.corespace.shroud.core.net.ServerConnectionMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.UUID

/**
 * `ios/shroudTests/ContactInviteParserTests.swift`: the six original tests (`:6-57`) and the shared
 * goldens (`matchesTheSharedParseGoldens`, `matchesTheSharedNormalizeGoldens`,
 * `matchesTheSharedShareURLGoldens`) copied line by line — the same table contacts §4.1–4.2 lists.
 */
class ContactInviteParserTest {
    private fun uuid(text: String) = UUID.fromString(text)

    /** `parsesUUID` (`:6-10`); iOS passes `uuidString`, which is upper-case. */
    @Test
    fun parsesUUID() {
        val id = uuid("11111111-1111-1111-1111-111111111111")
        assertEquals(Invite.UserId(id), ContactInviteParser.parse(id.toString().uppercase()))
    }

    /** `parsesShareCode` (`:12-16`). */
    @Test
    fun parsesShareCode() {
        assertEquals(Invite.ShareCode("ABCD234567"), ContactInviteParser.parse("abcd-2345-67"))
        assertEquals(Invite.ShareCode("XYZW987654"), ContactInviteParser.parse("  XYZW987654  "))
    }

    /** `parsesUsername` (`:18-22`). */
    @Test
    fun parsesUsername() {
        assertEquals(Invite.Username("alice_1"), ContactInviteParser.parse("alice_1"))
        assertEquals(Invite.Username("bob"), ContactInviteParser.parse("@Bob"))
    }

    /** `parsesShareLink` (`:24-34`). */
    @Test
    fun parsesShareLink() {
        assertEquals(Invite.ShareCode("ABCD234567"), ContactInviteParser.parse("https://shroud.corespace.de/u/ABCD234567"))
        assertEquals(Invite.ShareCode("ABCD234567"), ContactInviteParser.parse("shroud.corespace.de/u/ABCD234567"))
    }

    /** `rejectsEmptyAndTooShort` (`:36-42`). */
    @Test
    fun rejectsEmptyAndTooShort() {
        assertNull(ContactInviteParser.parse(""))
        assertNull(ContactInviteParser.parse("ab"))
        // Too short for a share code and invalid as a username (symbols).
        assertNull(ContactInviteParser.parse("!!!"))
    }

    /** `buildsOfficialShareURL` (`:44-56`). */
    @Test
    fun buildsOfficialShareURL() {
        val url = ContactInviteParser.shareUrl(
            code = "abcd234567",
            configuration = ServerConfiguration(
                mode = ServerConnectionMode.Official,
                host = "ignored",
                port = "8080",
                apiPath = "/api/v1",
                useHTTPS = true,
            ),
        )
        assertEquals("https://shroud.corespace.de/u/ABCD234567", url)
    }

    /** `matchesTheSharedParseGoldens` (`:62-103`), every row. */
    @Test
    fun matchesTheSharedParseGoldens() {
        val id = uuid("9b1deb4d-3b7d-4bad-9bdd-2b0d7b3dcb6d")
        val goldens: List<Pair<String, Invite?>> = listOf(
            "11111111-1111-1111-1111-111111111111" to Invite.UserId(uuid("11111111-1111-1111-1111-111111111111")),
            "9b1deb4d-3b7d-4bad-9bdd-2b0d7b3dcb6d" to Invite.UserId(id),
            "  9B1DEB4D-3B7D-4BAD-9BDD-2B0D7B3DCB6D  " to Invite.UserId(id),
            "abcd-2345-67" to Invite.ShareCode("ABCD234567"),
            "  XYZW987654  " to Invite.ShareCode("XYZW987654"),
            "alice_1" to Invite.Username("alice_1"),
            "@Bob" to Invite.Username("bob"),
            "https://shroud.corespace.de/u/ABCD234567" to Invite.ShareCode("ABCD234567"),
            "shroud.corespace.de/u/ABCD234567" to Invite.ShareCode("ABCD234567"),
            "https://shroud.corespace.de/u/abcd-2345-67/" to Invite.ShareCode("ABCD234567"),
            "http://192.168.1.20:8080/u/ABCD234567" to Invite.ShareCode("ABCD234567"),
            "example.com/u/ABCD234567" to Invite.ShareCode("ABCD234567"),
            "https://example.com/u/ABC" to null,
            "https://shroud.corespace.de/api/v1/users/9b1deb4d-3b7d-4bad-9bdd-2b0d7b3dcb6d" to Invite.UserId(id),
            "https://example.com/user/ABCD234567" to null,
            "https://example.com/ABCD234567" to null,
            "" to null,
            "   " to null,
            "ab" to null,
            "!!!" to null,
            "noahvorberg" to Invite.ShareCode("NOAHVORBERG"),
            "@noahvorberg" to Invite.ShareCode("NOAHVORBERG"),
            "noah_vorberg" to Invite.Username("noah_vorberg"),
            "ABCDEFGH" to Invite.ShareCode("ABCDEFGH"),
            "ABCDEFG" to Invite.Username("abcdefg"),
            "ABCDEFGHIJKLMNOPQ" to Invite.Username("abcdefghijklmnopq"),
            "abcdefghijklmnopq" to Invite.Username("abcdefghijklmnopq"),
            "@@ABCD234567@@" to Invite.ShareCode("ABCD234567"),
            "ab cd 23 45 67" to Invite.ShareCode("ABCD234567"),
            "Jane Cooper" to Invite.ShareCode("JANECOOPER"),
            "a-b" to null,
            "shroud.app" to null,
            "müllerhans" to Invite.ShareCode("MÜLLERHANS"),
        )
        for ((input, expected) in goldens) {
            assertEquals("parse(\"$input\")", expected, ContactInviteParser.parse(input))
        }
    }

    /** `matchesTheSharedNormalizeGoldens` (`:105-111`). */
    @Test
    fun matchesTheSharedNormalizeGoldens() {
        assertEquals("ABCD234567", ContactInviteParser.normalizeShareCode("abcd-2345-67"))
        assertEquals("XYZW987654", ContactInviteParser.normalizeShareCode(" @xyz w98 7654 "))
        assertEquals("ABCD", ContactInviteParser.normalizeShareCode("@@ab-cd@@"))
        assertEquals("AB\nCD", ContactInviteParser.normalizeShareCode("ab\ncd"))
    }

    /** `matchesTheSharedShareURLGoldens` (`:113-132`), code `abcd-2345-67`. */
    @Test
    fun matchesTheSharedShareURLGoldens() {
        fun url(mode: ServerConnectionMode, host: String, port: String, https: Boolean): String =
            ContactInviteParser.shareUrl(
                code = "abcd-2345-67",
                configuration = ServerConfiguration(mode = mode, host = host, port = port, apiPath = "/api/v1", useHTTPS = https),
            )
        val official = ServerConnectionMode.Official
        val selfHosted = ServerConnectionMode.SelfHosted
        assertEquals("https://shroud.corespace.de/u/ABCD234567", url(official, "ignored", "8080", https = true))
        assertEquals("https://shroud.corespace.de/u/ABCD234567", url(selfHosted, "127.0.0.1", "8080", https = false))
        assertEquals("https://shroud.corespace.de/u/ABCD234567", url(selfHosted, "localhost", "8080", https = false))
        assertEquals("https://shroud.corespace.de/u/ABCD234567", url(selfHosted, "  ", "8080", https = false))
        assertEquals("http://10.0.2.2:8080/u/ABCD234567", url(selfHosted, "10.0.2.2", "8080", https = false))
        assertEquals("https://chat.example.org/u/ABCD234567", url(selfHosted, "chat.example.org", "443", https = true))
        assertEquals("https://chat.example.org/u/ABCD234567", url(selfHosted, "chat.example.org", "", https = true))
        assertEquals("http://chat.example.org/u/ABCD234567", url(selfHosted, "chat.example.org", "80", https = false))
        assertEquals("https://chat.example.org:8443/u/ABCD234567", url(selfHosted, "chat.example.org", "8443", https = true))
        assertEquals("http://192.168.1.20:8080/u/ABCD234567", url(selfHosted, " 192.168.1.20 ", " 8080 ", https = false))
    }

    // ---- Android additions (no iOS counterpart) ----

    /** Every share URL this app hands out parses back to its code (what an in-app scan relies on). */
    @Test
    fun aShareUrlParsesBackToItsCode() {
        val configs = listOf(
            ServerConfiguration.official,
            ServerConfiguration(ServerConnectionMode.SelfHosted, "10.0.2.2", "8080", "/api/v1", false),
            ServerConfiguration(ServerConnectionMode.SelfHosted, "chat.example.org", "8443", "/api/v1", true),
        )
        for (config in configs) {
            val url = ContactInviteParser.shareUrl("ab-cd-2345-67xy", config)
            assertEquals(url, Invite.ShareCode("ABCD234567XY"), ContactInviteParser.parse(url))
        }
    }

    /** Swift's whitespace set: U+0085 and U+2028 are trimmed, U+001F (Kotlin `trim()` whitespace) is not. */
    @Test
    fun trimsExactlySwiftsWhitespaceAndNewlines() {
        assertEquals(Invite.ShareCode("ABCD234567"), ContactInviteParser.parse("\u0085 ABCD234567 \t"))
        assertNull(ContactInviteParser.parse("\u001Fab"))
        assertEquals("x", ContactInviteParser.trimSwiftWhitespace("　\r\nx "))
        assertEquals("\u001Cx", ContactInviteParser.trimSwiftWhitespace("\u001Cx"))
    }

    /** `UUID(uuidString:)` is strict: `UUID.fromString` would read `1-1-1-1-1` as an id. */
    @Test
    fun onlyCanonicalUuidsAreUserIds() {
        assertNull(ContactInviteParser.parse("1-1-1-1-1"))
        assertEquals(
            Invite.UserId(uuid("9b1deb4d-3b7d-4bad-9bdd-2b0d7b3dcb6d")),
            ContactInviteParser.parse("https://h/x/9B1DEB4D-3B7D-4BAD-9BDD-2B0D7B3DCB6D?y=1#z"),
        )
        // A path segment with spaces around an id is not an id.
        assertNull(ContactInviteParser.parse("https://h/x/%209b1deb4d-3b7d-4bad-9bdd-2b0d7b3dcb6d"))
    }

    /** Any scheme, query and fragment ignored, percent escapes decoded (Foundation's `URL.path`). */
    @Test
    fun readsTheLinkPathLikeFoundation() {
        assertEquals(Invite.ShareCode("ABCD234567"), ContactInviteParser.parse("foo://x/u/ABCD234567"))
        assertEquals(Invite.ShareCode("ABCD234567"), ContactInviteParser.parse("https://shroud.corespace.de/U/abcd234567?ref=qr#top"))
        assertEquals(Invite.ShareCode("ABCD234567"), ContactInviteParser.parse("https://shroud.corespace.de/u/%41BCD234567"))
        assertEquals(Invite.ShareCode("ABCD234567"), ContactInviteParser.parse("https://user@[::1]:8443/u/ABCD234567"))
    }

    /** Grapheme clusters count as one character, as Swift's `String.count`. */
    @Test
    fun countsCharactersAsGraphemeClusters() {
        // "ABCDEFG" + a combining acute accent: 7 characters for Swift, 8 code points.
        assertNull(ContactInviteParser.parse("ABCDEFǴ"))
        // A decomposed Ü is one character: still a 10-character code.
        assertEquals(10, ContactInviteParser.graphemeCount("MÜLLERHANS"))
        assertEquals(Invite.ShareCode("MÜLLERHANS"), ContactInviteParser.parse("müllerhans"))
    }

    /** P10c App Links: only `https://shroud.corespace.de/u/<code>` pre-fills an invite. */
    @Test
    fun appLinksNameTheirShareCode() {
        assertEquals("ABCD234567", ContactInviteParser.appLinkShareCode("https://shroud.corespace.de/u/abcd-2345-67"))
        assertEquals("ABCD234567", ContactInviteParser.appLinkShareCode("https://SHROUD.corespace.de/u/ABCD234567/"))
        assertNull(ContactInviteParser.appLinkShareCode("http://shroud.corespace.de/u/ABCD234567"))
        assertNull(ContactInviteParser.appLinkShareCode("https://chat.example.org/u/ABCD234567"))
        assertNull(ContactInviteParser.appLinkShareCode("https://shroud.corespace.de/u/ABC"))
        assertNull(ContactInviteParser.appLinkShareCode("https://shroud.corespace.de/api/v1/users/9b1deb4d-3b7d-4bad-9bdd-2b0d7b3dcb6d"))
        assertNull(ContactInviteParser.appLinkShareCode(null))
    }
}
