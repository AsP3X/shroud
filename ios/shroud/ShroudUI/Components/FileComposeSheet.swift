import SwiftUI
import UIKit

/// The file composer: what is about to go out, a caption, and Send (`docs/file-sharing.md` §7).
///
/// Human: Files go out exactly as they are, so the sheet says so — no compression, metadata
/// kept. Each row has the same tile, name and meta line as the bubble it will become, and the
/// same warning, so an APK or a macro file is flagged before it is sent too. An audio file shows
/// its round cover, title and `{artist} · {duration} · {size} · {EXT}` (§11.3).
/// Agent: Pure presentation over `files`; the host owns the copies in `tmp/` and removes them
/// (`onRemove`, `onCancel`, the sheet's dismissal).
struct FileComposeSheet: View {
    let files: [PickedFile]
    var onRemove: (PickedFile) -> Void
    var onCancel: () -> Void
    /// The caption (trimmed by the sender).
    var onSend: (String) -> Void

    @State private var caption = ""
    @FocusState private var captionFocused: Bool

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 0) {
                    ForEach(files) { file in
                        row(file)
                        if file.id != files.last?.id {
                            Divider()
                                .padding(.leading, 16 + FileMessageBubble.tileSide + 12)
                        }
                    }
                    Text(SharedFile.composerNote)
                        .font(.system(size: 13))
                        .foregroundStyle(Theme.textSecondary)
                        .fixedSize(horizontal: false, vertical: true)
                        .padding(.horizontal, 16)
                        .padding(.top, 14)
                }
                .padding(.vertical, 8)
            }
            .scrollDismissesKeyboard(.interactively)
            .background(Theme.background)
            .safeAreaInset(edge: .bottom) { captionBar }
            .navigationTitle(SharedFile.composerTitle(count: files.count))
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Cancel", action: onCancel)
                }
            }
        }
        .presentationDetents([.medium, .large])
        // iPad: a form sheet, not a stretched phone sheet.
        .presentationSizing(.form)
        .presentationBackground(Theme.background)
    }

    private func isAudio(_ file: PickedFile) -> Bool {
        file.type.category == .audio
    }

    /// The tile, or for an audio file the round cover of §11.3 (its art, else the accent circle
    /// with a music glyph).
    @ViewBuilder
    private func tile(_ file: PickedFile) -> some View {
        if isAudio(file) {
            ZStack {
                Circle().fill(Theme.accent)
                if let jpeg = file.audio?.cover?.jpeg, let cover = UIImage(data: jpeg) {
                    Image(uiImage: cover)
                        .resizable()
                        .scaledToFill()
                } else {
                    Image(systemName: FileMessageBubble.symbol(for: .audio))
                        .font(.system(size: 19, weight: .semibold))
                        .foregroundStyle(Color.white)
                }
            }
            .frame(width: FileMessageBubble.tileSide, height: FileMessageBubble.tileSide)
            .clipShape(Circle())
        } else {
            ZStack {
                RoundedRectangle(cornerRadius: FileMessageBubble.tileRadius, style: .continuous)
                    .fill(Theme.accent)
                Image(systemName: FileMessageBubble.symbol(for: file.type.category))
                    .font(.system(size: 19, weight: .semibold))
                    .foregroundStyle(Color.white)
            }
            .frame(width: FileMessageBubble.tileSide, height: FileMessageBubble.tileSide)
        }
    }

    /// `ti`, else the name.
    private func title(_ file: PickedFile) -> String {
        file.audio?.title ?? file.name
    }

    /// `{size} · {TYPE}`; an audio file's `{ar} · {duration} · {size} · {EXT}` without the
    /// missing parts (§11.3).
    private func meta(_ file: PickedFile) -> String {
        guard isAudio(file) else { return SharedFile.metaLine(byteCount: file.byteCount, type: file.type) }
        var parts: [String] = []
        if let artist = file.audio?.artist { parts.append(artist) }
        if let ms = file.audio?.durationMs { parts.append(AudioFileText.totalLabel(ms: ms)) }
        parts.append(SharedFile.metaLine(byteCount: file.byteCount, type: file.type))
        return parts.joined(separator: " · ")
    }

    private func row(_ file: PickedFile) -> some View {
        HStack(spacing: 12) {
            tile(file)
                .accessibilityHidden(true)

            VStack(alignment: .leading, spacing: 2) {
                Text(title(file))
                    .font(.system(size: 15, weight: .medium))
                    .foregroundStyle(Theme.textPrimary)
                    .lineLimit(1)
                    .truncationMode(.middle)
                Text(meta(file))
                    .font(.system(size: 13).monospacedDigit())
                    .foregroundStyle(Theme.textSecondary)
                    .lineLimit(1)
                if let warning = file.type.warning {
                    HStack(spacing: 4) {
                        Image(systemName: "exclamationmark.triangle.fill")
                            .font(.system(size: 10, weight: .semibold))
                            .foregroundStyle(Theme.warningIcon)
                        Text(warning.bubbleLine)
                            .font(.system(size: 12, weight: .medium))
                            .foregroundStyle(Theme.warningText)
                    }
                }
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            .accessibilityElement(children: .combine)

            Button {
                Haptics.impact(.light)
                onRemove(file)
            } label: {
                Image(systemName: "xmark.circle.fill")
                    .font(.system(size: 22))
                    .symbolRenderingMode(.hierarchical)
                    .foregroundStyle(Theme.textSecondary)
                    .frame(width: 44, height: 44)
                    .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
            .accessibilityLabel("Remove")
            .accessibilityValue(file.name)
        }
        .padding(.leading, 16)
        .padding(.trailing, 6)
        .padding(.vertical, 8)
    }

    private var captionBar: some View {
        HStack(alignment: .bottom, spacing: 10) {
            TextField("Add a caption…", text: $caption, axis: .vertical)
                .font(.system(size: 16))
                .foregroundStyle(Theme.textPrimary)
                .lineLimit(1 ... 4)
                .focused($captionFocused)
                .padding(.horizontal, 14)
                .padding(.vertical, 10)
                .frame(minHeight: 44)
                .glassEffect(.regular, in: .rect(cornerRadius: 22))

            Button {
                captionFocused = false
                onSend(caption.trimmingCharacters(in: .whitespacesAndNewlines))
            } label: {
                Text("Send")
                    .font(.system(size: 16, weight: .semibold))
                    .foregroundStyle(Color.white)
                    .padding(.horizontal, 18)
                    .frame(height: 44)
                    .background(Theme.accent, in: Capsule())
            }
            .pressable(scale: 0.92, dimming: 0, haptic: .medium)
            .disabled(files.isEmpty)
            .accessibilityLabel(files.count > 1 ? "Send \(files.count) files" : "Send")
        }
        .padding(.horizontal, 12)
        .padding(.top, 8)
        .padding(.bottom, 10)
    }
}
