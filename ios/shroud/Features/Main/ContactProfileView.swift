import SwiftUI

/// Contact header from a chat — maps to `Contact Profile` in `iOS-App.pen`.
struct ContactProfileView: View {
    let peerUserID: UUID
    let peerUsername: String

    @Environment(MessagingController.self) private var messaging
    @Environment(CallController.self) private var calls
    @Environment(\.dismiss) private var dismiss
    @State private var toast: String?
    @State private var showBlockConfirm = false
    @State private var isBlocking = false

    private var isOnline: Bool {
        messaging.presenceByUser[peerUserID]?.online == true
    }

    private var isBlocked: Bool {
        messaging.blockedUsers.contains { $0.userId == peerUserID }
    }

    private var statusLine: String {
        if messaging.typingPeerIDs.contains(peerUserID) { return "typing…" }
        if isOnline { return "online" }
        if let last = messaging.presenceByUser[peerUserID]?.lastSeenAt {
            return "last seen \(messaging.timeLabel(for: last))"
        }
        return "Shroud contact"
    }

    var body: some View {
        ScrollView {
            VStack(spacing: 12) {
                profileHead
                actionRow
                infoCard
                optionsCard
                blockCard
            }
            .padding(.horizontal, 16)
            .padding(.top, 4)
            .padding(.bottom, 32)
        }
        .background(Theme.backgroundGrouped.ignoresSafeArea())
        .navigationBarBackButtonHidden(true)
        .toolbar {
            ToolbarItem(placement: .topBarLeading) {
                Button {
                    dismiss()
                } label: {
                    HStack(spacing: 2) {
                        Image(systemName: "chevron.left")
                            .font(.system(size: 16, weight: .semibold))
                        Text("Back")
                            .font(.system(size: 16))
                    }
                    .foregroundStyle(Theme.accent)
                }
            }
            ToolbarItem(placement: .topBarTrailing) {
                Button("Edit") {
                    toast = "Edit coming soon"
                    scheduleClear()
                }
                .foregroundStyle(Theme.accent)
            }
        }
        .toolbarBackground(Theme.backgroundGrouped, for: .navigationBar)
        .toast($toast)
        .task {
            await messaging.refreshPresence(for: [peerUserID])
            await messaging.refreshBlocks()
        }
    }

    private var profileHead: some View {
        VStack(spacing: 3) {
            AvatarView(
                initials: AvatarView.initials(for: peerUsername),
                size: 96,
                gradient: AvatarView.gradient(for: peerUsername),
                fontSize: 34
            )
            .padding(.top, 8)

            VStack(spacing: 2) {
                Text(peerUsername)
                    .font(.system(size: 22, weight: .semibold))
                    .foregroundStyle(Theme.textPrimary)
                Text(statusLine)
                    .font(.system(size: 14))
                    .foregroundStyle(isOnline ? Theme.accent : Theme.textSecondary)
            }
            .padding(.top, 8)
        }
        .frame(maxWidth: .infinity)
    }

    private var actionRow: some View {
        HStack(spacing: 8) {
            profileAction(title: "Call", icon: "phone.fill") {
                Task {
                    await calls.startCall(
                        peerUserID: peerUserID,
                        peerUsername: peerUsername,
                        modality: .voice
                    )
                    if let err = calls.lastError {
                        toast = err
                        scheduleClear()
                    }
                }
            }
            profileAction(title: "Video", icon: "video.fill") {
                Task {
                    await calls.startCall(
                        peerUserID: peerUserID,
                        peerUsername: peerUsername,
                        modality: .video
                    )
                    if let err = calls.lastError {
                        toast = err
                        scheduleClear()
                    }
                }
            }
            profileAction(title: "Mute", icon: "bell.slash.fill") {
                toast = "Mute coming soon"
                scheduleClear()
            }
            profileAction(title: "Search", icon: "magnifyingglass") {
                toast = "Search coming soon"
                scheduleClear()
            }
        }
        .padding(.top, 6)
    }

    private func profileAction(title: String, icon: String, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            VStack(spacing: 5) {
                Image(systemName: icon)
                    .font(.system(size: 18, weight: .semibold))
                    .foregroundStyle(Theme.accent)
                Text(title)
                    .font(.system(size: 12, weight: .medium))
                    .foregroundStyle(Theme.accent)
            }
            .frame(maxWidth: .infinity)
            .padding(.vertical, 10)
            .background(Theme.background)
            .clipShape(RoundedRectangle(cornerRadius: 12, style: .continuous))
            .contentShape(Rectangle())
        }
        .pressable(scale: 0.93)
        .accessibilityLabel(title)
    }

    private var infoCard: some View {
        VStack(alignment: .leading, spacing: 0) {
            VStack(alignment: .leading, spacing: 1) {
                Text("username")
                    .font(.system(size: 12))
                    .foregroundStyle(Theme.textSecondary)
                Text("@\(peerUsername)")
                    .font(.system(size: 16))
                    .foregroundStyle(Theme.textPrimary)
            }
            .padding(.horizontal, 14)
            .padding(.vertical, 9)

            Rectangle()
                .fill(Theme.separator)
                .frame(height: 1)
                .padding(.leading, 14)

            VStack(alignment: .leading, spacing: 1) {
                Text("encryption")
                    .font(.system(size: 12))
                    .foregroundStyle(Theme.textSecondary)
                Text("End-to-end encrypted")
                    .font(.system(size: 16))
                    .foregroundStyle(Theme.textPrimary)
            }
            .padding(.horizontal, 14)
            .padding(.vertical, 9)
        }
        .background(Theme.background)
        .clipShape(RoundedRectangle(cornerRadius: 14, style: .continuous))
    }

    private var optionsCard: some View {
        VStack(spacing: 0) {
            optionsRow(icon: "bell.fill", title: "Notifications", value: "Enabled")
            Rectangle()
                .fill(Theme.separator)
                .frame(height: 1)
                .padding(.leading, 56)
            optionsRow(icon: "lock.fill", title: "Encryption", value: "On")
        }
        .background(Theme.background)
        .clipShape(RoundedRectangle(cornerRadius: 14, style: .continuous))
    }

    private func optionsRow(icon: String, title: String, value: String) -> some View {
        HStack(spacing: 12) {
            Image(systemName: icon)
                .font(.system(size: 15, weight: .semibold))
                .foregroundStyle(Theme.accent)
                .frame(width: 30, height: 30)
                .background(Theme.accentSoft)
                .clipShape(RoundedRectangle(cornerRadius: 8, style: .continuous))
            Text(title)
                .font(.system(size: 16))
                .foregroundStyle(Theme.textPrimary)
            Spacer()
            Text(value)
                .font(.system(size: 15))
                .foregroundStyle(Theme.textSecondary)
            Image(systemName: "chevron.right")
                .font(.system(size: 12, weight: .semibold))
                .foregroundStyle(Theme.textSecondary.opacity(0.7))
        }
        .padding(.horizontal, 14)
        .padding(.vertical, 10)
    }

    /// Blocking is the stronger form of "delete chat for both": that only unlinks the
    /// accounts, while this also stops new contact requests and messages from this user.
    private var blockCard: some View {
        Button {
            showBlockConfirm = true
        } label: {
            HStack(spacing: 8) {
                if isBlocking {
                    ProgressView().controlSize(.small)
                }
                Text(isBlocked ? "Unblock \(peerUsername)" : "Block \(peerUsername)")
                    .font(.system(size: 16))
                    .foregroundStyle(isBlocked ? Theme.accent : Theme.danger)
                Spacer(minLength: 0)
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(.horizontal, 14)
            .padding(.vertical, 13)
            .background(Theme.background)
            .clipShape(RoundedRectangle(cornerRadius: 14, style: .continuous))
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .disabled(isBlocking)
        .confirmationDialog(
            isBlocked ? "Unblock \(peerUsername)?" : "Block \(peerUsername)?",
            isPresented: $showBlockConfirm,
            titleVisibility: .visible
        ) {
            if isBlocked {
                Button("Unblock") { performBlockChange(block: false) }
            } else {
                Button("Block", role: .destructive) { performBlockChange(block: true) }
            }
            Button("Cancel", role: .cancel) {}
        } message: {
            Text(
                isBlocked
                    ? "They can send you a contact request again. Your existing messages are unaffected."
                    : "They can't message you or send contact requests. You'll also stop being contacts."
            )
        }
    }

    private func performBlockChange(block: Bool) {
        isBlocking = true
        Task {
            let error = block
                ? await messaging.blockUser(peerUserID, username: peerUsername)
                : await messaging.unblockUser(peerUserID)
            isBlocking = false
            if let error {
                toast = error
                Haptics.notification(.error)
            } else {
                toast = block ? "\(peerUsername) blocked" : "\(peerUsername) unblocked"
                Haptics.notification(.success)
            }
            scheduleClear()
        }
    }

    private func scheduleClear() {
        Task {
            try? await Task.sleep(nanoseconds: 1_800_000_000)
            toast = nil
        }
    }
}

#Preview {
    NavigationStack {
        ContactProfileView(peerUserID: UUID(), peerUsername: "Jane Cooper")
    }
    .environment(MessagingController())
}
