package de.corespace.shroud.core.media.scrub

// Byte-level helpers shared by the scrubbers and the header readers. Every reader is bounds-checked
// by its caller; these only assemble integers.

internal fun ByteArray.u8(i: Int): Int = this[i].toInt() and 0xFF

internal fun ByteArray.u16be(i: Int): Int = (u8(i) shl 8) or u8(i + 1)

internal fun ByteArray.u16le(i: Int): Int = u8(i) or (u8(i + 1) shl 8)

internal fun ByteArray.u24le(i: Int): Int = u8(i) or (u8(i + 1) shl 8) or (u8(i + 2) shl 16)

internal fun ByteArray.u32be(i: Int): Long =
    (u8(i).toLong() shl 24) or (u8(i + 1).toLong() shl 16) or (u8(i + 2).toLong() shl 8) or u8(i + 3).toLong()

internal fun ByteArray.u32le(i: Int): Long =
    u8(i).toLong() or (u8(i + 1).toLong() shl 8) or (u8(i + 2).toLong() shl 16) or (u8(i + 3).toLong() shl 24)

internal fun ByteArray.u64be(i: Int): Long = (u32be(i) shl 32) or u32be(i + 4)

/** Big-endian unsigned integer of [size] bytes (0…8); 0 bytes read as 0. */
internal fun ByteArray.uintBe(i: Int, size: Int): Long {
    var value = 0L
    for (k in 0 until size) value = (value shl 8) or u8(i + k).toLong()
    return value
}

internal fun ByteArray.putU16be(i: Int, value: Int) {
    this[i] = (value ushr 8).toByte()
    this[i + 1] = value.toByte()
}

internal fun ByteArray.putU32be(i: Int, value: Long) {
    this[i] = (value ushr 24).toByte()
    this[i + 1] = (value ushr 16).toByte()
    this[i + 2] = (value ushr 8).toByte()
    this[i + 3] = value.toByte()
}

/** True when the ASCII text [text] sits at [i] (and fits before [end]). */
internal fun ByteArray.hasAscii(i: Int, text: String, end: Int = size): Boolean {
    if (i < 0 || i + text.length > end) return false
    for (k in text.indices) if (this[i + k] != text[k].code.toByte()) return false
    return true
}

/** True when [prefix] sits at [i] (and fits before [end]). */
internal fun ByteArray.hasBytes(i: Int, prefix: ByteArray, end: Int = size): Boolean {
    if (i < 0 || i + prefix.size > end) return false
    for (k in prefix.indices) if (this[i + k] != prefix[k]) return false
    return true
}

/** Four bytes at [i] as ASCII (a box type, a chunk type, a brand). */
internal fun ByteArray.fourCc(i: Int): String = String(CharArray(4) { (this[i + it].toInt() and 0xFF).toChar() })

/** First index of [needle] in `[from, to)`, or −1. */
internal fun ByteArray.indexOf(needle: ByteArray, from: Int, to: Int = size): Int {
    if (needle.isEmpty()) return -1
    val last = to - needle.size
    var i = maxOf(0, from)
    val first = needle[0]
    while (i <= last) {
        if (this[i] == first && hasBytes(i, needle, to)) return i
        i++
    }
    return -1
}

/** True when every byte of `[from, to)` equals [value]. */
internal fun ByteArray.allEqual(from: Int, to: Int, value: Byte): Boolean {
    for (i in from until to) if (this[i] != value) return false
    return true
}

internal const val SPACE: Byte = 0x20
internal const val ZERO: Byte = 0
