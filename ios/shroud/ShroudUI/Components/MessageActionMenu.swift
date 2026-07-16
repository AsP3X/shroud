import SwiftUI

/// Long-press focus stack — reaction bar + context menu from `Conversation — * Message Menu`.
struct MessageActionMenu: View {
    let isMine: Bool
    var onReaction: (String) -> Void
    var onAction: (MessageMenuAction) -> Void

    private let reactions = ["👍", "❤️", "🔥", "😂", "😮", "🙏"]

    private var actions: [MessageMenuAction] {
        if isMine {
            return [.reply, .copy, .edit, .pin, .forward, .delete]
        }
        return [.reply, .copy, .pin, .forward, .select, .delete]
    }

    var body: some View {
        VStack(spacing: 9) {
            reactionBar
            menuCard
        }
        .frame(width: 250)
    }

    private var reactionBar: some View {
        HStack(spacing: 7) {
            ForEach(reactions, id: \.self) { emoji in
                Button {
                    onReaction(emoji)
                } label: {
                    Text(emoji)
                        .font(.system(size: 22))
                        .frame(width: 27, height: 27)
                }
                .buttonStyle(.plain)
            }
            Button {
                onAction(.moreReactions)
            } label: {
                Image(systemName: "plus")
                    .font(.system(size: 14, weight: .semibold))
                    .foregroundStyle(Theme.textSecondary)
                    .frame(width: 28, height: 28)
                    .background(Theme.backgroundGrouped)
                    .clipShape(Circle())
            }
            .buttonStyle(.plain)
        }
        .padding(.horizontal, 11)
        .padding(.vertical, 7)
        .background(Theme.background)
        .clipShape(Capsule())
        .shadow(color: Color.black.opacity(0.18), radius: 16, y: 6)
    }

    private var menuCard: some View {
        VStack(spacing: 0) {
            ForEach(Array(actions.enumerated()), id: \.element.id) { index, action in
                if index > 0 {
                    Rectangle()
                        .fill(Theme.separator)
                        .frame(height: 1)
                }
                Button {
                    onAction(action)
                } label: {
                    HStack {
                        Text(action.title)
                            .font(.system(size: 16))
                            .foregroundStyle(action.isDestructive ? Theme.danger : Theme.textPrimary)
                        Spacer()
                        Image(systemName: action.systemImage)
                            .font(.system(size: 15, weight: .medium))
                            .foregroundStyle(action.isDestructive ? Theme.danger : Theme.textSecondary)
                    }
                    .padding(.horizontal, 14)
                    .frame(height: 42)
                    .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
            }
        }
        .background(Theme.background)
        .clipShape(RoundedRectangle(cornerRadius: 14, style: .continuous))
        .shadow(color: Color.black.opacity(0.18), radius: 16, y: 6)
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
        case .moreReactions: "plus"
        }
    }

    var isDestructive: Bool { self == .delete }
}

#Preview {
    ZStack {
        Color.black.opacity(0.28).ignoresSafeArea()
        MessageActionMenu(isMine: true, onReaction: { _ in }, onAction: { _ in })
    }
}
