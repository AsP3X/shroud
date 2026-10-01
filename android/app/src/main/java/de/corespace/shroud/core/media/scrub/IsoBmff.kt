package de.corespace.shroud.core.media.scrub

/**
 * ISO base media file format reading for HEIF/HEIC/AVIF images (ISO/IEC 14496-12, 23008-12): the
 * top-level boxes, and the `meta` box's item information (`iinf`/`infe`), item locations (`iloc`),
 * primary item (`pitm`), properties (`iprp`: `ipco` + `ipma`) and item data (`idat`). Read-only; the
 * scrubber ([HeifScrubber]) and the header reader ([ImageHeader]) work from this model. Every
 * structural problem yields null — the callers then fall back to re-encoding.
 */
internal object IsoBmff {
    /** One box: [start] is its size field, the payload is `[payload, end)`. */
    class Box(val type: String, val start: Int, val headerSize: Int, val end: Int) {
        val payload: Int get() = start + headerSize
    }

    /** The boxes filling `[from, to)` exactly, or null when they do not. */
    fun boxes(b: ByteArray, from: Int, to: Int): List<Box>? {
        if (from < 0 || to > b.size || from > to) return null
        val result = ArrayList<Box>()
        var i = from
        while (i < to) {
            if (i + 8 > to) return null
            val size32 = b.u32be(i)
            val type = b.fourCc(i + 4)
            var header = 8
            val size: Long = when (size32) {
                0L -> (to - i).toLong()
                1L -> {
                    if (i + 16 > to) return null
                    header = 16
                    b.u64be(i + 8)
                }
                else -> size32
            }
            if (type == "uuid") header += 16
            if (size < header || i + size > to) return null
            result += Box(type, i, header, (i + size).toInt())
            i += size.toInt()
        }
        return result
    }

    class ItemInfo(
        val id: Long,
        val type: String,
        val protectionIndex: Int,
        /** The item name's bytes, without its terminating zero. */
        val nameStart: Int,
        val nameEnd: Int,
        /** `mime` items: their content type (`application/rdf+xml` for XMP). */
        val contentType: String?,
    )

    class ItemLocation(
        val id: Long,
        /** 0 = file offsets, 1 = `idat` offsets, 2 = other items. */
        val constructionMethod: Int,
        val dataReferenceIndex: Int,
        /** Absolute file ranges `[first, last]` in order; empty for construction method 2. */
        val extents: List<LongRange>,
    )

    /** What a HEIF file's `meta` box says. */
    class Heif(
        val topLevel: List<Box>,
        val meta: Box,
        val metaChildren: List<Box>,
        val handler: String?,
        /** The `hdlr` box; its name string (from [HANDLER_NAME_OFFSET] of the payload) can name the writing software. */
        val handlerBox: Box?,
        val primaryItem: Long?,
        val items: List<ItemInfo>,
        val locations: Map<Long, ItemLocation>,
        val idat: Box?,
        val properties: List<Box>,
        /** Item id → 1-based indices into [properties]. */
        val associations: Map<Long, List<Int>>,
    )

    /**
     * The `ftyp` major brand and the compatible brands present, or null when the bytes do not start
     * with `ftyp`. Like the web's `isHeif` (`web/src/media/heic.ts:5-10`) a header cut after the major
     * brand still sniffs.
     */
    fun brands(b: ByteArray): List<String>? {
        if (b.size < 12 || !b.hasAscii(4, "ftyp")) return null
        val size = b.u32be(0)
        if (size < 12) return null
        val brands = ArrayList<String>()
        brands += b.fourCc(8)
        val end = minOf(size, b.size.toLong()).toInt()
        var i = 16
        while (i + 4 <= end) {
            brands += b.fourCc(i)
            i += 4
        }
        return brands
    }

    /** Parses a HEIF file, or null when its structure is not one this reader understands. */
    fun parseHeif(b: ByteArray): Heif? {
        val top = boxes(b, 0, b.size) ?: return null
        if (top.isEmpty() || top[0].type != "ftyp") return null
        val metas = top.filter { it.type == "meta" }
        if (metas.size != 1) return null
        val meta = metas[0]
        val children = boxes(b, meta.payload + 4, meta.end) ?: return null
        var handler: String? = null
        var handlerBox: Box? = null
        var primary: Long? = null
        var items: List<ItemInfo> = emptyList()
        var ilocBox: Box? = null
        var idat: Box? = null
        var properties: List<Box> = emptyList()
        var associations: Map<Long, List<Int>> = emptyMap()
        for (child in children) {
            when (child.type) {
                "hdlr" -> {
                    if (child.payload + 12 > child.end) return null
                    handler = b.fourCc(child.payload + 8)
                    handlerBox = child
                }
                "pitm" -> {
                    val version = b.u8(child.payload)
                    val at = child.payload + 4
                    primary = if (version == 0) {
                        if (at + 2 > child.end) return null
                        b.u16be(at).toLong()
                    } else {
                        if (at + 4 > child.end) return null
                        b.u32be(at)
                    }
                }
                "iinf" -> items = parseIinf(b, child) ?: return null
                "iloc" -> ilocBox = child
                "idat" -> idat = child
                "iprp" -> {
                    val iprp = boxes(b, child.payload, child.end) ?: return null
                    val ipco = iprp.firstOrNull { it.type == "ipco" }
                    if (ipco != null) properties = boxes(b, ipco.payload, ipco.end) ?: return null
                    val ipma = iprp.firstOrNull { it.type == "ipma" }
                    if (ipma != null) associations = parseIpma(b, ipma) ?: return null
                }
            }
        }
        val locations = ilocBox?.let { parseIloc(b, it, idat) ?: return null } ?: emptyMap()
        return Heif(top, meta, children, handler, handlerBox, primary, items, locations, idat, properties, associations)
    }

    /** `hdlr` payload: version/flags (4), pre_defined (4), handler_type (4), reserved (12), then the name. */
    const val HANDLER_NAME_OFFSET = 24

    /**
     * True when the `dinf` box [dinf] says only "the data is in this file": one `dref` whose
     * entries are all self-contained (`flags & 1`) `url ` boxes with no location string
     * (ISO/IEC 14496-12 §8.7.2). Anything else names another place — refused by the scrubber.
     */
    fun isSelfContainedDinf(b: ByteArray, dinf: Box): Boolean {
        val children = boxes(b, dinf.payload, dinf.end) ?: return false
        if (children.size != 1 || children[0].type != "dref") return false
        val dref = children[0]
        if (dref.payload + 8 > dref.end) return false
        val count = b.u32be(dref.payload + 4)
        val entries = boxes(b, dref.payload + 8, dref.end) ?: return false
        if (entries.size.toLong() != count) return false
        return entries.all { entry ->
            entry.type == "url " && entry.payload + 4 == entry.end && (b.u8(entry.payload + 3) and 1) == 1
        }
    }

    private fun parseIinf(b: ByteArray, box: Box): List<ItemInfo>? {
        if (box.payload + 4 > box.end) return null
        val version = b.u8(box.payload)
        val first = box.payload + 4 + if (version == 0) 2 else 4
        val entries = boxes(b, first, box.end) ?: return null
        return entries.filter { it.type == "infe" }.map { parseInfe(b, it) ?: return null }
    }

    /** `infe` versions 2 and 3 (every HEIF writer); versions 0/1 have no item type and are refused. */
    private fun parseInfe(b: ByteArray, box: Box): ItemInfo? {
        if (box.payload + 4 > box.end) return null
        val version = b.u8(box.payload)
        if (version < 2) return null
        var p = box.payload + 4
        val id: Long
        if (version == 2) {
            if (p + 2 > box.end) return null
            id = b.u16be(p).toLong()
            p += 2
        } else {
            if (p + 4 > box.end) return null
            id = b.u32be(p)
            p += 4
        }
        if (p + 6 > box.end) return null
        val protection = b.u16be(p)
        val type = b.fourCc(p + 2)
        p += 6
        val nameEnd = zeroAt(b, p, box.end) ?: return null
        val nameStart = p
        p = nameEnd + 1
        var contentType: String? = null
        if (type == "mime") {
            val end = zeroAt(b, p, box.end) ?: return null
            contentType = String(b, p, end - p, Charsets.UTF_8)
        }
        return ItemInfo(id, type, protection, nameStart, nameEnd, contentType)
    }

    private fun zeroAt(b: ByteArray, from: Int, to: Int): Int? {
        for (i in from until to) if (b[i] == ZERO) return i
        return null
    }

    private fun parseIloc(b: ByteArray, box: Box, idat: Box?): Map<Long, ItemLocation>? {
        var p = box.payload
        if (p + 6 > box.end) return null
        val version = b.u8(p)
        if (version > 2) return null
        p += 4
        val offsetSize = b.u8(p) ushr 4
        val lengthSize = b.u8(p) and 0x0F
        val baseOffsetSize = b.u8(p + 1) ushr 4
        val indexSize = if (version == 1 || version == 2) b.u8(p + 1) and 0x0F else 0
        p += 2
        for (size in intArrayOf(offsetSize, lengthSize, baseOffsetSize, indexSize)) if (size != 0 && size != 4 && size != 8) return null
        val count: Long
        if (version < 2) {
            if (p + 2 > box.end) return null
            count = b.u16be(p).toLong()
            p += 2
        } else {
            if (p + 4 > box.end) return null
            count = b.u32be(p)
            p += 4
        }
        val result = LinkedHashMap<Long, ItemLocation>()
        for (n in 0 until count) {
            val idSize = if (version < 2) 2 else 4
            val fixed = idSize + (if (version == 1 || version == 2) 2 else 0) + 2 + baseOffsetSize + 2
            if (p + fixed > box.end) return null
            val id = b.uintBe(p, idSize)
            p += idSize
            var method = 0
            if (version == 1 || version == 2) {
                method = b.u16be(p) and 0x0F
                p += 2
            }
            val dataReference = b.u16be(p)
            p += 2
            val base = b.uintBe(p, baseOffsetSize)
            p += baseOffsetSize
            val extentCount = b.u16be(p)
            p += 2
            val extents = ArrayList<LongRange>()
            for (e in 0 until extentCount) {
                if (p + indexSize + offsetSize + lengthSize > box.end) return null
                p += indexSize
                val offset = b.uintBe(p, offsetSize)
                p += offsetSize
                var length = b.uintBe(p, lengthSize)
                p += lengthSize
                if (base < 0 || offset < 0 || length < 0) return null
                when (method) {
                    0 -> {
                        val start = base + offset
                        if (length == 0L) length = b.size - start // to the end of the file
                        if (start + length > b.size || length <= 0) return null
                        extents += start until start + length
                    }
                    1 -> {
                        val data = idat ?: return null
                        val start = data.payload + base + offset
                        if (length == 0L) length = data.end - start
                        if (start + length > data.end || length <= 0) return null
                        extents += start until start + length
                    }
                    2 -> Unit
                    else -> return null
                }
            }
            result[id] = ItemLocation(id, method, dataReference, extents)
        }
        return result
    }

    private fun parseIpma(b: ByteArray, box: Box): Map<Long, List<Int>>? {
        var p = box.payload
        if (p + 8 > box.end) return null
        val version = b.u8(p)
        val wide = (b.u8(p + 3) and 1) == 1
        p += 4
        val count = b.u32be(p)
        p += 4
        val result = HashMap<Long, List<Int>>()
        for (n in 0 until count) {
            val idSize = if (version < 1) 2 else 4
            if (p + idSize + 1 > box.end) return null
            val id = b.uintBe(p, idSize)
            p += idSize
            val associations = b.u8(p)
            p += 1
            val indices = ArrayList<Int>(associations)
            for (a in 0 until associations) {
                if (wide) {
                    if (p + 2 > box.end) return null
                    indices += b.u16be(p) and 0x7FFF
                    p += 2
                } else {
                    if (p + 1 > box.end) return null
                    indices += b.u8(p) and 0x7F
                    p += 1
                }
            }
            result[id] = indices
        }
        return result
    }

    /** The property boxes associated with [item] (index 0 = "no property" is skipped). */
    fun propertiesOf(heif: Heif, item: Long): List<Box> =
        heif.associations[item].orEmpty().mapNotNull { index -> heif.properties.getOrNull(index - 1) }
}
