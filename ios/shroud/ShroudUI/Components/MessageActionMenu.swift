import SwiftUI
import UIKit

/// Long-press focus stack — reaction bar + context menu from `Conversation — * Message Menu`.
/// Layout order is owned by the host: emoji bar → **message** → menu (Telegram).
struct MessageActionMenu: View {
    let isMine: Bool
    var onReaction: (String) -> Void
    var onAction: (MessageMenuAction) -> Void

    var body: some View {
        VStack(spacing: 9) {
            MessageReactionBar(onReaction: onReaction, onMore: { onAction(.moreReactions) })
            MessageContextMenuCard(isMine: isMine, onAction: onAction)
        }
        .frame(width: 250)
    }
}

// MARK: - Reaction bar (above the focused bubble)

struct MessageReactionBar: View {
    var onReaction: (String) -> Void
    var onMore: () -> Void
    /// 0…1 continuous progress (drives opacity + offset; avoid Bool for smooth close).
    var progress: CGFloat = 1

    static let reactions = ["❤️", "🔥", "👍", "😢", "🙏", "😮", "👎"]
    private static let emojiSize: CGFloat = 34
    private static let moreSize: CGFloat = 30
    private static let itemSpacing: CGFloat = 6
    private static let horizontalPadding: CGFloat = 12
    private static let verticalPadding: CGFloat = 8

    /// Intrinsic capsule width (emojis + more + spacing + padding).
    static var barWidth: CGFloat {
        let emojiCount = CGFloat(reactions.count)
        let items = emojiCount + 1 // more button
        return emojiCount * emojiSize
            + moreSize
            + (items - 1) * itemSpacing
            + horizontalPadding * 2
    }

    /// Intrinsic capsule height.
    static var barHeight: CGFloat {
        max(emojiSize, moreSize) + verticalPadding * 2
    }

    var body: some View {
        HStack(spacing: Self.itemSpacing) {
            ForEach(Self.reactions, id: \.self) { emoji in
                Button {
                    onReaction(emoji)
                } label: {
                    Text(emoji)
                        .font(.system(size: 26))
                        .frame(width: Self.emojiSize, height: Self.emojiSize)
                        .contentShape(Rectangle())
                }
                // Emoji squash hard on press — the most playful control in the app.
                .pressable(scale: 0.78, dimming: 0)
            }
            Button(action: onMore) {
                Image(systemName: "chevron.down")
                    .font(.system(size: 12, weight: .bold))
                    .foregroundStyle(Color.white.opacity(0.85))
                    .frame(width: Self.moreSize, height: Self.moreSize)
                    .background(Color.white.opacity(0.12))
                    .clipShape(Circle())
            }
            .pressable(scale: 0.85, dimming: 0)
            .accessibilityLabel("More reactions")
        }
        .padding(.horizontal, Self.horizontalPadding)
        .padding(.vertical, Self.verticalPadding)
        .fixedSize(horizontal: true, vertical: true)
        .background {
            Capsule()
                .fill(Color(red: 0.14, green: 0.14, blue: 0.16).opacity(0.92))
        }
        .overlay {
            Capsule()
                .stroke(Color.white.opacity(0.08), lineWidth: 0.5)
        }
        // Fade with the hero flight (no extra slide — hero owns the travel).
        .opacity(progress)
        .allowsHitTesting(progress > 0.5)
    }
}

// MARK: - Context menu card (below the focused bubble)

struct MessageContextMenuCard: View {
    let isMine: Bool
    var onAction: (MessageMenuAction) -> Void
    /// 0…1 continuous progress (drives opacity + offset; avoid Bool for smooth close).
    var progress: CGFloat = 1

    var body: some View {
        VStack(spacing: 0) {
            if isMine {
                menuRow(
                    title: "read",
                    systemImage: "checkmark",
                    destructive: false,
                    muted: true,
                    action: {}
                )
                separator
            }

            ForEach(Array(primaryActions.enumerated()), id: \.element.id) { index, action in
                if index > 0 { separator }
                menuRow(
                    title: action.title,
                    systemImage: action.systemImage,
                    destructive: action.isDestructive,
                    muted: false
                ) {
                    onAction(action)
                }
            }

            separator
            menuRow(
                title: MessageMenuAction.select.title,
                systemImage: MessageMenuAction.select.systemImage,
                destructive: false,
                muted: false
            ) {
                onAction(.select)
            }
        }
        .frame(width: 250)
        .background {
            RoundedRectangle(cornerRadius: 14, style: .continuous)
                .fill(Color(red: 0.12, green: 0.12, blue: 0.14).opacity(0.94))
        }
        .overlay {
            RoundedRectangle(cornerRadius: 14, style: .continuous)
                .stroke(Color.white.opacity(0.08), lineWidth: 0.5)
        }
        .clipShape(RoundedRectangle(cornerRadius: 14, style: .continuous))
        // Fade with the hero flight (no extra slide — hero owns the travel).
        .opacity(progress)
        .allowsHitTesting(progress > 0.5)
    }

    private var primaryActions: [MessageMenuAction] {
        [.reply, .copy, .pin, .forward, .delete]
    }

    private var separator: some View {
        Rectangle()
            .fill(Color.white.opacity(0.08))
            .frame(height: 1)
    }

    private func menuRow(
        title: String,
        systemImage: String,
        destructive: Bool,
        muted: Bool,
        action: @escaping () -> Void
    ) -> some View {
        Button(action: action) {
            HStack(spacing: 12) {
                Image(systemName: systemImage)
                    .font(.system(size: 15, weight: .medium))
                    .foregroundStyle(
                        destructive
                            ? Theme.danger
                            : (muted ? Color.white.opacity(0.45) : Color.white.opacity(0.85))
                    )
                    .frame(width: 22)
                Text(title)
                    .font(.system(size: 16))
                    .foregroundStyle(
                        destructive
                            ? Theme.danger
                            : (muted ? Color.white.opacity(0.55) : Color.white)
                    )
                Spacer()
            }
            .padding(.horizontal, 14)
            .frame(height: 44)
            .contentShape(Rectangle())
        }
        // Dark menu — highlight with a light wash rather than the grouped-background token.
        .buttonStyle(HighlightRowButtonStyle(fill: Color.white.opacity(0.1)))
        .disabled(muted)
    }
}

enum MessageMenuAction: String, Identifiable {
    case reply, copy, edit, pin, forward, select, delete, moreReactions

    var id: String { rawValue }

    var title: String {
        switch self {
        case .reply: "Reply"
        case .copy: "Copy"
        case .edit: "Edit"
        case .pin: "Pin"
        case .forward: "Forward"
        case .select: "Select"
        case .delete: "Delete"
        case .moreReactions: "More"
        }
    }

    var systemImage: String {
        switch self {
        case .reply: "arrowshape.turn.up.left"
        case .copy: "doc.on.doc"
        case .edit: "pencil"
        case .pin: "pin"
        case .forward: "arrowshape.turn.up.right"
        case .select: "checkmark.circle"
        case .delete: "trash"
        case .moreReactions: "chevron.down"
        }
    }

    var isDestructive: Bool { self == .delete }
}

// MARK: - Backdrop

/// Dim veil driven by a single continuous progress.
/// Solid scrim only — Material opacity animation stutters on dismiss.
struct MessageMenuBackdrop: View {
    var onTap: () -> Void
    /// 0…1 continuous open amount.
    var progress: CGFloat = 1

    var body: some View {
        // Layered solid dim approximates the old frosted look without per-frame blur cost.
        ZStack {
            Color.black.opacity(0.42 * progress)
            Color(red: 0.06, green: 0.06, blue: 0.08).opacity(0.28 * progress)
        }
        .ignoresSafeArea()
        .contentShape(Rectangle())
        .onTapGesture(perform: onTap)
        .allowsHitTesting(progress > 0.05)
    }
}

// MARK: - Decoded image cache (avoid UIImage(data:) on long-press)

/// Populated when chat bubbles first decode; long-press reuses the same instance.
enum DecodedImageCache {
    nonisolated(unsafe) private static var storage: [UUID: UIImage] = [:]
    /// Byte count of the data that produced the cached image — so preview → full replaces correctly.
    nonisolated(unsafe) private static var dataCounts: [UUID: Int] = [:]

    static func store(_ id: UUID, image: UIImage) {
        storage[id] = image
    }

    static func image(for id: UUID) -> UIImage? {
        storage[id]
    }

    /// Drop decoded bitmaps for deleted messages so nothing stays in RAM.
    static func remove(ids: [UUID]) {
        for id in ids {
            storage.removeValue(forKey: id)
            dataCounts.removeValue(forKey: id)
        }
    }

    /// Cache hit for the same byte payload, else decode once and store.
    static func image(forMessage id: UUID, data: Data?) -> UIImage? {
        guard let data else { return nil }
        if let cached = storage[id], dataCounts[id] == data.count {
            return cached
        }
        guard let image = UIImage(data: data) else { return nil }
        storage[id] = image
        dataCounts[id] = data.count
        return image
    }
}

// MARK: - Hero (same bubble views as the list so close lands without a pop)

struct MessageMenuHeroContent: View {
    let message: MessagingController.ChatMessage
    let timeLabel: String
    /// Kept for API stability; image hero reads `DecodedImageCache` via `ImageMessageBubble`.
    let heroImage: UIImage?
    /// Mirrors the list bubble so a voice note lifts off with the same fold.
    var inTranscriptTail = false

    var body: some View {
        switch message.kind {
        case .image:
            ImageMessageBubble(
                message: message,
                time: timeLabel,
                isRowEmbedded: false
            )
        case .video:
            VideoMessageBubble(
                message: message,
                time: timeLabel,
                isRowEmbedded: false
            )
        case .voice:
            VoiceMessageBubble(
                message: message,
                time: timeLabel,
                inTranscriptTail: inTranscriptTail,
                revealsArrival: false
            )
        case .text:
            MessageBubbleView(
                text: message.text,
                time: timeLabel,
                isMine: message.isMine,
                isDeleted: message.deleted,
                receipt: message.receipt,
                isRowEmbedded: false
            )
        case .todo:
            TodoMessageBubble(
                text: message.text,
                time: timeLabel,
                isDone: message.todoDone == true,
                onToggle: {}
            )
        }
    }
}

#Preview {
    ZStack {
        MessageMenuBackdrop(onTap: {}, progress: 1)
        VStack(spacing: 10) {
            MessageReactionBar(onReaction: { _ in }, onMore: {}, progress: 1)
            MessageBubbleView(
                text: "Hey! How are you?",
                time: "14:22",
                isMine: true,
                receipt: .read
            )
            MessageContextMenuCard(isMine: true, onAction: { _ in }, progress: 1)
        }
        .padding(24)
    }
}
