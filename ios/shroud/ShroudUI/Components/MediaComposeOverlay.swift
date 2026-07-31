import SwiftUI
import UIKit

/// Send-quality for media compose.
///
/// - **original** (default): full pixel dimensions, JPEG quality 1.0 (true source quality).
/// - **hd**: smaller send — still sharp, but downscaled and more compressed.
enum MediaComposeQuality: String, CaseIterable, Sendable {
    /// 100% source quality — default when composing a photo.
    case original
    /// Lower-size option (still labeled HD in the badge).
    case hd

    var label: String {
        switch self {
        case .original: "Original"
        case .hd: "HD"
        }
    }

    /// JPEG max **pixel** edge + compression for `MediaCrypto.jpegData`.
    var encodeParams: (maxEdge: CGFloat, quality: CGFloat) {
        switch self {
        // Cap only at a pathologically large edge so normal phone photos are unscaled.
        case .original: (16_384, 1.0)
        case .hd: (2560, 0.85)
        }
    }
}

/// Telegram-style media send screen — maps to `Conversation — Media Compose` in `iOS-App.pen`.
///
/// Idle: tools + blue arrow-up send.  
/// Caption focused: morphs chrome around a **stable** `TextField` (options + emoji + white check).
///
/// Important: the caption `TextField` must stay in the hierarchy when focus changes — swapping
/// entire bars with `matchedGeometryEffect` causes freezes/crashes.
struct MediaComposeOverlay: View {
    let image: UIImage
    let peerUsername: String
    var onCancel: () -> Void
    var onSend: (_ caption: String, _ quality: MediaComposeQuality) -> Void
    var onComingSoon: ((String) -> Void)?

    @State private var caption = ""
    @State private var quality: MediaComposeQuality = .original
    @State private var multiSelectHint = false
    @State private var toolBanner: String?
    @State private var keyboardHeight: CGFloat = 0
    @FocusState private var captionFocused: Bool

    private let chrome = Color(red: 44 / 255, green: 44 / 255, blue: 46 / 255)
    private let telegramBlue = Color(red: 51 / 255, green: 144 / 255, blue: 236 / 255)

    /// UI morph driven only by focus (not keyboard height) to avoid layout thrash mid-animation.
    private var isFocused: Bool { captionFocused }

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

    private func bottomChromePadding(homeInset: CGFloat) -> CGFloat {
        if keyboardHeight > 0 { return keyboardHeight }
        return max(homeInset, 8)
    }

    var body: some View {
        GeometryReader { geo in
            let topInset = max(geo.safeAreaInsets.top, windowTopInset, 47)
            let homeInset = max(geo.safeAreaInsets.bottom, windowBottomInset, 8)

            ZStack {
                Color.black.ignoresSafeArea()
                    .contentShape(Rectangle())
                    .onTapGesture { dismissCaptionKeyboard() }

                VStack(spacing: 0) {
                    topChrome(topInset: topInset)
                        .opacity(isFocused ? 0.35 : 1)

                    editChipRow
                        .opacity(isFocused ? 0 : 1)
                        .frame(height: isFocused ? 0 : nil)
                        .clipped()
                        .allowsHitTesting(!isFocused)

                    Image(uiImage: image)
                        .resizable()
                        .scaledToFit()
                        .frame(maxWidth: .infinity, maxHeight: .infinity)
                        .clipped()
                        .contentShape(Rectangle())
                        .onTapGesture { dismissCaptionKeyboard() }

                    bottomChrome(bottomPadding: bottomChromePadding(homeInset: homeInset))
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
                            .padding(.bottom, 220 + (keyboardHeight > 0 ? keyboardHeight * 0.25 : 0))
                    }
                    .transition(.opacity)
                    .allowsHitTesting(false)
                }
            }
        }
        .ignoresSafeArea()
        .preferredColorScheme(.dark)
        .accessibilityAddTraits(.isModal)
        .onReceive(NotificationCenter.default.publisher(for: UIResponder.keyboardWillChangeFrameNotification)) { note in
            updateKeyboardHeight(from: note)
        }
        .onReceive(NotificationCenter.default.publisher(for: UIResponder.keyboardWillHideNotification)) { note in
            let duration = (note.userInfo?[UIResponder.keyboardAnimationDurationUserInfoKey] as? Double) ?? 0.25
            withAnimation(.easeOut(duration: duration)) {
                keyboardHeight = 0
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

                Button {
                    dismissCaptionKeyboard()
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
                .pressable(scale: 0.88, dimming: 0)
                .accessibilityLabel("Select multiple")
            }
            .padding(.horizontal, 16)
            .padding(.bottom, 10)
        }
        .frame(maxWidth: .infinity)
        .background(Color.black)
    }

    private var editChipRow: some View {
        HStack {
            Button {
                dismissCaptionKeyboard()
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
            .pressable(scale: 0.9)
            .accessibilityLabel("Edit")

            Spacer(minLength: 0)
        }
        .padding(.horizontal, 16)
        .padding(.top, 8)
        .padding(.bottom, 8)
    }

    // MARK: - Bottom (single TextField; chrome morphs around it)

    private func bottomChrome(bottomPadding: CGFloat) -> some View {
        VStack(spacing: 12) {
            // One row: leading control | stable caption field | trailing control
            HStack(spacing: 10) {
                leadingControl
                    .animation(.easeOut(duration: 0.2), value: isFocused)

                captionField
                    .frame(maxWidth: .infinity)

                trailingControl
                    .animation(.easeOut(duration: 0.2), value: isFocused)
            }

            // Idle-only tool strip (hidden when focused, not removed from identity of TextField).
            if !isFocused {
                idleToolsRow
                    .transition(.opacity)
            }

            Color.clear.frame(height: bottomPadding)
        }
        .padding(.horizontal, 12)
        .padding(.top, 10)
        .frame(maxWidth: .infinity)
        .background(Color.black)
        .animation(.easeOut(duration: 0.22), value: isFocused)
        .animation(.easeOut(duration: 0.25), value: keyboardHeight)
    }

    @ViewBuilder
    private var leadingControl: some View {
        if isFocused {
            // Caption options (captions_focused.PNG left circle)
            Button {
                Haptics.impact(.light)
                flashToolBanner("Caption options coming soon")
                onComingSoon?("Caption options")
            } label: {
                Image(systemName: "list.bullet")
                    .font(.system(size: 17, weight: .semibold))
                    .foregroundStyle(Color.white)
                    .frame(width: 44, height: 44)
                    .background(chrome)
                    .clipShape(Circle())
            }
            .pressable(scale: 0.85, dimming: 0)
            .accessibilityLabel("Caption options")
            .transition(.scale.combined(with: .opacity))
        } else {
            // Idle: no leading control beside field (tools are below).
            Color.clear.frame(width: 0, height: 44)
        }
    }

    @ViewBuilder
    private var trailingControl: some View {
        if isFocused {
            // White check = Done (dismiss keyboard)
            Button {
                Haptics.impact(.light)
                dismissCaptionKeyboard()
            } label: {
                Image(systemName: "checkmark")
                    .font(.system(size: 17, weight: .bold))
                    .foregroundStyle(Color.black)
                    .frame(width: 44, height: 44)
                    .background(Color.white)
                    .clipShape(Circle())
            }
            .pressable(scale: 0.85, dimming: 0)
            .accessibilityLabel("Done")
            .transition(.scale.combined(with: .opacity))
        } else {
            // Idle: blue send (same arrow.up as chat composer)
            Button {
                dismissCaptionKeyboard()
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
            .pressable(scale: 0.85, dimming: 0, haptic: .medium)
            .accessibilityLabel("Send")
            .transition(.scale.combined(with: .opacity))
        }
    }

    /// Stable caption field — never destroyed on focus change.
    private var captionField: some View {
        HStack(spacing: 10) {
            TextField("Add a caption...", text: $caption, axis: .vertical)
                .font(.system(size: 16))
                .foregroundStyle(Color.white)
                .lineLimit(1 ... 4)
                .focused($captionFocused)
                .tint(telegramBlue)

            if isFocused {
                Button {
                    Haptics.impact(.light)
                    flashToolBanner("Emoji keyboard: use the globe key")
                } label: {
                    Image(systemName: "face.smiling")
                        .font(.system(size: 20, weight: .regular))
                        .foregroundStyle(Color.white.opacity(0.85))
                        .frame(width: 28, height: 28)
                }
                .pressable(scale: 0.85, haptic: nil)
                .accessibilityLabel("Emoji")
                .transition(.opacity)
            } else {
                Text("1")
                    .font(.system(size: 12, weight: .semibold))
                    .foregroundStyle(Color.white)
                    .frame(width: 22, height: 22)
                    .overlay {
                        Circle()
                            .stroke(Color.white.opacity(0.4), lineWidth: 1.5)
                    }
                    .accessibilityLabel("1 photo")
                    .transition(.opacity)
            }
        }
        .padding(.horizontal, 14)
        .padding(.vertical, 10)
        .frame(minHeight: 44)
        .background(chrome)
        .clipShape(Capsule())
    }

    private var idleToolsRow: some View {
        HStack(spacing: 0) {
            ScrollView(.horizontal, showsIndicators: false) {
                HStack(spacing: 8) {
                    toolCircle(systemName: "chevron.left", label: "Back") {
                        dismissCaptionKeyboard()
                        onCancel()
                    }
                    toolCircle(systemName: "crop", label: "Crop") {
                        dismissCaptionKeyboard()
                        flashToolBanner("Crop coming soon")
                        onComingSoon?("Crop")
                    }
                    toolCircle(systemName: "textformat", label: "Text") {
                        dismissCaptionKeyboard()
                        flashToolBanner("Text stickers coming soon")
                        onComingSoon?("Text")
                    }
                    toolCircle(systemName: "slider.horizontal.3", label: "Filters") {
                        dismissCaptionKeyboard()
                        flashToolBanner("Filters coming soon")
                        onComingSoon?("Filters")
                    }
                    qualityBadge
                }
            }
            Spacer(minLength: 0)
        }
    }

    private var qualityBadge: some View {
        Button {
            dismissCaptionKeyboard()
            Haptics.impact(.light)
            withAnimation(.easeInOut(duration: 0.15)) {
                // Default is Original (100%); tap toggles down to HD.
                quality = quality == .original ? .hd : .original
            }
            flashToolBanner(
                quality == .original ? "Original quality (100%)" : "HD quality (smaller file)"
            )
        } label: {
            Text(quality.label)
                .font(.system(size: 11, weight: .bold))
                .minimumScaleFactor(0.7)
                .lineLimit(1)
                .foregroundStyle(quality == .original ? telegramBlue : Color.white)
                .frame(width: 44, height: 44)
                .background(chrome)
                .clipShape(Circle())
                .overlay {
                    Circle()
                        .stroke(
                            quality == .original ? telegramBlue.opacity(0.85) : Color.clear,
                            lineWidth: 1.5
                        )
                }
        }
        .pressable(scale: 0.9)
        .accessibilityLabel("Quality \(quality.label)")
        .accessibilityHint("Tap to switch between Original and HD")
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
        .pressable(scale: 0.85, dimming: 0)
        .accessibilityLabel(label)
    }

    private func dismissCaptionKeyboard() {
        captionFocused = false
    }

    private func updateKeyboardHeight(from notification: Notification) {
        guard
            let frame = notification.userInfo?[UIResponder.keyboardFrameEndUserInfoKey] as? CGRect
        else { return }

        let duration = (notification.userInfo?[UIResponder.keyboardAnimationDurationUserInfoKey] as? Double)
            ?? 0.25
        // Prefer the active window scene's screen (UIScreen.main is deprecated in iOS 26).
        let screenHeight = UIApplication.shared.connectedScenes
            .compactMap { $0 as? UIWindowScene }
            .first(where: { $0.activationState == .foregroundActive })?
            .screen.bounds.height
            ?? frame.maxY
        let overlap = max(0, screenHeight - frame.origin.y)

        // Avoid animating layout while the TextField is mid-focus if height is unchanged.
        guard abs(overlap - keyboardHeight) > 0.5 else { return }

        withAnimation(.easeOut(duration: duration)) {
            keyboardHeight = overlap
        }
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
