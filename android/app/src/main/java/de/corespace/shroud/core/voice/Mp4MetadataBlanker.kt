package de.corespace.shroud.core.voice

import java.io.File
import java.io.IOException
import java.io.RandomAccessFile

/**
 * Blanks the metadata boxes of a finished voice note in place, so the file the peer receives says
 * nothing about the phone that recorded it — the voice-note side of the media metadata rules
 * (media-voice-links §5, §5.3; plan §1.1 rule 9).
 *
 * `MediaMuxer`'s MPEG-4 writer adds a movie-level QuickTime `meta` box (`mdta` keys) carrying device
 * facts such as `com.android.version` (the Android release), and `udta` (`©xyz`) once a location is
 * set; iOS's `AVAudioRecorder` file carries neither. Every `meta` and `udta` box at file level, under
 * `moov` and under each `trak` becomes a `free` box of the same size with a zeroed payload: no
 * offset moves (the `stco` chunk offsets into `mdat` stay valid), every player skips `free`, and
 * the old bytes are gone rather than merely hidden. Sample data, `mvhd`, `tkhd` and `mdia` are never
 * touched.
 *
 * Walks box headers only (32-bit, 64-bit `largesize` and size-0 "to the end" boxes); a header that
 * runs past its parent throws [IOException], and the caller fails the take rather than send a file
 * it could not check.
 */
internal object Mp4MetadataBlanker {
    /** Metadata boxes, blanked wherever they are found. */
    private val BLANKED = setOf("meta", "udta")

    /** Boxes whose children are searched: the movie and its tracks (`moov` → `trak`). */
    private val CONTAINERS = setOf("moov", "trak")

    private val FREE = byteArrayOf('f'.code.toByte(), 'r'.code.toByte(), 'e'.code.toByte(), 'e'.code.toByte())
    private const val ZERO_CHUNK = 8 * 1024

    /** Blanks [file] in place; returns how many boxes were blanked (0 when there were none). */
    fun blank(file: File): Int = RandomAccessFile(file, "rw").use { raf -> blankRange(raf, 0L, raf.length(), depth = 0) }

    private fun blankRange(raf: RandomAccessFile, start: Long, end: Long, depth: Int): Int {
        var blanked = 0
        var offset = start
        while (end - offset >= 8) {
            raf.seek(offset)
            val size32 = raf.readInt().toLong() and 0xFFFF_FFFFL
            val type = ByteArray(4).also { raf.readFully(it) }
            var header = 8L
            val size = when (size32) {
                0L -> end - offset
                1L -> {
                    if (end - offset < 16) throw IOException("truncated box header")
                    header = 16L
                    raf.readLong()
                }
                else -> size32
            }
            if (size < header || size > end - offset) throw IOException("box overruns its parent")
            val name = String(type, Charsets.ISO_8859_1)
            when {
                name in BLANKED -> {
                    blankBox(raf, offset, size, header)
                    blanked++
                }
                name in CONTAINERS && depth < 2 -> blanked += blankRange(raf, offset + header, offset + size, depth + 1)
            }
            offset += size
        }
        return blanked
    }

    /** Renames the box at [offset] to `free` (its size field stays) and zeroes everything after the header. */
    private fun blankBox(raf: RandomAccessFile, offset: Long, size: Long, header: Long) {
        raf.seek(offset + 4)
        raf.write(FREE)
        raf.seek(offset + header)
        val zeros = ByteArray(ZERO_CHUNK)
        var remaining = size - header
        while (remaining > 0) {
            val n = minOf(remaining, ZERO_CHUNK.toLong()).toInt()
            raf.write(zeros, 0, n)
            remaining -= n
        }
    }
}
