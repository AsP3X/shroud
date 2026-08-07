import SwiftUI
import UIKit

/// Telegram's trim bar: a filmstrip with two draggable handles and a live playhead.
///
/// Human: Everything outside the handles dims, the kept region gets a bright frame, and the
/// handles are wide enough to grab without hiding the frames they bound.
/// Agent: Writes only through the `trim` binding. `onSeek` lets the host preview the frame a
/// handle is sitting on; `onScrubEnd` is when it should resume playing.
struct VideoTrimStrip: View {
    let frames: [UIImage]
    let duration: Double
    @Binding var trim: VideoTrim
    /// Playhead position in seconds; nil hides the line.
    var playhead: Double?
    /// Called continuously while a handle moves, with the time it now sits on.
    var onSeek: ((Double) -> Void)?
    var onScrubEnd: (() -> Void)?

    @State private var activeHandle: Handle?
    /// Width of the seekable track (the strip minus one handle at each end).
    @State private var trackWidth: CGFloat = 0

    private enum Handle { case start, end }

    /// Shortest clip the handles allow — a video message needs at least a moment.
    private static let minimumDuration: Double = 1.0
    private static let handleWidth: CGFloat = 16
    private static let stripHeight: CGFloat = 48
    private static let coordinateSpace = "video-trim-strip"

    private var startX: CGFloat { Self.handleWidth + x(for: trim.start) }
    private var endX: CGFloat { Self.handleWidth + x(for: trim.end) }

    var body: some View {
        ZStack(alignment: .topLeading) {
            filmstrip
                .frame(height: Self.stripHeight)
                .clipShape(RoundedRectangle(cornerRadius: 8, style: .continuous))

            // Everything that will be cut away reads as "off".
            dim(from: 0, to: startX)
            dim(from: endX, to: trackWidth + Self.handleWidth * 2)

            RoundedRectangle(cornerRadius: 8, style: .continuous)
                .stroke(Color.white, lineWidth: 2.5)
                .frame(width: max(0, endX - startX), height: Self.stripHeight)
                .offset(x: startX)
                .allowsHitTesting(false)

            if let playhead, playhead >= trim.start, playhead <= trim.end, activeHandle == nil {
                Capsule()
                    .fill(Color.white)
                    .frame(width: 2.5, height: Self.stripHeight - 8)
                    .shadow(color: .black.opacity(0.45), radius: 2)
                    .offset(x: Self.handleWidth + x(for: playhead) - 1.25, y: 4)
                    .allowsHitTesting(false)
            }

            handle(.start, at: startX)
            handle(.end, at: endX)
        }
        .frame(height: Self.stripHeight)
        .coordinateSpace(.named(Self.coordinateSpace))
        .onGeometryChange(for: CGFloat.self) { proxy in
            max(1, proxy.size.width - Self.handleWidth * 2)
        } action: { trackWidth = $0 }
        .animation(Motion.snappy, value: frames.count)
    }

    // MARK: - Pieces

    private var filmstrip: some View {
        GeometryReader { geo in
            HStack(spacing: 0) {
                if frames.isEmpty {
                    Rectangle()
                        .fill(Color.white.opacity(0.09))
                        .shimmering()
                } else {
                    let tileWidth = geo.size.width / CGFloat(frames.count)
                    ForEach(Array(frames.enumerated()), id: \.offset) { _, frame in
                        Image(uiImage: frame)
                            .resizable()
                            .scaledToFill()
                            .frame(width: tileWidth, height: Self.stripHeight)
                            .clipped()
                    }
                }
            }
        }
    }

    private func dim(from: CGFloat, to: CGFloat) -> some View {
        Rectangle()
            .fill(Color.black.opacity(0.55))
            .frame(width: max(0, to - from), height: Self.stripHeight)
            .offset(x: from)
            .allowsHitTesting(false)
    }

    private func handle(_ which: Handle, at position: CGFloat) -> some View {
        let isActive = activeHandle == which
        return RoundedRectangle(cornerRadius: 5, style: .continuous)
            .fill(Color.white)
            .frame(width: Self.handleWidth, height: Self.stripHeight)
            .overlay {
                Capsule()
                    .fill(Color.black.opacity(0.35))
                    .frame(width: 2, height: 16)
            }
            .scaleEffect(x: isActive ? 1.2 : 1, anchor: .center)
            .offset(x: position - Self.handleWidth / 2)
            // Generous slop so a 16pt bar is still a comfortable target.
            .contentShape(Rectangle().inset(by: -12))
            .gesture(dragGesture(for: which))
            .animation(Motion.snappy, value: isActive)
            .accessibilityLabel(which == .start ? "Trim start" : "Trim end")
            .accessibilityValue(ChatVideoPlayer.timeLabel(which == .start ? trim.start : trim.end))
    }

    // MARK: - Dragging

    private func dragGesture(for which: Handle) -> some Gesture {
        DragGesture(minimumDistance: 0, coordinateSpace: .named(Self.coordinateSpace))
            .onChanged { value in
                if activeHandle == nil {
                    activeHandle = which
                    Haptics.impact(.light)
                }
                move(which, toX: value.location.x)
            }
            .onEnded { _ in
                activeHandle = nil
                onScrubEnd?()
            }
    }

    private func move(_ which: Handle, toX position: CGFloat) {
        guard duration > 0, trackWidth > 0 else { return }
        let raw = Double((position - Self.handleWidth) / trackWidth) * duration
        let minimum = min(Self.minimumDuration, duration)
        switch which {
        case .start:
            trim.start = max(0, min(raw, trim.end - minimum))
            onSeek?(trim.start)
        case .end:
            trim.end = min(duration, max(raw, trim.start + minimum))
            onSeek?(trim.end)
        }
    }

    private func x(for seconds: Double) -> CGFloat {
        guard duration > 0 else { return 0 }
        return CGFloat(min(1, max(0, seconds / duration))) * trackWidth
    }
}
