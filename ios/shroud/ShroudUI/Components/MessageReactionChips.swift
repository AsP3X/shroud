import SwiftUI

/// What one reaction chip shows: one person's emoji and their face — or both faces, when the two
/// picked exactly the same emoji.
struct ReactionChipContent: Equatable, Hashable, Identifiable {
    struct Reactor: Equatable, Hashable {
        let id: UUID
        /// Display name; drawn as initials.
        let name: String
        /// Read as "you" by VoiceOver.
        var isMe = false
    }

    /// Oldest first.
    let emojis: [String]
    /// In the order they reacted.
    let reactors: [Reactor]
    let includesMe: Bool
    /// The message the chip belongs to, so a reaction flying in can find it. Nil where no
    /// flight should land (the long-press preview).
    var messageID: UUID?

    var id: String { reactors.map(\.id.uuidString).joined(separator: "+") }

    var spokenNames: String {
        ListFormatter.localizedString(byJoining: reactors.map { $0.isMe ? "you" : $0.name })
    }
}

extension [ReactionChipContent] {
    /// "Reactions: anna ❤️ 🔥, you 👍" — for a bubble VoiceOver reads as one element.
    var spokenSummary: String? {
        guard !isEmpty else { return nil }
        let parts = map { chip in "\(chip.spokenNames) \(chip.emojis.joined(separator: " "))" }
        return "Reactions: " + parts.joined(separator: ", ")
    }

    /// Every emoji on the bubble, once, in chip order; and which of them are ours.
    var emojiActions: [(emoji: String, isMine: Bool)] {
        let mine = Set(filter(\.includesMe).flatMap(\.emojis))
        var seen = Set<String>()
        return flatMap(\.emojis).compactMap { emoji in
            seen.insert(emoji).inserted ? (emoji, mine.contains(emoji)) : nil
        }
    }
}

extension View {
    /// A bubble that VoiceOver reads as one element hides its chip buttons; each chip comes
    /// back as a named action on the bubble, and so does the double-tap quick reaction.
    func reactionAccessibilityActions(
        _ chips: [ReactionChipContent],
        onTap: ((String) -> Void)?
    ) -> some View {
        accessibilityActions {
            if let onTap {
                ForEach(chips.emojiActions, id: \.emoji) { action in
                    Button(action.isMine ? "Remove your \(action.emoji) reaction" : "React with \(action.emoji)") {
                        onTap(action.emoji)
                    }
                }
                if !chips.contains(where: { $0.emojis.contains(MessageReactionBar.quickReaction) }) {
                    Button("React with \(MessageReactionBar.quickReaction)") {
                        onTap(MessageReactionBar.quickReaction)
                    }
                }
            }
        }
    }
}

/// A reaction chip inside a bubble: one person's emoji, then their face (Telegram 1:1 shows
/// faces, not counts). Filled when it's ours. Each emoji is its own tap target, as each chip is
/// in Telegram: ours are taken back, theirs are added to ours.
struct ReactionChipView: View {
    let chip: ReactionChipContent
    /// The chip sits on our own (accent) bubble.
    let onOutgoingBubble: Bool
    var onTap: ((String) -> Void)?

    // Telegram's in-bubble reaction button (ReactionButtonListComponent): 30 pt tall, a 20 pt
    // emoji, 24 pt faces overlapping by half.
    static let height: CGFloat = 30
    /// Point size of the emoji glyph; it fills a 20 pt box like Telegram's animated one.
    static let emojiFontSize: CGFloat = 17
    private static let emojiBox: CGFloat = 20
    private static let avatarSize: CGFloat = 24
    private static let avatarStep: CGFloat = 12
    /// Telegram stacks at most three faces; more reactors than that is a group chat.
    private static let maxAvatars = 3

    @Environment(\.reactionFlightTarget) private var flightTarget

    /// A reaction of ours is flying in to this emoji of our chip: it waits, and says where it is.
    private func isFlightTarget(_ emoji: String) -> Bool {
        guard chip.includesMe, let flightTarget, let messageID = chip.messageID else { return false }
        return flightTarget.messageID == messageID && flightTarget.emoji == emoji
    }

    private var fill: Color {
        switch (onOutgoingBubble, chip.includesMe) {
        case (true, true): Color.white
        case (true, false): Color.white.opacity(0.2)
        case (false, true): Theme.accent
        case (false, false): Theme.accent.opacity(0.14)
        }
    }

    var body: some View {
        HStack(spacing: 4) {
            HStack(spacing: 2) {
                ForEach(chip.emojis, id: \.self) { emoji in
                    emojiButton(emoji)
                        .transition(.scale(scale: 0.4).combined(with: .opacity))
                }
            }
            HStack(spacing: Self.avatarStep - Self.avatarSize) {
                    ForEach(chip.reactors.prefix(Self.maxAvatars), id: \.id) { reactor in
                        AvatarView(
                            initials: AvatarView.initials(for: reactor.name),
                            size: Self.avatarSize,
                            gradient: AvatarView.gradient(for: reactor.name),
                            fontSize: 9
                        )
                        .overlay { Circle().stroke(fill, lineWidth: 1.5) }
                        .accessibilityHidden(true)
                }
            }
        }
        .padding(.leading, 6)
        .padding(.trailing, chip.reactors.isEmpty ? 6 : 3)
        .frame(height: Self.height)
        .background(Capsule().fill(fill))
    }

    private func emojiButton(_ emoji: String) -> some View {
        Button {
            MessageTapClaim.claim()
            onTap?(emoji)
        } label: {
            Text(emoji)
                .font(.system(size: Self.emojiFontSize))
                .fixedSize()
                .frame(width: Self.emojiBox, height: Self.emojiBox)
                .opacity(isFlightTarget(emoji) ? 0 : 1)
                .background {
                    if isFlightTarget(emoji) {
                        GeometryReader { geo in
                            Color.clear.preference(
                                key: ReactionFlightFrameKey.self,
                                value: geo.frame(in: .global)
                            )
                        }
                    }
                }
                // The whole height of the chip, and a little either side, takes the tap.
                .padding(.horizontal, 2)
                .frame(height: Self.height)
                .contentShape(Rectangle())
        }
        .buttonStyle(ReactionChipButtonStyle())
        .accessibilityLabel("\(emoji), \(chip.spokenNames)")
        .accessibilityHint(chip.includesMe ? "Removes your reaction" : "Reacts with the same emoji")
    }
}

/// The chip's press: it squashes, and it claims the touch as the finger lands.
///
/// Human: The row's own taps (open a photo or video, the double-tap reaction) see a chip's touch
/// too. A claim made in the button's action comes too late — the row reads the release first —
/// so it is made on touch-down, well inside `MessageTapClaim`'s window when the release comes.
private struct ReactionChipButtonStyle: ButtonStyle {
    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .scaleEffect(configuration.isPressed ? 0.88 : 1)
            .animation(configuration.isPressed ? Motion.press : Motion.release, value: configuration.isPressed)
            .onChange(of: configuration.isPressed) { _, pressed in
                if pressed { MessageTapClaim.claim() }
            }
    }
}

/// Chips and the time at the foot of a reacted bubble.
///
/// Human: Telegram's layout: chips flow left to right and wrap; the time (and ticks) sit at the
/// trailing end of the last chip row when there is room, else on a line of their own.
/// Agent: Subviews are the chips followed by exactly one meta view (last). Given a width it takes
/// all of it — wrapping at it, with the time at its trailing edge — so the bubble decides the
/// width (`LinkBubbleRole.footer` measures it unproposed: one line, the hugging width).
struct ReactionFooterLayout: Layout {
    /// Telegram's gap between reaction buttons, both ways.
    var spacing: CGFloat = 6
    var rowSpacing: CGFloat = 6
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
        spacing: CGFloat = 6,
        rowSpacing: CGFloat = 6,
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
        let size = arrangement(width: width, subviews: subviews).size
        // Fill a proposed width: the time belongs at the bubble's trailing edge, not after the
        // last chip.
        return CGSize(width: max(size.width, width ?? 0), height: size.height)
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
    /// False inside a bubble VoiceOver reads as one element: there the chips come back as the
    /// bubble's actions (`reactionAccessibilityActions`), and a hidden button can't be what a
    /// double tap on the bubble activates.
    var chipsAccessible = true
    @ViewBuilder var meta: Meta

    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    var body: some View {
        ReactionFooterLayout {
            ForEach(chips) { chip in
                ReactionChipView(chip: chip, onOutgoingBubble: onOutgoingBubble, onTap: onTap)
                .accessibilityHidden(!chipsAccessible)
                .transition(reduceMotion ? .opacity : .scale(scale: 0.4).combined(with: .opacity))
            }
            meta
        }
        .animation(Motion.respecting(reduceMotion, Motion.bouncy), value: chips)
    }
}
