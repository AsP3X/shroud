import SwiftUI

/// Telegram-style center control: download arrow + optional size, or spinner while fetching.
struct MediaDownloadChip: View {
    var byteCount: Int?
    var isDownloading: Bool
    var action: () -> Void

    var body: some View {
        Button(action: action) {
            ZStack {
                Circle()
                    .fill(Color.black.opacity(0.45))
                    .frame(width: 52, height: 52)
                if isDownloading {
                    ProgressView()
                        .tint(.white)
                } else {
                    VStack(spacing: 2) {
                        Image(systemName: "arrow.down")
                            .font(.system(size: 18, weight: .bold))
                            .foregroundStyle(Color.white)
                        if let byteCount, byteCount > 0 {
                            Text(MediaCrypto.byteCountLabel(byteCount))
                                .font(.system(size: 10, weight: .semibold).monospacedDigit())
                                .foregroundStyle(Color.white.opacity(0.95))
                                .lineLimit(1)
                                .minimumScaleFactor(0.7)
                        }
                    }
                    .padding(.horizontal, 4)
                }
            }
        }
        .buttonStyle(.plain)
        .pressable(scale: 0.92, haptic: .light)
        .accessibilityLabel(isDownloading ? "Downloading media" : "Download media")
    }
}
