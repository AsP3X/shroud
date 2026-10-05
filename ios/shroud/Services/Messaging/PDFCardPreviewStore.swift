import Foundation
import Observation
import UIKit

/// Sharp preview cards of PDFs that are on this device, drawn from the files themselves
/// (`docs/file-sharing.md` §10.1).
///
/// Human: The envelope's `th` has to fit 6 KB, so it is soft. Once the blob is here, the card
/// is drawn again at the screen's scale and replaces it. The renders are decrypted document
/// pages, so they live **in memory only**, bounded to about 24 MB, and leave with everything
/// else when the chats lock or the message is deleted.
/// Agent: Main actor. `MessagingController.requestPDFCard` does the decrypting and drawing; this
/// keeps the results, the LRU order and which messages are in flight or can't be drawn.
@MainActor
@Observable
final class PDFCardPreviewStore {
    static let shared = PDFCardPreviewStore()

    struct Card: Sendable {
        let image: UIImage
        let pageCount: Int
        /// The pixel width it was drawn at; a wider bubble asks again.
        let pixelWidth: Int
    }

    private(set) var cards: [UUID: Card] = [:]

    @ObservationIgnored private var order: [UUID] = []
    @ObservationIgnored private var inFlight: Set<UUID> = []
    /// Files that didn't parse, are locked or failed §4: not tried again this session.
    @ObservationIgnored private var refused: Set<UUID> = []
    /// Bumped on lock, so a render that finishes afterwards is dropped.
    @ObservationIgnored private var generation = 0

    static let byteBudget = 24 * 1024 * 1024

    func card(for id: UUID) -> Card? {
        cards[id]
    }

    /// Whether `id` still needs a render `pixelWidth` wide.
    func needsRender(_ id: UUID, pixelWidth: Int) -> Bool {
        guard !inFlight.contains(id), !refused.contains(id) else { return false }
        guard let card = cards[id] else { return true }
        return card.pixelWidth < pixelWidth
    }

    /// Runs `render` once for `id`, keeping its result.
    func render(_ id: UUID, pixelWidth: Int, _ render: @escaping @Sendable () async -> Card?) {
        guard needsRender(id, pixelWidth: pixelWidth) else { return }
        inFlight.insert(id)
        let started = generation
        Task {
            let card = await render()
            // Locked or deleted while it rendered: drop it.
            guard started == self.generation, self.inFlight.remove(id) != nil else { return }
            guard let card else {
                self.refused.insert(id)
                return
            }
            self.store(card, for: id)
        }
    }

    private func store(_ card: Card, for id: UUID) {
        cards[id] = card
        order.removeAll { $0 == id }
        order.append(id)
        var bytes = order.reduce(0) { $0 + (cards[$1].map(Self.cost) ?? 0) }
        while bytes > Self.byteBudget, order.count > 1 {
            let evicted = order.removeFirst()
            bytes -= cards[evicted].map(Self.cost) ?? 0
            cards[evicted] = nil
        }
    }

    private static func cost(_ card: Card) -> Int {
        guard let image = card.image.cgImage else { return 0 }
        return image.bytesPerRow * image.height
    }

    func remove(ids: [UUID]) {
        for id in ids {
            cards[id] = nil
            inFlight.remove(id)
            refused.remove(id)
        }
        order.removeAll { ids.contains($0) }
    }

    /// Chats locked or signed out: every render leaves memory.
    func removeAll() {
        generation += 1
        cards = [:]
        order = []
        inFlight = []
        refused = []
    }
}
