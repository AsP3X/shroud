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
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    @State private var draft = ""
    @State private var showAttach = false
    /// Owns the mic session for this thread. The composer only reads its live state.
    @State private var voiceRecorder = VoiceRecorder()
    @State private var toast: String?
    /// A reaction on its way from the bar or a double tap to its chip (`ReactionFlight`).
    @State private var reactionFlight: ReactionFlight?
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
    /// Live global frames of each bubble (visual only — no row spacers). Only read when a menu
    /// opens, so it lives in a reference type: as `@State` every scroll frame and every frame of
    /// the back swipe redrew the whole thread, and the redraw re-fired the preference in the
    /// same frame ("Bound preference MessageBubbleFrameKey tried to update multiple times").
    @State private var bubbleFrames = BubbleFrameStore()
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
    /// Message being answered. The composer shows its quote until the reply is sent or dropped.
    @State private var replyTarget: MessagingController.ChatMessage?
    /// Bumped to raise the keyboard when a reply starts.
    @State private var composerFocusToken = 0
    /// Quoted message the thread should scroll to (nonce so the same one can be tapped twice).
    @State private var jumpTarget: JumpTarget?
    @State private var jumpNonce = 0
    /// Row flashing after a jump, so the eye finds the message it landed on.
    @State private var highlightedMessageID: UUID?
    @State private var highlightTask: Task<Void, Never>?
    /// Link preview for the first link in the draft (Telegram's strip above the composer).
    @State private var linkComposer = LinkPreviewComposer()
    /// Oldest rendered message; moves up as the reader nears the top. Pinned to a message, not a
    /// count, so a page landing above or a message arriving below never adds or drops rows on
    /// its own (either would shift the thread under the reader, or under a send's scroll).
    @State private var renderFrom: RenderAnchor?
    /// Drives the thread's offset when rows are added above the reader.
    @State private var threadScroll = ScrollPosition(edge: .bottom)
    /// Scroll geometry, kept out of view state so scrolling doesn't redraw the thread.
    @State private var scrollState = ThreadScrollState()

    private var messages: [MessagingController.ChatMessage] {
        messaging.threads[peerUserID] ?? []
    }

    /// Index of the oldest rendered message; everything before it waits for the reader.
    private var renderStart: Int {
        let fallback = max(0, messages.count - Self.renderWindow)
        guard let renderFrom else { return fallback }
        // From the end: the anchor is usually near the newest messages.
        if let index = messages.lastIndex(where: { $0.id == renderFrom.id }) { return index }
        // Gone (deleted just for us): start at whatever took its place in time.
        return messages.firstIndex(where: { $0.createdAt >= renderFrom.createdAt }) ?? fallback
    }

    /// Older messages in memory but held back from the thread until the reader scrolls up.
    private var hiddenCount: Int {
        renderStart
    }

    /// Moves the top of the rendered window to `index`.
    private func moveRenderStart(to index: Int) {
        guard messages.indices.contains(index) else { return }
        let message = messages[index]
        renderFrom = RenderAnchor(id: message.id, createdAt: message.createdAt)
    }

    /// The server may have more above what is in memory (and the chat has started).
    private var hasOlderOnServer: Bool {
        !messages.isEmpty && messaging.hasOlderHistory(for: peerUserID)
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

    private var peerActivity: ChatPeerActivity? {
        guard !isNotes else { return nil }
        return messaging.peerActivity(for: peerUserID)
    }

    private var isOnline: Bool {
        !isNotes && messaging.presenceByUser[peerUserID]?.online == true
    }

    private var presenceLabel: String {
        if isNotes { return "Only you · stored on this device" }
        if let peerActivity { return "\(peerActivity.label)…" }
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

    /// The edge swipe pops the whole chat, so it waits while something else owns the screen: the
    /// message menu, a voice take, and the in-screen overlays (viewer, editors, player), whose
    /// own drags start at the left edge too.
    private var allowsSwipeBack: Bool {
        focusedMenu == nil && !voiceRecorder.isRecording
            && viewingMedia == nil && viewingVideo == nil
            && composeDraft == nil && videoDraft == nil && !isSendingMedia
    }

    private var presenceAccent: Bool {
        peerActivity != nil || isOnline
    }

    var body: some View {
        chatSurface
            .overlay {
                if let focusedMenu {
                    messageMenuOverlay(session: focusedMenu)
                }
            }
            // Above the menu: a pick leaves the bar while the menu is still fading out.
            .overlay { ReactionFlightLayer(flight: reactionFlight) }
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
            // Links in bubbles (and previews) open in the in-app browser, like Telegram's.
            .environment(\.openURL, OpenURLAction { url in
                openLink(url) ? .handled : .discarded
            })
            .frame(maxWidth: .infinity, maxHeight: .infinity)
            .background(Theme.backgroundChat)
            // Glass bars: the thread scrolls under both and the scroll edge effect fades it.
            .glassTopBar {
                topChrome
            }
            .glassBottomBar {
                VStack(spacing: 8) {
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
                            linkComposer.draftChanged(text)
                            if !isNotes {
                                // Keepalive and idle timing live in `MessagingController`.
                                messaging.setTyping(
                                    peerUserID: peerUserID,
                                    isTyping: !text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
                                )
                            }
                        },
                        reply: composerReply,
                        onTapReply: {
                            if let replyTarget { jumpToQuoted(replyTarget.id) }
                        },
                        onCancelReply: { clearReply() },
                        focusToken: composerFocusToken,
                        linkBar: composerLinkBar,
                        linkShowsAboveText: linkComposer.showsAboveText,
                        linkCanToggleImageSize: linkComposer.canToggleImageSize,
                        linkUsesLargeImage: linkComposer.usesLargeImage,
                        onToggleLinkAboveText: { linkComposer.toggleShowsAboveText() },
                        onToggleLinkImageSize: { linkComposer.toggleImageSize() },
                        onRemoveLinkPreview: {
                            Haptics.impact(.light)
                            linkComposer.dismiss()
                        }
                    )
                }
            }
            .navigationBarBackButtonHidden(true)
            .toolbar(.hidden, for: .navigationBar)
            .toolbarBackground(.hidden, for: .navigationBar)
            // Hiding the bar also kills the system edge swipe; bring it back.
            .interactivePopGesture(enabled: allowsSwipeBack)
            .task {
                // Pin immediately if the thread is already in memory, then again after network load.
                pinToBottomToken &+= 1
                await messaging.loadThread(peerUserID: peerUserID, reconcile: true)
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
                highlightTask?.cancel()
                linkComposer.reset()
                voiceRecorder.cancel()
                VoicePlaybackCoordinator.shared.stop()
                menuAnimationTask?.cancel()
                if !isNotes {
                    messaging.setTyping(peerUserID: peerUserID, isTyping: false)
                    messaging.setRecording(peerUserID: peerUserID, isRecording: false)
                }
                if messaging.activePeerID == peerUserID {
                    messaging.setActivePeer(nil)
                }
            }
            .onReceive(NotificationCenter.default.publisher(for: .shroudCallMediaStarting)) { _ in
                if voiceRecorder.isRecording { cancelRecording() }
                VoicePlaybackCoordinator.shared.stop()
            }
            .onChange(of: voiceRecorder.isRecording) { _, recording in
                if !isNotes {
                    messaging.setRecording(peerUserID: peerUserID, isRecording: recording)
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
            .onChange(of: messaging.reactionFailure) { _, failure in
                guard let failure else { return }
                toast = failure.message
                Haptics.notification(.error)
                scheduleToastClear()
            }
            .fullScreenCover(isPresented: $showCamera) {
                CameraPicker { capture in
                    showCamera = false
                    guard let capture else { return }
                    switch capture {
                    case .photo(let image):
                        presentMediaCompose([PickedPhoto(image: image)])
                    case .movie(let movie):
                        Task { await presentCapturedMovie(movie) }
                    }
                }
                .ignoresSafeArea()
            }
            .navigationDestination(item: $profileDestination) { dest in
                ContactProfileView(peerUserID: dest.peerUserID, peerUsername: dest.peerUsername)
            }
    }

    /// Horizontal inset on the message list; bubbles subtract it to get their row width.
    private static let threadHorizontalInset: CGFloat = 16
    /// Newest messages rendered when a chat opens; older ones are added as the reader scrolls up.
    private static let renderWindow = 60
    /// Rows added above the reader each time they near the top.
    private static let renderStep = 50
    /// How close to the top (pt) the reader gets before older rows are added or fetched.
    private static let revealSlack: CGFloat = 600
    /// Scroll to a message landing at the bottom.
    private static let followAnimation = Animation.easeOut(duration: 0.25)

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
                    let reference = outgoingReplyReference
                    clearReply()
                    withAnimation(.easeOut(duration: 0.15)) {
                        self.composeDraft = nil
                    }
                    Task {
                        await sendPickedPhotos(
                            photos,
                            edits: edits,
                            caption: caption,
                            quality: quality,
                            replyTo: reference
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
                    let reference = outgoingReplyReference
                    clearReply()
                    closeVideoCompose()
                    // The bubbles land immediately, so pin before the first encode starts.
                    pinToBottomToken &+= 1
                    Task { await sendVideoPlans(plans, movies: movies, replyTo: reference) }
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
                .glassEffect(.regular, in: .rect(cornerRadius: 14))
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

    // MARK: - Top chrome (Liquid Glass bar)

    /// Back, the contact (avatar + name + presence), and the call / more controls.
    ///
    /// Human: Only the controls carry glass; the contact block is plain so the name reads
    /// like a title. Video and Call fuse into one capsule, the way the system toolbar groups
    /// neighbouring items. There is no backdrop: `glassTopBar` fades the thread under it.
    /// Agent: RETURNS the bar row; presence animation lives on the centre block.
    private var topChrome: some View {
        GlassBarRow(centersTitle: false) {
            GlassBarButton(systemImage: "chevron.left") {
                if let onBack {
                    onBack()
                } else {
                    dismiss()
                }
            }
            .accessibilityLabel("Back")
        } center: {
            headerContact
        } trailing: {
            if !isNotes {
                GlassBarGroup {
                    GlassBarButton(systemImage: "video.fill", haptic: .medium) {
                        startCall(.video)
                    }
                    .accessibilityLabel("Video call")

                    GlassBarButton(systemImage: "phone.fill", haptic: .medium) {
                        startCall(.voice)
                    }
                    .accessibilityLabel("Call")
                }
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
                    .font(GlassBarMetrics.glyphFont)
                    .foregroundStyle(Theme.accent)
                    .frame(width: GlassBarMetrics.controlSize, height: GlassBarMetrics.controlSize)
                    .contentShape(Circle())
            }
            .glassEffect(.regular.interactive(), in: .circle)
            .accessibilityLabel("More")
        }
    }

    /// The bar's centre: Notes' bookmark, or the peer (tap opens the profile).
    @ViewBuilder
    private var headerContact: some View {
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
                            if let peerActivity {
                                // "online" → "typing" / "recording" swaps in place,
                                // its glyph moving in step with the thread bubble.
                                TypingLabel(activity: peerActivity)
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
                        .animation(Motion.snappy, value: peerActivity)
                    }
                    Spacer(minLength: 0)
                }
                .contentShape(Rectangle())
            }
            .pressable(scale: 0.98, dimming: 0.12)
        }
    }

    /// Places a call to the peer; a failure shows as a toast.
    private func startCall(_ modality: CallModality) {
        Task {
            await calls.startCall(
                peerUserID: peerUserID,
                peerUsername: peerUsername,
                modality: modality
            )
            if let err = calls.lastError {
                toast = err
                scheduleToastClear()
            }
        }
    }

    // MARK: - Messages

    private var messageList: some View {
        // Resolved once per pass; every reply header reads from it.
        let quoted = quotedMessagesByID
        return ScrollViewReader { proxy in
            ScrollView {
                // Telegram-like density: tighter gaps between bubbles.
                // Non-lazy VStack so the bottom anchor exists as soon as messages are set
                // (LazyVStack often fails first `scrollTo` because the last row is not realized).
                VStack(spacing: 3) {
                    // The E2E notice marks the start of the chat, so it waits until the
                    // oldest message is on screen; until then the top row loads more.
                    if hiddenCount > 0 || hasOlderOnServer {
                        olderHistoryRow
                    } else {
                        headerChips
                            .padding(.bottom, 6)
                    }

                    ForEach(groupedTimeline, id: \.id) { item in
                        switch item {
                        case let .date(label, id):
                            ChatDateChip(label: label)
                                .id(id)
                                .padding(.vertical, 8)
                        case let .message(message):
                            // Redraws only when this bubble's own content changes.
                            let key = rowKey(for: message, quoted: quoted)
                            EquatableMessageRow(key: key) {
                                messageRow(message, key: key)
                            }
                                .equatable()
                                .id(message.id)
                                // Flashes after a jump from a reply header, full-bleed so the
                                // eye catches the row rather than the bubble alone.
                                .background {
                                    if highlightedMessageID == message.id {
                                        Rectangle()
                                            .fill(Theme.accent.opacity(0.14))
                                            .padding(.horizontal, -Self.threadHorizontalInset)
                                            .padding(.vertical, -1.5)
                                            .allowsHitTesting(false)
                                            .transition(.opacity)
                                    }
                                }
                                // The hold that opens the menu ends with the finger lifting off
                                // this bubble. Buttons and tap gestures inside it (a link
                                // preview, a photo, a play button) fire on that release, so the
                                // photo or page used to open over the fresh menu. Disabling the
                                // bubble while its menu is up cancels them; the gestures below sit
                                // outside and have already done their job.
                                .disabled(focusedMenu?.message.id == message.id)
                                // Swipe left to answer it (Telegram). Disabled while the
                                // context menu owns the screen, and for bubbles the peer
                                // could not resolve yet.
                                .swipeToReply(
                                    isEnabled: message.canBeQuoted && focusedMenu == nil,
                                    isMine: message.isMine
                                ) {
                                    startReply(to: message)
                                }
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
                                        : nil,
                                    // Quick reaction on text only: other bubbles have controls
                                    // of their own that would take both taps too.
                                    onDoubleTap: message.kind == .text && messaging.canReact(to: message)
                                        ? { point in
                                            quickReact(message, at: point)
                                        }
                                        : nil
                                ) { rowGlobalFrame in
                                    openMessageMenu(
                                        for: message,
                                        sourceGlobalFrame: Self.menuSourceFrame(
                                            bubble: bubbleFrames.frames[message.id],
                                            row: rowGlobalFrame,
                                            isMine: message.isMine
                                        )
                                    )
                                }
                        }
                    }

                    if let peerActivity {
                        TypingIndicatorBubble(activity: peerActivity)
                            .id("typing-indicator")
                    }

                    // Keeps the thread's bottom spacing; pins scroll to the content edge below it.
                    Color.clear
                        .frame(height: 1)
                        .id("thread-bottom")
                }
                .padding(.horizontal, Self.threadHorizontalInset)
                .padding(.vertical, 12)
                // Drives the bubble insertion transition above. Keyed on the newest id (not
                // just `count`) so a same-count reload still resolves without re-animating
                // the whole thread. The bottom-pin below runs on the same change. Not for the
                // first messages shown: a chat opens settled, it doesn't pop in bubble by bubble.
                .animation(renderFrom == nil ? nil : Motion.bouncy, value: newestMessageID)
                // Springy, so the ink bubble pops out of its tail corner like a message landing.
                .animation(Motion.bouncy, value: peerActivity)
                // Chips spring in and out and bubbles grow instead of jumping — for our taps and
                // for the other side's reactions arriving over the socket alike.
                .animation(
                    Motion.respecting(reduceMotion, Motion.bouncy),
                    value: messaging.reactionRevision(for: peerUserID)
                )
                .environment(\.reactionFlightTarget, reactionFlight?.target)
                .onPreferenceChange(ReactionFlightFrameKey.self) { frame in
                    landReactionFlight(at: frame)
                }
                .onPreferenceChange(MessageBubbleFrameKey.self) { frames in
                    bubbleFrames.frames.merge(frames, uniquingKeysWith: { $1 })
                }
            }
            // Open chats pre-scrolled to newest, like Telegram/Signal/WhatsApp, and sit a short
            // thread on the composer. Not for size changes: that role snaps to the new bottom
            // the instant a message lands, so a send jumped instead of scrolling. Following the
            // bottom is done below, animated.
            .defaultScrollAnchor(.bottom, for: .initialOffset)
            .defaultScrollAnchor(.bottom, for: .alignment)
            .scrollPosition($threadScroll)
            .scrollDismissesKeyboard(.interactively)
            .onScrollGeometryChange(for: ThreadScrollMetrics.self) { geometry in
                ThreadScrollMetrics(
                    contentHeight: geometry.contentSize.height,
                    offsetY: geometry.contentOffset.y,
                    nearTop: geometry.contentOffset.y + geometry.contentInsets.top < Self.revealSlack,
                    topInset: geometry.contentInsets.top,
                    // Offsets here start under the top inset (see `scrollTo(y:)` below).
                    atBottom: geometry.contentOffset.y >= geometry.contentSize.height
                        - geometry.containerSize.height - geometry.contentInsets.top - 2,
                    containerHeight: geometry.containerSize.height
                )
            } action: { old, new in
                scrollState.metrics = new
                // Rows went in above the reader: put back the distance to the bottom so the
                // message they were reading stays where it was.
                if let hold = scrollState.holdFromBottom, new.contentHeight != old.contentHeight {
                    scrollState.holdFromBottom = nil
                    // `scrollTo(y:)` counts from under the top inset; `contentOffset` doesn't.
                    threadScroll.scrollTo(y: new.contentHeight - hold + new.topInset)
                    scrollState.staleOffset = new.offsetY
                    return
                }
                // Until the restore lands, the offset is the old one and still reads "near
                // the top"; revealing on it would add a second batch against a stale hold.
                if let stale = scrollState.staleOffset {
                    guard new.offsetY != stale else { return }
                    scrollState.staleOffset = nil
                }
                // Reading the newest message: stay on it. Content growing under it (a send, an
                // arrival, a transcript unfolding) scrolls along; the keyboard just re-pins.
                if old.atBottom, renderFrom != nil {
                    if new.contentHeight > old.contentHeight {
                        withAnimation(Self.followAnimation) { threadScroll.scrollTo(edge: .bottom) }
                    } else if new.containerHeight != old.containerHeight {
                        threadScroll.scrollTo(edge: .bottom)
                    }
                }
                if new.nearTop { revealOlder() }
            }
            .onChange(of: messages.isEmpty, initial: true) { _, isEmpty in
                // Freeze the window on the first messages shown; it only grows from here.
                if renderFrom == nil, !isEmpty { moveRenderStart(to: renderStart) }
            }
            .onChange(of: messages.count) { _, _ in
                // An older page landed (or a send went out) while the reader sits at the top.
                revealOlder()
            }
            .onChange(of: newestMessageID) { old, _ in
                // The first messages shown are already at the bottom; only later ones scroll.
                scrollToBottom(animated: old != nil, force: old == nil)
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
            .onChange(of: peerActivity) { _, activity in
                if activity != nil { scrollToBottom() }
            }
            .onChange(of: jumpTarget) { _, target in
                guard let target else { return }
                withAnimation(Motion.standard) {
                    proxy.scrollTo(target.id, anchor: .center)
                }
                flashHighlight(target.id)
                jumpTarget = nil
            }
            .onChange(of: pinToBottomToken) { _, _ in
                // Opening + post-load: force pin without animation so we never flash the top.
                scrollToBottom(animated: false, force: true)
            }
            .onAppear {
                scrollToBottom(animated: false, force: true)
            }
        }
    }

    /// Top of a thread that goes back further: a spinner while an older page is fetched,
    /// the same height either way so rows below it don't shift when it stops.
    private var olderHistoryRow: some View {
        ZStack {
            if hiddenCount == 0, messaging.isLoadingOlderHistory(for: peerUserID) {
                ProgressView()
                    .controlSize(.small)
                    .accessibilityLabel("Loading earlier messages")
            }
        }
        .frame(maxWidth: .infinity, minHeight: 28)
    }

    /// Near the top: render the next older rows already in memory, or fetch another page.
    /// Re-checked as pages land and as the added rows settle, so a thread shorter than the
    /// screen keeps filling until it isn't.
    private func revealOlder() {
        let state = scrollState
        guard state.settled, state.pinning == 0, state.metrics.nearTop,
              state.holdFromBottom == nil, state.staleOffset == nil
        else { return }
        let start = renderStart
        if start > 0 {
            state.holdFromBottom = state.metrics.contentHeight - state.metrics.offsetY
            moveRenderStart(to: max(0, start - Self.renderStep))
        } else if hasOlderOnServer, !messaging.isLoadingOlderHistory(for: peerUserID) {
            Task { await messaging.loadOlderMessages(peerUserID: peerUserID) }
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
        for message in messages[renderStart...] {
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
    private func scrollToBottom(animated: Bool = true, force: Bool = false) {
        // The content's real end (typing bubble and bottom padding included). Scrolling to the
        // last bubble stopped 16 pt short, which also read as "not at the bottom" afterwards.
        let pin = { threadScroll.scrollTo(edge: .bottom) }
        if animated {
            withAnimation(Self.followAnimation, pin)
        } else {
            // Disable implicit animation so open does not animate from the top of the thread.
            var transaction = Transaction()
            transaction.disablesAnimations = true
            withTransaction(transaction, pin)
        }

        guard force else { return }
        // `defaultScrollAnchor` + first layout pass can still leave us mid-thread; re-pin after frames settle.
        scrollState.pinning += 1
        Task { @MainActor in
            for delayNs in [16_000_000, 50_000_000, 120_000_000] as [UInt64] {
                try? await Task.sleep(nanoseconds: delayNs)
                var transaction = Transaction()
                transaction.disablesAnimations = true
                withTransaction(transaction, pin)
            }
            // Only now is "near the top" the reader's doing and not the first layout pass.
            scrollState.pinning -= 1
            scrollState.settled = true
            revealOlder()
        }
    }

    // MARK: - Replies

    /// One scroll request. The nonce lets the same quote be tapped twice in a row.
    private struct JumpTarget: Equatable {
        let id: UUID
        let nonce: Int
    }

    /// Quoted messages that are still in the thread, so every reply header resolves in one pass
    /// instead of scanning the thread per bubble.
    private var quotedMessagesByID: [UUID: MessagingController.ChatMessage] {
        let wanted = Set(messages.compactMap { $0.replyTo?.messageID })
        guard !wanted.isEmpty else { return [:] }
        return Dictionary(
            messages.filter { wanted.contains($0.id) }.map { ($0.id, $0) },
            uniquingKeysWith: { first, _ in first }
        )
    }

    /// Header for a bubble that quotes something; nil for an ordinary message.
    private func replyContent(
        for message: MessagingController.ChatMessage,
        quoted: [UUID: MessagingController.ChatMessage]
    ) -> ReplyQuoteContent? {
        guard let reference = message.replyTo else { return nil }
        return ReplyQuoteContent.make(
            reference: reference,
            original: quoted[reference.messageID],
            peerName: peerUsername,
            myUserID: messaging.myUserID
        )
    }

    /// The live copy of the message being answered (it may have been edited or deleted since).
    private var liveReplyTarget: MessagingController.ChatMessage? {
        guard let replyTarget else { return nil }
        return messages.first(where: { $0.id == replyTarget.id }) ?? replyTarget
    }

    /// Quote shown in the composer bar.
    private var composerReply: ReplyQuoteContent? {
        guard let liveReplyTarget else { return nil }
        return ReplyQuoteContent.make(original: liveReplyTarget, peerName: peerUsername)
    }

    /// What gets sealed into the next message sent from this composer.
    ///
    /// Human: Taken from the snapshot captured when the reply started, not the live
    /// bubble. If the original is deleted for everyone while we are still typing, the
    /// header reads "Deleted message" but the send still carries the quote we started with.
    private var outgoingReplyReference: MessageReplyReference? {
        replyTarget?.replyReference
    }

    private func startReply(to message: MessagingController.ChatMessage) {
        guard message.canBeQuoted else { return }
        withAnimation(Motion.snappy) { replyTarget = message }
        // Telegram opens the keyboard the moment a reply starts.
        composerFocusToken &+= 1
    }

    private func clearReply() {
        guard replyTarget != nil else { return }
        withAnimation(Motion.snappy) { replyTarget = nil }
    }

    /// Scrolls to a quoted message and flashes it. Says so when it is no longer on the device
    /// (older than the local window, or deleted just for us).
    private func jumpToQuoted(_ messageID: UUID) {
        guard let index = messages.firstIndex(where: { $0.id == messageID }) else {
            toast = "The original message isn't in this chat any more."
            Haptics.notification(.warning)
            scheduleToastClear()
            return
        }
        jumpNonce &+= 1
        let target = JumpTarget(id: messageID, nonce: jumpNonce)
        guard index < renderStart else {
            jumpTarget = target
            return
        }
        // Above the rendered rows: render down to it (and a few older for context) first.
        moveRenderStart(to: max(0, index - 10))
        Task { @MainActor in
            await Task.yield()
            jumpTarget = target
        }
    }

    private func flashHighlight(_ messageID: UUID) {
        highlightTask?.cancel()
        withAnimation(Motion.fade) { highlightedMessageID = messageID }
        highlightTask = Task { @MainActor in
            try? await Task.sleep(for: .milliseconds(1100))
            guard !Task.isCancelled else { return }
            withAnimation(.easeOut(duration: 0.45)) { highlightedMessageID = nil }
        }
    }

    // MARK: - Actions

    private func sendDraft() {
        let text = draft
        let reference = outgoingReplyReference
        // Only a preview that finished loading, for a link still in the text, goes along.
        let preview = linkComposer.takeAttachment(for: text)
        draft = ""
        clearReply()
        messaging.setTyping(peerUserID: peerUserID, isTyping: false)
        Haptics.impact(.light)
        Task { await messaging.sendText(text, to: peerUserID, replyTo: reference, linkPreview: preview) }
    }

    // MARK: - Links

    /// True while a message menu owns the screen. A tap that reaches the thread then is the
    /// release of the hold that opened the menu, never a new request.
    private var isShowingMessageMenu: Bool {
        focusedMenu != nil
    }

    /// Opens a link from a bubble in the in-app browser.
    ///
    /// Human: `.disabled` on the pressed bubble cancels its buttons, but a link inside `Text` is
    /// activated by SwiftUI itself and still arrives here on release — so the menu check lives
    /// in the opener too.
    /// Agent: RETURNS false (link discarded) while a message menu is open.
    @discardableResult
    private func openLink(_ url: URL) -> Bool {
        guard !isShowingMessageMenu else { return false }
        // The row's double tap (quick reaction) sees a link's taps too.
        MessageTapClaim.claim()
        return InAppBrowser.open(url)
    }

    /// The composer's link strip, while the draft has a link worth previewing.
    private var composerLinkBar: ChatLinkBarState? {
        switch linkComposer.phase {
        case .idle: nil
        case let .loading(url): .loading(url: url.absoluteString)
        case let .ready(draft): ChatLinkBarState(preview: draft.preview)
        }
    }

    /// The picture a bubble's preview shows, decoded once and cached.
    ///
    /// Human: A large picture lives in the message's media blob (downloaded on appear, blurred
    /// envelope placeholder until then); a small one rides inline in the preview itself.
    private func linkPreviewImage(for message: MessagingController.ChatMessage) -> LinkPreviewImage {
        guard let preview = message.linkPreview, !message.deleted else { return .none }
        if message.hasLargeLinkImage {
            let aspect: CGFloat = {
                if let width = message.imageWidth, let height = message.imageHeight, width > 0, height > 0 {
                    return CGFloat(width) / CGFloat(height)
                }
                return preview.imageAspect ?? 1.91
            }()
            return .large(
                full: LinkPreviewImageCache.image(for: message.id, variant: .full, data: message.imageData),
                placeholder: LinkPreviewImageCache.image(for: message.id, variant: .placeholder, data: message.previewData),
                aspect: aspect
            )
        }
        if let thumbnail = LinkPreviewImageCache.image(for: message.id, variant: .thumbnail, data: preview.thumbnail) {
            return .thumbnail(thumbnail)
        }
        return .none
    }

    /// What "Copy Link" copies: the previewed page, else the first link (an address as typed).
    private func copyableLink(in message: MessagingController.ChatMessage) -> String? {
        guard message.kind == .text, !message.deleted else { return nil }
        if let url = message.linkPreview?.url { return url }
        guard let link = MessageLinkText.links(in: message.text).first else { return nil }
        if link.isEmail {
            return (message.text as NSString).substring(with: link.range)
        }
        return link.url.absoluteString
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
            if !isNotes {
                messaging.setRecording(peerUserID: peerUserID, isRecording: true)
            }
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
        if !isNotes {
            messaging.setRecording(peerUserID: peerUserID, isRecording: false)
        }
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
                if !isNotes {
                    messaging.setRecording(peerUserID: peerUserID, isRecording: false)
                }
                toast = "Hold to record, release to send"
                Haptics.notification(.warning)
                scheduleToastClear()
                return
            }
            Haptics.impact(.light)
            if !isNotes {
                messaging.setRecording(peerUserID: peerUserID, isRecording: false)
                messaging.setTyping(peerUserID: peerUserID, isTyping: false)
            }
            let reference = outgoingReplyReference
            clearReply()
            Task {
                let error = await messaging.sendVoice(
                    audioData: take.data,
                    durationMs: take.durationMs,
                    to: peerUserID,
                    waveform: take.waveform,
                    replyTo: reference,
                    // Best-effort on-device transcript (Tier 1). Runs beside the send, never
                    // in front of it (see `sendVoice`): the note goes out right away and the
                    // transcript follows once ready.
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

    /// One thread row. Reads per-message state from `key` only: an observable read in here (a
    /// transfer, the thread) would redraw the row on every change of it, whatever
    /// `EquatableMessageRow` says.
    @ViewBuilder
    private func messageRow(
        _ message: MessagingController.ChatMessage,
        key: MessageRowKey
    ) -> some View {
        let reply = key.reply
        // Reacting where it can't happen (sending, failed, Saved Messages) offers nothing.
        let onReactionTap: ((String) -> Void)? = messaging.canReact(to: message)
            ? { emoji in react(emoji, to: message) }
            : nil
        switch message.kind {
        case .image:
            ImageMessageBubble(
                message: message,
                time: messaging.clockTimeLabel(for: message.createdAt),
                onDownload: {
                    downloadMedia(message)
                },
                transfer: key.transfer,
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
                frameReportID: message.id,
                reply: reply,
                onReplyTap: message.replyTo.map { reference in
                    { jumpToQuoted(reference.messageID) }
                },
                reactions: reactionChips(for: message),
                onReactionTap: onReactionTap
            )
        case .video:
            VideoMessageBubble(
                message: message,
                time: messaging.clockTimeLabel(for: message.createdAt),
                onDownload: {
                    downloadMedia(message)
                },
                transfer: key.transfer,
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
                frameReportID: message.id,
                reply: reply,
                onReplyTap: message.replyTo.map { reference in
                    { jumpToQuoted(reference.messageID) }
                },
                reactions: reactionChips(for: message),
                onReactionTap: onReactionTap
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
                inTranscriptTail: key.inTranscriptTail,
                reply: reply,
                onReplyTap: message.replyTo.map { reference in
                    { jumpToQuoted(reference.messageID) }
                },
                reactions: reactionChips(for: message),
                onReactionTap: onReactionTap
            )
        case .text:
            MessageBubbleView(
                text: message.text,
                time: messaging.clockTimeLabel(for: message.createdAt),
                isMine: message.isMine,
                isDeleted: message.deleted,
                receipt: isNotes ? .sent : message.receipt,
                frameReportID: message.id,
                reply: reply,
                onReplyTap: message.replyTo.map { reference in
                    { jumpToQuoted(reference.messageID) }
                },
                linkPreview: message.linkPreview,
                linkPreviewImage: linkPreviewImage(for: message),
                onOpenLinkPreview: message.linkPreview?.openURL.map { url in
                    { _ = openLink(url) }
                },
                reactions: reactionChips(for: message),
                onReactionTap: onReactionTap
            )
            .onAppear {
                // Preview pictures are small and load on their own (photos wait for a tap).
                if message.needsLinkImageDownload {
                    Task { await messaging.ensureLinkImageLoaded(for: message) }
                }
            }
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
                    .padding(.horizontal, 14)
                    .frame(height: 36)
            }
            .buttonStyle(PressableButtonStyle(scale: 1, dimming: 0))
            // A small glass capsule, sitting in the same bar as the composer.
            .glassEffect(.regular.interactive(), in: .capsule)
            .accessibilityLabel("Add as todo")

            Text("Saved only on this device")
                .font(.system(size: 12))
                .foregroundStyle(Theme.textSecondary)
            Spacer(minLength: 0)
        }
        .padding(.horizontal, 14)
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
    private func sendVideoPlans(
        _ plans: [VideoSendPlan],
        movies: [PickedMovie],
        replyTo: MessageReplyReference? = nil
    ) async {
        guard !plans.isEmpty else { return }
        defer { movies.forEach { $0.cleanup() } }

        var firstError: String?
        for (index, plan) in plans.enumerated() {
            // The quote goes on the first clip only, as the caption does.
            let error = await messaging.sendVideo(
                plan,
                to: peerUserID,
                replyTo: index == 0 ? replyTo : nil
            )
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

    /// Camera movie → the same trim sheet as a library pick.
    private func presentCapturedMovie(_ movie: PickedMovie) async {
        guard let probe = await VideoMedia.probe(url: movie.url) else {
            movie.cleanup()
            toast = "Could not load that video."
            scheduleToastClear()
            return
        }
        let poster = await VideoMedia.posterImage(url: movie.url)
        presentVideoCompose([PickedVideo(movie: movie, probe: probe, poster: poster)])
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
        quality: MediaComposeQuality = .original,
        replyTo: MessageReplyReference? = nil
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
                edits: edits.indices.contains(index) ? edits[index] : MediaEdits(),
                // Telegram puts the caption — and the reply — on the first item of an album.
                replyTo: index == 0 ? replyTo : nil
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

    /// Where the long-press menu's bubble lifts from: the drawn bubble's frame when it is a
    /// frame of this row, otherwise the bubble's size at the row's place.
    ///
    /// Human: The bubble reports its global frame through a preference, and the last report can
    /// be one made mid-way through the chat's opening transition — half a screen off, and never
    /// corrected, because nothing lays the thread out again once the transition ends. Opening a
    /// menu then lifted the message half out of view. The row's frame comes fresh from the press
    /// itself, so it is the judge: a stored bubble frame that does not sit inside the row is
    /// stale, and the bubble is placed at the row's leading (theirs) or trailing (ours) edge.
    /// Agent: Pure; `MessageMenuSourceFrameTests` covers it.
    static func menuSourceFrame(bubble: CGRect?, row: CGRect, isMine: Bool) -> CGRect {
        guard let bubble else { return row }
        if row.insetBy(dx: -2, dy: -2).contains(bubble) { return bubble }
        let width = min(bubble.width, row.width)
        let height = min(bubble.height, row.height)
        return CGRect(x: isMine ? row.maxX - width : row.minX, y: row.minY, width: width, height: height)
    }

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

        withAnimation(Motion.respecting(reduceMotion, Motion.menuLift)) {
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

        // Back into the list slot with Telegram's ease-in-out — no teleport.
        let drop = Motion.respecting(reduceMotion, Motion.menuDrop)
        let duration = reduceMotion ? Motion.reducedDuration : Motion.menuDropDuration
        withAnimation(drop) {
            menuProgress = 0
        }

        menuAnimationTask = Task { @MainActor in
            try? await Task.sleep(nanoseconds: UInt64(duration * 1_000_000_000) + 20_000_000)
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

    /// The long-press menu: the bubble lifts out of the thread, the reaction bar lands on top
    /// of it and the action card under it (Telegram). `MessageMenuOverlay` does the placing.
    ///
    /// Agent: READS `menuProgress` (animated by open/dismiss); every button closes the menu
    /// first, then acts.
    private func messageMenuOverlay(session: FocusedMessageMenu) -> some View {
        let message = session.message
        let hasLink = copyableLink(in: message) != nil
        return MessageMenuOverlay(
            sourceGlobalFrame: session.sourceGlobalFrame,
            isMine: message.isMine,
            cardHeight: MessageContextMenuCard.height(isMine: message.isMine, hasLink: hasLink),
            progress: menuProgress,
            onReaction: { emoji, source in
                dismissMessageMenu()
                reactAfterMenu(emoji, to: message, from: source)
            },
            selectedReactions: Set(messaging.myReactions(on: liveMessage(message))),
            showsReactions: messaging.canReact(to: liveMessage(message)),
            onBackdropTap: {
                // The finger that opened the menu is usually still down; its release lands on
                // the backdrop and would close what the hold just opened.
                guard let opened = menuOpenedAt,
                      Date().timeIntervalSince(opened) > Self.menuTapGrace
                else { return }
                dismissMessageMenu()
            }
        ) {
            MessageMenuHeroContent(
                message: message,
                timeLabel: messaging.clockTimeLabel(for: message.createdAt),
                heroImage: session.heroImage,
                inTranscriptTail: transcriptTail.contains(message.id),
                reply: replyContent(for: message, quoted: quotedMessagesByID),
                linkPreviewImage: linkPreviewImage(for: message),
                // No flight lands in the preview; it is gone by the time the chip changes.
                reactions: reactionChips(for: message, anchored: false)
            )
        } card: {
            MessageContextMenuCard(
                isMine: message.isMine,
                onAction: { action in
                    dismissMessageMenu()
                    handleMenu(action, message: message)
                },
                progress: menuProgress,
                hasLink: hasLink
            )
        }
        // A new message is a new menu: no scroll position carries over from the last one.
        .id(message.id)
    }

    /// Telegram's double-tap reaction.
    private static let quickReaction = MessageReactionBar.quickReaction

    /// Chips for a bubble, with the names this chat knows (ours and the peer's). `anchored`
    /// chips carry the message id, so a flying reaction can find its landing spot.
    private func reactionChips(
        for message: MessagingController.ChatMessage,
        anchored: Bool = true
    ) -> [ReactionChipContent] {
        guard !message.reactions.isEmpty, !message.deleted else { return [] }
        let me = messaging.myUserID
        let mine = messaging.myReactions(on: message)
        return ReactionMerge.chips(message.reactions, me: me).map { chip in
            ReactionChipContent(
                emojis: chip.emojis,
                reactors: chip.userIDs.map { id in
                    id == me
                        ? ReactionChipContent.Reactor(id: id, name: messaging.myUsername ?? "You", isMe: true)
                        : ReactionChipContent.Reactor(id: id, name: peerUsername)
                },
                includesMe: chip.includesMe,
                myEmojis: mine,
                messageID: anchored ? message.id : nil
            )
        }
    }

    /// The thread's current copy: a menu's snapshot may predate a reaction that just landed.
    private func liveMessage(_ message: MessagingController.ChatMessage) -> MessagingController.ChatMessage {
        messages.first(where: { $0.id == message.id }) ?? message
    }

    /// A pick from the long-press menu. The emoji leaves the bar at once; the chip changes only
    /// once the bubble is back in its slot, so the lifted copy never lands on a bubble that has
    /// already grown underneath it.
    private func reactAfterMenu(
        _ emoji: String,
        to message: MessagingController.ChatMessage,
        from source: CGRect?
    ) {
        let live = liveMessage(message)
        if let source, messaging.canReact(to: live), !messaging.myReactions(on: live).contains(emoji) {
            beginReactionFlight(emoji, messageID: message.id, from: source)
        }
        let settle = reduceMotion ? Motion.reducedDuration : Motion.menuDropDuration
        Task { @MainActor in
            try? await Task.sleep(for: .seconds(settle + 0.03))
            react(emoji, to: liveMessage(message))
        }
    }

    /// Double tap on a text bubble: Telegram's quick reaction, flying out from under the finger.
    private func quickReact(_ message: MessagingController.ChatMessage, at point: CGPoint) {
        guard focusedMenu == nil else { return }
        if !messaging.myReactions(on: message).contains(Self.quickReaction) {
            let start = CGRect(x: point.x - 22, y: point.y - 22, width: 44, height: 44)
            beginReactionFlight(Self.quickReaction, messageID: message.id, from: start, scale: 1.6)
        }
        react(Self.quickReaction, to: message)
    }

    /// Starts a flight (see `ReactionFlight`). Reduce Motion: the chip just appears.
    private func beginReactionFlight(_ emoji: String, messageID: UUID, from source: CGRect, scale: CGFloat = 1) {
        guard !reduceMotion else { return }
        let flight = ReactionFlight(emoji: emoji, messageID: messageID, from: source, fromScale: scale)
        reactionFlight = flight
        // The chip never showed up (scrolled away, the save refused at once): let it go.
        Task { @MainActor in
            try? await Task.sleep(for: .seconds(1.2))
            guard reactionFlight?.id == flight.id, reactionFlight?.to == nil else { return }
            withAnimation(Motion.fade) { reactionFlight = nil }
        }
    }

    /// The landing chip reported where its emoji sits: fly there, then hand over to the chip.
    private func landReactionFlight(at frame: CGRect?) {
        guard let frame, let flight = reactionFlight else { return }
        guard flight.to == nil else {
            // The thread moved under the flight (the bottom follows a growing bubble): re-aim.
            if flight.to != frame { reactionFlight?.to = frame }
            return
        }
        reactionFlight?.to = frame
        withAnimation(Motion.reactionFlight) {
            reactionFlight?.landed = true
        } completion: {
            guard reactionFlight?.id == flight.id else { return }
            reactionFlight = nil
        }
    }

    /// Everything a row's bubble is drawn from (see `EquatableMessageRow`).
    private func rowKey(
        for message: MessagingController.ChatMessage,
        quoted: [UUID: MessagingController.ChatMessage]
    ) -> MessageRowKey {
        MessageRowKey(
            message: message,
            reply: replyContent(for: message, quoted: quoted),
            transfer: messaging.mediaTransfers[message.id],
            inTranscriptTail: transcriptTail.contains(message.id),
            linkImage: LinkImageIdentity(linkPreviewImage(for: message))
        )
    }

    /// Picking `emoji` (bar, grid, chip or double tap): sets it, or takes it back when it is
    /// already ours.
    private func react(_ emoji: String, to message: MessagingController.ChatMessage) {
        guard messaging.canReact(to: message) else {
            toast = "You can react once the message is sent."
            scheduleToastClear()
            return
        }
        Haptics.impact(.light)
        messaging.toggleReaction(emoji, on: message.id, peerUserID: peerUserID)
    }

    private func handleMenu(_ action: MessageMenuAction, message: MessagingController.ChatMessage) {
        switch action {
        case .copy:
            UIPasteboard.general.string = message.text
            toast = "Copied"
            Haptics.notification(.success)
            scheduleToastClear()
        case .copyLink:
            guard let link = copyableLink(in: message) else { return }
            UIPasteboard.general.string = link
            toast = "Link copied"
            Haptics.notification(.success)
            scheduleToastClear()
        case .reply:
            startReply(to: message)
        case .edit, .pin, .forward, .select, .moreReactions:
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
        // Backstop for the release of a hold that opened the message menu.
        guard !isShowingMessageMenu else { return }
        guard message.imageData != nil else {
            downloadMedia(message)
            return
        }
        withAnimation(.easeOut(duration: 0.2)) {
            viewingMedia = ViewingMedia(id: message.id)
        }
    }

    private func openVideoPlayer(for message: MessagingController.ChatMessage) {
        guard !isShowingMessageMenu else { return }
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

/// The oldest rendered message (its date finds the spot again if it is deleted).
private struct RenderAnchor: Equatable {
    let id: UUID
    let createdAt: Date
}

/// What the thread needs of its scroll geometry: enough to hold a reading position.
private struct ThreadScrollMetrics: Equatable {
    var contentHeight: CGFloat = 0
    var offsetY: CGFloat = 0
    var nearTop = false
    var topInset: CGFloat = 0
    var atBottom = false
    var containerHeight: CGFloat = 0
}

/// Where each bubble is drawn, for the long-press menu's hero. Not observed on purpose.
@MainActor
private final class BubbleFrameStore {
    var frames: [UUID: CGRect] = [:]
}

/// Per-thread scroll bookkeeping. A reference type on purpose: it changes every frame while
/// scrolling, and none of it should redraw the thread.
@MainActor
private final class ThreadScrollState {
    var metrics = ThreadScrollMetrics()
    /// Distance from the content's bottom edge to restore once rows added above have laid out.
    var holdFromBottom: CGFloat?
    /// Offset from before that restore, until the scroll view reports the new one.
    var staleOffset: CGFloat?
    /// Forced pins to the bottom still settling (the first layout reads as "at the top").
    var pinning = 0
    /// The opening pin has landed; before that the offset says nothing about the reader.
    var settled = false
}

/// Everything a thread row's bubble is drawn from.
///
/// Agent: Compares the whole message — cheap for an unchanged one, since `Data` equality
/// short-circuits on shared storage — so a field added to `ChatMessage` is covered without
/// touching this. Anything else `messageRow` reads per message belongs here too.
private struct MessageRowKey: Equatable {
    let message: MessagingController.ChatMessage
    let reply: ReplyQuoteContent?
    let transfer: MessagingController.MediaTransfer?
    let inTranscriptTail: Bool
    let linkImage: LinkImageIdentity
}

/// A link preview's pictures by identity (`LinkPreviewImageCache` hands out one instance per
/// message and variant until the bytes change).
private struct LinkImageIdentity: Equatable {
    private let kind: Int
    private let first: ObjectIdentifier?
    private let second: ObjectIdentifier?
    private let aspect: CGFloat

    init(_ image: LinkPreviewImage) {
        switch image {
        case .none:
            (kind, first, second, aspect) = (0, nil, nil, 0)
        case let .thumbnail(thumbnail):
            (kind, first, second, aspect) = (1, ObjectIdentifier(thumbnail), nil, 0)
        case let .large(full, placeholder, ratio):
            (kind, first, second, aspect) = (2, full.map(ObjectIdentifier.init), placeholder.map(ObjectIdentifier.init), ratio)
        }
    }
}

/// A thread row that redraws only when what it shows changes.
///
/// Human: Bubbles take closures, and closures never compare equal, so any change to the thread
/// — one reaction, one receipt — redrew every loaded bubble (hundreds after scrolling up). The
/// key holds everything the bubble is drawn from; its closures act on the message by id.
private struct EquatableMessageRow<Content: View>: View, Equatable {
    let key: MessageRowKey
    @ViewBuilder let content: () -> Content

    static func == (lhs: Self, rhs: Self) -> Bool { lhs.key == rhs.key }

    var body: some View { content() }
}
