import SwiftUI

/// Full-screen in-call chrome for voice and video sessions.
struct InCallOverlay: View {
    @Environment(CallController.self) private var calls

    var body: some View {
        if let active = calls.active {
            content(for: active)
                .transition(.opacity.combined(with: .scale(scale: 0.98)))
        }
    }

    @ViewBuilder
    private func content(for call: CallController.ActiveCall) -> some View {
        ZStack {
            LinearGradient(
                colors: [
                    Color(red: 0.08, green: 0.10, blue: 0.16),
                    Color(red: 0.05, green: 0.06, blue: 0.10),
                ],
                startPoint: .top,
                endPoint: .bottom
            )
            .ignoresSafeArea()

            VStack(spacing: 28) {
                Spacer(minLength: 48)

                AvatarView(
                    initials: AvatarView.initials(for: call.peerUsername),
                    size: 104,
                    gradient: AvatarView.gradient(for: call.peerUsername),
                    fontSize: 36
                )
                .opacity(call.phase == .active ? 1 : 0.92)

                VStack(spacing: 6) {
                    Text(call.peerUsername)
                        .font(.system(size: 26, weight: .semibold))
                        .foregroundStyle(.white)
                    Text(statusLine(for: call))
                        .font(.system(size: 15))
                        .foregroundStyle(.white.opacity(0.72))
                    if call.modality == .video {
                        Text("Video")
                            .font(.system(size: 13, weight: .medium))
                            .foregroundStyle(Theme.accent)
                    }
                }

                Spacer()

                HStack(spacing: 28) {
                    callButton(
                        icon: call.isMuted ? "mic.slash.fill" : "mic.fill",
                        label: call.isMuted ? "Unmute" : "Mute",
                        color: call.isMuted ? Theme.danger : .white.opacity(0.18)
                    ) {
                        Task { await calls.toggleMute() }
                    }

                    if call.modality == .video {
                        callButton(
                            icon: call.isVideoEnabled ? "video.fill" : "video.slash.fill",
                            label: "Video",
                            color: call.isVideoEnabled ? .white.opacity(0.18) : Theme.danger
                        ) {
                            Task { await calls.toggleVideo() }
                        }
                    }

                    if call.phase == .incomingRinging {
                        callButton(icon: "phone.down.fill", label: "Decline", color: Theme.danger) {
                            Task { await calls.rejectIncoming() }
                        }
                        callButton(icon: "phone.fill", label: "Accept", color: Theme.online) {
                            Task { await calls.acceptIncoming() }
                        }
                    } else {
                        callButton(icon: "phone.down.fill", label: "End", color: Theme.danger) {
                            Task { await calls.hangup() }
                        }
                    }
                }
                .padding(.bottom, 48)
            }
            .padding(.horizontal, 24)
        }
    }

    private func statusLine(for call: CallController.ActiveCall) -> String {
        switch call.phase {
        case .idle:
            return ""
        case .outgoingRinging:
            return "Calling…"
        case .incomingRinging:
            return call.modality == .video ? "Incoming video call" : "Incoming call"
        case .connecting:
            return "Connecting…"
        case .active:
            if let start = call.startedAt {
                return elapsed(from: start)
            }
            return "Connected"
        case .ending:
            return "Ending…"
        }
    }

    private func elapsed(from start: Date) -> String {
        let seconds = max(0, Int(Date().timeIntervalSince(start)))
        let m = seconds / 60
        let s = seconds % 60
        return String(format: "%d:%02d", m, s)
    }

    private func callButton(
        icon: String,
        label: String,
        color: Color,
        action: @escaping () -> Void
    ) -> some View {
        Button(action: action) {
            VStack(spacing: 8) {
                Image(systemName: icon)
                    .font(.system(size: 22, weight: .semibold))
                    .foregroundStyle(.white)
                    .frame(width: 64, height: 64)
                    .background(color)
                    .clipShape(Circle())
                Text(label)
                    .font(.system(size: 12, weight: .medium))
                    .foregroundStyle(.white.opacity(0.8))
            }
        }
        .buttonStyle(.plain)
    }
}
