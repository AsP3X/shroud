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
    @State private var toast: Toast?
    /// Height of the composer bar (plus the Notes toolbar), so toasts land above it.
    @State private var composerBarHeight: CGFloat = 0
    /// Notes only: the header menu's "Delete All Notes" confirmation.
    @State private var showNotesDeleteConfirm = false
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
    /// Files picked with photos or videos — shown in the file composer after the media compose closes.
    @State private var filesAfterMediaCompose: [PickedFile] = []
    @State private var profileDestination: ProfileDestination?
    @State private var photoPickerItems: [PhotosPickerItem] = []
    @State private var showPhotoPicker = false
    /// True when the picker was opened from compose, so its results append instead of replace.
    @State private var pickerAppendsToDraft = false
    @State private var showCamera = false
    /// The document picker behind the attach sheet's File row.
    @State private var showFileImporter = false
    /// Files copied in from the picker, waiting in the file composer (plaintext in `tmp/`).
    /// The sheet's item: it hands the files to the sheet itself. A separate list read from the
    /// `isPresented` sheet's closure came through empty ("Sending 0 Files"), and Send sent nothing.
    @State private var stagedFiles: StagedFiles?
    /// A received file's open or share, waiting on its warning (§6 of docs/file-sharing.md).
    @State private var pendingFileAction: PendingFileAction?
    @State private var isSendingMedia = false
    /// Message IDs currently downloading full media (Telegram-style manual download).
    @State private var mediaDownloadIDs: Set<UUID> = []
    /// Downloads the reader stopped with the bubble's ring — ending empty-handed is no failure.
    @State private var cancelledDownloadIDs: Set<UUID> = []
    /// Bumped after thread load / open so we re-pin to the newest message once layout is ready.
    @State private var pinToBottomToken = 0
    /// The chat has been on screen before. Coming back from a pushed screen (the profile) keeps
    /// the reader's place instead of pinning to the newest message again.
    @State private var didOpen = false
    /// The first page of a chat that isn't on this device yet is on its way.
    @State private var loadingFirstPage = false
    /// Why that first page couldn't be fetched, so the chat has nothing to show but a retry.
    /// Kept from the load itself: the shared `lastError` is overwritten and cleared by any action.
    @State private var firstLoadError: String?
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
    /// The reader is up in the history, and what landed under them since; only the jump
    /// control reads it.
    @State private var jumpToLatest = JumpToLatestState()

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

    /// Loads the chat and keeps the outcome for the failed-first-load state: the error, read
    /// right after the load, when it left the chat empty (a load that fails over a thread
    /// already on the device just goes offline instead).
    private func loadThreadKeepingFailure() async {
        await messaging.loadThread(peerUserID: peerUserID, reconcile: true)
        guard !Task.isCancelled else { return }
        firstLoadError = messages.isEmpty ? messaging.lastError : nil
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
        // Notes are sealed to the account and sync to its other devices, so this says who can
        // read them, not where they are kept.
        if isNotes { return "Only you · end-to-end encrypted" }
        if let peerActivity { return "\(peerActivity.label)…" }
        if isOnline { return "online" }
        if messaging.isOffline { return "offline · local copy" }
        return ChatListFormatting.presenceLabel(for: messaging.presenceByUser[peerUserID]) ?? "…"
    }

    /// True when this id is still in the thread as a delete-for-everyone tombstone.
    private func isTombstone(_ id: UUID) -> Bool {
        messages.contains { $0.id == id && $0.deleted }
    }

    /// The open photo or clip was deleted for everyone, so its viewer should leave with it.
    private var deletedOpenMedia: Bool {
        if let id = viewingMedia?.id, isTombstone(id) { return true }
        if let id = viewingVideo?.id, isTombstone(id) { return true }
        return false
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

    /// A full-screen layer (viewer, editors, player) hides the composer. The sending HUD is a
    /// centred card, so the composer stays visible under it.
    private var coversComposer: Bool {
        viewingMedia != nil || viewingVideo != nil || composeDraft != nil || videoDraft != nil
    }

    /// Toasts sit above the composer; while a full-screen layer hides it they drop back to the
    /// screen's bottom edge.
    private var toastBottomInset: CGFloat {
        coversComposer ? 0 : composerBarHeight
    }

    var body: some View {
        chatSurfaceWithFiles
            .overlay(alignment: .bottomTrailing) { jumpToLatestLayer }
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
                pendingDelete?.message.kind == .file ? "Delete this file?" : "Delete message?",
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
                "Delete all notes?",
                isPresented: $showNotesDeleteConfirm,
                titleVisibility: .visible
            ) {
                Button("Delete", role: .destructive) { deleteNotes() }
                Button("Cancel", role: .cancel) {}
            } message: {
                Text("Removes every note from this device and your account.")
            }
            .toast($toast, bottomInset: toastBottomInset)
            .animation(Motion.scrim, value: viewingMedia != nil)
            .animation(Motion.scrim, value: viewingVideo != nil)
            .animation(Motion.scrim, value: composeDraft != nil)
            .animation(Motion.scrim, value: videoDraft != nil)
            .animation(Motion.snappy, value: isSendingMedia)
            .onGeometryChange(for: CGFloat.self) { $0.size.width } action: { threadWidth = $0 }
            // Applied last so the list *and* the long-press menu hero size bubbles identically —
            // a mismatch here shows up as the bubble re-wrapping the moment the menu opens.
            .environment(\.chatRowWidth, max(0, threadWidth - Self.threadHorizontalInset * 2))
            .onChange(of: deletedOpenMedia) { _, gone in
                guard gone else { return }
                withAnimation(.easeOut(duration: 0.2)) {
                    viewingMedia = nil
                    viewingVideo = nil
                }
            }
    }

    private var deleteDialogBinding: Binding<Bool> {
        Binding(
            get: { pendingDelete != nil },
            set: { if !$0 { pendingDelete = nil } }
        )
    }

    /// File sharing's presentations: the document picker, the file composer and the warning
    /// a received app or macro file asks before it is opened or shared.
    private var chatSurfaceWithFiles: some View {
        chatSurface
            .fileImporter(
                isPresented: $showFileImporter,
                allowedContentTypes: SharedFile.pickerTypes,
                allowsMultipleSelection: true
            ) { result in
                Task { await loadPickedFiles(result) }
            }
            .sheet(item: stagedFilesBinding) { staged in
                FileComposeSheet(
                    files: staged.files,
                    onRemove: { file in
                        file.cleanup()
                        stagedFiles?.files.removeAll { $0.id == file.id }
                        if stagedFiles?.files.isEmpty == true { discardStagedFiles() }
                    },
                    onCancel: discardStagedFiles,
                    onSend: { caption in
                        // Handed to the sends: closing the sheet must not delete them.
                        let files = staged.files
                        stagedFiles = nil
                        let reference = outgoingReplyReference
                        clearReply()
                        pinToBottomToken &+= 1
                        Task { await sendPickedFiles(files, caption: caption, replyTo: reference) }
                    }
                )
            }
            // An alert, not a confirmation dialog: iOS 26 draws no Cancel in the anchored one.
            .alert(
                pendingFileAction?.warning.dialogTitle ?? "",
                isPresented: fileWarningBinding,
                presenting: pendingFileAction
            ) { pending in
                Button("Cancel", role: .cancel) { pendingFileAction = nil }
                    .keyboardShortcut(.defaultAction)
                Button("Continue", role: .destructive) {
                    pendingFileAction = nil
                    performFileAction(pending.kind, for: pending.message)
                }
            } message: { pending in
                Text(pending.warning.dialogMessage(sender: peerUsername))
            }
    }

    private var fileWarningBinding: Binding<Bool> {
        Binding(
            get: { pendingFileAction != nil },
            set: { if !$0 { pendingFileAction = nil } }
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
            // The header also gets a scrim: its presence line is too small to read over a
            // bubble the edge effect has only half faded.
            .glassTopBar(scrim: Theme.backgroundChat) {
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
                // The toast overlay sits outside this bar's inset; it lifts itself by this much.
                .onGeometryChange(for: CGFloat.self) { $0.size.height } action: { composerBarHeight = $0 }
            }
            .navigationBarBackButtonHidden(true)
            .toolbar(.hidden, for: .navigationBar)
            .toolbarBackground(.hidden, for: .navigationBar)
            // Hiding the bar also kills the system edge swipe; bring it back.
            .interactivePopGesture(enabled: allowsSwipeBack)
            .task {
                // Pin immediately if the thread is already in memory, then again after network
                // load. Back from the profile, only the reconcile runs: the reader keeps their place.
                let opening = !didOpen
                didOpen = true
                if opening { pinToBottomToken &+= 1 }
                // Nothing on this device yet: a spinner rather than the brand-new-chat header
                // (back from the profile, only in place of a failed first load).
                loadingFirstPage = messages.isEmpty && (opening || firstLoadError != nil)
                await loadThreadKeepingFailure()
                loadingFirstPage = false
                if opening { pinToBottomToken &+= 1 }
            }
            .onAppear {
                messaging.setActivePeer(peerUserID)
                // Opening a chat should always start at the newest message (Telegram/Signal/WhatsApp).
                if !didOpen { pinToBottomToken &+= 1 }
            }
            .onDisappear {
                // Leaving the thread throws away an in-flight take and silences playback —
                // there is no mini-player to hand either off to.
                highlightTask?.cancel()
                // A pushed profile comes back to the same draft, so its link preview stays.
                if profileDestination == nil { linkComposer.reset() }
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
                maxSelectionCount: pickerAppendsToDraft
                    ? max(1, Self.maxPhotosPerSend - pickerStagedCount)
                    : Self.maxPhotosPerSend,
                selectionBehavior: .ordered,
                matching: pickerFilter,
                preferredItemEncoding: .current,
                photoLibrary: .shared()
            )
            .onChange(of: photoPickerItems) { _, items in
                guard !items.isEmpty else { return }
                Task { await loadPickedMedia(items) }
            }
            .onChange(of: messaging.reactionFailure) { _, failure in
                guard let failure else { return }
                toast = .failure(failure.message)
                Haptics.notification(.error)
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
                } onFailure: { message in
                    // A capture that couldn't be read isn't a Cancel: say it was lost.
                    toast = .failure(message)
                    Haptics.notification(.error)
                }
                .ignoresSafeArea()
            }
            .navigationDestination(item: $profileDestination) { dest in
                ContactProfileView(
                    peerUserID: dest.peerUserID,
                    peerUsername: dest.peerUsername,
                    // The chat is gone: leave the thread, which takes the profile with it.
                    onChatDeleted: {
                        if let onBack {
                            onBack()
                        } else {
                            dismiss()
                        }
                    }
                )
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
        // Hide in the same turn the photo becomes a tombstone. Waiting for `onChange`
        // lets the pager fall through to the next photo for a frame.
        if let viewingMedia, !isTombstone(viewingMedia.id) {
            MediaImageViewerOverlay(
                items: mediaViewerItems,
                initialID: viewingMedia.id,
                onClose: {
                    withAnimation(.easeOut(duration: 0.2)) {
                        self.viewingMedia = nil
                    }
                },
                // The thread's own "Delete message?" dialog, with its scope choice; a delete
                // closes the viewer (`performDelete`), Cancel leaves it open.
                onDelete: { id in
                    if let message = messages.first(where: { $0.id == id }) {
                        pendingDelete = PendingDelete(message: message)
                    }
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
                    presentFilesAfterMediaCompose()
                },
                onSend: { caption, quality, edits in
                    let photos = composeDraft.photos
                    let reference = outgoingReplyReference
                    clearReply()
                    withAnimation(.easeOut(duration: 0.15)) {
                        self.composeDraft = nil
                    }
                    presentFilesAfterMediaCompose()
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
                // A full album has no room: Add shows disabled.
                onAddMore: composeDraft.photos.count < Self.maxPhotosPerSend
                    ? { openPickerToAppend() }
                    : nil,
                onRemovePhoto: { index in
                    removeComposePhoto(at: index)
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
                onAddMore: videoDraft.videos.count < Self.maxPhotosPerSend
                    ? { openPickerToAppend() }
                    : nil,
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
        if let viewingVideo, !isTombstone(viewingVideo.id) {
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

    private var sendingMediaLayer: some View {
        // In its own container: glass outside one leaves at once, whatever the transition says.
        GlassEffectContainer {
            if isSendingMedia {
                ProgressView("Sending media…")
                    .padding(16)
                    .glassEffect(.regular, in: .rect(cornerRadius: 14))
                    .transition(.scale(scale: 0.9).combined(with: .opacity))
            }
        }
    }

    /// Telegram's jump-to-latest control, over the composer's send / mic slot while the reader
    /// is up in the history. Not over a full-screen layer, nor during a voice take, whose lock
    /// rises into the same spot.
    private var jumpToLatestLayer: some View {
        JumpToLatestLayer(
            state: jumpToLatest,
            messages: messages,
            isAllowed: !coversComposer && !voiceRecorder.isRecording,
            // A long animated run through the history is what Reduce Motion asks to skip.
            action: { scrollToBottom(animated: !reduceMotion) }
        )
        // Centred over the composer's 44 pt trailing slot (12 pt bar inset + 2), 8 pt above the bar.
        .padding(.trailing, 14)
        .padding(.bottom, composerBarHeight + 8)
        // Rides up and down with the reply and link strips.
        .animation(Motion.respecting(reduceMotion, Motion.snappy), value: composerBarHeight)
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

    /// Items already in the compose screen that "Add" extends.
    private var pickerStagedCount: Int {
        videoDraft?.videos.count ?? composeDraft?.photos.count ?? 0
    }

    /// "Add" picks more of what the open compose screen holds: a photo can't join a video
    /// send, and one picked there would open a second compose screen hidden under it.
    private var pickerFilter: PHPickerFilter {
        guard pickerAppendsToDraft else { return .any(of: [.images, .videos]) }
        if videoDraft != nil { return .videos }
        if composeDraft != nil { return .images }
        return .any(of: [.images, .videos])
    }

    /// "Add" on a compose screen: the picker again, its picks joining that send.
    private func openPickerToAppend() {
        pickerAppendsToDraft = true
        photoPickerItems = []
        showPhotoPicker = true
    }

    // MARK: - Top chrome (Liquid Glass bar)

    /// Back on the left, the contact (avatar + name + presence) centred on the screen, and
    /// on the right Video + Call for a peer, or the More menu for Notes.
    ///
    /// Human: Only the controls carry glass; the contact block is plain so the name reads
    /// like a title, and it is centred like one. Video and Call fuse into one capsule, the way
    /// the system toolbar groups neighbouring items. A peer chat is deleted from the chat
    /// list's long-press menu, so its bar holds nothing else; Notes keeps a More menu because
    /// that is the only place its notes can be cleared from inside the thread. The bar has
    /// no backdrop of its own: `glassTopBar(scrim:)` blurs and covers the thread under it.
    /// Agent: RETURNS the bar row; presence animation lives on the centre block.
    private var topChrome: some View {
        // Notes has one control per side, so its centre may use the width the calls would take.
        GlassBarRow(sideReserve: GlassBarMetrics.sideReserve(controls: isNotes ? 1 : 2)) {
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
            } else {
                // Notes have no one on the other side, so the menu only clears them.
                Menu {
                    Button(role: .destructive) {
                        showNotesDeleteConfirm = true
                    } label: {
                        Label("Delete All Notes", systemImage: "trash")
                    }
                } label: {
                    Image(systemName: "ellipsis")
                        .font(GlassBarMetrics.glyphFont)
                        .foregroundStyle(Theme.accent)
                        .frame(width: GlassBarMetrics.controlSize, height: GlassBarMetrics.controlSize)
                        .contentShape(Capsule())
                }
                .glassEffect(.regular.interactive(), in: .capsule)
                .accessibilityLabel("More")
            }
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
            }
            // One heading ("Notes to me, Only you · …"), not two loose fragments.
            .accessibilityElement(children: .combine)
            .accessibilityAddTraits(.isHeader)
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
                toast = .failure(err)
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
                    } else if messages.isEmpty, loadingFirstPage {
                        // Not the new-chat header: this chat's history is still on its way.
                        ProgressView()
                            .controlSize(.small)
                            .accessibilityLabel("Loading messages")
                            .frame(maxWidth: .infinity, minHeight: 28)
                    } else if messages.isEmpty, let firstLoadError {
                        ListLoadErrorView(
                            title: "Can't load messages",
                            message: firstLoadError,
                            retry: { await loadThreadKeepingFailure() }
                        )
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
                                // The menu's actions for VoiceOver and Switch Control, which can't
                                // hold. Reply comes from `swipeToReply`, the whole menu from
                                // `messageContextLongPress` ("Message options").
                                .accessibilityActions { messageActions(for: message) }
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
                                // Swipe right to answer it. Disabled while the
                                // context menu owns the screen, and for bubbles the peer
                                // could not resolve yet.
                                .swipeToReply(
                                    isEnabled: message.canBeQuoted && focusedMenu == nil,
                                    isMine: message.isMine
                                ) {
                                    startReply(to: message)
                                }
                                // Arriving bubbles grow out of the corner they were "spoken" from;
                                // under Reduce Motion they fade in where they sit.
                                .transition(
                                    reduceMotion ? AnyTransition.opacity : Motion.bubbleIn(isMine: message.isMine)
                                )
                                // Keep layout space while focused so the list doesn’t jump.
                                // Hero sits on this slot at progress 0, so handoff is seamless.
                                .opacity(focusedMenu?.message.id == message.id ? 0 : 1)
                                // UIKit long-press (0.25s). SwiftUI long-press in ScrollView is unreliable.
                                .messageContextLongPress(
                                    minimumDuration: 0.25,
                                    onTap: (message.presentedKind == .image || message.presentedKind == .video)
                                        ? {
                                            handleMediaTap(message)
                                        }
                                        : message.presentedKind == .file
                                        ? {
                                            handleFileRowTap(message)
                                        }
                                        : nil,
                                    // Quick reaction on text only: other bubbles have controls
                                    // of their own that would take both taps too.
                                    onDoubleTap: message.presentedKind == .text && messaging.canReact(to: message)
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
                .animation(
                    renderFrom == nil ? nil : Motion.respecting(reduceMotion, Motion.bouncy),
                    value: newestMessageID
                )
                // Springy, so the ink bubble pops out of its tail corner like a message landing.
                .animation(Motion.respecting(reduceMotion, Motion.bouncy), value: peerActivity)
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
                // Last, once this pass has decided whether it follows the bottom.
                defer { updateJumpToLatest() }
                // On the newest message: whatever scroll there was under way has landed.
                if new.atBottom { scrollState.headingToBottom = false }
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
                        scrollState.headingToBottom = !new.atBottom
                        withAnimation(Self.followAnimation) { threadScroll.scrollTo(edge: .bottom) }
                    } else if new.containerHeight != old.containerHeight {
                        scrollState.headingToBottom = !new.atBottom
                        threadScroll.scrollTo(edge: .bottom)
                    }
                }
                if new.nearTop { revealOlder() }
            }
            .onChange(of: messages.isEmpty, initial: true) { _, isEmpty in
                // Freeze the window on the first messages shown; it only grows from here.
                if renderFrom == nil, !isEmpty { moveRenderStart(to: renderStart) }
                // Messages came in anyway (an event, a background reload): no failed load left.
                if !isEmpty { firstLoadError = nil }
            }
            .onChange(of: messaging.lastError == nil) { _, cleared in
                // The reconnect and poll reloads report only through the shared `lastError`, and
                // a chat that is really new stays empty. Once something succeeds again, reload
                // it here and take that outcome, rather than keep the error over a chat with
                // nothing in it. Only while it's on screen: loading activates the chat.
                guard cleared, firstLoadError != nil, messaging.activePeerID == peerUserID else { return }
                Task { await loadThreadKeepingFailure() }
            }
            .onChange(of: messages.count) { _, _ in
                // An older page landed (or a send went out) while the reader sits at the top.
                revealOlder()
            }
            .onChange(of: newestMessageID) { old, _ in
                // The first messages shown are already at the bottom; later ones scroll only for a
                // reader already there (the geometry follow covers that too) or for our own send.
                // Someone reading history stays where they are.
                guard old == nil || !scrollState.settled || scrollState.metrics.atBottom
                    || messages.last?.isMine == true
                else { return }
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
                // The ink bubble joins the bottom; it only pulls a reader who is already there.
                if activity != nil, scrollState.metrics.atBottom { scrollToBottom() }
            }
            .onScrollPhaseChange { old, new in
                // The reader's finger went down or came up: where the thread sits is their doing
                // now, not a scroll to the bottom still under way.
                guard old == .interacting || new == .interacting else { return }
                scrollState.headingToBottom = false
                updateJumpToLatest()
            }
            .onChange(of: jumpTarget) { _, target in
                guard let target else { return }
                // Up to the quoted message: the reader leaves the bottom as if they had scrolled.
                scrollState.headingToBottom = false
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
                // Only on opening; back from the profile the reader keeps their place.
                if !didOpen { scrollToBottom(animated: false, force: true) }
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

    /// Shows the jump-to-latest control once the reader is off the bottom by their own doing:
    /// not before the opening pin has landed, not while a scroll to the bottom is under way,
    /// and not in a thread too short to scroll (pulling it past its edge reads as "off the
    /// bottom" too).
    private func updateJumpToLatest() {
        let state = scrollState
        let away = state.settled && state.pinning == 0 && !state.headingToBottom
            && !state.metrics.atBottom && state.metrics.contentHeight > state.metrics.containerHeight
        guard away != jumpToLatest.isAway else { return }
        jumpToLatest.setAway(away, newest: messages.last)
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
        // Off the bottom until this lands, but not by the reader's doing: the jump control goes.
        if !scrollState.metrics.atBottom { scrollState.headingToBottom = true }
        updateJumpToLatest()
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
            updateJumpToLatest()
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
    /// header reads "Message deleted" but the send still carries the quote we started with.
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
            toast = .failure("The original message isn't in this chat any more.")
            Haptics.notification(.warning)
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
            toast = .failure(SessionController.userMessage(for: error))
            Haptics.notification(.error)
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
                toast = .info("Hold to record, release to send")
                Haptics.notification(.warning)
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
                    toast = .failure(error)
                    Haptics.notification(.error)
                } else {
                    Haptics.notification(.success)
                }
            }
        } catch {
            toast = .failure(SessionController.userMessage(for: error))
            Haptics.notification(.error)
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
        switch message.presentedKind {
        case .image:
            ImageMessageBubble(
                message: message,
                time: messaging.clockTimeLabel(for: message.createdAt),
                onDownload: {
                    downloadMedia(message)
                },
                transfer: key.transfer,
                onCancelDownload: {
                    cancelDownload(message)
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
                            toast = .failure(error)
                            Haptics.notification(.error)
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
                    cancelDownload(message)
                },
                onRetry: {
                    Task {
                        // The bubble carries its own ring while retrying — no modal spinner.
                        let error = await messaging.retryFailedVideo(
                            messageID: message.id,
                            peerUserID: peerUserID
                        )
                        if let error {
                            toast = .failure(error)
                            Haptics.notification(.error)
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
                // Returns once the fetch is over, so the bubble can tell a failure (retry
                // glyph) from a load still running (spinner).
                onAppearLoad: {
                    await messaging.ensureVoiceLoaded(for: message)
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
                        toast = .failure(error.localizedDescription)
                        Haptics.notification(.error)
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
        case .file:
            FileMessageBubble(
                message: message,
                time: messaging.clockTimeLabel(for: message.createdAt),
                transfer: key.transfer,
                onTap: {
                    fileAction(.open, for: message)
                },
                onCancelDownload: {
                    cancelDownload(message)
                },
                onRetry: {
                    retryFile(message)
                },
                frameReportID: message.id,
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
            // The accent gradient, as on the chat list's row: fading to `accentSoft` left the
            // white bookmark at 2.3:1 in light mode.
            Circle()
                .fill(Theme.brandGradient)
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
                    toast = .info("Type a todo, then tap Todo.")
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

            Spacer(minLength: 0)
        }
        .padding(.horizontal, 14)
    }

    private func handleAttach(_ option: ChatAttachOption) {
        switch option {
        case .photos:
            // A fresh pick, even if an "Add" picker was closed without picking.
            pickerAppendsToDraft = false
            showPhotoPicker = true
        case .camera:
            if UIImagePickerController.isSourceTypeAvailable(.camera) {
                showCamera = true
            } else {
                toast = .failure("Camera is not available on this device.")
            }
        case .file:
            showFileImporter = true
        case .location, .contact, .music, .gift, .stickers:
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
            toast = .failure(items.count > 1 ? "Could not load those items." : "Could not load that item.")
            return
        }

        // "Add" from an open compose screen extends that send instead of starting a new one.
        // The picker only offered that screen's kind, up to the room left in it; anything past
        // that still gets its temp file removed.
        if appending, var draft = videoDraft, !pickedVideos.isEmpty {
            let merged = draft.videos + pickedVideos
            merged.dropFirst(Self.maxPhotosPerSend).forEach { $0.movie.cleanup() }
            draft.videos = Array(merged.prefix(Self.maxPhotosPerSend))
            withAnimation(Motion.standard) { videoDraft = draft }
            return
        }
        if appending, var draft = composeDraft, !pickedPhotos.isEmpty {
            pickedVideos.forEach { $0.movie.cleanup() }
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
            // Keep the reason up longer so it can be read.
            toast = .failure(firstError, duration: .seconds(4))
            Haptics.notification(.error)
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
            toast = .failure("Could not load that video.")
            return
        }
        let poster = await VideoMedia.posterImage(url: movie.url)
        presentVideoCompose([PickedVideo(movie: movie, probe: probe, poster: poster)])
    }

    /// Closes the video compose and hands any photos from the same pick to the photo compose.
    private func closeVideoCompose() {
        withAnimation(.easeOut(duration: 0.2)) { videoDraft = nil }
        guard !photosAfterVideoCompose.isEmpty else {
            presentFilesAfterMediaCompose()
            return
        }
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
        if draft.photos.isEmpty { presentFilesAfterMediaCompose() }
    }

    /// The rest of a file pick that also held photos or videos: the file composer, once the
    /// media compose has finished leaving.
    private func presentFilesAfterMediaCompose() {
        guard !filesAfterMediaCompose.isEmpty else { return }
        let files = filesAfterMediaCompose
        filesAfterMediaCompose = []
        Task {
            try? await Task.sleep(for: .milliseconds(260))
            stagedFiles = StagedFiles(files: files)
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
            // Bubbles stay in the thread with Retry; also surface the reason, up longer so it
            // can be read.
            toast = .failure(firstError, duration: .seconds(4))
            Haptics.notification(.error)
        } else {
            Haptics.notification(.success)
        }
    }

    private func showComingSoon(_ feature: String) {
        toast = .info("\(feature) coming soon")
        Haptics.impact(.light)
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
        // VoiceOver moves into the (modal) menu; a turn later, once the overlay is in the tree.
        Task { @MainActor in AccessibilityNotification.ScreenChanged(nil).post() }

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
        let live = liveMessage(message)
        // Your own message's ticks, spelled out; a note has no reader, a tombstone no receipt.
        let receipt: MessageReceiptStatus? = live.isMine && !isNotes && !live.deleted ? live.receipt : nil
        // Only what this message can do: no Reply before it is sent, no Copy of a stand-in.
        let actions = MessageMenuAction.primary(
            canReply: live.canBeQuoted,
            canCopy: copyableText(live) != nil,
            hasLink: copyableLink(in: message) != nil,
            canShare: canShareFile(live)
        )
        return MessageMenuOverlay(
            sourceGlobalFrame: session.sourceGlobalFrame,
            isMine: message.isMine,
            cardHeight: MessageContextMenuCard.height(receipt: receipt, actions: actions),
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
                receipt: receipt,
                actions: actions,
                onAction: { action in
                    dismissMessageMenu()
                    handleMenu(action, message: message)
                },
                progress: menuProgress
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
            toast = .info("You can react once the message is sent.")
            return
        }
        Haptics.impact(.light)
        messaging.toggleReaction(emoji, on: message.id, peerUserID: peerUserID)
    }

    private func handleMenu(_ action: MessageMenuAction, message: MessagingController.ChatMessage) {
        switch action {
        case .copy:
            guard let text = copyableText(liveMessage(message)) else { return }
            UIPasteboard.general.string = text
            toast = Toast("Copied")
            Haptics.notification(.success)
        case .copyLink:
            guard let link = copyableLink(in: message) else { return }
            UIPasteboard.general.string = link
            toast = Toast("Link copied")
            Haptics.notification(.success)
        case .reply:
            startReply(to: message)
        case .share:
            // Once the lifted bubble is back in its slot, so the sheet doesn't rise over it.
            let settle = reduceMotion ? Motion.reducedDuration : Motion.menuDropDuration
            Task { @MainActor in
                try? await Task.sleep(for: .seconds(settle + 0.03))
                fileAction(.share, for: message)
            }
        case .edit, .pin, .forward, .select, .moreReactions:
            showComingSoon(action.title)
        case .delete:
            pendingDelete = PendingDelete(message: message)
        }
    }

    /// What "Copy" copies: the words the reader sees, never a stand-in (web's `copyableText`).
    ///
    /// Human: Media bubbles keep a stand-in label in `text` ("Photo", "Video", "Media",
    /// "Voice message") and a tombstone keeps "Message deleted"; putting those on the clipboard
    /// and saying "Copied" copies nothing the reader wanted. A photo or clip offers its caption,
    /// a voice note its transcript.
    /// Agent: nil means no Copy — the menu hides the row and VoiceOver gets no action.
    private func copyableText(_ m: MessagingController.ChatMessage) -> String? {
        guard !m.deleted else { return nil }
        let raw: String = switch m.kind {
        case .text, .todo: (m.text == "[Unable to decrypt]" || m.text == "[Binary message]") ? "" : m.text
        case .image: (m.text == "Photo" || m.text == "Media") ? "" : m.text
        case .video: (m.text == "Video" || m.text == "Media") ? "" : m.text
        case .voice: m.transcript ?? ""
        // A file's `text` is its caption alone; the name is not what Copy is for.
        case .file: m.text
        }
        return raw.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty ? nil : raw
    }

    /// A thread row's accessibility actions: the menu's Copy, Copy Link and Delete, for
    /// VoiceOver and Switch Control, which can't hold a bubble.
    @ViewBuilder
    private func messageActions(for message: MessagingController.ChatMessage) -> some View {
        if copyableText(message) != nil {
            Button("Copy") { handleMenu(.copy, message: message) }
        }
        if copyableLink(in: message) != nil {
            Button("Copy Link") { handleMenu(.copyLink, message: message) }
        }
        if canShareFile(message) {
            Button("Share") { fileAction(.share, for: message) }
        }
        Button("Delete") { handleMenu(.delete, message: message) }
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
        // Asked from the photo viewer: it leaves before the photo does, whichever the scope
        // (just for us, the row goes rather than turning into a tombstone).
        if viewingMedia != nil {
            withAnimation(.easeOut(duration: 0.2)) { viewingMedia = nil }
        }
        Task {
            if let error = await messaging.deleteMessage(message, scope: scope) {
                toast = .failure(error)
                Haptics.notification(.error)
            } else {
                toast = Toast(scope == .everyone ? "Deleted for everyone" : "Deleted")
                Haptics.notification(.success)
            }
        }
    }

    /// Clears every note and leaves the screen — there is nothing left to show here.
    private func deleteNotes() {
        Task {
            let outcome = await messaging.deleteConversation(peerUserID: peerUserID, scope: .me)
            if case let .failed(message) = outcome {
                toast = .failure(message)
                Haptics.notification(.error)
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

    /// Every photo in the thread, so the viewer can page through them the way Telegram does.
    ///
    /// Only downloaded photos are listed — the viewer never fetches one on its own, and a page
    /// with nothing to load would spin forever; one downloaded while the viewer is open joins on
    /// the next render. `image` is the cached full decode when there is one, never the envelope
    /// preview.
    private var mediaViewerItems: [MediaImageViewerOverlay.Item] {
        messages
            .filter { $0.kind == .image && !$0.deleted && $0.receipt != .failed && $0.imageData != nil }
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
                    image: message.imageData.flatMap { DecodedImageCache.image(for: message.id, decodedFrom: $0) },
                    data: message.imageData,
                    aspect: height > 0 ? width / height : 1
                )
            }
    }

    /// Tap on media: download if needed, otherwise open.
    private func handleMediaTap(_ message: MessagingController.ChatMessage) {
        guard !message.deleted else { return }
        // A failed send has only Retry; its caption footer still reaches the row.
        guard message.receipt != .failed else { return }
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
                // Stopped from the ring: nothing went wrong.
                if cancelledDownloadIDs.remove(message.id) != nil { return }
                let live = messaging.threads[peerUserID]?.first(where: { $0.id == message.id })
                if live?.imageData == nil {
                    toast = .failure("Could not download that photo.")
                    Haptics.notification(.error)
                } else {
                    Haptics.impact(.light)
                }
            case .video:
                await messaging.ensureVideoLoaded(for: message)
                if cancelledDownloadIDs.remove(message.id) != nil { return }
                let live = messaging.threads[peerUserID]?.first(where: { $0.id == message.id })
                if live?.videoData == nil {
                    toast = .failure("Could not download that video.")
                    Haptics.notification(.error)
                } else {
                    Haptics.impact(.light)
                }
            default:
                break
            }
        }
    }

    /// The bubble's ring X: stops the download without the "Could not download" that an
    /// empty-handed download ends with otherwise.
    ///
    /// Agent: Claims the tap, so the row's deferred tap doesn't start the download again.
    private func cancelDownload(_ message: MessagingController.ChatMessage) {
        MessageTapClaim.claim()
        if mediaDownloadIDs.contains(message.id) { cancelledDownloadIDs.insert(message.id) }
        messaging.cancelMediaDownload(messageID: message.id)
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

    // MARK: - Files

    /// A received file's open or share, held while its warning is up.
    private struct PendingFileAction: Identifiable {
        enum Kind {
            /// Quick Look (after the content check).
            case open
            /// The share sheet (Save to Files lives there; no content check needed).
            case share
        }

        let id = UUID()
        let message: MessagingController.ChatMessage
        let kind: Kind
        let warning: SharedFile.Warning
    }

    /// The document picker's picks: checked, copied in, then the photo and video compose for the
    /// images and videos Shroud can show in the chat, and the file composer for the rest.
    ///
    /// Human: One toast per pick, for the first file that can't go (or for an eleventh one);
    /// the rest still reach the composer. A photo or video picked as a file goes out like one
    /// from the library (compressed, metadata scrubbed), so it shows in the chat; only one the
    /// photo or video pipeline can't decode (a TIFF it can't read, an MKV) stays a file.
    private func loadPickedFiles(_ result: Result<[URL], any Error>) async {
        guard case let .success(urls) = result, !urls.isEmpty else { return }
        var refusal = urls.count > SharedFile.maxFilesPerSend ? SharedFile.tooManyRefusal : nil
        let kept = Array(urls.prefix(SharedFile.maxFilesPerSend))
        // Copying can mean a provider download; never on the main actor.
        let outcomes = await Task.detached(priority: .userInitiated) {
            kept.map(PickedFile.copyIn)
        }.value

        var picked: [PickedFile] = []
        for outcome in outcomes {
            switch outcome {
            case let .success(file):
                picked.append(file)
            case let .failure(reason):
                if refusal == nil { refusal = reason.message }
            }
        }
        if let refusal {
            toast = .failure(refusal, duration: .seconds(4))
            Haptics.notification(.error)
        }
        guard !picked.isEmpty else { return }

        var photos: [PickedPhoto] = []
        var videos: [PickedVideo] = []
        var files: [PickedFile] = []
        for file in picked {
            switch file.type.category {
            case .image:
                // Read and decoded off the main actor, as the library path does.
                let decoded = await Task.detached(priority: .userInitiated) { () -> (Data, UIImage)? in
                    guard let data = try? Data(contentsOf: file.url),
                          let preview = MediaCrypto.previewImage(from: data, maxEdge: 2048)
                    else { return nil }
                    return (data, preview)
                }.value
                if let (data, preview) = decoded {
                    file.cleanup()
                    photos.append(PickedPhoto(preview: preview, source: .fileData(data)))
                } else {
                    files.append(file)
                }
            case .video:
                // The clip keeps the copy in `tmp/`; the video compose removes it.
                if let probe = await VideoMedia.probe(url: file.url) {
                    let poster = await VideoMedia.posterImage(url: file.url)
                    videos.append(PickedVideo(movie: PickedMovie(url: file.url), probe: probe, poster: poster))
                } else {
                    files.append(file)
                }
            default:
                files.append(file)
            }
        }

        // Clips first, then photos, then the files (as a mixed library pick does).
        if !videos.isEmpty {
            photosAfterVideoCompose = photos
            filesAfterMediaCompose = files
            presentVideoCompose(videos)
        } else if !photos.isEmpty {
            filesAfterMediaCompose = files
            presentMediaCompose(photos)
        } else {
            stagedFiles = StagedFiles(files: files)
        }
    }

    /// The file composer's files, one per pick.
    struct StagedFiles: Identifiable {
        let id = UUID()
        var files: [PickedFile]
    }

    /// The file composer's presentation; a swipe-down closes it without sending.
    private var stagedFilesBinding: Binding<StagedFiles?> {
        Binding(
            get: { stagedFiles },
            set: { staged in
                if staged == nil { discardStagedFiles() } else { stagedFiles = staged }
            }
        )
    }

    /// The composer closed without sending: its copies go.
    private func discardStagedFiles() {
        stagedFiles?.files.forEach { $0.cleanup() }
        stagedFiles = nil
    }

    /// Sends the composed files in order; the caption and the quote go on the first only.
    /// Each bubble carries its own ring, so there is no modal spinner.
    private func sendPickedFiles(
        _ files: [PickedFile],
        caption: String,
        replyTo: MessageReplyReference?
    ) async {
        guard !files.isEmpty else { return }
        defer { files.forEach { $0.cleanup() } }
        var firstError: String?
        for (index, file) in files.enumerated() {
            let error = await messaging.sendFile(
                file,
                to: peerUserID,
                caption: index == 0 ? caption : "",
                replyTo: index == 0 ? replyTo : nil
            )
            if let error, firstError == nil { firstError = error }
        }
        if let firstError {
            toast = .failure(firstError, duration: .seconds(4))
            Haptics.notification(.error)
        } else {
            Haptics.notification(.success)
        }
    }

    private func retryFile(_ message: MessagingController.ChatMessage) {
        Task {
            let error = await messaging.retryFailedFile(messageID: message.id, peerUserID: peerUserID)
            if let error {
                toast = .failure(error)
                Haptics.notification(.error)
            } else {
                Haptics.notification(.success)
            }
        }
    }

    /// A tap that reached the row beside a file bubble: what a tap on the bubble would do.
    private func handleFileRowTap(_ message: MessagingController.ChatMessage) {
        let live = liveMessage(message)
        guard !live.deleted, live.fileType != nil else { return }
        if let transfer = messaging.mediaTransfers[live.id] {
            if !transfer.isUpload { cancelDownload(live) }
            return
        }
        if live.isMine, live.receipt == .failed {
            retryFile(live)
            return
        }
        fileAction(.open, for: live)
    }

    /// Share is offered for a file Shroud can open and that is, or can get, on this device.
    private func canShareFile(_ message: MessagingController.ChatMessage) -> Bool {
        message.kind == .file && !message.deleted && message.fileType != nil
            && (message.fileStored || message.mediaObjectId != nil)
    }

    /// Opens or shares a file — after the warning when it is a received app or macro file.
    private func fileAction(_ kind: PendingFileAction.Kind, for message: MessagingController.ChatMessage) {
        // Backstop for the release of a hold that opened the message menu.
        if kind == .open, isShowingMessageMenu { return }
        let live = liveMessage(message)
        guard !live.deleted, let type = live.fileType else { return }
        if !live.isMine, let warning = type.warning {
            pendingFileAction = PendingFileAction(message: live, kind: kind, warning: warning)
            return
        }
        performFileAction(kind, for: live)
    }

    /// Downloads when needed, decrypts into `tmp/`, then the PDF viewer, Quick Look or the share
    /// sheet.
    private func performFileAction(_ kind: PendingFileAction.Kind, for message: MessagingController.ChatMessage) {
        Task {
            var live = liveMessage(message)
            if live.needsMediaDownload {
                guard await downloadFile(live) else { return }
                live = liveMessage(message)
            }
            // The reader left the chat while it downloaded: don't open over another screen.
            guard messaging.activePeerID == peerUserID else { return }
            let id = live.id
            switch await messaging.openFile(live) {
            case .failure(.unsupported):
                return
            case .failure(.notDownloaded):
                toast = .failure("Could not download that file.")
                Haptics.notification(.error)
            case .failure(.damaged), .failure(.unreadable):
                toast = .failure("Could not open that file.")
                Haptics.notification(.error)
            case let .success(opened):
                switch kind {
                case .open:
                    // Saving and sharing stay possible; only a viewer is refused.
                    guard opened.contentMatches else {
                        messaging.releaseOpenedFile(messageID: id)
                        if let type = live.fileType {
                            toast = .failure(SharedFile.contentMismatch(type), duration: .seconds(4))
                        }
                        Haptics.notification(.error)
                        return
                    }
                    if live.fileType?.category == .pdf {
                        FileViewerPresenter.shared.pdf(opened.url, messageID: id, title: live.fileName ?? "PDF") { [messaging] in
                            messaging.releaseOpenedFile(messageID: id)
                        }
                    } else {
                        FileViewerPresenter.shared.preview(opened.url, messageID: id) { [messaging] in
                            messaging.releaseOpenedFile(messageID: id)
                        }
                    }
                case .share:
                    FileViewerPresenter.shared.share(opened.url, messageID: id, from: bubbleFrames.frames[id]) { [messaging] in
                        messaging.releaseOpenedFile(messageID: id)
                    }
                }
            }
        }
    }

    /// Fetches a file's sealed blob. RETURNS whether it is on this device now.
    private func downloadFile(_ message: MessagingController.ChatMessage) async -> Bool {
        guard !mediaDownloadIDs.contains(message.id) else { return false }
        mediaDownloadIDs.insert(message.id)
        defer { mediaDownloadIDs.remove(message.id) }
        await messaging.ensureFileLoaded(for: message)
        // Stopped from the ring: nothing went wrong.
        if cancelledDownloadIDs.remove(message.id) != nil { return false }
        guard liveMessage(message).fileStored else {
            toast = .failure("Could not download that file.")
            Haptics.notification(.error)
            return false
        }
        Haptics.impact(.light)
        return true
    }

    /// Built once: the viewer's list formats every photo in the thread on each pass.
    private static let viewerDateFormatter: DateFormatter = {
        let formatter = DateFormatter()
        formatter.locale = Locale(identifier: "en_GB")
        formatter.dateFormat = "dd.MM.yy"
        return formatter
    }()

    private static func viewerDateLine(for date: Date) -> String {
        viewerDateFormatter.string(from: date)
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
    /// A scroll to the newest message is under way (a follow, a pin, the jump control), so
    /// being off the bottom right now is not the reader's doing.
    var headingToBottom = false
}

/// The jump-to-latest control's state: whether the reader is up in the history, and what has
/// landed under them since they left the bottom.
///
/// Human: Observable, unlike `ThreadScrollState`, but only `JumpToLatestLayer` reads it, so
/// leaving the bottom or a message arriving under the reader redraws the control and not the
/// thread.
/// Agent: WRITTEN from the scroll callbacks through `setAway`; the count is derived from the
/// thread on every read, so deletes and re-keyed sends can't leave it stale.
@Observable
@MainActor
final class JumpToLatestState {
    /// The reader has scrolled off the newest message.
    private(set) var isAway = false
    /// The newest message when they left; what lands after it is counted.
    private var leftAt: RenderAnchor?

    /// The reader left the bottom (`newest` being the newest message then) or got back to it.
    func setAway(_ away: Bool, newest: MessagingController.ChatMessage?) {
        guard away != isAway else { return }
        leftAt = away ? newest.map { RenderAnchor(id: $0.id, createdAt: $0.createdAt) } : nil
        isAway = away
    }

    /// Messages from the other side that arrived after the reader left the bottom. The newest
    /// message back then is found again by its date if it has been deleted since. Tombstones
    /// don't count, nor do our own sends, which take the reader to the bottom anyway.
    func unseenCount(in messages: [MessagingController.ChatMessage]) -> Int {
        guard isAway else { return 0 }
        var start = 0
        if let leftAt {
            start = messages.lastIndex { $0.id == leftAt.id }.map { $0 + 1 }
                ?? messages.firstIndex { $0.createdAt > leftAt.createdAt }
                ?? messages.count
        }
        return messages[start...].count { !$0.isMine && !$0.deleted }
    }
}

/// Reads `JumpToLatestState`, so that only this control redraws when the reader leaves the
/// bottom or a message lands under them.
private struct JumpToLatestLayer: View {
    let state: JumpToLatestState
    let messages: [MessagingController.ChatMessage]
    /// False while something else owns the bottom of the screen.
    let isAllowed: Bool
    let action: () -> Void

    var body: some View {
        ChatJumpToLatestButton(
            isVisible: isAllowed && state.isAway,
            count: state.unseenCount(in: messages),
            action: action
        )
    }
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
