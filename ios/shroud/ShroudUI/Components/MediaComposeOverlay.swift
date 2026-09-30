import SwiftUI
import UIKit

/// A photo chosen for compose: what to show on screen, and what to actually put on the wire.
///
/// `preview` is deliberately screen-sized — the full original stays encoded in `source` until
/// send, so composing a 48 MP photo doesn't cost hundreds of megabytes of backing store.
struct PickedPhoto: Identifiable {
    /// Stable identity so per-photo edits survive reordering or removal from the send.
    let id = UUID()
    let preview: UIImage
    let source: MediaImageSource

    /// Camera captures and other in-memory images, which have no original file to preserve.
    init(image: UIImage) {
        source = .image(image)
        // Same 2048-px screen-sized preview the library path makes; the full capture stays in `source`.
        let longEdge = max(image.size.width, image.size.height)
        guard longEdge > 2048 else {
            preview = image
            return
        }
        let scale = 2048 / longEdge
        let size = CGSize(
            width: (image.size.width * scale).rounded(),
            height: (image.size.height * scale).rounded()
        )
        let format = UIGraphicsImageRendererFormat.preferred()
        format.scale = 1
        format.opaque = true
        preview = UIGraphicsImageRenderer(size: size, format: format).image { _ in
            // Drawing through UIImage also bakes in the EXIF orientation.
            image.draw(in: CGRect(origin: .zero, size: size))
        }
    }

    init(preview: UIImage, source: MediaImageSource) {
        self.preview = preview
        self.source = source
    }
}

/// Send-quality for media compose.
///
/// - **original** (default): the library file's own encoded image — no resize, no
///   re-encode, no colour-space conversion. Its location, capture time and device details are
///   removed (`MediaMetadataScrubber`). Only camera captures, outsized files (ProRAW, huge
///   panoramas) and files whose metadata can't be removed fall back to a maximum-quality re-encode.
/// - **hd**: smaller send — still sharp, but downscaled and more compressed.
enum MediaComposeQuality: String, CaseIterable, Sendable {
    /// 100% source quality — default when composing a photo.
    case original
    /// Lower-size option (still labeled HD in the badge).
    case hd

    var label: String {
        switch self {
        case .original: "Original"
        case .hd: "HD"
        }
    }

    /// Parameters for `MediaCrypto.encode`.
    ///
    /// `allowsPassthrough` is what actually makes Original lossless; the edge/compression
    /// values only apply when a source can't be shipped verbatim.
    var encodeParams: (maxEdge: CGFloat, compression: CGFloat, allowsPassthrough: Bool) {
        switch self {
        // Cap only at a pathologically large edge so normal phone photos are unscaled.
        case .original: (16_384, 1.0, true)
        case .hd: (2560, 0.85, false)
        }
    }
}

/// Telegram-style media send screen — maps to `Conversation — Media Compose` in `iOS-App.pen`.
///
/// Idle: tools + blue arrow-up send.  
/// Caption focused: morphs chrome around a **stable** `TextField` (options + emoji + white check).
///
/// Important: the caption `TextField` must stay in the hierarchy when focus changes — swapping
/// entire bars with `matchedGeometryEffect` causes freezes/crashes.
struct MediaComposeOverlay: View {
    /// Every photo staged for this send. The first is shown; the rest sit in the strip.
    let photos: [PickedPhoto]
    let peerUsername: String
    var onCancel: () -> Void
    /// Edits are handed back per photo, indexed alongside `photos`.
    var onSend: (_ caption: String, _ quality: MediaComposeQuality, _ edits: [MediaEdits]) -> Void
    /// Asks the host to open the picker again so more photos can join this send.
    var onAddMore: (() -> Void)?
    var onRemovePhoto: ((Int) -> Void)?

    @State private var caption = ""
    @State private var quality: MediaComposeQuality = .original
    @State private var toolBanner: String?
    @State private var keyboardHeight: CGFloat = 0
    @FocusState private var captionFocused: Bool

    /// The photo on screen, by identity — an index would slide onto a neighbour when a photo to
    /// its left leaves the strip. Nil until the user picks one, which means the first.
    @State private var selectedID: UUID?
    /// Non-destructive edits per photo id — keyed by identity, not position, so removing one
    /// photo from the strip can't hand its crop to a different picture.
    @State private var edits: [UUID: MediaEdits] = [:]
    /// Rendered previews (edits baked into the screen-sized copy), keyed by photo id.
    @State private var renderedPreviews: [UUID: UIImage] = [:]
    @State private var activeEditor: Editor?
    /// The draw/text editor's canvas, rendered off the main actor before the cover rises.
    @State private var editorBase: UIImage?
    @State private var showFilters = false
    @State private var confirmClearEdits = false

    /// Index into `photos` currently on screen.
    private var selection: Int {
        photos.firstIndex { $0.id == selectedID } ?? 0
    }

    private enum Editor: String, Identifiable {
        case crop, draw, text
        var id: String { rawValue }
    }

    private let chrome = Color(red: 44 / 255, green: 44 / 255, blue: 46 / 255)
    private let telegramBlue = Color(red: 51 / 255, green: 144 / 255, blue: 236 / 255)

    /// UI morph driven only by focus (not keyboard height) to avoid layout thrash mid-animation.
    private var isFocused: Bool { captionFocused }

    private var windowTopInset: CGFloat {
        Self.keyWindowSafeArea.top
    }

    private var windowBottomInset: CGFloat {
        Self.keyWindowSafeArea.bottom
    }

    private static var keyWindowSafeArea: UIEdgeInsets {
        let scenes = UIApplication.shared.connectedScenes.compactMap { $0 as? UIWindowScene }
        let window = scenes.flatMap(\.windows).first(where: \.isKeyWindow)
            ?? scenes.flatMap(\.windows).first
        return window?.safeAreaInsets ?? UIEdgeInsets(top: 59, left: 0, bottom: 34, right: 0)
    }

    private func bottomChromePadding(homeInset: CGFloat) -> CGFloat {
        if keyboardHeight > 0 { return keyboardHeight }
        return max(homeInset, 8)
    }

    var body: some View {
        GeometryReader { geo in
            let topInset = max(geo.safeAreaInsets.top, windowTopInset, 47)
            let homeInset = max(geo.safeAreaInsets.bottom, windowBottomInset, 8)

            ZStack {
                Color.black.ignoresSafeArea()
                    .contentShape(Rectangle())
                    .onTapGesture { dismissCaptionKeyboard() }

                VStack(spacing: 0) {
                    topChrome(topInset: topInset)
                        .opacity(isFocused ? 0.35 : 1)

                    editChipRow
                        .opacity(isFocused ? 0 : 1)
                        .frame(height: isFocused ? 0 : nil)
                        .clipped()
                        .allowsHitTesting(!isFocused)

                    Image(uiImage: displayedImage)
                        .resizable()
                        .scaledToFit()
                        .frame(maxWidth: .infinity, maxHeight: .infinity)
                        .clipped()
                        .id(currentPhoto?.id)
                        // Edits land as a cross-dissolve rather than a snap.
                        .transition(.opacity)
                        .animation(Motion.fade, value: currentEdits)
                        .contentShape(Rectangle())
                        .onTapGesture { dismissCaptionKeyboard() }

                    bottomChrome(bottomPadding: bottomChromePadding(homeInset: homeInset))
                        // Rides 12 pt above the caption row, so it clears the keyboard and the
                        // filter strip in every state.
                        .overlay(alignment: .top) {
                            if let toolBanner {
                                Text(toolBanner)
                                    .font(.system(size: 14, weight: .semibold))
                                    .foregroundStyle(Color.white)
                                    .padding(.horizontal, 16)
                                    .padding(.vertical, 10)
                                    .background(chrome.opacity(0.95))
                                    .clipShape(Capsule())
                                    .alignmentGuide(.top) { $0[.bottom] + 12 }
                                    .transition(.opacity)
                                    .allowsHitTesting(false)
                            }
                        }
                }
                // Focus changes arrive without a transaction; animate the whole column so the chip
                // row, top bar and photo move with the bottom chrome instead of snapping.
                .animation(Motion.scrim, value: isFocused)
            }
        }
        .fullScreenCover(item: $activeEditor, onDismiss: { editorBase = nil }) { editor in
            editorScreen(editor)
        }
        .ignoresSafeArea()
        .preferredColorScheme(.dark)
        .accessibilityAddTraits(.isModal)
        .onAppear { syncEdits() }
        .onChange(of: photos.count) { _, _ in syncEdits() }
        .task(id: RenderKey(photo: currentPhoto?.id, edits: currentEdits)) {
            await renderPreview()
        }
        .onReceive(NotificationCenter.default.publisher(for: UIResponder.keyboardWillChangeFrameNotification)) { note in
            updateKeyboardHeight(from: note)
        }
        .onReceive(NotificationCenter.default.publisher(for: UIResponder.keyboardWillHideNotification)) { note in
            let duration = (note.userInfo?[UIResponder.keyboardAnimationDurationUserInfoKey] as? Double) ?? 0.25
            withAnimation(.easeOut(duration: duration)) {
                keyboardHeight = 0
            }
        }
    }

    // MARK: - Top

    private func topChrome(topInset: CGFloat) -> some View {
        VStack(spacing: 0) {
            Color.clear.frame(height: topInset)

            HStack(spacing: 8) {
                HStack(spacing: 6) {
                    Image(systemName: "arrow.up")
                        .font(.system(size: 14, weight: .semibold))
                        .foregroundStyle(Color.white)
                    Text(peerUsername)
                        .font(.system(size: 16, weight: .semibold))
                        .foregroundStyle(Color.white)
                        .lineLimit(1)
                }
                .accessibilityElement(children: .combine)
                .accessibilityLabel("Sending to \(peerUsername)")

                Spacer(minLength: 8)

                Button {
                    dismissCaptionKeyboard()
                    onAddMore?()
                } label: {
                    HStack(spacing: 5) {
                        Image(systemName: "plus")
                            .font(.system(size: 13, weight: .bold))
                        Text("Add")
                            .font(.system(size: 14, weight: .semibold))
                    }
                    .foregroundStyle(Color.white)
                    .padding(.horizontal, 12)
                    .frame(height: 30)
                    .background(chrome)
                    .clipShape(Capsule())
                    // A 44 pt target without making the capsule itself taller.
                    .contentShape(Capsule().inset(by: -7))
                }
                .pressable(scale: 0.9, dimming: 0)
                // The host passes nil once the send is full, so Add reads as unavailable.
                .disabled(onAddMore == nil)
                .opacity(onAddMore == nil ? 0.4 : 1)
                .accessibilityLabel("Add more photos")
            }
            .padding(.horizontal, 16)
            .padding(.bottom, 10)
        }
        .frame(maxWidth: .infinity)
        .background(Color.black)
    }

    /// Edited-state chip plus the multi-photo strip.
    private var editChipRow: some View {
        VStack(spacing: 8) {
            HStack(spacing: 8) {
                if currentEdits.isIdentity {
                    chip(icon: "wand.and.stars", text: "NO EDITS", active: false)
                } else {
                    Button {
                        // Crop, drawing, text and filter go in one tap, so ask first.
                        confirmClearEdits = true
                    } label: {
                        chip(icon: "arrow.uturn.backward", text: "EDITED", active: true)
                            .contentShape(Capsule().inset(by: -10))
                    }
                    .pressable(scale: 0.9)
                    .accessibilityLabel("Clear edits")
                    // iOS 26 draws this as a callout on the chip and omits Cancel; tapping
                    // outside cancels.
                    .confirmationDialog(
                        "Clear all edits to this photo?",
                        isPresented: $confirmClearEdits,
                        titleVisibility: .visible
                    ) {
                        Button("Clear Edits", role: .destructive) {
                            withAnimation(Motion.standard) {
                                setEdits(MediaEdits())
                            }
                            flashToolBanner("Edits cleared")
                        }
                        Button("Cancel", role: .cancel) {}
                    } message: {
                        Text("The crop, drawing, text and filter on this photo will be removed.")
                    }
                    .transition(.scale(scale: 0.85).combined(with: .opacity))
                }

                Spacer(minLength: 0)
            }
            .animation(Motion.snappy, value: currentEdits.isIdentity)

            if photos.count > 1 {
                photoStrip
                    .transition(.opacity.combined(with: .move(edge: .top)))
            }
        }
        .padding(.horizontal, 16)
        .padding(.top, 8)
        .padding(.bottom, 8)
        .animation(Motion.standard, value: photos.count)
    }

    private func chip(icon: String, text: String, active: Bool) -> some View {
        HStack(spacing: 4) {
            Image(systemName: icon)
                .font(.system(size: 11, weight: .semibold))
            Text(text)
                .font(.system(size: 11, weight: .semibold))
        }
        .foregroundStyle(active ? telegramBlue : Color.white.opacity(0.85))
        .padding(.horizontal, 10)
        .padding(.vertical, 6)
        .background(chrome.opacity(0.9))
        .clipShape(Capsule())
    }

    /// Thumbnails of everything queued for this send; tap to switch, long-press to remove.
    private var photoStrip: some View {
        ScrollView(.horizontal, showsIndicators: false) {
            HStack(spacing: 8) {
                ForEach(Array(photos.enumerated()), id: \.element.id) { index, photo in
                    Button {
                        dismissCaptionKeyboard()
                        withAnimation(Motion.standard) { selectedID = photo.id }
                    } label: {
                        Image(uiImage: renderedPreviews[photo.id] ?? photo.preview)
                            .resizable()
                            .scaledToFill()
                            .frame(width: 54, height: 54)
                            .clipShape(RoundedRectangle(cornerRadius: 8, style: .continuous))
                            .overlay {
                                RoundedRectangle(cornerRadius: 8, style: .continuous)
                                    .stroke(
                                        index == selection ? telegramBlue : Color.clear,
                                        lineWidth: 2.5
                                    )
                            }
                            .scaleEffect(index == selection ? 1.05 : 1)
                    }
                    .pressable(scale: 0.9, dimming: 0)
                    .contextMenu {
                        Button(role: .destructive) {
                            removePhoto(at: index)
                        } label: {
                            Label("Remove", systemImage: "trash")
                        }
                    }
                    .accessibilityLabel("Photo \(index + 1) of \(photos.count)")
                    .accessibilityAddTraits(index == selection ? [.isSelected] : [])
                    .accessibilityAction(named: "Remove") { removePhoto(at: index) }
                }
            }
            // Margin inside the scroll content, so the selected ring at either end isn't clipped.
            .padding(.horizontal, 16)
            .padding(.vertical, 4)
        }
        .frame(height: 62)
        // Cancels editChipRow's gutter: the strip scrolls edge to edge, thumbnails still line
        // up with the chip.
        .padding(.horizontal, -16)
        .animation(Motion.snappy, value: selection)
    }

    /// Keeps the photo on screen when another one leaves; removing the one on screen moves to
    /// its right-hand neighbour (or the left one at the end).
    private func removePhoto(at index: Int) {
        guard let onRemovePhoto else { return }
        if index == selection {
            selectedID = (photos[safe: index + 1] ?? photos[safe: index - 1])?.id
        }
        onRemovePhoto(index)
    }

    // MARK: - Bottom (single TextField; chrome morphs around it)

    private func bottomChrome(bottomPadding: CGFloat) -> some View {
        VStack(spacing: 12) {
            // One row: leading control | stable caption field | trailing control
            HStack(spacing: 10) {
                leadingControl
                    .animation(.easeOut(duration: 0.2), value: isFocused)

                captionField
                    .frame(maxWidth: .infinity)

                trailingControl
                    .animation(.easeOut(duration: 0.2), value: isFocused)
            }

            // Idle-only tool strip (hidden when focused, not removed from identity of TextField).
            if !isFocused {
                VStack(spacing: 12) {
                    if showFilters {
                        MediaFilterStrip(
                            image: photos[safe: selection]?.preview ?? UIImage(),
                            filter: Binding(
                                get: { currentEdits.filter },
                                set: { value in updateEdits { $0.filter = value } }
                            ),
                            intensity: Binding(
                                get: { currentEdits.filterIntensity },
                                set: { value in updateEdits { $0.filterIntensity = value } }
                            )
                        )
                        .padding(.horizontal, -12)
                        .transition(.opacity.combined(with: .move(edge: .bottom)))
                    }
                    idleToolsRow
                }
                .transition(.opacity)
            }

            Color.clear.frame(height: bottomPadding)
        }
        .padding(.horizontal, 12)
        .padding(.top, 10)
        .frame(maxWidth: .infinity)
        .background(Color.black)
        // Same as the column's; kept here so it outranks the keyboard-height animation below.
        .animation(Motion.scrim, value: isFocused)
        .animation(.easeOut(duration: 0.25), value: keyboardHeight)
    }

    /// Idle: no leading control beside the field (tools are below). Absent rather than zero-width,
    /// so the HStack adds no spacing and the field lines up with the tools row.
    @ViewBuilder
    private var leadingControl: some View {
        if isFocused {
            // Caption options (captions_focused.PNG left circle)
            Button {
                // The banner alone: the host's toast would land on top of the caption row.
                flashToolBanner("Caption options coming soon")
            } label: {
                Image(systemName: "list.bullet")
                    .font(.system(size: 17, weight: .semibold))
                    .foregroundStyle(Color.white)
                    .frame(width: 44, height: 44)
                    .background(chrome)
                    .clipShape(Circle())
            }
            .pressable(scale: 0.85, dimming: 0)
            .accessibilityLabel("Caption options")
            .transition(.scale.combined(with: .opacity))
        }
    }

    @ViewBuilder
    private var trailingControl: some View {
        if isFocused {
            // White check = Done (dismiss keyboard)
            Button {
                dismissCaptionKeyboard()
            } label: {
                Image(systemName: "checkmark")
                    .font(.system(size: 17, weight: .bold))
                    .foregroundStyle(Color.black)
                    .frame(width: 44, height: 44)
                    .background(Color.white)
                    .clipShape(Circle())
            }
            .pressable(scale: 0.85, dimming: 0)
            .accessibilityLabel("Done")
            .transition(.scale.combined(with: .opacity))
        } else {
            // Idle: blue send (same arrow.up as chat composer)
            Button {
                dismissCaptionKeyboard()
                onSend(caption, quality, photos.map { edits[$0.id] ?? MediaEdits() })
            } label: {
                Image(systemName: "arrow.up")
                    .font(.system(size: 18, weight: .bold))
                    .foregroundStyle(Color.white)
                    .frame(width: 50, height: 50)
                    .background(telegramBlue)
                    .clipShape(Circle())
            }
            .pressable(scale: 0.85, dimming: 0, haptic: .medium)
            .accessibilityLabel(photos.count > 1 ? "Send \(photos.count) photos" : "Send")
            .transition(.scale.combined(with: .opacity))
        }
    }

    /// Stable caption field — never destroyed on focus change.
    private var captionField: some View {
        HStack(spacing: 10) {
            TextField("Add a caption...", text: $caption, axis: .vertical)
                .font(.system(size: 16))
                .foregroundStyle(Color.white)
                .lineLimit(1 ... 4)
                .focused($captionFocused)
                .tint(telegramBlue)

            if isFocused {
                Button {
                    Haptics.impact(.light)
                    flashToolBanner("Emoji keyboard: use the globe key")
                } label: {
                    Image(systemName: "face.smiling")
                        .font(.system(size: 20, weight: .regular))
                        .foregroundStyle(Color.white.opacity(0.85))
                        .frame(width: 28, height: 28)
                        .contentShape(Rectangle().inset(by: -8))
                }
                .pressable(scale: 0.85, haptic: nil)
                .accessibilityLabel("Emoji")
                .transition(.opacity)
            } else {
                Text("\(photos.count)")
                    .font(.system(size: 12, weight: .semibold))
                    .foregroundStyle(Color.white)
                    .contentTransition(.numericText())
                    .frame(width: 22, height: 22)
                    .overlay {
                        Circle()
                            .stroke(Color.white.opacity(0.4), lineWidth: 1.5)
                    }
                    .accessibilityLabel("\(photos.count) photo\(photos.count == 1 ? "" : "s")")
                    .transition(.opacity)
            }
        }
        .padding(.horizontal, 14)
        .padding(.vertical, 10)
        .frame(minHeight: 44)
        .background(chrome)
        .clipShape(Capsule())
    }

    private var idleToolsRow: some View {
        HStack(spacing: 0) {
            ScrollView(.horizontal, showsIndicators: false) {
                HStack(spacing: 8) {
                    toolCircle(systemName: "chevron.left", label: "Back") {
                        dismissCaptionKeyboard()
                        onCancel()
                    }
                    toolCircle(
                        systemName: "crop.rotate",
                        label: "Crop",
                        active: currentEdits.hasCrop
                    ) {
                        dismissCaptionKeyboard()
                        openEditor(.crop)
                    }
                    toolCircle(
                        systemName: "pencil.tip.crop.circle",
                        label: "Draw",
                        active: currentEdits.hasDrawing
                    ) {
                        dismissCaptionKeyboard()
                        openEditor(.draw)
                    }
                    toolCircle(
                        systemName: "textformat",
                        label: "Text",
                        active: !currentEdits.texts.isEmpty
                    ) {
                        dismissCaptionKeyboard()
                        openEditor(.text)
                    }
                    toolCircle(
                        systemName: "slider.horizontal.3",
                        label: "Filters",
                        active: currentEdits.filter != .none || showFilters
                    ) {
                        dismissCaptionKeyboard()
                        withAnimation(Motion.standard) { showFilters.toggle() }
                    }
                    qualityBadge
                }
            }
            Spacer(minLength: 0)
        }
    }

    // MARK: - Editors

    /// Crop opens at once. Draw and Text first render their canvas off the main actor, so the tap
    /// doesn't stall and the cover's builder never renders (it re-runs on every overlay update).
    private func openEditor(_ editor: Editor) {
        withAnimation(Motion.standard) { showFilters = false }
        guard editor != .crop, let photo = currentPhoto else {
            activeEditor = editor
            return
        }
        let base = annotationEdits(includingDrawing: editor == .text)
        let source = photo.preview
        let photoID = photo.id
        Task {
            let image = await Task.detached(priority: .userInitiated) {
                MediaEditRenderer.render(source, edits: base)
            }.value
            // A second tap, or a switch to another photo, may have landed meanwhile.
            guard activeEditor == nil, currentPhoto?.id == photoID else { return }
            editorBase = image
            activeEditor = editor
        }
    }

    /// Editors are presented as full-screen covers rather than stacked layers — they are modal
    /// tasks with their own Cancel/Done, and the cover gives them the rise-and-settle transition
    /// for free.
    @ViewBuilder
    private func editorScreen(_ editor: Editor) -> some View {
        if let photo = photos[safe: selection] {
            let binding = Binding<MediaEdits>(
                get: { currentEdits },
                set: { setEdits($0) }
            )

            switch editor {
            case .crop:
                MediaCropEditor(
                    image: photo.preview,
                    edits: binding,
                    onCancel: closeEditor,
                    onDone: closeEditor
                )
            case .draw:
                // The canvas re-draws the existing strokes itself, so the base must not
                // already contain them. `openEditor` renders it; the fallback never normally runs.
                MediaDrawEditor(
                    image: editorBase ?? annotationBase(includingDrawing: false),
                    edits: binding,
                    onCancel: closeEditor,
                    onDone: closeEditor
                )
            case .text:
                // Stickers sit above the markup, so the markup belongs in the base here.
                MediaTextEditor(
                    image: editorBase ?? annotationBase(includingDrawing: true),
                    edits: binding,
                    onCancel: closeEditor,
                    onDone: closeEditor
                )
            }
        }
    }

    private func closeEditor() {
        activeEditor = nil
    }

    /// The photo with crop, rotation and filter applied — the canvas an annotation tool paints
    /// onto. Existing stickers are always dropped; the markup is optional (see call sites).
    private func annotationBase(includingDrawing: Bool) -> UIImage {
        guard let photo = photos[safe: selection] else { return UIImage() }
        return MediaEditRenderer.render(photo.preview, edits: annotationEdits(includingDrawing: includingDrawing))
    }

    /// The edits baked into an annotation canvas: everything but the stickers, and the markup
    /// only when asked for.
    private func annotationEdits(includingDrawing: Bool) -> MediaEdits {
        var base = currentEdits
        if !includingDrawing { base.drawing = nil }
        base.texts = []
        return base
    }

    private var qualityBadge: some View {
        Button {
            dismissCaptionKeyboard()
            withAnimation(.easeInOut(duration: 0.15)) {
                // Default is Original (100%); tap toggles down to HD.
                quality = quality == .original ? .hd : .original
            }
            flashToolBanner(
                quality == .original ? "Original quality · location removed" : "HD — smaller file"
            )
        } label: {
            Text(quality.label)
                .font(.system(size: 11, weight: .bold))
                .minimumScaleFactor(0.7)
                .lineLimit(1)
                .foregroundStyle(quality == .original ? telegramBlue : Color.white)
                // Inset so "Original" scales down inside the ring instead of touching it.
                .padding(.horizontal, 4)
                .frame(width: 44, height: 44)
                .contentShape(Circle())
                .overlay {
                    Circle()
                        .stroke(
                            quality == .original ? telegramBlue.opacity(0.85) : Color.clear,
                            lineWidth: 1.5
                        )
                }
        }
        // Glass like the tool circles beside it.
        .buttonStyle(PressableButtonStyle(scale: 1, dimming: 0))
        .glassEffect(.regular.interactive(), in: .circle)
        .accessibilityLabel("Quality \(quality.label)")
        .accessibilityHint("Tap to switch between Original and HD")
    }

    private func toolCircle(
        systemName: String,
        label: String,
        active: Bool = false,
        action: @escaping () -> Void
    ) -> some View {
        Button {
            Haptics.impact(.light)
            action()
        } label: {
            Image(systemName: systemName)
                .font(.system(size: 17, weight: .medium))
                // A tool that has been used stays lit, so the toolbar shows what's applied.
                .foregroundStyle(active ? telegramBlue : Color.white)
                .frame(width: 44, height: 44)
                .contentShape(Circle())
                .overlay {
                    Circle()
                        .stroke(active ? telegramBlue.opacity(0.85) : Color.clear, lineWidth: 1.5)
                }
        }
        // Liquid Glass tool circles over the photo, as the system's own editors draw them.
        .buttonStyle(PressableButtonStyle(scale: 1, dimming: 0, haptic: nil))
        .glassEffect(.regular.interactive(), in: .circle)
        .accessibilityLabel(label)
        .accessibilityAddTraits(active ? [.isSelected] : [])
        .animation(Motion.snappy, value: active)
    }

    // MARK: - Edit state

    private var currentPhoto: PickedPhoto? { photos[safe: selection] }

    /// What the big preview shows: the rendered result when one is ready, the raw photo until then.
    private var displayedImage: UIImage {
        guard let photo = currentPhoto else { return UIImage() }
        return renderedPreviews[photo.id] ?? photo.preview
    }

    private var currentEdits: MediaEdits {
        guard let photo = currentPhoto else { return MediaEdits() }
        return edits[photo.id] ?? MediaEdits()
    }

    private func setEdits(_ value: MediaEdits) {
        guard let photo = currentPhoto else { return }
        edits[photo.id] = value
    }

    private func updateEdits(_ mutate: (inout MediaEdits) -> Void) {
        var value = currentEdits
        mutate(&value)
        setEdits(value)
    }

    /// Drops edits and renders for photos that have left the strip. `selection` needs no fix-up:
    /// it follows `selectedID`, and falls back to the first photo if that one is gone.
    private func syncEdits() {
        let live = Set(photos.map(\.id))
        edits = edits.filter { live.contains($0.key) }
        renderedPreviews = renderedPreviews.filter { live.contains($0.key) }
    }

    /// Identity of a render request — a new value means the visible photo needs re-rendering.
    private struct RenderKey: Equatable {
        let photo: UUID?
        let edits: MediaEdits
    }

    /// Bakes the current edits into the screen-sized preview, off the main actor.
    private func renderPreview() async {
        guard let photo = currentPhoto else { return }
        let snapshot = currentEdits
        guard !snapshot.isIdentity else {
            renderedPreviews[photo.id] = photo.preview
            return
        }
        // Coalesce slider scrubs: .task(id:) cancels this on the next key, so only a settled edit
        // renders (the detached render below can't be cancelled once it starts).
        try? await Task.sleep(for: .milliseconds(40))
        guard !Task.isCancelled else { return }
        let source = photo.preview
        let rendered = await Task.detached(priority: .userInitiated) {
            MediaEditRenderer.render(source, edits: snapshot)
        }.value
        // A later edit may have landed while this was rendering; only publish if still current.
        guard snapshot == currentEdits else { return }
        renderedPreviews[photo.id] = rendered
    }

    private func dismissCaptionKeyboard() {
        captionFocused = false
    }

    private func updateKeyboardHeight(from notification: Notification) {
        guard
            let frame = notification.userInfo?[UIResponder.keyboardFrameEndUserInfoKey] as? CGRect
        else { return }

        let duration = (notification.userInfo?[UIResponder.keyboardAnimationDurationUserInfoKey] as? Double)
            ?? 0.25
        // Prefer the active window scene's screen (UIScreen.main is deprecated in iOS 26).
        let screenHeight = UIApplication.shared.connectedScenes
            .compactMap { $0 as? UIWindowScene }
            .first(where: { $0.activationState == .foregroundActive })?
            .screen.bounds.height
            ?? frame.maxY
        let overlap = max(0, screenHeight - frame.origin.y)

        // Avoid animating layout while the TextField is mid-focus if height is unchanged.
        guard abs(overlap - keyboardHeight) > 0.5 else { return }

        withAnimation(.easeOut(duration: duration)) {
            keyboardHeight = overlap
        }
    }

    private func flashToolBanner(_ text: String) {
        withAnimation(.easeOut(duration: 0.15)) {
            toolBanner = text
        }
        Task {
            try? await Task.sleep(nanoseconds: 1_600_000_000)
            await MainActor.run {
                withAnimation(.easeOut(duration: 0.2)) {
                    if toolBanner == text { toolBanner = nil }
                }
            }
        }
    }
}

extension Array {
    /// Bounds-checked lookup — the compose strip indexes into arrays the host is mutating.
    subscript(safe index: Int) -> Element? {
        indices.contains(index) ? self[index] : nil
    }
}

#Preview("Media compose") {
    MediaComposeOverlay(
        photos: [PickedPhoto(image: UIImage(systemName: "photo")!)],
        peerUsername: "Jane Cooper",
        onCancel: {},
        onSend: { _, _, _ in }
    )
}
