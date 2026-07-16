import SwiftUI

/// 1:1 chat thread with sealed send/receive.
struct ConversationView: View {
    let peerUserID: UUID
    let peerUsername: String

    @Environment(MessagingController.self) private var messaging
    @State private var draft = ""
    @State private var typingTask: Task<Void, Never>?
    @FocusState private var composerFocused: Bool

    private var messages: [MessagingController.ChatMessage] {
        messaging.threads[peerUserID] ?? []
    }

    private var subtitle: String {
        if messaging.typingPeerIDs.contains(peerUserID) {
            return "typing…"
        }
        if let presence = messaging.presenceByUser[peerUserID] {
            return presence.online ? "online" : presenceSubtitle(presence)
        }
        return "tap to load presence"
    }

    var body: some View {
        VStack(spacing: 0) {
            ScrollViewReader { proxy in
                ScrollView {
                    LazyVStack(spacing: 8) {
                        ForEach(messages) { message in
                            bubble(message)
                                .id(message.id)
                        }
                    }
                    .padding(.horizontal, 14)
                    .padding(.vertical, 12)
                }
                .onChange(of: messages.count) { _, _ in
                    if let last = messages.last {
                        withAnimation(.easeOut(duration: 0.2)) {
                            proxy.scrollTo(last.id, anchor: .bottom)
                        }
                    }
                }
            }

            composer
        }
        .background(Theme.backgroundChat)
        .navigationTitle(peerUsername)
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .principal) {
                VStack(spacing: 1) {
                    Text(peerUsername)
                        .font(.system(size: 16, weight: .semibold))
                        .foregroundStyle(Theme.textPrimary)
                    Text(subtitle)
                        .font(.system(size: 12))
                        .foregroundStyle(
                            messaging.typingPeerIDs.contains(peerUserID) || messaging.presenceByUser[peerUserID]?.online == true
                                ? Theme.accent
                                : Theme.textSecondary
                        )
                }
            }
        }
        .task {
            await messaging.loadThread(peerUserID: peerUserID)
        }
        .onAppear {
            messaging.setActivePeer(peerUserID)
        }
        .onDisappear {
            typingTask?.cancel()
            messaging.setTyping(peerUserID: peerUserID, isTyping: false)
            if messaging.activePeerID == peerUserID {
                messaging.setActivePeer(nil)
            }
        }
    }

    private func bubble(_ message: MessagingController.ChatMessage) -> some View {
        HStack {
            if message.isMine { Spacer(minLength: 48) }
            VStack(alignment: message.isMine ? .trailing : .leading, spacing: 4) {
                Text(message.text)
                    .font(.system(size: 16))
                    .foregroundStyle(message.isMine ? Color.white : Theme.textPrimary)
                    .padding(.horizontal, 12)
                    .padding(.vertical, 8)
                    .background(
                        message.isMine
                            ? Theme.accent
                            : Theme.bubbleIncoming
                    )
                    .clipShape(RoundedRectangle(cornerRadius: 16, style: .continuous))
                Text(messaging.timeLabel(for: message.createdAt))
                    .font(.system(size: 11))
                    .foregroundStyle(Theme.textSecondary)
            }
            if !message.isMine { Spacer(minLength: 48) }
        }
    }

    private var composer: some View {
        HStack(spacing: 10) {
            TextField("Message", text: $draft, axis: .vertical)
                .lineLimit(1 ... 5)
                .padding(.horizontal, 12)
                .padding(.vertical, 8)
                .background(Theme.backgroundGrouped)
                .clipShape(RoundedRectangle(cornerRadius: 18, style: .continuous))
                .focused($composerFocused)
                .onChange(of: draft) { _, newValue in
                    scheduleTyping(!newValue.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
                }

            Button {
                let text = draft
                draft = ""
                messaging.setTyping(peerUserID: peerUserID, isTyping: false)
                Task { await messaging.sendText(text, to: peerUserID) }
            } label: {
                Image(systemName: "arrow.up.circle.fill")
                    .font(.system(size: 32))
                    .foregroundStyle(
                        draft.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
                            ? Theme.textSecondary
                            : Theme.accent
                    )
            }
            .disabled(draft.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
        }
        .padding(.horizontal, 12)
        .padding(.vertical, 8)
        .background(.ultraThinMaterial)
    }

    private func scheduleTyping(_ isTyping: Bool) {
        typingTask?.cancel()
        messaging.setTyping(peerUserID: peerUserID, isTyping: isTyping)
        guard isTyping else { return }
        typingTask = Task {
            try? await Task.sleep(nanoseconds: 2_500_000_000)
            if !Task.isCancelled {
                messaging.setTyping(peerUserID: peerUserID, isTyping: false)
            }
        }
    }

    private func presenceSubtitle(_ presence: PresenceDTO) -> String {
        if let last = presence.lastSeenAt {
            return "last seen \(messaging.timeLabel(for: last))"
        }
        return "offline"
    }
}
