import SwiftUI

/// Calls tab — recent session history + empty state when idle.
struct CallsView: View {
    @Environment(CallController.self) private var calls
    @Environment(MessagingController.self) private var messaging

    var body: some View {
        MainScrollScreen(title: "Calls", collapsesTitle: true) {
            EmptyView()
        } navTrailing: {
            EmptyView()
        } accessory: {
            EmptyView()
        } content: {
            if calls.recent.isEmpty {
                emptyState
            } else {
                LazyVStack(spacing: 0) {
                    ForEach(Array(calls.recent.enumerated()), id: \.element.id) { index, item in
                        recentRow(item)
                            .entranceRow(index: index)
                        Rectangle()
                            .fill(Theme.separator)
                            .frame(height: 1)
                            .padding(.leading, 76)
                    }
                    Color.clear.frame(height: 88)
                }
                // A finished call prepends a row — slide the history down instead of snapping.
                .animation(Motion.standard, value: calls.recent.map(\.id))
            }
        }
        .listEntranceHost(resetOn: calls.recent.isEmpty)
        .background(Theme.background)
    }

    private var emptyState: some View {
        VStack(spacing: 14) {
            Image(systemName: "phone.fill")
                .font(.system(size: 36, weight: .semibold))
                .foregroundStyle(Theme.accent.opacity(0.85))
                .symbolEffect(.bounce, options: .nonRepeating)
                .padding(.top, 56)

            Text("Voice & video calls")
                .font(.system(size: 18, weight: .semibold))
                .foregroundStyle(Theme.textPrimary)

            Text("Call any contact from their profile or chat. Signaling, WebRTC media, and CallKit are live.")
                .font(.system(size: 14))
                .foregroundStyle(Theme.textSecondary)
                .multilineTextAlignment(.center)
                .padding(.horizontal, 32)

            if !messaging.contacts.isEmpty {
                Text("Tip: open a contact and tap Call or Video.")
                    .font(.system(size: 13))
                    .foregroundStyle(Theme.textSecondary)
                    .padding(.top, 8)
            }

            Color.clear.frame(height: 80)
        }
        .frame(maxWidth: .infinity)
    }

    private func recentRow(_ item: CallController.RecentCall) -> some View {
        HStack(spacing: 12) {
            AvatarView(
                initials: AvatarView.initials(for: item.peerUsername),
                size: 48,
                gradient: AvatarView.gradient(for: item.peerUsername),
                fontSize: 17
            )

            VStack(alignment: .leading, spacing: 2) {
                Text(item.peerUsername)
                    .font(.system(size: 16, weight: .semibold))
                    .foregroundStyle(Theme.textPrimary)
                HStack(spacing: 4) {
                    Image(systemName: item.isOutgoing ? "arrow.up.right" : "arrow.down.left")
                        .font(.system(size: 11, weight: .semibold))
                    Text(statusLabel(item))
                        .font(.system(size: 13))
                }
                .foregroundStyle(Theme.textSecondary)
            }

            Spacer()

            VStack(alignment: .trailing, spacing: 8) {
                Text(item.at.formatted(date: .omitted, time: .shortened))
                    .font(.system(size: 12))
                    .foregroundStyle(Theme.textSecondary)

                HStack(spacing: 8) {
                    Button {
                        Task {
                            await calls.startCall(
                                peerUserID: item.peerUserID,
                                peerUsername: item.peerUsername,
                                modality: .voice
                            )
                        }
                    } label: {
                        Image(systemName: "phone.fill")
                            .font(.system(size: 14, weight: .semibold))
                            .foregroundStyle(Theme.accent)
                            .frame(width: 32, height: 32)
                            .background(Theme.backgroundGrouped)
                            .clipShape(Circle())
                    }
                    .pressable(scale: 0.86, haptic: .medium)
                    .accessibilityLabel("Call \(item.peerUsername)")

                    Button {
                        Task {
                            await calls.startCall(
                                peerUserID: item.peerUserID,
                                peerUsername: item.peerUsername,
                                modality: .video
                            )
                        }
                    } label: {
                        Image(systemName: "video.fill")
                            .font(.system(size: 14, weight: .semibold))
                            .foregroundStyle(Theme.accent)
                            .frame(width: 32, height: 32)
                            .background(Theme.backgroundGrouped)
                            .clipShape(Circle())
                    }
                    .pressable(scale: 0.86, haptic: .medium)
                    .accessibilityLabel("Video call \(item.peerUsername)")
                }
            }
        }
        .padding(.horizontal, 16)
        .padding(.vertical, 12)
    }

    private func statusLabel(_ item: CallController.RecentCall) -> String {
        let kind = item.modality == .video ? "Video" : "Voice"
        switch item.status {
        case "missed", "rejected", "cancelled":
            return "\(kind) · \(item.status.capitalized)"
        case "ended":
            return "\(kind) · Completed"
        default:
            return "\(kind) · \(item.status.capitalized)"
        }
    }
}

#Preview {
    CallsView()
        .environment(CallController())
        .environment(MessagingController())
}
