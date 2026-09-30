import SwiftUI

/// Shows the signed-in user's QR code, share link, and short code for inviting contacts.
struct MyQRCodeSheet: View {
    @Environment(SessionController.self) private var session
    @Environment(\.dismiss) private var dismiss

    @State private var toast: Toast?

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
                            selectableRow(url.absoluteString, copyLabel: "Copy link") {
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
                            selectableRow(shareCode, copyLabel: "Copy share code") {
                                copy(shareCode, label: "Code copied")
                            }
                        }
                        .frame(maxWidth: .infinity, alignment: .leading)
                        .padding(.horizontal, 4)
                    }

                    Text("Friends can scan this QR, open the link, or type your share code to add you.")
                        .font(.system(size: 14))
                        .foregroundStyle(Theme.textSecondary)
                        .multilineTextAlignment(.center)
                        .padding(.top, 8)

                    if let url = shareURL {
                        // PrimaryButton's recipe (54 pt capsule, accent shadow, medium press
                        // haptic); ShareLink can't be wrapped in PrimaryButton itself.
                        ShareLink(item: url) {
                            Label("Share invite", systemImage: "square.and.arrow.up")
                                .font(.system(size: 17, weight: .semibold))
                                .foregroundStyle(Color.white)
                                .frame(maxWidth: .infinity)
                                .frame(height: 54)
                                .background(Theme.accent)
                                .clipShape(Capsule())
                                .shadow(color: Theme.accent.opacity(0.25), radius: 20, y: 8)
                        }
                        .pressable(scale: 0.975, dimming: 0.05, haptic: .medium)
                        .padding(.top, 4)
                    }
                }
                .padding(24)
            }
            .background(Theme.background)
            // Copy confirmations float and clear, like everywhere else, instead of pushing
            // the Share button down for the rest of the sheet's life.
            .toast($toast)
            // The sheet covers the tab bar it inherits this from; the toast sits on its own
            // bottom edge.
            .environment(\.tabBarClearance, 0)
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

    /// `copyLabel` tells the two copy buttons apart for VoiceOver and Voice Control.
    private func selectableRow(_ text: String, copyLabel: String, onCopy: @escaping () -> Void) -> some View {
        HStack(spacing: 10) {
            Text(text)
                .font(.system(size: 14, design: .monospaced))
                .foregroundStyle(Theme.textPrimary)
                .textSelection(.enabled)
                .lineLimit(2)
                .minimumScaleFactor(0.7)
            Spacer(minLength: 8)
            Button(action: onCopy) {
                // 12 pt of hit area around the glyph, taken back out of the layout so the row
                // keeps its height; it overhangs only the row's own padding.
                Image(systemName: "doc.on.doc")
                    .font(.system(size: 15, weight: .semibold))
                    .foregroundStyle(Theme.accent)
                    .padding(12)
                    .contentShape(Rectangle())
            }
            // copy() fires the haptic on success; one tick, not two.
            .pressable(scale: 0.85, haptic: nil)
            .padding(-12)
            .accessibilityLabel(copyLabel)
        }
        .padding(12)
        .background(Theme.backgroundGrouped)
        .clipShape(RoundedRectangle(cornerRadius: 12, style: .continuous))
    }

    private func copy(_ value: String, label: String) {
        UIPasteboard.general.string = value
        Haptics.impact(.light)
        toast = Toast(label)
    }
}
