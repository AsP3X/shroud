import SwiftUI
import UIKit

/// Telegram-style text stickers: type, colour, style, then drag / pinch / rotate into place.
struct MediaTextEditor: View {
    let image: UIImage
    @Binding var edits: MediaEdits
    var onCancel: () -> Void
    var onDone: () -> Void

    @State private var overlays: [TextOverlay]
    @State private var selectedID: UUID?
    @State private var editingID: UUID?
    @State private var draftText = ""
    /// Live gesture deltas for the selected sticker, applied on top of its committed transform.
    @State private var dragTranslation: CGSize = .zero
    @State private var pinchScale: CGFloat = 1
    @State private var spinAngle: Angle = .zero

    @FocusState private var keyboardFocused: Bool

    private let chrome = Color(red: 44 / 255, green: 44 / 255, blue: 46 / 255)
    private let telegramBlue = Color(red: 51 / 255, green: 144 / 255, blue: 236 / 255)

    init(
        image: UIImage,
        edits: Binding<MediaEdits>,
        onCancel: @escaping () -> Void,
        onDone: @escaping () -> Void
    ) {
        self.image = image
        _edits = edits
        self.onCancel = onCancel
        self.onDone = onDone
        _overlays = State(initialValue: edits.wrappedValue.texts)
    }

    private var selected: TextOverlay? {
        overlays.first { $0.id == selectedID }
    }

    var body: some View {
        GeometryReader { geo in
            let area = CGSize(width: geo.size.width, height: geo.size.height - 220)
            let frame = fittedSize(in: area)
            let origin = CGPoint(x: geo.size.width / 2, y: area.height / 2 + 40)

            ZStack {
                Color.black.ignoresSafeArea()
                    .contentShape(Rectangle())
                    .onTapGesture {
                        withAnimation(Motion.snappy) { selectedID = nil }
                    }

                ZStack {
                    Image(uiImage: image)
                        .resizable()
                        .scaledToFit()
                        .frame(width: frame.width, height: frame.height)

                    ForEach(overlays) { overlay in
                        stickerView(overlay, in: frame)
                    }
                }
                .frame(width: frame.width, height: frame.height)
                .position(origin)

                if editingID != nil {
                    composerLayer
                } else {
                    VStack(spacing: 0) {
                        Spacer()
                        controls
                    }
                }
            }
        }
        .ignoresSafeArea()
        .preferredColorScheme(.dark)
        .accessibilityAddTraits(.isModal)
    }

    private func fittedSize(in area: CGSize) -> CGSize {
        let size = image.size
        guard size.width > 0, size.height > 0, area.width > 0, area.height > 0 else { return area }
        let scale = min(area.width / size.width, area.height / size.height)
        return CGSize(width: size.width * scale, height: size.height * scale)
    }

    // MARK: - Sticker

    @ViewBuilder
    private func stickerView(_ overlay: TextOverlay, in frame: CGSize) -> some View {
        let isSelected = overlay.id == selectedID
        let liveScale = isSelected ? pinchScale : 1
        let liveAngle = isSelected ? spinAngle.radians : 0
        let offset = isSelected ? dragTranslation : .zero
        let fontSize = max(10, overlay.relativeFontSize * frame.width * overlay.scale * liveScale)

        Text(overlay.string.isEmpty ? " " : overlay.string)
            .font(.system(size: fontSize, weight: .bold))
            .foregroundStyle(overlay.style == .filled ? Color(overlay.contrastUIColor) : overlay.color)
            .shadow(
                color: overlay.style == .plain ? Color.black.opacity(0.45) : .clear,
                radius: fontSize * 0.12,
                y: fontSize * 0.03
            )
            .padding(.horizontal, overlay.style == .filled ? fontSize * 0.28 : 0)
            .padding(.vertical, overlay.style == .filled ? fontSize * 0.18 : 0)
            .background {
                if overlay.style == .filled {
                    RoundedRectangle(cornerRadius: fontSize * 0.26, style: .continuous)
                        .fill(overlay.color)
                }
            }
            .overlay {
                if isSelected {
                    RoundedRectangle(cornerRadius: fontSize * 0.26, style: .continuous)
                        .stroke(Color.white.opacity(0.85), style: StrokeStyle(lineWidth: 1, dash: [5, 4]))
                        .padding(-8)
                }
            }
            .rotationEffect(.radians(overlay.rotation + liveAngle))
            .position(
                x: overlay.center.x * frame.width + offset.width,
                y: overlay.center.y * frame.height + offset.height
            )
            .animation(Motion.snappy, value: isSelected)
            .gesture(stickerGesture(overlay, frame: frame))
            .onTapGesture(count: 2) { beginEditing(overlay) }
            .onTapGesture {
                Haptics.impact(.light)
                withAnimation(Motion.snappy) { selectedID = overlay.id }
            }
    }

    private func stickerGesture(_ overlay: TextOverlay, frame: CGSize) -> some Gesture {
        let drag = DragGesture(minimumDistance: 2)
            .onChanged { value in
                if selectedID != overlay.id { selectedID = overlay.id }
                dragTranslation = value.translation
            }
            .onEnded { value in
                commitTransform(for: overlay.id, frame: frame, translation: value.translation)
            }

        let pinch = MagnifyGesture()
            .onChanged { value in
                if selectedID != overlay.id { selectedID = overlay.id }
                pinchScale = value.magnification
            }
            .onEnded { value in
                update(overlay.id) { $0.scale = max(0.25, min(6, $0.scale * value.magnification)) }
                pinchScale = 1
            }

        let spin = RotateGesture()
            .onChanged { value in
                if selectedID != overlay.id { selectedID = overlay.id }
                spinAngle = value.rotation
            }
            .onEnded { value in
                update(overlay.id) { $0.rotation += value.rotation.radians }
                spinAngle = .zero
            }

        return drag.simultaneously(with: pinch).simultaneously(with: spin)
    }

    private func commitTransform(for id: UUID, frame: CGSize, translation: CGSize) {
        guard frame.width > 0, frame.height > 0 else { return }
        update(id) {
            $0.center = CGPoint(
                x: min(max(0.02, $0.center.x + translation.width / frame.width), 0.98),
                y: min(max(0.02, $0.center.y + translation.height / frame.height), 0.98)
            )
        }
        dragTranslation = .zero
    }

    private func update(_ id: UUID, _ mutate: (inout TextOverlay) -> Void) {
        guard let index = overlays.firstIndex(where: { $0.id == id }) else { return }
        mutate(&overlays[index])
    }

    // MARK: - Text entry

    private func beginEditing(_ overlay: TextOverlay) {
        draftText = overlay.string
        editingID = overlay.id
        selectedID = overlay.id
        keyboardFocused = true
    }

    private func addSticker() {
        let overlay = TextOverlay(
            colorIndex: 0,
            center: CGPoint(x: 0.5, y: 0.45)
        )
        withAnimation(Motion.bouncy) {
            overlays.append(overlay)
        }
        beginEditing(overlay)
    }

    private var composerLayer: some View {
        ZStack {
            Color.black.opacity(0.55)
                .ignoresSafeArea()
                .contentShape(Rectangle())
                .onTapGesture { finishEditing() }

            VStack {
                Spacer()
                HStack(spacing: 10) {
                    TextField("Text", text: $draftText, axis: .vertical)
                        .font(.system(size: 17, weight: .semibold))
                        .foregroundStyle(Color.white)
                        .lineLimit(1 ... 3)
                        .focused($keyboardFocused)
                        .tint(telegramBlue)
                        .submitLabel(.done)
                        .onSubmit { finishEditing() }

                    Button {
                        Haptics.impact(.light)
                        finishEditing()
                    } label: {
                        Image(systemName: "checkmark")
                            .font(.system(size: 15, weight: .bold))
                            .foregroundStyle(Color.black)
                            .frame(width: 34, height: 34)
                            .background(Color.white)
                            .clipShape(Circle())
                    }
                    .pressable(scale: 0.85, dimming: 0)
                }
                .padding(.horizontal, 14)
                .padding(.vertical, 10)
                .background(chrome)
                .clipShape(RoundedRectangle(cornerRadius: 22, style: .continuous))
                .padding(.horizontal, 16)
                .padding(.bottom, 12)
            }
        }
        .transition(.opacity)
    }

    private func finishEditing() {
        guard let id = editingID else { return }
        let trimmed = draftText.trimmingCharacters(in: .whitespacesAndNewlines)
        withAnimation(Motion.snappy) {
            if trimmed.isEmpty {
                overlays.removeAll { $0.id == id }
                selectedID = nil
            } else {
                update(id) { $0.string = trimmed }
            }
            editingID = nil
        }
        keyboardFocused = false
    }

    // MARK: - Controls

    private var controls: some View {
        VStack(spacing: 14) {
            if let selected {
                HStack(spacing: 10) {
                    ForEach(Array(TextOverlay.palette.enumerated()), id: \.offset) { index, color in
                        Button {
                            Haptics.impact(.light)
                            withAnimation(Motion.snappy) {
                                update(selected.id) { $0.colorIndex = index }
                            }
                        } label: {
                            Circle()
                                .fill(color)
                                .frame(width: 26, height: 26)
                                .overlay {
                                    Circle().stroke(
                                        Color.white.opacity(selected.colorIndex == index ? 1 : 0.25),
                                        lineWidth: selected.colorIndex == index ? 2.5 : 1
                                    )
                                }
                                .scaleEffect(selected.colorIndex == index ? 1.15 : 1)
                        }
                        .pressable(scale: 0.85, dimming: 0)
                        .accessibilityLabel("Colour \(index + 1)")
                    }
                }
                .transition(.opacity.combined(with: .move(edge: .bottom)))
            }

            HStack(spacing: 20) {
                toolButton(systemName: "plus", label: "Add") { addSticker() }
                toolButton(
                    systemName: selected?.style.systemImage ?? "textformat",
                    label: "Style",
                    enabled: selected != nil
                ) {
                    guard let selected else { return }
                    withAnimation(Motion.snappy) {
                        update(selected.id) { $0.style = $0.style.next }
                    }
                }
                toolButton(systemName: "trash", label: "Delete", enabled: selected != nil) {
                    guard let id = selectedID else { return }
                    withAnimation(Motion.snappy) {
                        overlays.removeAll { $0.id == id }
                        selectedID = nil
                    }
                }
            }

            HStack {
                Button {
                    Haptics.impact(.light)
                    onCancel()
                } label: {
                    Text("Cancel")
                        .font(.system(size: 17, weight: .semibold))
                        .foregroundStyle(Color.white)
                        .frame(height: 44)
                        .padding(.horizontal, 8)
                }
                .pressable(scale: 0.92, dimming: 0)

                Spacer()

                Button {
                    Haptics.impact(.medium)
                    edits.texts = overlays.filter { !$0.string.isEmpty }
                    onDone()
                } label: {
                    Text("Done")
                        .font(.system(size: 17, weight: .bold))
                        .foregroundStyle(telegramBlue)
                        .frame(height: 44)
                        .padding(.horizontal, 8)
                }
                .pressable(scale: 0.92, dimming: 0)
            }
            .padding(.horizontal, 20)
        }
        .padding(.bottom, 28)
        .padding(.top, 12)
        .animation(Motion.standard, value: selectedID)
        .background(
            LinearGradient(
                colors: [Color.black.opacity(0), Color.black.opacity(0.9), Color.black],
                startPoint: .top,
                endPoint: .bottom
            )
        )
    }

    private func toolButton(
        systemName: String,
        label: String,
        enabled: Bool = true,
        action: @escaping () -> Void
    ) -> some View {
        Button(action: action) {
            VStack(spacing: 4) {
                Image(systemName: systemName)
                    .font(.system(size: 18, weight: .medium))
                    .foregroundStyle(enabled ? Color.white : Color.white.opacity(0.35))
                    .frame(width: 44, height: 44)
                    .background(chrome)
                    .clipShape(Circle())
                    .contentTransition(.symbolEffect(.replace))
                Text(label)
                    .font(.system(size: 11, weight: .medium))
                    .foregroundStyle(Color.white.opacity(enabled ? 0.7 : 0.3))
            }
        }
        .pressable(scale: 0.88, dimming: 0)
        .disabled(!enabled)
        .accessibilityLabel(label)
    }
}
