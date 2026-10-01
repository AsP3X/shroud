package de.corespace.shroud.core.media.scrub

/**
 * XMP packets, blanked in place: port of iOS `MediaMetadataScrubber.blankXMP` and the private
 * `XMPBlanker` scanner (`ios/shroud/Services/Crypto/MediaMetadataScrubber.swift:184-210, :240-244,
 * :279-413`), byte for byte on a `ByteArray`.
 *
 * Every disallowed property in every `<x:xmpmeta>`/`<x:xapmeta>` packet is overwritten with
 * spaces. Byte length never changes, so container offsets (HEIF `iloc`, JPEG MPF) stay valid, and
 * the result is still well-formed XML: whitespace is legal between elements and attributes.
 *
 * Kept: properties in the HDR gain-map namespaces `hdrgm` (ISO 21496-1 / Adobe) and `HDRGainMap`
 * (Apple), plus `tiff:Orientation`. Structural elements (`x:xmpmeta`, `x:xapmeta`, `rdf:*`) stay with
 * only their `xmlns`, `xmlns:*`, `rdf:*`, `x:*`, `xml:*` and kept attributes. Decision
 * (media-voice-links §5.2): Google's `Container:`/`Item:` (GContainer) and `GCamera:` namespaces are
 * **not** kept — they only locate motion-photo videos and duplicate MPF for gain maps; Skia finds the
 * gain map through MPF + `hdrgm`.
 *
 * [audit] is the verification side (iOS reads XMP tags back through ImageIO, `:57-65`): it lists
 * every property left in the packets that fails [MediaMetadataScrubber.isAllowedTagPath].
 */
internal object XmpBlanker {
    /** iOS `keptXMPPrefixes` (`:188`). An unknown prefix is removed — a lost HDR hint beats a leak. */
    val KEPT_PREFIXES: Set<String> = setOf("hdrgm", "HDRGainMap")

    /** iOS `keptXMPProperties` (`:190`). */
    val KEPT_PROPERTIES: Set<String> = setOf("tiff:Orientation")

    private val OPEN_XMPMETA = "<x:xmpmeta".toByteArray(Charsets.US_ASCII)
    private val OPEN_XAPMETA = "<x:xapmeta".toByteArray(Charsets.US_ASCII)
    private val CLOSE_XMPMETA = "</x:xmpmeta>".toByteArray(Charsets.US_ASCII)
    private val CLOSE_XAPMETA = "</x:xapmeta>".toByteArray(Charsets.US_ASCII)
    private val BARE_RDF = "<rdf:".toByteArray(Charsets.US_ASCII)

    /** iOS `isKeptXMPName` (`:240-244`). */
    fun isKeptName(qualified: String): Boolean {
        if (qualified in KEPT_PROPERTIES) return true
        val colon = qualified.indexOf(':')
        if (colon < 0) return false
        return qualified.substring(0, colon) in KEPT_PREFIXES
    }

    /** iOS `blankXMP(in:)` (`:196-210`), limited to `[from, to)`. */
    fun blank(bytes: ByteArray, from: Int = 0, to: Int = bytes.size) {
        for (packet in packets(bytes, from, to)) {
            if (packet.closed) Scanner(bytes, packet.start, packet.end).blank() else break
        }
    }

    /**
     * Properties left in `[from, to)` that are not allowed (`XMP.<name>`), plus `XMP.unterminated`
     * for a packet without its end tag and `XMP.bare` for RDF outside any packet. Empty = clean.
     */
    fun audit(bytes: ByteArray, from: Int = 0, to: Int = bytes.size): List<String> {
        val found = ArrayList<String>()
        var outsideFrom = from
        for (packet in packets(bytes, from, to)) {
            if (bytes.indexOf(BARE_RDF, outsideFrom, packet.start) >= 0) found += "XMP.bare"
            if (!packet.closed) {
                found += "XMP.unterminated"
                return found
            }
            Scanner(bytes, packet.start, packet.end).disallowed(found)
            outsideFrom = packet.end
        }
        if (bytes.indexOf(BARE_RDF, outsideFrom, to) >= 0) found += "XMP.bare"
        return found
    }

    /** True when `[from, to)` holds an XMP packet or bare RDF. */
    fun mentionsXmp(bytes: ByteArray, from: Int = 0, to: Int = bytes.size): Boolean =
        bytes.indexOf(OPEN_XMPMETA, from, to) >= 0 || bytes.indexOf(OPEN_XAPMETA, from, to) >= 0 ||
            bytes.indexOf(BARE_RDF, from, to) >= 0

    private class Packet(val start: Int, val end: Int, val closed: Boolean)

    /** The packet loop of `blankXMP` (`:198-208`): `<x:xmpmeta` first, else `<x:xapmeta`, up to its close tag. */
    private fun packets(bytes: ByteArray, from: Int, to: Int): List<Packet> {
        val result = ArrayList<Packet>()
        var searchFrom = from
        while (true) {
            var open = bytes.indexOf(OPEN_XMPMETA, searchFrom, to)
            if (open < 0) open = bytes.indexOf(OPEN_XAPMETA, searchFrom, to)
            if (open < 0) break
            val closeTag = if (bytes[open + 4] == 'm'.code.toByte()) CLOSE_XMPMETA else CLOSE_XAPMETA
            val close = bytes.indexOf(closeTag, open, to)
            if (close < 0) {
                result += Packet(open, to, closed = false)
                break
            }
            val end = close + closeTag.size
            result += Packet(open, end, closed = true)
            searchFrom = end
        }
        return result
    }

    /**
     * A minimal scanner over one XMP packet `[lower, upper)` (iOS `XMPBlanker`, `:281-413`). It only
     * finds element and attribute boundaries — never builds a tree — because disallowed properties
     * are blanked, not moved.
     */
    private class Scanner(private val storage: ByteArray, private val lower: Int, private val upper: Int) {
        private class Attribute(val name: String, val spanStart: Int, val spanEnd: Int)

        private class StartTag(val name: String, val attributes: List<Attribute>, val end: Int, val selfClosing: Boolean)

        /** iOS `run()` (`:286-310`). */
        fun blank() {
            var index = lower
            while (index < upper) {
                if (storage[index] != LT) {
                    index++
                    continue
                }
                skipMarkup(index)?.let {
                    index = it
                    continue
                }
                val tag = parseStartTag(index)
                if (isStructural(tag.name)) {
                    for (attribute in tag.attributes) if (!keepsAttribute(attribute.name)) blank(attribute.spanStart, attribute.spanEnd)
                    index = tag.end
                } else if (isKeptName(tag.name)) {
                    index = if (tag.selfClosing) tag.end else endOfElement(tag.name, tag.end)
                } else {
                    val end = if (tag.selfClosing) tag.end else endOfElement(tag.name, tag.end)
                    blank(index, end)
                    index = end
                }
            }
        }

        /**
         * The same walk, listing what [MediaMetadataScrubber.isAllowedTagPath] refuses instead of
         * blanking it. Text outside every property (not valid RDF, never written by cameras) is
         * reported as `XMP.text`: the blanker would leave it, so such a packet is not proven clean.
         */
        fun disallowed(found: MutableList<String>) {
            var index = lower
            var strayText = false
            while (index < upper) {
                if (storage[index] != LT) {
                    if (!strayText && !isSpace(storage[index])) {
                        strayText = true
                        found += "XMP.text"
                    }
                    index++
                    continue
                }
                skipMarkup(index)?.let {
                    index = it
                    continue
                }
                val tag = parseStartTag(index)
                if (isStructural(tag.name)) {
                    for (attribute in tag.attributes) {
                        if (isStructuralAttribute(attribute.name)) continue
                        if (!MediaMetadataScrubber.isAllowedTagPath(attribute.name)) found += "XMP." + attribute.name
                    }
                    index = tag.end
                } else {
                    if (!MediaMetadataScrubber.isAllowedTagPath(tag.name)) found += "XMP." + tag.name
                    index = if (tag.selfClosing) tag.end else endOfElement(tag.name, tag.end)
                }
            }
        }

        /** `<?…?>`, `<!--…-->`, `<![CDATA[…]]>` and end tags (`:290-293`); null for a start tag. */
        private fun skipMarkup(index: Int): Int? = when {
            starts("<?", index) -> after("?>", index)
            starts("<!--", index) -> after("-->", index)
            starts("<![CDATA[", index) -> after("]]>", index)
            starts("</", index) -> after(">", index)
            else -> null
        }

        private fun isStructural(name: String): Boolean = name == "x:xmpmeta" || name == "x:xapmeta" || name.startsWith("rdf:")

        private fun isStructuralAttribute(name: String): Boolean =
            name == "xmlns" || name.startsWith("xmlns:") || name.startsWith("rdf:") || name.startsWith("x:") || name.startsWith("xml:")

        /** iOS `keepsAttribute` (`:319-322`). */
        private fun keepsAttribute(name: String): Boolean = isStructuralAttribute(name) || isKeptName(name)

        private fun isNameByte(byte: Byte): Boolean =
            !(byte == SP || byte == TAB || byte == LF || byte == CR || byte == GT || byte == SLASH || byte == EQ)

        private fun isSpace(byte: Byte): Boolean = byte == SP || byte == TAB || byte == LF || byte == CR

        /** iOS `parseStartTag(at:)` (`:334-364`). */
        private fun parseStartTag(start: Int): StartTag {
            var index = start + 1
            val nameStart = index
            while (index < upper && isNameByte(storage[index])) index++
            val name = String(storage, nameStart, index - nameStart, Charsets.UTF_8)
            val attributes = ArrayList<Attribute>()
            while (index < upper) {
                val spanStart = index
                while (index < upper && isSpace(storage[index])) index++
                if (index >= upper) break
                if (storage[index] == GT) return StartTag(name, attributes, index + 1, selfClosing = false)
                if (storage[index] == SLASH) return StartTag(name, attributes, minOf(index + 2, upper), selfClosing = true)
                val attributeNameStart = index
                while (index < upper && isNameByte(storage[index])) index++
                val attributeName = String(storage, attributeNameStart, index - attributeNameStart, Charsets.UTF_8)
                while (index < upper && (isSpace(storage[index]) || storage[index] == EQ)) index++
                if (index >= upper) break
                val quote = storage[index]
                if (quote == DQUOTE || quote == SQUOTE) {
                    index++
                    while (index < upper && storage[index] != quote) index++
                    index = minOf(index + 1, upper)
                }
                attributes += Attribute(attributeName, spanStart, index)
            }
            return StartTag(name, attributes, upper, selfClosing = false)
        }

        /**
         * Index just past the end tag matching an element opened before [start], counting nested
         * elements of the same name (iOS `endOfElement(named:openedAt:)`, `:368-390`).
         */
        private fun endOfElement(name: String, start: Int): Int {
            val open = "<$name".toByteArray(Charsets.UTF_8)
            val close = "</$name".toByteArray(Charsets.UTF_8)
            var depth = 1
            var index = start
            while (index < upper) {
                if (matches(close, index)) {
                    depth--
                    val end = after(">", index)
                    if (depth == 0) return end
                    index = end
                } else if (matches(open, index) && index + open.size < upper &&
                    (!isNameByte(storage[index + open.size]) || storage[index + open.size] == SLASH)
                ) {
                    val tag = parseStartTag(index)
                    if (!tag.selfClosing) depth++
                    index = tag.end
                } else {
                    index++
                }
            }
            return upper
        }

        private fun matches(needle: ByteArray, index: Int): Boolean = storage.hasBytes(index, needle, upper)

        private fun starts(text: String, index: Int): Boolean = storage.hasAscii(index, text, upper)

        private fun after(text: String, index: Int): Int {
            var cursor = index
            while (cursor < upper) {
                if (storage.hasAscii(cursor, text, upper)) return cursor + text.length
                cursor++
            }
            return upper
        }

        private fun blank(from: Int, to: Int) {
            for (i in from until minOf(to, upper)) storage[i] = SPACE
        }

        private companion object {
            const val LT = '<'.code.toByte()
            const val GT = '>'.code.toByte()
            const val SLASH = '/'.code.toByte()
            const val EQ = '='.code.toByte()
            const val SP = ' '.code.toByte()
            const val TAB = '\t'.code.toByte()
            const val LF = '\n'.code.toByte()
            const val CR = '\r'.code.toByte()
            const val DQUOTE = '"'.code.toByte()
            const val SQUOTE = '\''.code.toByte()
        }
    }
}
