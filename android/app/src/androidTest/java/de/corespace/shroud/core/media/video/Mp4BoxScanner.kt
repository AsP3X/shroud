package de.corespace.shroud.core.media.video

import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets

/**
 * Walks the ISO-BMFF / QuickTime box tree of an MP4 and reports what would tell a recipient where,
 * when or on what a clip was recorded (media-voice-links §5.3; the Android stand-in for iOS's
 * `identifyingMetadata(in:)`, `MediaMetadataScrubberTests.swift:298-309`):
 *
 * - user-data and `ilst` items `©xyz`, `loci`, `©day`, `©mak`, `©mod`, `©swr`, `XMP_`;
 * - `mdta` keys under `com.apple.quicktime.` (location, make, model, creation date…);
 * - an XMP `uuid` box;
 * - any of the given raw strings anywhere in the file (`+52.5200`, `Test Phone`, `2026-09-01`).
 *
 * Also reads the `mvhd` creation time, so a test can tell a fresh output from a copied one.
 */
class Mp4BoxScanner(private val bytes: ByteArray) {
    /** One box: its type, its path from the root ("moov/udta/©xyz") and where its payload is. */
    data class Box(val type: String, val path: String, val offset: Int, val size: Int, val headerSize: Int) {
        val payloadOffset: Int get() = offset + headerSize
        val payloadSize: Int get() = size - headerSize
    }

    val boxes: List<Box> = ArrayList<Box>().also { parse(0, bytes.size, "", it, depth = 0) }

    /** Every box of [type], anywhere. */
    fun find(type: String): List<Box> = boxes.filter { it.type == type }

    /** `mdta` key names from every `keys` box (QuickTime metadata). */
    val mdtaKeys: List<String>
        get() = find("keys").flatMap { box ->
            val buf = ByteBuffer.wrap(bytes, box.payloadOffset, box.payloadSize)
            buf.position(box.payloadOffset + 4) // version + flags
            val count = buf.int
            val keys = ArrayList<String>()
            repeat(count) {
                if (buf.remaining() < 8) return@repeat
                val size = buf.int
                buf.int // namespace, "mdta"
                if (size < 8 || size - 8 > buf.remaining()) return@repeat
                val name = ByteArray(size - 8)
                buf.get(name)
                keys += String(name, StandardCharsets.UTF_8)
            }
            keys
        }

    /** `mvhd` creation time in seconds since 1904, or null. */
    val creationTime: Long?
        get() {
            val box = find("mvhd").firstOrNull() ?: return null
            val buf = ByteBuffer.wrap(bytes, box.payloadOffset, box.payloadSize)
            val version = buf.get().toInt()
            buf.position(buf.position() + 3)
            return if (version == 1) buf.long else buf.int.toLong() and 0xFFFF_FFFFL
        }

    /** What identifies the recording; empty for a clean file. */
    fun identifyingMetadata(rawStrings: List<String> = emptyList()): List<String> {
        val found = ArrayList<String>()
        for (box in boxes) {
            if (box.type in IDENTIFYING_TYPES) found += "box ${box.path}"
            if (box.type == "uuid" && box.payloadSize >= 16 && uuidOf(box) == XMP_UUID) found += "XMP uuid ${box.path}"
        }
        mdtaKeys.filter { it.startsWith("com.apple.quicktime.") }.forEach { found += "mdta key $it" }
        val raw = String(bytes, StandardCharsets.ISO_8859_1)
        rawStrings.filter { raw.contains(it) }.forEach { found += "string \"$it\"" }
        return found
    }

    private fun uuidOf(box: Box): String =
        (0 until 16).joinToString("") { "%02x".format(bytes[box.payloadOffset + it].toInt() and 0xFF) }

    private fun parse(start: Int, end: Int, parent: String, out: MutableList<Box>, depth: Int) {
        if (depth > MAX_DEPTH) return
        var at = start
        while (at + 8 <= end) {
            var size = u32(at)
            val type = String(bytes, at + 4, 4, StandardCharsets.ISO_8859_1)
            var header = 8
            if (size == 1L) {
                if (at + 16 > end) return
                size = ByteBuffer.wrap(bytes, at + 8, 8).long
                header = 16
            } else if (size == 0L) {
                size = (end - at).toLong()
            }
            if (size < header || at + size > end) return
            val path = if (parent.isEmpty()) type else "$parent/$type"
            val box = Box(type, path, at, size.toInt(), header)
            out += box
            if (type in CONTAINERS || (parent.endsWith("ilst") && depth > 0)) {
                parse(childrenStart(box), at + size.toInt(), path, out, depth + 1)
            }
            at += size.toInt()
        }
    }

    /** `meta` is a FullBox in ISO files and a plain container in QuickTime ones. */
    private fun childrenStart(box: Box): Int {
        if (box.type != "meta") return box.payloadOffset
        val p = box.payloadOffset
        val looksFull = box.payloadSize >= 12 && u32(p) == 0L &&
            String(bytes, p + 8, 4, StandardCharsets.ISO_8859_1).let { it == "hdlr" || it == "keys" || it == "ilst" }
        return if (looksFull) p + 4 else p
    }

    private fun u32(at: Int): Long = ByteBuffer.wrap(bytes, at, 4).int.toLong() and 0xFFFF_FFFFL

    companion object {
        private const val MAX_DEPTH = 16

        private val CONTAINERS = setOf("moov", "trak", "mdia", "minf", "stbl", "udta", "meta", "ilst", "edts", "dinf", "mvex", "moof", "traf")

        /** QuickTime / iTunes user data that says where, when or on what (©xyz, loci, ©day, ©mak, ©mod…). */
        val IDENTIFYING_TYPES = setOf("©xyz", "loci", "©day", "©mak", "©mod", "©swr", "XMP_")

        /** The XMP `uuid` box (BE7ACFCB-97A9-42E8-9C71-999491E3AFAC). */
        const val XMP_UUID = "be7acfcb97a942e89c71999491e3afac"
    }
}
