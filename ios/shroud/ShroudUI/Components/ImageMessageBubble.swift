import SwiftUI
import UIKit

/// Telegram-style image bubble with bottom-trailing time / receipts overlay.
struct ImageMessageBubble: View {
    let message: MessagingController.ChatMessage
    let time: String
    var onAppearLoad: (() -> Void)?

    private var isMine: Bool { message.isMine }

    private var corners: UnevenRoundedRectangle {
        if isMine {
            UnevenRoundedRectangle(
                topLeadingRadius: 17.5,
                bottomLeadingRadius: 17.5,
                bottomTrailingRadius: 5,
                topTrailingRadius: 17.5,
                style: .continuous
            )
        } else {
            UnevenRoundedRectangle(
                topLeadingRadius: 17.5,
                bottomLeadingRadius: 5,
                bottomTrailingRadius: 17.5,
                topTrailingRadius: 17.5,
                style: .continuous
            )
        }
    }

    private var displaySize: CGSize {
        let maxW: CGFloat = 240
        let maxH: CGFloat = 320
        let w = CGFloat(message.imageWidth ?? 240)
        let h = CGFloat(message.imageHeight ?? 240)
        guard w > 0, h > 0 else { return CGSize(width: 180, height: 180) }
        let scale = min(maxW / w, maxH / h, 1)
        return CGSize(width: max(120, w * scale), height: max(120, h * scale))
    }

    var body: some View {
        HStack(alignment: .bottom, spacing: 0) {
            if isMine { Spacer(minLength: 56) }

            ZStack(alignment: .bottomTrailing) {
                Group {
                    if message.deleted {
                        deletedPlaceholder
                    } else if let data = message.imageData, let ui = UIImage(data: data) {
                        Image(uiImage: ui)
                            .resizable()
                            .scaledToFill()
                            .frame(width: displaySize.width, height: displaySize.height)
                            .clipped()
                    } else {
                        loadingPlaceholder
                    }
                }

                // Time chip over the image (Telegram style).
                HStack(spacing: 3) {
                    Text(time)
                        .font(.system(size: 11, weight: .medium))
                        .monospacedDigit()
                    if isMine, !message.deleted {
                        receiptIcon
                    }
                }
                .foregroundStyle(Color.white.opacity(0.95))
                .padding(.horizontal, 7)
                .padding(.vertical, 3)
                .background(Color.black.opacity(0.35))
                .clipShape(Capsule())
                .padding(8)
            }
            .frame(width: displaySize.width, height: displaySize.height)
            .clipShape(corners)
            .shadow(color: Color.black.opacity(0.08), radius: 3, y: 1)
            .onAppear { onAppearLoad?() }

            if !isMine { Spacer(minLength: 56) }
        }
        .frame(maxWidth: .infinity, alignment: isMine ? .trailing : .leading)
    }

    private var loadingPlaceholder: some View {
        ZStack {
            (isMine ? Theme.accent : Theme.bubbleIncoming)
            ProgressView()
                .tint(isMine ? Color.white.opacity(0.9) : Theme.accent)
        }
        .frame(width: displaySize.width, height: displaySize.height)
    }

    private var deletedPlaceholder: some View {
        ZStack {
            Theme.backgroundGrouped
            Text("Photo deleted")
                .font(.system(size: 14))
                .foregroundStyle(Theme.textSecondary)
        }
        .frame(width: displaySize.width, height: 120)
    }

    @ViewBuilder
    private var receiptIcon: some View {
        switch message.receipt {
        case .sending:
            ProgressView()
                .controlSize(.mini)
                .tint(.white)
                .scaleEffect(0.65)
                .frame(width: 12, height: 11)
        case .sent:
            Image(systemName: "checkmark")
                .font(.system(size: 9, weight: .semibold))
        case .delivered, .read:
            HStack(spacing: -3) {
                Image(systemName: "checkmark")
                    .font(.system(size: 9, weight: .semibold))
                Image(systemName: "checkmark")
                    .font(.system(size: 9, weight: .semibold))
            }
            .opacity(message.receipt == .read ? 1 : 0.75)
        }
    }
}
