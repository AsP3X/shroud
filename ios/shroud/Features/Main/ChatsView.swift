import SwiftUI

/// Chats list — live conversations from the API.
/// Opens a conversation with a standard **horizontal push / swipe** transition.
struct ChatsView: View {
    @Environment(MessagingController.self) private var messaging

    @Binding var path: [ChatRoute]
    @State private var searchText = ""
    @State private var showNewChat = false

    init(path: Binding<[ChatRoute]> = .constant([])) {
        _path = path
    }

    /// Skeleton stands in only for the *first* load — a refresh over existing rows keeps the
    /// list. Keyed on `hasLoadedChats` (not `isLoadingChats`) so the 3s poll can't flip an
    /// empty list back to placeholders on every tick.
    private var showsSkeleton: Bool {
        !messaging.hasLoadedChats && messaging.conversations.isEmpty && searchText.isEmpty
    }

    private var filtered: [ConversationItemDTO] {
        let query = searchText.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !query.isEmpty else { return messaging.conversations }
        return messaging.conversations.filter {
            $0.peer.username.localizedCaseInsensitiveContains(query)
                || messaging.preview(for: $0).localizedCaseInsensitiveContains(query)
        }
    }

    var body: some View {
        NavigationStack(path: $path) {
            MainScrollScreen(title: "Chats", collapsesTitle: true) {
                EmptyView()
            } navTrailing: {
                Button {
                    showNewChat = true
                } label: {
                    Image(systemName: "square.and.pencil")
                        .font(.system(size: 18, weight: .semibold))
                        .foregroundStyle(Theme.accent)
                        .frame(width: 44, height: 44, alignment: .trailing)
                        .contentShape(Rectangle())
                }
                .pressable(scale: 0.88)
                .accessibilityLabel("New chat")
            } accessory: {
                SearchField(text: $searchText)
                    .padding(.horizontal, 16)
                    .padding(.bottom, 10)
            } content: {
                LazyVStack(spacing: 0) {
                    ForEach(Array(filtered.enumerated()), id: \.element.id) { index, conversation in
                        NavigationLink(
                            value: ChatRoute.conversation(
                                peerID: conversation.peer.id,
                                username: conversation.peer.username
                            )
                        ) {
                            ChatRowView(
                                title: conversation.peer.username,
                                subtitle: rowSubtitle(conversation),
                                time: messaging.timeLabel(for: conversation.lastMessageAt),
                                unreadCount: {
                                    let n = messaging.unreadCount(for: conversation.peer.id)
                                    return n > 0 ? n : nil
                                }(),
                                subtitleAccent: messaging.typingPeerIDs.contains(conversation.peer.id),
                                avatarGradient: AvatarView.gradient(for: conversation.peer.username)
                            )
                        }
                        .buttonStyle(HighlightRowButtonStyle())
                        .entranceRow(index: index)

                        if index < filtered.count - 1 {
                            listSeparator
                        }
                    }

                    if showsSkeleton {
                        SkeletonChatList()
                    } else if filtered.isEmpty {
                        emptyState
                    }

                    Color.clear.frame(height: 16)
                }
                // Rows reorder on new messages (newest chat jumps to the top) — animate the move.
                .animation(Motion.standard, value: filtered.map(\.id))
                .animation(Motion.fade, value: showsSkeleton)
            }
            // Re-arms the staggered entrance the moment the first page of chats lands.
            .listEntranceHost(resetOn: messaging.conversations.isEmpty)
            .background(Theme.background)
            .navigationDestination(for: ChatRoute.self) { route in
                switch route {
                case let .conversation(peerID, username):
                    ConversationView(peerUserID: peerID, peerUsername: username)
                        // Explicit push style (slide from trailing).
                        .navigationTransition(.automatic)
                }
            }
            .sheet(isPresented: $showNewChat) {
                NewChatSheet { peerID, username in
                    withAnimation(ChatOpenAnimation.push) {
                        path.append(.conversation(peerID: peerID, username: username))
                    }
                }
            }
        }
        .refreshable {
            // Explicit pull always fetches, even if a background poll is mid-flight.
            await messaging.refreshConversations(force: true)
        }
        .task {
            await messaging.refreshConversations()
        }
    }

    private func rowSubtitle(_ conversation: ConversationItemDTO) -> String {
        if messaging.typingPeerIDs.contains(conversation.peer.id) {
            return "typing…"
        }
        return messaging.preview(for: conversation)
    }

    private var listSeparator: some View {
        Rectangle()
            .fill(Theme.separator)
            .frame(height: 1)
            .padding(.leading, 80)
    }

    private var emptyState: some View {
        VStack(spacing: 12) {
            Image(systemName: "bubble.left.and.bubble.right.fill")
                .font(.system(size: 34, weight: .semibold))
                .foregroundStyle(Theme.accent.opacity(0.85))
                // Gentle one-shot bounce so the empty screen still feels alive on arrival.
                .symbolEffect(.bounce, options: .nonRepeating)
                .padding(.bottom, 4)
            Text(searchText.isEmpty ? "No chats yet" : "No matches")
                .font(.system(size: 16, weight: .semibold))
                .foregroundStyle(Theme.textPrimary)
            Text(
                searchText.isEmpty
                    ? "Message a contact to start a conversation."
                    : "Try a different name."
            )
            .font(.system(size: 14))
            .foregroundStyle(Theme.textSecondary)
            .multilineTextAlignment(.center)
            if searchText.isEmpty {
                Button("New Chat") {
                    showNewChat = true
                }
                .font(.system(size: 16, weight: .semibold))
                .foregroundStyle(Theme.accent)
                .padding(.top, 4)
                .pressable(scale: 0.94)
            }
        }
        .frame(maxWidth: .infinity)
        .padding(.top, 48)
        .padding(.horizontal, 24)
        .transition(.opacity.combined(with: .offset(y: 8)))
    }
}

#Preview {
    ChatsView()
        .environment(MessagingController())
}
