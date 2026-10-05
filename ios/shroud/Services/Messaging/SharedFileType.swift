import Foundation
import UniformTypeIdentifiers

/// What a shared file is, decided by the extension of its cleaned name (`docs/file-sharing.md`
/// §4–§6), plus the name rules and the copy every client shares.
///
/// Human: The extension decides on both ends. The sender's `mime` is informational only: a
/// receiver looks the type, the MIME it opens the file as and the warning up in this table.
/// Anything not listed — SVG, HTML, scripts, executables, archives — is refused by the sender
/// and shown as "Unsupported file" with no download by the receiver.
/// Agent: Pure. `FileNameTests` / `SharedFileTypeTests` pin it against
/// `scripts/gen_file_vectors.mjs`; change web/ and android/ together.
nonisolated enum SharedFile {
    enum Category: String, Sendable, CaseIterable {
        case text, pdf, word, excel, powerPoint, image, video, app
    }

    /// The two cautions of §6, shown on the bubble and asked before a received file is opened.
    enum Warning: Sendable, Equatable {
        case macros
        case app

        var bubbleLine: String {
            switch self {
            case .app: "Installs an app"
            case .macros: "May contain macros"
            }
        }

        var dialogTitle: String {
            switch self {
            case .app: "This file can install an app"
            case .macros: "This file may contain macros"
            }
        }

        func dialogMessage(sender: String) -> String {
            switch self {
            case .app:
                "APK files install apps on Android. A harmful app can take over the phone and read your data. Only continue if you trust \(sender) and expected this file."
            case .macros:
                "Macros in Office files can run harmful code. Only continue if you trust \(sender) and expected this file, and don't turn on macros unless you're sure."
            }
        }

        /// Appended to the bubble's accessibility label.
        var spokenSuffix: String {
            switch self {
            case .app: "installs an app"
            case .macros: "may contain macros"
            }
        }
    }

    struct FileType: Equatable, Sendable {
        /// Lowercased extension.
        let ext: String
        let mime: String
        let category: Category
        let warning: Warning?

        /// The extension upper-cased, for the meta line (`2.4 MB · PDF`).
        var label: String { ext.uppercased() }
    }

    /// 2 GiB − 1 MiB of plaintext, so the sealed blob stays under the server's 2 GiB.
    static let maxPlaintextBytes = Int64(VideoMedia.maxPlaintextBytes)
    /// Files per send, like photos. The caption and the reply ride on the first one.
    static let maxFilesPerSend = 10
    /// Longest cleaned name, in code points.
    static let maxNameLength = 120

    // MARK: - The table

    private static let rows: [(String, String, Category, Warning?)] = [
        ("txt", "text/plain", .text, nil),
        ("csv", "text/csv", .text, nil),
        ("pdf", "application/pdf", .pdf, nil),
        ("docx", "application/vnd.openxmlformats-officedocument.wordprocessingml.document", .word, nil),
        ("dotx", "application/vnd.openxmlformats-officedocument.wordprocessingml.template", .word, nil),
        ("rtf", "application/rtf", .word, nil),
        ("doc", "application/msword", .word, .macros),
        ("dot", "application/msword", .word, .macros),
        ("docm", "application/vnd.ms-word.document.macroEnabled.12", .word, .macros),
        ("dotm", "application/vnd.ms-word.template.macroEnabled.12", .word, .macros),
        ("xlsx", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", .excel, nil),
        ("xltx", "application/vnd.openxmlformats-officedocument.spreadsheetml.template", .excel, nil),
        ("xls", "application/vnd.ms-excel", .excel, .macros),
        ("xlt", "application/vnd.ms-excel", .excel, .macros),
        ("xlsm", "application/vnd.ms-excel.sheet.macroEnabled.12", .excel, .macros),
        ("xltm", "application/vnd.ms-excel.template.macroEnabled.12", .excel, .macros),
        ("xlsb", "application/vnd.ms-excel.sheet.binary.macroEnabled.12", .excel, .macros),
        ("pptx", "application/vnd.openxmlformats-officedocument.presentationml.presentation", .powerPoint, nil),
        ("ppsx", "application/vnd.openxmlformats-officedocument.presentationml.slideshow", .powerPoint, nil),
        ("potx", "application/vnd.openxmlformats-officedocument.presentationml.template", .powerPoint, nil),
        ("ppt", "application/vnd.ms-powerpoint", .powerPoint, .macros),
        ("pps", "application/vnd.ms-powerpoint", .powerPoint, .macros),
        ("pot", "application/vnd.ms-powerpoint", .powerPoint, .macros),
        ("pptm", "application/vnd.ms-powerpoint.presentation.macroEnabled.12", .powerPoint, .macros),
        ("ppsm", "application/vnd.ms-powerpoint.slideshow.macroEnabled.12", .powerPoint, .macros),
        ("potm", "application/vnd.ms-powerpoint.template.macroEnabled.12", .powerPoint, .macros),
        ("jpg", "image/jpeg", .image, nil),
        ("jpeg", "image/jpeg", .image, nil),
        ("png", "image/png", .image, nil),
        ("gif", "image/gif", .image, nil),
        ("webp", "image/webp", .image, nil),
        ("heic", "image/heic", .image, nil),
        ("heif", "image/heif", .image, nil),
        ("avif", "image/avif", .image, nil),
        ("tif", "image/tiff", .image, nil),
        ("tiff", "image/tiff", .image, nil),
        ("bmp", "image/bmp", .image, nil),
        ("mp4", "video/mp4", .video, nil),
        ("m4v", "video/x-m4v", .video, nil),
        ("mov", "video/quicktime", .video, nil),
        ("webm", "video/webm", .video, nil),
        ("mkv", "video/x-matroska", .video, nil),
        ("avi", "video/x-msvideo", .video, nil),
        ("3gp", "video/3gpp", .video, nil),
        ("apk", "application/vnd.android.package-archive", .app, .app),
    ]

    private static let table: [String: FileType] = Dictionary(
        uniqueKeysWithValues: rows.map { ($0.0, FileType(ext: $0.0, mime: $0.1, category: $0.2, warning: $0.3)) }
    )

    /// Every supported extension, in the table's order (the picker and tests use it).
    static let supportedExtensions: [String] = rows.map(\.0)

    /// The type of an already cleaned name, or nil when it is unsupported.
    static func type(forName cleanedName: String) -> FileType? {
        guard let ext = fileExtension(of: cleanedName) else { return nil }
        return table[ext.lowercased()]
    }

    /// What the document picker offers: the table's extensions as `UTType`s.
    ///
    /// Agent: The system has no type for some of them (`apk`); those come back as dynamic
    /// types, which still filter by extension. The receiver's table is the real gate.
    static let pickerTypes: [UTType] = {
        var seen = Set<String>()
        return supportedExtensions.compactMap { ext in
            guard let type = UTType(filenameExtension: ext), seen.insert(type.identifier).inserted else { return nil }
            return type
        }
    }()

    // MARK: - Content check (§4)

    /// How many leading bytes `contentMatches` needs.
    static let contentCheckBytes = 8 * 1024

    private static let zipMagic: [UInt8] = [0x50, 0x4B, 0x03, 0x04]
    private static let oleMagic: [UInt8] = [0xD0, 0xCF, 0x11, 0xE0, 0xA1, 0xB1, 0x1A, 0xE1]
    private static let zipTypes: Set<String> = [
        "docx", "dotx", "docm", "dotm", "xlsx", "xltx", "xlsm", "xltm", "xlsb",
        "pptx", "ppsx", "potx", "pptm", "ppsm", "potm", "apk",
    ]
    private static let oleTypes: Set<String> = ["doc", "dot", "xls", "xlt", "ppt", "pps", "pot"]

    /// Whether the file's first bytes fit its extension; checked before any viewer gets it.
    /// Images and videos are left to the platform decoders.
    static func contentMatches(_ type: FileType, header: Data) -> Bool {
        let bytes = [UInt8](header.prefix(contentCheckBytes))
        switch type.ext {
        case "pdf":
            let window = bytes.prefix(1024)
            let marker = Array("%PDF-".utf8)
            guard window.count >= marker.count else { return false }
            return (0 ... window.count - marker.count).contains { start in
                Array(window[start ..< start + marker.count]) == marker
            }
        case "rtf":
            return bytes.starts(with: Array("{\\rtf".utf8))
        case "txt", "csv":
            return !bytes.contains(0)
        case let ext where zipTypes.contains(ext):
            return bytes.starts(with: zipMagic)
        case let ext where oleTypes.contains(ext):
            return bytes.starts(with: oleMagic)
        default:
            return true
        }
    }

    // MARK: - Names (§5)

    private static let removed: Set<UInt32> = {
        var set = Set<UInt32>()
        let ranges: [ClosedRange<UInt32>] = [
            0x00 ... 0x08, 0x0E ... 0x1F, 0x7F ... 0x9F, 0xAD ... 0xAD, 0x061C ... 0x061C,
            0x180E ... 0x180E, 0x200B ... 0x200F, 0x202A ... 0x202E, 0x2060 ... 0x2064,
            0x2066 ... 0x206F, 0x2028 ... 0x2029, 0xFEFF ... 0xFEFF, 0xFFF9 ... 0xFFFB,
        ]
        for range in ranges { set.formUnion(range) }
        return set
    }()

    private static let spaces: Set<UInt32> = {
        var set = Set<UInt32>([0x20, 0xA0, 0x1680, 0x202F, 0x205F, 0x3000])
        set.formUnion(0x09 ... 0x0D)
        set.formUnion(0x2000 ... 0x200A)
        return set
    }()

    private static let replaced: Set<UInt32> = Set("<>:\"|?*".unicodeScalars.map(\.value))

    /// The name a file is sent and shown under: no path, no invisible or bidi controls, no
    /// characters a file system refuses, at most 120 code points with the extension kept.
    ///
    /// Human: Cleaned the same way on the sender (before sealing) and the receiver (before
    /// showing or saving), so a hostile sender can't smuggle a path, a hidden extension or a
    /// right-to-left override (`invoice‮fdp.exe`). Counted in code points, like every client.
    static func cleanName(_ raw: String) -> String {
        // Scalars, not characters: a combining mark after a "/" must not hide the separator.
        let all = Array(raw.precomposedStringWithCanonicalMapping.unicodeScalars)
        let tail = all.lastIndex { $0 == "/" || $0 == "\\" }.map { all[($0 + 1)...] } ?? all[...]

        var scalars: [UInt32] = []
        for scalar in tail {
            let value = scalar.value
            if removed.contains(value) { continue }
            if replaced.contains(value) {
                scalars.append(0x5F)
            } else if spaces.contains(value) {
                if scalars.last != 0x20 { scalars.append(0x20) }
            } else {
                scalars.append(value)
            }
        }
        scalars = trimSpacesAndDots(scalars)

        var stem = scalars
        var ext: [UInt32] = []
        if let dot = scalars.lastIndex(of: 0x2E), dot > 0 {
            let candidate = Array(scalars[(dot + 1)...])
            if (1 ... 10).contains(candidate.count), candidate.allSatisfy(isASCIIAlphanumeric) {
                stem = Array(scalars[..<dot])
                ext = candidate
            }
        }
        let budget = maxNameLength - (ext.isEmpty ? 0 : ext.count + 1)
        stem = trimSpacesAndDots(stem.count > budget ? Array(stem.prefix(budget)) : stem)
        if stem.isEmpty { stem = "file".unicodeScalars.map(\.value) }
        let out = ext.isEmpty ? stem : stem + [0x2E] + ext
        var view = String.UnicodeScalarView()
        view.append(contentsOf: out.compactMap(Unicode.Scalar.init))
        return String(view)
    }

    /// What follows the last `.` of a cleaned name, when that dot isn't the first character
    /// and the rest is 1–10 ASCII letters or digits. Case is kept; lookups lowercase it.
    static func fileExtension(of name: String) -> String? {
        let scalars = Array(name.unicodeScalars)
        guard let dot = scalars.lastIndex(of: "."), dot > 0 else { return nil }
        let candidate = scalars[(dot + 1)...]
        guard (1 ... 10).contains(candidate.count), candidate.allSatisfy({ isASCIIAlphanumeric($0.value) }) else { return nil }
        var view = String.UnicodeScalarView()
        view.append(contentsOf: candidate)
        return String(view)
    }

    private static func isASCIIAlphanumeric(_ value: UInt32) -> Bool {
        (0x30 ... 0x39).contains(value) || (0x41 ... 0x5A).contains(value) || (0x61 ... 0x7A).contains(value)
    }

    private static func trimSpacesAndDots(_ scalars: [UInt32]) -> [UInt32] {
        var start = scalars.startIndex
        var end = scalars.endIndex
        while start < end, scalars[start] == 0x20 || scalars[start] == 0x2E { start += 1 }
        while end > start, scalars[end - 1] == 0x20 || scalars[end - 1] == 0x2E { end -= 1 }
        return Array(scalars[start ..< end])
    }

    // MARK: - Copy (§7)

    static func unsupportedRefusal(_ name: String) -> String {
        "Shroud can't send “\(name)”: this file type isn't supported."
    }

    static func tooLargeRefusal(_ name: String) -> String {
        "“\(name)” is larger than 2 GB."
    }

    static func emptyRefusal(_ name: String) -> String {
        "“\(name)” is empty."
    }

    static let tooManyRefusal = "You can send up to 10 files at once."

    static func contentMismatch(_ type: FileType) -> String {
        "This file doesn't match its .\(type.ext) type, so Shroud won't open it."
    }

    static func composerTitle(count: Int) -> String {
        count == 1 ? "Send File" : "Send \(count) Files"
    }

    static let composerNote = "Files are sent as they are, without compression, and keep their metadata."
    static let unsupportedLabel = "Unsupported file"

    /// `{size} · {TYPE}`, the bubble's and the composer's meta line.
    /// `{size} · {TYPE}`, led by a PDF's page count when it is known: `12 pages · 2.4 MB · PDF`.
    static func metaLine(byteCount: Int64?, type: FileType, pageCount: Int? = nil) -> String {
        var parts: [String] = []
        if let pageCount, pageCount > 0 { parts.append(pageCountLabel(pageCount)) }
        if let byteCount, byteCount > 0 { parts.append(MediaCrypto.byteCountLabel(Int(clamping: byteCount))) }
        parts.append(type.label)
        return parts.joined(separator: " · ")
    }

    /// `1 page` / `12 pages`.
    static func pageCountLabel(_ count: Int) -> String {
        count == 1 ? "1 page" : "\(count) pages"
    }
}
