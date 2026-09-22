import Foundation
import Observation

/// Composer-side link previews: watches the draft, fetches a preview for its first link, and
/// hands the result to the send.
///
/// Human: Telegram's behaviour, step for step. Typing or pasting a link shows "Loading preview…"
/// above the field, then the page's title and description. ✕ drops the preview for that link
/// (typing a *different* link brings one back); tapping the bar offers "Show above text",
/// "Larger / Smaller image" and "Remove preview". A preview that has not finished loading when
/// Send is tapped is simply left off — sending never waits on a website.
/// Agent: Main-actor view model owned by `ConversationView`. CALLS `LinkPreviewFetching` (the
/// only network access); READS `SecurityPreferences.generatesLinkPreviews`. Holds no key
/// material and never persists anything.
@Observable
final class LinkPreviewComposer {
    enum Phase: Equatable {
        case idle
        case loading(URL)
        case ready(LinkPreviewDraft)
    }

    private(set) var phase: Phase = .idle
    /// Telegram's "Show above message".
    private(set) var showsAboveText = false
    /// The user's size choice; nil follows the page's default.
    private(set) var largeImageOverride: Bool?

    @ObservationIgnored private let fetcher: any LinkPreviewFetching
    @ObservationIgnored private var fetchTask: Task<Void, Never>?
    @ObservationIgnored private var debounceTask: Task<Void, Never>?
    /// Links the user closed with ✕ during this draft.
    @ObservationIgnored private var dismissed: Set<String> = []
    /// Recent results, so deleting and re-typing a character never refetches.
    @ObservationIgnored private var cache: [String: LinkPreviewDraft] = [:]
    @ObservationIgnored private var cacheOrder: [String] = []
    /// Waits for the typist to pause (Telegram fires on a similar beat).
    @ObservationIgnored private let debounce: Duration

    init(fetcher: any LinkPreviewFetching = LinkPreviewFetcher(), debounce: Duration = .milliseconds(450)) {
        self.fetcher = fetcher
        self.debounce = debounce
    }

    /// The loaded preview, if any.
    var draft: LinkPreviewDraft? {
        if case let .ready(draft) = phase { return draft }
        return nil
    }

    /// Whether the large layout is in effect for the loaded preview.
    var usesLargeImage: Bool {
        guard let draft, draft.largeImage != nil else { return false }
        return largeImageOverride ?? draft.prefersLargeImage
    }

    /// "Larger / Smaller image" only makes sense when both layouts exist.
    var canToggleImageSize: Bool {
        guard let draft else { return false }
        return draft.largeImage != nil && draft.preview.thumbnail != nil
    }

    // MARK: - Draft tracking

    /// Call on every draft change.
    ///
    /// Agent: Debounced; cancels a fetch for a link that is no longer in the text.
    func draftChanged(_ text: String, enabled: Bool = SecurityPreferences.generatesLinkPreviews) {
        debounceTask?.cancel()
        guard enabled, let url = LinkDetector.firstPreviewableURL(in: text) else {
            clear(keepDismissed: !text.isEmpty)
            return
        }
        let key = Self.key(for: url)
        if dismissed.contains(key) {
            cancelFetch()
            setPhase(.idle)
            return
        }
        // Already showing (or fetching) this link: nothing to do.
        switch phase {
        case let .loading(current) where Self.key(for: current) == key: return
        case let .ready(current) where Self.key(forURLString: current.preview.url) == key: return
        default: break
        }
        if let cached = cache[key] {
            cancelFetch()
            resetOptions()
            setPhase(.ready(cached))
            return
        }
        debounceTask = Task { [weak self, debounce] in
            try? await Task.sleep(for: debounce)
            guard !Task.isCancelled else { return }
            self?.startFetch(url)
        }
    }

    private func startFetch(_ url: URL) {
        cancelFetch()
        resetOptions()
        setPhase(.loading(url))
        let key = Self.key(for: url)
        fetchTask = Task { [weak self, fetcher] in
            let result = try? await fetcher.fetchPreview(for: url)
            guard !Task.isCancelled, let self else { return }
            guard case let .loading(current) = self.phase, Self.key(for: current) == key else { return }
            if let result {
                self.remember(result, for: key)
                self.setPhase(.ready(result))
            } else {
                // Telegram shows nothing when a page has no preview.
                self.setPhase(.idle)
            }
        }
    }

    // MARK: - Options

    /// ✕ / "Remove preview": no preview for this link for the rest of the draft.
    func dismiss() {
        switch phase {
        case let .loading(url): dismissed.insert(Self.key(for: url))
        case let .ready(draft):
            if let key = Self.key(forURLString: draft.preview.url) { dismissed.insert(key) }
        case .idle: break
        }
        cancelFetch()
        setPhase(.idle)
    }

    func toggleShowsAboveText() {
        showsAboveText.toggle()
    }

    func toggleImageSize() {
        guard canToggleImageSize else { return }
        largeImageOverride = !usesLargeImage
    }

    // MARK: - Sending

    /// What to seal with the message about to be sent, then back to idle for the next draft.
    ///
    /// Agent: RETURNS nil unless the loaded preview's link is still in `text`.
    func takeAttachment(for text: String) -> LinkPreviewAttachment? {
        defer { reset() }
        guard let draft,
              let url = LinkDetector.firstPreviewableURL(in: text),
              Self.key(for: url) == Self.key(forURLString: draft.preview.url)
        else { return nil }
        var preview = draft.preview
        preview.showsAboveText = showsAboveText
        let large = usesLargeImage
        return LinkPreviewAttachment(
            preview: preview,
            largeImage: large ? draft.largeImage : nil,
            largeImageWidth: large ? draft.largeImageWidth : nil,
            largeImageHeight: large ? draft.largeImageHeight : nil
        )
    }

    /// Clears everything, including dismissed links (after a send, or leaving the chat).
    func reset() {
        debounceTask?.cancel()
        clear(keepDismissed: false)
    }

    // MARK: - Private

    private func clear(keepDismissed: Bool) {
        cancelFetch()
        if !keepDismissed { dismissed.removeAll() }
        resetOptions()
        setPhase(.idle)
    }

    private func cancelFetch() {
        fetchTask?.cancel()
        fetchTask = nil
    }

    private func resetOptions() {
        showsAboveText = false
        largeImageOverride = nil
    }

    private func setPhase(_ new: Phase) {
        guard phase != new else { return }
        phase = new
    }

    private func remember(_ draft: LinkPreviewDraft, for key: String) {
        cache[key] = draft
        cacheOrder.removeAll { $0 == key }
        cacheOrder.append(key)
        while cacheOrder.count > 8 {
            cache.removeValue(forKey: cacheOrder.removeFirst())
        }
    }

    /// Identity of a link: scheme and host lowercased, everything else as typed.
    ///
    /// Human: `Example.com/Tour` and `example.com/Tour` are the same page, but paths are
    /// case-sensitive — `youtu.be/dQw4w9WgXcQ` and `youtu.be/dqw4w9wgxcq` are different videos,
    /// so lowercasing the whole URL would hand one link the other's preview.
    /// Agent: Used for the cache, the dismissed set, and matching the draft's link at send time.
    private nonisolated static func key(for url: URL) -> String {
        guard let components = URLComponents(url: url, resolvingAgainstBaseURL: false),
              let host = components.percentEncodedHost
        else { return url.absoluteString }
        let scheme = components.scheme?.lowercased() ?? ""
        let port = components.port.map { ":\($0)" } ?? ""
        let query = components.percentEncodedQuery.map { "?\($0)" } ?? ""
        let fragment = components.percentEncodedFragment.map { "#\($0)" } ?? ""
        return "\(scheme)://\(host.lowercased())\(port)\(components.percentEncodedPath)\(query)\(fragment)"
    }

    private nonisolated static func key(forURLString string: String) -> String? {
        URL(string: string).map(key(for:))
    }
}
