import SwiftUI
import UIKit

/// Telegram-style image bubble with bottom-trailing time / receipts overlay.
struct ImageMessageBubble: View {
    let message: MessagingController.ChatMessage
    let time: String
    var onAppearLoad: (() -> Void)?
    var onRetry: (() -> Void)?
    /// Tap the photo (when loaded) — host presents the media overlay.
    var onOpen: (() -> Void)?
    /// When false, renders only the bubble (no leading/trailing row spacers) for menu hero.
    var isRowEmbedded: Bool = true
    /// When set, reports this bubble’s global frame via `MessageBubbleFrameKey`.
    var frameReportID: UUID? = nil

    private var isMine: Bool { message.isMine }
    private var isFailed: Bool { message.receipt == .failed }
    private var canOpen: Bool {
        !message.deleted && !isFailed && message.imageData != nil
    }

    /// Caption when `text` is real user text (not the default "Photo" label).
    private var hasCaption: Bool {
        let t = message.text.trimmingCharacters(in: .whitespacesAndNewlines)
        return !t.isEmpty && t != "Photo"
    }

    private var caption: String {
        message.text.trimmingCharacters(in: .whitespacesAndNewlines)
    }

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
        Group {
            if isRowEmbedded {
                HStack(alignment: .bottom, spacing: 0) {
                    if isMine { Spacer(minLength: 56) }
                    bubbleCore
                    if !isMine { Spacer(minLength: 56) }
                }
                .frame(maxWidth: .infinity, alignment: isMine ? .trailing : .leading)
            } else {
                bubbleCore
            }
        }
    }

    private var bubbleCore: some View {
        VStack(alignment: isMine ? .trailing : .leading, spacing: 6) {
            VStack(alignment: .leading, spacing: 0) {
                ZStack(alignment: .bottomTrailing) {
                    Group {
                        if message.deleted {
                            deletedPlaceholder
                        } else if let data = message.imageData,
                                  let ui = DecodedImageCache.image(forMessage: message.id, data: data)
                        {
                            Image(uiImage: ui)
                                .resizable()
                                .scaledToFill()
                                .frame(width: displaySize.width, height: displaySize.height)
                                .clipped()
                                .opacity(isFailed ? 0.55 : 1)
                        } else {
                            loadingPlaceholder
                        }
                    }

                    if isFailed {
                        failedOverlay
                    } else if !hasCaption {
                        timeChip
                    }
                }
                .frame(width: displaySize.width, height: displaySize.height)
                .clipShape(
                    hasCaption
                        ? UnevenRoundedRectangle(
                            topLeadingRadius: 17.5,
                            bottomLeadingRadius: 0,
                            bottomTrailingRadius: 0,
                            topTrailingRadius: 17.5,
                            style: .continuous
                        )
                        : corners
                )
                .contentShape(Rectangle())
                // Tap / long-press are handled on the row via UIKit
                // (`messageContextLongPress`) so ScrollView doesn’t delay the menu ~1s.
                // Keep a SwiftUI tap as fallback when the bubble is used outside chat rows.
                .onTapGesture {
                    guard canOpen else { return }
                    Haptics.impact(.light)
                    onOpen?()
                }

                if hasCaption, !message.deleted {
                    captionFooter
                }
            }
            .overlay {
                if isFailed {
                    RoundedRectangle(cornerRadius: 17.5, style: .continuous)
                        .stroke(Theme.danger.opacity(0.7), lineWidth: 1.5)
                }
            }
            .shadow(color: Color.black.opacity(0.08), radius: 3, y: 1)
            .onAppear { onAppearLoad?() }

            if isFailed {
                failedFooter
            }
        }
        .background {
            if let frameReportID {
                GeometryReader { geo in
                    Color.clear.preference(
                        key: MessageBubbleFrameKey.self,
                        value: [frameReportID: geo.frame(in: .global)]
                    )
                }
            }
        }
    }

    /// Match text bubbles: muted meta for time/sent/delivered; brighter ticks when read.
    private var metaColor: Color { Color.white.opacity(0.75) }
    private var readTickColor: Color { Color.white.opacity(0.95) }

    private var timeChip: some View {
        HStack(spacing: 3) {
            Text(time)
                .font(.system(size: 11, weight: .regular))
                .foregroundStyle(metaColor)
                .monospacedDigit()
                .fixedSize()
            if isMine, !message.deleted {
                MessageReceiptIcon(
                    receipt: message.receipt,
                    metaColor: metaColor,
                    readColor: readTickColor,
                    failedColor: Color.white
                )
            }
        }
        .padding(.horizontal, 7)
        .padding(.vertical, 3)
        .background(Color.black.opacity(0.35))
        .clipShape(Capsule())
        .padding(8)
    }

    private var failedOverlay: some View {
        VStack(spacing: 8) {
            Image(systemName: "exclamationmark.triangle.fill")
                .font(.system(size: 28))
                .foregroundStyle(Color.white)
            Text("Not sent")
                .font(.system(size: 13, weight: .semibold))
                .foregroundStyle(Color.white)
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .background(Color.black.opacity(0.35))
    }

    private var failedFooter: some View {
        VStack(alignment: isMine ? .trailing : .leading, spacing: 4) {
            if let error = message.sendError, !error.isEmpty {
                Text(error)
                    .font(.system(size: 12))
                    .foregroundStyle(Theme.danger)
                    .multilineTextAlignment(isMine ? .trailing : .leading)
                    .frame(maxWidth: displaySize.width, alignment: isMine ? .trailing : .leading)
            }
            Button {
                onRetry?()
            } label: {
                Label("Retry", systemImage: "arrow.clockwise")
                    .font(.system(size: 13, weight: .semibold))
                    .foregroundStyle(Theme.accent)
            }
            .buttonStyle(.plain)
        }
        .padding(.horizontal, 2)
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

    /// Caption strip under the photo (Telegram: text lives under media in the bubble).
    private var captionFooter: some View {
        HStack(alignment: .bottom, spacing: 8) {
            Text(caption)
                .font(.system(size: 16))
                .foregroundStyle(isMine ? Color.white : Theme.textPrimary)
                .multilineTextAlignment(.leading)
                .fixedSize(horizontal: false, vertical: true)

            Spacer(minLength: 4)

            HStack(spacing: 3) {
                Text(time)
                    .font(.system(size: 11, weight: .regular))
                    .foregroundStyle(isMine ? Color.white.opacity(0.65) : Theme.textSecondary)
                    .monospacedDigit()
                if isMine {
                    MessageReceiptIcon(
                        receipt: message.receipt,
                        metaColor: Color.white.opacity(0.65),
                        readColor: Color.white.opacity(0.95)
                    )
                }
            }
            .fixedSize()
        }
        .padding(.horizontal, 11)
        .padding(.top, 7)
        .padding(.bottom, 6)
        .frame(width: displaySize.width, alignment: .leading)
        .background(isMine ? Theme.accent : Theme.bubbleIncoming)
        .clipShape(
            UnevenRoundedRectangle(
                topLeadingRadius: 0,
                bottomLeadingRadius: isMine ? 17.5 : 5,
                bottomTrailingRadius: isMine ? 5 : 17.5,
                topTrailingRadius: 0,
                style: .continuous
            )
        )
    }

}
