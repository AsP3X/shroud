package de.corespace.shroud.core.links

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.util.regex.Matcher
import java.util.regex.Pattern

/**
 * Finds web links and e-mail addresses in plain message text — iOS `LinkDetector`
 * (`ios/shroud/Services/Links/LinkDetector.swift:14-170`), web `links.ts:14-177`
 * (media-voice-links §10.1).
 *
 * The server never sees message text, so each client finds links itself, and every client must
 * underline the **same UTF-16 ranges**: a small hand-written rule set shared line for line with
 * iOS and the web and pinned by the same vectors (`LinkDetectorTests`, `links.selftest.ts`),
 * not a platform detector whose results change between releases. Kotlin `String` indices are
 * UTF-16 units like `NSString` and JavaScript strings, so [DetectedLink.start] and
 * [DetectedLink.length] map one to one.
 *
 * Pure and deterministic; never throws. Only `http`, `https` and `mailto` URLs are produced, and
 * [DetectedLink.url] is kept as a string exactly as built — `HTTPS://Example.COM` stays as typed.
 */
object LinkDetector {
    /**
     * Unicode `White_Space`, spelled out (media-voice-links §10.1). `NSRegularExpression`'s `\s`
     * is ICU's `[\p{WhiteSpace}]`; the JVM's default `\s` is ASCII only while Android's ICU-backed
     * regex is not, so naming the set makes JVM tests and devices agree.
     */
    private const val WHITE_SPACE = "\\t\\n\\u000B\\f\\r \\u0085\\u00A0\\u1680\\u2000-\\u200A\\u2028\\u2029\\u202F\\u205F\\u3000"

    /**
     * `LinkDetector.swift:30` with `\s` → [WHITE_SPACE] and `\d` → `[0-9]` (ICU's `\d` is any
     * Unicode digit, JavaScript's only ASCII; the port only ever sees ports, so ASCII it is).
     * Group 1 a scheme URL, group 2 an e-mail, group 3 a `www.`/bare host. The look-behind keeps a
     * match from starting inside a word, a path or an address (`foo@bar.com` must not also yield
     * `bar.com`); trailing punctuation is trimmed afterwards ([trimTrailing]).
     */
    private const val PATTERN =
        "(?<![\\p{L}\\p{N}@._\\-/#%+~=&])(?:" +
            "(https?://[^" + WHITE_SPACE + "<>\"]+)" +
            "|([\\p{L}\\p{N}._%+\\-]+@(?:[\\p{L}\\p{N}](?:[\\p{L}\\p{N}\\-]{0,61}[\\p{L}\\p{N}])?\\.)+" +
            "(?:xn--[a-z0-9\\-]{1,59}|\\p{L}{2,63}))" +
            "|((?:www\\.)?(?:[\\p{L}\\p{N}](?:[\\p{L}\\p{N}\\-]{0,61}[\\p{L}\\p{N}])?\\.)+" +
            "(?:xn--[a-z0-9\\-]{1,59}|\\p{L}{2,63})(?::[0-9]{1,5})?(?:[/?#][^" + WHITE_SPACE + "<>\"]*)?)" +
            ")"

    /** Compiled once; a pattern the regex engine rejects detects nothing (`LinkDetector.swift:32-35`, web `:115-126`). */
    private val regex: Pattern? by lazy {
        try {
            Pattern.compile(PATTERN, Pattern.CASE_INSENSITIVE or Pattern.UNICODE_CASE)
        } catch (_: Exception) {
            null
        }
    }

    /** Characters that end a sentence rather than a URL (`LinkDetector.swift:37-38`). */
    private const val TRAILING_PUNCTUATION = ".,:;!?'\"‘’“”»›…"

    /**
     * Two-letter country codes (`LinkDetector.swift:40-51`). Bare hosts must end in one of these or
     * in [GENERIC_TLDS], so sentences without a space after the full stop ("Ende.Da") are not
     * turned into links.
     */
    private val COUNTRY_TLDS: Set<String> = (
        "ac ad ae af ag ai al am ao aq ar as at au aw ax az ba bb bd be bf bg bh bi bj bm bn bo br bs " +
            "bt bw by bz ca cc cd cf cg ch ci ck cl cm cn co cr cu cv cw cx cy cz de dj dk dm do dz ec ee " +
            "eg er es et eu fi fj fk fm fo fr ga gb gd ge gf gg gh gi gl gm gn gp gq gr gs gt gu gw gy hk " +
            "hm hn hr ht hu id ie il im in io iq ir is it je jm jo jp ke kg kh ki km kn kp kr kw ky kz la " +
            "lb lc li lk lr ls lt lu lv ly ma mc md me mg mh mk ml mm mn mo mp mq mr ms mt mu mv mw mx my " +
            "mz na nc ne nf ng ni nl no np nr nu nz om pa pe pf pg ph pk pl pm pn pr ps pt pw py qa re ro " +
            "rs ru rw sa sb sc sd se sg sh si sk sl sm sn so sr ss st su sv sx sy sz tc td tf tg th tj tk " +
            "tl tm tn to tr tt tv tw tz ua ug uk us uy uz va vc ve vg vi vn vu wf ws ye yt za zm zw"
        ).split(' ').toSet()

    /** The generic TLDs people actually type without a scheme (`LinkDetector.swift:53-64`). */
    private val GENERIC_TLDS: Set<String> = (
        "com org net edu gov mil int info biz name pro app dev xyz online site website tech store shop " +
            "blog news cloud club live page link wiki email social media art design studio travel one top " +
            "mobi museum coop aero asia jobs tel cat post zone network digital agency company services " +
            "solutions systems software team tools works today space fun games music video photo photos " +
            "gallery host codes support help guide center school academy education university health care " +
            "law finance money bank capital fund market events community foundation family life city " +
            "berlin hamburg bayern koeln wien london paris nyc tokyo amsterdam swiss gmbh ltd inc llc eco " +
            "energy bio garden house reisen restaurant cafe coffee bar wine rocks guru expert consulting " +
            "partners tips fyi run chat ninja social"
        ).split(' ').toSet()

    /** Every link in [text], in order (`LinkDetector.swift:66-79`). Empty for plain prose. */
    fun links(text: String): List<DetectedLink> {
        if (text.isEmpty()) return emptyList()
        val matcher = regex?.matcher(text) ?: return emptyList()
        val found = ArrayList<DetectedLink>()
        while (matcher.find()) {
            link(matcher, text)?.let(found::add)
        }
        return found
    }

    /**
     * The link a preview is built for: the first one that is not an e-mail address — Telegram
     * previews the first link of a message too (`LinkDetector.swift:81-85`).
     */
    fun firstPreviewableUrl(text: String): String? = links(text).firstOrNull { !it.isEmail }?.url

    /** One run of [splitLinks]: plain text ([link] null) or the text of one link. */
    data class TextPart(val text: String, val link: DetectedLink?)

    /**
     * [text] cut into plain runs and links, in order, covering every character once — web
     * `splitLinks` (`links.ts:160-175`), what the bubbles' link text is drawn from.
     */
    fun splitLinks(text: String): List<TextPart> {
        val links = links(text)
        if (links.isEmpty()) return listOf(TextPart(text, null))
        val parts = ArrayList<TextPart>(links.size * 2 + 1)
        var cursor = 0
        for (link in links) {
            if (link.start > cursor) parts += TextPart(text.substring(cursor, link.start), null)
            parts += TextPart(text.substring(link.start, link.start + link.length), link)
            cursor = link.start + link.length
        }
        if (cursor < text.length) parts += TextPart(text.substring(cursor), null)
        return parts
    }

    // ---- Match → link (`LinkDetector.swift:89-123`) ----------------------------------------------

    private fun link(match: Matcher, text: String): DetectedLink? {
        val schemeStart = match.start(1)
        if (schemeStart >= 0) {
            val end = trimTrailing(text, schemeStart, match.end(1))
            val raw = text.substring(schemeStart, end)
            // "https://" on its own, or a scheme followed only by punctuation, is not a link
            // (iOS `URL(string:)?.host`, web `new URL().hostname`).
            val host = raw.toHttpUrlOrNull()?.host
            if (host.isNullOrEmpty()) return null
            return DetectedLink(start = schemeStart, length = end - schemeStart, url = raw, isEmail = false)
        }

        val emailStart = match.start(2)
        if (emailStart >= 0) {
            val raw = text.substring(emailStart, match.end(2))
            if (!isAllowedTld(raw.substringAfterLast('@'))) return null
            return DetectedLink(start = emailStart, length = raw.length, url = "mailto:$raw", isEmail = true)
        }

        val hostStart = match.start(3)
        if (hostStart < 0) return null
        val end = trimTrailing(text, hostStart, match.end(3))
        val raw = text.substring(hostStart, end)
        val hostName = raw.takeWhile { it != ':' && it != '/' && it != '?' && it != '#' }
        // `www.` says "this is a web address" on its own; a bare name needs a real TLD.
        if (!raw.startsWith("www.", ignoreCase = true) && !isAllowedTld(hostName)) return null
        return DetectedLink(start = hostStart, length = end - hostStart, url = "https://$raw", isEmail = false)
    }

    /**
     * The end of the match `[start, end)` with sentence punctuation and unbalanced closing brackets
     * dropped (`LinkDetector.swift:125-158`): "(see example.com/a_(b))." keeps the `)` that closes
     * `(b` but loses the one that closes the sentence's bracket and the full stop.
     */
    private fun trimTrailing(text: String, start: Int, end: Int): Int {
        var length = end - start
        while (length > 0) {
            val last = text[start + length - 1]
            if (last in TRAILING_PUNCTUATION) {
                length -= 1
                continue
            }
            val opener = when (last) {
                ')' -> '('
                ']' -> '['
                '}' -> '{'
                else -> null
            }
            if (opener != null && count(text, start, start + length, opener) < count(text, start, start + length, last)) {
                length -= 1
                continue
            }
            break
        }
        return start + length
    }

    private fun count(text: String, from: Int, to: Int, unit: Char): Int {
        var n = 0
        for (i in from until to) if (text[i] == unit) n++
        return n
    }

    /**
     * True when the host's last label is a real top-level domain (`LinkDetector.swift:160-169`).
     * Swift's `split` drops empty labels, so the last *non-empty* label counts.
     */
    private fun isAllowedTld(host: String): Boolean {
        val tld = host.split('.').lastOrNull { it.isNotEmpty() }?.lowercase() ?: return false
        if (tld.startsWith("xn--")) return tld.length > 4
        // A TLD in its own script (e.g. `.рф`) — the regex already required letters.
        if (tld.codePoints().allMatch { it > 0x7F }) return true
        if (tld.length == 2) return tld in COUNTRY_TLDS
        return tld in GENERIC_TLDS
    }
}
