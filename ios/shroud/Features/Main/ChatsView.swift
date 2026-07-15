import SwiftUI

/// Chats list — maps to `Chats` in `iOS-App.pen`.
/// Sticky large title collapses into a compact bar title while scrolling.
struct ChatsView: View {
    @State private var searchText = ""
    @State private var isEditing = false

    private var filteredChats: [ChatListItem] {
        let query = searchText.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !query.isEmpty else { return MainSampleData.chats }
        return MainSampleData.chats.filter {
            $0.title.localizedCaseInsensitiveContains(query)
                || $0.preview.localizedCaseInsensitiveContains(query)
        }
    }

    var body: some View {
        MainScrollScreen(title: "Chats", collapsesTitle: true) {
            Button(isEditing ? "Done" : "Edit") {
                withAnimation(.easeInOut(duration: 0.2)) {
                    isEditing.toggle()
                }
            }
            .font(.system(size: 16))
            .foregroundStyle(Theme.accent)
        } navTrailing: {
            HStack(spacing: 18) {
                Button {} label: {
                    Image(systemName: "camera.fill")
                        .font(.system(size: 18, weight: .semibold))
                        .foregroundStyle(Theme.accent)
                }
                .buttonStyle(.plain)
                .accessibilityLabel("Camera")

                Button {} label: {
                    Image(systemName: "square.and.pencil")
                        .font(.system(size: 18, weight: .semibold))
                        .foregroundStyle(Theme.accent)
                }
                .buttonStyle(.plain)
                .accessibilityLabel("New chat")
            }
        } accessory: {
            SearchField(text: $searchText)
                .padding(.horizontal, 16)
                .padding(.bottom, 10)
        } content: {
            LazyVStack(spacing: 0) {
                ForEach(Array(filteredChats.enumerated()), id: \.element.id) { index, chat in
                    Button {} label: {
                        ChatRowView(
                            title: chat.title,
                            subtitle: chat.preview,
                            time: chat.time,
                            unreadCount: chat.unreadCount,
                            avatarGradient: chat.gradient
                        )
                    }
                    .buttonStyle(.plain)

                    if index < filteredChats.count - 1 {
                        listSeparator
                    }
                }

                if filteredChats.isEmpty {
                    emptyState
                }

                Color.clear.frame(height: 16)
            }
        }
        .background(Theme.background)
    }

    private var listSeparator: some View {
        Rectangle()
            .fill(Theme.separator)
            .frame(height: 1)
            .padding(.leading, 80)
    }

    private var emptyState: some View {
        VStack(spacing: 8) {
            Text("No chats found")
                .font(.system(size: 16, weight: .semibold))
                .foregroundStyle(Theme.textPrimary)
            Text("Try a different search.")
                .font(.system(size: 14))
                .foregroundStyle(Theme.textSecondary)
        }
        .frame(maxWidth: .infinity)
        .padding(.top, 48)
    }
}

#Preview {
    ChatsView()
}
