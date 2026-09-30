import SwiftUI

/// Calls tab — the account's call history from the server, newest first; empty state when there
/// is none. Back-to-back calls with the same person within an hour share one section.
struct CallsView: View {
    @Environment(CallController.self) private var calls
    /// Sections the user opened, by `CallRun.id`; every section starts collapsed.
    @State private var expandedRuns: Set<UUID> = []
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    /// Calls in a row with the same person within an hour, newest first: one row for a single
    /// call, a section listing every call of the run for several.
    struct CallRun: Identifiable, Equatable {
        let calls: [CallController.RecentCall]
        /// The oldest call's id: a new call with the same person joins the section rather than
        /// replacing it, and a single row that gains a second call stays in place.
        var id: UUID { calls[calls.count - 1].id }
        var latest: CallController.RecentCall { calls[0] }
    }

    /// The most a section spans, from its newest call to its oldest.
    static let runSpan: TimeInterval = 60 * 60

    /// `calls` (newest first) cut into runs of consecutive calls with the same person, each
    /// within `runSpan` of the run's newest call.
    static func runs(of calls: [CallController.RecentCall]) -> [CallRun] {
        var runs: [[CallController.RecentCall]] = []
        for call in calls {
            if let run = runs.last, run[0].peerUserID == call.peerUserID,
               run[0].at.timeIntervalSince(call.at) <= runSpan {
                runs[runs.count - 1].append(call)
            } else {
                runs.append([call])
            }
        }
        return runs.map(CallRun.init)
    }

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
                let runs = Self.runs(of: calls.recent)
                LazyVStack(spacing: 0) {
                    ForEach(Array(runs.enumerated()), id: \.element.id) { index, run in
                        runView(run)
                            .entranceRow(index: index)
                            .onAppear {
                                // The last row asks for the next older page.
                                if run.id == runs.last?.id {
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

    @ViewBuilder
    private func runView(_ run: CallRun) -> some View {
        if run.calls.count == 1 {
            recentRow(run.latest)
        } else {
            let expanded = expandedRuns.contains(run.id)
            VStack(spacing: 0) {
                runHeader(run, expanded: expanded)
                // The rows unfold from under the header: they slide down inside this container,
                // whose clip grows with them, so nothing shows over the header.
                VStack(spacing: 0) {
                    if expanded {
                        // Every call of the run, newest first, each a row of its own under the
                        // name.
                        VStack(spacing: 0) {
                            ForEach(run.calls) { item in
                                Rectangle()
                                    .fill(Theme.separator)
                                    .frame(height: 1)
                                    .padding(.leading, 76)
                                runCallRow(item)
                            }
                        }
                        // A thread down from the avatar ties the calls to the person above them.
                        .background(alignment: .leading) {
                            Capsule()
                                .fill(Theme.separator)
                                .frame(width: 2)
                                .padding(.leading, 39)
                                .padding(.bottom, 10)
                        }
                        .transition(reduceMotion ? .opacity : .move(edge: .top).combined(with: .opacity))
                    }
                }
                .clipped()
            }
        }
    }

    /// The head of a section: who, how many calls and how many of them were missed, when the
    /// latest was, and a chevron. Tapping it shows or hides the calls; it starts collapsed.
    private func runHeader(_ run: CallRun, expanded: Bool) -> some View {
        Button {
            withAnimation(Motion.respecting(reduceMotion, Motion.standard)) {
                if expanded {
                    expandedRuns.remove(run.id)
                } else {
                    expandedRuns.insert(run.id)
                }
            }
        } label: {
            HStack(spacing: 12) {
                AvatarView(
                    initials: AvatarView.initials(for: run.latest.peerUsername),
                    size: 48,
                    gradient: AvatarView.gradient(for: run.latest.peerUsername),
                    fontSize: 17
                )

                VStack(alignment: .leading, spacing: 2) {
                    Text(run.latest.peerUsername)
                        .font(.system(size: 16, weight: .semibold))
                        .foregroundStyle(Theme.textPrimary)
                        .lineLimit(1)
                    runSummary(run)
                        .font(.system(size: 13))
                        .foregroundStyle(Theme.textSecondary)
                        .lineLimit(1)
                        .contentTransition(.numericText())
                }

                Spacer()

                VStack(alignment: .trailing, spacing: 8) {
                    Text(ChatListFormatting.timeLabel(for: run.latest.at))
                        .font(.system(size: 12))
                        .foregroundStyle(Theme.textSecondary)
                    // The same disc as the call buttons, so it reads as the row's control; the
                    // chevron turns over as the section opens.
                    Image(systemName: "chevron.down")
                        .font(.system(size: 13, weight: .semibold))
                        .foregroundStyle(Theme.textSecondary)
                        .rotationEffect(.degrees(expanded ? -180 : 0))
                        .frame(width: 32, height: 32)
                        .background(Theme.backgroundGrouped)
                        .clipShape(Circle())
                }
            }
            .padding(.horizontal, 16)
            .padding(.vertical, 12)
            .geometryGroup()
        }
        .buttonStyle(HighlightRowButtonStyle())
        .accessibilityElement(children: .ignore)
        .accessibilityLabel("\(run.latest.peerUsername), \(runSummaryLabel(run))")
        .accessibilityValue(expanded ? "Expanded" : "Collapsed")
        .accessibilityHint(expanded ? "Hides the calls" : "Shows the calls")
    }

    /// "3 calls", or "3 calls · 1 missed" with the missed count in red, as a missed call reads
    /// in its own row.
    private func runSummary(_ run: CallRun) -> Text {
        let missed = run.calls.filter(Self.isMissed).count
        guard missed > 0 else { return Text("\(run.calls.count) calls") }
        return Text("\(run.calls.count) calls · \(Text("\(missed) missed").foregroundColor(Theme.danger))")
    }

    private func runSummaryLabel(_ run: CallRun) -> String {
        let missed = run.calls.filter(Self.isMissed).count
        return missed > 0 ? "\(run.calls.count) calls, \(missed) missed" : "\(run.calls.count) calls"
    }

    /// One call inside a section, lined up under the name and kept short: how it went over when,
    /// with the time of day even on older days so calls of the same day tell apart, and the two
    /// call buttons.
    private func runCallRow(_ item: CallController.RecentCall) -> some View {
        HStack(spacing: 12) {
            VStack(alignment: .leading, spacing: 1) {
                HStack(spacing: 4) {
                    Image(systemName: item.isOutgoing ? "arrow.up.right" : "arrow.down.left")
                        .font(.system(size: 11, weight: .semibold))
                        .foregroundStyle(Theme.textSecondary)
                    statusText(item)
                        .font(.system(size: 14))
                        .foregroundStyle(Theme.textPrimary)
                        .lineLimit(1)
                        .minimumScaleFactor(0.85)
                }
                Text(Self.runTimeLabel(for: item.at))
                    .font(.system(size: 12))
                    .foregroundStyle(Theme.textSecondary)
                    .lineLimit(1)
            }
            .accessibilityElement(children: .ignore)
            .accessibilityLabel(accessibilityLabel(item, named: false))

            Spacer(minLength: 0)

            if !item.peerDeleted {
                callButtons(item)
            }
        }
        .padding(.leading, 76)
        .padding(.trailing, 16)
        .padding(.vertical, 8)
        // Rows below an opening section slide as one piece, text with avatar.
        .geometryGroup()
    }

    /// "14:02" today, "Yesterday, 14:02", "28 Sep, 14:02".
    static func runTimeLabel(for date: Date) -> String {
        let calendar = Calendar.current
        let time = date.formatted(date: .omitted, time: .shortened)
        if calendar.isDateInToday(date) { return time }
        if calendar.isDateInYesterday(date) { return "Yesterday, \(time)" }
        return "\(date.formatted(.dateTime.day().month(.abbreviated))), \(time)"
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
                    // Handles have no spaces: a long one is cut off, not broken mid-word.
                    .lineLimit(1)
                HStack(spacing: 4) {
                    Image(systemName: item.isOutgoing ? "arrow.up.right" : "arrow.down.left")
                        .font(.system(size: 11, weight: .semibold))
                    statusText(item)
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

            // Time today, then "Yesterday", then the date, as in the chat list; VoiceOver hears
            // the whole date and time.
            trailing(item, time: ChatListFormatting.timeLabel(for: item.at))
        }
        .padding(.horizontal, 16)
        .padding(.vertical, 12)
        .geometryGroup()
    }

    /// The time over the two call buttons.
    private func trailing(_ item: CallController.RecentCall, time: String) -> some View {
        VStack(alignment: .trailing, spacing: 8) {
            Text(time)
                .font(.system(size: 12))
                .foregroundStyle(Theme.textSecondary)
                .lineLimit(1)
                .accessibilityHidden(true)

            // A deleted account can't be called back.
            if !item.peerDeleted {
                callButtons(item)
            }
        }
    }

    /// Voice and video, far enough apart that the two 44 pt targets never overlap.
    private func callButtons(_ item: CallController.RecentCall) -> some View {
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

    /// Who called whom and how it went: "Outgoing voice · 4:12", "Incoming video · Missed".
    /// The outcome is close to what the call screen closed with (docs/calls.md), in fewer words:
    /// an unanswered call of ours is "No answer", theirs is "Missed", a call that talked shows
    /// how long, and one that never connected reads "Failed". Coarser in places: a ring of ours
    /// the network dropped still reads "Cancelled". Short enough for one line next to the call
    /// buttons on a 375 pt screen; VoiceOver hears the long forms.
    private func statusText(_ item: CallController.RecentCall) -> Text {
        // A call of theirs that we didn't take stands out in red, as in the Phone app.
        let outcome = Self.isMissed(item) ? Text(outcome(item)).foregroundColor(Theme.danger) : Text(outcome(item))
        return Text("\(item.isOutgoing ? "Outgoing" : "Incoming") \(kindLabel(item)) · \(outcome)")
    }

    /// A call of theirs that we never took: rang out, or they gave up.
    static func isMissed(_ item: CallController.RecentCall) -> Bool {
        !item.isOutgoing && (item.status == "missed" || item.status == "cancelled")
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

    /// "Anna, outgoing voice call, 4 minutes, 12 seconds, 30 September 2026 at 09:41"; inside a
    /// section, which already said the name, without it.
    private func accessibilityLabel(_ item: CallController.RecentCall, named: Bool = true) -> String {
        let direction = item.isOutgoing ? "outgoing" : "incoming"
        let when = item.at.formatted(date: .long, time: .shortened)
        let call = "\(direction) \(kindLabel(item)) call, \(outcome(item, spoken: true)), \(when)"
        return named ? "\(item.peerUsername), \(call)" : call.prefix(1).uppercased() + call.dropFirst()
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
