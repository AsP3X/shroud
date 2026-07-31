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

    @State private var draft = ""
    @State private var typingTask: Task<Void, Never>?
    @State private var showAttach = false
    @State private var isRecording = false
    @State private var recordingSeconds = 0
    @State private var recordingTimer: Timer?
    @State private var voiceRecorder = VoiceRecorder()
    @State private var toast: String?
    /// Active long-press focus session.
    @State private var focusedMenu: FocusedMessageMenu?
    /// 0 = list slot, 1 = focus stack. Single source of truth for open+close motion.
    @State private var menuProgress: CGFloat = 0
    @State private var menuAnimationTask: Task<Void, Never>?
    @State private var menuAnimationGeneration = 0
    /// Live global frames of each bubble (visual only — no row spacers).
    @State private var bubbleGlobalFrames: [UUID: CGRect] = [:]
    @State private var viewingMedia: ViewingMedia?
    @State private var composeDraft: ComposeDraft?
    @State private var profileDestination: ProfileDestination?
    @State private var photoPickerItem: PhotosPickerItem?
    @State private var showPhotoPicker = false
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
                recordingTimer?.invalidate()
                menuAnimationTask?.cancel()
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
                if let focusedMenu {
                    messageMenuOverlay(session: focusedMenu)
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
                    if let onBack {
                        onBack()
                    } else {
                        dismiss()
                    }
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
                        .frame(width: 28, height: 28)
                        .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
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

    private func startRecordingUI() {
        Task {
            do {
                try await voiceRecorder.start()
                isRecording = true
                recordingSeconds = 0
                recordingTimer?.invalidate()
                recordingTimer = Timer.scheduledTimer(withTimeInterval: 1, repeats: true) { _ in
                    recordingSeconds = voiceRecorder.elapsedSeconds
                }
                Haptics.impact(.medium)
            } catch {
                toast = SessionController.userMessage(for: error)
                scheduleToastClear()
            }
        }
    }

    private func stopRecording(discard: Bool) -> () -> Void {
        {
            recordingTimer?.invalidate()
            recordingTimer = nil
            isRecording = false
            recordingSeconds = 0
            do {
                let result = try voiceRecorder.stop(discard: discard)
                if discard {
                    Haptics.notification(.warning)
                    return
                }
                guard let result else { return }
                Haptics.impact(.light)
                Task {
                    // Best-effort on-device transcript (Tier 1) — never blocks send on failure.
                    var transcript: String?
                    if let text = try? await VoiceTranscriber.transcribe(audioData: result.data),
                       !text.isEmpty
                    {
                        transcript = text
                    }
                    let error = await messaging.sendVoice(
                        audioData: result.data,
                        durationMs: result.durationMs,
                        to: peerUserID,
                        transcript: transcript
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
                scheduleToastClear()
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
                    return try? await VoiceTranscriber.transcribe(audioData: data)
                }
            )
        case .text:
            MessageBubbleView(
                text: message.text,
                time: messaging.clockTimeLabel(for: message.createdAt),
                isMine: message.isMine,
                isDeleted: message.deleted,
                receipt: message.receipt,
                frameReportID: message.id
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
        quality: MediaComposeQuality = .original
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
                    onTap: { dismissMessageMenu() },
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
