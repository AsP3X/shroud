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
    /// When set (hero presentation from chat list), used instead of `dismiss()`.
    var onBack: (() -> Void)? = nil

    @Environment(MessagingController.self) private var messaging
    @Environment(CallController.self) private var calls
    @Environment(\.dismiss) private var dismiss
    @Environment(\.displayScale) private var displayScale

    @State private var draft = ""
    @State private var typingTask: Task<Void, Never>?
    @State private var showAttach = false
    /// Owns the mic session for this thread. The composer only reads its live state.
    @State private var voiceRecorder = VoiceRecorder()
    @State private var toast: String?
    /// Active long-press focus session.
    @State private var focusedMenu: FocusedMessageMenu?
    /// 0 = list slot, 1 = focus stack. Single source of truth for open+close motion.
    @State private var menuProgress: CGFloat = 0
    @State private var menuAnimationTask: Task<Void, Never>?
    @State private var menuAnimationGeneration = 0
    /// When the current menu opened — used to ignore the release of the finger that opened it.
    @State private var menuOpenedAt: Date?
    /// Live global frames of each bubble (visual only — no row spacers).
    @State private var bubbleGlobalFrames: [UUID: CGRect] = [:]
    @State private var viewingMedia: ViewingMedia?
    @State private var composeDraft: ComposeDraft?
    @State private var profileDestination: ProfileDestination?
    @State private var photoPickerItems: [PhotosPickerItem] = []
    @State private var showPhotoPicker = false
    /// True when the picker was opened from compose, so its results append instead of replace.
    @State private var pickerAppendsToDraft = false
    @State private var showCamera = false
    @State private var isSendingMedia = false
    /// Bumped after thread load / open so we re-pin to the newest message once layout is ready.
    @State private var pinToBottomToken = 0

    private var messages: [MessagingController.ChatMessage] {
        messaging.threads[peerUserID] ?? []
    }

    /// Stable identity of the newest bubble (count alone misses same-count reloads).
    private var newestMessageID: UUID? {
        messages.last?.id
    }

    private var isNotes: Bool {
        messaging.isNotesChat(peerUserID)
    }

    private var isPeerTyping: Bool {
        !isNotes && messaging.typingPeerIDs.contains(peerUserID)
    }

    private var isOnline: Bool {
        !isNotes && messaging.presenceByUser[peerUserID]?.online == true
    }

    private var presenceLabel: String {
        if isNotes { return "Only you · stored on this device" }
        if isPeerTyping { return "typing…" }
        if isOnline { return "online" }
        if messaging.isOffline { return "offline · local copy" }
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
                VStack(spacing: 0) {
                    if isNotes {
                        notesToolbar
                    }
                    ChatComposerView(
                        draft: $draft,
                        recorder: voiceRecorder,
                        onAttach: { showAttach = true },
                        onSend: sendDraft,
                        onRecordStart: startRecording,
                        onRecordCancel: cancelRecording,
                        onRecordSend: sendRecording,
                        onDraftChange: { text in
                            if !isNotes {
                                scheduleTyping(!text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
                            }
                        }
                    )
                }
            }
            .navigationBarBackButtonHidden(true)
            .toolbar(.hidden, for: .navigationBar)
            .toolbarBackground(.hidden, for: .navigationBar)
            .task {
                // Pin immediately if the thread is already in memory, then again after network load.
                pinToBottomToken &+= 1
                await messaging.loadThread(peerUserID: peerUserID)
                pinToBottomToken &+= 1
            }
            .onAppear {
                messaging.setActivePeer(peerUserID)
                // Opening a chat should always start at the newest message (Telegram/Signal/WhatsApp).
                pinToBottomToken &+= 1
            }
            .onDisappear {
                typingTask?.cancel()
                // Leaving the thread throws away an in-flight take and silences playback —
                // there is no mini-player to hand either off to.
                voiceRecorder.cancel()
                VoicePlaybackCoordinator.shared.stop()
                menuAnimationTask?.cancel()
                if !isNotes {
                    messaging.setTyping(peerUserID: peerUserID, isTyping: false)
                }
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
                    onPickImage: { picked in
                        showAttach = false
                        presentMediaCompose([picked])
                    }
                )
                .presentationDetents([.height(420)])
                .presentationDragIndicator(.hidden)
                .presentationBackground(Theme.background)
            }
            // `.current` keeps the library's own encoding — `.automatic` lets the system
            // transcode HEIC to JPEG behind our back, which is a silent quality loss.
            .photosPicker(
                isPresented: $showPhotoPicker,
                selection: $photoPickerItems,
                maxSelectionCount: Self.maxPhotosPerSend,
                selectionBehavior: .ordered,
                matching: .images,
                preferredItemEncoding: .current,
                photoLibrary: .shared()
            )
            .onChange(of: photoPickerItems) { _, items in
                guard !items.isEmpty else { return }
                Task { await loadPickedPhotosForCompose(items) }
            }
            .fullScreenCover(isPresented: $showCamera) {
                CameraPicker { image in
                    showCamera = false
                    guard let image else { return }
                    presentMediaCompose([PickedPhoto(image: image)])
                }
                .ignoresSafeArea()
            }
            .navigationDestination(item: $profileDestination) { dest in
                ContactProfileView(peerUserID: dest.peerUserID, peerUsername: dest.peerUsername)
            }
            .overlay {
                if let focusedMenu {
                    messageMenuOverlay(session: focusedMenu)
                }
            }
            .overlay {
                if let viewingMedia {
                    MediaImageViewerOverlay(
                        items: mediaViewerItems,
                        initialID: viewingMedia.id,
                        onClose: {
                            withAnimation(.easeOut(duration: 0.2)) {
                                self.viewingMedia = nil
                            }
                        },
                        onLoad: { messageID in
                            guard let message = messages.first(where: { $0.id == messageID }) else { return }
                            Task { await messaging.ensureImageLoaded(for: message) }
                        },
                        onComingSoon: { feature in
                            toast = "\(feature) coming soon"
                            scheduleToastClear()
                        }
                    )
                    // Cover chat header + composer + status bar (true Telegram overlay).
                    .ignoresSafeArea()
                    // Grows into place from just under full size — reads as "zoom into the photo".
                    .transition(.scale(scale: 0.94).combined(with: .opacity))
                    .zIndex(50)
                }
            }
            .overlay {
                if let composeDraft {
                    MediaComposeOverlay(
                        photos: composeDraft.photos,
                        peerUsername: peerUsername,
                        onCancel: {
                            withAnimation(.easeOut(duration: 0.2)) {
                                self.composeDraft = nil
                            }
                        },
                        onSend: { caption, quality, edits in
                            let photos = composeDraft.photos
                            withAnimation(.easeOut(duration: 0.15)) {
                                self.composeDraft = nil
                            }
                            Task {
                                await sendPickedPhotos(
                                    photos,
                                    edits: edits,
                                    caption: caption,
                                    quality: quality
                                )
                            }
                        },
                        onAddMore: {
                            pickerAppendsToDraft = true
                            photoPickerItems = []
                            showPhotoPicker = true
                        },
                        onRemovePhoto: { index in
                            removeComposePhoto(at: index)
                        },
                        onComingSoon: { feature in
                            toast = "\(feature) coming soon"
                            scheduleToastClear()
                        }
                    )
                    .ignoresSafeArea()
                    // Compose is a sheet-like surface — it rises from the composer it replaces.
                    .transition(.move(edge: .bottom).combined(with: .opacity))
                    .zIndex(60)
                }
            }
            .overlay {
                if isSendingMedia {
                    ProgressView("Sending photo…")
                        .padding(16)
                        .background(.ultraThinMaterial)
                        .clipShape(RoundedRectangle(cornerRadius: 14, style: .continuous))
                        .transition(.scale(scale: 0.9).combined(with: .opacity))
                }
            }
            .toast($toast)
            .animation(Motion.scrim, value: viewingMedia != nil)
            .animation(Motion.scrim, value: composeDraft != nil)
            .animation(Motion.snappy, value: isSendingMedia)
    }

    private struct ProfileDestination: Identifiable, Hashable {
        let peerUserID: UUID
        let peerUsername: String
        var id: UUID { peerUserID }
    }

    /// Which photo the media overlay opened on (not a navigation destination).
    private struct ViewingMedia: Identifiable {
        let id: UUID
    }

    /// Photos staged for caption + edit + send (Telegram media compose).
    private struct ComposeDraft: Identifiable {
        let id = UUID()
        var photos: [PickedPhoto]
    }

    /// Telegram caps an album at 10; matching that keeps one send from ballooning.
    private static let maxPhotosPerSend = 10

    // MARK: - Top chrome (extends under status bar)

    private var topChrome: some View {
        VStack(spacing: 0) {
            HStack(spacing: 10) {
                Button {
                    if let onBack {
                        onBack()
                    } else {
                        dismiss()
                    }
                } label: {
                    Image(systemName: "chevron.left")
                        .font(.system(size: 18, weight: .semibold))
                        .foregroundStyle(Theme.accent)
                        .frame(width: 34, height: 34)
                        .contentShape(Rectangle())
                }
                .pressable(scale: 0.82)
                .accessibilityLabel("Back")

                Group {
                    if isNotes {
                        HStack(spacing: 10) {
                            notesHeaderAvatar
                            VStack(alignment: .leading, spacing: 1) {
                                Text(peerUsername)
                                    .font(.system(size: 16, weight: .semibold))
                                    .foregroundStyle(Theme.textPrimary)
                                    .lineLimit(1)
                                Text(presenceLabel)
                                    .font(.system(size: 12))
                                    .foregroundStyle(Theme.textSecondary)
                                    .lineLimit(1)
                            }
                            Spacer(minLength: 0)
                        }
                        .frame(maxWidth: .infinity, alignment: .leading)
                    } else {
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
                                            PresenceDot(isTyping: isPeerTyping)
                                                .transition(Motion.iconSwap)
                                        }
                                        Text(presenceLabel)
                                            .font(.system(size: 12))
                                            .foregroundStyle(presenceAccent ? Theme.accent : Theme.textSecondary)
                                            .lineLimit(1)
                                            // "online" → "typing…" swaps in place.
                                            .contentTransition(.opacity)
                                    }
                                    // Presence is the header's only live state — animate every part of it.
                                    .animation(Motion.snappy, value: presenceLabel)
                                    .animation(Motion.snappy, value: isOnline || isPeerTyping)
                                }
                            }
                            .contentShape(Rectangle())
                        }
                        .pressable(scale: 0.98, dimming: 0.12)
                        .frame(maxWidth: .infinity, alignment: .leading)
                    }
                }

                if !isNotes {
                    Button {
                        Task {
                            await calls.startCall(
                                peerUserID: peerUserID,
                                peerUsername: peerUsername,
                                modality: .video
                            )
                            if let err = calls.lastError {
                                toast = err
                                scheduleToastClear()
                            }
                        }
                    } label: {
                        Image(systemName: "video.fill")
                            .font(.system(size: 17, weight: .semibold))
                            .foregroundStyle(Theme.accent)
                            .frame(width: 34, height: 34)
                            .contentShape(Rectangle())
                    }
                    .pressable(scale: 0.82, haptic: .medium)
                    .accessibilityLabel("Video call")

                    Button {
                        Task {
                            await calls.startCall(
                                peerUserID: peerUserID,
                                peerUsername: peerUsername,
                                modality: .voice
                            )
                            if let err = calls.lastError {
                                toast = err
                                scheduleToastClear()
                            }
                        }
                    } label: {
                        Image(systemName: "phone.fill")
                            .font(.system(size: 16, weight: .semibold))
                            .foregroundStyle(Theme.accent)
                            .frame(width: 34, height: 34)
                            .contentShape(Rectangle())
                    }
                    .pressable(scale: 0.82, haptic: .medium)
                    .accessibilityLabel("Call")
                }
            }
            .padding(.horizontal, 16)
            .padding(.top, 6)
            .padding(.bottom, 10)

            Rectangle()
                .fill(Theme.separator)
                .frame(height: 1 / displayScale)
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
                // Non-lazy VStack so the bottom anchor exists as soon as messages are set
                // (LazyVStack often fails first `scrollTo` because the last row is not realized).
                VStack(spacing: 3) {
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
                                // Arriving bubbles grow out of the corner they were "spoken" from.
                                .transition(Motion.bubbleIn(isMine: message.isMine))
                                // Keep layout space while focused so the list doesn’t jump.
                                // Hero sits on this slot at progress 0, so handoff is seamless.
                                .opacity(focusedMenu?.message.id == message.id ? 0 : 1)
                                // UIKit long-press (0.25s). SwiftUI long-press in ScrollView is unreliable.
                                .messageContextLongPress(
                                    minimumDuration: 0.25,
                                    onTap: message.kind == .image
                                        ? { openMediaViewer(for: message) }
                                        : nil
                                ) { rowGlobalFrame in
                                    // Prefer the true bubble frame; fall back to the press row.
                                    let source = bubbleGlobalFrames[message.id] ?? rowGlobalFrame
                                    openMessageMenu(for: message, sourceGlobalFrame: source)
                                }
                        }
                    }

                    if isPeerTyping {
                        TypingIndicatorBubble()
                            .id("typing-indicator")
                    }

                    // Stable end anchor — always scroll here when opening / pinning to newest.
                    Color.clear
                        .frame(height: 1)
                        .id("thread-bottom")
                }
                .padding(.horizontal, 16)
                .padding(.vertical, 12)
                // Drives the bubble insertion transition above. Keyed on the newest id (not
                // just `count`) so a same-count reload still resolves without re-animating
                // the whole thread. The bottom-pin below runs on the same change.
                .animation(Motion.bouncy, value: newestMessageID)
                .animation(Motion.standard, value: isPeerTyping)
                .onPreferenceChange(MessageBubbleFrameKey.self) { frames in
                    bubbleGlobalFrames.merge(frames, uniquingKeysWith: { $1 })
                }
            }
            // Open chats pre-scrolled to newest (iOS 17+), like Telegram/Signal/WhatsApp.
            .defaultScrollAnchor(.bottom)
            .scrollDismissesKeyboard(.interactively)
            .onChange(of: messages.count) { _, _ in
                scrollToBottom(proxy)
            }
            .onChange(of: newestMessageID) { _, _ in
                scrollToBottom(proxy)
            }
            .onChange(of: isPeerTyping) { _, typing in
                if typing { scrollToBottom(proxy) }
            }
            .onChange(of: pinToBottomToken) { _, _ in
                // Opening + post-load: force pin without animation so we never flash the top.
                scrollToBottom(proxy, animated: false, force: true)
            }
            .onAppear {
                scrollToBottom(proxy, animated: false, force: true)
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

    /// Pins the thread to the newest content (bottom).
    /// - Parameter force: When true, retries after layout so open/load always lands on the latest message.
    private func scrollToBottom(
        _ proxy: ScrollViewProxy,
        animated: Bool = true,
        force: Bool = false
    ) {
        let pin = {
            // Prefer the fixed end anchor so Lazy/layout races cannot miss a message id.
            if isPeerTyping {
                proxy.scrollTo("typing-indicator", anchor: .bottom)
            }
            proxy.scrollTo("thread-bottom", anchor: .bottom)
            if let last = messages.last {
                proxy.scrollTo(last.id, anchor: .bottom)
            }
        }
        if animated {
            withAnimation(.easeOut(duration: 0.2), pin)
        } else {
            // Disable implicit animation so open does not animate from the top of the thread.
            var transaction = Transaction()
            transaction.disablesAnimations = true
            withTransaction(transaction, pin)
        }

        guard force else { return }
        // `defaultScrollAnchor` + first layout pass can still leave us mid-thread; re-pin after frames settle.
        Task { @MainActor in
            for delayNs in [16_000_000, 50_000_000, 120_000_000] as [UInt64] {
                try? await Task.sleep(nanoseconds: delayNs)
                var transaction = Transaction()
                transaction.disablesAnimations = true
                withTransaction(transaction) {
                    proxy.scrollTo("thread-bottom", anchor: .bottom)
                    if let last = messages.last {
                        proxy.scrollTo(last.id, anchor: .bottom)
                    }
                }
            }
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

    // MARK: - Voice recording

    /// Begins a take. Returns false so the composer can drop straight back to idle when the
    /// mic is unavailable — otherwise the UI would show a recording that never started.
    private func startRecording() async -> Bool {
        // Playback and recording cannot share the route; a note that is playing must yield.
        VoicePlaybackCoordinator.shared.stop()
        do {
            try await voiceRecorder.start()
            Haptics.impact(.medium)
            // Recording is a clear signal the user wants a transcript, so start fetching the
            // language model now — it downloads while they speak instead of stalling the send.
            // No-op once installed.
            Task.detached(priority: .utility) { await VoiceTranscriber.prepareModel() }
            return true
        } catch {
            toast = SessionController.userMessage(for: error)
            Haptics.notification(.error)
            scheduleToastClear()
            return false
        }
    }

    private func cancelRecording() {
        voiceRecorder.cancel()
    }

    /// Names to bias the recogniser toward — proper nouns are what transcripts most often
    /// get wrong, and in a messenger the likely ones are the people you talk to.
    private var transcriptionHints: [String] {
        var names = [peerUsername]
        names.append(contentsOf: messaging.contacts.map(\.username))
        // Keep the list short; a long bias list dilutes each entry.
        return Array(Set(names)).sorted().prefix(50).map { $0 }
    }

    /// Finishes the take and sends it. A sub-`minimumDuration` take is treated as a mis-tap:
    /// discarded, with a hint instead of an error.
    private func sendRecording() {
        do {
            guard let take = try voiceRecorder.finish() else {
                toast = "Hold to record, release to send"
                Haptics.notification(.warning)
                scheduleToastClear()
                return
            }
            Haptics.impact(.light)
            Task {
                let error = await messaging.sendVoice(
                    audioData: take.data,
                    durationMs: take.durationMs,
                    to: peerUserID,
                    waveform: take.waveform,
                    // Best-effort on-device transcript (Tier 1) — never blocks send on failure.
                    // Runs after the bubble is on screen (see `sendVoice`), so a long recording
                    // appears immediately instead of waiting on the transcriber.
                    transcriptProvider: {
                        try? await VoiceTranscriber.transcribe(
                            audioData: take.data,
                            contextualStrings: transcriptionHints,
                            conversationID: peerUserID
                        )
                    }
                )
                if let error {
                    toast = error
                    Haptics.notification(.error)
                    scheduleToastClear()
                } else {
                    Haptics.notification(.success)
                }
            }
        } catch {
            toast = SessionController.userMessage(for: error)
            Haptics.notification(.error)
            scheduleToastClear()
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
                },
                frameReportID: message.id
            )
        case .voice:
            VoiceMessageBubble(
                message: message,
                time: messaging.clockTimeLabel(for: message.createdAt),
                onAppearLoad: {
                    Task { await messaging.ensureVoiceLoaded(for: message) }
                },
                onRequestTranscript: {
                    guard let data = message.voiceData else { return nil }
                    return try? await VoiceTranscriber.transcribe(
                        audioData: data,
                        contextualStrings: transcriptionHints,
                        conversationID: peerUserID
                    )
                }
            )
        case .text:
            MessageBubbleView(
                text: message.text,
                time: messaging.clockTimeLabel(for: message.createdAt),
                isMine: message.isMine,
                isDeleted: message.deleted,
                receipt: isNotes ? .sent : message.receipt,
                frameReportID: message.id
            )
        case .todo:
            TodoMessageBubble(
                text: message.text,
                time: messaging.clockTimeLabel(for: message.createdAt),
                isDone: message.todoDone == true,
                onToggle: {
                    messaging.toggleTodo(messageID: message.id, peerUserID: peerUserID)
                    Haptics.impact(.light)
                }
            )
        }
    }

    private var notesHeaderAvatar: some View {
        ZStack {
            Circle()
                .fill(
                    LinearGradient(
                        colors: [Theme.accent, Theme.accentSoft],
                        startPoint: .topLeading,
                        endPoint: .bottomTrailing
                    )
                )
                .frame(width: 40, height: 40)
            Image(systemName: "bookmark.fill")
                .font(.system(size: 16, weight: .semibold))
                .foregroundStyle(Color.white)
        }
        .accessibilityHidden(true)
    }

    private var notesToolbar: some View {
        HStack(spacing: 10) {
            Button {
                let text = draft.trimmingCharacters(in: .whitespacesAndNewlines)
                guard !text.isEmpty else {
                    toast = "Type a todo, then tap Todo."
                    scheduleToastClear()
                    return
                }
                messaging.sendTodo(text, to: peerUserID)
                draft = ""
                Haptics.notification(.success)
                pinToBottomToken &+= 1
            } label: {
                Label("Todo", systemImage: "checklist")
                    .font(.system(size: 14, weight: .semibold))
                    .foregroundStyle(Theme.accent)
                    .padding(.horizontal, 12)
                    .padding(.vertical, 8)
                    .background(Theme.backgroundGrouped)
                    .clipShape(Capsule())
            }
            .pressable(scale: 0.94)
            .accessibilityLabel("Add as todo")

            Text("Saved only on this device")
                .font(.system(size: 12))
                .foregroundStyle(Theme.textSecondary)
            Spacer(minLength: 0)
        }
        .padding(.horizontal, 14)
        .padding(.top, 8)
        .padding(.bottom, 2)
        .background(Theme.background)
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

    private func loadPickedPhotosForCompose(_ items: [PhotosPickerItem]) async {
        let appending = pickerAppendsToDraft
        defer {
            photoPickerItems = []
            pickerAppendsToDraft = false
        }

        var picked: [PickedPhoto] = []
        for item in items {
            // The original file's bytes — held encoded until send so "Original" stays original.
            guard let data = try? await item.loadTransferable(type: Data.self) else { continue }
            // Downsample off the main thread; a 48 MP decode would stutter the picker dismissal.
            let preview = await Task.detached(priority: .userInitiated) {
                MediaCrypto.previewImage(from: data, maxEdge: 2048)
            }.value
            guard let preview else { continue }
            picked.append(PickedPhoto(preview: preview, source: .fileData(data)))
        }

        guard !picked.isEmpty else {
            toast = items.count > 1 ? "Could not load those photos." : "Could not load that photo."
            scheduleToastClear()
            return
        }

        if appending, var draft = composeDraft {
            draft.photos = Array((draft.photos + picked).prefix(Self.maxPhotosPerSend))
            withAnimation(Motion.standard) { composeDraft = draft }
        } else {
            presentMediaCompose(picked)
        }
    }

    private func presentMediaCompose(_ picked: [PickedPhoto]) {
        withAnimation(.easeOut(duration: 0.2)) {
            composeDraft = ComposeDraft(photos: Array(picked.prefix(Self.maxPhotosPerSend)))
        }
    }

    private func removeComposePhoto(at index: Int) {
        guard var draft = composeDraft, draft.photos.indices.contains(index) else { return }
        draft.photos.remove(at: index)
        withAnimation(Motion.standard) {
            composeDraft = draft.photos.isEmpty ? nil : draft
        }
    }

    /// Sends the staged photos in order, each with its own edits and the shared caption.
    /// Telegram puts the caption on the first item of an album; this does the same.
    private func sendPickedPhotos(
        _ photos: [PickedPhoto],
        edits: [MediaEdits],
        caption: String = "",
        quality: MediaComposeQuality = .original
    ) async {
        guard !photos.isEmpty else { return }
        isSendingMedia = true
        defer { isSendingMedia = false }

        var firstError: String?
        for (index, photo) in photos.enumerated() {
            let error = await messaging.sendImage(
                photo.source,
                to: peerUserID,
                caption: index == 0 ? caption : "",
                quality: quality,
                edits: edits.indices.contains(index) ? edits[index] : MediaEdits()
            )
            if let error, firstError == nil { firstError = error }
        }

        if let firstError {
            // Bubbles stay in the thread with Retry; also surface the reason.
            toast = firstError
            Haptics.notification(.error)
            // Keep error visible longer so it can be read.
            Task {
                try? await Task.sleep(nanoseconds: 4_000_000_000)
                if toast == firstError { toast = nil }
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

    /// How long after opening the backdrop ignores taps (see `MessageMenuBackdrop` above).
    private static let menuTapGrace: TimeInterval = 0.4

    /// Shared open/close timing — ease-out (fast start → settle at the end).
    private static let messageMenuAnimationDuration: Double = 0.21

    private static let messageMenuAnimation = Animation.easeOut(duration: messageMenuAnimationDuration)

    private struct FocusedMessageMenu: Identifiable {
        var id: UUID { message.id }
        let message: MessagingController.ChatMessage
        /// Cached / already-decoded bitmap — never re-decode during animation.
        let heroImage: UIImage?
        /// Bubble frame in **global** coordinates at long-press (list slot).
        let sourceGlobalFrame: CGRect
    }

    private func openMessageMenu(
        for message: MessagingController.ChatMessage,
        sourceGlobalFrame: CGRect
    ) {
        // Cancel any in-flight dismiss so it can’t clear a freshly opened menu.
        menuAnimationTask?.cancel()
        menuAnimationGeneration &+= 1

        // Cache-only image lookup (no decode on the open path).
        let heroImage: UIImage? = message.kind == .image
            ? DecodedImageCache.image(for: message.id)
            : nil

        // Start at the list slot (progress 0), then lift into the focus stack.
        var reset = Transaction()
        reset.disablesAnimations = true
        withTransaction(reset) {
            menuProgress = 0
            focusedMenu = FocusedMessageMenu(
                message: message,
                heroImage: heroImage,
                sourceGlobalFrame: sourceGlobalFrame
            )
        }

        withAnimation(Self.messageMenuAnimation) {
            menuProgress = 1
        }
        menuOpenedAt = Date()

        Haptics.impact(.medium)
    }

    private func dismissMessageMenu() {
        guard focusedMenu != nil else { return }
        menuAnimationTask?.cancel()
        menuAnimationGeneration &+= 1
        let generation = menuAnimationGeneration

        // Ease-out back to the list slot: fast start, decelerate into place — no teleport.
        withAnimation(Self.messageMenuAnimation) {
            menuProgress = 0
        }

        menuAnimationTask = Task { @MainActor in
            try? await Task.sleep(
                nanoseconds: UInt64(Self.messageMenuAnimationDuration * 1_000_000_000) + 20_000_000
            )
            guard !Task.isCancelled, generation == menuAnimationGeneration else { return }
            // Hero is already exactly on the source frame; swap back to the list bubble.
            var clear = Transaction()
            clear.disablesAnimations = true
            withTransaction(clear) {
                focusedMenu = nil
                menuProgress = 0
            }
        }
    }

    /// Context card width (matches `MessageContextMenuCard`).
    private static let messageMenuCardWidth: CGFloat = 250
    /// Context card row height × action count (mine includes muted “read”).
    private static func messageMenuCardHeight(isMine: Bool) -> CGFloat {
        let rows: CGFloat = isMine ? 7 : 6
        return rows * 44
    }

    private static let messageMenuStackSpacing: CGFloat = 10
    private static let messageMenuChromeHorizontalPad: CGFloat = 12

    /// Clamp a chrome strip so its full width stays inside the overlay.
    private func clampedChromeMinX(
        preferredMinX: CGFloat,
        width: CGFloat,
        containerWidth: CGFloat
    ) -> CGFloat {
        let pad = Self.messageMenuChromeHorizontalPad
        let minX = pad
        let maxX = max(minX, containerWidth - pad - width)
        return min(max(preferredMinX, minX), maxX)
    }

    /// Where the hero sits when fully open: stack centered, X locked to the source bubble.
    private func focusedHeroFrame(
        sourceLocal: CGRect,
        container: CGSize,
        isMine: Bool
    ) -> CGRect {
        let reactionH = MessageReactionBar.barHeight
        let menuH = Self.messageMenuCardHeight(isMine: isMine)
        let spacing = Self.messageMenuStackSpacing
        let stackH = reactionH + spacing + sourceLocal.height + spacing + menuH

        let topPad: CGFloat = 56
        let bottomPad: CGFloat = 48
        let available = max(0, container.height - topPad - bottomPad)
        var stackTop = topPad + max(0, (available - stackH) / 2)
        if stackTop + stackH > container.height - bottomPad {
            stackTop = max(topPad, container.height - bottomPad - stackH)
        }

        let heroY = stackTop + reactionH + spacing
        // Keep the bubble’s horizontal home (mine trailing / peer leading).
        return CGRect(
            x: sourceLocal.minX,
            y: heroY,
            width: sourceLocal.width,
            height: sourceLocal.height
        )
    }

    private func lerp(_ a: CGFloat, _ b: CGFloat, _ t: CGFloat) -> CGFloat {
        a + (b - a) * t
    }

    private func lerpRect(_ a: CGRect, _ b: CGRect, _ t: CGFloat) -> CGRect {
        CGRect(
            x: lerp(a.minX, b.minX, t),
            y: lerp(a.minY, b.minY, t),
            width: lerp(a.width, b.width, t),
            height: lerp(a.height, b.height, t)
        )
    }

    @ViewBuilder
    /// Hero flies between the real list bubble and the focus stack (ease-out both ways).
    private func messageMenuOverlay(session: FocusedMessageMenu) -> some View {
        let message = session.message
        let progress = menuProgress
        let spacing = Self.messageMenuStackSpacing
        let reactionW = MessageReactionBar.barWidth
        let reactionH = MessageReactionBar.barHeight
        let menuW = Self.messageMenuCardWidth
        let menuH = Self.messageMenuCardHeight(isMine: message.isMine)

        GeometryReader { proxy in
            let containerGlobal = proxy.frame(in: .global)
            // Convert captured global bubble frame into this full-screen overlay’s local space.
            let sourceLocal = CGRect(
                x: session.sourceGlobalFrame.minX - containerGlobal.minX,
                y: session.sourceGlobalFrame.minY - containerGlobal.minY,
                width: max(1, session.sourceGlobalFrame.width),
                height: max(1, session.sourceGlobalFrame.height)
            )
            let focusLocal = focusedHeroFrame(
                sourceLocal: sourceLocal,
                container: proxy.size,
                isMine: message.isMine
            )
            // progress 0 = exact list bubble, 1 = focus stack. Close eases into sourceLocal.
            let heroFrame = lerpRect(sourceLocal, focusLocal, progress)

            // Align chrome to the bubble, then clamp so the full bar/card stays on-screen
            // (outgoing bubbles near the trailing edge used to clip the “more” button).
            let preferredReactionX = message.isMine
                ? heroFrame.maxX - reactionW
                : heroFrame.minX
            let preferredMenuX = message.isMine
                ? heroFrame.maxX - menuW
                : heroFrame.minX
            let reactionX = clampedChromeMinX(
                preferredMinX: preferredReactionX,
                width: reactionW,
                containerWidth: proxy.size.width
            )
            let menuX = clampedChromeMinX(
                preferredMinX: preferredMenuX,
                width: menuW,
                containerWidth: proxy.size.width
            )
            let reactionY = heroFrame.minY - spacing - reactionH
            let menuY = heroFrame.maxY + spacing

            ZStack(alignment: .topLeading) {
                MessageMenuBackdrop(
                    onTap: {
                        // The finger that opened the menu is usually still down; its release
                        // lands on this backdrop and would close what the hold just opened.
                        guard let opened = menuOpenedAt,
                              Date().timeIntervalSince(opened) > Self.menuTapGrace
                        else { return }
                        dismissMessageMenu()
                    },
                    progress: progress
                )

                // Reaction + menu track the moving hero and fade with progress.
                MessageReactionBar(
                    onReaction: { emoji in
                        dismissMessageMenu()
                        toast = "Reacted \(emoji)"
                        scheduleToastClear()
                    },
                    onMore: {
                        dismissMessageMenu()
                        showComingSoon("More reactions")
                    },
                    progress: progress
                )
                .frame(width: reactionW, height: reactionH)
                .position(x: reactionX + reactionW / 2, y: reactionY + reactionH / 2)

                MessageMenuHeroContent(
                    message: message,
                    timeLabel: messaging.clockTimeLabel(for: message.createdAt),
                    heroImage: session.heroImage
                )
                // Same size as the list bubble so progress 0 is a perfect handoff.
                .frame(width: heroFrame.width, height: heroFrame.height)
                .position(x: heroFrame.midX, y: heroFrame.midY)
                .allowsHitTesting(false)

                MessageContextMenuCard(
                    isMine: message.isMine,
                    onAction: { action in
                        dismissMessageMenu()
                        handleMenu(action, message: message)
                    },
                    progress: progress
                )
                .frame(width: menuW, height: menuH, alignment: .top)
                .position(x: menuX + menuW / 2, y: menuY + menuH / 2)
            }
            .frame(width: proxy.size.width, height: proxy.size.height)
        }
        .ignoresSafeArea()
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

    /// Every photo in the thread, so the viewer can page through them the way Telegram does.
    ///
    /// Pages that haven't been decrypted yet come through with `image == nil` and load on demand;
    /// their aspect ratio is already known from the message metadata, so nothing reflows.
    private var mediaViewerItems: [MediaImageViewerOverlay.Item] {
        messages
            .filter { $0.kind == .image && !$0.deleted && $0.receipt != .failed }
            .map { message in
                let caption = message.text.trimmingCharacters(in: .whitespacesAndNewlines)
                let width = CGFloat(message.imageWidth ?? 0)
                let height = CGFloat(message.imageHeight ?? 0)
                return MediaImageViewerOverlay.Item(
                    id: message.id,
                    title: message.isMine ? "You" : peerUsername,
                    dateLine: Self.viewerDateLine(for: message.createdAt),
                    caption: caption == "Photo" ? nil : caption,
                    // Cache-only — decoding every photo in the thread here would block the
                    // main thread the moment the viewer opens. Pages decode their own.
                    image: DecodedImageCache.image(for: message.id),
                    data: message.imageData,
                    aspect: height > 0 ? width / height : 1
                )
            }
    }

    /// Presents the Telegram-style media **overlay** over the conversation (not a push).
    private func openMediaViewer(for message: MessagingController.ChatMessage) {
        withAnimation(.easeOut(duration: 0.2)) {
            viewingMedia = ViewingMedia(id: message.id)
        }
        Task { await messaging.ensureImageLoaded(for: message) }
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
