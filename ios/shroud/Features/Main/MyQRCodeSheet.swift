import SwiftUI

/// Shows the signed-in user's QR code, share link, and short code for inviting contacts.
struct MyQRCodeSheet: View {
    @Environment(SessionController.self) private var session
    @Environment(\.dismiss) private var dismiss

    @State private var copiedMessage: String?

    private var shareCode: String? {
        session.shareCode
    }

    private var shareURL: URL? {
        guard let shareCode else { return nil }
        return ContactInviteParser.shareURL(code: shareCode)
    }

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(spacing: 20) {
                    if let username = session.username {
                        Text("@\(username)")
                            .font(.system(size: 20, weight: .semibold))
                            .foregroundStyle(Theme.textPrimary)
                    }

                    if let url = shareURL {
                        QRCodeView(payload: url.absoluteString, size: 220)

                        VStack(alignment: .leading, spacing: 8) {
                            label("Share link")
                            selectableRow(url.absoluteString) {
                                copy(url.absoluteString, label: "Link copied")
                            }
                        }
                        .frame(maxWidth: .infinity, alignment: .leading)
                        .padding(.horizontal, 4)
                    } else {
                        ProgressView("Loading your code…")
                            .padding(.vertical, 40)
                    }

                    if let shareCode {
                        VStack(alignment: .leading, spacing: 8) {
                            label("Share code")
                            selectableRow(shareCode) {
                                copy(shareCode, label: "Code copied")
                            }
                        }
                        .frame(maxWidth: .infinity, alignment: .leading)
                        .padding(.horizontal, 4)
                    }

                    if let copiedMessage {
                        Text(copiedMessage)
                            .font(.system(size: 13, weight: .medium))
                            .foregroundStyle(Theme.accent)
                    }

                    Text("Friends can scan this QR, open the link, or type your share code to add you.")
                        .font(.system(size: 14))
                        .foregroundStyle(Theme.textSecondary)
                        .multilineTextAlignment(.center)
                        .padding(.top, 8)

                    if let url = shareURL {
                        ShareLink(item: url) {
                            Label("Share invite", systemImage: "square.and.arrow.up")
                                .font(.system(size: 16, weight: .semibold))
                                .frame(maxWidth: .infinity)
                                .padding(.vertical, 14)
                                .background(Theme.accent)
                                .foregroundStyle(.white)
                                .clipShape(RoundedRectangle(cornerRadius: 14, style: .continuous))
                        }
                        .padding(.top, 4)
                    }
                }
                .padding(24)
            }
            .background(Theme.background)
            .navigationTitle("My QR Code")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Done") { dismiss() }
                }
            }
            .task {
                // Ensure share code is loaded for sessions created before this feature.
                if session.shareCode == nil {
                    await session.validateSessionIfNeeded()
                }
            }
        }
    }

    private func label(_ text: String) -> some View {
        Text(text)
            .font(.system(size: 12, weight: .semibold))
            .foregroundStyle(Theme.textSecondary)
    }

    private func selectableRow(_ text: String, onCopy: @escaping () -> Void) -> some View {
        HStack(spacing: 10) {
            Text(text)
                .font(.system(size: 14, design: .monospaced))
                .foregroundStyle(Theme.textPrimary)
                .textSelection(.enabled)
                .lineLimit(2)
                .minimumScaleFactor(0.7)
            Spacer(minLength: 8)
            Button(action: onCopy) {
                Image(systemName: "doc.on.doc")
                    .font(.system(size: 15, weight: .semibold))
                    .foregroundStyle(Theme.accent)
            }
            .pressable(scale: 0.85)
            .accessibilityLabel("Copy")
        }
        .padding(12)
        .background(Theme.backgroundGrouped)
        .clipShape(RoundedRectangle(cornerRadius: 12, style: .continuous))
    }

    private func copy(_ value: String, label: String) {
        UIPasteboard.general.string = value
        Haptics.impact(.light)
        copiedMessage = label
    }
}
