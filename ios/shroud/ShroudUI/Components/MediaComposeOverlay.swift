import SwiftUI
import UIKit

/// Telegram-style media send screen — maps to `Conversation — Media Compose` in `iOS-App.pen`.
///
/// Full-screen overlay (not a pushed route): photo preview, caption field, tools, blue send.
struct MediaComposeOverlay: View {
    let image: UIImage
    /// Recipient shown in the header (peer username).
    let peerUsername: String
    var onCancel: () -> Void
    var onSend: (_ caption: String) -> Void
    var onComingSoon: ((String) -> Void)?

    @State private var caption = ""
    @FocusState private var captionFocused: Bool

    private let chrome = Color(red: 44 / 255, green: 44 / 255, blue: 46 / 255)
    /// Telegram blue send (design token in pen: `#3390EC`).
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

                // Centered letterboxed photo (matches pen Media Stage).
                Image(uiImage: image)
                    .resizable()
                    .scaledToFit()
                    .frame(maxWidth: geo.size.width)
                    .frame(maxHeight: geo.size.height * 0.55)
                    .clipped()

                VStack(spacing: 0) {
                    topChrome(topInset: topInset)
                    Spacer(minLength: 0)
                    bottomChrome(bottomInset: bottomInset)
                }
            }
        }
        .ignoresSafeArea()
        .preferredColorScheme(.dark)
        .accessibilityAddTraits(.isModal)
        .onAppear {
            // Focus caption after present for Telegram-like flow.
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

                Spacer(minLength: 8)

                // Multi-select circle (Telegram)
                Circle()
                    .stroke(Color.white.opacity(0.6), lineWidth: 1.5)
                    .frame(width: 28, height: 28)
                    .accessibilityHidden(true)
            }
            .padding(.horizontal, 16)
            .padding(.bottom, 10)
        }
        .frame(maxWidth: .infinity)
        .background(Color.black)
    }

    // MARK: - Bottom

    private func bottomChrome(bottomInset: CGFloat) -> some View {
        VStack(spacing: 12) {
            captionField

            HStack(spacing: 0) {
                HStack(spacing: 8) {
                    toolCircle(systemName: "chevron.left", label: "Back") {
                        captionFocused = false
                        onCancel()
                    }
                    toolCircle(systemName: "crop", label: "Crop") {
                        onComingSoon?("Crop")
                    }
                    toolCircle(systemName: "textformat", label: "Text") {
                        onComingSoon?("Text")
                    }
                    toolCircle(systemName: "slider.horizontal.3", label: "Filters") {
                        onComingSoon?("Filters")
                    }
                    qualityBadge
                }

                Spacer(minLength: 8)

                // Same arrow.up as chat composer send, Telegram blue per design.
                Button {
                    captionFocused = false
                    Haptics.impact(.light)
                    onSend(caption)
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
            .padding(.horizontal, 2)

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

            // Media count badge (single selection for now).
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
            onComingSoon?("Quality")
        } label: {
            Text("SD")
                .font(.system(size: 12, weight: .bold))
                .foregroundStyle(Color.white)
                .frame(width: 44, height: 44)
                .background(chrome)
                .clipShape(Circle())
        }
        .buttonStyle(.plain)
        .accessibilityLabel("Quality SD")
    }

    private func toolCircle(systemName: String, label: String, action: @escaping () -> Void) -> some View {
        Button(action: action) {
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
}

#Preview("Media compose") {
    MediaComposeOverlay(
        image: UIImage(systemName: "photo")!,
        peerUsername: "Jane Cooper",
        onCancel: {},
        onSend: { _ in }
    )
}
