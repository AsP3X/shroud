package de.corespace.shroud.core.media.files

import de.corespace.shroud.core.crypto.MediaCrypto
import java.text.Normalizer
import java.util.Locale

// File sharing's shared rules (docs/file-sharing.md §4–§7): the type table, the content check, the
// name cleaning and the copy every client shows. Pure, so the vectors of §9 pin them on the JVM.

/** What kind of document a supported extension is: picks the bubble's glyph (§7). */
enum class FileCategory { Text, Pdf, Word, Excel, PowerPoint, Image, Video, App }

/**
 * The two warnings of §6: the bubble line (sender and receiver), and the dialog a **received** file
 * shows before any action that hands its plaintext to another program.
 */
enum class FileWarning(val bubbleLine: String, val dialogTitle: String, private val dialogTemplate: String, val accessibilitySuffix: String) {
    App(
        bubbleLine = "Installs an app",
        dialogTitle = "This file can install an app",
        dialogTemplate = "APK files install apps on Android. A harmful app can take over the phone and read your data. " +
            "Only continue if you trust {sender} and expected this file.",
        accessibilitySuffix = ", installs an app",
    ),
    Macros(
        bubbleLine = "May contain macros",
        dialogTitle = "This file may contain macros",
        dialogTemplate = "Macros in Office files can run harmful code. Only continue if you trust {sender} and expected this file, " +
            "and don't turn on macros unless you're sure.",
        accessibilitySuffix = ", may contain macros",
    ),
    ;

    /** The dialog's message with the contact's display name. */
    fun dialogMessage(sender: String): String = dialogTemplate.replace("{sender}", sender)

    companion object {
        /** The dialog's buttons (§6): Cancel is the default; Continue is drawn destructive. */
        const val CANCEL = "Cancel"
        const val CONTINUE = "Continue"
    }
}

/** The first-bytes check a type gets before any viewer sees it (§4 "Content check before opening"). */
enum class ContentCheck { Pdf, Zip, Ole, Rtf, PlainText, None }

/**
 * One row of the §4 table: the lowercased [extension], the canonical [mime] a receiver opens it as,
 * its [category], its [warning] and its [check]. The extension decides; a sender's `mime` is
 * informational only.
 */
data class FileType(
    val extension: String,
    val mime: String,
    val category: FileCategory,
    val warning: FileWarning?,
    val check: ContentCheck,
) {
    /** `TYPE` of the meta line: the extension upper-cased (§7). */
    val label: String get() = extension.uppercase(Locale.ROOT)

    /** Android never installs an APK itself (no `REQUEST_INSTALL_PACKAGES`): no Open, only Save to Downloads and Share (§6). */
    val canOpen: Boolean get() = category != FileCategory.App
}

/** The §4 table, looked up by the lowercased extension of a cleaned name. */
object FileTypes {
    private const val DOCX = "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
    private const val DOTX = "application/vnd.openxmlformats-officedocument.wordprocessingml.template"
    private const val XLSX = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
    private const val XLTX = "application/vnd.openxmlformats-officedocument.spreadsheetml.template"
    private const val PPTX = "application/vnd.openxmlformats-officedocument.presentationml.presentation"
    private const val PPSX = "application/vnd.openxmlformats-officedocument.presentationml.slideshow"
    private const val POTX = "application/vnd.openxmlformats-officedocument.presentationml.template"

    /** Every supported type, in the table's order. */
    val all: List<FileType> = listOf(
        FileType("txt", "text/plain", FileCategory.Text, null, ContentCheck.PlainText),
        FileType("csv", "text/csv", FileCategory.Text, null, ContentCheck.PlainText),
        FileType("pdf", "application/pdf", FileCategory.Pdf, null, ContentCheck.Pdf),
        FileType("docx", DOCX, FileCategory.Word, null, ContentCheck.Zip),
        FileType("dotx", DOTX, FileCategory.Word, null, ContentCheck.Zip),
        FileType("rtf", "application/rtf", FileCategory.Word, null, ContentCheck.Rtf),
        FileType("doc", "application/msword", FileCategory.Word, FileWarning.Macros, ContentCheck.Ole),
        FileType("dot", "application/msword", FileCategory.Word, FileWarning.Macros, ContentCheck.Ole),
        FileType("docm", "application/vnd.ms-word.document.macroEnabled.12", FileCategory.Word, FileWarning.Macros, ContentCheck.Zip),
        FileType("dotm", "application/vnd.ms-word.template.macroEnabled.12", FileCategory.Word, FileWarning.Macros, ContentCheck.Zip),
        FileType("xlsx", XLSX, FileCategory.Excel, null, ContentCheck.Zip),
        FileType("xltx", XLTX, FileCategory.Excel, null, ContentCheck.Zip),
        FileType("xls", "application/vnd.ms-excel", FileCategory.Excel, FileWarning.Macros, ContentCheck.Ole),
        FileType("xlt", "application/vnd.ms-excel", FileCategory.Excel, FileWarning.Macros, ContentCheck.Ole),
        FileType("xlsm", "application/vnd.ms-excel.sheet.macroEnabled.12", FileCategory.Excel, FileWarning.Macros, ContentCheck.Zip),
        FileType("xltm", "application/vnd.ms-excel.template.macroEnabled.12", FileCategory.Excel, FileWarning.Macros, ContentCheck.Zip),
        FileType("xlsb", "application/vnd.ms-excel.sheet.binary.macroEnabled.12", FileCategory.Excel, FileWarning.Macros, ContentCheck.Zip),
        FileType("pptx", PPTX, FileCategory.PowerPoint, null, ContentCheck.Zip),
        FileType("ppsx", PPSX, FileCategory.PowerPoint, null, ContentCheck.Zip),
        FileType("potx", POTX, FileCategory.PowerPoint, null, ContentCheck.Zip),
        FileType("ppt", "application/vnd.ms-powerpoint", FileCategory.PowerPoint, FileWarning.Macros, ContentCheck.Ole),
        FileType("pps", "application/vnd.ms-powerpoint", FileCategory.PowerPoint, FileWarning.Macros, ContentCheck.Ole),
        FileType("pot", "application/vnd.ms-powerpoint", FileCategory.PowerPoint, FileWarning.Macros, ContentCheck.Ole),
        FileType("pptm", "application/vnd.ms-powerpoint.presentation.macroEnabled.12", FileCategory.PowerPoint, FileWarning.Macros, ContentCheck.Zip),
        FileType("ppsm", "application/vnd.ms-powerpoint.slideshow.macroEnabled.12", FileCategory.PowerPoint, FileWarning.Macros, ContentCheck.Zip),
        FileType("potm", "application/vnd.ms-powerpoint.template.macroEnabled.12", FileCategory.PowerPoint, FileWarning.Macros, ContentCheck.Zip),
        FileType("jpg", "image/jpeg", FileCategory.Image, null, ContentCheck.None),
        FileType("jpeg", "image/jpeg", FileCategory.Image, null, ContentCheck.None),
        FileType("png", "image/png", FileCategory.Image, null, ContentCheck.None),
        FileType("gif", "image/gif", FileCategory.Image, null, ContentCheck.None),
        FileType("webp", "image/webp", FileCategory.Image, null, ContentCheck.None),
        FileType("heic", "image/heic", FileCategory.Image, null, ContentCheck.None),
        FileType("heif", "image/heif", FileCategory.Image, null, ContentCheck.None),
        FileType("avif", "image/avif", FileCategory.Image, null, ContentCheck.None),
        FileType("tif", "image/tiff", FileCategory.Image, null, ContentCheck.None),
        FileType("tiff", "image/tiff", FileCategory.Image, null, ContentCheck.None),
        FileType("bmp", "image/bmp", FileCategory.Image, null, ContentCheck.None),
        FileType("mp4", "video/mp4", FileCategory.Video, null, ContentCheck.None),
        FileType("m4v", "video/x-m4v", FileCategory.Video, null, ContentCheck.None),
        FileType("mov", "video/quicktime", FileCategory.Video, null, ContentCheck.None),
        FileType("webm", "video/webm", FileCategory.Video, null, ContentCheck.None),
        FileType("mkv", "video/x-matroska", FileCategory.Video, null, ContentCheck.None),
        FileType("avi", "video/x-msvideo", FileCategory.Video, null, ContentCheck.None),
        FileType("3gp", "video/3gpp", FileCategory.Video, null, ContentCheck.None),
        FileType("apk", "application/vnd.android.package-archive", FileCategory.App, FileWarning.App, ContentCheck.Zip),
    )

    private val byExtension: Map<String, FileType> = all.associateBy { it.extension }

    /** The type of a lowercased extension; null = unsupported. */
    fun forExtension(extension: String?): FileType? = extension?.let { byExtension[it.lowercase(Locale.ROOT)] }

    /** The type of [name] (cleaned here first, so a raw name works too); null = unsupported. */
    fun forName(name: String): FileType? = forExtension(FileNames.extension(FileNames.clean(name)))

    /**
     * What `ACTION_OPEN_DOCUMENT` may offer (§7 "Attach"): the table's MIME types, plus the two
     * names Android's own `MimeTypeMap` gives CSV and RTF files, which document providers report
     * instead — without them those files could not be picked at all. The extension still decides
     * what is sent.
     */
    val pickerMimeTypes: Array<String> = (all.map { it.mime } + listOf("text/comma-separated-values", "text/rtf")).distinct().toTypedArray()
}

/**
 * §4's content check on the first bytes of a received file: a `.pdf` that is not a PDF, an Office
 * file or APK that is not a zip (or OLE container), an RTF without `{\rtf`, a text file with a NUL
 * byte in its first 8 KiB. Images and videos are left to the platform decoders.
 */
object FileContentCheck {
    /** How much of the file [matches] needs: 8 KiB, the text rule's window. */
    const val HEAD_BYTES = 8 * 1024

    private const val PDF_WINDOW = 1024
    private val PDF = "%PDF-".toByteArray(Charsets.US_ASCII)
    private val ZIP = byteArrayOf(0x50, 0x4B, 0x03, 0x04)
    private val OLE = byteArrayOf(0xD0.toByte(), 0xCF.toByte(), 0x11, 0xE0.toByte(), 0xA1.toByte(), 0xB1.toByte(), 0x1A, 0xE1.toByte())
    private val RTF = "{\\rtf".toByteArray(Charsets.US_ASCII)

    /** Whether [head] (the file's first bytes, up to [HEAD_BYTES]; the whole file when shorter) fits [type]. */
    fun matches(type: FileType, head: ByteArray, length: Int = head.size): Boolean {
        val size = length.coerceIn(0, head.size)
        return when (type.check) {
            ContentCheck.Pdf -> indexOf(head, minOf(size, PDF_WINDOW), PDF) >= 0
            ContentCheck.Zip -> startsWith(head, size, ZIP)
            ContentCheck.Ole -> startsWith(head, size, OLE)
            ContentCheck.Rtf -> startsWith(head, size, RTF)
            ContentCheck.PlainText -> (0 until minOf(size, HEAD_BYTES)).none { head[it] == 0.toByte() }
            ContentCheck.None -> true
        }
    }

    private fun startsWith(data: ByteArray, size: Int, prefix: ByteArray): Boolean =
        size >= prefix.size && prefix.indices.all { data[it] == prefix[it] }

    private fun indexOf(data: ByteArray, size: Int, needle: ByteArray): Int {
        if (size < needle.size) return -1
        for (start in 0..size - needle.size) {
            if (needle.indices.all { data[start + it] == needle[it] }) return start
        }
        return -1
    }
}

/**
 * §5: the name is cleaned the same way on the sender (before sealing) and on the receiver (before
 * showing or saving), so a hostile sender cannot smuggle a path, a hidden extension or a
 * right-to-left override. Works on code points; [clean] of a cleaned name returns it unchanged.
 */
object FileNames {
    /** Longest cleaned name, in code points (rule 8). */
    const val MAX_CODE_POINTS = 120

    /** An empty stem's stand-in (rule 9). */
    const val EMPTY_STEM = "file"

    private const val MAX_EXTENSION = 10
    private const val SPACE = 0x20
    private const val DOT = 0x2E

    /** The cleaned name of [raw] (rules 1–9). */
    fun clean(raw: String): String {
        var text = Normalizer.normalize(raw, Normalizer.Form.NFC)
        val cut = maxOf(text.lastIndexOf('/'), text.lastIndexOf('\\'))
        if (cut >= 0) text = text.substring(cut + 1)
        val points = ArrayList<Int>(text.length)
        var index = 0
        while (index < text.length) {
            val cp = text.codePointAt(index)
            index += Character.charCount(cp)
            when {
                isRemoved(cp) -> Unit
                isReplaced(cp) -> points += '_'.code
                isSpace(cp) -> if (points.lastOrNull() != SPACE) points += SPACE
                else -> points += cp
            }
        }
        val trimmed = trimSpacesAndDots(points)
        val (stem, extension) = split(trimmed)
        val budget = MAX_CODE_POINTS - (if (extension.isEmpty()) 0 else extension.size + 1)
        var cleanStem = trimSpacesAndDots(if (stem.size > budget) stem.subList(0, budget) else stem)
        if (cleanStem.isEmpty()) cleanStem = EMPTY_STEM.map { it.code }
        val out = if (extension.isEmpty()) cleanStem else cleanStem + DOT + extension
        return buildString { out.forEach { appendCodePoint(it) } }
    }

    /** The lowercased extension of a cleaned [name] (rule 7), or null. */
    fun extension(name: String): String? {
        val points = name.codePoints().toArray().toList()
        val extension = split(points).second
        if (extension.isEmpty()) return null
        return buildString { extension.forEach { appendCodePoint(it) } }.lowercase(Locale.ROOT)
    }

    /** Rule 7: what follows the last `.`, unless that dot leads or the rest is not 1–10 ASCII letters or digits. */
    private fun split(points: List<Int>): Pair<List<Int>, List<Int>> {
        val dot = points.lastIndexOf(DOT)
        if (dot <= 0) return points to emptyList()
        val candidate = points.subList(dot + 1, points.size)
        if (candidate.isEmpty() || candidate.size > MAX_EXTENSION || !candidate.all(::isAsciiAlphanumeric)) return points to emptyList()
        return points.subList(0, dot) to candidate
    }

    private fun trimSpacesAndDots(points: List<Int>): List<Int> {
        var start = 0
        var end = points.size
        while (start < end && (points[start] == SPACE || points[start] == DOT)) start++
        while (end > start && (points[end - 1] == SPACE || points[end - 1] == DOT)) end--
        return points.subList(start, end)
    }

    private fun isAsciiAlphanumeric(cp: Int): Boolean = cp in 0x30..0x39 || cp in 0x41..0x5A || cp in 0x61..0x7A

    /** Rule 3. */
    private fun isRemoved(cp: Int): Boolean =
        cp in 0x00..0x08 || cp in 0x0E..0x1F || cp in 0x7F..0x9F || cp == 0xAD || cp == 0x061C || cp == 0x180E ||
            cp in 0x200B..0x200F || cp in 0x202A..0x202E || cp in 0x2060..0x2064 || cp in 0x2066..0x206F ||
            cp == 0x2028 || cp == 0x2029 || cp == 0xFEFF || cp in 0xFFF9..0xFFFB

    /** Rule 4: `< > : " | ? *`. */
    private fun isReplaced(cp: Int): Boolean = cp == '<'.code || cp == '>'.code || cp == ':'.code || cp == '"'.code ||
        cp == '|'.code || cp == '?'.code || cp == '*'.code

    /** Rule 5. */
    private fun isSpace(cp: Int): Boolean =
        cp in 0x09..0x0D || cp == 0x20 || cp == 0xA0 || cp == 0x1680 || cp in 0x2000..0x200A || cp == 0x202F || cp == 0x205F || cp == 0x3000
}

/** The user-facing sentences of file sharing (§6, §7), word for word. */
object FileCopy {
    /** Quote label, chat-list fallback context and the bubble's spoken kind. */
    const val FILE = "File"

    /** The message menu's rows for file messages (§7). */
    const val SAVE_TO_DOWNLOADS = "Save to Downloads"
    const val SHARE = "Share"

    const val UNSUPPORTED = "Unsupported file"
    const val NOT_SENT = "Not sent"

    /** The file composer (§7). */
    const val CAPTION_PLACEHOLDER = "Add a caption…"
    const val SEND = "Send"
    const val REMOVE = "Remove"
    const val COMPOSER_NOTE = "Files are sent as they are, without compression, and keep their metadata."

    /** After Save to Downloads, and when it failed. */
    const val SAVED_TO_DOWNLOADS = "Saved to Downloads"
    const val COULD_NOT_SAVE = "Could not save that file."
    const val COULD_NOT_SHARE = "Could not share that file."
    const val COULD_NOT_OPEN = "Could not open that file."
    const val COULD_NOT_DOWNLOAD = "Could not download that file."
    const val COULD_NOT_READ = "Could not read that file."

    /** The file composer's title: "Send File" / "Send {n} Files". */
    fun composerTitle(count: Int): String = if (count == 1) "Send File" else "Send $count Files"

    /** Refusals, one toast per pick (§7). */
    fun unsupported(name: String): String = "Shroud can't send “$name”: this file type isn't supported."
    fun tooLarge(name: String): String = "“$name” is larger than 2 GB."
    fun empty(name: String): String = "“$name” is empty."
    const val TOO_MANY = "You can send up to 10 files at once."

    /** §4: the first bytes do not fit the extension; saving and sharing stay possible. */
    fun mismatch(extension: String): String = "This file doesn't match its .$extension type, so Shroud won't open it."

    /** §7: `ACTION_VIEW` found no app. */
    fun noApp(extension: String): String = "No app on this phone can open .$extension files."

    /** The meta line `{size} · {TYPE}`. */
    fun meta(size: String, type: String): String = "$size · $type"

    /** While transferring: `{done} of {total}`. */
    fun progress(done: String, total: String): String = "$done of $total"

    /** `File, {name}, {size}` plus the warning's suffix (§7). */
    fun accessibilityLabel(name: String, size: String, warning: FileWarning?): String =
        "File, $name, $size" + (warning?.accessibilitySuffix ?: "")
}

/** The send limits of §2. */
object FileLimits {
    /** 2 GiB − 1 MiB of plaintext per file (`MediaCrypto.MAX_PLAINTEXT_BYTES`): the SHRF1 blob stays under the server's 2 GiB. */
    const val MAX_PLAINTEXT_BYTES: Long = MediaCrypto.MAX_PLAINTEXT_BYTES

    /** At most this many files per send, like photos. */
    const val MAX_FILES_PER_SEND = 10
}
