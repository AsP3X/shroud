import SwiftUI
import UIKit

/// Telegram-style fullscreen **overlay** for chat photos (not a pushed screen).
///
/// - Letterboxes typical photos on black (`scaledToFit`)
/// - Full-bleeds tall/screenshot-like images (`scaledToFill`) with chrome floating over them
/// - Drag down (or back) to dismiss
struct MediaImageViewerOverlay: View {
    let image: UIImage
    /// Pill title — `"You"` for outbound, peer username for inbound.
    let title: String
    /// Secondary line under title (e.g. `26.06.26`).
    let dateLine: String
    var onClose: () -> Void
    var onShare: (() -> Void)?
    var onComingSoon: ((String) -> Void)?

    @State private var dragOffset: CGFloat = 0
    @State private var dimOpacity: Double = 1
    @State private var chromeVisible = true
    @State private var suppressChromeToggle = false

    private let circleFill = Color(red: 44 / 255, green: 44 / 255, blue: 46 / 255).opacity(0.92)

    /// Window insets — reliable when this overlay sits inside a view that already ate the safe area.
    private var windowTopInset: CGFloat {
        Self.keyWindowSafeArea.top
    }

    private var windowBottomInset: CGFloat {
        Self.keyWindowSafeArea.bottom
    }

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
            let topInset = max(geo.safeAreaInsets.top, windowTopInset, 47)
            let bottomInset = max(geo.safeAreaInsets.bottom, windowBottomInset, 8)

            ZStack {
                Color.black
                    .opacity(dimOpacity)
                    .ignoresSafeArea()

                imageLayer(containerSize: size)
                    .offset(y: dragOffset)
                    .scaleEffect(dragScale)
                    .simultaneousGesture(dismissDrag)
                    .onTapGesture {
                        guard !suppressChromeToggle else { return }
                        withAnimation(.easeInOut(duration: 0.18)) {
                            chromeVisible.toggle()
                        }
                    }

                if chromeVisible {
                    VStack(spacing: 0) {
                        topChrome(topInset: topInset)
                        Spacer(minLength: 0)
                        bottomChrome(bottomInset: bottomInset)
                    }
                    .opacity(chromeOpacity)
                    .allowsHitTesting(abs(dragOffset) < 1)
                }
            }
        }
        .ignoresSafeArea()
        .preferredColorScheme(.dark)
        .accessibilityAddTraits(.isModal)
    }

    // MARK: - Image

    /// Screenshots / tall portraits fill the screen; other photos letterbox.
    private var prefersFullBleed: Bool {
        let w = image.size.width
        let h = image.size.height
        guard w > 0, h > 0 else { return false }
        let aspect = w / h
        return aspect < 0.72
    }

    @ViewBuilder
    private func imageLayer(containerSize: CGSize) -> some View {
        Image(uiImage: image)
            .resizable()
            .aspectRatio(contentMode: prefersFullBleed ? .fill : .fit)
            .frame(width: containerSize.width, height: containerSize.height)
            .clipped()
    }

    // MARK: - Top chrome

    /// Full Telegram header: status-bar band + back / title pill / more.
    private func topChrome(topInset: CGFloat) -> some View {
        VStack(spacing: 0) {
            // Status bar / Dynamic Island band (solid black in letterbox mode).
            Color.clear
                .frame(height: topInset)

            HStack(spacing: 12) {
                circleButton(systemName: "chevron.left", accessibility: "Close") {
                    dismissAnimated()
                }

                Spacer(minLength: 4)

                titlePill

                Spacer(minLength: 4)

                circleButton(systemName: "ellipsis", accessibility: "More") {
                    onComingSoon?("More")
                }
            }
            .padding(.horizontal, 16)
            .padding(.top, 4)
            .padding(.bottom, 12)
        }
        .frame(maxWidth: .infinity)
        .background {
            if prefersFullBleed {
                LinearGradient(
                    colors: [
                        Color.black.opacity(0.92),
                        Color.black.opacity(0.55),
                        Color.black.opacity(0),
                    ],
                    startPoint: .top,
                    endPoint: .bottom
                )
            } else {
                Color.black
            }
        }
    }

    private var titlePill: some View {
        VStack(spacing: 1) {
            Text(title)
                .font(.system(size: 15, weight: .semibold))
                .foregroundStyle(Color.white)
                .lineLimit(1)
            Text(dateLine)
                .font(.system(size: 12))
                .foregroundStyle(Color.white.opacity(0.6))
                .lineLimit(1)
        }
        .padding(.horizontal, 18)
        .padding(.vertical, 6)
        .background(circleFill)
        .clipShape(Capsule())
    }

    // MARK: - Bottom chrome

    private func bottomChrome(bottomInset: CGFloat) -> some View {
        VStack(spacing: 0) {
            HStack {
                actionCircle(systemName: "square.and.arrow.up", label: "Share") {
                    if let onShare {
                        onShare()
                    } else {
                        shareImage()
                    }
                }

                Spacer(minLength: 0)

                HStack(spacing: 16) {
                    actionCircle(systemName: "textformat", label: "Text") {
                        onComingSoon?("Text recognition")
                    }
                    actionCircle(systemName: "rectangle.on.rectangle", label: "All media") {
                        onComingSoon?("All media")
                    }
                }

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
            if prefersFullBleed {
                LinearGradient(
                    colors: [
                        Color.black.opacity(0),
                        Color.black.opacity(0.5),
                        Color.black.opacity(0.92),
                    ],
                    startPoint: .top,
                    endPoint: .bottom
                )
            } else {
                Color.black
            }
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
                .font(.system(size: 18, weight: .medium))
                .foregroundStyle(Color.white)
                .frame(width: 50, height: 50)
                .background(circleFill)
                .clipShape(Circle())
        }
        .pressable(scale: 0.85, dimming: 0)
        .accessibilityLabel(label)
    }

    // MARK: - Dismiss drag

    private var dragScale: CGFloat {
        let t = min(1, abs(dragOffset) / 500)
        return 1 - t * 0.08
    }

    private var chromeOpacity: Double {
        max(0, 1 - abs(dragOffset) / 180)
    }

    private var dismissDrag: some Gesture {
        DragGesture(minimumDistance: 16, coordinateSpace: .local)
            .onChanged { value in
                suppressChromeToggle = true
                let dy = value.translation.height
                dragOffset = dy
                dimOpacity = max(0.25, 1 - abs(dy) / 420)
            }
            .onEnded { value in
                let dy = value.translation.height
                let predicted = value.predictedEndTranslation.height
                if abs(dy) > 100 || abs(predicted) > 220 {
                    dismissAnimated()
                } else {
                    withAnimation(.spring(response: 0.32, dampingFraction: 0.86)) {
                        dragOffset = 0
                        dimOpacity = 1
                    }
                }
                DispatchQueue.main.asyncAfter(deadline: .now() + 0.05) {
                    suppressChromeToggle = false
                }
            }
    }

    private func dismissAnimated() {
        Haptics.impact(.light)
        withAnimation(.easeOut(duration: 0.2)) {
            dimOpacity = 0
            dragOffset = dragOffset == 0 ? 40 : dragOffset * 1.15
        }
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.18) {
            onClose()
        }
    }

    private func shareImage() {
        let activity = UIActivityViewController(activityItems: [image], applicationActivities: nil)
        guard let scene = UIApplication.shared.connectedScenes.first as? UIWindowScene,
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
    ZStack {
        Theme.backgroundChat.ignoresSafeArea()
        MediaImageViewerOverlay(
            image: UIImage(systemName: "photo")!,
            title: "You",
            dateLine: "16.07.26",
            onClose: {}
        )
    }
}
