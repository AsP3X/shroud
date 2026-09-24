import SwiftUI

/// Chats list — live conversations from the API.
/// Opens a conversation with a standard **horizontal push / swipe** transition.
struct ChatsView: View {
    @Environment(MessagingController.self) private var messaging
    @Environment(\.isTabBarSearchActive) private var isTabBarSearchActive

    @Binding var path: [ChatRoute]
    /// Owned by `MainTabView` so the tab bar's search field filters this list too.
    @Binding var searchText: String
    @State private var showNewChat = false
    /// Chat waiting on the delete-scope confirmation (scope is picked in the dialog).
    @State private var pendingChatDelete: PendingChatDelete?
    @State private var toast: String?

    init(path: Binding<[ChatRoute]> = .constant([]), searchText: Binding<String> = .constant("")) {
        _path = path
        _searchText = searchText
    }

    /// The chat a long-press asked to delete. Notes have no second party, so they only
    /// offer the single "delete for me" verb.
    private struct PendingChatDelete: Identifiable {
        let peerID: UUID
        let username: String
        let isNotes: Bool
        var id: UUID { peerID }
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

    private var showsNotesRow: Bool {
        let query = searchText.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !query.isEmpty else { return true }
        let name = MessagingController.notesDisplayName
        let preview = messaging.preview(forPeer: MessagingController.notesPeerID)
        return name.localizedCaseInsensitiveContains(query)
            || preview.localizedCaseInsensitiveContains(query)
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
                VStack(spacing: 8) {
                    if messaging.isOffline {
                        offlineBanner
                            .padding(.horizontal, 16)
                            .transition(.move(edge: .top).combined(with: .opacity))
                    }
                    if !isTabBarSearchActive {
                        SearchField(text: $searchText)
                            .padding(.horizontal, 16)
                            .padding(.bottom, 10)
                            .transition(.opacity)
                    }
                }
                .animation(Motion.fade, value: messaging.isOffline)
            } content: {
                LazyVStack(spacing: 0) {
                    if showsNotesRow {
                        NavigationLink(
                            value: ChatRoute.conversation(
                                peerID: MessagingController.notesPeerID,
                                username: MessagingController.notesDisplayName
                            )
                        ) {
                            ChatRowView(
                                title: MessagingController.notesDisplayName,
                                subtitle: messaging.preview(forPeer: MessagingController.notesPeerID),
                                time: messaging.timeLabel(for: messaging.notesLastActivity()),
                                avatarGradient: notesAvatarGradient,
                                avatarSystemImage: "bookmark.fill"
                            )
                        }
                        .buttonStyle(HighlightRowButtonStyle())
                        .entranceRow(index: 0)
                        .contextMenu {
                            deleteChatButton(
                                peerID: MessagingController.notesPeerID,
                                username: MessagingController.notesDisplayName,
                                isNotes: true
                            )
                        }

                        if !filtered.isEmpty || showsSkeleton {
                            listSeparator
                        }
                    }

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
                                hasUnseenReactions: messaging.hasUnseenReactions(for: conversation.peer.id),
                                isMuted: messaging.isMuted(conversation.peer.id),
                                activity: messaging.peerActivity(for: conversation.peer.id),
                                avatarGradient: AvatarView.gradient(for: conversation.peer.username)
                            )
                        }
                        .buttonStyle(HighlightRowButtonStyle())
                        .entranceRow(index: index + (showsNotesRow ? 1 : 0))
                        // Telegram's long-press entry point — the list is a LazyVStack, not a
                        // List, so `.swipeActions` is not available here.
                        .contextMenu {
                            chatNotificationButtons(conversation)
                            deleteChatButton(
                                peerID: conversation.peer.id,
                                username: conversation.peer.username,
                                isNotes: false
                            )
                        }

                        if index < filtered.count - 1 {
                            listSeparator
                        }
                    }

                    if showsSkeleton {
                        SkeletonChatList()
                    } else if let error = messaging.chatsError, messaging.conversations.isEmpty, !showsNotesRow {
                        // Nothing loaded *and* the load failed — don't claim the account is empty.
                        ListLoadErrorView(
                            title: "Can't load chats",
                            message: error,
                            retry: { await messaging.refreshConversations(force: true) }
                        )
                    } else if filtered.isEmpty, !showsNotesRow {
                        emptyState
                    } else if filtered.isEmpty, showsNotesRow, messaging.conversations.isEmpty, !showsSkeleton {
                        // Only Notes is available — still fine when the account has no chats yet.
                        Color.clear.frame(height: 8)
                    }

                    Color.clear.frame(height: 16)
                }
                // Rows reorder on new messages (newest chat jumps to the top) — animate the move.
                .animation(Motion.standard, value: filtered.map(\.id))
                .animation(Motion.fade, value: showsSkeleton)
                .animation(Motion.fade, value: messaging.chatsError)
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
            .confirmationDialog(
                chatDeleteTitle,
                isPresented: chatDeleteBinding,
                titleVisibility: .visible,
                presenting: pendingChatDelete
            ) { pending in
                if !pending.isNotes {
                    Button("Delete for me and \(pending.username)", role: .destructive) {
                        performChatDelete(pending, scope: .everyone)
                    }
                }
                Button(pending.isNotes ? "Delete" : "Delete for me", role: .destructive) {
                    performChatDelete(pending, scope: .me)
                }
                Button("Cancel", role: .cancel) { pendingChatDelete = nil }
            } message: { pending in
                Text(chatDeleteExplanation(pending))
            }
            .toast($toast)
        }
        .refreshable {
            // Explicit pull always fetches, even if a background poll is mid-flight.
            await messaging.refreshConversations(force: true)
        }
        .task {
            await messaging.refreshConversations()
        }
    }

    // MARK: - Notifications

    /// Mark as read, and mute (for a while, or until turned back on) or unmute — on every device.
    @ViewBuilder
    private func chatNotificationButtons(_ conversation: ConversationItemDTO) -> some View {
        let peerID = conversation.peer.id
        if messaging.unreadCount(for: peerID) > 0 {
            Button {
                messaging.markChatRead(peerUserID: peerID)
                Haptics.impact(.light)
            } label: {
                Label("Mark as Read", systemImage: "checkmark.message")
            }
        }
        if messaging.isMuted(peerID) {
            Button {
                changeMute(peerID, to: nil)
            } label: {
                Label("Unmute", systemImage: "bell")
            }
        } else {
            Menu {
                ForEach(MuteDuration.allCases) { duration in
                    Button(duration.title) { changeMute(peerID, to: duration) }
                }
            } label: {
                Label("Mute", systemImage: "bell.slash")
            }
        }
    }

    private func changeMute(_ peerID: UUID, to duration: MuteDuration?) {
        Task {
            let error = if let duration {
                await messaging.muteChat(peerUserID: peerID, duration: duration)
            } else {
                await messaging.unmuteChat(peerUserID: peerID)
            }
            if let error {
                toast = error
                Haptics.notification(.error)
            } else {
                toast = duration == nil ? "Notifications on" : (MuteDuration.label(for: messaging.mute(for: peerID)) ?? "Muted")
                Haptics.impact(.light)
            }
            try? await Task.sleep(nanoseconds: 1_800_000_000)
            toast = nil
        }
    }

    // MARK: - Delete chat

    @ViewBuilder
    private func deleteChatButton(peerID: UUID, username: String, isNotes: Bool) -> some View {
        Button(role: .destructive) {
            pendingChatDelete = PendingChatDelete(
                peerID: peerID,
                username: username,
                isNotes: isNotes
            )
        } label: {
            Label(isNotes ? "Delete Saved Messages" : "Delete Chat", systemImage: "trash")
        }
    }

    private var chatDeleteBinding: Binding<Bool> {
        Binding(
            get: { pendingChatDelete != nil },
            set: { if !$0 { pendingChatDelete = nil } }
        )
    }

    private var chatDeleteTitle: String {
        guard let pending = pendingChatDelete else { return "Delete chat?" }
        return pending.isNotes ? "Delete Saved Messages?" : "Delete chat with \(pending.username)?"
    }

    /// Spells out the asymmetric outcome up front: deleting for both always disconnects the
    /// two accounts, but the peer's own messages only disappear if they allowed it.
    private func chatDeleteExplanation(_ pending: PendingChatDelete) -> String {
        if pending.isNotes {
            return "Removes every saved message from this device and your account."
        }
        return """
        Deleting for both unsends your messages in \(pending.username)'s chat and removes them \
        as a contact — you'd both have to add each other again. Their own messages stay unless \
        they allow chats to be cleared for them.
        """
    }

    private func performChatDelete(_ pending: PendingChatDelete, scope: ConversationDeleteScope) {
        pendingChatDelete = nil
        Task {
            let outcome = await messaging.deleteConversation(
                peerUserID: pending.peerID,
                scope: scope
            )
            switch outcome {
            case .clearedForMe:
                toast = "Chat deleted"
                Haptics.notification(.success)
            case .clearedForBoth:
                toast = "Chat deleted for both"
                Haptics.notification(.success)
            case .unsentForPeer:
                toast = "Deleted · \(pending.username) keeps their own messages"
                Haptics.notification(.success)
            case let .failed(message):
                toast = message
                Haptics.notification(.error)
            }
            try? await Task.sleep(nanoseconds: 2_400_000_000)
            toast = nil
        }
    }

    private func rowSubtitle(_ conversation: ConversationItemDTO) -> String {
        messaging.preview(for: conversation)
    }

    private var notesAvatarGradient: LinearGradient {
        LinearGradient(
            colors: [Theme.accent, Theme.accentSoft],
            startPoint: .topLeading,
            endPoint: .bottomTrailing
        )
    }

    private var offlineBanner: some View {
        HStack(spacing: 8) {
            Image(systemName: "wifi.slash")
                .font(.system(size: 13, weight: .semibold))
            Text("Offline — showing last \(LocalMessageStore.retentionDays) days on this device")
                .font(.system(size: 13, weight: .medium))
                .lineLimit(2)
            Spacer(minLength: 0)
        }
        .foregroundStyle(Theme.textPrimary)
        .padding(.horizontal, 12)
        .padding(.vertical, 8)
        .background(Theme.backgroundGrouped)
        .clipShape(RoundedRectangle(cornerRadius: 12, style: .continuous))
        .accessibilityLabel("Offline. Showing cached messages.")
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
