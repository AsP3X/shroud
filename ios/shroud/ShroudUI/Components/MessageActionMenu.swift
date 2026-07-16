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
    var isVisible: Bool = true

    private let reactions = ["❤️", "🔥", "👍", "😢", "🙏", "😮", "👎"]

    var body: some View {
        HStack(spacing: 6) {
            ForEach(reactions, id: \.self) { emoji in
                Button {
                    onReaction(emoji)
                } label: {
                    Text(emoji)
                        .font(.system(size: 26))
                        .frame(width: 34, height: 34)
                }
                .buttonStyle(.plain)
            }
            Button(action: onMore) {
                Image(systemName: "chevron.down")
                    .font(.system(size: 12, weight: .bold))
                    .foregroundStyle(Color.white.opacity(0.85))
                    .frame(width: 30, height: 30)
                    .background(Color.white.opacity(0.12))
                    .clipShape(Circle())
            }
            .buttonStyle(.plain)
            .accessibilityLabel("More reactions")
        }
        .padding(.horizontal, 12)
        .padding(.vertical, 8)
        .background {
            Capsule()
                .fill(Color(red: 0.14, green: 0.14, blue: 0.16).opacity(0.92))
        }
        .overlay {
            Capsule()
                .stroke(Color.white.opacity(0.08), lineWidth: 0.5)
        }
        // Opacity + offset only (no scale — cheaper and snappier).
        .opacity(isVisible ? 1 : 0)
        .offset(y: isVisible ? 0 : 8)
        .allowsHitTesting(isVisible)
    }
}

// MARK: - Context menu card (below the focused bubble)

struct MessageContextMenuCard: View {
    let isMine: Bool
    var onAction: (MessageMenuAction) -> Void
    var isVisible: Bool = true

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
        // Opacity + offset only (no scale — cheaper and snappier).
        .opacity(isVisible ? 1 : 0)
        .offset(y: isVisible ? 0 : 8)
        .allowsHitTesting(isVisible)
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
        .buttonStyle(.plain)
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

/// Frosted veil. Material is **snapped** on/off (never opacity-animated — that lags).
/// Only the solid dim eases for open/close feel.
struct MessageMenuBackdrop: View {
    var onTap: () -> Void
    /// 0…1 — animates dim only.
    var dimProgress: Double = 1
    /// When false, Material is removed immediately (used on dismiss to avoid blur tear-down lag).
    var showsBlur: Bool = true

    var body: some View {
        ZStack {
            if showsBlur {
                Rectangle()
                    .fill(.regularMaterial)
                    .environment(\.colorScheme, .dark)
                    .opacity(0.85)
                    .transition(.identity)
            }

            // Cheap solid fade for open/close.
            Color.black.opacity(0.22 * dimProgress)
        }
        .ignoresSafeArea()
        .contentShape(Rectangle())
        .onTapGesture(perform: onTap)
        .allowsHitTesting(dimProgress > 0.05 || showsBlur)
    }
}

// MARK: - Decoded image cache (avoid UIImage(data:) on long-press)

/// Populated when chat bubbles first decode; long-press reuses the same instance.
enum DecodedImageCache {
    nonisolated(unsafe) private static var storage: [UUID: UIImage] = [:]

    static func store(_ id: UUID, image: UIImage) {
        storage[id] = image
    }

    static func image(for id: UUID) -> UIImage? {
        storage[id]
    }

    /// Cache hit, else decode once and store.
    static func image(forMessage id: UUID, data: Data?) -> UIImage? {
        if let cached = storage[id] { return cached }
        guard let data, let image = UIImage(data: data) else { return nil }
        storage[id] = image
        return image
    }
}

// MARK: - Lightweight hero (no ImageMessageBubble tree)

struct MessageMenuHeroContent: View {
    let message: MessagingController.ChatMessage
    let timeLabel: String
    let heroImage: UIImage?

    private let maxHeroHeight: CGFloat = 180
    private let maxHeroWidth: CGFloat = 220

    var body: some View {
        switch message.kind {
        case .image:
            imageHero
        case .text:
            MessageBubbleView(
                text: message.text,
                time: timeLabel,
                isMine: message.isMine,
                isDeleted: message.deleted,
                receipt: message.receipt
            )
        }
    }

    @ViewBuilder
    private var imageHero: some View {
        let size = fittedSize
        ZStack(alignment: .bottomTrailing) {
            if let heroImage {
                Image(uiImage: heroImage)
                    .resizable()
                    .interpolation(.medium)
                    .scaledToFill()
                    .frame(width: size.width, height: size.height)
                    .clipped()
            } else {
                RoundedRectangle(cornerRadius: 16, style: .continuous)
                    .fill(Color.white.opacity(0.08))
                    .frame(width: size.width, height: size.height)
            }

            Text(timeLabel)
                .font(.system(size: 11, weight: .semibold))
                .foregroundStyle(.white)
                .padding(.horizontal, 7)
                .padding(.vertical, 3)
                .background(Color.black.opacity(0.45), in: Capsule())
                .padding(8)
        }
        .frame(width: size.width, height: size.height)
        .clipShape(RoundedRectangle(cornerRadius: 16, style: .continuous))
    }

    private var fittedSize: CGSize {
        let w = CGFloat(message.imageWidth ?? 240)
        let h = CGFloat(message.imageHeight ?? 240)
        guard w > 0, h > 0 else {
            return CGSize(width: 180, height: 160)
        }
        let scale = min(maxHeroWidth / w, maxHeroHeight / h, 1)
        return CGSize(width: max(120, w * scale), height: max(100, h * scale))
    }
}

#Preview {
    ZStack {
        MessageMenuBackdrop(onTap: {}, dimProgress: 1, showsBlur: true)
        VStack(spacing: 10) {
            MessageReactionBar(onReaction: { _ in }, onMore: {})
            MessageBubbleView(
                text: "Hey! How are you?",
                time: "14:22",
                isMine: true,
                receipt: .read
            )
            MessageContextMenuCard(isMine: true, onAction: { _ in })
        }
        .padding(24)
    }
}
