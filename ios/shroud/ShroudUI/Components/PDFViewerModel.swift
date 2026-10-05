import Observation
import PDFKit
import SwiftUI
import UIKit

/// State of one open PDF in the viewer (`docs/file-sharing.md` §10.2).
///
/// Human: The document is parsed off the main thread, so a large or broken file never freezes
/// the chat behind it. A locked PDF waits for its password; one PDFKit can't read ends in the
/// damaged state, which still offers Share. Search runs through PDFKit's own find, which
/// reports matches as it goes, so the counter grows while a long document is searched.
/// Agent: Main actor. `PDFViewerScreen` draws it; `PDFKitView`'s coordinator owns the `PDFView`
/// and reports page changes back through `pageChanged`.
@MainActor
@Observable
final class PDFViewerModel {
    enum Phase: Equatable {
        case loading
        case locked(wrongPassword: Bool)
        case ready
        case damaged
    }

    let url: URL
    let messageID: UUID
    /// The file's cleaned name.
    let title: String

    private(set) var phase: Phase = .loading
    private(set) var document: PDFDocument?
    /// Zero-based page in view.
    private(set) var pageIndex = 0
    private(set) var pageCount = 0
    var chromeVisible = true
    /// The pages sidebar (regular width) or drawer (compact) is open.
    var showsPages = false
    /// Set by the screen: the window is wide enough for the sidebar beside the pages.
    @ObservationIgnored var regularWidth = false

    // Search
    private(set) var isSearching = false
    var query = "" {
        didSet { if query != oldValue { scheduleSearch() } }
    }
    private(set) var matches: [PDFSelection] = []
    private(set) var matchIndex: Int?
    private(set) var searchFinished = false

    /// Set by the coordinator; the model scrolls and highlights through it.
    @ObservationIgnored weak var pdfView: PDFView?
    /// The password that unlocked the document, for the thumbnail renderer's own copy.
    @ObservationIgnored private(set) var password: String?
    /// Page thumbnails for the sidebar, cached for the viewer's life.
    @ObservationIgnored private(set) lazy var thumbnails = PDFThumbnailRenderer(url: url, password: password)
    @ObservationIgnored private var searchTask: Task<Void, Never>?
    @ObservationIgnored private var finder: Finder?
    @ObservationIgnored private var highlightScheduled = false

    init(url: URL, messageID: UUID, title: String) {
        self.url = url
        self.messageID = messageID
        self.title = title
    }

    // MARK: - Loading

    func load() async {
        guard phase == .loading, document == nil else { return }
        let url = url
        let parsed = await Task.detached(priority: .userInitiated) {
            UncheckedDocument(document: PDFDocument(url: url))
        }.value
        guard let document = parsed.document else {
            phase = .damaged
            return
        }
        self.document = document
        if document.isLocked {
            phase = .locked(wrongPassword: false)
        } else {
            becomeReady()
        }
    }

    func unlock(with password: String) {
        guard let document, case .locked = phase else { return }
        if document.unlock(withPassword: password) {
            self.password = password
            becomeReady()
        } else {
            phase = .locked(wrongPassword: true)
        }
    }

    private func becomeReady() {
        guard let document else { return }
        pageCount = document.pageCount
        guard pageCount > 0 else {
            phase = .damaged
            return
        }
        pageIndex = min(PDFViewerSession.lastPage(for: messageID) ?? 0, pageCount - 1)
        // In the same update as `.ready`, so the pages are first laid out beside the sidebar.
        placeSidebar()
        phase = .ready
    }

    /// Opens the sidebar the way this session left it — open by default for more than one page.
    /// Only where it sits beside the pages: a drawer always starts closed.
    func placeSidebar() {
        guard pageCount > 0 else { return }
        showsPages = regularWidth && (PDFViewerSession.sidebarOpen ?? (pageCount > 1))
    }

    func togglePages() {
        showsPages.toggle()
        if regularWidth { PDFViewerSession.sidebarOpen = showsPages }
    }

    /// Width ÷ height of a page as shown (PDFKit reads only that page's dictionary).
    func aspect(ofPage index: Int) -> CGFloat {
        guard let page = document?.page(at: index) else { return 1 / 1.414 }
        let box = page.bounds(for: .cropBox)
        let size = page.rotation % 180 == 0 ? box.size : CGSize(width: box.height, height: box.width)
        guard size.width > 0, size.height > 0 else { return 1 / 1.414 }
        return size.width / size.height
    }

    /// `Page 3 of 12`, `1 page`, or `Loading…` until the count is known.
    var subtitle: String {
        switch phase {
        case .loading: return "Loading…"
        case .locked, .damaged: return ""
        case .ready:
            if pageCount == 1 { return SharedFile.pageCountLabel(1) }
            return "Page \(pageIndex + 1) of \(pageCount)"
        }
    }

    // MARK: - Pages

    func pageChanged(to index: Int) {
        guard index >= 0, index < pageCount, index != pageIndex else { return }
        pageIndex = index
        PDFViewerSession.remember(page: index, for: messageID)
    }

    func go(toPage index: Int) {
        guard let document, let pdfView, pageCount > 0 else { return }
        let target = min(max(0, index), pageCount - 1)
        guard let page = document.page(at: target) else { return }
        if let pdfView = pdfView as? ShroudPDFView {
            pdfView.scrollToTop(of: page)
        } else {
            pdfView.go(to: page)
        }
        pageChanged(to: target)
    }

    func step(_ delta: Int) {
        go(toPage: pageIndex + delta)
    }

    // MARK: - Search

    func beginSearch() {
        guard phase == .ready else { return }
        chromeVisible = true
        isSearching = true
    }

    func endSearch() {
        isSearching = false
        query = ""
        clearResults()
    }

    /// Next (`+1`) or previous (`-1`) match, wrapping around.
    func stepMatch(_ delta: Int) {
        guard !matches.isEmpty else { return }
        let current = matchIndex ?? (delta > 0 ? -1 : 0)
        let next = (current + delta + matches.count) % matches.count
        matchIndex = next
        showCurrentMatch()
    }

    /// `3 of 17`, `No results`, or nothing while the field is empty or the search runs.
    var resultLabel: String {
        guard !query.trimmingCharacters(in: .whitespaces).isEmpty else { return "" }
        if matches.isEmpty { return searchFinished ? "No results" : "" }
        return "\((matchIndex ?? 0) + 1) of \(matches.count)"
    }

    private func clearResults() {
        searchTask?.cancel()
        finder?.cancel()
        finder = nil
        matches = []
        matchIndex = nil
        searchFinished = false
        pdfView?.highlightedSelections = nil
    }

    /// Waits for typing to pause, then asks PDFKit to find the query.
    private func scheduleSearch() {
        clearResults()
        let needle = query.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !needle.isEmpty, let document else { return }
        searchTask = Task { [weak self] in
            try? await Task.sleep(for: .milliseconds(250))
            guard !Task.isCancelled, let self else { return }
            let finder = Finder(document: document) { [weak self] selection in
                self?.found(selection)
            } onEnd: { [weak self] in
                self?.searchFinished = true
                self?.updateHighlights()
            }
            self.finder = finder
            finder.start(needle)
        }
    }

    private func found(_ selection: PDFSelection) {
        matches.append(selection)
        if matchIndex == nil {
            matchIndex = 0
            showCurrentMatch()
        } else {
            scheduleHighlights()
        }
    }

    private func showCurrentMatch() {
        updateHighlights()
        guard let matchIndex, matches.indices.contains(matchIndex) else { return }
        let match = matches[matchIndex]
        pdfView?.go(to: match)
        if let page = match.pages.first, let index = document?.index(for: page) {
            pageChanged(to: index)
        }
    }

    /// Coalesces highlight updates while matches stream in.
    private func scheduleHighlights() {
        guard !highlightScheduled else { return }
        highlightScheduled = true
        Task { [weak self] in
            try? await Task.sleep(for: .milliseconds(120))
            self?.highlightScheduled = false
            self?.updateHighlights()
        }
    }

    private func updateHighlights() {
        let accent = UIColor(Theme.accent)
        for (index, match) in matches.enumerated() {
            match.color = accent.withAlphaComponent(index == matchIndex ? 0.6 : 0.3)
        }
        pdfView?.highlightedSelections = matches.isEmpty ? nil : matches
    }

    func close() {
        clearResults()
    }

    /// PDFKit's incremental find, reported on the main actor.
    @MainActor
    private final class Finder: NSObject, PDFDocumentDelegate {
        private weak var document: PDFDocument?
        private let onMatch: @MainActor (PDFSelection) -> Void
        private let onEnd: @MainActor () -> Void
        private var cancelled = false

        init(
            document: PDFDocument,
            onMatch: @escaping @MainActor (PDFSelection) -> Void,
            onEnd: @escaping @MainActor () -> Void
        ) {
            self.document = document
            self.onMatch = onMatch
            self.onEnd = onEnd
        }

        func start(_ needle: String) {
            document?.delegate = self
            document?.beginFindString(needle, withOptions: [.caseInsensitive, .diacriticInsensitive])
        }

        func cancel() {
            cancelled = true
            document?.cancelFindString()
            if document?.delegate === self { document?.delegate = nil }
        }

        nonisolated func didMatchString(_ instance: PDFSelection) {
            let box = UncheckedSelection(selection: instance)
            Task { @MainActor in
                guard !self.cancelled else { return }
                self.onMatch(box.selection)
            }
        }

        nonisolated func documentDidEndDocumentFind(_ notification: Notification) {
            Task { @MainActor in
                guard !self.cancelled else { return }
                self.onEnd()
            }
        }
    }
}

/// PDFKit objects cross from the parse task to the main actor once, and are only used there.
nonisolated private struct UncheckedDocument: @unchecked Sendable {
    let document: PDFDocument?
}

nonisolated private struct UncheckedSelection: @unchecked Sendable {
    let selection: PDFSelection
}

/// The page each PDF was left on, for this session only (`docs/file-sharing.md` §10.2).
///
/// Agent: Main actor; `forgetAll` runs when the chats lock or the account signs out.
@MainActor
enum PDFViewerSession {
    private static var pages: [UUID: Int] = [:]

    static func lastPage(for messageID: UUID) -> Int? {
        pages[messageID]
    }

    static func remember(page: Int, for messageID: UUID) {
        pages[messageID] = page
    }

    /// Whether the reader left the pages sidebar open; nil until they toggle it.
    static var sidebarOpen: Bool?

    static func forgetAll() {
        pages = [:]
        sidebarOpen = nil
    }
}
