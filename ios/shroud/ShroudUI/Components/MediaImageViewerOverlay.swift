import Photos
import SwiftUI
import UIKit
import UniformTypeIdentifiers

/// Telegram-style fullscreen **overlay** for chat photos (not a pushed screen).
///
/// Matches `design/image_viewer.PNG`:
/// - back / name+date pill / more across the top, share / edit pair / delete across the bottom
/// - the photo is always shown whole (aspect fit) on black — nothing is ever cropped
/// - pinch, double-tap and pan to zoom; swipe sideways to page through the chat's photos
/// - drag down (or back) to dismiss, with the photo tracking the finger
struct MediaImageViewerOverlay: View {
    /// One photo in the conversation's media strip.
    struct Item: Identifiable, Equatable {
        let id: UUID
        /// Pill title — `"You"` for outbound, peer username for inbound.
        let title: String
        /// Secondary line under title (e.g. `26.06.26`).
        let dateLine: String
        /// User caption, when the photo was sent with one.
        let caption: String?
        /// `nil` until the blob has been downloaded and decrypted.
        let image: UIImage?
        /// Encoded bytes, used to save/share at the sender's original quality.
        let data: Data?
        /// Width ÷ height from the message metadata — known before the image lands.
        let aspect: CGFloat

        static func == (lhs: Item, rhs: Item) -> Bool {
            lhs.id == rhs.id && lhs.image === rhs.image && lhs.caption == rhs.caption
        }
    }

    let items: [Item]
    let initialID: UUID
    var onClose: () -> Void
    /// Asks the host to fetch a page that hasn't been decrypted yet.
    var onLoad: ((UUID) -> Void)?
    var onComingSoon: ((String) -> Void)?

    @State private var currentID: UUID
    @State private var dragOffset: CGSize = .zero
    @State private var dimOpacity: Double = 1
    @State private var chromeVisible = true
    @State private var banner: String?

    init(
        items: [Item],
        initialID: UUID,
        onClose: @escaping () -> Void,
        onLoad: ((UUID) -> Void)? = nil,
        onComingSoon: ((String) -> Void)? = nil
    ) {
        self.items = items
        self.initialID = initialID
        self.onClose = onClose
        self.onLoad = onLoad
        self.onComingSoon = onComingSoon
        _currentID = State(initialValue: initialID)
    }

    private let circleFill = Color(red: 44 / 255, green: 44 / 255, blue: 46 / 255).opacity(0.92)

    private var currentItem: Item? {
        items.first { $0.id == currentID } ?? items.first
    }

    /// Window insets — reliable when this overlay sits inside a view that already ate the safe area.
    private static var keyWindowSafeArea: UIEdgeInsets {
        let scenes = UIApplication.shared.connectedScenes.compactMap { $0 as? UIWindowScene }
        let window = scenes
            .flatMap(\.windows)
            .first(where: \.isKeyWindow)
            ?? scenes.flatMap(\.windows).first
        return window?.safeAreaInsets ?? UIEdgeInsets(top: 59, left: 0, bottom: 34, right: 0)
    }

    var body: some View {
        GeometryReader { geo in
            let size = geo.size
            // Prefer window insets — GeometryReader often reports 0 when parent already consumed safe area.
            let topInset = max(geo.safeAreaInsets.top, Self.keyWindowSafeArea.top, 47)
            let bottomInset = max(geo.safeAreaInsets.bottom, Self.keyWindowSafeArea.bottom, 8)

            ZStack {
                Color.black
                    .opacity(dimOpacity)
                    .ignoresSafeArea()

                pager(containerSize: size)
                    .offset(x: dragOffset.width * 0.4, y: dragOffset.height)
                    .scaleEffect(dragScale)

                if chromeVisible {
                    VStack(spacing: 0) {
                        topChrome(topInset: topInset, containerSize: size)
                        Spacer(minLength: 0)
                        bottomChrome(bottomInset: bottomInset, containerSize: size)
                    }
                    .opacity(chromeOpacity)
                    .allowsHitTesting(abs(dragOffset.height) < 1)
                    .transition(.opacity)
                }

                if let banner {
                    VStack {
                        Spacer()
                        Text(banner)
                            .font(.system(size: 14, weight: .semibold))
                            .foregroundStyle(Color.white)
                            .padding(.horizontal, 16)
                            .padding(.vertical, 10)
                            .background(circleFill)
                            .clipShape(Capsule())
                            .padding(.bottom, bottomInset + 96)
                    }
                    .transition(.opacity)
                    .allowsHitTesting(false)
                }
            }
        }
        .ignoresSafeArea()
        .preferredColorScheme(.dark)
        .accessibilityAddTraits(.isModal)
        .onChange(of: currentID) { _, id in
            onLoad?(id)
        }
    }

    // MARK: - Pager

    private func pager(containerSize: CGSize) -> some View {
        TabView(selection: $currentID) {
            ForEach(items) { item in
                page(item, containerSize: containerSize)
                    .tag(item.id)
            }
        }
        .tabViewStyle(.page(indexDisplayMode: .never))
    }

    private func page(_ item: Item, containerSize: CGSize) -> some View {
        Page(
            item: item,
            isCurrent: item.id == currentID,
            containerSize: containerSize,
            onLoad: onLoad,
            onToggleChrome: {
                withAnimation(.easeInOut(duration: 0.18)) {
                    chromeVisible.toggle()
                }
            },
            onZoomChange: { zoomed in
                // Zooming in is a "look closer" gesture — get the chrome out of the way.
                withAnimation(.easeInOut(duration: 0.18)) {
                    chromeVisible = !zoomed
                }
            },
            onDismissDragChange: { translation in
                dragOffset = translation
                dimOpacity = max(0.25, 1 - abs(translation.height) / 420)
            },
            onDismissDragEnd: endDismissDrag
        )
    }

    /// One photo in the pager, responsible for turning its own bytes into an image.
    ///
    /// Human: Decoding belongs here rather than in the item list — a thread with fifty photos
    /// would otherwise decode all fifty on the main thread the instant the viewer opened.
    private struct Page: View {
        let item: Item
        let isCurrent: Bool
        let containerSize: CGSize
        let onLoad: ((UUID) -> Void)?
        let onToggleChrome: () -> Void
        let onZoomChange: (Bool) -> Void
        let onDismissDragChange: (CGSize) -> Void
        let onDismissDragEnd: (CGSize, CGSize) -> Void

        @State private var decoded: UIImage?

        private var image: UIImage? { item.image ?? decoded }

        var body: some View {
            Group {
                if let image {
                    ZoomableImageView(
                        image: image,
                        isActive: isCurrent,
                        onSingleTap: onToggleChrome,
                        onZoomChange: onZoomChange,
                        onDismissDragChange: onDismissDragChange,
                        onDismissDragEnd: onDismissDragEnd
                    )
                } else {
                    placeholder
                }
            }
            .task(id: item.data?.count ?? 0) {
                guard item.image == nil else { return }
                guard let data = item.data else {
                    onLoad?(item.id)
                    return
                }
                let image = await Task.detached(priority: .userInitiated) {
                    UIImage(data: data)
                }.value
                guard let image else { return }
                DecodedImageCache.store(item.id, image: image)
                decoded = image
            }
        }

        /// Sized from the message metadata — including the edge-to-edge treatment a screen-shaped
        /// vertical will get — so the photo lands without the frame jumping.
        private var placeholder: some View {
            let scale = MediaViewerLayout.presentationScale(aspect: item.aspect, in: containerSize)
            let base = MediaViewerLayout.fittedSize(aspect: item.aspect, in: containerSize)
            let fitted = CGSize(width: base.width * scale, height: base.height * scale)
            return ZStack {
                Color.clear
                Rectangle()
                    .fill(Color.white.opacity(0.06))
                    .frame(width: fitted.width, height: fitted.height)
                ProgressView()
                    .tint(Color.white.opacity(0.9))
            }
            .frame(width: containerSize.width, height: containerSize.height)
            .contentShape(Rectangle())
            .onTapGesture(perform: onToggleChrome)
        }
    }


    /// True when the photo runs under the chrome — that's when it needs a scrim to stay legible.
    private func imageReachesChrome(_ item: Item?, containerSize: CGSize, inset: CGFloat) -> Bool {
        guard let item else { return false }
        // An edge-to-edge vertical always sits under both bars.
        if MediaViewerLayout.opensFullBleed(aspect: item.aspect, in: containerSize) { return true }
        let fitted = MediaViewerLayout.fittedSize(aspect: item.aspect, in: containerSize)
        let margin = (containerSize.height - fitted.height) / 2
        return margin < inset + 52
    }

    /// Solid black when the photo letterboxes clear of the chrome, a scrim when it runs underneath.
    ///
    /// The ramp is front-loaded rather than linear: a caption sits at the *top* of the bottom
    /// bar, so it needs real coverage well before the bar's own edge.
    @ViewBuilder
    private func chromeBackground(fading: Bool, fromTop: Bool) -> some View {
        if fading {
            LinearGradient(
                stops: fromTop
                    ? [
                        .init(color: .black.opacity(0.95), location: 0),
                        .init(color: .black.opacity(0.6), location: 0.55),
                        .init(color: .black.opacity(0), location: 1),
                    ]
                    : [
                        .init(color: .black.opacity(0), location: 0),
                        .init(color: .black.opacity(0.45), location: 0.3),
                        .init(color: .black.opacity(0.8), location: 0.6),
                        .init(color: .black.opacity(0.95), location: 1),
                    ],
                startPoint: .top,
                endPoint: .bottom
            )
        } else {
            Color.black
        }
    }

    // MARK: - Top chrome

    private func topChrome(topInset: CGFloat, containerSize: CGSize) -> some View {
        VStack(spacing: 0) {
            // Status bar / Dynamic Island band.
            Color.clear
                .frame(height: topInset)

            HStack(spacing: 12) {
                circleButton(systemName: "chevron.left", accessibility: "Close") {
                    dismissAnimated()
                }

                Spacer(minLength: 4)

                titlePill

                Spacer(minLength: 4)

                moreMenu
            }
            .padding(.horizontal, 16)
            .padding(.top, 4)
            .padding(.bottom, 12)
        }
        .frame(maxWidth: .infinity)
        .background {
            chromeBackground(
                fading: imageReachesChrome(currentItem, containerSize: containerSize, inset: topInset),
                fromTop: true
            )
        }
    }

    private var titlePill: some View {
        VStack(spacing: 1) {
            Text(currentItem?.title ?? "")
                .font(.system(size: 15, weight: .semibold))
                .foregroundStyle(Color.white)
                .lineLimit(1)
            Text(currentItem?.dateLine ?? "")
                .font(.system(size: 12))
                .foregroundStyle(Color.white.opacity(0.6))
                .lineLimit(1)
        }
        .padding(.horizontal, 18)
        .padding(.vertical, 6)
        .background(circleFill)
        .clipShape(Capsule())
        // The pill re-reads on every page, so cross-fade instead of snapping the name.
        .animation(.easeOut(duration: 0.18), value: currentID)
        .accessibilityElement(children: .combine)
    }

    private var moreMenu: some View {
        Menu {
            Button {
                saveCurrentToPhotos()
            } label: {
                Label("Save to Photos", systemImage: "square.and.arrow.down")
            }
            Button {
                shareCurrent()
            } label: {
                Label("Share", systemImage: "square.and.arrow.up")
            }
            Button {
                copyCurrent()
            } label: {
                Label("Copy", systemImage: "doc.on.doc")
            }
        } label: {
            Image(systemName: "ellipsis")
                .font(.system(size: 17, weight: .semibold))
                .foregroundStyle(Color.white)
                .frame(width: 40, height: 40)
                .background(circleFill)
                .clipShape(Circle())
        }
        .accessibilityLabel("More")
    }

    // MARK: - Bottom chrome

    private func bottomChrome(bottomInset: CGFloat, containerSize: CGSize) -> some View {
        VStack(spacing: 12) {
            if let caption = currentItem?.caption, !caption.isEmpty {
                ScrollView {
                    Text(caption)
                        .font(.system(size: 16))
                        .foregroundStyle(Color.white)
                        .frame(maxWidth: .infinity, alignment: .leading)
                }
                .frame(maxHeight: 96)
                .padding(.horizontal, 20)
                .padding(.top, 12)
            }

            HStack(spacing: 0) {
                actionCircle(systemName: "arrowshape.turn.up.right", label: "Share") {
                    shareCurrent()
                }

                Spacer(minLength: 0)

                // Telegram groups the two edit tools into one capsule (see image_viewer.PNG).
                HStack(spacing: 4) {
                    capsuleAction(systemName: "pencil.tip.crop.circle", label: "Draw") {
                        onComingSoon?("Drawing")
                    }
                    capsuleAction(systemName: "text.viewfinder", label: "Text recognition") {
                        onComingSoon?("Text recognition")
                    }
                }
                .background(circleFill)
                .clipShape(Capsule())

                Spacer(minLength: 0)

                actionCircle(systemName: "trash", label: "Delete") {
                    onComingSoon?("Delete")
                }
            }
            .padding(.horizontal, 28)
            .padding(.top, 10)
            .padding(.bottom, 8)

            Color.clear.frame(height: max(bottomInset, 8))
        }
        .frame(maxWidth: .infinity)
        .background {
            chromeBackground(
                fading: imageReachesChrome(currentItem, containerSize: containerSize, inset: bottomInset),
                fromTop: false
            )
        }
    }

    // MARK: - Controls

    private func circleButton(
        systemName: String,
        accessibility: String,
        action: @escaping () -> Void
    ) -> some View {
        Button(action: action) {
            Image(systemName: systemName)
                .font(.system(size: 17, weight: .semibold))
                .foregroundStyle(Color.white)
                .frame(width: 40, height: 40)
                .background(circleFill)
                .clipShape(Circle())
        }
        .pressable(scale: 0.85, dimming: 0)
        .accessibilityLabel(accessibility)
    }

    private func actionCircle(
        systemName: String,
        label: String,
        action: @escaping () -> Void
    ) -> some View {
        Button(action: action) {
            Image(systemName: systemName)
                .font(.system(size: 18, weight: .regular))
                .foregroundStyle(Color.white)
                .frame(width: 40, height: 40)
                .background(circleFill)
                .clipShape(Circle())
                // Keeps the 40 pt Telegram look with a 48 pt target.
                .padding(4)
                .contentShape(Rectangle())
        }
        .pressable(scale: 0.85, dimming: 0)
        .accessibilityLabel(label)
    }

    /// Sits inside the shared capsule, so it carries no background of its own.
    private func capsuleAction(
        systemName: String,
        label: String,
        action: @escaping () -> Void
    ) -> some View {
        Button(action: action) {
            Image(systemName: systemName)
                .font(.system(size: 18, weight: .regular))
                .foregroundStyle(Color.white)
                .frame(width: 40, height: 40)
                .contentShape(Rectangle())
        }
        .pressable(scale: 0.85, dimming: 0)
        .accessibilityLabel(label)
    }

    // MARK: - Dismiss

    private var dragScale: CGFloat {
        let t = min(1, abs(dragOffset.height) / 500)
        return 1 - t * 0.12
    }

    private var chromeOpacity: Double {
        max(0, 1 - abs(dragOffset.height) / 180)
    }

    private func endDismissDrag(translation: CGSize, velocity: CGSize) {
        let travelled = abs(translation.height)
        let flick = abs(velocity.height)
        if travelled > 110 || flick > 900 {
            dismissAnimated(direction: translation.height >= 0 ? 1 : -1)
        } else {
            withAnimation(.spring(response: 0.32, dampingFraction: 0.86)) {
                dragOffset = .zero
                dimOpacity = 1
            }
        }
    }

    private func dismissAnimated(direction: CGFloat = 1) {
        Haptics.impact(.light)
        withAnimation(.easeOut(duration: 0.2)) {
            dimOpacity = 0
            chromeVisible = false
            dragOffset.height = dragOffset.height == 0 ? 40 * direction : dragOffset.height * 1.6
        }
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.18) {
            onClose()
        }
    }

    // MARK: - Actions

    private func flashBanner(_ text: String) {
        withAnimation(.easeOut(duration: 0.15)) { banner = text }
        Task {
            try? await Task.sleep(nanoseconds: 1_600_000_000)
            await MainActor.run {
                withAnimation(.easeOut(duration: 0.2)) {
                    if banner == text { banner = nil }
                }
            }
        }
    }

    /// Writes the photo to a temp file so it shares as a real image file, in its original format.
    private func shareableURL(for item: Item) -> URL? {
        guard let data = item.data else { return nil }
        let mime = MediaCrypto.mimeType(for: data)
        let ext = UTType(mimeType: mime)?.preferredFilenameExtension ?? "jpg"
        let url = FileManager.default.temporaryDirectory
            .appendingPathComponent("shroud-photo-\(item.id.uuidString).\(ext)")
        do {
            try data.write(to: url, options: [.atomic, .completeFileProtectionUnlessOpen])
            return url
        } catch {
            return nil
        }
    }

    private func shareCurrent() {
        guard let item = currentItem else { return }
        let activityItem: Any
        let shared = shareableURL(for: item)
        if let shared {
            activityItem = shared
        } else if let image = item.image {
            activityItem = image
        } else {
            return
        }
        // The decrypted copy goes as soon as the sheet is done with it.
        presentActivity(with: [activityItem]) {
            if let shared { try? FileManager.default.removeItem(at: shared) }
        }
    }

    private func copyCurrent() {
        guard let image = currentItem?.image else { return }
        UIPasteboard.general.image = image
        Haptics.notification(.success)
        flashBanner("Copied")
    }

    private func saveCurrentToPhotos() {
        guard let item = currentItem, let data = item.data else { return }
        Task {
            let status = await PHPhotoLibrary.requestAuthorization(for: .addOnly)
            guard status == .authorized || status == .limited else {
                flashBanner("Allow photo access to save.")
                return
            }
            do {
                try await PHPhotoLibrary.shared().performChanges {
                    // Writing the original bytes keeps the sender's quality and metadata.
                    PHAssetCreationRequest.forAsset().addResource(with: .photo, data: data, options: nil)
                }
                Haptics.notification(.success)
                flashBanner("Saved to Photos")
            } catch {
                Haptics.notification(.error)
                flashBanner("Could not save that photo.")
            }
        }
    }

    private func presentActivity(with items: [Any], completion: (() -> Void)? = nil) {
        let activity = UIActivityViewController(activityItems: items, applicationActivities: nil)
        activity.completionWithItemsHandler = { _, _, _, _ in completion?() }
        guard let scene = UIApplication.shared.connectedScenes
            .compactMap({ $0 as? UIWindowScene })
            .first(where: { $0.activationState == .foregroundActive })
            ?? UIApplication.shared.connectedScenes.compactMap({ $0 as? UIWindowScene }).first,
            let root = scene.windows.first(where: \.isKeyWindow)?.rootViewController
        else { return }

        var presenter = root
        while let presented = presenter.presentedViewController {
            presenter = presented
        }
        if let pop = activity.popoverPresentationController {
            pop.sourceView = presenter.view
            pop.sourceRect = CGRect(
                x: presenter.view.bounds.midX,
                y: presenter.view.bounds.maxY - 80,
                width: 1,
                height: 1
            )
        }
        presenter.present(activity, animated: true)
    }
}

#Preview("Media viewer overlay") {
    let id = UUID()
    return ZStack {
        Theme.backgroundChat.ignoresSafeArea()
        MediaImageViewerOverlay(
            items: [
                .init(
                    id: id,
                    title: "You",
                    dateLine: "16.07.26",
                    caption: "Found this in the fridge aisle",
                    image: UIImage(systemName: "photo"),
                    data: nil,
                    aspect: 0.75
                ),
            ],
            initialID: id,
            onClose: {}
        )
    }
}
