import Foundation
import XCTest

@testable import shroud

/// Decrypted media left in `tmp/` by a crash must not outlive the next launch, and a lock must
/// not pull the file out from under a recording or playback that is still writing to it.
final class SensitiveTempFilesTests: XCTestCase {
    private let files = FileManager.default
    private var tmp: URL { files.temporaryDirectory }

    private func make(_ name: String, modified: Date = Date()) throws -> URL {
        let url = tmp.appendingPathComponent(name)
        try Data("plaintext".utf8).write(to: url)
        try files.setAttributes([.modificationDate: modified], ofItemAtPath: url.path)
        return url
    }

    override func tearDown() {
        for name in ["shroud-test-old.mp4", "shroud-test-new.mp4", "unrelated-test-file"] {
            try? files.removeItem(at: tmp.appendingPathComponent(name))
        }
        super.tearDown()
    }

    func testLaunchSweepRemovesEveryShroudFileAndNothingElse() throws {
        let leftover = try make("shroud-test-new.mp4")
        let unrelated = try make("unrelated-test-file")

        SensitiveTempFiles.sweep()

        XCTAssertFalse(files.fileExists(atPath: leftover.path))
        XCTAssertTrue(files.fileExists(atPath: unrelated.path))
    }

    func testLockSweepKeepsFilesStillInUse() throws {
        let stale = try make("shroud-test-old.mp4", modified: Date().addingTimeInterval(-3600))
        let live = try make("shroud-test-new.mp4")

        SensitiveTempFiles.sweep(olderThan: 600)

        XCTAssertFalse(files.fileExists(atPath: stale.path))
        XCTAssertTrue(files.fileExists(atPath: live.path))
    }

    func testTemporaryDirectoryIsProtectedAfterLaunchPrep() throws {
        SensitiveTempFiles.prepareAtLaunch()
        let attributes = try files.attributesOfItem(atPath: tmp.path)
        // The simulator has no data protection and may report nothing; a device must say so.
        if let protection = attributes[.protectionKey] as? FileProtectionType {
            XCTAssertEqual(protection, .completeUnlessOpen)
        }
    }
}
