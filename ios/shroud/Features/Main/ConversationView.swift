import PhotosUI
import SwiftUI
import UIKit

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
    @State private var viewingMedia: ViewingMedia?
    @State private var composeDraft: ComposeDraft?
    @State private var profileDestination: ProfileDestination?
    @State private var photoPickerItem: PhotosPickerItem?
    @State private var showPhotoPicker = false
    @State private var showCamera = false
    @State private var isSendingMedia = false

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
                    onCancel: { showAttach = false },
                    onPickImage: { image in
                        showAttach = false
                        presentMediaCompose(image)
                    }
                )
                .presentationDetents([.height(420)])
                .presentationDragIndicator(.hidden)
                .presentationBackground(Theme.background)
            }
            .photosPicker(
                isPresented: $showPhotoPicker,
                selection: $photoPickerItem,
                matching: .images,
                photoLibrary: .shared()
            )
            .onChange(of: photoPickerItem) { _, item in
                guard let item else { return }
                Task { await loadPickedPhotoForCompose(item) }
            }
            .fullScreenCover(isPresented: $showCamera) {
                CameraPicker { image in
                    showCamera = false
                    guard let image else { return }
                    presentMediaCompose(image)
                }
                .ignoresSafeArea()
            }
            .navigationDestination(item: $profileDestination) { dest in
                ContactProfileView(peerUserID: dest.peerUserID, peerUsername: dest.peerUsername)
            }
            .overlay {
                if let focusedMessage {
                    messageMenuOverlay(for: focusedMessage)
                }
            }
            .overlay {
                if let viewingMedia {
                    MediaImageViewerOverlay(
                        image: viewingMedia.image,
                        title: viewingMedia.title,
                        dateLine: viewingMedia.dateLine,
                        onClose: {
                            withAnimation(.easeOut(duration: 0.2)) {
                                self.viewingMedia = nil
                            }
                        },
                        onComingSoon: { feature in
                            toast = "\(feature) coming soon"
                            scheduleToastClear()
                        }
                    )
                    // Cover chat header + composer + status bar (true Telegram overlay).
                    .ignoresSafeArea()
                    .transition(.opacity)
                    .zIndex(50)
                }
            }
            .overlay {
                if let composeDraft {
                    MediaComposeOverlay(
                        image: composeDraft.image,
                        peerUsername: peerUsername,
                        onCancel: {
                            withAnimation(.easeOut(duration: 0.2)) {
                                self.composeDraft = nil
                            }
                        },
                        onSend: { caption, quality in
                            let image = composeDraft.image
                            withAnimation(.easeOut(duration: 0.15)) {
                                self.composeDraft = nil
                            }
                            Task {
                                await sendUIImage(image, caption: caption, quality: quality)
                            }
                        },
                        onComingSoon: { feature in
                            toast = "\(feature) coming soon"
                            scheduleToastClear()
                        }
                    )
                    .ignoresSafeArea()
                    .transition(.opacity)
                    .zIndex(60)
                }
            }
            .overlay {
                if isSendingMedia {
                    ProgressView("Sending photo…")
                        .padding(16)
                        .background(.ultraThinMaterial)
                        .clipShape(RoundedRectangle(cornerRadius: 14, style: .continuous))
                }
            }
            .toast($toast)
            .animation(.easeOut(duration: 0.2), value: viewingMedia != nil)
            .animation(.easeOut(duration: 0.2), value: composeDraft != nil)
    }

    private struct ProfileDestination: Identifiable, Hashable {
        let peerUserID: UUID
        let peerUsername: String
        var id: UUID { peerUserID }
    }

    /// In-conversation media overlay payload (not a navigation destination).
    private struct ViewingMedia: Identifiable {
        let id: UUID
        let image: UIImage
        let title: String
        let dateLine: String
    }

    /// Draft photo ready for caption + send (Telegram media compose).
    private struct ComposeDraft: Identifiable {
        let id = UUID()
        let image: UIImage
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
                // Telegram-like density: tighter gaps between bubbles.
                LazyVStack(spacing: 3) {
                    headerChips
                        .padding(.bottom, 6)

                    ForEach(groupedTimeline, id: \.id) { item in
                        switch item {
                        case let .date(label, id):
                            ChatDateChip(label: label)
                                .id(id)
                                .padding(.vertical, 8)
                        case let .message(message):
                            messageRow(message)
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

    @ViewBuilder
    private func messageRow(_ message: MessagingController.ChatMessage) -> some View {
        switch message.kind {
        case .image:
            ImageMessageBubble(
                message: message,
                time: messaging.clockTimeLabel(for: message.createdAt),
                onAppearLoad: {
                    Task { await messaging.ensureImageLoaded(for: message) }
                },
                onRetry: {
                    Task {
                        isSendingMedia = true
                        let error = await messaging.retryFailedImage(
                            messageID: message.id,
                            peerUserID: peerUserID
                        )
                        isSendingMedia = false
                        if let error {
                            toast = error
                            Haptics.notification(.error)
                            scheduleToastClear()
                        } else {
                            Haptics.notification(.success)
                        }
                    }
                },
                onOpen: {
                    openMediaViewer(for: message)
                }
            )
        case .text:
            MessageBubbleView(
                text: message.text,
                time: messaging.clockTimeLabel(for: message.createdAt),
                isMine: message.isMine,
                isDeleted: message.deleted,
                receipt: message.receipt
            )
        }
    }

    private func handleAttach(_ option: ChatAttachOption) {
        switch option {
        case .photos:
            showPhotoPicker = true
        case .camera:
            if UIImagePickerController.isSourceTypeAvailable(.camera) {
                showCamera = true
            } else {
                toast = "Camera is not available on this device."
                scheduleToastClear()
            }
        case .file, .location, .contact, .music, .gift, .stickers:
            showComingSoon(option.title)
        }
    }

    private func loadPickedPhotoForCompose(_ item: PhotosPickerItem) async {
        defer { photoPickerItem = nil }
        do {
            guard let data = try await item.loadTransferable(type: Data.self),
                  let image = UIImage(data: data)
            else {
                toast = "Could not load that photo."
                scheduleToastClear()
                return
            }
            presentMediaCompose(image)
        } catch {
            toast = "Could not load that photo."
            scheduleToastClear()
        }
    }

    private func presentMediaCompose(_ image: UIImage) {
        withAnimation(.easeOut(duration: 0.2)) {
            composeDraft = ComposeDraft(image: image)
        }
    }

    private func sendUIImage(
        _ image: UIImage,
        caption: String = "",
        quality: MediaComposeQuality = .sd
    ) async {
        isSendingMedia = true
        let error = await messaging.sendImage(
            image,
            to: peerUserID,
            caption: caption,
            quality: quality
        )
        isSendingMedia = false
        if let error {
            // Bubble stays in the thread with Retry; also surface the reason.
            toast = error
            Haptics.notification(.error)
            // Keep error visible longer so it can be read.
            Task {
                try? await Task.sleep(nanoseconds: 4_000_000_000)
                if toast == error { toast = nil }
            }
        } else {
            Haptics.notification(.success)
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
                messageRow(message)
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

    /// Presents the Telegram-style media **overlay** over the conversation (not a push).
    private func openMediaViewer(for message: MessagingController.ChatMessage) {
        Task {
            await messaging.ensureImageLoaded(for: message)
            let data = messaging.threads[peerUserID]?
                .first(where: { $0.id == message.id })?
                .imageData ?? message.imageData
            guard let data, let image = UIImage(data: data) else {
                toast = "Could not open that photo."
                scheduleToastClear()
                return
            }
            let title = message.isMine ? "You" : peerUsername
            let dateLine = Self.viewerDateLine(for: message.createdAt)
            withAnimation(.easeOut(duration: 0.2)) {
                viewingMedia = ViewingMedia(
                    id: message.id,
                    image: image,
                    title: title,
                    dateLine: dateLine
                )
            }
        }
    }

    private static func viewerDateLine(for date: Date) -> String {
        let formatter = DateFormatter()
        formatter.locale = Locale(identifier: "en_GB")
        formatter.dateFormat = "dd.MM.yy"
        return formatter.string(from: date)
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
