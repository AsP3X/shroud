import PencilKit
import SwiftUI
import UIKit

/// Telegram-style markup over a photo, backed by PencilKit.
///
/// Human: PencilKit gives pressure, smoothing, a real eraser and undo for free — reimplementing
/// stroke rendering by hand would look worse and cost more. The canvas is laid out to exactly
/// cover the photo, and strokes are stored in the unit square so they survive any output size.
struct MediaDrawEditor: View {
    let image: UIImage
    @Binding var edits: MediaEdits
    var onCancel: () -> Void
    var onDone: () -> Void

    @State private var canvas: PKCanvasView = EditorCanvasView()
    /// Owned per-editor; `PKToolPicker.shared(for:)` is deprecated.
    @State private var toolPicker = PKToolPicker()
    @State private var toolPickerVisible = false
    /// Undo follows the canvas's own undo stack; Clear follows whether there is anything to clear.
    @State private var canUndo = false
    @State private var hasStrokes = false
    /// How far the docked tool picker reaches up from the bottom edge (0 while it's hidden).
    @State private var pickerInset: CGFloat = 0

    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    private let chrome = Color(red: 44 / 255, green: 44 / 255, blue: 46 / 255)
    private let telegramBlue = Color(red: 51 / 255, green: 144 / 255, blue: 236 / 255)

    var body: some View {
        GeometryReader { geo in
            // Clear of the status bar and Dynamic Island, like the compose screen; the bottom
            // edge stays 170 pt up for the controls.
            let top = max(geo.safeAreaInsets.top, Self.keyWindowSafeArea.top, 47) + 8
            let canvasArea = CGSize(width: geo.size.width, height: geo.size.height - 170 - top)
            let frame = fittedFrame(in: canvasArea)

            ZStack {
                Color.black.ignoresSafeArea()

                ZStack {
                    Image(uiImage: image)
                        .resizable()
                        .scaledToFit()
                        .frame(width: frame.width, height: frame.height)

                    DrawingCanvas(
                        canvas: canvas,
                        toolPicker: toolPicker,
                        drawing: edits.drawing,
                        onChange: { drawing in
                            hasStrokes = !drawing.strokes.isEmpty
                            refreshUndo()
                        },
                        onPickerChange: { isVisible, inset in
                            toolPickerVisible = isVisible
                            guard inset != pickerInset else { return }
                            withAnimation(Motion.respecting(reduceMotion, Motion.standard)) {
                                pickerInset = inset
                            }
                        }
                    )
                    .frame(width: frame.width, height: frame.height)
                }
                .position(x: geo.size.width / 2, y: top + canvasArea.height / 2)

                VStack(spacing: 0) {
                    Spacer()
                    controls
                }
            }
        }
        .ignoresSafeArea()
        .preferredColorScheme(.dark)
        .accessibilityAddTraits(.isModal)
    }

    private static var keyWindowSafeArea: UIEdgeInsets {
        let scenes = UIApplication.shared.connectedScenes.compactMap { $0 as? UIWindowScene }
        let window = scenes.flatMap(\.windows).first(where: \.isKeyWindow)
            ?? scenes.flatMap(\.windows).first
        return window?.safeAreaInsets ?? UIEdgeInsets(top: 59, left: 0, bottom: 34, right: 0)
    }

    /// Reads the stack a main-queue turn later, in case PencilKit registers the undo after
    /// telling us the drawing changed.
    private func refreshUndo() {
        Task { @MainActor in
            canUndo = canvas.undoManager?.canUndo ?? false
        }
    }

    private func fittedFrame(in area: CGSize) -> CGSize {
        let size = image.size
        guard size.width > 0, size.height > 0, area.width > 0, area.height > 0 else { return area }
        let scale = min(area.width / size.width, area.height / size.height)
        return CGSize(width: size.width * scale, height: size.height * scale)
    }

    private var controls: some View {
        VStack(spacing: 14) {
            HStack(spacing: 20) {
                toolButton(systemName: "arrow.uturn.backward", label: "Undo", enabled: canUndo) {
                    canvas.undoManager?.undo()
                    refreshUndo()
                }
                toolButton(
                    systemName: "pencil.tip",
                    label: "Tools",
                    enabled: true,
                    selected: toolPickerVisible
                ) {
                    toolPickerVisible.toggle()
                    updateToolPicker()
                }
                toolButton(systemName: "trash", label: "Clear", enabled: hasStrokes) {
                    // Undoable instead of confirmed: a mis-tap costs one Undo, not the markup.
                    let previous = canvas.drawing
                    canvas.undoManager?.registerUndo(withTarget: canvas) { $0.drawing = previous }
                    canvas.undoManager?.setActionName("Clear")
                    canvas.drawing = PKDrawing()
                    refreshUndo()
                }
            }
            .animation(Motion.snappy, value: canUndo)
            .animation(Motion.snappy, value: hasStrokes)

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
                .pressable(scale: 0.92, dimming: 0, haptic: nil)

                Spacer()

                Button {
                    Haptics.impact(.medium)
                    commit()
                    onDone()
                } label: {
                    Text("Done")
                        .font(.system(size: 17, weight: .bold))
                        .foregroundStyle(telegramBlue)
                        .frame(height: 44)
                        .padding(.horizontal, 8)
                }
                .pressable(scale: 0.92, dimming: 0, haptic: nil)
            }
            .padding(.horizontal, 20)
        }
        // Rides above the docked tool picker so Cancel / Done stay reachable while it's open.
        .padding(.bottom, pickerInset > 0 ? pickerInset + 12 : 28)
        .padding(.top, 12)
        .background(
            LinearGradient(
                colors: [Color.black.opacity(0), Color.black.opacity(0.9), Color.black],
                startPoint: .top,
                endPoint: .bottom
            )
            // Lifted over the photo while the picker is open; strokes still land between buttons.
            .allowsHitTesting(false)
        )
    }

    private func toolButton(
        systemName: String,
        label: String,
        enabled: Bool,
        selected: Bool = false,
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
                Text(label)
                    .font(.system(size: 11, weight: .medium))
                    .foregroundStyle(Color.white.opacity(enabled ? 0.7 : 0.3))
            }
        }
        .pressable(scale: 0.88, dimming: 0)
        .disabled(!enabled)
        .accessibilityLabel(label)
        .accessibilityAddTraits(selected ? .isSelected : [])
    }

    private func updateToolPicker() {
        // Swatches show ink as the light-styled canvas draws it (and as it's sent), while the
        // picker's own chrome stays dark to match the editor.
        toolPicker.colorUserInterfaceStyle = .light
        toolPicker.overrideUserInterfaceStyle = .dark
        toolPicker.setVisible(toolPickerVisible, forFirstResponder: canvas)
        // Idempotent (observers are held weakly); from here on the canvas draws the picker's tool.
        toolPicker.addObserver(canvas)
        if toolPickerVisible { canvas.becomeFirstResponder() }
    }

    /// Stores the strokes normalised to the unit square so the renderer can scale them to
    /// whatever the full-resolution output turns out to be.
    private func commit() {
        let drawing = canvas.drawing
        guard !drawing.strokes.isEmpty else {
            edits.drawing = nil
            return
        }
        let size = canvas.bounds.size
        guard size.width > 0, size.height > 0 else {
            edits.drawing = drawing.dataRepresentation()
            return
        }
        let normalised = drawing.transformed(using: CGAffineTransform(
            scaleX: 1 / size.width,
            y: 1 / size.height
        ))
        edits.drawing = normalised.dataRepresentation()
    }
}

/// A canvas with its own undo stack, so Undo walks only this session's strokes and never pops
/// the window's shared manager (the caption field and earlier editor sessions use that one).
private final class EditorCanvasView: PKCanvasView {
    private let stack = UndoManager()

    override var undoManager: UndoManager? { stack }
}

/// Hosts a `PKCanvasView` sized to the photo, restoring any strokes already made.
private struct DrawingCanvas: UIViewRepresentable {
    let canvas: PKCanvasView
    let toolPicker: PKToolPicker
    let drawing: Data?
    var onChange: (PKDrawing) -> Void
    /// Whether the tool picker shows, and how far it reaches up from the window's bottom edge.
    var onPickerChange: (_ isVisible: Bool, _ inset: CGFloat) -> Void

    func makeUIView(context: Context) -> PKCanvasView {
        canvas.backgroundColor = .clear
        canvas.isOpaque = false
        canvas.drawingPolicy = .anyInput
        canvas.delegate = context.coordinator
        canvas.tool = PKInkingTool(.pen, color: .systemRed, width: 12)
        // PencilKit adapts ink for dark mode (black shows as white), but the renderer bakes it in
        // its light colours; a light canvas shows the strokes as they will be sent.
        canvas.overrideUserInterfaceStyle = .light
        context.coordinator.canvasView = canvas
        // The canvas itself observes only once Tools opens (updateToolPicker): observing copies
        // the picker's own thin black pen onto it, which would replace the red default above.
        toolPicker.addObserver(context.coordinator)
        return canvas
    }

    func updateUIView(_ view: PKCanvasView, context: Context) {
        context.coordinator.onChange = onChange
        context.coordinator.onPickerChange = onPickerChange
        // Strokes come back in unit space; scale them onto the live canvas once it has a size.
        guard !context.coordinator.didRestore,
              let drawing,
              let stored = try? PKDrawing(data: drawing),
              view.bounds.width > 0
        else { return }
        context.coordinator.didRestore = true
        view.drawing = stored.transformed(using: CGAffineTransform(
            scaleX: view.bounds.width,
            y: view.bounds.height
        ))
        // Restored strokes are the starting point, not something to undo.
        view.undoManager?.removeAllActions()
    }

    func makeCoordinator() -> Coordinator {
        Coordinator(onChange: onChange, onPickerChange: onPickerChange)
    }

    final class Coordinator: NSObject, PKCanvasViewDelegate, PKToolPickerObserver {
        var onChange: (PKDrawing) -> Void
        var onPickerChange: (_ isVisible: Bool, _ inset: CGFloat) -> Void
        var didRestore = false
        weak var canvasView: PKCanvasView?

        init(
            onChange: @escaping (PKDrawing) -> Void,
            onPickerChange: @escaping (_ isVisible: Bool, _ inset: CGFloat) -> Void
        ) {
            self.onChange = onChange
            self.onPickerChange = onPickerChange
        }

        func canvasViewDrawingDidChange(_ canvasView: PKCanvasView) {
            onChange(canvasView.drawing)
        }

        func toolPickerVisibilityDidChange(_ toolPicker: PKToolPicker) {
            reportPicker(toolPicker)
        }

        func toolPickerFramesObscuredDidChange(_ toolPicker: PKToolPicker) {
            reportPicker(toolPicker)
        }

        /// On iPhone the picker docks at the bottom; this is how much of the window it covers.
        private func reportPicker(_ toolPicker: PKToolPicker) {
            guard let window = canvasView?.window else { return }
            let obscured = toolPicker.frameObscured(in: window)
            let inset = obscured.isNull || !toolPicker.isVisible
                ? 0
                : max(0, window.bounds.maxY - obscured.minY)
            onPickerChange(toolPicker.isVisible, inset)
        }
    }
}
