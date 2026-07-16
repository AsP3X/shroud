import SwiftUI

/// 1:1 chat thread — maps to `Conversation` (+ variants) in `design/iOS-App.pen`.
///
/// Layout notes:
/// - Top chrome and composer are `safeAreaInset`s whose **backgrounds** extend under
///   the status bar / home indicator so light+dark modes fill edge-to-edge.
/// - The floating main tab bar is hidden by path ownership in `MainTabView` (not here).
struct ConversationView: View {
    let peerUserID: UUID
    let peerUsername: String

    @Environment(MessagingController.self) private var messaging
    @Environment(\.dismiss) private var dismiss

    @State private var draft = ""
    @State private var typingTask: Task<Void, Never>?
    @State private var showAttach = false
    @State private var isRecording = false
    @State private var recordingSeconds = 0
    @State private var recordingTimer: Timer?
    @State private var toast: String?
    @State private var focusedMessage: MessagingController.ChatMessage?
    @State private var profileDestination: ProfileDestination?

    private var messages: [MessagingController.ChatMessage] {
        messaging.threads[peerUserID] ?? []
    }

    private var isPeerTyping: Bool {
        messaging.typingPeerIDs.contains(peerUserID)
    }

    private var isOnline: Bool {
        messaging.presenceByUser[peerUserID]?.online == true
    }

    private var presenceLabel: String {
        if isPeerTyping { return "typing…" }
        if isOnline { return "online" }
        if let presence = messaging.presenceByUser[peerUserID] {
            if let last = presence.lastSeenAt {
                return "last seen \(messaging.timeLabel(for: last))"
            }
            return "offline"
        }
        return "…"
    }

    private var presenceAccent: Bool {
        isPeerTyping || isOnline
    }

    var body: some View {
        messageList
            .frame(maxWidth: .infinity, maxHeight: .infinity)
            .background(Theme.backgroundChat)
            .safeAreaInset(edge: .top, spacing: 0) {
                topChrome
            }
            .safeAreaInset(edge: .bottom, spacing: 0) {
                ChatComposerView(
                    draft: $draft,
                    isRecording: isRecording,
                    recordingSeconds: recordingSeconds,
                    onAttach: { showAttach = true },
                    onSend: sendDraft,
                    onMicTap: startRecordingUI,
                    onDiscardRecording: stopRecording(discard: true),
                    onSendRecording: stopRecording(discard: false),
                    onDraftChange: { scheduleTyping(!$0.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty) }
                )
            }
            .navigationBarBackButtonHidden(true)
            .toolbar(.hidden, for: .navigationBar)
            .toolbarBackground(.hidden, for: .navigationBar)
            .task {
                await messaging.loadThread(peerUserID: peerUserID)
            }
            .onAppear {
                messaging.setActivePeer(peerUserID)
            }
            .onDisappear {
                typingTask?.cancel()
                recordingTimer?.invalidate()
                messaging.setTyping(peerUserID: peerUserID, isTyping: false)
                if messaging.activePeerID == peerUserID {
                    messaging.setActivePeer(nil)
                }
            }
            .sheet(isPresented: $showAttach) {
                ChatAttachSheet(
                    onSelect: { option in
                        showAttach = false
                        handleAttach(option)
                    },
                    onCancel: { showAttach = false }
                )
                .presentationDetents([.height(420)])
                .presentationDragIndicator(.hidden)
                .presentationBackground(Theme.background)
            }
            .navigationDestination(item: $profileDestination) { dest in
                ContactProfileView(peerUserID: dest.peerUserID, peerUsername: dest.peerUsername)
            }
            .overlay {
                if let focusedMessage {
                    messageMenuOverlay(for: focusedMessage)
                }
            }
            .toast($toast)
    }

    private struct ProfileDestination: Identifiable, Hashable {
        let peerUserID: UUID
        let peerUsername: String
        var id: UUID { peerUserID }
    }

    // MARK: - Top chrome (extends under status bar)

    private var topChrome: some View {
        VStack(spacing: 0) {
            HStack(spacing: 10) {
                Button {
                    dismiss()
                } label: {
                    Image(systemName: "chevron.left")
                        .font(.system(size: 18, weight: .semibold))
                        .foregroundStyle(Theme.accent)
                        .frame(width: 26, height: 26)
                        .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
                .accessibilityLabel("Back")

                Button {
                    profileDestination = ProfileDestination(
                        peerUserID: peerUserID,
                        peerUsername: peerUsername
                    )
                } label: {
                    HStack(spacing: 10) {
                        AvatarView(
                            initials: AvatarView.initials(for: peerUsername),
                            size: 40,
                            gradient: AvatarView.gradient(for: peerUsername),
                            fontSize: 14
                        )

                        VStack(alignment: .leading, spacing: 1) {
                            Text(peerUsername)
                                .font(.system(size: 16, weight: .semibold))
                                .foregroundStyle(Theme.textPrimary)
                                .lineLimit(1)
                            HStack(spacing: 4) {
                                if isOnline || isPeerTyping {
                                    Circle()
                                        .fill(isPeerTyping ? Theme.accent : Theme.online)
                                        .frame(width: 7, height: 7)
                                }
                                Text(presenceLabel)
                                    .font(.system(size: 12))
                                    .foregroundStyle(presenceAccent ? Theme.accent : Theme.textSecondary)
                                    .lineLimit(1)
                            }
                        }
                    }
                }
                .buttonStyle(.plain)
                .frame(maxWidth: .infinity, alignment: .leading)

                Button {
                    showComingSoon("Video calls")
                } label: {
                    Image(systemName: "video.fill")
                        .font(.system(size: 17, weight: .semibold))
                        .foregroundStyle(Theme.accent)
                        .frame(width: 28, height: 28)
                        .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
                .accessibilityLabel("Video call")

                Button {
                    showComingSoon("Voice calls")
                } label: {
                    Image(systemName: "phone.fill")
                        .font(.system(size: 16, weight: .semibold))
                        .foregroundStyle(Theme.accent)
                        .frame(width: 26, height: 26)
                        .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
                .accessibilityLabel("Call")
            }
            .padding(.horizontal, 16)
            .padding(.top, 6)
            .padding(.bottom, 10)

            Rectangle()
                .fill(Theme.separator)
                .frame(height: 1 / UIScreen.main.scale)
        }
        .background {
            // Solid theme color under status bar (works in light + dark).
            Theme.background
                .ignoresSafeArea(edges: .top)
        }
    }

    // MARK: - Messages

    private var messageList: some View {
        ScrollViewReader { proxy in
            ScrollView {
                LazyVStack(spacing: 10) {
                    headerChips

                    ForEach(groupedTimeline, id: \.id) { item in
                        switch item {
                        case let .date(label, id):
                            ChatDateChip(label: label)
                                .id(id)
                                .padding(.top, 4)
                        case let .message(message):
                            MessageBubbleView(
                                text: message.text,
                                time: messaging.clockTimeLabel(for: message.createdAt),
                                isMine: message.isMine,
                                isDeleted: message.deleted
                            )
                            .id(message.id)
                            .onLongPressGesture(minimumDuration: 0.35) {
                                Haptics.impact(.medium)
                                focusedMessage = message
                            }
                        }
                    }

                    if isPeerTyping {
                        TypingIndicatorBubble()
                            .id("typing-indicator")
                    }

                    Color.clear.frame(height: 8).id("thread-bottom")
                }
                .padding(.horizontal, 16)
                .padding(.vertical, 12)
            }
            .scrollDismissesKeyboard(.interactively)
            .onChange(of: messages.count) { _, _ in
                scrollToBottom(proxy)
            }
            .onChange(of: isPeerTyping) { _, typing in
                if typing { scrollToBottom(proxy) }
            }
            .onAppear {
                scrollToBottom(proxy, animated: false)
            }
        }
    }

    private var headerChips: some View {
        VStack(spacing: 8) {
            if messages.isEmpty {
                ChatDateChip(label: "Today")
            }
            ChatE2ENotice()
                .padding(.bottom, 4)
        }
        .frame(maxWidth: .infinity)
    }

    // MARK: - Grouping

    private enum TimelineItem: Identifiable {
        case date(String, id: String)
        case message(MessagingController.ChatMessage)

        var id: String {
            switch self {
            case let .date(_, id): id
            case let .message(m): m.id.uuidString
            }
        }
    }

    private var groupedTimeline: [TimelineItem] {
        var items: [TimelineItem] = []
        var lastDay: String?
        let calendar = Calendar.current
        for message in messages {
            let dayKey = dayKey(for: message.createdAt, calendar: calendar)
            if dayKey != lastDay {
                items.append(.date(dayLabel(for: message.createdAt, calendar: calendar), id: "day-\(dayKey)"))
                lastDay = dayKey
            }
            items.append(.message(message))
        }
        return items
    }

    private func dayKey(for date: Date, calendar: Calendar) -> String {
        let c = calendar.dateComponents([.year, .month, .day], from: date)
        return "\(c.year ?? 0)-\(c.month ?? 0)-\(c.day ?? 0)"
    }

    private func dayLabel(for date: Date, calendar: Calendar) -> String {
        if calendar.isDateInToday(date) { return "Today" }
        if calendar.isDateInYesterday(date) { return "Yesterday" }
        return date.formatted(date: .abbreviated, time: .omitted)
    }

    private func scrollToBottom(_ proxy: ScrollViewProxy, animated: Bool = true) {
        let action = {
            if isPeerTyping {
                proxy.scrollTo("typing-indicator", anchor: .bottom)
            } else if let last = messages.last {
                proxy.scrollTo(last.id, anchor: .bottom)
            } else {
                proxy.scrollTo("thread-bottom", anchor: .bottom)
            }
        }
        if animated {
            withAnimation(.easeOut(duration: 0.2), action)
        } else {
            action()
        }
    }

    // MARK: - Actions

    private func sendDraft() {
        let text = draft
        draft = ""
        messaging.setTyping(peerUserID: peerUserID, isTyping: false)
        Haptics.impact(.light)
        Task { await messaging.sendText(text, to: peerUserID) }
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

    private func startRecordingUI() {
        isRecording = true
        recordingSeconds = 0
        recordingTimer?.invalidate()
        recordingTimer = Timer.scheduledTimer(withTimeInterval: 1, repeats: true) { _ in
            recordingSeconds += 1
        }
        Haptics.impact(.medium)
    }

    private func stopRecording(discard: Bool) -> () -> Void {
        {
            recordingTimer?.invalidate()
            recordingTimer = nil
            isRecording = false
            recordingSeconds = 0
            if discard {
                Haptics.notification(.warning)
            } else {
                showComingSoon("Voice messages")
            }
        }
    }

    private func handleAttach(_ option: ChatAttachOption) {
        switch option {
        case .camera, .photos, .file, .location, .contact, .music, .gift, .stickers:
            showComingSoon(option.title)
        }
    }

    private func showComingSoon(_ feature: String) {
        toast = "\(feature) coming soon"
        Haptics.impact(.light)
        Task {
            try? await Task.sleep(nanoseconds: 2_000_000_000)
            if toast?.contains(feature) == true {
                toast = nil
            }
        }
    }

    @ViewBuilder
    private func messageMenuOverlay(for message: MessagingController.ChatMessage) -> some View {
        ZStack {
            Theme.textPrimary.opacity(0.28)
                .ignoresSafeArea()
                .onTapGesture { focusedMessage = nil }

            VStack(spacing: 12) {
                MessageBubbleView(
                    text: message.text,
                    time: messaging.clockTimeLabel(for: message.createdAt),
                    isMine: message.isMine,
                    isDeleted: message.deleted
                )
                .padding(.horizontal, 24)

                MessageActionMenu(
                    isMine: message.isMine,
                    onReaction: { emoji in
                        focusedMessage = nil
                        toast = "Reacted \(emoji)"
                        scheduleToastClear()
                    },
                    onAction: { action in
                        focusedMessage = nil
                        handleMenu(action, message: message)
                    }
                )
            }
            .padding(.horizontal, 20)
        }
        .transition(.opacity)
    }

    private func handleMenu(_ action: MessageMenuAction, message: MessagingController.ChatMessage) {
        switch action {
        case .copy:
            UIPasteboard.general.string = message.text
            toast = "Copied"
            Haptics.notification(.success)
            scheduleToastClear()
        case .reply, .edit, .pin, .forward, .select, .moreReactions:
            showComingSoon(action.title)
        case .delete:
            toast = "Delete coming soon"
            scheduleToastClear()
        }
    }

    private func scheduleToastClear() {
        Task {
            try? await Task.sleep(nanoseconds: 1_800_000_000)
            toast = nil
        }
    }
}

#Preview {
    NavigationStack {
        ConversationView(
            peerUserID: UUID(),
            peerUsername: "Jane Cooper"
        )
    }
    .environment(MessagingController())
}
