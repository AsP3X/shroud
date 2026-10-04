import SwiftUI

/// Full-screen "Update required": the server no longer serves this build.
///
/// Human: Covers everything but a running logout wipe (Welcome, the lock screen, the chats) and
/// waits for a call to end first. `RootView` drops the keyboard and dismisses sheets, covers and
/// dialogs under it for as long as it is up. The lock screen's hero recipe (mark on an accent-soft disc,
/// a bubble-coloured badge) with a download arrow in the badge; copy and buttons follow the
/// lock screen's "Set a device passcode" state. "Check again" asks the server once more and the
/// screen goes away when it no longer says required.
/// Agent: Pure view; `RootView` wires `onUpdate` (openURL) and `onCheckAgain` (ClientVersionController).
struct UpdateRequiredView: View {
    let latestVersion: String?
    let updateURL: URL?
    let currentVersion: String
    let isChecking: Bool
    /// "Check again" feedback; shown above the buttons, not over them.
    @Binding var toast: Toast?
    let onUpdate: () -> Void
    let onCheckAgain: () -> Void

    // Design sizes at the default text size, scaled with Dynamic Type.
    @ScaledMetric(relativeTo: .title) private var titleSize: CGFloat = 30
    @ScaledMetric(relativeTo: .body) private var bodySize: CGFloat = 15
    @ScaledMetric(relativeTo: .body) private var bodyWidth: CGFloat = 300
    @ScaledMetric(relativeTo: .caption) private var footnoteSize: CGFloat = 11

    var body: some View {
        // Scrolls only when it doesn't fit (large text, an SE in landscape); the buttons stay
        // pinned at the bottom.
        GeometryReader { proxy in
            ScrollView {
                VStack(spacing: 28) {
                    hero
                    copy
                }
                .padding(.horizontal, 24)
                .padding(.vertical, 24)
                .frame(maxWidth: 520)
                .frame(maxWidth: .infinity, minHeight: proxy.size.height)
            }
            .scrollBounceBehavior(.basedOnSize)
        }
        // Before the inset: the toast lands in the space above the pinned buttons.
        .toast($toast)
        .safeAreaInset(edge: .bottom, spacing: 0) {
            actions
                .padding(.horizontal, 24)
                .padding(.top, 8)
                .padding(.bottom, 12)
                .frame(maxWidth: 520)
                .frame(maxWidth: .infinity)
                // Copy scrolled under the pinned buttons doesn't show through.
                .background(Theme.background.ignoresSafeArea(edges: .bottom))
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .background(Theme.background.ignoresSafeArea())
        .accessibilityElement(children: .contain)
        .accessibilityAddTraits(.isModal)
    }

    // MARK: - Hero

    private var hero: some View {
        ZStack {
            Circle()
                .fill(Theme.accentSoft)
                .frame(width: 144, height: 144)
            BrandLogoMark(size: 88)
                .shadow(color: Theme.accent.opacity(0.3), radius: 16, y: 14)
            ZStack {
                Circle()
                    // Bubble surface, not the page background: stays a visible disc in dark mode.
                    .fill(Theme.bubbleIncoming)
                    .frame(width: 40, height: 40)
                    .shadow(color: Color.black.opacity(0.16), radius: 8, y: 6)
                Image(systemName: "arrow.down")
                    .font(.system(size: 16, weight: .bold))
                    .foregroundStyle(Theme.accent)
            }
            .offset(x: 41, y: 42)
        }
        .frame(height: 160)
        .accessibilityHidden(true)
    }

    // MARK: - Copy

    private var copy: some View {
        VStack(spacing: 10) {
            Text("Update required")
                .font(.system(size: titleSize, weight: .bold))
                .tracking(-0.6)
                .foregroundStyle(Theme.textPrimary)
                .multilineTextAlignment(.center)
                .accessibilityAddTraits(.isHeader)
            Text(ClientVersionPolicy.requiredMessage(latest: latestVersion, hasUpdateURL: updateURL != nil))
                .font(.system(size: bodySize))
                .foregroundStyle(Theme.textSecondary)
                .multilineTextAlignment(.center)
                .lineSpacing(4)
                .frame(maxWidth: bodyWidth)
                .fixedSize(horizontal: false, vertical: true)
        }
    }

    // MARK: - Actions

    private var actions: some View {
        VStack(spacing: 12) {
            if updateURL != nil {
                PrimaryButton(title: "Update", showsArrow: false, action: onUpdate)
                    .accessibilityIdentifier("updateRequired.update")
                SecondaryButton(title: isChecking ? "Checking…" : "Check again", action: onCheckAgain)
                    .disabled(isChecking)
                    .accessibilityIdentifier("updateRequired.checkAgain")
            } else {
                // Nothing to open: checking again is the one thing left to do here.
                PrimaryButton(
                    title: isChecking ? "Checking…" : "Check again",
                    showsArrow: false,
                    isLoading: isChecking,
                    action: onCheckAgain
                )
                .accessibilityIdentifier("updateRequired.checkAgain")
            }

            HStack(spacing: 6) {
                Image(systemName: "info.circle.fill")
                    .font(.system(size: footnoteSize - 1, weight: .semibold))
                Text("You have Shroud \(currentVersion)")
                    .font(.system(size: footnoteSize))
            }
            .foregroundStyle(Theme.textSecondary.opacity(0.75))
            .padding(.top, 4)
        }
    }
}

#Preview("With link") {
    UpdateRequiredView(
        latestVersion: "1.4",
        updateURL: URL(string: "https://testflight.apple.com/join/example"),
        currentVersion: "1.0",
        isChecking: false,
        toast: .constant(nil),
        onUpdate: {},
        onCheckAgain: {}
    )
}

#Preview("No link") {
    UpdateRequiredView(
        latestVersion: nil,
        updateURL: nil,
        currentVersion: "1.0",
        isChecking: false,
        toast: .constant(nil),
        onUpdate: {},
        onCheckAgain: {}
    )
}
