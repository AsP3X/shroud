import SwiftUI

/// Message text with its links made tappable, styled the way Telegram styles them.
///
/// Human: Telegram's rule is simple: a link is drawn in the link colour, and underlined only
/// when that colour is the same as the text's. Incoming bubbles therefore show accent-coloured
/// links (`accentText`, which stays readable in dark mode) without an underline; outgoing
/// (accent) bubbles show white, underlined links. A tap goes through SwiftUI's `openURL`,
/// which `ConversationView` routes to the in-app browser.
/// Agent: Pure; detection is `LinkDetector` (same rules as the web client), cached per string.
enum MessageLinkText {
    /// `text` with a `.link` run for every detected link.
    static func attributed(_ text: String, isMine: Bool) -> AttributedString {
        var result = AttributedString(text)
        let links = self.links(in: text)
        guard !links.isEmpty else { return result }
        for link in links {
            guard let stringRange = Range(link.range, in: text),
                  let range = Range<AttributedString.Index>(stringRange, in: result)
            else { continue }
            result[range].link = link.url
            result[range].swiftUI.foregroundColor = isMine ? Color.white : Theme.accentText
            if isMine {
                result[range].swiftUI.underlineStyle = .single
            }
        }
        return result
    }

    /// Links in `text`, remembered for the strings a thread keeps redrawing.
    static func links(in text: String) -> [DetectedLink] {
        if let cached = cache[text] { return cached }
        let found = LinkDetector.links(in: text)
        if cache.count >= cacheLimit { cache.removeAll(keepingCapacity: true) }
        cache[text] = found
        return found
    }

    /// Whether `text` contains anything tappable (drives the "Copy Link" menu row).
    static func firstLink(in text: String) -> URL? {
        links(in: text).first?.url
    }

    private static let cacheLimit = 256
    private static var cache: [String: [DetectedLink]] = [:]
}
