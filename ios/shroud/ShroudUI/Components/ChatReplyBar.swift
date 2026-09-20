import SwiftUI

/// The "Reply to …" strip above the composer while a reply is being written.
///
/// Human: Same shape as the quote that will end up inside the sent bubble, so the thing you are
/// answering looks identical before and after you send it. Tapping the strip jumps to the quoted
/// message (Telegram does the same); the ✕ drops the reply and leaves the draft alone.
/// Agent: Pure presentation — the host owns the reply target and clears it in `onCancel`.
struct ChatReplyBar: View {
    let content: ReplyQuoteContent
    var onTapPreview: (() -> Void)?
    var onCancel: () -> Void

    var body: some View {
        HStack(spacing: 8) {
            Image(systemName: "arrowshape.turn.up.left")
                .font(.system(size: 18, weight: .medium))
                .foregroundStyle(Theme.accent)
                .frame(width: 26, height: 34)
                .accessibilityHidden(true)

            ReplyQuoteView(
                content: content,
                style: .composer,
                onTap: onTapPreview,
                fontSize: 15
            )

            Button(action: onCancel) {
                Image(systemName: "xmark")
                    .font(.system(size: 15, weight: .semibold))
                    .foregroundStyle(Theme.textSecondary)
                    .frame(width: 34, height: 34)
                    .contentShape(Rectangle())
            }
            .pressable(scale: 0.86)
            .accessibilityLabel("Cancel reply")
        }
        .padding(.leading, 10)
        .padding(.trailing, 6)
        .padding(.top, 6)
        .padding(.bottom, 2)
        .frame(maxWidth: .infinity, alignment: .leading)
    }
}

#Preview("Reply bar") {
    VStack(spacing: 0) {
        Spacer()
        ChatReplyBar(
            content: ReplyQuoteContent(
                author: "Jane Cooper",
                text: "Hey! Are we still on for tomorrow?",
                isStandIn: false,
                thumbnail: nil,
                symbolName: nil
            ),
            onCancel: {}
        )
        ChatReplyBar(
            content: ReplyQuoteContent(
                author: "You",
                text: "Photo",
                isStandIn: true,
                thumbnail: nil,
                symbolName: "photo"
            ),
            onCancel: {}
        )
    }
    .background(Theme.background)
}
