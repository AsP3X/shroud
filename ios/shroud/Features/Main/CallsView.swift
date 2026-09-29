import SwiftUI

/// Calls tab — recent session history + empty state when idle.
struct CallsView: View {
    @Environment(CallController.self) private var calls

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
                    Color.clear.frame(height: 16)
                }
                // A finished call prepends a row — slide the history down instead of snapping.
                .animation(Motion.standard, value: calls.recent.map(\.id))
            }
        }
        .listEntranceHost(resetOn: calls.recent.isEmpty)
        .background(Theme.background)
    }

    /// The same recipe as the Chats and Contacts empty states, so switching tabs never moves it.
    private var emptyState: some View {
        VStack(spacing: 12) {
            Image(systemName: "phone.fill")
                .font(.system(size: 34, weight: .semibold))
                .foregroundStyle(Theme.accent.opacity(0.85))
                .symbolEffect(.bounce, options: .nonRepeating)
                .padding(.bottom, 4)

            Text("No calls yet")
                .font(.system(size: 16, weight: .semibold))
                .foregroundStyle(Theme.textPrimary)

            Text("Your recent calls show up here. To call someone, open their chat or profile and tap Call or Video.")
                .font(.system(size: 14))
                .foregroundStyle(Theme.textSecondary)
                .multilineTextAlignment(.center)
        }
        .frame(maxWidth: .infinity)
        .padding(.top, 48)
        .padding(.horizontal, 24)
    }

    private func recentRow(_ item: CallController.RecentCall) -> some View {
        // Time today, then "Yesterday", then the date, as in the chat list: the history lasts as
        // long as the app does, which can be days.
        let time = ChatListFormatting.timeLabel(for: item.at)
        return HStack(spacing: 12) {
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
                    // Handles have no spaces: a long one is cut off, not broken mid-word.
                    .lineLimit(1)
                HStack(spacing: 4) {
                    Image(systemName: item.isOutgoing ? "arrow.up.right" : "arrow.down.left")
                        .font(.system(size: 11, weight: .semibold))
                    Text(statusLabel(item))
                        .font(.system(size: 13))
                        .lineLimit(1)
                }
                .foregroundStyle(Theme.textSecondary)
            }
            // One stop for VoiceOver, with the direction the arrow only draws. The two call
            // buttons stay their own elements.
            .accessibilityElement(children: .ignore)
            .accessibilityLabel("\(item.peerUsername), \(item.isOutgoing ? "outgoing" : "incoming") \(statusLabel(item)), \(time)")

            Spacer()

            VStack(alignment: .trailing, spacing: 8) {
                Text(time)
                    .font(.system(size: 12))
                    .foregroundStyle(Theme.textSecondary)
                    .accessibilityHidden(true)

                // Far enough apart that the two 44 pt targets never overlap.
                HStack(spacing: 12) {
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
                            // A 44 pt target around the 32 pt disc, with no change to the row.
                            .contentShape(Circle().inset(by: -6))
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
                            .contentShape(Circle().inset(by: -6))
                    }
                    .pressable(scale: 0.86, haptic: .medium)
                    .accessibilityLabel("Video call \(item.peerUsername)")
                }
            }
        }
        .padding(.horizontal, 16)
        .padding(.vertical, 12)
    }

    /// Close to what the call screen closed with (docs/calls.md), in fewer words: an unanswered
    /// call of ours is "No answer", theirs is "Missed", and a call that never connected is not
    /// "Completed". Coarser in places: a ring of ours the network dropped still reads "Cancelled".
    private func statusLabel(_ item: CallController.RecentCall) -> String {
        let kind = item.modality == .video ? "Video" : "Voice"
        let outcome: String
        switch item.status {
        case "missed":
            outcome = item.isOutgoing ? "No answer" : "Missed"
        case "rejected":
            outcome = "Declined"
        case "cancelled":
            outcome = item.isOutgoing ? "Cancelled" : "Missed"
        case "busy":
            outcome = "Busy"
        case "ended":
            outcome = item.connected ? "Completed" : "Not connected"
        case "answered_elsewhere":
            outcome = "Answered elsewhere"
        default:
            outcome = item.status.capitalized
        }
        return "\(kind) · \(outcome)"
    }
}

#Preview {
    CallsView()
        .environment(CallController())
        .environment(MessagingController())
}
