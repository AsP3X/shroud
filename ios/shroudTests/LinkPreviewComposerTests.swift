import XCTest

@testable import shroud

/// The composer's link preview state machine, driven by a fake fetcher (no network).
@MainActor
final class LinkPreviewComposerTests: XCTestCase {
    /// Returns `draft` for every URL (with the URL filled in), or fails when nil.
    private final class FakeFetcher: LinkPreviewFetching, @unchecked Sendable {
        var draft: LinkPreviewDraft?
        private(set) var requested: [URL] = []

        init(draft: LinkPreviewDraft?) {
            self.draft = draft
        }

        func fetchPreview(for url: URL) async throws -> LinkPreviewDraft {
            requested.append(url)
            guard var draft else { throw URLError(.badServerResponse) }
            draft.preview.url = url.absoluteString
            return draft
        }
    }

    private func draft(large: Bool) -> LinkPreviewDraft {
        LinkPreviewDraft(
            preview: LinkPreview(
                url: "https://example.com",
                siteName: "Example",
                title: "A page",
                thumbnail: Data([1, 2, 3])
            ),
            largeImage: large ? Data([4, 5, 6]) : nil,
            largeImageWidth: large ? 1200 : nil,
            largeImageHeight: large ? 630 : nil,
            prefersLargeImage: large
        )
    }

    private func settle(_ composer: LinkPreviewComposer, until condition: () -> Bool) async {
        for _ in 0 ..< 100 where !condition() {
            try? await Task.sleep(for: .milliseconds(10))
        }
    }

    func testLoadsAPreviewForTheFirstLink() async {
        let fetcher = FakeFetcher(draft: draft(large: false))
        let composer = LinkPreviewComposer(fetcher: fetcher, debounce: .zero)
        composer.draftChanged("look https://example.com and https://other.org", enabled: true)
        await settle(composer) { composer.draft != nil }
        XCTAssertEqual(composer.draft?.preview.url, "https://example.com")
        XCTAssertEqual(fetcher.requested.map(\.absoluteString), ["https://example.com"])
    }

    func testPlainTextShowsNothing() async {
        let fetcher = FakeFetcher(draft: draft(large: false))
        let composer = LinkPreviewComposer(fetcher: fetcher, debounce: .zero)
        composer.draftChanged("no links here", enabled: true)
        await settle(composer) { false }
        XCTAssertEqual(composer.phase, .idle)
        XCTAssertTrue(fetcher.requested.isEmpty)
    }

    func testSwitchedOffNeverFetches() async {
        let fetcher = FakeFetcher(draft: draft(large: false))
        let composer = LinkPreviewComposer(fetcher: fetcher, debounce: .zero)
        composer.draftChanged("https://example.com", enabled: false)
        await settle(composer) { false }
        XCTAssertEqual(composer.phase, .idle)
        XCTAssertTrue(fetcher.requested.isEmpty)
    }

    func testFailedFetchHidesTheBar() async {
        let composer = LinkPreviewComposer(fetcher: FakeFetcher(draft: nil), debounce: .zero)
        composer.draftChanged("https://example.com", enabled: true)
        await settle(composer) { composer.phase == .idle }
        XCTAssertEqual(composer.phase, .idle)
    }

    func testDismissedLinkStaysDismissedButANewLinkLoads() async {
        let fetcher = FakeFetcher(draft: draft(large: false))
        let composer = LinkPreviewComposer(fetcher: fetcher, debounce: .zero)
        composer.draftChanged("https://example.com", enabled: true)
        await settle(composer) { composer.draft != nil }
        composer.dismiss()
        XCTAssertEqual(composer.phase, .idle)

        composer.draftChanged("https://example.com is great", enabled: true)
        await settle(composer) { false }
        XCTAssertEqual(composer.phase, .idle)

        composer.draftChanged("https://example.com vs https://other.org", enabled: true)
        await settle(composer) { false }
        XCTAssertEqual(composer.phase, .idle, "the first link decides, and it was dismissed")

        composer.draftChanged("try https://other.org", enabled: true)
        await settle(composer) { composer.draft != nil }
        XCTAssertEqual(composer.draft?.preview.url, "https://other.org")
    }

    func testAttachmentNeedsTheLinkStillInTheText() async {
        let composer = LinkPreviewComposer(fetcher: FakeFetcher(draft: draft(large: false)), debounce: .zero)
        composer.draftChanged("https://example.com", enabled: true)
        await settle(composer) { composer.draft != nil }
        XCTAssertNil(composer.takeAttachment(for: "the link is gone"))
        XCTAssertEqual(composer.phase, .idle, "sending resets the composer")

        composer.draftChanged("https://example.com", enabled: true)
        await settle(composer) { composer.draft != nil }
        let attachment = composer.takeAttachment(for: "https://example.com")
        XCTAssertEqual(attachment?.preview.url, "https://example.com")
        XCTAssertNil(attachment?.largeImage)
    }

    func testHostCaseIsTheSameLinkButPathCaseIsNot() async {
        let fetcher = FakeFetcher(draft: draft(large: false))
        let composer = LinkPreviewComposer(fetcher: fetcher, debounce: .zero)
        composer.draftChanged("https://Example.com/Tour", enabled: true)
        await settle(composer) { composer.draft != nil }

        // The same page with its host retyped: no second fetch, and the preview still goes out.
        composer.draftChanged("https://example.com/Tour", enabled: true)
        await settle(composer) { false }
        XCTAssertEqual(fetcher.requested.count, 1)
        XCTAssertNotNil(composer.takeAttachment(for: "https://example.com/Tour"))

        // Paths are case-sensitive (YouTube ids, …): another page gets its own preview.
        composer.draftChanged("https://example.com/Tour", enabled: true)
        await settle(composer) { composer.draft != nil }
        composer.draftChanged("https://example.com/tour", enabled: true)
        await settle(composer) { fetcher.requested.count == 2 && composer.draft != nil }
        XCTAssertEqual(fetcher.requested.last?.absoluteString, "https://example.com/tour")
        XCTAssertEqual(composer.draft?.preview.url, "https://example.com/tour")
    }

    func testOptionsShapeTheAttachment() async {
        let composer = LinkPreviewComposer(fetcher: FakeFetcher(draft: draft(large: true)), debounce: .zero)
        composer.draftChanged("https://example.com", enabled: true)
        await settle(composer) { composer.draft != nil }
        XCTAssertTrue(composer.usesLargeImage)
        XCTAssertTrue(composer.canToggleImageSize)

        composer.toggleShowsAboveText()
        composer.toggleImageSize()
        XCTAssertFalse(composer.usesLargeImage)
        let small = composer.takeAttachment(for: "https://example.com")
        XCTAssertEqual(small?.preview.showsAboveText, true)
        XCTAssertNil(small?.largeImage, "smaller image sends the inline thumbnail only")

        composer.draftChanged("https://example.com", enabled: true)
        await settle(composer) { composer.draft != nil }
        let large = composer.takeAttachment(for: "https://example.com")
        XCTAssertEqual(large?.largeImage, Data([4, 5, 6]))
        XCTAssertEqual(large?.preview.showsAboveText, false, "options reset per preview")
    }
}
