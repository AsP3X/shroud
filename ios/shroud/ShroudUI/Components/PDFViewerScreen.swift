import PDFKit
import SwiftUI
import UIKit

/// Shroud's own PDF viewer (`docs/file-sharing.md` §10.2): the pages scroll continuously under
/// a Liquid Glass bar with the name and `Page 3 of 12`, Pages, Search and Share; the page overview
/// is a column of thumbnails on the left (`PDFPageList`).
///
/// Human: PDFKit draws the pages in tiles at the zoom in view, so a 500-page file opens as fast
/// as a one-pager and text stays sharp at 6×. A tap on a page hides the bars for reading; a
/// double tap zooms to 2.5× where it landed and back. Links to other pages jump; web and mail
/// links open the way links in messages do; nothing else is followed.
/// Agent: Presented full screen by `FileViewerPresenter.pdf`, which owns the staged plaintext
/// and removes it once this is gone. `onClose` asks the presenter to dismiss.
struct PDFViewerScreen: View {
    @State var model: PDFViewerModel
    let onClose: () -> Void

    @FocusState private var searchFocused: Bool
    @Environment(\.horizontalSizeClass) private var sizeClass
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    /// How far the drawer follows a finger dragging it shut.
    @State private var drawerDrag: CGFloat = 0

    /// The sidebar sits beside the pages; in compact width the list is a drawer.
    private var isRegular: Bool { sizeClass == .regular }
    private var sidebarAnimation: Animation? {
        reduceMotion ? nil : .timingCurve(
            ShroudPDFView.slideCurve.0, ShroudPDFView.slideCurve.1, ShroudPDFView.slideCurve.2, ShroudPDFView.slideCurve.3,
            duration: ShroudPDFView.slideDuration
        )
    }

    static let sidebarWidth: CGFloat = 200
    static let drawerWidth: CGFloat = 280

    var body: some View {
        NavigationStack {
            content
                .frame(maxWidth: .infinity, maxHeight: .infinity)
                .background(Theme.pdfCanvas.ignoresSafeArea())
                .navigationBarTitleDisplayMode(.inline)
                .toolbar { toolbar }
                .toolbarVisibility(model.chromeVisible ? .visible : .hidden, for: .navigationBar)
                .background { keyCommands }
        }
        .overlay { drawer }
        .statusBarHidden(!model.chromeVisible)
        .task { await model.load() }
        .onAppear { model.regularWidth = isRegular }
        .onChange(of: isRegular) { _, regular in
            model.regularWidth = regular
            model.placeSidebar()
        }
        .onDisappear { model.close() }
        .accessibilityAction(.escape) { escape() }
    }

    @ViewBuilder
    private var content: some View {
        switch model.phase {
        case .loading:
            ProgressView()
                .controlSize(.large)
        case .ready:
            HStack(spacing: 0) {
                if isRegular, model.showsPages {
                    PDFPageList(model: model)
                        .frame(width: Self.sidebarWidth)
                        .background(Theme.pdfSidebar.ignoresSafeArea())
                        .overlay(alignment: .trailing) {
                            Rectangle()
                                .fill(Theme.separator)
                                .frame(width: 1)
                                .ignoresSafeArea()
                        }
                        .transition(.move(edge: .leading))
                        // Over the pages while they glide out from under it.
                        .zIndex(1)
                }
                PDFKitView(model: model)
                    .ignoresSafeArea()
                    // The pages take their new width at once and glide there on their own
                    // (`ShroudPDFView.glideNextResize`), instead of PDFKit re-fitting every frame.
                    .transaction { $0.animation = nil }
            }
        case let .locked(wrongPassword):
            PDFPasswordCard(wrongPassword: wrongPassword) { model.unlock(with: $0) }
        case .damaged:
            PDFDamagedCard(url: model.url, title: model.title)
        }
    }

    // MARK: - Bar

    @ToolbarContentBuilder
    private var toolbar: some ToolbarContent {
        if model.isSearching {
            ToolbarItem(placement: .principal) {
                searchField
            }
            ToolbarItemGroup(placement: .topBarTrailing) {
                Button("Previous result", systemImage: "chevron.up") { model.stepMatch(-1) }
                    .disabled(model.matches.isEmpty)
                Button("Next result", systemImage: "chevron.down") { model.stepMatch(1) }
                    .disabled(model.matches.isEmpty)
            }
            ToolbarSpacer(.fixed, placement: .topBarTrailing)
            ToolbarItem(placement: .topBarTrailing) {
                Button("Done") { withAnimation(Motion.snappy) { model.endSearch() } }
                    .keyboardShortcut(.cancelAction)
            }
        } else {
            ToolbarItem(placement: .topBarLeading) {
                Button(role: .close, action: onClose)
                    .accessibilityLabel("Close")
                    .keyboardShortcut(.cancelAction)
            }
            if model.phase == .ready, model.pageCount > 0 {
                ToolbarItem(placement: .topBarLeading) {
                    Button("Pages", systemImage: "sidebar.left", action: togglePages)
                    .accessibilityAddTraits(model.showsPages ? .isSelected : [])
                }
            }
            ToolbarItem(placement: .principal) {
                titleBlock
            }
            if model.phase == .ready {
                ToolbarItemGroup(placement: .topBarTrailing) {
                    Button("Search", systemImage: "magnifyingglass") {
                        withAnimation(Motion.snappy) { model.beginSearch() }
                        searchFocused = true
                    }
                    .keyboardShortcut("f", modifiers: .command)
                    ShareLink(item: model.url, preview: SharePreview(model.title)) {
                        Label("Share", systemImage: "square.and.arrow.up")
                    }
                }
            } else if model.phase == .damaged {
                ToolbarItem(placement: .topBarTrailing) {
                    ShareLink(item: model.url, preview: SharePreview(model.title)) {
                        Label("Share", systemImage: "square.and.arrow.up")
                    }
                }
            }
        }
    }

    private var titleBlock: some View {
        VStack(spacing: 1) {
            Text(model.title)
                .font(.system(size: 15, weight: .semibold))
                .foregroundStyle(Theme.textPrimary)
                .lineLimit(1)
                .truncationMode(.middle)
            if !model.subtitle.isEmpty {
                Text(model.subtitle)
                    .font(.system(size: 12).monospacedDigit())
                    .foregroundStyle(Theme.textSecondary)
                    .lineLimit(1)
                    .contentTransition(.numericText())
                    .animation(Motion.snappy, value: model.pageIndex)
            }
        }
        // Its own glass, as the photo viewer's name pill: a white page scrolls under the bar,
        // and white dark-mode text needs something behind it.
        .padding(.horizontal, 14)
        .padding(.vertical, 5)
        .glassEffect(.regular, in: .capsule)
        .accessibilityElement(children: .combine)
        .accessibilityAddTraits(.isHeader)
    }

    private var searchField: some View {
        HStack(spacing: 6) {
            Image(systemName: "magnifyingglass")
                .font(.system(size: 15, weight: .medium))
                .foregroundStyle(Theme.textSecondary)
                .accessibilityHidden(true)
            TextField("Search in PDF", text: $model.query)
                .font(.system(size: 16))
                .focused($searchFocused)
                .submitLabel(.search)
                .autocorrectionDisabled()
                .textInputAutocapitalization(.never)
                .onSubmit {
                    model.stepMatch(1)
                    searchFocused = true
                }
            if !model.resultLabel.isEmpty {
                Text(model.resultLabel)
                    .font(.system(size: 13).monospacedDigit())
                    .foregroundStyle(Theme.textSecondary)
                    .lineLimit(1)
                    .fixedSize()
                    .contentTransition(.numericText())
            }
        }
        .padding(.horizontal, 12)
        .frame(height: 36)
        .frame(minWidth: 180, idealWidth: 400, maxWidth: .infinity)
        .glassEffect(.regular, in: .capsule)
    }

    // MARK: - Keys

    /// iPad keyboards: arrows and page keys step a page; Home / End go to the ends.
    @ViewBuilder
    private var keyCommands: some View {
        if model.phase == .ready, !model.isSearching {
            Group {
                Button("Previous page") { model.step(-1) }
                    .keyboardShortcut(.leftArrow, modifiers: [])
                Button("Next page") { model.step(1) }
                    .keyboardShortcut(.rightArrow, modifiers: [])
                Button("Previous page") { model.step(-1) }
                    .keyboardShortcut(.pageUp, modifiers: [])
                Button("Next page") { model.step(1) }
                    .keyboardShortcut(.pageDown, modifiers: [])
                Button("First page") { model.go(toPage: 0) }
                    .keyboardShortcut(.home, modifiers: [])
                Button("Last page") { model.go(toPage: model.pageCount - 1) }
                    .keyboardShortcut(.end, modifiers: [])
            }
            .opacity(0)
            .accessibilityHidden(true)
        }
    }

    // MARK: - Drawer

    /// Compact width: the page list slides in from the left over a scrim. The scrim and the list
    /// are inserted on their own, not inside a container that comes and goes, so each keeps its
    /// own transition (a container's insertion would fade them in together).
    private var drawer: some View {
        let open = !isRegular && model.showsPages && model.phase == .ready
        return ZStack(alignment: .leading) {
            if open {
                Color.black.opacity(0.3)
                    .ignoresSafeArea()
                    .onTapGesture { closeDrawer() }
                    .accessibilityHidden(true)
                    .transition(.opacity)
            }
            if open {
                VStack(alignment: .leading, spacing: 0) {
                    Text("Pages")
                        .font(.system(size: 17, weight: .semibold))
                        .foregroundStyle(Theme.textPrimary)
                        .accessibilityAddTraits(.isHeader)
                        .padding(.horizontal, 20)
                        .padding(.top, 12)
                    PDFPageList(model: model) { closeDrawer() }
                }
                .containerRelativeFrame(.horizontal) { width, _ in min(Self.drawerWidth, width * 0.8) }
                .frame(maxHeight: .infinity, alignment: .top)
                .background(Theme.pdfSidebar.ignoresSafeArea())
                .shadow(color: .black.opacity(0.18), radius: 20, x: 4)
                .offset(x: min(0, drawerDrag))
                .gesture(
                    DragGesture(minimumDistance: 12)
                        .onChanged { drawerDrag = $0.translation.width }
                        .onEnded { value in
                            if value.translation.width < -60 || value.predictedEndTranslation.width < -140 {
                                closeDrawer()
                            } else {
                                withAnimation(Motion.snappy) { drawerDrag = 0 }
                            }
                        }
                )
                .transition(.move(edge: .leading))
            }
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .leading)
        .accessibilityElement(children: .contain)
        .accessibilityAddTraits(open ? .isModal : [])
        .accessibilityAction(.escape) { if open { closeDrawer() } }
    }

    private func togglePages() {
        if isRegular, !reduceMotion {
            (model.pdfView as? ShroudPDFView)?.glideNextResize()
        }
        withAnimation(sidebarAnimation) { model.togglePages() }
    }

    private func closeDrawer() {
        withAnimation(sidebarAnimation) {
            model.showsPages = false
            drawerDrag = 0
        }
    }

    private func escape() {
        if model.isSearching {
            model.endSearch()
        } else if !isRegular, model.showsPages {
            closeDrawer()
        } else {
            onClose()
        }
    }
}

// MARK: - PDFKit

/// The `PDFView`, sized so a page fits the width (at most 920 pt wide) and zooms to 6×.
private struct PDFKitView: UIViewRepresentable {
    let model: PDFViewerModel

    func makeCoordinator() -> Coordinator {
        Coordinator(model: model)
    }

    func makeUIView(context: Context) -> ShroudPDFView {
        let view = ShroudPDFView()
        view.backgroundColor = UIColor(Theme.pdfCanvas)
        view.displayMode = .singlePageContinuous
        view.displayDirection = .vertical
        view.displaysPageBreaks = true
        // 12 between pages and at the sides (`docs/file-sharing.md` §10.2).
        view.pageBreakMargins = UIEdgeInsets(top: 6, left: 12, bottom: 6, right: 12)
        view.pageShadowsEnabled = true
        view.autoScales = false
        view.delegate = context.coordinator
        view.document = model.document
        // After the first fit, which changes the scale: open at the top of the page last read.
        view.onFirstLayout = { [weak view] in
            guard let view, let page = model.document?.page(at: model.pageIndex) else { return }
            view.open(at: page)
        }
        context.coordinator.attach(view)
        model.pdfView = view
        return view
    }

    func updateUIView(_ view: ShroudPDFView, context: Context) {
        if view.document !== model.document {
            view.document = model.document
        }
    }

    static func dismantleUIView(_ view: ShroudPDFView, coordinator: Coordinator) {
        coordinator.detach()
    }

    @MainActor
    final class Coordinator: NSObject, PDFViewDelegate, UIGestureRecognizerDelegate {
        private let model: PDFViewerModel
        private weak var view: ShroudPDFView?
        private var pageObserver: NSObjectProtocol?

        init(model: PDFViewerModel) {
            self.model = model
        }

        func attach(_ view: ShroudPDFView) {
            self.view = view
            let doubleTap = UITapGestureRecognizer(target: self, action: #selector(doubleTapped(_:)))
            doubleTap.numberOfTapsRequired = 2
            doubleTap.delegate = self
            view.addGestureRecognizer(doubleTap)
            let tap = UITapGestureRecognizer(target: self, action: #selector(tapped(_:)))
            tap.require(toFail: doubleTap)
            tap.cancelsTouchesInView = false
            tap.delegate = self
            view.addGestureRecognizer(tap)
            pageObserver = NotificationCenter.default.addObserver(
                forName: .PDFViewPageChanged,
                object: view,
                queue: .main
            ) { [weak self] _ in
                MainActor.assumeIsolated { self?.reportPage() }
            }
        }

        func detach() {
            if let pageObserver { NotificationCenter.default.removeObserver(pageObserver) }
            pageObserver = nil
        }

        private func reportPage() {
            guard let view, let page = view.currentPage, let document = view.document else { return }
            model.pageChanged(to: document.index(for: page))
        }

        /// A tap that isn't on a link shows or hides the bars.
        @objc private func tapped(_ gesture: UITapGestureRecognizer) {
            guard let view else { return }
            let point = gesture.location(in: view)
            // PDFKit follows the link itself.
            if let page = view.page(for: point, nearest: false),
               let annotation = page.annotation(at: view.convert(point, to: page)),
               annotation.url != nil || annotation.destination != nil || annotation.action != nil
            {
                return
            }
            if view.currentSelection != nil {
                view.clearSelection()
                return
            }
            withAnimation(.easeInOut(duration: 0.18)) {
                model.chromeVisible.toggle()
            }
        }

        @objc private func doubleTapped(_ gesture: UITapGestureRecognizer) {
            view?.toggleZoom(at: gesture.location(in: view))
        }

        func gestureRecognizer(
            _ gestureRecognizer: UIGestureRecognizer,
            shouldRecognizeSimultaneouslyWith otherGestureRecognizer: UIGestureRecognizer
        ) -> Bool {
            true
        }

        // MARK: PDFViewDelegate

        /// Web and mail links open as links in messages do; other schemes do nothing.
        func pdfViewWillClick(onLink sender: PDFView, with url: URL) {
            _ = InAppBrowser.open(url)
        }
    }
}

/// `PDFView` that fits the page width (capped at 920 pt) and zooms from there to 6×.
///
/// Agent: `baseScale` is the fit; a rotation or a split-view resize keeps the relative zoom.
final class ShroudPDFView: PDFView {
    static let maxPageWidth: CGFloat = 920
    static let maxZoom: CGFloat = 6
    static let doubleTapZoom: CGFloat = 2.5
    /// The pages sidebar's slide (§10.2): decelerating, without a jump at the start.
    static let slideDuration: TimeInterval = 0.28
    static let slideCurve: (Double, Double, Double, Double) = (0.32, 0.72, 0, 1)

    var onFirstLayout: (() -> Void)?
    private(set) var baseScale: CGFloat = 0
    private var fittedWidth: CGFloat = 0
    /// Where the view sat in its window at the last layout, for a glide from there.
    private var lastFrameInWindow: CGRect?
    /// Until when the next re-fit glides instead of jumping (`glideNextResize`).
    private var glideUntil: CFTimeInterval = 0
    /// The page opened on, while the first layout passes settle (the bar's insets, the sidebar):
    /// each re-fit lands on its top again instead of keeping the middle of the view.
    private var openingPage: PDFPage?

    func open(at page: PDFPage) {
        openingPage = page
        scrollToTop(of: page)
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.6) { [weak self] in
            self?.openingPage = nil
        }
    }

    /// The sidebar is about to slide in or out beside the pages. The view takes its new width at
    /// once, so PDFKit lays the pages out a single time; they then glide from where they were to
    /// where they now are with the sidebar, as a Core Animation transform the render server runs.
    func glideNextResize() {
        glideUntil = CACurrentMediaTime() + 0.3
    }

    override func layoutSubviews() {
        let oldFrame = lastFrameInWindow
        let oldScale = scaleFactor
        super.layoutSubviews()
        let frameInWindow = convert(bounds, to: nil)
        lastFrameInWindow = frameInWindow
        guard bounds.width > 0, document != nil, abs(bounds.width - fittedWidth) > 0.5 else { return }
        let first = fittedWidth == 0
        fittedWidth = bounds.width
        refit()
        if !first, CACurrentMediaTime() < glideUntil, let oldFrame {
            glideUntil = 0
            glide(from: oldFrame, scale: oldScale, to: frameInWindow)
        }
        if first {
            let callback = onFirstLayout
            onFirstLayout = nil
            callback?()
        }
    }

    private func refit() {
        // The spot in the middle of the view stays put, so a sidebar opening beside the pages or a
        // rotation keeps the reader's place.
        let center = CGPoint(x: bounds.midX, y: bounds.midY)
        let anchorPage = baseScale > 0 ? page(for: center, nearest: true) : nil
        let anchorPoint = anchorPage.map { convert(center, to: $0) }
        defer {
            if let openingPage {
                scrollToTop(of: openingPage)
            } else if let anchorPage, let anchorPoint {
                keep(anchorPoint, on: anchorPage, at: center)
            }
        }
        let relative = baseScale > 0 ? scaleFactor / baseScale : 1
        let fit = scaleFactorForSizeToFit
        guard fit > 0 else { return }
        var base = fit
        if let width = widestPageWidth, width > 0 {
            base = min(fit, Self.maxPageWidth / width)
        }
        baseScale = base
        minScaleFactor = base
        maxScaleFactor = base * Self.maxZoom
        scaleFactor = base * min(max(1, relative), Self.maxZoom)
    }

    /// Draws the freshly fitted pages as they were before (`old` frame, `scale`), then lets them
    /// settle. `refit` kept the spot in the middle of the view in the middle, so the mapping is a
    /// scale about the middle plus the move between the two middles. It is applied to what the
    /// scroll view shows, which is clipped to the new frame only once the glide is over: the pages
    /// still reach where they were, and pages around the view fill in while they shrink.
    private func glide(from old: CGRect, scale oldScale: CGFloat, to new: CGRect) {
        guard let scroll = Self.scrollView(in: self), scaleFactor > 0, oldScale > 0 else { return }
        let ratio = oldScale / scaleFactor
        var from = CATransform3DMakeTranslation(old.midX - new.midX, old.midY - new.midY, 0)
        from = CATransform3DScale(from, ratio, ratio, 1)
        let curve = Self.slideCurve
        let animation = CABasicAnimation(keyPath: "sublayerTransform")
        animation.fromValue = NSValue(caTransform3D: from)
        animation.toValue = NSValue(caTransform3D: CATransform3DIdentity)
        animation.duration = Self.slideDuration
        animation.timingFunction = CAMediaTimingFunction(
            controlPoints: Float(curve.0), Float(curve.1), Float(curve.2), Float(curve.3)
        )
        CATransaction.begin()
        CATransaction.setCompletionBlock { [weak scroll] in scroll?.clipsToBounds = true }
        scroll.clipsToBounds = false
        scroll.layer.add(animation, forKey: "glide")
        CATransaction.commit()
    }

    /// Puts the top of `page` just under the bar (PDFKit's own `go(to:)` stops short of it and
    /// leaves the previous page's foot in view).
    func scrollToTop(of page: PDFPage) {
        guard page.rotation % 180 == 0, let scroll = Self.scrollView(in: self) else {
            go(to: page)
            return
        }
        go(to: page)
        layoutIfNeeded()
        let box = page.bounds(for: displayBox)
        let top = convert(CGPoint(x: box.midX, y: page.rotation == 180 ? box.minY : box.maxY), from: page)
        let target = CGPoint(x: bounds.midX, y: scroll.adjustedContentInset.top + pageBreakMargins.top)
        keep(convert(top, to: page), on: page, at: CGPoint(x: top.x, y: target.y))
    }

    /// Scrolls so `point` of `page` sits at `target` in the view again.
    private func keep(_ point: CGPoint, on page: PDFPage, at target: CGPoint) {
        guard let scroll = Self.scrollView(in: self) else { return }
        layoutIfNeeded()
        let now = convert(point, from: page)
        let insets = scroll.adjustedContentInset
        let maxX = max(-insets.left, scroll.contentSize.width - scroll.bounds.width + insets.right)
        let maxY = max(-insets.top, scroll.contentSize.height - scroll.bounds.height + insets.bottom)
        scroll.contentOffset = CGPoint(
            x: min(max(-insets.left, scroll.contentOffset.x + now.x - target.x), maxX),
            y: min(max(-insets.top, scroll.contentOffset.y + now.y - target.y), maxY)
        )
    }

    /// The first page's shown width (crop box, turned by its rotation).
    private var widestPageWidth: CGFloat? {
        guard let page = document?.page(at: 0) else { return nil }
        let box = page.bounds(for: displayBox)
        return page.rotation % 180 == 0 ? box.width : box.height
    }

    /// 1× ↔ 2.5× around `point`, animated through the scroll view PDFKit zooms with.
    func toggleZoom(at point: CGPoint) {
        guard baseScale > 0 else { return }
        if scaleFactor > baseScale * 1.05 {
            animateScale(to: baseScale, around: nil)
        } else {
            animateScale(to: baseScale * Self.doubleTapZoom, around: point)
        }
    }

    private func animateScale(to target: CGFloat, around point: CGPoint?) {
        guard let scrollView = Self.scrollView(in: self), let zoomed = scrollView.delegate?.viewForZooming?(in: scrollView) else {
            scaleFactor = target
            return
        }
        // The scroll view's zoom tracks `scaleFactor` by a fixed ratio.
        let ratio = scrollView.zoomScale / scaleFactor
        let targetZoom = target * ratio
        guard let point else {
            scrollView.setZoomScale(targetZoom, animated: true)
            return
        }
        let center = convert(point, to: zoomed)
        let size = CGSize(width: scrollView.bounds.width / targetZoom, height: scrollView.bounds.height / targetZoom)
        scrollView.zoom(to: CGRect(x: center.x - size.width / 2, y: center.y - size.height / 2, width: size.width, height: size.height), animated: true)
    }

    private static func scrollView(in view: UIView) -> UIScrollView? {
        for subview in view.subviews {
            if let scroll = subview as? UIScrollView { return scroll }
            if let nested = scrollView(in: subview) { return nested }
        }
        return nil
    }
}

// MARK: - States

/// A locked PDF: the password field and Open (§10.2).
private struct PDFPasswordCard: View {
    let wrongPassword: Bool
    let onUnlock: (String) -> Void

    @State private var password = ""
    @FocusState private var focused: Bool

    var body: some View {
        VStack(spacing: 14) {
            Image(systemName: "lock.fill")
                .font(.system(size: 34, weight: .semibold))
                .foregroundStyle(Theme.accent)
                .padding(.bottom, 4)
                .accessibilityHidden(true)
            Text("This PDF is protected")
                .font(.system(size: 20, weight: .semibold))
                .foregroundStyle(Theme.textPrimary)
                .multilineTextAlignment(.center)
            Text("Enter its password to open it.")
                .font(.system(size: 15))
                .foregroundStyle(Theme.textSecondary)
                .multilineTextAlignment(.center)
            SecureField("Password", text: $password)
                .textContentType(.oneTimeCode)
                .font(.system(size: 17))
                .focused($focused)
                .submitLabel(.go)
                .onSubmit(unlock)
                .padding(.horizontal, 14)
                .frame(height: 46)
                .background(Theme.background, in: RoundedRectangle(cornerRadius: 12, style: .continuous))
                .overlay {
                    RoundedRectangle(cornerRadius: 12, style: .continuous)
                        .strokeBorder(wrongPassword ? Theme.danger : Theme.separator, lineWidth: 1)
                }
                .padding(.top, 6)
            if wrongPassword {
                Text("Wrong password. Try again.")
                    .font(.system(size: 13, weight: .medium))
                    .foregroundStyle(Theme.dangerText)
                    .transition(.opacity)
            }
            Button(action: unlock) {
                Text("Open")
                    .font(.system(size: 17, weight: .semibold))
                    .frame(maxWidth: .infinity)
                    .frame(height: 48)
            }
            .buttonStyle(.glassProminent)
            .tint(Theme.accent)
            .disabled(password.isEmpty)
            .padding(.top, 4)
        }
        .padding(24)
        .frame(maxWidth: 360)
        .animation(Motion.snappy, value: wrongPassword)
        .onAppear { focused = true }
        .onChange(of: wrongPassword) { _, wrong in
            if wrong {
                password = ""
                Haptics.notification(.error)
            }
        }
    }

    private func unlock() {
        guard !password.isEmpty else { return }
        onUnlock(password)
    }
}

/// A PDF PDFKit can't read: say so, and keep Share (§10.2).
private struct PDFDamagedCard: View {
    let url: URL
    let title: String

    var body: some View {
        VStack(spacing: 12) {
            Image(systemName: "doc.questionmark")
                .font(.system(size: 38, weight: .regular))
                .foregroundStyle(Theme.textSecondary)
                .padding(.bottom, 4)
                .accessibilityHidden(true)
            Text("Shroud can't show this PDF.")
                .font(.system(size: 20, weight: .semibold))
                .foregroundStyle(Theme.textPrimary)
                .multilineTextAlignment(.center)
            Text("It may be damaged or use features Shroud can't display.")
                .font(.system(size: 15))
                .foregroundStyle(Theme.textSecondary)
                .multilineTextAlignment(.center)
            ShareLink(item: url, preview: SharePreview(title)) {
                Label("Share", systemImage: "square.and.arrow.up")
                    .font(.system(size: 17, weight: .semibold))
                    .padding(.horizontal, 8)
                    .frame(height: 44)
            }
            .buttonStyle(.glass)
            .padding(.top, 8)
        }
        .padding(24)
        .frame(maxWidth: 360)
    }
}
