import CoreImage
import SwiftUI
import UIKit

/// Live filter picker for compose — a thumbnail per preset plus an intensity slider.
///
/// Thumbnails are rendered once from a small copy of the photo, so scrolling the strip never
/// touches the full-resolution bitmap.
struct MediaFilterStrip: View {
    let image: UIImage
    @Binding var filter: MediaFilter
    @Binding var intensity: Double

    @State private var thumbnails: [MediaFilter: UIImage] = [:]

    private let chrome = Color(red: 44 / 255, green: 44 / 255, blue: 46 / 255)
    private let telegramBlue = Color(red: 51 / 255, green: 144 / 255, blue: 236 / 255)

    var body: some View {
        VStack(spacing: 12) {
            if filter != .none {
                HStack(spacing: 12) {
                    Text("Intensity")
                        .font(.system(size: 12, weight: .semibold))
                        .foregroundStyle(Color.white.opacity(0.7))
                    Slider(value: $intensity, in: 0 ... 1)
                        .tint(telegramBlue)
                    Text("\(Int(intensity * 100))%")
                        .font(.system(size: 12, weight: .semibold))
                        .foregroundStyle(Color.white.opacity(0.7))
                        .monospacedDigit()
                        .frame(width: 40, alignment: .trailing)
                }
                .padding(.horizontal, 20)
                .transition(.opacity.combined(with: .move(edge: .bottom)))
            }

            ScrollView(.horizontal, showsIndicators: false) {
                HStack(spacing: 10) {
                    ForEach(MediaFilter.allCases) { preset in
                        Button {
                            Haptics.impact(.light)
                            withAnimation(Motion.snappy) {
                                filter = preset
                                if preset != .none { intensity = 1 }
                            }
                        } label: {
                            VStack(spacing: 6) {
                                ZStack {
                                    if let thumb = thumbnails[preset] {
                                        Image(uiImage: thumb)
                                            .resizable()
                                            .scaledToFill()
                                    } else {
                                        chrome
                                    }
                                }
                                .frame(width: 58, height: 58)
                                .clipShape(RoundedRectangle(cornerRadius: 10, style: .continuous))
                                .overlay {
                                    RoundedRectangle(cornerRadius: 10, style: .continuous)
                                        .stroke(
                                            filter == preset ? telegramBlue : Color.clear,
                                            lineWidth: 2.5
                                        )
                                }
                                .scaleEffect(filter == preset ? 1.06 : 1)

                                Text(preset.label)
                                    .font(.system(size: 10, weight: .semibold))
                                    .foregroundStyle(
                                        filter == preset ? telegramBlue : Color.white.opacity(0.7)
                                    )
                            }
                        }
                        .pressable(scale: 0.9, dimming: 0)
                        .accessibilityLabel(preset.label)
                    }
                }
                .padding(.horizontal, 20)
            }
        }
        .animation(Motion.standard, value: filter)
        .task(id: image) {
            thumbnails = await Self.makeThumbnails(from: image)
        }
    }

    private static func makeThumbnails(from image: UIImage) async -> [MediaFilter: UIImage] {
        await Task.detached(priority: .userInitiated) {
            guard let small = MediaCrypto.previewImage(
                from: image.jpegData(compressionQuality: 0.9) ?? Data(),
                maxEdge: 160
            ), let cgImage = small.cgImage else { return [:] }

            let context = CIContext(options: [.useSoftwareRenderer: false])
            let input = CIImage(cgImage: cgImage)
            var out: [MediaFilter: UIImage] = [:]
            for preset in MediaFilter.allCases {
                let output = preset.apply(to: input, intensity: 1)
                if let rendered = context.createCGImage(output, from: input.extent) {
                    out[preset] = UIImage(cgImage: rendered)
                }
            }
            return out
        }.value
    }
}
