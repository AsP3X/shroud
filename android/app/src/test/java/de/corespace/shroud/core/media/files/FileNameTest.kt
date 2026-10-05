package de.corespace.shroud.core.media.files

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * §5's name cleaning against all 20 rows of the `== names ==` block that
 * `node scripts/gen_file_vectors.mjs` prints (docs/file-sharing.md §9), inputs spelled with the
 * script's own escapes so invisible characters stay visible here.
 */
class FileNameTest {
    private val vectors = listOf(
        "report.pdf" to "report.pdf",
        "../../etc/passwd" to "passwd",
        "C:\\Users\\me\\Desktop\\Budget 2026.XLSX" to "Budget 2026.XLSX",
        "invoice\u202Efdp.exe" to "invoicefdp.exe",
        "  .hidden.txt  " to "hidden.txt",
        "what?<now>:\"x\"|*.docx" to "what__now___x___.docx",
        "tabs\tand\nnewlines.txt" to "tabs and newlines.txt",
        "many     spaces\u00A0\u3000here.csv" to "many spaces here.csv",
        "trailing dots...." to "trailing dots",
        "noext" to "noext",
        ".pdf" to "pdf",
        "..." to "file",
        "" to "file",
        "e\u0301te\u0301.txt" to "\u00E9t\u00E9.txt",
        "a".repeat(130) + ".pptx" to "a".repeat(115) + ".pptx",
        "x".repeat(200) to "x".repeat(120),
        "archive.tar.gz" to "archive.tar.gz",
        "weird.ext-with-dash" to "weird.ext-with-dash",
        "zero\u200Bwidth\uFEFF.apk" to "zerowidth.apk",
        "emoji \uD83D\uDCC4 notes.TXT" to "emoji \uD83D\uDCC4 notes.TXT",
    )

    @Test
    fun allTwentyScriptVectorsClean() {
        assertEquals(20, vectors.size)
        for ((input, expected) in vectors) assertEquals("clean(${input.take(30)})", expected, FileNames.clean(input))
    }

    @Test
    fun cleaningIsIdempotent() {
        // The sender cleans, the receiver cleans again: a cleaned name never changes.
        for ((_, expected) in vectors) assertEquals(expected, FileNames.clean(expected))
    }

    @Test
    fun theExtensionIsLowercasedAndOnlyAsciiAlphanumerics() {
        assertEquals("xlsx", FileNames.extension("Budget 2026.XLSX"))
        assertEquals("gz", FileNames.extension("archive.tar.gz"))
        assertEquals("txt", FileNames.extension("emoji \uD83D\uDCC4 notes.TXT"))
        assertNull(FileNames.extension("weird.ext-with-dash"))
        assertNull(FileNames.extension("noext"))
        assertNull(FileNames.extension("pdf"))
        assertNull(FileNames.extension("toolong.abcdefghijk"))
        assertEquals("abcdefghij", FileNames.extension("ten.abcdefghij"))
    }

    @Test
    fun aRightToLeftOverrideCannotHideTheRealExtension() {
        val cleaned = FileNames.clean("invoice\u202Efdp.exe")
        assertEquals("exe", FileNames.extension(cleaned))
        assertNull(FileTypes.forName(cleaned))
    }

    @Test
    fun longNamesKeepTheirExtensionAndCountCodePoints() {
        // 130 emoji (two UTF-16 units each) + ".pdf": the stem is cut to 116 code points.
        val cleaned = FileNames.clean("\uD83D\uDCC4".repeat(130) + ".pdf")
        assertEquals(120, cleaned.codePointCount(0, cleaned.length))
        assertEquals("pdf", FileNames.extension(cleaned))
        // A cut that ends in a space trims it again: "a"×114 + " " + "b"×20 is cut to 115 code points.
        assertEquals("a".repeat(114) + ".pptx", FileNames.clean("a".repeat(114) + "  " + "b".repeat(20) + ".pptx"))
    }
}
