import Foundation
import Testing
@testable import shroud

/// File name cleaning (`docs/file-sharing.md` §5): all 20 rows of the `== names ==` block of
/// `node scripts/gen_file_vectors.mjs`, with the script's own inputs.
struct FileNameTests {
    static let vectors: [(String, String)] = [
        ("report.pdf", "report.pdf"),
        ("../../etc/passwd", "passwd"),
        ("C:\\Users\\me\\Desktop\\Budget 2026.XLSX", "Budget 2026.XLSX"),
        ("invoice\u{202E}fdp.exe", "invoicefdp.exe"),
        ("  .hidden.txt  ", "hidden.txt"),
        ("what?<now>:\"x\"|*.docx", "what__now___x___.docx"),
        ("tabs\tand\nnewlines.txt", "tabs and newlines.txt"),
        ("many     spaces\u{00A0}\u{3000}here.csv", "many spaces here.csv"),
        ("trailing dots....", "trailing dots"),
        ("noext", "noext"),
        (".pdf", "pdf"),
        ("...", "file"),
        ("", "file"),
        ("e\u{0301}te\u{0301}.txt", "\u{00E9}t\u{00E9}.txt"),
        (String(repeating: "a", count: 130) + ".pptx", String(repeating: "a", count: 115) + ".pptx"),
        (String(repeating: "x", count: 200), String(repeating: "x", count: 120)),
        ("archive.tar.gz", "archive.tar.gz"),
        ("weird.ext-with-dash", "weird.ext-with-dash"),
        ("zero\u{200B}width\u{FEFF}.apk", "zerowidth.apk"),
        ("emoji 📄 notes.TXT", "emoji 📄 notes.TXT"),
    ]

    @Test
    func everyVectorIsPinned() {
        #expect(Self.vectors.count == 20)
    }

    @Test(arguments: FileNameTests.vectors)
    func cleansLikeTheScript(input: String, expected: String) {
        let cleaned = SharedFile.cleanName(input)
        // Scalars, so an NFC/NFD difference can't hide behind `String` equality.
        #expect(Array(cleaned.unicodeScalars) == Array(expected.unicodeScalars))
    }

    @Test
    func cleaningIsIdempotent() {
        for (_, expected) in Self.vectors {
            #expect(SharedFile.cleanName(expected) == expected)
        }
    }

    @Test
    func theExtensionFollowsRuleSeven() {
        #expect(SharedFile.fileExtension(of: "Budget 2026.XLSX") == "XLSX")
        #expect(SharedFile.fileExtension(of: "archive.tar.gz") == "gz")
        #expect(SharedFile.fileExtension(of: "weird.ext-with-dash") == nil)
        #expect(SharedFile.fileExtension(of: "pdf") == nil)
        #expect(SharedFile.fileExtension(of: ".pdf") == nil)
        #expect(SharedFile.fileExtension(of: "name.abcdefghijk") == nil)
        #expect(SharedFile.fileExtension(of: "name.tx\u{00E9}") == nil)
    }

    @Test
    func aLongNameKeepsItsExtensionWithinTheLimit() {
        let cleaned = SharedFile.cleanName(String(repeating: "b", count: 300) + ".docx")
        #expect(cleaned.unicodeScalars.count == 120)
        #expect(cleaned.hasSuffix(".docx"))
    }
}
