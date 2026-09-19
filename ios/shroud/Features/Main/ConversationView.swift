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
    /// Message waiting on the delete-scope confirmation.
    @State private var pendingDelete: PendingDelete?
    /// Whole-chat delete confirmation (scope is picked in the dialog).
    @State private var showChatDeleteConfirm = false
    /// Live global frames of each bubble (visual only — no row spacers).
    @State private var bubbleGlobalFrames: [UUID: CGRect] = [:]
    @State private var viewingMedia: ViewingMedia?
    /// Full-screen video playback after decrypt.
    @State private var viewingVideo: ViewingVideo?
    @State private var composeDraft: ComposeDraft?
    /// Videos staged for trim + caption + send (Telegram video compose).
    @State private var videoDraft: VideoComposeDraft?
    /// Photos picked in the same session as videos — shown after the video compose closes.
    @State private var photosAfterVideoCompose: [PickedPhoto] = []
    @State private var profileDestination: ProfileDestination?
    @State private var photoPickerItems: [PhotosPickerItem] = []
    @State private var showPhotoPicker = false
    /// True when the picker was opened from compose, so its results append instead of replace.
    @State private var pickerAppendsToDraft = false
    @State private var showCamera = false
    @State private var isSendingMedia = false
    /// Message IDs currently downloading full media (Telegram-style manual download).
    @State private var mediaDownloadIDs: Set<UUID> = []
    /// Bumped after thread load / open so we re-pin to the newest message once layout is ready.
    @State private var pinToBottomToken = 0
    /// Thread width, so bubbles size themselves to the device instead of a fixed column.
    @State private var threadWidth: CGFloat = 0

    private var messages: [MessagingController.ChatMessage] {
        messaging.threads[peerUserID] ?? []
    }

    /// Stable identity of the newest bubble (count alone misses same-count reloads).
    private var newestMessageID: UUID? {
        messages.last?.id
    }

    /// The newest voice notes with nothing newer under them; their transcripts unfold unasked.
    private var transcriptTail: [UUID] {
        VoiceTranscriptDisclosure.tail(of: messages)
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
        chatSurface
            .overlay {
                if let focusedMenu {
                    messageMenuOverlay(session: focusedMenu)
                }
            }
            .overlay { mediaViewerLayer }
            .overlay { photoComposeLayer }
            .overlay { videoComposeLayer }
            .overlay { videoPlayerLayer }
            .overlay { sendingMediaLayer }
            .confirmationDialog(
                "Delete message?",
                isPresented: deleteDialogBinding,
                titleVisibility: .visible,
                presenting: pendingDelete
            ) { pending in
                if canDeleteForEveryone(pending.message) {
                    Button("Delete for everyone", role: .destructive) {
                        performDelete(pending.message, scope: .everyone)
                    }
                }
                Button(isNotes ? "Delete" : "Delete for me", role: .destructive) {
                    performDelete(pending.message, scope: .me)
                }
                Button("Cancel", role: .cancel) { pendingDelete = nil }
            }
            .confirmationDialog(
                isNotes ? "Delete Saved Messages?" : "Delete chat with \(peerUsername)?",
                isPresented: $showChatDeleteConfirm,
                titleVisibility: .visible
            ) {
                if !isNotes {
                    Button("Delete for me and \(peerUsername)", role: .destructive) {
                        performChatDelete(scope: .everyone)
                    }
                }
                Button(isNotes ? "Delete" : "Delete for me", role: .destructive) {
                    performChatDelete(scope: .me)
                }
                Button("Cancel", role: .cancel) {}
            } message: {
                Text(chatDeleteExplanation)
            }
            .toast($toast)
            .animation(Motion.scrim, value: viewingMedia != nil)
            .animation(Motion.scrim, value: viewingVideo != nil)
            .animation(Motion.scrim, value: composeDraft != nil)
            .animation(Motion.scrim, value: videoDraft != nil)
            .animation(Motion.snappy, value: isSendingMedia)
            .onGeometryChange(for: CGFloat.self) { $0.size.width } action: { threadWidth = $0 }
            // Applied last so the list *and* the long-press menu hero size bubbles identically —
            // a mismatch here shows up as the bubble re-wrapping the moment the menu opens.
            .environment(\.chatRowWidth, max(0, threadWidth - Self.threadHorizontalInset * 2))
    }

    private var deleteDialogBinding: Binding<Bool> {
        Binding(
            get: { pendingDelete != nil },
            set: { if !$0 { pendingDelete = nil } }
        )
    }

    /// The thread itself plus its chrome and modal presentations — everything that is *not*
    /// a full-screen layer. Split from `body` to keep either chain inside the type checker's
    /// budget.
    private var chatSurface: some View {
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
                                // Keepalive and idle timing live in `MessagingController`.
                                messaging.setTyping(
                                    peerUserID: peerUserID,
                                    isTyping: !text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
                                )
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
                matching: .any(of: [.images, .videos]),
                preferredItemEncoding: .current,
                photoLibrary: .shared()
            )
            .onChange(of: photoPickerItems) { _, items in
                guard !items.isEmpty else { return }
                Task { await loadPickedMedia(items) }
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
    }

    /// Horizontal inset on the message list; bubbles subtract it to get their row width.
    private static let threadHorizontalInset: CGFloat = 16

    // MARK: - Full-screen layers
    //
    // Each of these is its own property rather than an inline `.overlay { … }`: the body is
    // already a long modifier chain, and folding five presentation surfaces into it pushes the
    // type checker past its budget.

    @ViewBuilder
    private var mediaViewerLayer: some View {
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
                    // Viewer only loads pages that were already downloaded (no silent fetch).
                    guard let message = messages.first(where: { $0.id == messageID }),
                          message.imageData != nil
                    else { return }
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

    @ViewBuilder
    private var photoComposeLayer: some View {
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

    @ViewBuilder
    private var videoComposeLayer: some View {
        if let videoDraft {
            VideoComposeOverlay(
                videos: videoDraft.videos,
                peerUsername: peerUsername,
                onCancel: {
                    videoDraft.videos.forEach { $0.movie.cleanup() }
                    closeVideoCompose()
                },
                onSend: { plans in
                    let movies = videoDraft.videos.map(\.movie)
                    closeVideoCompose()
                    // The bubbles land immediately, so pin before the first encode starts.
                    pinToBottomToken &+= 1
                    Task { await sendVideoPlans(plans, movies: movies) }
                },
                onAddMore: {
                    pickerAppendsToDraft = true
                    photoPickerItems = []
                    showPhotoPicker = true
                },
                onRemoveVideo: { index in
                    removeComposeVideo(at: index)
                }
            )
            .ignoresSafeArea()
            .transition(.move(edge: .bottom).combined(with: .opacity))
            .zIndex(61)
        }
    }

    @ViewBuilder
    private var videoPlayerLayer: some View {
        if let viewingVideo {
            VideoPlayerOverlay(
                data: viewingVideo.data,
                title: viewingVideo.title,
                subtitle: viewingVideo.dateLine,
                onClose: {
                    withAnimation(.easeOut(duration: 0.2)) {
                        self.viewingVideo = nil
                    }
                }
            )
            .ignoresSafeArea()
            .transition(.opacity)
            .zIndex(55)
        }
    }

    @ViewBuilder
    private var sendingMediaLayer: some View {
        if isSendingMedia {
            ProgressView("Sending media…")
                .padding(16)
                .background(.ultraThinMaterial)
                .clipShape(RoundedRectangle(cornerRadius: 14, style: .continuous))
                .transition(.scale(scale: 0.9).combined(with: .opacity))
        }
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

    /// Full-screen video player payload (decrypted bytes).
    private struct ViewingVideo: Identifiable {
        let id: UUID
        let data: Data
        let title: String
        let dateLine: String
    }

    /// Message the delete confirmation is about (scope is picked in the dialog).
    private struct PendingDelete: Identifiable {
        let message: MessagingController.ChatMessage
        var id: UUID { message.id }
    }

    /// Photos staged for caption + edit + send (Telegram media compose).
    private struct ComposeDraft: Identifiable {
        let id = UUID()
        var photos: [PickedPhoto]
    }

    /// Videos staged for trim + mute + caption + send.
    private struct VideoComposeDraft: Identifiable {
        let id = UUID()
        var videos: [PickedVideo]
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
                                        if isPeerTyping {
                                            // "online" → "typing" swaps in place, dots riding the
                                            // same wave as the ink bubble in the thread.
                                            TypingLabel()
                                                .transition(.opacity)
                                        } else {
                                            if isOnline {
                                                PresenceDot()
                                                    .transition(Motion.iconSwap)
                                            }
                                            Text(presenceLabel)
                                                .font(.system(size: 12))
                                                .foregroundStyle(presenceAccent ? Theme.accent : Theme.textSecondary)
                                                .lineLimit(1)
                                                .contentTransition(.opacity)
                                                .transition(.opacity)
                                        }
                                    }
                                    // Presence is the header's only live state — animate every part of it.
                                    .animation(Motion.snappy, value: presenceLabel)
                                    .animation(Motion.snappy, value: isOnline)
                                    .animation(Motion.snappy, value: isPeerTyping)
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

                // Chat-level actions. Notes are local Saved Messages, so they only clear.
                Menu {
                    Button(role: .destructive) {
                        showChatDeleteConfirm = true
                    } label: {
                        Label(
                            isNotes ? "Delete Saved Messages" : "Delete Chat",
                            systemImage: "trash"
                        )
                    }
                } label: {
                    Image(systemName: "ellipsis")
                        .font(.system(size: 17, weight: .semibold))
                        .foregroundStyle(Theme.accent)
                        .frame(width: 30, height: 34)
                        .contentShape(Rectangle())
                }
                .accessibilityLabel("More")
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
                                    onTap: (message.kind == .image || message.kind == .video)
                                        ? {
                                            handleMediaTap(message)
                                        }
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
                .padding(.horizontal, Self.threadHorizontalInset)
                .padding(.vertical, 12)
                // Drives the bubble insertion transition above. Keyed on the newest id (not
                // just `count`) so a same-count reload still resolves without re-animating
                // the whole thread. The bottom-pin below runs on the same change.
                .animation(Motion.bouncy, value: newestMessageID)
                // Springy, so the ink bubble pops out of its tail corner like a message landing.
                .animation(Motion.bouncy, value: isPeerTyping)
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
            .onChange(of: transcriptTail) { old, new in
                // Pushed off the bottom by something newer: forget the reader's choice so the
                // note folds with the rest.
                withAnimation(Motion.standard) {
                    for id in old where !new.contains(id) {
                        VoiceTranscriptDisclosure.shared.clearChoice(for: id)
                    }
                }
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
            if !isNotes {
                messaging.setTyping(peerUserID: peerUserID, isTyping: false)
            }
            Task {
                let error = await messaging.sendVoice(
                    audioData: take.data,
                    durationMs: take.durationMs,
                    to: peerUserID,
                    waveform: take.waveform,
                    // Best-effort on-device transcript (Tier 1) — never blocks send on failure.
                    // Runs after the bubble is on screen (see `sendVoice`), so a long recording
                    // appears immediately instead of waiting on the transcriber.
                    transcriptProvider: { messageID in
                        // Never hold a note back for the one-time Whisper download (hundreds of
                        // megabytes); it keeps going in the background since recording began.
                        guard await VoiceTranscriber.modelIsInstalled() else { return nil }
                        return try? await VoiceTranscriber.transcribe(
                            audioData: take.data,
                            contextualStrings: transcriptionHints,
                            conversationID: peerUserID,
                            tracking: messageID
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
                onDownload: {
                    downloadMedia(message)
                },
                transfer: messaging.mediaTransfers[message.id],
                onCancelDownload: {
                    messaging.cancelMediaDownload(messageID: message.id)
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
        case .video:
            VideoMessageBubble(
                message: message,
                time: messaging.clockTimeLabel(for: message.createdAt),
                onDownload: {
                    downloadMedia(message)
                },
                transfer: messaging.mediaTransfers[message.id],
                onCancelDownload: {
                    messaging.cancelMediaDownload(messageID: message.id)
                },
                onRetry: {
                    Task {
                        // The bubble carries its own ring while retrying — no modal spinner.
                        let error = await messaging.retryFailedVideo(
                            messageID: message.id,
                            peerUserID: peerUserID
                        )
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
                    openVideoPlayer(for: message)
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
                    do {
                        let transcript = try await VoiceTranscriber.transcribe(
                            audioData: data,
                            contextualStrings: transcriptionHints,
                            conversationID: peerUserID,
                            tracking: message.id
                        )
                        if !transcript.isEmpty {
                            // Keep it, and share it so the other side sees it too (web included).
                            Task {
                                await messaging.shareTranscript(
                                    transcript,
                                    forVoiceMessage: message.id,
                                    peerUserID: peerUserID
                                )
                            }
                        }
                        return transcript
                    } catch {
                        toast = error.localizedDescription
                        Haptics.notification(.error)
                        scheduleToastClear()
                        return nil
                    }
                },
                inTranscriptTail: transcriptTail.contains(message.id)
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

    private func loadPickedMedia(_ items: [PhotosPickerItem]) async {
        let appending = pickerAppendsToDraft
        defer {
            photoPickerItems = []
            pickerAppendsToDraft = false
        }

        var pickedPhotos: [PickedPhoto] = []
        var pickedVideos: [PickedVideo] = []

        for item in items {
            if Self.isVideoPickerItem(item) {
                guard let movie = try? await item.loadTransferable(type: PickedMovie.self) else { continue }
                // Probing is metadata-only, so compose opens already knowing the clip.
                guard let probe = await VideoMedia.probe(url: movie.url) else {
                    movie.cleanup()
                    continue
                }
                let poster = await VideoMedia.posterImage(url: movie.url)
                pickedVideos.append(PickedVideo(movie: movie, probe: probe, poster: poster))
                continue
            }
            // The original file's bytes — held encoded until send so "Original" stays original.
            guard let data = try? await item.loadTransferable(type: Data.self) else { continue }
            let preview = await Task.detached(priority: .userInitiated) {
                MediaCrypto.previewImage(from: data, maxEdge: 2048)
            }.value
            guard let preview else { continue }
            pickedPhotos.append(PickedPhoto(preview: preview, source: .fileData(data)))
        }

        guard !pickedPhotos.isEmpty || !pickedVideos.isEmpty else {
            toast = items.count > 1 ? "Could not load those items." : "Could not load that item."
            scheduleToastClear()
            return
        }

        // "Add" from an open compose screen extends that send instead of starting a new one.
        if appending, var draft = videoDraft, !pickedVideos.isEmpty {
            draft.videos = Array((draft.videos + pickedVideos).prefix(Self.maxPhotosPerSend))
            withAnimation(Motion.standard) { videoDraft = draft }
            return
        }
        if appending, var draft = composeDraft, !pickedPhotos.isEmpty {
            draft.photos = Array((draft.photos + pickedPhotos).prefix(Self.maxPhotosPerSend))
            withAnimation(Motion.standard) { composeDraft = draft }
            return
        }

        // Photos and videos get different compose surfaces, so one mixed pick becomes two
        // steps: trim the clips first, then caption the photos.
        if !pickedVideos.isEmpty {
            photosAfterVideoCompose = pickedPhotos
            presentVideoCompose(pickedVideos)
            return
        }
        presentMediaCompose(pickedPhotos)
    }

    private static func isVideoPickerItem(_ item: PhotosPickerItem) -> Bool {
        item.supportedContentTypes.contains { type in
            type.conforms(to: .movie) || type.conforms(to: .video) || type.conforms(to: .mpeg4Movie)
        }
    }

    /// Sends the composed clips in order. Each bubble carries its own progress ring, so there
    /// is no modal spinner here — the thread stays usable while a long clip encodes.
    private func sendVideoPlans(_ plans: [VideoSendPlan], movies: [PickedMovie]) async {
        guard !plans.isEmpty else { return }
        defer { movies.forEach { $0.cleanup() } }

        var firstError: String?
        for plan in plans {
            let error = await messaging.sendVideo(plan, to: peerUserID)
            if let error, firstError == nil { firstError = error }
        }

        if let firstError {
            toast = firstError
            Haptics.notification(.error)
            Task {
                try? await Task.sleep(nanoseconds: 4_000_000_000)
                if toast == firstError { toast = nil }
            }
        } else {
            Haptics.notification(.success)
        }
    }

    private func presentMediaCompose(_ picked: [PickedPhoto]) {
        withAnimation(.easeOut(duration: 0.2)) {
            composeDraft = ComposeDraft(photos: Array(picked.prefix(Self.maxPhotosPerSend)))
        }
    }

    private func presentVideoCompose(_ picked: [PickedVideo]) {
        withAnimation(.easeOut(duration: 0.2)) {
            videoDraft = VideoComposeDraft(videos: Array(picked.prefix(Self.maxPhotosPerSend)))
        }
    }

    /// Closes the video compose and hands any photos from the same pick to the photo compose.
    private func closeVideoCompose() {
        withAnimation(.easeOut(duration: 0.2)) { videoDraft = nil }
        guard !photosAfterVideoCompose.isEmpty else { return }
        let photos = photosAfterVideoCompose
        photosAfterVideoCompose = []
        Task {
            // Let the video surface finish leaving before the photo one arrives.
            try? await Task.sleep(for: .milliseconds(260))
            presentMediaCompose(photos)
        }
    }

    private func removeComposePhoto(at index: Int) {
        guard var draft = composeDraft, draft.photos.indices.contains(index) else { return }
        draft.photos.remove(at: index)
        withAnimation(Motion.standard) {
            composeDraft = draft.photos.isEmpty ? nil : draft
        }
    }

    private func removeComposeVideo(at index: Int) {
        guard var draft = videoDraft, draft.videos.indices.contains(index) else { return }
        let removed = draft.videos.remove(at: index)
        removed.movie.cleanup()
        if draft.videos.isEmpty {
            closeVideoCompose()
        } else {
            withAnimation(Motion.standard) { videoDraft = draft }
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
                    heroImage: session.heroImage,
                    inTranscriptTail: transcriptTail.contains(message.id)
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
            pendingDelete = PendingDelete(message: message)
        }
    }

    /// Delete-for-everyone is the sender's call only, and only once the server has the
    /// message — the server rejects anything else.
    private func canDeleteForEveryone(_ message: MessagingController.ChatMessage) -> Bool {
        !isNotes
            && message.isMine
            && !message.deleted
            && !message.pendingSync
            && message.receipt != .failed
    }

    private func performDelete(
        _ message: MessagingController.ChatMessage,
        scope: MessageDeleteScope
    ) {
        pendingDelete = nil
        Task {
            if let error = await messaging.deleteMessage(message, scope: scope) {
                toast = error
                Haptics.notification(.error)
            } else {
                toast = scope == .everyone ? "Deleted for everyone" : "Deleted"
                Haptics.notification(.success)
            }
            scheduleToastClear()
        }
    }

    /// Spells out the asymmetric outcome before the tap: "for both" always disconnects the
    /// two accounts, but the peer's own messages only vanish if they allowed that.
    private var chatDeleteExplanation: String {
        if isNotes {
            return "Removes every saved message from this device and your account."
        }
        return """
        Deleting for both unsends your messages in \(peerUsername)'s chat and removes them as \
        a contact — you'd both have to add each other again. Their own messages stay unless \
        they allow chats to be cleared for them.
        """
    }

    /// Deletes the whole thread and leaves the screen — there is nothing left to show here.
    private func performChatDelete(scope: ConversationDeleteScope) {
        showChatDeleteConfirm = false
        Task {
            let outcome = await messaging.deleteConversation(peerUserID: peerUserID, scope: scope)
            if case let .failed(message) = outcome {
                toast = message
                Haptics.notification(.error)
                scheduleToastClear()
                return
            }
            Haptics.notification(.success)
            if let onBack {
                onBack()
            } else {
                dismiss()
            }
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

    /// Tap on media: download if needed, otherwise open.
    private func handleMediaTap(_ message: MessagingController.ChatMessage) {
        if message.needsMediaDownload {
            downloadMedia(message)
            return
        }
        switch message.kind {
        case .image:
            openMediaViewer(for: message)
        case .video:
            openVideoPlayer(for: message)
        default:
            break
        }
    }

    /// Explicit full-blob download (never runs on scroll/appear).
    private func downloadMedia(_ message: MessagingController.ChatMessage) {
        guard message.needsMediaDownload else { return }
        guard !mediaDownloadIDs.contains(message.id) else { return }
        mediaDownloadIDs.insert(message.id)
        Task {
            defer { mediaDownloadIDs.remove(message.id) }
            switch message.kind {
            case .image:
                await messaging.ensureImageLoaded(for: message)
                let live = messaging.threads[peerUserID]?.first(where: { $0.id == message.id })
                if live?.imageData == nil {
                    toast = "Could not download that photo."
                    Haptics.notification(.error)
                    scheduleToastClear()
                } else {
                    Haptics.impact(.light)
                }
            case .video:
                await messaging.ensureVideoLoaded(for: message)
                let live = messaging.threads[peerUserID]?.first(where: { $0.id == message.id })
                if live?.videoData == nil {
                    toast = "Could not download that video."
                    Haptics.notification(.error)
                    scheduleToastClear()
                } else {
                    Haptics.impact(.light)
                }
            default:
                break
            }
        }
    }

    /// Presents the Telegram-style media **overlay** over the conversation (not a push).
    private func openMediaViewer(for message: MessagingController.ChatMessage) {
        guard message.imageData != nil else {
            downloadMedia(message)
            return
        }
        withAnimation(.easeOut(duration: 0.2)) {
            viewingMedia = ViewingMedia(id: message.id)
        }
    }

    private func openVideoPlayer(for message: MessagingController.ChatMessage) {
        Task {
            let live = messaging.threads[peerUserID]?.first(where: { $0.id == message.id }) ?? message
            guard let data = live.videoData, !data.isEmpty else {
                downloadMedia(message)
                return
            }
            withAnimation(.easeOut(duration: 0.2)) {
                viewingVideo = ViewingVideo(
                    id: message.id,
                    data: data,
                    title: message.isMine ? "You" : peerUsername,
                    dateLine: Self.viewerDateLine(for: message.createdAt)
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
