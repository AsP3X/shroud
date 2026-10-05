import SwiftUI
import UIKit

/// The PDF viewer's page overview: one column of thumbnails on the left, as in Preview or Acrobat
/// (`docs/file-sharing.md` §10.2).
///
/// Human: Beside the pages in regular width, as a drawer from the left on an iPhone. The page in
/// view is outlined, and the list keeps it in sight while the document scrolls — but never
/// pulls the list away from under a reader who is scrolling it.
/// Agent: Rows are sized from the page boxes alone; thumbnails render lazily through the
/// model's `PDFThumbnailRenderer`, so a 500-page file lists at once and renders only what shows.
struct PDFPageList: View {
    let model: PDFViewerModel
    /// A tap picked a page (the drawer closes on it).
    var onPick: () -> Void = {}

    @Environment(\.displayScale) private var displayScale
    /// The reader is scrolling the list: it doesn't follow the document meanwhile.
    @State private var listScrolling = false

    static let thumbnailWidth: CGFloat = 128
    static let maxThumbnailHeight: CGFloat = 182

    var body: some View {
        ScrollViewReader { proxy in
            ScrollView {
                LazyVStack(spacing: 20) {
                    ForEach(0 ..< model.pageCount, id: \.self) { index in
                        row(index)
                            .id(index)
                    }
                }
                .padding(.vertical, 16)
                .frame(maxWidth: .infinity)
            }
            .scrollIndicators(.hidden)
            .onScrollPhaseChange { _, phase in
                listScrolling = phase != .idle
            }
            .onAppear {
                proxy.scrollTo(model.pageIndex, anchor: .center)
            }
            .onChange(of: model.pageIndex) { _, index in
                guard !listScrolling else { return }
                // No anchor: scrolls only when the row has left the visible part.
                withAnimation(.easeInOut(duration: 0.25)) {
                    proxy.scrollTo(index)
                }
            }
        }
        .accessibilityElement(children: .contain)
        .accessibilityLabel("Pages")
    }

    private func row(_ index: Int) -> some View {
        let isCurrent = index == model.pageIndex
        return Button {
            model.go(toPage: index)
            onPick()
        } label: {
            PDFPageThumbnail(
                index: index,
                aspect: model.aspect(ofPage: index),
                isCurrent: isCurrent,
                renderer: model.thumbnails,
                pixelWidth: Int((Self.thumbnailWidth * displayScale).rounded())
            )
        }
        .buttonStyle(.plain)
        .accessibilityLabel("Page \(index + 1) of \(model.pageCount)")
        .accessibilityAddTraits(isCurrent ? .isSelected : [])
    }
}

/// One page in the list: white at the page's aspect, 128 wide, its number under it.
private struct PDFPageThumbnail: View {
    let index: Int
    let aspect: CGFloat
    let isCurrent: Bool
    let renderer: PDFThumbnailRenderer
    let pixelWidth: Int

    @State private var image: UIImage?

    private var size: CGSize {
        let width = PDFPageList.thumbnailWidth
        let height = min(PDFPageList.maxThumbnailHeight, width / max(aspect, 0.1))
        // A page taller than the cap keeps its aspect at a narrower width.
        return CGSize(width: min(width, height * aspect), height: height)
    }

    var body: some View {
        VStack(spacing: 6) {
            Color.white
                .overlay {
                    if let image {
                        Image(uiImage: image)
                            .resizable()
                            .interpolation(.high)
                            .transition(.opacity)
                    }
                }
                .frame(width: size.width, height: size.height)
                .clipShape(RoundedRectangle(cornerRadius: 2, style: .continuous))
                .overlay {
                    RoundedRectangle(cornerRadius: 2, style: .continuous)
                        .strokeBorder(Color.black.opacity(0.1), lineWidth: 0.5)
                }
                .shadow(color: .black.opacity(0.12), radius: 3, y: 1)
                .overlay {
                    if isCurrent {
                        RoundedRectangle(cornerRadius: 5, style: .continuous)
                            .strokeBorder(Theme.accent, lineWidth: 2)
                            .padding(-5)
                    }
                }
            Text("\(index + 1)")
                .font(.system(size: 12, weight: isCurrent ? .semibold : .regular).monospacedDigit())
                .foregroundStyle(isCurrent ? Theme.accentText : Theme.textSecondary)
        }
        .padding(.top, 5)
        .animation(.easeOut(duration: 0.15), value: image != nil)
        .task {
            guard image == nil else { return }
            image = await renderer.thumbnail(page: index + 1, pixelWidth: pixelWidth)
        }
    }
}

/// Page thumbnails off the main thread, one at a time, from CoreGraphics' own copy of the
/// document, kept for the viewer's life in a cache bounded to about 12 MB.
///
/// Agent: Its own `CGPDFDocument`, so it never touches the `PDFDocument` the view draws. A row
/// that scrolled away before its turn is cancelled and skipped. Unlocked with the viewer's
/// password when the file has one.
actor PDFThumbnailRenderer {
    private let url: URL
    private let password: String?
    private var document: CGPDFDocument?
    private var opened = false
    private var cache: [Int: UIImage] = [:]
    private var order: [Int] = []
    private var cachedBytes = 0

    static let byteBudget = 12 * 1024 * 1024

    init(url: URL, password: String?) {
        self.url = url
        self.password = password
    }

    func thumbnail(page: Int, pixelWidth: Int) -> UIImage? {
        if let cached = cache[page] {
            order.removeAll { $0 == page }
            order.append(page)
            return cached
        }
        guard !Task.isCancelled else { return nil }
        if !opened {
            opened = true
            let document = CGPDFDocument(url as CFURL)
            if let document, !document.isUnlocked, let password {
                _ = document.unlockWithPassword(password)
            }
            self.document = PDFPagePreview.document(document)
        }
        guard let document, let rendered = PDFPagePreview.renderPage(page, of: document, width: pixelWidth) else {
            return nil
        }
        let image = UIImage(cgImage: rendered)
        cache[page] = image
        order.append(page)
        cachedBytes += rendered.bytesPerRow * rendered.height
        while cachedBytes > Self.byteBudget, order.count > 1 {
            let evicted = order.removeFirst()
            if let old = cache.removeValue(forKey: evicted)?.cgImage {
                cachedBytes -= old.bytesPerRow * old.height
            }
        }
        return image
    }
}
