import SwiftUI

/// What one reaction chip shows: the emoji and who picked it.
struct ReactionChipContent: Equatable, Hashable, Identifiable {
    struct Reactor: Equatable, Hashable {
        let id: UUID
        /// Display name; drawn as initials.
        let name: String
        /// Read as "you" by VoiceOver.
        var isMe = false
    }

    let emoji: String
    /// Oldest first.
    let reactors: [Reactor]
    let includesMe: Bool

    var id: String { emoji }
}

/// A reaction chip inside a bubble (Telegram 1:1: the emoji plus the reactors' avatars instead
/// of a count). Filled when the reaction is ours.
struct ReactionChipView: View {
    let chip: ReactionChipContent
    /// The chip sits on our own (accent) bubble.
    let onOutgoingBubble: Bool
    var onTap: (() -> Void)?

    static let height: CGFloat = 26
    private static let avatarSize: CGFloat = 18
    /// Telegram stacks at most three faces; more reactors than that is a group chat.
    private static let maxAvatars = 3

    private var fill: Color {
        switch (onOutgoingBubble, chip.includesMe) {
        case (true, true): Color.white
        case (true, false): Color.white.opacity(0.2)
        case (false, true): Theme.accent
        case (false, false): Theme.accent.opacity(0.14)
        }
    }

    var body: some View {
        Button {
            // The row's own taps (double-tap reaction) see this touch too.
            MessageTapClaim.claim()
            onTap?()
        } label: {
            HStack(spacing: 4) {
                Text(chip.emoji)
                    .font(.system(size: 15))
                    .fixedSize()
                HStack(spacing: -6) {
                    ForEach(chip.reactors.prefix(Self.maxAvatars), id: \.id) { reactor in
                        AvatarView(
                            initials: AvatarView.initials(for: reactor.name),
                            size: Self.avatarSize,
                            gradient: AvatarView.gradient(for: reactor.name),
                            fontSize: 8
                        )
                        .overlay { Circle().stroke(fill, lineWidth: 1.5) }
                    }
                }
            }
            .padding(.leading, 7)
            .padding(.trailing, chip.reactors.isEmpty ? 7 : 4)
            .frame(height: Self.height)
            .background(Capsule().fill(fill))
            .contentShape(Capsule())
        }
        .buttonStyle(.plain)
        .pressable(scale: 0.88, dimming: 0)
        .accessibilityLabel(accessibilityLabel)
        .accessibilityHint(chip.includesMe ? "Removes your reaction" : "Reacts with the same emoji")
    }

    private var accessibilityLabel: String {
        let names = chip.reactors.map { $0.isMe ? "you" : $0.name }
        return "\(chip.emoji), \(ListFormatter.localizedString(byJoining: names))"
    }
}

/// Chips and the time at the foot of a reacted bubble.
///
/// Human: Telegram's layout: chips flow left to right and wrap; the time (and ticks) sit at the
/// trailing end of the last chip row when there is room, else on a line of their own.
/// Agent: Subviews are the chips followed by exactly one meta view (last). The bubble's own
/// width decides wrapping; measured without a width it lays everything on one line.
struct ReactionFooterLayout: Layout {
    var spacing: CGFloat = 4
    var rowSpacing: CGFloat = 4
    /// Clear space between the last chip and the time.
    var metaGap: CGFloat = 8

    struct Arrangement: Equatable {
        /// Chip frames, then the meta frame (its x assumes the hugged width; `place` pins it to
        /// the trailing edge instead).
        var frames: [CGRect]
        var size: CGSize
    }

    /// Pure geometry, shared by `sizeThatFits` and `placeSubviews` and unit-tested.
    static func arrange(
        chips: [CGSize],
        meta: CGSize,
        width: CGFloat?,
        spacing: CGFloat = 4,
        rowSpacing: CGFloat = 4,
        metaGap: CGFloat = 8
    ) -> Arrangement {
        let limit = width ?? .infinity
        var frames: [CGRect] = []
        var x: CGFloat = 0
        var y: CGFloat = 0
        var rowHeight: CGFloat = 0
        var used: CGFloat = 0
        for chip in chips {
            if x > 0, x + chip.width > limit {
                y += rowHeight + rowSpacing
                x = 0
                rowHeight = 0
            }
            frames.append(CGRect(origin: CGPoint(x: x, y: y), size: chip))
            used = max(used, x + chip.width)
            x += chip.width + spacing
            rowHeight = max(rowHeight, chip.height)
        }

        let rowEnd = chips.isEmpty ? 0 : x - spacing
        let metaX = chips.isEmpty ? 0 : rowEnd + metaGap
        if chips.isEmpty || metaX + meta.width <= limit {
            // Centred on the last chip row, a touch low like a plain bubble's time.
            let rowBottom = y + max(rowHeight, meta.height)
            let metaY = min(rowBottom - meta.height, y + (max(rowHeight, meta.height) - meta.height) / 2 + 2)
            frames.append(CGRect(origin: CGPoint(x: metaX, y: metaY), size: meta))
            used = max(used, metaX + meta.width)
            return Arrangement(frames: frames, size: CGSize(width: used, height: rowBottom))
        }
        let metaY = y + rowHeight + rowSpacing
        frames.append(CGRect(origin: CGPoint(x: 0, y: metaY), size: meta))
        used = max(used, meta.width)
        return Arrangement(frames: frames, size: CGSize(width: used, height: metaY + meta.height))
    }

    private func arrangement(width: CGFloat?, subviews: Subviews) -> Arrangement {
        guard let metaView = subviews.last else { return Arrangement(frames: [], size: .zero) }
        let chips = subviews.dropLast().map { $0.sizeThatFits(.unspecified) }
        return Self.arrange(
            chips: Array(chips),
            meta: metaView.sizeThatFits(.unspecified),
            width: width,
            spacing: spacing,
            rowSpacing: rowSpacing,
            metaGap: metaGap
        )
    }

    func sizeThatFits(proposal: ProposedViewSize, subviews: Subviews, cache: inout Void) -> CGSize {
        let width = proposal.width.flatMap { $0.isFinite ? $0 : nil }
        return arrangement(width: width, subviews: subviews).size
    }

    func placeSubviews(in bounds: CGRect, proposal: ProposedViewSize, subviews: Subviews, cache: inout Void) {
        let result = arrangement(width: bounds.width, subviews: subviews)
        for (index, subview) in subviews.enumerated() {
            let frame = result.frames[index]
            let isMeta = index == subviews.count - 1
            let x = isMeta ? bounds.maxX - frame.width : bounds.minX + frame.minX
            subview.place(
                at: CGPoint(x: x, y: bounds.minY + frame.minY),
                proposal: ProposedViewSize(frame.size)
            )
        }
    }
}

/// The foot of a reacted bubble: chips, then the bubble's own time + ticks.
struct ReactionFooter<Meta: View>: View {
    let chips: [ReactionChipContent]
    let onOutgoingBubble: Bool
    var onTap: ((String) -> Void)?
    @ViewBuilder var meta: Meta

    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    var body: some View {
        ReactionFooterLayout {
            ForEach(chips) { chip in
                ReactionChipView(chip: chip, onOutgoingBubble: onOutgoingBubble) {
                    onTap?(chip.emoji)
                }
                .transition(reduceMotion ? .opacity : .scale(scale: 0.4).combined(with: .opacity))
            }
            meta
        }
        .animation(Motion.respecting(reduceMotion, Motion.bouncy), value: chips)
    }
}
