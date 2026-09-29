import SwiftUI

/// Placeholder that mirrors `ChatRowView`'s geometry while the first page loads.
///
/// Human: Showing the *shape* of the answer instead of the word "Loading…" is what makes
/// a network-backed list feel instant — the layout never jumps when real rows arrive.
struct SkeletonChatRow: View {
    /// Varies the title width per row so the stack doesn't read as a barcode.
    var titleWidth: CGFloat = 120
    var subtitleWidth: CGFloat = 200

    var body: some View {
        HStack(alignment: .center, spacing: 12) {
            Circle()
                .fill(Theme.backgroundGrouped)
                .frame(width: 52, height: 52)

            VStack(alignment: .leading, spacing: 8) {
                bar(width: titleWidth, height: 13)
                bar(width: subtitleWidth, height: 11)
            }

            Spacer(minLength: 8)

            bar(width: 34, height: 10)
        }
        .padding(.horizontal, 16)
        // Same 72 pt as `ChatRowView` (52 pt avatar + 10 pt above and below).
        .padding(.vertical, 10)
        .accessibilityHidden(true)
    }

    private func bar(width: CGFloat, height: CGFloat) -> some View {
        RoundedRectangle(cornerRadius: height / 2, style: .continuous)
            .fill(Theme.backgroundGrouped)
            .frame(width: width, height: height)
    }
}

/// Stack of shimmering chat-row placeholders.
struct SkeletonChatList: View {
    var count: Int = 7

    /// Deterministic pseudo-random widths — stable across redraws so nothing twitches.
    private static let widths: [(CGFloat, CGFloat)] = [
        (132, 214), (96, 168), (148, 190), (110, 232),
        (124, 152), (88, 205), (140, 176),
    ]

    var body: some View {
        VStack(spacing: 0) {
            ForEach(0 ..< count, id: \.self) { index in
                let size = Self.widths[index % Self.widths.count]
                SkeletonChatRow(titleWidth: size.0, subtitleWidth: size.1)
            }
        }
        // The rows stay hidden; VoiceOver hears one "Loading" instead of an empty screen.
        .accessibilityElement(children: .ignore)
        .accessibilityLabel("Loading")
        .shimmering()
        .transition(.opacity)
    }
}

#Preview {
    SkeletonChatList()
        .background(Theme.background)
}
