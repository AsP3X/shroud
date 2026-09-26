import SwiftUI

/// Full-screen in-call chrome for voice and video sessions.
///
/// Human: A call is voice or video by what the two cameras do right now. Either person turns
/// theirs on or off with Video at any time. Their picture opens out of their face as a growing
/// circle once its first frame arrives, and closes back into it; ours sits in the corner while
/// it is on.
struct InCallOverlay: View {
    @Environment(CallController.self) private var calls
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    /// Where the face is on screen: their picture opens from it and closes back into it. A
    /// reference, so measuring the face never re-renders the call screen.
    @State private var face = FaceSpot()

    var body: some View {
        if let active = calls.active {
            content(for: active)
                .transition(.opacity.combined(with: .scale(scale: 0.98)))
        }
    }

    /// Their picture: their camera is on and its frames arrive (never a black or stale frame).
    private var showsRemoteVideo: Bool {
        calls.remoteVideoTrack != nil && calls.active?.remoteCameraOff == false && calls.remoteVideoLive
    }

    /// Our own picture, in the corner, from the camera's first frame.
    private func showsLocalVideo(_ call: CallController.ActiveCall) -> Bool {
        call.isVideoEnabled && call.phase != .ending && calls.localVideoTrack != nil && calls.localVideoLive
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

            // Mounted for the whole call (hidden while their camera is off), so the picture can
            // open out of the face and close back into it instead of popping in and out.
            if let track = calls.remoteVideoTrack {
                CallVideoView(
                    track: track,
                    reveal: .init(open: showsRemoteVideo, warm: call.remoteCameraOff == false, face: face)
                )
                .ignoresSafeArea()
                .allowsHitTesting(false)
            }

            VStack(spacing: 28) {
                Spacer(minLength: 48)

                face(for: call)

                VStack(spacing: 6) {
                    Text(call.peerUsername)
                        .font(.system(size: 26, weight: .semibold))
                        .foregroundStyle(.white)
                        // Always on: it only shows over their picture, and a shadow that fades
                        // with the circle would be redrawn every frame of it.
                        .shadow(color: .black.opacity(0.45), radius: 8, y: 2)
                    statusLabel(for: call)
                    // "You're speaking": only while the call runs with an open mic. Muting
                    // hides it; the Mute control already says so in red.
                    if call.phase == .active, !call.isMuted {
                        SpeakingIndicatorView { await calls.localAudioLevel() }
                            .padding(.top, 6)
                            .transition(.opacity.combined(with: .scale(scale: 0.9)))
                    }
                    if let notice = call.notice, call.phase != .ending {
                        Text(notice)
                            .font(.system(size: 13))
                            .foregroundStyle(.white.opacity(0.8))
                            .multilineTextAlignment(.center)
                    }
                }
                .animation(Motion.snappy, value: call.phase)
                .animation(Motion.snappy, value: call.isMuted)

                Spacer()

                if call.phase != .ending {
                    controls(for: call)
                        .padding(.bottom, 48)
                        .animation(Motion.standard, value: call.phase)
                }
            }
            .padding(.horizontal, 24)

            if showsLocalVideo(call), let local = calls.localVideoTrack {
                CallVideoView(track: local, mirror: calls.usesFrontCamera)
                    .frame(width: 108, height: 164)
                    .clipShape(RoundedRectangle(cornerRadius: 16, style: .continuous))
                    .overlay(alignment: .bottom) {
                        if calls.canSwitchCamera {
                            Image(systemName: "arrow.triangle.2.circlepath")
                                .font(.system(size: 12, weight: .bold))
                                .foregroundStyle(.white)
                                .padding(6)
                                .background(.black.opacity(0.45))
                                .clipShape(Circle())
                                .padding(.bottom, 8)
                        }
                    }
                    .onTapGesture { calls.switchCamera() }
                    .accessibilityLabel("Switch camera")
                    .overlay {
                        RoundedRectangle(cornerRadius: 16, style: .continuous)
                            .strokeBorder(.white.opacity(0.35), lineWidth: 1)
                    }
                    // Video on grows it out of the corner; off shrinks it back there.
                    .transition(.scale(scale: 0.8, anchor: .topTrailing).combined(with: .opacity))
                    .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topTrailing)
                    .padding(.top, 12)
                    .padding(.trailing, 16)
            }
        }
        .animation(Motion.standard, value: showsLocalVideo(call))
    }

    /// The other person's face, where their picture opens from. It keeps its place in the layout
    /// while the picture shows, so nothing below it moves; it only swells and fades as the circle
    /// opens out of it, and comes back in front as the circle closes onto it.
    @ViewBuilder
    private func face(for call: CallController.ActiveCall) -> some View {
        let open = showsRemoteVideo
        ZStack {
            avatar(for: call)
                .overlay(alignment: .bottomTrailing) {
                    if call.remoteMicMuted {
                        Image(systemName: "mic.slash.fill")
                            .font(.system(size: 12, weight: .bold))
                            .foregroundStyle(.white)
                            .padding(6)
                            .background(Theme.danger)
                            .clipShape(Circle())
                            .offset(x: 4, y: 4)
                    }
                }
                .scaleEffect(open && !reduceMotion ? 1.14 : 1)
                .opacity(open ? 0 : 1)
                .animation(faceAnimation(call, open: open), value: open)
        }
        // Measured outside the scale above: the circle needs the face's resting place and size.
        // Written to a reference, not state: the video reads it when its circle starts to move.
        .onGeometryChange(for: CGRect.self) { $0.frame(in: .global) } action: { [face] in face.frame = $0 }
        .accessibilityHidden(open)
    }

    /// The face gives way at once as the circle opens. Closing, it comes straight back while the
    /// circle shrinks behind it, and is whole before the circle lands
    /// (`CallVideoContainer.closeDuration`).
    private func faceAnimation(_ call: CallController.ActiveCall, open: Bool) -> Animation {
        if open || reduceMotion || call.phase == .ending { return .easeOut(duration: 0.2) }
        return .easeOut(duration: 0.26).delay(0.06)
    }

    /// Human: Liquid Glass circles over the backdrop or the remote video, as the system's
    /// own call screen draws them. A control that is *on* (muted, speaker, camera off) takes
    /// a colour tint; the rest stay clear glass. One container, so neighbours morph together.
    @ViewBuilder
    private func controls(for call: CallController.ActiveCall) -> some View {
        GlassEffectContainer(spacing: 22) {
            controlRow(for: call)
        }
    }

    @ViewBuilder
    private func controlRow(for call: CallController.ActiveCall) -> some View {
        HStack(spacing: 22) {
            if call.phase != .incomingRinging {
                callButton(
                    icon: call.isMuted ? "mic.slash.fill" : "mic.fill",
                    label: call.isMuted ? "Unmute" : "Mute",
                    tint: call.isMuted ? Theme.danger : nil
                ) {
                    Task { await calls.toggleMute() }
                }

                // Every call has it: on makes a voice call a video call, off makes it voice again.
                let videoAvailable = call.canVideo || call.isVideoEnabled
                callButton(
                    icon: call.isVideoEnabled ? "video.fill" : "video.slash.fill",
                    label: "Video",
                    tint: call.isVideoEnabled ? Theme.accent : nil,
                    accessibilityLabel: call.isVideoEnabled ? "Turn video off" : "Turn video on"
                ) {
                    Task { await calls.toggleVideo() }
                }
                .disabled(!videoAvailable)
                .opacity(videoAvailable ? 1 : 0.45)

                callButton(
                    icon: call.speakerOn ? "speaker.wave.2.fill" : "speaker.fill",
                    label: "Speaker",
                    tint: call.speakerOn ? Theme.accent : nil
                ) {
                    calls.toggleSpeaker()
                }
            }

            if call.phase == .incomingRinging {
                callButton(icon: "phone.down.fill", label: "Decline", tint: Theme.danger) {
                    Task { await calls.rejectIncoming() }
                }
                callButton(icon: "phone.fill", label: "Accept", tint: Theme.online) {
                    Task { await calls.acceptIncoming() }
                }
            } else {
                callButton(icon: "phone.down.fill", label: "End", tint: Theme.danger) {
                    Task { await calls.hangup() }
                }
            }
        }
    }

    /// Human: While ringing, the avatar breathes so the screen never looks frozen on a
    /// slow network; once connected it settles to a steady state.
    @ViewBuilder
    private func avatar(for call: CallController.ActiveCall) -> some View {
        let base = AvatarView(
            initials: AvatarView.initials(for: call.peerUsername),
            size: 104,
            gradient: AvatarView.gradient(for: call.peerUsername),
            fontSize: 36
        )

        if isRinging(call.phase) {
            base.phaseAnimator([false, true]) { view, big in
                view
                    .scaleEffect(big ? 1.05 : 0.97)
                    .shadow(color: Theme.accent.opacity(big ? 0.45 : 0.15), radius: big ? 34 : 14)
            } animation: { _ in .easeInOut(duration: 1.1) }
        } else {
            base.opacity(call.phase == .active ? 1 : 0.92)
        }
    }

    private func isRinging(_ phase: CallController.Phase) -> Bool {
        phase == .outgoingRinging || phase == .incomingRinging || phase == .connecting
    }

    /// Human: An active call must show a *running* clock — a one-shot `Date()` read renders
    /// once and then sits there looking broken. TimelineView re-renders it every second.
    @ViewBuilder
    private func statusLabel(for call: CallController.ActiveCall) -> some View {
        Group {
            if call.phase == .active, call.reconnecting {
                Text("Reconnecting…")
            } else if call.phase == .active, let start = call.startedAt {
                TimelineView(.periodic(from: start, by: 1)) { context in
                    Text(elapsed(from: start, now: context.date))
                        .monospacedDigit()
                        .contentTransition(.numericText(countsDown: false))
                }
            } else {
                Text(statusLine(for: call))
                    .contentTransition(.opacity)
            }
        }
        .font(.system(size: 15))
        .foregroundStyle(.white.opacity(0.72))
        .animation(Motion.snappy, value: call.phase)
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
            return "Connected"
        case .ending:
            return call.endedText ?? "Call ended"
        }
    }

    private func elapsed(from start: Date, now: Date) -> String {
        let seconds = max(0, Int(now.timeIntervalSince(start)))
        let m = seconds / 60
        let s = seconds % 60
        return String(format: "%d:%02d", m, s)
    }

    /// One call control: a 64 pt glass circle (tinted when `tint` is set) over its caption.
    private func callButton(
        icon: String,
        label: String,
        tint: Color?,
        accessibilityLabel: String? = nil,
        action: @escaping () -> Void
    ) -> some View {
        Button(action: action) {
            VStack(spacing: 8) {
                Image(systemName: icon)
                    .font(.system(size: 22, weight: .semibold))
                    .foregroundStyle(.white)
                    // Mute / video glyphs morph through their slashed variant.
                    .contentTransition(.symbolEffect(.replace))
                    .frame(width: 64, height: 64)
                    .contentShape(Circle())
                    // Interactive glass swells under the finger; a tint marks an "on" state.
                    .glassEffect(callGlass(tint: tint), in: .circle)
                Text(label)
                    .font(.system(size: 12, weight: .medium))
                    .foregroundStyle(.white.opacity(0.8))
                    .contentTransition(.opacity)
            }
            .contentShape(Rectangle())
            .accessibilityElement(children: .ignore)
            .accessibilityLabel(accessibilityLabel ?? label)
        }
        // Call controls are consequential — a heavier tick on press-down.
        .buttonStyle(PressableButtonStyle(scale: 1, dimming: 0, haptic: .medium))
        .animation(Motion.snappy, value: icon)
        .animation(Motion.snappy, value: tint)
        .transition(Motion.iconSwap)
    }

    private func callGlass(tint: Color?) -> Glass {
        if let tint {
            return .regular.tint(tint).interactive()
        }
        return .regular.interactive()
    }
}
