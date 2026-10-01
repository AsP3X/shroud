package de.corespace.shroud.core.links

import de.corespace.shroud.core.net.wire.WireText
import okhttp3.HttpUrl
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

/**
 * What a web page says about itself in its `<head>` — the raw material of a link preview
 * (iOS `LinkPageMetadata`, `ios/shroud/Services/Links/LinkPageMetadata.swift:3-20`).
 */
data class LinkPageMetadata(
    val siteName: String? = null,
    val title: String? = null,
    val summary: String? = null,
    /** Absolute `https` URL of the page's preview image. */
    val imageUrl: HttpUrl? = null,
    /** Size the page declares for that image (`og:image:width/height`); a hint only. */
    val imageWidth: Int? = null,
    val imageHeight: Int? = null,
    /** `og:type` is a video, or the page declares a player. */
    val isVideo: Boolean = false,
) {
    /** Nothing a preview could show (`LinkPageMetadata.swift:16-19`). */
    val isEmpty: Boolean get() = title == null && summary == null && imageUrl == null

    /** Page texts are message content: never printed. */
    override fun toString(): String = "LinkPageMetadata(image=${imageUrl != null}, video=$isVideo)"
}

/**
 * Reads OpenGraph / Twitter-card / `<title>` metadata out of an HTML head — a character-for-character
 * port of iOS `LinkPageMetadataParser` (`LinkPageMetadata.swift:22-338`; web
 * `linkPreview/pageMetadata.ts`), media-voice-links §10.3.
 *
 * Deliberately not an HTML parser: previews only need a dozen `<meta>` tags near the top of the
 * page, and a tag scanner copes with broken markup a strict parser would reject. Pure, no I/O; the
 * caller hands in at most the first [LinkPreviewFetcher.MAX_HEAD_BYTES] of the page, fetched by the
 * sender's own phone.
 *
 * Swift walks `Character`s (grapheme clusters), Kotlin `Char`s; they only differ outside the ASCII
 * delimiters used here. "Whitespace" is Swift's `Character.isWhitespace` — Unicode `White_Space`
 * ([WireText.isWhitespaceOrNewline]), not Kotlin's `Char.isWhitespace()`.
 */
object LinkPageMetadataParser {
    /** Parses [data] (the start of an HTML document) fetched from [pageUrl]; [contentType] is the response's `Content-Type`. */
    fun parse(data: ByteArray, pageUrl: HttpUrl, contentType: String?): LinkPageMetadata {
        val html = decode(data, contentType)
        val head = headSection(html)

        val meta = HashMap<String, String>()
        for (tag in tags("meta", head)) {
            val attributes = attributes(tag)
            val content = attributes["content"]?.let(::decodeEntities)
            if (content.isNullOrEmpty()) continue
            for (key in META_KEYS) {
                val name = attributes[key]?.lowercase() ?: continue
                if (name !in meta) meta[name] = content
            }
        }

        val documentTitle = title(head)
        val rawImage = firstNonEmpty(
            meta["og:image:secure_url"],
            meta["og:image:url"],
            meta["og:image"],
            meta["twitter:image"],
            meta["twitter:image:src"],
        )
        val type = meta["og:type"]?.lowercase().orEmpty()
        return LinkPageMetadata(
            siteName = firstNonEmpty(meta["og:site_name"], meta["application-name"]),
            title = firstNonEmpty(meta["og:title"], meta["twitter:title"], documentTitle),
            summary = firstNonEmpty(meta["og:description"], meta["twitter:description"], meta["description"]),
            imageUrl = rawImage?.let { secureUrl(it, pageUrl) },
            imageWidth = meta["og:image:width"]?.let(::swiftInt),
            imageHeight = meta["og:image:height"]?.let(::swiftInt),
            isVideo = type.startsWith("video") ||
                "og:video" in meta ||
                "og:video:url" in meta ||
                "og:video:secure_url" in meta ||
                "twitter:player" in meta,
        )
    }

    /** `LinkPageMetadata.swift:42`: a meta tag's content is recorded under each of these names. */
    private val META_KEYS = listOf("property", "name", "itemprop")

    // ---- Text decoding (`LinkPageMetadata.swift:75-138`) -------------------------------------------

    /**
     * Decodes with the declared charset, then a `<meta charset>` sniff of the first 1 KB (ASCII is
     * enough to read it whatever the real encoding is), then strict UTF-8, then lossy UTF-8 so a
     * legacy page with no usable declaration still gets a preview.
     */
    fun decode(data: ByteArray, contentType: String?): String {
        charsetInContentType(contentType)?.let(::charsetNamed)?.let { charset ->
            strictString(data, charset)?.let { return it }
        }
        val prefix = String(data, 0, minOf(SNIFF_BYTES, data.size), Charsets.UTF_8).lowercase()
        val at = prefix.indexOf("charset=")
        if (at >= 0) {
            val value = prefix.substring(at + "charset=".length).dropWhile { it == '"' || it == '\'' }
            charsetNamed(value.takeWhile { it !in "\"'>; /" })?.let { charset ->
                strictString(data, charset)?.let { return it }
            }
        }
        strictString(data, Charsets.UTF_8)?.let { return it }
        return String(data, Charsets.UTF_8)
    }

    private const val SNIFF_BYTES = 1024

    /**
     * [data] decoded as [charset], forgiving a multi-byte character cut off at the end
     * (`LinkPageMetadata.swift:102-112`): only the head of a page is read, so its last character is
     * often incomplete, and one broken character must not throw a Shift_JIS or GBK title onto the
     * lossy fallback.
     */
    private fun strictString(data: ByteArray, charset: Charset): String? {
        for (cut in 0..minOf(3, maxOf(data.size - 1, 0))) {
            val decoder = charset.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
            try {
                return decoder.decode(ByteBuffer.wrap(data, 0, data.size - cut)).toString()
            } catch (_: CharacterCodingException) {
                // try one byte shorter
            }
        }
        return null
    }

    /** The `charset=` value of a `Content-Type`, up to `;`, a space or a quote (`LinkPageMetadata.swift:114-119`). */
    private fun charsetInContentType(contentType: String?): String? {
        val lowered = contentType?.lowercase() ?: return null
        val at = lowered.indexOf("charset=")
        if (at < 0) return null
        return lowered.substring(at + "charset=".length).takeWhile { it != ';' && it != ' ' && it != '"' }
    }

    /**
     * The charset behind a label: the common ones by name, anything else through the IANA registry
     * (`windows-1251`, `gbk`, `big5`, `euc-kr`, `koi8-r`, …) — `LinkPageMetadata.swift:121-138`.
     * Apple's `.shiftJIS` is the Windows superset, so the Shift_JIS labels take `windows-31j` when
     * the platform has it (as the web's TextDecoder does).
     */
    private fun charsetNamed(name: String): Charset? {
        val label = name.trim { it == '"' || it == '\'' || it == ' ' }.lowercase()
        return when (label) {
            "" -> null
            "utf-8", "utf8" -> Charsets.UTF_8
            "iso-8859-1", "latin1", "iso8859-1" -> Charsets.ISO_8859_1
            "windows-1252", "cp1252" -> charsetOrNull("windows-1252")
            "iso-8859-2" -> charsetOrNull("ISO-8859-2")
            "shift_jis", "shift-jis", "sjis", "x-sjis" -> charsetOrNull("windows-31j") ?: charsetOrNull("Shift_JIS")
            "euc-jp" -> charsetOrNull("EUC-JP")
            else -> charsetOrNull(label)
        }
    }

    private fun charsetOrNull(name: String): Charset? =
        try {
            Charset.forName(name)
        } catch (_: Exception) {
            null // IllegalCharsetNameException, UnsupportedCharsetException
        }

    // ---- Tag scanning (`LinkPageMetadata.swift:140-253`) -------------------------------------------

    /**
     * Everything before `</head>` (or `<body`), so a page that quotes markup in its body cannot inject
     * a second set of tags (`LinkPageMetadata.swift:142-151`).
     */
    private fun headSection(html: String): String {
        val end = html.indexOf("</head", ignoreCase = true).takeIf { it >= 0 }
            ?: html.indexOf("<body", ignoreCase = true).takeIf { it >= 0 }
            ?: return html
        return html.substring(0, end)
    }

    /** The raw source of every `<name …>` tag, without the name and the closing `>` (`:153-172`). */
    private fun tags(name: String, html: String): List<String> {
        val result = ArrayList<String>()
        val opener = "<$name"
        var cursor = 0
        while (true) {
            val start = html.indexOf(opener, cursor, ignoreCase = true)
            if (start < 0) break
            // `<meta` must be followed by whitespace, `>` or `/`; `<metadata>` is not a meta tag.
            val after = start + opener.length
            if (after >= html.length) break
            val next = html[after]
            if (!isWhitespace(next) && next != '>' && next != '/') {
                cursor = after
                continue
            }
            val end = tagEnd(html, after)
            if (end < 0) break
            result += html.substring(after, end)
            cursor = end + 1
        }
        return result
    }

    /**
     * The `>` that closes a tag whose attributes start at [start] (`LinkPageMetadata.swift:174-203`):
     * HTML allows a bare `>` inside a quoted value, and titles such as "Rust > Go?" do show up in
     * `og:title`. A quote opens a value only right after `=` (whitespace between allowed); an
     * unquoted value ends at whitespace, so quotes inside it are text. −1 for a tag left open (an
     * unterminated quote runs to the end, as in a browser).
     */
    private fun tagEnd(html: String, start: Int): Int {
        var index = start
        var afterEquals = false
        while (index < html.length) {
            val character = html[index]
            if (character == '>') return index
            if (character == '=') {
                afterEquals = true
            } else if (afterEquals && (character == '"' || character == '\'')) {
                // A quoted value runs to its closing quote, whatever it contains.
                val close = html.indexOf(character, index + 1)
                if (close < 0) return -1
                index = close
                afterEquals = false
            } else if (!isWhitespace(character)) {
                afterEquals = false
            }
            index++
        }
        return -1
    }

    /**
     * Attribute map of one tag's source, keys lowercased; `"…"`, `'…'` and bare values; the first
     * occurrence of a name wins; `/` and whitespace separate (`LinkPageMetadata.swift:205-253`).
     */
    fun attributes(tag: String): Map<String, String> {
        val attributes = HashMap<String, String>()
        var index = 0

        fun skipWhitespace() {
            while (index < tag.length && (isWhitespace(tag[index]) || tag[index] == '/')) index++
        }

        while (true) {
            skipWhitespace()
            if (index >= tag.length) break
            val nameStart = index
            while (index < tag.length && !isWhitespace(tag[index]) && tag[index] != '=' && tag[index] != '/') index++
            val name = tag.substring(nameStart, index).lowercase()
            skipWhitespace()
            if (index >= tag.length || tag[index] != '=') {
                if (name.isNotEmpty() && name !in attributes) attributes[name] = ""
                continue
            }
            index++
            while (index < tag.length && isWhitespace(tag[index])) index++
            if (index >= tag.length) break
            val value: String
            if (tag[index] == '"' || tag[index] == '\'') {
                val quote = tag[index]
                val valueStart = index + 1
                val valueEnd = tag.indexOf(quote, valueStart).takeIf { it >= 0 } ?: tag.length
                value = tag.substring(valueStart, valueEnd)
                index = if (valueEnd < tag.length) valueEnd + 1 else valueEnd
            } else {
                val valueStart = index
                while (index < tag.length && !isWhitespace(tag[index])) index++
                value = tag.substring(valueStart, index)
            }
            if (name.isNotEmpty() && name !in attributes) attributes[name] = value
        }
        return attributes
    }

    /** `<title …>` up to `</title`, entity-decoded (`LinkPageMetadata.swift:255-261`). */
    private fun title(head: String): String? {
        val open = head.indexOf("<title", ignoreCase = true)
        if (open < 0) return null
        val openEnd = head.indexOf('>', open + "<title".length)
        if (openEnd < 0) return null
        val close = head.indexOf("</title", openEnd + 1, ignoreCase = true)
        if (close < 0) return null
        return decodeEntities(head.substring(openEnd + 1, close))
    }

    /** The first value that is not empty once trimmed, trimmed (`LinkPageMetadata.swift:263-270`). */
    private fun firstNonEmpty(vararg values: String?): String? {
        for (value in values) {
            val trimmed = value?.let(WireText::trimWhitespacesAndNewlines)
            if (!trimmed.isNullOrEmpty()) return trimmed
        }
        return null
    }

    /**
     * Resolves a (possibly relative or protocol-relative) image reference against the page to an
     * `https` URL (`LinkPageMetadata.swift:272-286`): `http` is upgraded rather than fetched in the
     * clear; anything else (`data:`, `javascript:`) is dropped. OkHttp only resolves `http`/`https`.
     */
    fun secureUrl(raw: String, page: HttpUrl): HttpUrl? {
        val resolved = page.resolve(raw) ?: return null
        return if (resolved.isHttps) resolved else resolved.newBuilder().scheme("https").build()
    }

    // ---- Entities (`LinkPageMetadata.swift:288-337`) -----------------------------------------------

    private val NAMED_ENTITIES: Map<String, String> = mapOf(
        "amp" to "&", "lt" to "<", "gt" to ">", "quot" to "\"", "apos" to "'", "nbsp" to " ",
        "ndash" to "–", "mdash" to "—", "hellip" to "…", "laquo" to "«", "raquo" to "»",
        "lsquo" to "‘", "rsquo" to "’", "ldquo" to "“", "rdquo" to "”", "sbquo" to "‚", "bdquo" to "„",
        "bull" to "•", "middot" to "·", "copy" to "©", "reg" to "®", "trade" to "™", "euro" to "€",
        "pound" to "£", "yen" to "¥", "deg" to "°", "times" to "×", "shy" to "",
        "auml" to "ä", "ouml" to "ö", "uuml" to "ü", "Auml" to "Ä", "Ouml" to "Ö", "Uuml" to "Ü", "szlig" to "ß",
        "eacute" to "é", "egrave" to "è", "ecirc" to "ê", "aacute" to "á", "agrave" to "à", "acirc" to "â",
        "oacute" to "ó", "ograve" to "ò", "ocirc" to "ô", "uacute" to "ú", "iacute" to "í", "ccedil" to "ç",
        "ntilde" to "ñ", "Eacute" to "É", "oslash" to "ø", "aring" to "å", "aelig" to "æ",
    )

    /**
     * Decodes `&amp;`, `&#39;`, `&#x27;` and the common named entities (`LinkPageMetadata.swift:302-327`).
     * An entity ends at a `;` within 12 characters of its `&`; unknown entities stay verbatim.
     */
    fun decodeEntities(text: String): String {
        if ('&' !in text) return text
        val output = StringBuilder(text.length)
        var index = 0
        while (index < text.length) {
            val character = text[index]
            val semicolon = if (character == '&') text.indexOf(';', index).takeIf { it in index until index + 12 } else null
            if (semicolon == null) {
                output.append(character)
                index++
                continue
            }
            val replacement = entityValue(text.substring(index + 1, semicolon))
            if (replacement != null) {
                output.append(replacement)
                index = semicolon + 1
            } else {
                output.append(character)
                index++
            }
        }
        return output.toString()
    }

    /** `LinkPageMetadata.swift:329-337`: `#x…`/`#X…` hex, `#…` decimal (a Unicode scalar), else the table. */
    private fun entityValue(entity: String): String? {
        val codePoint = when {
            entity.startsWith("#x") || entity.startsWith("#X") -> swiftUInt32(entity.substring(2), 16)
            entity.startsWith("#") -> swiftUInt32(entity.substring(1), 10)
            else -> return NAMED_ENTITIES[entity]
        } ?: return null
        // Swift `Unicode.Scalar(UInt32)`: no surrogates, nothing past U+10FFFF.
        if (codePoint > 0x10FFFFL || codePoint in 0xD800L..0xDFFFL) return null
        return String(Character.toChars(codePoint.toInt()))
    }

    /**
     * Swift `UInt32(text, radix:)`: an optional `+` (or `-` before a zero), then ASCII digits of the
     * radix; null on anything else or past `UInt32.max`.
     */
    private fun swiftUInt32(text: String, radix: Int): Long? {
        var digits = text
        var negative = false
        if (digits.startsWith('+') || digits.startsWith('-')) {
            negative = digits[0] == '-'
            digits = digits.substring(1)
        }
        if (digits.isEmpty()) return null
        var value = 0L
        for (c in digits) {
            val digit = asciiDigit(c, radix) ?: return null
            value = value * radix + digit
            if (value > 0xFFFF_FFFFL) return null
        }
        if (negative && value != 0L) return null
        return value
    }

    /**
     * Swift `Int(text)`: an optional sign and ASCII digits, no spaces; null on overflow. Kotlin's
     * `toIntOrNull` would also take non-ASCII digits (`"١٢"`).
     */
    private fun swiftInt(text: String): Int? {
        var digits = text
        var negative = false
        if (digits.startsWith('+') || digits.startsWith('-')) {
            negative = digits[0] == '-'
            digits = digits.substring(1)
        }
        if (digits.isEmpty() || digits.any { it !in '0'..'9' }) return null
        val value = digits.toLongOrNull() ?: return null
        val signed = if (negative) -value else value
        return signed.takeIf { it in Int.MIN_VALUE..Int.MAX_VALUE }?.toInt()
    }

    private fun asciiDigit(c: Char, radix: Int): Int? {
        val digit = when (c) {
            in '0'..'9' -> c - '0'
            in 'a'..'z' -> c - 'a' + 10
            in 'A'..'Z' -> c - 'A' + 10
            else -> return null
        }
        return digit.takeIf { it < radix }
    }

    private fun isWhitespace(c: Char): Boolean = WireText.isWhitespaceOrNewline(c.code)
}
