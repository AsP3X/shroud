package de.corespace.shroud.core.contacts

import de.corespace.shroud.core.net.ServerConfiguration
import de.corespace.shroud.core.net.ServerConnectionMode
import java.io.ByteArrayOutputStream
import java.text.BreakIterator
import java.util.Locale
import java.util.UUID

/**
 * Free-form invite text — a share code, a username, a user id or a `/u/<code>` link, typed, pasted
 * or scanned — read the way iOS reads it (`ios/shroud/Services/API/ContactInviteParser.swift:4-119`;
 * contacts §4.1–4.2, plan C15). Pure; no Android types, so it runs in JVM tests.
 *
 * Every rule is iOS's, character for character, and the shared goldens of
 * `ios/shroudTests/ContactInviteParserTests.swift` (`matchesTheSharedParseGoldens`,
 * `matchesTheSharedNormalizeGoldens`, `matchesTheSharedShareURLGoldens`) hold here line by line —
 * which is why the web's wider reading (`/user/<code>`, a bare `host/<CODE>`, ASCII-only codes;
 * web-parity §9.1, api-realtime §8) is **not** adopted: it contradicts those goldens (plan I7, "one
 * truth for all clients"). A username of 8–16 letters and digits reads as a share code here, as on
 * iOS; [InviteLookup] retries it as a username when no such code exists (P10a).
 *
 * Swift semantics ported by hand: `trimmingCharacters(in: .whitespacesAndNewlines)` is
 * [trimSwiftWhitespace] (Kotlin's `trim()` also strips U+001C–U+001F and keeps U+0085), `String.count`
 * counts grapheme clusters ([graphemeCount]), `CharacterSet.alphanumerics` is the Unicode categories
 * L*, M*, N*, `UUID(uuidString:)` accepts only the canonical 36-character form (never
 * `UUID.fromString`, which reads `1-1-1-1-1`), and `URL.path` is read without `android.net.Uri`
 * (a stub in JVM tests).
 */
object ContactInviteParser {
    /** What an invite names. */
    sealed interface Invite {
        data class UserId(val id: UUID) : Invite

        /** Normalized ([normalizeShareCode]): upper-case, no `@`, `-` or spaces. */
        data class ShareCode(val code: String) : Invite

        /** Lower-case, 3–32 of `a-z 0-9 _`. */
        data class Username(val name: String) : Invite
    }

    /** The official public share host for links and QR payloads (`:11-12`). */
    const val OFFICIAL_SHARE_HOST = "shroud.corespace.de"

    private val CANONICAL_UUID = Regex("^[0-9A-Fa-f]{8}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{12}$")
    private val URL_SCHEME = Regex("^[A-Za-z][A-Za-z0-9+.\\-]*$")
    private const val USERNAME_ALPHABET = "abcdefghijklmnopqrstuvwxyz0123456789_"

    /**
     * The link a share code is handed out as (`shareURL(code:configuration:)`, `:14-36`), built from
     * the **current** server configuration: the official host for the official server and for a
     * self-hosted server on an empty host, `127.0.0.1` or `localhost` (a local API has no public web
     * front, `:25-27`); else `<http|https>://<host>[:<port>]/u/<CODE>`, the port left out when empty,
     * `80` or `443`. `10.0.2.2` (the emulator's host alias) is kept as a host like any other, so the
     * golden table stays shared with iOS (contacts §4.2 [AND]).
     */
    fun shareUrl(code: String, configuration: ServerConfiguration): String {
        val normalized = normalizeShareCode(code)
        val official = "https://$OFFICIAL_SHARE_HOST/u/$normalized"
        return when (configuration.mode) {
            ServerConnectionMode.Official -> official
            ServerConnectionMode.SelfHosted -> {
                val host = trimSwiftWhitespace(configuration.host)
                if (host.isEmpty() || host == "127.0.0.1" || host == "localhost") return official
                val scheme = if (configuration.useHTTPS) "https" else "http"
                val port = trimSwiftWhitespace(configuration.port)
                if (port.isEmpty() || port == "80" || port == "443") "$scheme://$host/u/$normalized" else "$scheme://$host:$port/u/$normalized"
            }
        }
    }

    /**
     * `normalizeShareCode` (`:38-44`): trimmed (whitespace and newlines), every `@` stripped from
     * both ends, every `-` and every U+0020 space removed (tabs and newlines inside stay), upper-cased
     * with Unicode full case mapping and no locale (Swift `uppercased()` = Kotlin `uppercase()`).
     */
    fun normalizeShareCode(raw: String): String =
        trimSwiftWhitespace(raw).trim('@').replace("-", "").replace(" ", "").uppercase()

    /**
     * Interprets invite material (`parse`, `:46-74`), in this order: a canonical UUID → [Invite.UserId];
     * a link ([parseUrl]); the normalized text in share-code form → [Invite.ShareCode]; the trimmed
     * text without its `@`s, lower-cased, in username form → [Invite.Username]; else null.
     */
    fun parse(raw: String): Invite? {
        val trimmed = trimSwiftWhitespace(raw)
        if (trimmed.isEmpty()) return null
        canonicalUuid(trimmed)?.let { return Invite.UserId(it) }
        parseUrl(trimmed)?.let { return it }
        val code = normalizeShareCode(trimmed)
        if (isShareCodeFormat(code)) return Invite.ShareCode(code)
        val username = trimmed.trim('@').lowercase()
        if (isUsernameFormat(username)) return Invite.Username(username)
        return null
    }

    /**
     * `isShareCodeFormat` (`:105-110`): 8–16 characters (grapheme clusters, as Swift counts), every
     * code point alphanumeric in the Unicode sense (L*, M*, N*), and already upper-case. Wider than
     * the server's `[A-Z0-9]{8,16}` (`auth/share_code.rs`): `MÜLLERHANS` passes here and the server
     * answers "User not found." — kept for the shared goldens.
     */
    fun isShareCodeFormat(code: String): Boolean {
        if (graphemeCount(code) !in 8..16) return false
        return code.codePoints().allMatch(::isSwiftAlphanumeric) && code == code.uppercase()
    }

    /** `isUsernameFormat` (`:112-118`): 3–32 characters, each one of `a-z 0-9 _`. */
    fun isUsernameFormat(username: String): Boolean {
        if (graphemeCount(username) !in 3..32) return false
        return username.all { it in USERNAME_ALPHABET }
    }

    /**
     * The share code of an App Link `https://shroud.corespace.de/u/<code>` (manifest filter of P10c,
     * contacts §5.10), for the shell to put into `Contacts.pendingInvite`; null for any other link
     * or text. Only that exact host and scheme: a self-hosted host cannot be verified as an App Link,
     * so its links keep opening the browser. The caller pre-fills Add Contact with it and never sends
     * a request without the user's tap.
     */
    fun appLinkShareCode(url: String?): String? {
        val text = url?.let(::trimSwiftWhitespace) ?: return null
        val parts = splitUrl(text) ?: return null
        if (!parts.scheme.equals("https", ignoreCase = true)) return null
        if (!parts.host.equals(OFFICIAL_SHARE_HOST, ignoreCase = true)) return null
        val invite = parseUrl(text) as? Invite.ShareCode ?: return null
        return invite.code
    }

    // ---- Links (`parseURL`, `:76-103`) ----

    /**
     * Candidates: the text itself when it contains `://`; else `https://` + text and `http://` +
     * text when it starts with `shroud.` (any case) or contains `/u/`; else none. For each candidate
     * the percent-decoded path is split on `/` (empty segments dropped): `…/u/<code>` whose code
     * normalizes to share-code form → [Invite.ShareCode]; else a last segment that is a canonical
     * UUID (`…/api/v1/users/<id>`) → [Invite.UserId]. Any scheme counts (`foo://x/u/CODE`).
     */
    private fun parseUrl(raw: String): Invite? {
        val candidates = when {
            raw.contains("://") -> listOf(raw)
            raw.lowercase().startsWith("shroud.") || raw.contains("/u/") -> listOf("https://$raw", "http://$raw")
            else -> emptyList()
        }
        for (candidate in candidates) {
            val url = splitUrl(candidate) ?: continue
            val segments = url.path.split('/').filter { it.isNotEmpty() }
            if (segments.size >= 2 && segments[segments.size - 2].lowercase() == "u") {
                val code = normalizeShareCode(segments.last())
                if (isShareCodeFormat(code)) return Invite.ShareCode(code)
            }
            segments.lastOrNull()?.let(::canonicalUuid)?.let { return Invite.UserId(it) }
        }
        return null
    }

    private class UrlParts(val scheme: String, val host: String, val path: String)

    /**
     * RFC 3986 split of `scheme:[//authority]path[?query][#fragment]`, standing in for Foundation's
     * `URL(string:)` + `.path` (which `android.net.Uri` cannot do in JVM tests): null when the
     * scheme is not a valid scheme; the path percent-decoded as UTF-8 (kept raw when it does not
     * decode, rather than invented).
     */
    private fun splitUrl(text: String): UrlParts? {
        val colon = text.indexOf(':')
        if (colon <= 0) return null
        val scheme = text.substring(0, colon)
        if (!URL_SCHEME.matches(scheme)) return null
        var rest = text.substring(colon + 1)
        val end = rest.indexOfAny(charArrayOf('?', '#')).let { if (it < 0) rest.length else it }
        rest = rest.substring(0, end)
        var host = ""
        if (rest.startsWith("//")) {
            val afterSlashes = rest.substring(2)
            val slash = afterSlashes.indexOf('/')
            val authority = if (slash < 0) afterSlashes else afterSlashes.substring(0, slash)
            host = authority.substringAfterLast('@').let { hostPort ->
                if (hostPort.startsWith("[")) hostPort.substringBefore(']') + "]" else hostPort.substringBefore(':')
            }
            rest = if (slash < 0) "" else afterSlashes.substring(slash)
        }
        return UrlParts(scheme, host, percentDecode(rest) ?: rest)
    }

    /** `%XX` escapes as UTF-8; null on a malformed escape or invalid UTF-8. */
    private fun percentDecode(text: String): String? {
        if ('%' !in text) return text
        val out = ByteArrayOutputStream(text.length)
        var i = 0
        while (i < text.length) {
            val c = text[i]
            if (c == '%') {
                if (i + 2 >= text.length) return null
                val hi = Character.digit(text[i + 1], 16)
                val lo = Character.digit(text[i + 2], 16)
                if (hi < 0 || lo < 0) return null
                out.write(hi * 16 + lo)
                i += 3
            } else {
                val end = if (Character.isHighSurrogate(c) && i + 1 < text.length) i + 2 else i + 1
                out.write(text.substring(i, end).toByteArray(Charsets.UTF_8))
                i = end
            }
        }
        val bytes = out.toByteArray()
        val decoder = Charsets.UTF_8.newDecoder()
        return try {
            decoder.decode(java.nio.ByteBuffer.wrap(bytes)).toString()
        } catch (_: java.nio.charset.CharacterCodingException) {
            null
        }
    }

    // ---- Swift string semantics ----

    /** `UUID(uuidString:)`: the canonical 8-4-4-4-12 hex form, any case, nothing around it. */
    private fun canonicalUuid(text: String): UUID? =
        if (CANONICAL_UUID.matches(text)) UUID.fromString(text) else null

    /** `CharacterSet.alphanumerics`: Unicode general categories L*, M* and N*. */
    private fun isSwiftAlphanumeric(codePoint: Int): Boolean = when (Character.getType(codePoint).toByte()) {
        Character.UPPERCASE_LETTER, Character.LOWERCASE_LETTER, Character.TITLECASE_LETTER,
        Character.MODIFIER_LETTER, Character.OTHER_LETTER,
        Character.NON_SPACING_MARK, Character.ENCLOSING_MARK, Character.COMBINING_SPACING_MARK,
        Character.DECIMAL_DIGIT_NUMBER, Character.LETTER_NUMBER, Character.OTHER_NUMBER,
        -> true
        else -> false
    }

    /** Swift `String.count`: extended grapheme clusters, not UTF-16 units. */
    internal fun graphemeCount(text: String): Int {
        if (text.isEmpty()) return 0
        val iterator = BreakIterator.getCharacterInstance(Locale.ROOT)
        iterator.setText(text)
        var count = 0
        while (iterator.next() != BreakIterator.DONE) count++
        return count
    }

    /**
     * `trimmingCharacters(in: .whitespacesAndNewlines)`: strips general categories Zs, Zl, Zp,
     * U+0009–U+000D and U+0085 from both ends — exactly that set.
     */
    internal fun trimSwiftWhitespace(text: String): String {
        var start = 0
        var end = text.length
        while (start < end) {
            val cp = text.codePointAt(start)
            if (!isSwiftWhitespaceOrNewline(cp)) break
            start += Character.charCount(cp)
        }
        while (end > start) {
            val cp = text.codePointBefore(end)
            if (!isSwiftWhitespaceOrNewline(cp)) break
            end -= Character.charCount(cp)
        }
        return text.substring(start, end)
    }

    private fun isSwiftWhitespaceOrNewline(codePoint: Int): Boolean =
        codePoint in 0x09..0x0D || codePoint == 0x85 || when (Character.getType(codePoint).toByte()) {
            Character.SPACE_SEPARATOR, Character.LINE_SEPARATOR, Character.PARAGRAPH_SEPARATOR -> true
            else -> false
        }
}
