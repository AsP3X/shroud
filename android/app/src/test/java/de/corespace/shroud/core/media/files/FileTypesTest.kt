package de.corespace.shroud.core.media.files

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.InputStream

/** §4's type table and content check, §2's limits at intake, and the copy of §6 and §7 word for word. */
class FileTypesTest {
    @Test
    fun theTableIsTheSpecs() {
        val expected = mapOf(
            "txt" to "text/plain", "csv" to "text/csv", "pdf" to "application/pdf",
            "docx" to "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
            "dotx" to "application/vnd.openxmlformats-officedocument.wordprocessingml.template",
            "rtf" to "application/rtf", "doc" to "application/msword", "dot" to "application/msword",
            "docm" to "application/vnd.ms-word.document.macroEnabled.12", "dotm" to "application/vnd.ms-word.template.macroEnabled.12",
            "xlsx" to "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
            "xltx" to "application/vnd.openxmlformats-officedocument.spreadsheetml.template",
            "xls" to "application/vnd.ms-excel", "xlt" to "application/vnd.ms-excel",
            "xlsm" to "application/vnd.ms-excel.sheet.macroEnabled.12", "xltm" to "application/vnd.ms-excel.template.macroEnabled.12",
            "xlsb" to "application/vnd.ms-excel.sheet.binary.macroEnabled.12",
            "pptx" to "application/vnd.openxmlformats-officedocument.presentationml.presentation",
            "ppsx" to "application/vnd.openxmlformats-officedocument.presentationml.slideshow",
            "potx" to "application/vnd.openxmlformats-officedocument.presentationml.template",
            "ppt" to "application/vnd.ms-powerpoint", "pps" to "application/vnd.ms-powerpoint", "pot" to "application/vnd.ms-powerpoint",
            "pptm" to "application/vnd.ms-powerpoint.presentation.macroEnabled.12",
            "ppsm" to "application/vnd.ms-powerpoint.slideshow.macroEnabled.12",
            "potm" to "application/vnd.ms-powerpoint.template.macroEnabled.12",
            "jpg" to "image/jpeg", "jpeg" to "image/jpeg", "png" to "image/png", "gif" to "image/gif", "webp" to "image/webp",
            "heic" to "image/heic", "heif" to "image/heif", "avif" to "image/avif", "tif" to "image/tiff", "tiff" to "image/tiff",
            "bmp" to "image/bmp", "mp4" to "video/mp4", "m4v" to "video/x-m4v", "mov" to "video/quicktime", "webm" to "video/webm",
            "mkv" to "video/x-matroska", "avi" to "video/x-msvideo", "3gp" to "video/3gpp",
            "apk" to "application/vnd.android.package-archive",
        )
        assertEquals(expected, FileTypes.all.associate { it.extension to it.mime })
        assertEquals(expected.size, FileTypes.all.size)
    }

    @Test
    fun macrosAndAppsWarnAndTheLegacyBinariesCountAsMacros() {
        val macros = setOf("doc", "dot", "docm", "dotm", "xls", "xlt", "xlsm", "xltm", "xlsb", "ppt", "pps", "pot", "pptm", "ppsm", "potm")
        assertEquals(macros, FileTypes.all.filter { it.warning == FileWarning.Macros }.map { it.extension }.toSet())
        assertEquals(listOf("apk"), FileTypes.all.filter { it.warning == FileWarning.App }.map { it.extension })
        assertFalse(FileTypes.forExtension("apk")!!.canOpen)
        assertTrue(FileTypes.all.filter { it.extension != "apk" }.all { it.canOpen })
    }

    @Test
    fun theExtensionDecidesInAnyCaseAndTheRestIsUnsupported() {
        assertEquals("application/pdf", FileTypes.forName("Report.PDF")!!.mime)
        assertEquals("PDF", FileTypes.forName("Report.PDF")!!.label)
        assertEquals(FileCategory.Excel, FileTypes.forName("C:\\x\\Budget.XLSX")!!.category)
        for (name in listOf("a.svg", "a.html", "a.htm", "a.js", "a.exe", "a.zip", "archive.tar.gz", "a.sh", "noext", "a.", ".pdf")) {
            assertNull(name, FileTypes.forName(name))
        }
    }

    @Test
    fun thePickerOffersTheTableAndAndroidsCsvAndRtfNames() {
        val offered = FileTypes.pickerMimeTypes.toSet()
        assertTrue(FileTypes.all.all { it.mime in offered })
        assertTrue("text/comma-separated-values" in offered)
        assertTrue("text/rtf" in offered)
        assertEquals(offered.size, FileTypes.pickerMimeTypes.size)
    }

    // ---- Content check (§4) ----

    private fun check(ext: String, head: ByteArray) = FileContentCheck.matches(FileTypes.forExtension(ext)!!, head)

    @Test
    fun pdfNeedsItsMarkerWithinTheFirstKilobyte() {
        assertTrue(check("pdf", "%PDF-1.7\n".toByteArray()))
        assertTrue(check("pdf", ByteArray(1000) { 0x20 } + "%PDF-".toByteArray()))
        assertFalse(check("pdf", ByteArray(1020) { 0x20 } + "%PDF-".toByteArray()))
        assertFalse(check("pdf", "PK\u0003\u0004".toByteArray()))
        assertFalse(check("pdf", ByteArray(0)))
    }

    @Test
    fun officeXmlAndApkAreZipsAndTheLegacyFormatsOle() {
        val zip = byteArrayOf(0x50, 0x4B, 0x03, 0x04, 0x14, 0)
        val ole = byteArrayOf(0xD0.toByte(), 0xCF.toByte(), 0x11, 0xE0.toByte(), 0xA1.toByte(), 0xB1.toByte(), 0x1A, 0xE1.toByte(), 0)
        for (ext in listOf("docx", "dotx", "docm", "dotm", "xlsx", "xltx", "xlsm", "xltm", "xlsb", "pptx", "ppsx", "potx", "pptm", "ppsm", "potm", "apk")) {
            assertTrue(ext, check(ext, zip))
            assertFalse(ext, check(ext, ole))
        }
        for (ext in listOf("doc", "dot", "xls", "xlt", "ppt", "pps", "pot")) {
            assertTrue(ext, check(ext, ole))
            assertFalse(ext, check(ext, zip))
            assertFalse(ext, check(ext, ole.copyOf(7)))
        }
        assertTrue(check("rtf", "{\\rtf1\\ansi".toByteArray()))
        assertFalse(check("rtf", "{\\rt".toByteArray()))
    }

    @Test
    fun textHasNoNulInItsFirst8KiB() {
        assertTrue(check("txt", "plain text\n".toByteArray()))
        assertTrue(check("csv", ByteArray(0)))
        assertFalse(check("txt", "a\u0000b".toByteArray()))
        assertFalse(check("csv", ByteArray(8191) { 0x41 } + byteArrayOf(0)))
        // The head handed in is at most 8 KiB: a NUL past it is not looked at.
        assertTrue(FileContentCheck.matches(FileTypes.forExtension("txt")!!, ByteArray(8192) { 0x41 } + byteArrayOf(0), 8192))
    }

    @Test
    fun imagesAndVideosAreLeftToTheDecoders() {
        for (ext in listOf("jpg", "png", "heic", "mp4", "mkv")) assertTrue(ext, check(ext, byteArrayOf(1, 2, 3)))
    }

    // ---- Copy (§6, §7) ----

    @Test
    fun theWordsAreTheSpecs() {
        assertEquals("Shroud can't send “a.exe”: this file type isn't supported.", FileCopy.unsupported("a.exe"))
        assertEquals("“big.mkv” is larger than 2 GB.", FileCopy.tooLarge("big.mkv"))
        assertEquals("“empty.txt” is empty.", FileCopy.empty("empty.txt"))
        assertEquals("You can send up to 10 files at once.", FileCopy.TOO_MANY)
        assertEquals("This file doesn't match its .pdf type, so Shroud won't open it.", FileCopy.mismatch("pdf"))
        assertEquals("No app on this phone can open .xlsb files.", FileCopy.noApp("xlsb"))
        assertEquals("Send File", FileCopy.composerTitle(1))
        assertEquals("Send 3 Files", FileCopy.composerTitle(3))
        assertEquals("Files are sent as they are, without compression, and keep their metadata.", FileCopy.COMPOSER_NOTE)
        assertEquals("Add a caption…", FileCopy.CAPTION_PLACEHOLDER)
        assertEquals("2.4 MB · PDF", FileCopy.meta("2.4 MB", "PDF"))
        assertEquals("1.1 MB of 2.4 MB", FileCopy.progress("1.1 MB", "2.4 MB"))
        assertEquals("File, a.apk, 3 MB, installs an app", FileCopy.accessibilityLabel("a.apk", "3 MB", FileWarning.App))
        assertEquals("File, a.doc, 3 MB, may contain macros", FileCopy.accessibilityLabel("a.doc", "3 MB", FileWarning.Macros))
        assertEquals("Installs an app", FileWarning.App.bubbleLine)
        assertEquals("This file can install an app", FileWarning.App.dialogTitle)
        assertEquals(
            "APK files install apps on Android. A harmful app can take over the phone and read your data. Only continue if you trust jane and expected this file.",
            FileWarning.App.dialogMessage("jane"),
        )
        assertEquals("May contain macros", FileWarning.Macros.bubbleLine)
        assertEquals("Cancel", FileWarning.CANCEL)
        assertEquals("Continue", FileWarning.CONTINUE)
    }

    // ---- Intake (§2, §7) ----

    private fun candidate(name: String?, size: Long) = FileIntake.Candidate(name, size) { InputStream.nullInputStream() }

    @Test
    fun intakeRefusesEachBadPickWithItsOwnSentence() {
        val result = FileIntake.evaluate(
            listOf(
                candidate("../Report.PDF", 100),
                candidate("setup.exe", 100),
                candidate("empty.txt", 0),
                candidate("huge.mkv", FileLimits.MAX_PLAINTEXT_BYTES + 1),
                candidate("exactly.mkv", FileLimits.MAX_PLAINTEXT_BYTES),
                candidate(null, 5),
                candidate("unknown size.docx", PickedFile.UNKNOWN_SIZE),
            ),
        )
        assertEquals(listOf("Report.PDF", "exactly.mkv", "unknown size.docx"), result.files.map { it.name })
        assertEquals(
            listOf(
                "Shroud can't send “setup.exe”: this file type isn't supported.",
                "“empty.txt” is empty.",
                "“huge.mkv” is larger than 2 GB.",
                "Shroud can't send “file”: this file type isn't supported.",
            ),
            result.refusals,
        )
        assertFalse("no name in toString", result.files.first().toString().contains("Report"))
    }

    @Test
    fun intakeKeepsTheFirstTenAndSaysSoOnce() {
        val result = FileIntake.evaluate((1..13).map { candidate("f$it.pdf", 10) })
        assertEquals((1..10).map { "f$it.pdf" }, result.files.map { it.name })
        assertEquals(listOf(FileCopy.TOO_MANY), result.refusals)
    }
}
