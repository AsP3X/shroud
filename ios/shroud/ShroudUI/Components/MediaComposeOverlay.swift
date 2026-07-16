import SwiftUI
import UIKit

/// Send-quality for media compose (maps to design **SD** badge / Telegram quality).
enum MediaComposeQuality: String, CaseIterable, Sendable {
    case sd
    case hd

    var label: String {
        switch self {
        case .sd: "SD"
        case .hd: "HD"
        }
    }

    /// JPEG max edge + compression for `MediaCrypto.jpegData`.
    var encodeParams: (maxEdge: CGFloat, quality: CGFloat) {
        switch self {
        case .sd: (1280, 0.72)
        case .hd: (2560, 0.88)
        }
    }
}

/// Telegram-style media send screen — maps to `Conversation — Media Compose` in `iOS-App.pen`.
///
/// Full-screen overlay (not a pushed route): photo preview, caption field, tools, blue send.
struct MediaComposeOverlay: View {
    let image: UIImage
    /// Recipient shown in the header (peer username).
    let peerUsername: String
    var onCancel: () -> Void
    /// Caption + encode quality chosen in the compose UI.
    var onSend: (_ caption: String, _ quality: MediaComposeQuality) -> Void
    var onComingSoon: ((String) -> Void)?

    @State private var caption = ""
    @State private var quality: MediaComposeQuality = .sd
    @State private var multiSelectHint = false
    @State private var toolBanner: String?
    @FocusState private var captionFocused: Bool

    private let chrome = Color(red: 44 / 255, green: 44 / 255, blue: 46 / 255)
    /// Telegram blue send (design: `#3390EC`).
    private let telegramBlue = Color(red: 51 / 255, green: 144 / 255, blue: 236 / 255)

    private var windowTopInset: CGFloat {
        Self.keyWindowSafeArea.top
    }

    private var windowBottomInset: CGFloat {
        Self.keyWindowSafeArea.bottom
    }

    private static var keyWindowSafeArea: UIEdgeInsets {
        let scenes = UIApplication.shared.connectedScenes.compactMap { $0 as? UIWindowScene }
        let window = scenes.flatMap(\.windows).first(where: \.isKeyWindow)
            ?? scenes.flatMap(\.windows).first
        return window?.safeAreaInsets ?? UIEdgeInsets(top: 59, left: 0, bottom: 34, right: 0)
    }

    var body: some View {
        GeometryReader { geo in
            let topInset = max(geo.safeAreaInsets.top, windowTopInset, 47)
            let bottomInset = max(geo.safeAreaInsets.bottom, windowBottomInset, 8)

            ZStack {
                Color.black.ignoresSafeArea()

                // Centered letterboxed photo (pen Media Stage ~420pt tall).
                Image(uiImage: image)
                    .resizable()
                    .scaledToFit()
                    .frame(maxWidth: geo.size.width)
                    .frame(maxHeight: min(420, geo.size.height * 0.52))
                    .clipped()

                VStack(spacing: 0) {
                    topChrome(topInset: topInset)
                    // EDIT chip over the letterbox (design Tool Chip at y≈132).
                    HStack {
                        editChip
                        Spacer(minLength: 0)
                    }
                    .padding(.horizontal, 16)
                    .padding(.top, 8)

                    Spacer(minLength: 0)
                    bottomChrome(bottomInset: bottomInset)
                }

                if let toolBanner {
                    VStack {
                        Spacer()
                        Text(toolBanner)
                            .font(.system(size: 14, weight: .semibold))
                            .foregroundStyle(Color.white)
                            .padding(.horizontal, 16)
                            .padding(.vertical, 10)
                            .background(chrome.opacity(0.95))
                            .clipShape(Capsule())
                            .padding(.bottom, 220)
                    }
                    .transition(.opacity.combined(with: .move(edge: .bottom)))
                    .allowsHitTesting(false)
                }
            }
        }
        .ignoresSafeArea()
        .preferredColorScheme(.dark)
        .accessibilityAddTraits(.isModal)
        .onAppear {
            DispatchQueue.main.asyncAfter(deadline: .now() + 0.35) {
                captionFocused = true
            }
        }
    }

    // MARK: - Top

    private func topChrome(topInset: CGFloat) -> some View {
        VStack(spacing: 0) {
            Color.clear.frame(height: topInset)

            HStack(spacing: 8) {
                HStack(spacing: 6) {
                    Image(systemName: "arrow.up")
                        .font(.system(size: 14, weight: .semibold))
                        .foregroundStyle(Color.white)
                    Text(peerUsername)
                        .font(.system(size: 16, weight: .semibold))
                        .foregroundStyle(Color.white)
                        .lineLimit(1)
                }
                .accessibilityElement(children: .combine)
                .accessibilityLabel("Sending to \(peerUsername)")

                Spacer(minLength: 8)

                // Multi-select (Telegram) — visual stub for now.
                Button {
                    multiSelectHint.toggle()
                    Haptics.impact(.light)
                    flashToolBanner("Multi-select coming soon")
                    onComingSoon?("Multi-select")
                } label: {
                    Circle()
                        .stroke(Color.white.opacity(multiSelectHint ? 1 : 0.6), lineWidth: 1.5)
                        .frame(width: 28, height: 28)
                        .overlay {
                            if multiSelectHint {
                                Image(systemName: "checkmark")
                                    .font(.system(size: 12, weight: .bold))
                                    .foregroundStyle(Color.white)
                            }
                        }
                }
                .buttonStyle(.plain)
                .accessibilityLabel("Select multiple")
            }
            .padding(.horizontal, 16)
            .padding(.bottom, 10)
        }
        .frame(maxWidth: .infinity)
        .background(Color.black)
    }

    private var editChip: some View {
        Button {
            Haptics.impact(.light)
            flashToolBanner("Edit tools coming soon")
            onComingSoon?("Edit")
        } label: {
            HStack(spacing: 4) {
                Image(systemName: "pencil.slash")
                    .font(.system(size: 11, weight: .semibold))
                Text("EDIT")
                    .font(.system(size: 11, weight: .semibold))
                Image(systemName: "chevron.down")
                    .font(.system(size: 10, weight: .semibold))
            }
            .foregroundStyle(Color.white.opacity(0.85))
            .padding(.horizontal, 10)
            .padding(.vertical, 6)
            .background(chrome.opacity(0.9))
            .clipShape(Capsule())
        }
        .buttonStyle(.plain)
        .accessibilityLabel("Edit")
    }

    // MARK: - Bottom

    private func bottomChrome(bottomInset: CGFloat) -> some View {
        VStack(spacing: 12) {
            captionField

            HStack(spacing: 0) {
                ScrollView(.horizontal, showsIndicators: false) {
                    HStack(spacing: 8) {
                        toolCircle(systemName: "chevron.left", label: "Back") {
                            captionFocused = false
                            onCancel()
                        }
                        toolCircle(systemName: "crop", label: "Crop") {
                            flashToolBanner("Crop coming soon")
                            onComingSoon?("Crop")
                        }
                        toolCircle(systemName: "textformat", label: "Text") {
                            flashToolBanner("Text stickers coming soon")
                            onComingSoon?("Text")
                        }
                        toolCircle(systemName: "slider.horizontal.3", label: "Filters") {
                            flashToolBanner("Filters coming soon")
                            onComingSoon?("Filters")
                        }
                        qualityBadge
                    }
                }

                Spacer(minLength: 10)

                // Same arrow.up as chat composer; Telegram blue per design.
                Button {
                    captionFocused = false
                    Haptics.impact(.medium)
                    onSend(caption, quality)
                } label: {
                    Image(systemName: "arrow.up")
                        .font(.system(size: 18, weight: .bold))
                        .foregroundStyle(Color.white)
                        .frame(width: 50, height: 50)
                        .background(telegramBlue)
                        .clipShape(Circle())
                }
                .buttonStyle(.plain)
                .accessibilityLabel("Send")
            }

            Color.clear.frame(height: max(bottomInset, 8))
        }
        .padding(.horizontal, 14)
        .padding(.top, 12)
        .frame(maxWidth: .infinity)
        .background(Color.black)
    }

    private var captionField: some View {
        HStack(spacing: 10) {
            TextField("Add a caption...", text: $caption, axis: .vertical)
                .font(.system(size: 16))
                .foregroundStyle(Color.white)
                .lineLimit(1 ... 4)
                .focused($captionFocused)
                .tint(telegramBlue)

            Text("1")
                .font(.system(size: 12, weight: .semibold))
                .foregroundStyle(Color.white)
                .frame(width: 22, height: 22)
                .overlay {
                    Circle()
                        .stroke(Color.white.opacity(0.4), lineWidth: 1.5)
                }
                .accessibilityLabel("1 photo")
        }
        .padding(.horizontal, 14)
        .padding(.vertical, 10)
        .frame(minHeight: 44)
        .background(chrome)
        .clipShape(Capsule())
    }

    private var qualityBadge: some View {
        Button {
            Haptics.impact(.light)
            withAnimation(.easeInOut(duration: 0.15)) {
                quality = quality == .sd ? .hd : .sd
            }
            flashToolBanner(quality == .hd ? "High quality" : "Standard quality")
        } label: {
            Text(quality.label)
                .font(.system(size: 12, weight: .bold))
                .foregroundStyle(quality == .hd ? telegramBlue : Color.white)
                .frame(width: 44, height: 44)
                .background(chrome)
                .clipShape(Circle())
                .overlay {
                    Circle()
                        .stroke(quality == .hd ? telegramBlue.opacity(0.8) : Color.clear, lineWidth: 1.5)
                }
        }
        .buttonStyle(.plain)
        .accessibilityLabel("Quality \(quality.label)")
        .accessibilityHint("Double tap to toggle SD and HD")
    }

    private func toolCircle(systemName: String, label: String, action: @escaping () -> Void) -> some View {
        Button {
            Haptics.impact(.light)
            action()
        } label: {
            Image(systemName: systemName)
                .font(.system(size: 17, weight: .medium))
                .foregroundStyle(Color.white)
                .frame(width: 44, height: 44)
                .background(chrome)
                .clipShape(Circle())
        }
        .buttonStyle(.plain)
        .accessibilityLabel(label)
    }

    private func flashToolBanner(_ text: String) {
        withAnimation(.easeOut(duration: 0.15)) {
            toolBanner = text
        }
        Task {
            try? await Task.sleep(nanoseconds: 1_600_000_000)
            await MainActor.run {
                withAnimation(.easeOut(duration: 0.2)) {
                    if toolBanner == text { toolBanner = nil }
                }
            }
        }
    }
}

#Preview("Media compose") {
    MediaComposeOverlay(
        image: UIImage(systemName: "photo")!,
        peerUsername: "Jane Cooper",
        onCancel: {},
        onSend: { _, _ in }
    )
}
