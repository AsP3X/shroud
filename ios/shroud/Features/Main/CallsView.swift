import SwiftUI

/// Calls tab — the account's call history from the server, newest first; empty state when there
/// is none.
struct CallsView: View {
    @Environment(CallController.self) private var calls

    /// Placeholders only for the first load: a reload over existing rows keeps the list.
    private var showsSkeleton: Bool {
        !calls.hasLoadedHistory && calls.recent.isEmpty
    }

    var body: some View {
        MainScrollScreen(title: "Calls", collapsesTitle: true) {
            EmptyView()
        } navTrailing: {
            EmptyView()
        } accessory: {
            EmptyView()
        } content: {
            if showsSkeleton {
                SkeletonChatList()
            } else if let error = calls.historyError, calls.recent.isEmpty {
                // Nothing loaded and the load failed: don't claim there were no calls.
                ListLoadErrorView(
                    title: "Can't load calls",
                    message: error,
                    retry: { await calls.refreshHistory() }
                )
            } else if calls.recent.isEmpty {
                emptyState
            } else {
                LazyVStack(spacing: 0) {
                    ForEach(Array(calls.recent.enumerated()), id: \.element.id) { index, item in
                        recentRow(item)
                            .entranceRow(index: index)
                            .onAppear {
                                // The last row asks for the next older page.
                                if item.id == calls.recent.last?.id {
                                    Task { await calls.loadOlderHistory() }
                                }
                            }
                        Rectangle()
                            .fill(Theme.separator)
                            .frame(height: 1)
                            .padding(.leading, 76)
                    }
                    if calls.isLoadingOlderHistory {
                        ProgressView()
                            .tint(Theme.textSecondary)
                            .frame(maxWidth: .infinity)
                            .padding(.vertical, 16)
                            .accessibilityLabel("Loading older calls")
                    } else if calls.olderHistoryFailed {
                        // The last row has already appeared and won't ask again by itself.
                        Button {
                            Task { await calls.loadOlderHistory() }
                        } label: {
                            Text("Load older calls")
                                .font(.system(size: 15, weight: .semibold))
                                .foregroundStyle(Theme.accent)
                                .frame(maxWidth: .infinity, minHeight: 44)
                                .contentShape(Rectangle())
                        }
                        .pressable(scale: 0.96)
                        .padding(.vertical, 6)
                    }
                    Color.clear.frame(height: 16)
                }
                // A finished call prepends a row — slide the history down instead of snapping.
                .animation(Motion.standard, value: calls.recent.map(\.id))
            }
        }
        .refreshable {
            await calls.refreshHistory()
        }
        // Calls of our other devices, or from while the socket was down, show on every visit.
        .task {
            await calls.refreshHistory()
        }
        .animation(Motion.fade, value: showsSkeleton)
        .animation(Motion.fade, value: calls.historyError)
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
        // Time today, then "Yesterday", then the date, as in the chat list; VoiceOver hears the
        // whole date and time.
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
                        // "Outgoing video · No answer" is a few points too wide at 375 pt.
                        .minimumScaleFactor(0.85)
                }
                .foregroundStyle(Theme.textSecondary)
            }
            // One stop for VoiceOver, with the whole date and the duration in words. The two
            // call buttons stay their own elements.
            .accessibilityElement(children: .ignore)
            .accessibilityLabel(accessibilityLabel(item))

            Spacer()

            VStack(alignment: .trailing, spacing: 8) {
                Text(time)
                    .font(.system(size: 12))
                    .foregroundStyle(Theme.textSecondary)
                    .accessibilityHidden(true)

                // Far enough apart that the two 44 pt targets never overlap. A deleted account
                // can't be called back.
                if !item.peerDeleted {
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
        }
        .padding(.horizontal, 16)
        .padding(.vertical, 12)
    }

    /// Who called whom and how it went: "Outgoing voice · 4:12", "Incoming video · Missed".
    /// The outcome is close to what the call screen closed with (docs/calls.md), in fewer words:
    /// an unanswered call of ours is "No answer", theirs is "Missed", a call that talked shows
    /// how long, and one that never connected reads "Failed". Coarser in places: a ring of ours
    /// the network dropped still reads "Cancelled". Short enough for one line next to the call
    /// buttons on a 375 pt screen; VoiceOver hears the long forms.
    private func statusLabel(_ item: CallController.RecentCall) -> String {
        "\(item.isOutgoing ? "Outgoing" : "Incoming") \(kindLabel(item)) · \(outcome(item))"
    }

    private func kindLabel(_ item: CallController.RecentCall) -> String {
        item.modality == .video ? "video" : "voice"
    }

    private func outcome(_ item: CallController.RecentCall, spoken: Bool = false) -> String {
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
            if item.connected, let duration = item.duration {
                outcome = spoken ? Self.spokenDuration(duration) : Self.durationLabel(duration)
            } else {
                outcome = item.connected ? "Completed" : (spoken ? "not connected" : "Failed")
            }
        case "answered_elsewhere":
            // Shows only until the call ends; the server's row then has its length.
            outcome = spoken ? "answered on another device" : "Other device"
        default:
            outcome = item.status.capitalized
        }
        return outcome
    }

    /// "Anna, outgoing voice call, 4 minutes, 12 seconds, 30 September 2026 at 09:41".
    private func accessibilityLabel(_ item: CallController.RecentCall) -> String {
        let direction = item.isOutgoing ? "outgoing" : "incoming"
        let when = item.at.formatted(date: .long, time: .shortened)
        return "\(item.peerUsername), \(direction) \(kindLabel(item)) call, \(outcome(item, spoken: true)), \(when)"
    }

    /// "0:42", "4:12", "1:02:03" — as the call screen's timer counted it.
    static func durationLabel(_ seconds: TimeInterval) -> String {
        let total = Int(seconds.rounded(.down))
        let hours = total / 3600
        let minutes = total / 60 % 60
        let secs = total % 60
        return hours > 0
            ? String(format: "%d:%02d:%02d", hours, minutes, secs)
            : String(format: "%d:%02d", minutes, secs)
    }

    private static func spokenDuration(_ seconds: TimeInterval) -> String {
        Duration.seconds(Int(seconds.rounded(.down)))
            .formatted(.units(allowed: [.hours, .minutes, .seconds], width: .wide))
    }
}

#Preview {
    CallsView()
        .environment(CallController())
        .environment(MessagingController())
}
