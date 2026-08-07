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

    @State private var canvas = PKCanvasView()
    /// Owned per-editor; `PKToolPicker.shared(for:)` is deprecated.
    @State private var toolPicker = PKToolPicker()
    @State private var toolPickerVisible = false
    @State private var canUndo = false

    private let chrome = Color(red: 44 / 255, green: 44 / 255, blue: 46 / 255)
    private let telegramBlue = Color(red: 51 / 255, green: 144 / 255, blue: 236 / 255)

    var body: some View {
        GeometryReader { geo in
            let canvasArea = CGSize(width: geo.size.width, height: geo.size.height - 210)
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
                        drawing: edits.drawing,
                        onChange: { canUndo = !$0.strokes.isEmpty }
                    )
                    .frame(width: frame.width, height: frame.height)
                }
                .position(x: geo.size.width / 2, y: canvasArea.height / 2 + 40)

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
                    canUndo = !canvas.drawing.strokes.isEmpty
                }
                toolButton(systemName: "pencil.tip", label: "Tools", enabled: true) {
                    toolPickerVisible.toggle()
                    updateToolPicker()
                }
                toolButton(systemName: "trash", label: "Clear", enabled: canUndo) {
                    withAnimation(Motion.snappy) {
                        canvas.drawing = PKDrawing()
                        canUndo = false
                    }
                }
            }
            .animation(Motion.snappy, value: canUndo)

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
                    commit()
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
        enabled: Bool,
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
    }

    private func updateToolPicker() {
        toolPicker.setVisible(toolPickerVisible, forFirstResponder: canvas)
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

/// Hosts a `PKCanvasView` sized to the photo, restoring any strokes already made.
private struct DrawingCanvas: UIViewRepresentable {
    let canvas: PKCanvasView
    let drawing: Data?
    var onChange: (PKDrawing) -> Void

    func makeUIView(context: Context) -> PKCanvasView {
        canvas.backgroundColor = .clear
        canvas.isOpaque = false
        canvas.drawingPolicy = .anyInput
        canvas.delegate = context.coordinator
        canvas.tool = PKInkingTool(.pen, color: .systemRed, width: 12)
        return canvas
    }

    func updateUIView(_ view: PKCanvasView, context: Context) {
        context.coordinator.onChange = onChange
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
    }

    func makeCoordinator() -> Coordinator {
        Coordinator(onChange: onChange)
    }

    final class Coordinator: NSObject, PKCanvasViewDelegate {
        var onChange: (PKDrawing) -> Void
        var didRestore = false

        init(onChange: @escaping (PKDrawing) -> Void) {
            self.onChange = onChange
        }

        func canvasViewDrawingDidChange(_ canvasView: PKCanvasView) {
            onChange(canvasView.drawing)
        }
    }
}
