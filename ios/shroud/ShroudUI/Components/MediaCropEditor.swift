import SwiftUI
import UIKit

/// Telegram-style crop & rotate screen.
///
/// The photo stays put and the crop window moves over it — drag inside to reposition, drag a
/// corner or edge to resize, or pick a ratio to snap the window to shape. The rule-of-thirds
/// grid fades in only while a drag is live, so a still frame reads as the finished crop.
struct MediaCropEditor: View {
    let image: UIImage
    @Binding var edits: MediaEdits
    var onCancel: () -> Void
    var onDone: () -> Void

    /// Live crop in normalised coordinates; committed to `edits` on Done.
    @State private var crop: CGRect
    @State private var quarters: Int
    @State private var mirrored: Bool
    @State private var activeHandle: Handle?
    @State private var dragStartCrop: CGRect = .zero
    @State private var ratio: AspectPreset = .free

    private let chrome = Color(red: 44 / 255, green: 44 / 255, blue: 46 / 255)
    private let telegramBlue = Color(red: 51 / 255, green: 144 / 255, blue: 236 / 255)
    /// Smallest crop window, as a fraction of each axis.
    private let minSide: CGFloat = 0.12

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
        _crop = State(initialValue: edits.wrappedValue.cropRect)
        _quarters = State(initialValue: edits.wrappedValue.rotationQuarters)
        _mirrored = State(initialValue: edits.wrappedValue.mirrored)
    }

    private enum Handle: Equatable {
        case move
        case topLeft, topRight, bottomLeft, bottomRight
        case top, bottom, leading, trailing
    }

    enum AspectPreset: String, CaseIterable, Identifiable {
        case free, square, portrait3x4, landscape4x3, portrait9x16, landscape16x9

        var id: String { rawValue }

        var label: String {
            switch self {
            case .free: "Free"
            case .square: "Square"
            case .portrait3x4: "3:4"
            case .landscape4x3: "4:3"
            case .portrait9x16: "9:16"
            case .landscape16x9: "16:9"
            }
        }

        /// Width ÷ height, or `nil` for unconstrained.
        var value: CGFloat? {
            switch self {
            case .free: nil
            case .square: 1
            case .portrait3x4: 3.0 / 4.0
            case .landscape4x3: 4.0 / 3.0
            case .portrait9x16: 9.0 / 16.0
            case .landscape16x9: 16.0 / 9.0
            }
        }
    }

    /// The photo with rotation baked in. Cached — re-rendering it every layout pass would
    /// redraw the whole bitmap on each frame of a drag.
    @State private var orientedPreview: UIImage?

    private var preview: UIImage { orientedPreview ?? image }

    private var imageAspect: CGFloat {
        let size = preview.size
        return size.height > 0 ? size.width / size.height : 1
    }

    var body: some View {
        GeometryReader { geo in
            // Leaves room for the status bar above and the control stack below.
            let canvas = CGRect(
                x: 20,
                y: 80,
                width: max(1, geo.size.width - 40),
                height: max(1, geo.size.height - 240)
            )
            let preview = self.preview
            let frame = imageFrame(for: preview, in: canvas)

            ZStack {
                Color.black.ignoresSafeArea()

                Image(uiImage: preview)
                    .resizable()
                    .scaledToFit()
                    .frame(width: frame.width, height: frame.height)
                    .position(x: frame.midX, y: frame.midY)
                    .overlay {
                        // Everything outside the window dims, so the crop reads instantly.
                        ScrimMask(crop: crop)
                            .fill(Color.black.opacity(0.55), style: FillStyle(eoFill: true))
                            .frame(width: frame.width, height: frame.height)
                            .position(x: frame.midX, y: frame.midY)
                            .allowsHitTesting(false)
                    }
                    .animation(Motion.standard, value: quarters)
                    .animation(Motion.standard, value: mirrored)

                cropWindow(in: frame)

                VStack(spacing: 0) {
                    Spacer()
                    controls
                }
            }
            .contentShape(Rectangle())
            .gesture(dragGesture(in: frame))
        }
        .ignoresSafeArea()
        .preferredColorScheme(.dark)
        .accessibilityAddTraits(.isModal)
        .task(id: OrientationKey(quarters: quarters, mirrored: mirrored)) {
            let source = image
            let (q, m) = (quarters, mirrored)
            orientedPreview = await Task.detached(priority: .userInitiated) {
                MediaEditRenderer.oriented(source, quarters: q, mirrored: m)
            }.value
        }
    }

    private struct OrientationKey: Equatable {
        let quarters: Int
        let mirrored: Bool
    }

    // MARK: - Geometry

    private func imageFrame(for preview: UIImage, in canvas: CGRect) -> CGRect {
        let size = preview.size
        guard size.width > 0, size.height > 0 else { return canvas }
        let scale = min(canvas.width / size.width, canvas.height / size.height)
        let fitted = CGSize(width: size.width * scale, height: size.height * scale)
        return CGRect(
            x: canvas.midX - fitted.width / 2,
            y: canvas.midY - fitted.height / 2,
            width: fitted.width,
            height: fitted.height
        )
    }

    private func cropFrame(in imageFrame: CGRect) -> CGRect {
        CGRect(
            x: imageFrame.minX + crop.minX * imageFrame.width,
            y: imageFrame.minY + crop.minY * imageFrame.height,
            width: crop.width * imageFrame.width,
            height: crop.height * imageFrame.height
        )
    }

    /// Punches the crop window out of a full-bleed rectangle (even-odd fill).
    private struct ScrimMask: Shape {
        let crop: CGRect

        func path(in rect: CGRect) -> Path {
            var path = Path(rect)
            path.addRect(CGRect(
                x: rect.minX + crop.minX * rect.width,
                y: rect.minY + crop.minY * rect.height,
                width: crop.width * rect.width,
                height: crop.height * rect.height
            ))
            return path
        }
    }

    // MARK: - Crop window

    private func cropWindow(in imageFrame: CGRect) -> some View {
        let window = cropFrame(in: imageFrame)
        return ZStack {
            Rectangle()
                .stroke(Color.white, lineWidth: 1.5)
                .frame(width: window.width, height: window.height)

            // Rule of thirds, only while the user is actually adjusting.
            if activeHandle != nil {
                GridLines()
                    .stroke(Color.white.opacity(0.5), lineWidth: 0.5)
                    .frame(width: window.width, height: window.height)
                    .transition(.opacity)
            }

            ForEach(corners, id: \.self) { corner in
                CornerBracket(corner: corner)
                    .stroke(Color.white, style: StrokeStyle(lineWidth: 3, lineCap: .round))
                    .frame(width: 26, height: 26)
                    .position(cornerPoint(corner, in: CGRect(origin: .zero, size: window.size)))
            }
            .frame(width: window.width, height: window.height)
        }
        .frame(width: window.width, height: window.height)
        .position(x: window.midX, y: window.midY)
        .animation(Motion.snappy, value: ratio)
        .animation(Motion.fade, value: activeHandle != nil)
        .allowsHitTesting(false)
    }

    private var corners: [Handle] { [.topLeft, .topRight, .bottomLeft, .bottomRight] }

    private func cornerPoint(_ handle: Handle, in rect: CGRect) -> CGPoint {
        switch handle {
        case .topLeft: CGPoint(x: rect.minX, y: rect.minY)
        case .topRight: CGPoint(x: rect.maxX, y: rect.minY)
        case .bottomLeft: CGPoint(x: rect.minX, y: rect.maxY)
        case .bottomRight: CGPoint(x: rect.maxX, y: rect.maxY)
        default: CGPoint(x: rect.midX, y: rect.midY)
        }
    }

    private struct GridLines: Shape {
        func path(in rect: CGRect) -> Path {
            var path = Path()
            for i in 1 ... 2 {
                let x = rect.minX + rect.width * CGFloat(i) / 3
                path.move(to: CGPoint(x: x, y: rect.minY))
                path.addLine(to: CGPoint(x: x, y: rect.maxY))
                let y = rect.minY + rect.height * CGFloat(i) / 3
                path.move(to: CGPoint(x: rect.minX, y: y))
                path.addLine(to: CGPoint(x: rect.maxX, y: y))
            }
            return path
        }
    }

    private struct CornerBracket: Shape {
        let corner: Handle

        func path(in rect: CGRect) -> Path {
            var path = Path()
            switch corner {
            case .topLeft:
                path.move(to: CGPoint(x: rect.minX, y: rect.maxY))
                path.addLine(to: CGPoint(x: rect.minX, y: rect.minY))
                path.addLine(to: CGPoint(x: rect.maxX, y: rect.minY))
            case .topRight:
                path.move(to: CGPoint(x: rect.minX, y: rect.minY))
                path.addLine(to: CGPoint(x: rect.maxX, y: rect.minY))
                path.addLine(to: CGPoint(x: rect.maxX, y: rect.maxY))
            case .bottomLeft:
                path.move(to: CGPoint(x: rect.minX, y: rect.minY))
                path.addLine(to: CGPoint(x: rect.minX, y: rect.maxY))
                path.addLine(to: CGPoint(x: rect.maxX, y: rect.maxY))
            default:
                path.move(to: CGPoint(x: rect.maxX, y: rect.minY))
                path.addLine(to: CGPoint(x: rect.maxX, y: rect.maxY))
                path.addLine(to: CGPoint(x: rect.minX, y: rect.maxY))
            }
            return path
        }
    }

    // MARK: - Dragging

    private func dragGesture(in imageFrame: CGRect) -> some Gesture {
        DragGesture(minimumDistance: 0, coordinateSpace: .local)
            .onChanged { value in
                if activeHandle == nil {
                    let handle = self.handle(near: value.startLocation, in: imageFrame)
                    guard let handle else { return }
                    dragStartCrop = crop
                    withAnimation(Motion.fade) { activeHandle = handle }
                    Haptics.impact(.light)
                }
                guard let activeHandle else { return }
                apply(
                    translation: value.translation,
                    handle: activeHandle,
                    imageFrame: imageFrame
                )
            }
            .onEnded { _ in
                withAnimation(Motion.fade) { activeHandle = nil }
            }
    }

    /// Corner and edge grabs win over "move" inside a generous 34 pt radius.
    private func handle(near point: CGPoint, in imageFrame: CGRect) -> Handle? {
        let window = cropFrame(in: imageFrame)
        let slop: CGFloat = 34

        let candidates: [(Handle, CGPoint)] = [
            (.topLeft, CGPoint(x: window.minX, y: window.minY)),
            (.topRight, CGPoint(x: window.maxX, y: window.minY)),
            (.bottomLeft, CGPoint(x: window.minX, y: window.maxY)),
            (.bottomRight, CGPoint(x: window.maxX, y: window.maxY)),
            (.top, CGPoint(x: window.midX, y: window.minY)),
            (.bottom, CGPoint(x: window.midX, y: window.maxY)),
            (.leading, CGPoint(x: window.minX, y: window.midY)),
            (.trailing, CGPoint(x: window.maxX, y: window.midY)),
        ]

        let nearest = candidates
            .map { ($0.0, hypot($0.1.x - point.x, $0.1.y - point.y)) }
            .min { $0.1 < $1.1 }

        if let nearest, nearest.1 <= slop { return nearest.0 }
        return window.insetBy(dx: -12, dy: -12).contains(point) ? .move : nil
    }

    private func apply(translation: CGSize, handle: Handle, imageFrame: CGRect) {
        guard imageFrame.width > 0, imageFrame.height > 0 else { return }
        let dx = translation.width / imageFrame.width
        let dy = translation.height / imageFrame.height
        var next = dragStartCrop

        switch handle {
        case .move:
            next.origin.x = min(max(0, dragStartCrop.minX + dx), 1 - dragStartCrop.width)
            next.origin.y = min(max(0, dragStartCrop.minY + dy), 1 - dragStartCrop.height)
            crop = next
            return
        case .topLeft:
            next = rect(minX: dragStartCrop.minX + dx, minY: dragStartCrop.minY + dy,
                        maxX: dragStartCrop.maxX, maxY: dragStartCrop.maxY)
        case .topRight:
            next = rect(minX: dragStartCrop.minX, minY: dragStartCrop.minY + dy,
                        maxX: dragStartCrop.maxX + dx, maxY: dragStartCrop.maxY)
        case .bottomLeft:
            next = rect(minX: dragStartCrop.minX + dx, minY: dragStartCrop.minY,
                        maxX: dragStartCrop.maxX, maxY: dragStartCrop.maxY + dy)
        case .bottomRight:
            next = rect(minX: dragStartCrop.minX, minY: dragStartCrop.minY,
                        maxX: dragStartCrop.maxX + dx, maxY: dragStartCrop.maxY + dy)
        case .top:
            next = rect(minX: dragStartCrop.minX, minY: dragStartCrop.minY + dy,
                        maxX: dragStartCrop.maxX, maxY: dragStartCrop.maxY)
        case .bottom:
            next = rect(minX: dragStartCrop.minX, minY: dragStartCrop.minY,
                        maxX: dragStartCrop.maxX, maxY: dragStartCrop.maxY + dy)
        case .leading:
            next = rect(minX: dragStartCrop.minX + dx, minY: dragStartCrop.minY,
                        maxX: dragStartCrop.maxX, maxY: dragStartCrop.maxY)
        case .trailing:
            next = rect(minX: dragStartCrop.minX, minY: dragStartCrop.minY,
                        maxX: dragStartCrop.maxX + dx, maxY: dragStartCrop.maxY)
        }

        if let target = ratio.value {
            next = constrained(next, to: target, anchor: handle)
        }
        crop = next
    }

    private func rect(minX: CGFloat, minY: CGFloat, maxX: CGFloat, maxY: CGFloat) -> CGRect {
        let x0 = min(max(0, minX), maxX - minSide)
        let y0 = min(max(0, minY), maxY - minSide)
        let x1 = max(min(1, maxX), x0 + minSide)
        let y1 = max(min(1, maxY), y0 + minSide)
        return CGRect(x: x0, y: y0, width: x1 - x0, height: y1 - y0)
    }

    /// Re-shapes a dragged rect to the locked ratio, pinning the corner the user isn't holding.
    private func constrained(_ input: CGRect, to target: CGFloat, anchor: Handle) -> CGRect {
        // The crop rect is normalised to the photo, so a 1:1 window is only square once the
        // photo's own aspect is divided back out.
        let normalised = target / imageAspect

        var width = input.width
        var height = width / normalised
        if height > 1 {
            height = 1
            width = height * normalised
        }
        width = min(width, 1)
        height = min(height, 1)

        var x = input.minX
        var y = input.minY
        switch anchor {
        case .topLeft, .leading, .top:
            x = input.maxX - width
            y = input.maxY - height
        case .topRight:
            y = input.maxY - height
        case .bottomLeft:
            x = input.maxX - width
        default:
            break
        }

        x = min(max(0, x), 1 - width)
        y = min(max(0, y), 1 - height)
        return CGRect(x: x, y: y, width: width, height: height)
    }

    private func applyPreset(_ preset: AspectPreset) {
        ratio = preset
        guard let target = preset.value else { return }
        // Centre the largest window of that shape that still fits the photo.
        let normalised = target / max(0.0001, imageAspect)
        var width: CGFloat = 1
        var height = width / normalised
        if height > 1 {
            height = 1
            width = height * normalised
        }
        withAnimation(Motion.standard) {
            crop = CGRect(x: (1 - width) / 2, y: (1 - height) / 2, width: width, height: height)
        }
    }

    // MARK: - Controls

    private var controls: some View {
        VStack(spacing: 16) {
            ScrollView(.horizontal, showsIndicators: false) {
                HStack(spacing: 8) {
                    ForEach(AspectPreset.allCases) { preset in
                        Button {
                            Haptics.impact(.light)
                            applyPreset(preset)
                        } label: {
                            Text(preset.label)
                                .font(.system(size: 13, weight: .semibold))
                                .foregroundStyle(ratio == preset ? Color.black : Color.white)
                                .padding(.horizontal, 14)
                                .padding(.vertical, 8)
                                .background(ratio == preset ? Color.white : chrome)
                                .clipShape(Capsule())
                        }
                        .pressable(scale: 0.9, dimming: 0)
                    }
                }
                .padding(.horizontal, 20)
            }

            HStack(spacing: 20) {
                toolButton(systemName: "rotate.left", label: "Rotate") {
                    withAnimation(Motion.standard) {
                        quarters = ((quarters - 1) % 4 + 4) % 4
                        crop = CGRect(x: 0, y: 0, width: 1, height: 1)
                        ratio = .free
                    }
                }
                toolButton(systemName: "arrow.left.and.right.righttriangle.left.righttriangle.right", label: "Flip") {
                    withAnimation(Motion.standard) { mirrored.toggle() }
                }
                toolButton(systemName: "arrow.counterclockwise", label: "Reset") {
                    withAnimation(Motion.standard) {
                        crop = CGRect(x: 0, y: 0, width: 1, height: 1)
                        quarters = 0
                        mirrored = false
                        ratio = .free
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
                    edits.cropRect = crop
                    edits.rotationQuarters = quarters
                    edits.mirrored = mirrored
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
        .background(
            LinearGradient(
                colors: [Color.black.opacity(0), Color.black.opacity(0.9), Color.black],
                startPoint: .top,
                endPoint: .bottom
            )
        )
    }

    private func toolButton(systemName: String, label: String, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            VStack(spacing: 4) {
                Image(systemName: systemName)
                    .font(.system(size: 18, weight: .medium))
                    .foregroundStyle(Color.white)
                    .frame(width: 44, height: 44)
                    .background(chrome)
                    .clipShape(Circle())
                Text(label)
                    .font(.system(size: 11, weight: .medium))
                    .foregroundStyle(Color.white.opacity(0.7))
            }
        }
        .pressable(scale: 0.88, dimming: 0)
        .accessibilityLabel(label)
    }
}
