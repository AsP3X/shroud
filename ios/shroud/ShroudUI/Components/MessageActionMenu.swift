import SwiftUI

/// Long-press focus stack — reaction bar + context menu from `Conversation — * Message Menu`.
/// Layout order is owned by the host: emoji bar → **message** → menu (Telegram).
struct MessageActionMenu: View {
    let isMine: Bool
    var onReaction: (String) -> Void
    var onAction: (MessageMenuAction) -> Void

    var body: some View {
        // Combined stack for previews; ConversationView places the bubble in between.
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
        .shadow(color: Color.black.opacity(0.35), radius: 20, y: 8)
    }
}

// MARK: - Context menu card (below the focused bubble)

struct MessageContextMenuCard: View {
    let isMine: Bool
    var onAction: (MessageMenuAction) -> Void

    var body: some View {
        VStack(spacing: 0) {
            // Read receipt row (outbound) — Telegram-style meta header.
            if isMine {
                menuRow(
                    title: "read",
                    systemImage: "checkmark",
                    destructive: false,
                    muted: true
                ) {
                    // Informational; no-op for now.
                }
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
        .shadow(color: Color.black.opacity(0.4), radius: 24, y: 10)
    }

    private var primaryActions: [MessageMenuAction] {
        if isMine {
            return [.reply, .copy, .pin, .forward, .delete]
        }
        return [.reply, .copy, .pin, .forward, .delete]
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

// MARK: - Heavy dark blur backdrop (Telegram long-press)

/// Strong blur + dark dim for message focus overlay.
struct MessageMenuBackdrop: View {
    var onTap: () -> Void

    var body: some View {
        ZStack {
            // Heavy material blur (dark).
            Rectangle()
                .fill(.ultraThickMaterial)
                .environment(\.colorScheme, .dark)

            // Extra darken so chat content is heavily obscured.
            Color.black.opacity(0.62)

            // Subtle purple ambient like Telegram wallpaper bleed.
            RadialGradient(
                colors: [
                    Color(red: 0.35, green: 0.2, blue: 0.75).opacity(0.35),
                    Color.clear,
                ],
                center: .center,
                startRadius: 40,
                endRadius: 320
            )
            .blendMode(.plusLighter)
            .opacity(0.5)
        }
        .ignoresSafeArea()
        .contentShape(Rectangle())
        .onTapGesture(perform: onTap)
    }
}

#Preview {
    ZStack {
        MessageMenuBackdrop(onTap: {})
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
