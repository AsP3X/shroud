import SwiftUI

/// Full-screen in-call chrome for voice and video sessions.
///
/// Human: A call is voice or video by what the two cameras do right now. Either person turns
/// theirs on or off with Video at any time. Their picture opens out of their face as a growing
/// circle once its first frame arrives, and closes back into it; ours sits in the corner while
/// it is on. The name, the running time and the speaking meter show under the face. They stay
/// there until the other person's camera is actually showing: our own picture is a small corner
/// tile and does not cover them. Their picture moves the three together, up and across at once,
/// into the top-leading corner, and back under the face when their camera turns off
/// (`CallStageLayout`).
///
/// Either person can share their screen next to their camera. Theirs fills the screen, fitted
/// whole on black and zoomable (`SharedScreenView`), their camera moves into a tile above ours,
/// and the controls step aside after a few seconds (a tap brings them back). Ours is the phone's
/// whole screen, through the system's broadcast; a capsule over the controls says it is shared
/// and stops it.
struct InCallOverlay: View {
    @Environment(CallController.self) private var calls
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    /// Where the face is on screen: their picture opens from it and closes back into it. A
    /// reference, so measuring the face never re-renders the call screen.
    @State private var face = FaceSpot()
    /// The call the name block has been placed for, and whether that block is in the corner.
    /// Until the first placement, the corner follows the cameras directly so the opening frame
    /// is already right.
    @State private var placedCall: UUID?
    @State private var inCorner = false
    /// Reduce Motion only: the name block is faded out while it changes places.
    @State private var blockHidden = false
    /// Over their shared screen, the controls and the name step aside.
    @State private var chromeHidden = false
    /// Bumped by every touch on the call screen: the controls stay up a while longer.
    @State private var chromeTouch = 0
    @State private var broadcastPicker = BroadcastPickerTrigger()

    /// Our own picture, in the top-trailing corner of the safe area.
    static let selfViewSize = CGSize(width: 108, height: 164)
    /// Beside a shared screen the pictures are tiles, theirs above ours, a little smaller.
    static let tileSize = CGSize(width: 90, height: 136)
    /// How long the controls stay up over a shared screen once nothing is touched.
    static let chromeLinger: Duration = .seconds(4)
    static let selfViewInsets = EdgeInsets(top: 12, leading: 0, bottom: 0, trailing: 16)
    /// What the docked name block leaves free at the trailing edge: our picture, its inset and a
    /// 12 pt gap. Kept free whether or not our camera is on, so turning it on never resizes the block.
    static let selfViewReserve = selfViewInsets.trailing + selfViewSize.width + 12

    /// The name block leaves the face only while their picture is on screen. Our own camera
    /// does not: it sits in the opposite corner and never covers the name.
    static func nameBelongsInCorner(remotePicture: Bool) -> Bool {
        remotePicture
    }

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

    /// Their shared screen: they share it and its frames arrive. It takes the whole screen, and
    /// their camera goes into a tile.
    private var showsRemoteScreen: Bool {
        calls.remoteScreenTrack != nil && calls.active?.remoteSharingScreen == true && calls.remoteScreenLive
            && calls.active?.phase != .ending
    }

    /// The controls and the name are out of the way over their screen.
    private var chromeAway: Bool {
        chromeHidden && showsRemoteScreen
    }

    /// Our own picture, in the corner, from the camera's first frame.
    private func showsLocalVideo(_ call: CallController.ActiveCall) -> Bool {
        call.isVideoEnabled && call.phase != .ending && calls.localVideoTrack != nil && calls.localVideoLive
    }

    @ViewBuilder
    private func content(for call: CallController.ActiveCall) -> some View {
        let screen = showsRemoteScreen
        let sharing = (call.isSharingScreen || call.screenShareStarting) && call.phase != .ending
        let picture = Self.nameBelongsInCorner(remotePicture: showsRemoteVideo || screen)
        // Ending can drop their picture at once. Hold the name where it already is for that last moment.
        let videoOn = call.phase == .ending && placedCall == call.id ? inCorner : picture
        let docked = placedCall == call.id ? inCorner : videoOn
        let ending = call.phase == .ending
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
                    // Over their screen their camera is a tile; the full picture closes onto the face.
                    reveal: .init(open: showsRemoteVideo && !screen, warm: call.remoteCameraOff == false, face: face)
                )
                .ignoresSafeArea()
                .allowsHitTesting(false)
            }

            // Mounted as soon as they say they share, so its renderer has a frame by the time the
            // first one is announced; shown from then, fading in, and out when they stop.
            if let track = calls.remoteScreenTrack, call.remoteSharingScreen, call.phase != .ending {
                SharedScreenView(track: track) { toggleChrome() }
                    .ignoresSafeArea()
                    .opacity(screen ? 1 : 0)
                    .scaleEffect(screen || reduceMotion ? 1 : 0.97)
                    .animation(.easeOut(duration: 0.28), value: screen)
                    .allowsHitTesting(screen)
                    .accessibilityHidden(!screen)
                    .transition(.opacity)
            }

            topShade
                .opacity((showsRemoteVideo || screen) && !chromeAway ? 1 : 0)
                .animation(.easeOut(duration: 0.3), value: showsRemoteVideo || screen)
                .animation(.easeOut(duration: 0.3), value: chromeAway)

            VStack(spacing: 28) {
                CallStageLayout(progress: docked ? 1 : 0) {
                    face(for: call)
                        // On top: a name too long to clear the face passes behind it.
                        .zIndex(1)
                    info(for: call)
                        .animation(Motion.snappy, value: call.phase)
                        .animation(Motion.snappy, value: call.isMuted)
                        .opacity(blockHidden || chromeAway ? 0 : 1)
                        .animation(.easeOut(duration: 0.25), value: chromeAway)
                }
                .frame(maxWidth: .infinity, maxHeight: .infinity)

                controls(for: call)
                .padding(.bottom, 48)
                .animation(Motion.standard, value: call.phase)
                // Ending, the row goes at once but keeps its room, so the face and the name
                // stay where they are for the last moment of the screen.
                .opacity(ending || chromeAway ? 0 : 1)
                .animation(nil, value: ending)
                .animation(.easeOut(duration: 0.25), value: chromeAway)
                .allowsHitTesting(!ending && !chromeAway)
                .accessibilityHidden(ending || chromeAway)
            }
            // Clear of the sharing pill while it shows.
            .padding(.top, sharing ? Self.sharingIndicatorInset : 0)

            tiles(for: call, screen: screen)
                .padding(.top, sharing ? Self.sharingIndicatorInset : 0)

            if sharing {
                sharingIndicator(starting: !call.isSharingScreen)
                    .padding(.top, 4)
                    .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .top)
                    .transition(.move(edge: .top).combined(with: .opacity))
            }

            // The system's broadcast picker, out of sight; Share opens it.
            BroadcastPickerHost(trigger: broadcastPicker)
                .frame(width: 1, height: 1)
                .allowsHitTesting(false)
                .accessibilityHidden(true)
                .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .bottomLeading)
        }
        .animation(Motion.standard, value: showsLocalVideo(call))
        .animation(Motion.standard, value: screen)
        .animation(Motion.snappy, value: sharing)
        .task(id: NamePlace(call: call.id, video: videoOn)) {
            await placeName(call.id, inCorner: videoOn)
        }
        .task(id: ChromeClock(screen: screen, hidden: chromeHidden, touch: chromeTouch)) {
            await lingerChrome(screen: screen)
        }
    }

    /// The pictures in the top-trailing corner: theirs above ours while their screen fills the
    /// rest, ours alone otherwise. Tapping ours flips the camera.
    @ViewBuilder
    private func tiles(for call: CallController.ActiveCall, screen: Bool) -> some View {
        let size = screen ? Self.tileSize : Self.selfViewSize
        VStack(alignment: .trailing, spacing: 10) {
            if screen, showsRemoteVideo, let theirs = calls.remoteVideoTrack {
                CallVideoView(track: theirs)
                    .frame(width: size.width, height: size.height)
                    .clipShape(RoundedRectangle(cornerRadius: 16, style: .continuous))
                    .overlay {
                        RoundedRectangle(cornerRadius: 16, style: .continuous)
                            .strokeBorder(.white.opacity(0.35), lineWidth: 1)
                    }
                    .allowsHitTesting(false)
                    .accessibilityLabel("\(call.peerUsername)’s camera")
                    .transition(.scale(scale: 0.8, anchor: .topTrailing).combined(with: .opacity))
            }
            if showsLocalVideo(call), let local = calls.localVideoTrack {
                CallVideoView(track: local, mirror: calls.usesFrontCamera)
                    .frame(width: size.width, height: size.height)
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
            }
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topTrailing)
        .padding(.top, Self.selfViewInsets.top)
        .padding(.trailing, Self.selfViewInsets.trailing)
    }

    /// We share our screen: a small red pill at the top centre, under the status bar, for as long
    /// as it lasts, with Stop at its end. It stays when the controls step aside, and says
    /// "Starting…" from the broadcast's connection to its first frame.
    private func sharingIndicator(starting: Bool) -> some View {
        HStack(spacing: 7) {
            Circle()
                .fill(.white)
                .frame(width: 7, height: 7)
                .phaseAnimator([1.0, 0.35]) { dot, level in
                    dot.opacity(reduceMotion ? 1 : level)
                } animation: { _ in .easeInOut(duration: 0.8) }
            Text(starting ? "Starting…" : "Sharing screen")
                .font(.system(size: 13, weight: .semibold))
                .foregroundStyle(.white)
                .lineLimit(1)
                .contentTransition(.opacity)
            Button {
                _ = calls.toggleScreenShare()
            } label: {
                Image(systemName: "stop.fill")
                    .font(.system(size: 9, weight: .bold))
                    .foregroundStyle(Theme.danger)
                    .frame(width: 22, height: 22)
                    .background(.white, in: Circle())
                    // A finger-sized target around the small disc.
                    .padding(4)
                    .contentShape(Rectangle())
            }
            .buttonStyle(PressableButtonStyle(scale: 0.9, dimming: 0, haptic: .medium))
            .accessibilityLabel("Stop sharing your screen")
        }
        .padding(.leading, 12)
        .glassEffect(.regular.tint(Theme.danger.opacity(0.75)), in: .capsule)
        .accessibilityElement(children: .contain)
        .accessibilityLabel(starting ? "Starting to share your screen" : "You’re sharing your screen")
    }

    /// Room the pill takes at the top: the stage and the tiles move down by it while it shows.
    static let sharingIndicatorInset: CGFloat = 40

    /// What the controls' timer follows: their screen coming or going, a tap, any touch.
    private struct ChromeClock: Equatable {
        let screen: Bool
        let hidden: Bool
        let touch: Int
    }

    /// Over their screen, the controls go after a few seconds untouched. Never with VoiceOver,
    /// which needs them in reach. Without their screen they are always up.
    private func lingerChrome(screen: Bool) async {
        guard screen else {
            if chromeHidden { chromeHidden = false }
            return
        }
        guard !chromeHidden, !UIAccessibility.isVoiceOverRunning else { return }
        do {
            try await Task.sleep(for: Self.chromeLinger)
        } catch {
            return
        }
        chromeHidden = true
    }

    private func toggleChrome() {
        chromeHidden.toggle()
        chromeTouch += 1
    }

    /// Share: the system's broadcast picker opens (the person starts the broadcast there), or
    /// the broadcast stops.
    private func toggleShare() {
        if calls.toggleScreenShare() { broadcastPicker.open() }
    }

    /// The safety number, and a tap once it has been compared. It does not block the call.
    @ViewBuilder
    private func safetyCompare(for call: CallController.ActiveCall) -> some View {
        if let number = calls.safetyNumberForActiveCall() {
            Text(number)
                .font(.system(size: 11, weight: .medium, design: .monospaced))
                .foregroundStyle(.white.opacity(0.85))
                .multilineTextAlignment(.center)
                .fixedSize(horizontal: false, vertical: true)
                .shadow(color: .black.opacity(0.5), radius: 3, y: 1)
        }
        Button {
            calls.confirmSafety()
        } label: {
            Text("Safety number not compared")
                .font(.system(size: 13, weight: .medium))
                .foregroundStyle(.white.opacity(0.9))
        }
        .buttonStyle(.plain)
    }

    /// Human: The name, the status line and the speaking meter, centred on each other in both
    /// places. The whole group travels as one piece: nothing inside it re-aligns on the way, so
    /// the text is not measured or drawn again while it moves.
    /// Agent: One view in both places. `CallStageLayout` moves it; alignment stays `.center`.
    private func info(for call: CallController.ActiveCall) -> some View {
        VStack(alignment: .center, spacing: 6) {
            Text(call.peerUsername)
                .font(.system(size: 26, weight: .semibold))
                .foregroundStyle(.white)
                // One line in both places: a long handle (they have no spaces to wrap at) comes
                // down a little in size rather than breaking mid-word.
                .lineLimit(1)
                .minimumScaleFactor(0.7)
                // Always on: it only shows over their picture, and a shadow that fades with the
                // circle would be redrawn every frame of it.
                .shadow(color: .black.opacity(0.45), radius: 8, y: 2)
            statusLabel(for: call)
                // Tighter than the name's: the smaller, dimmer line needs a firm edge over a
                // bright picture.
                .shadow(color: .black.opacity(0.5), radius: 3, y: 1)
            if !call.safetyVerified, call.phase != .ending {
                safetyCompare(for: call)
            }
            // "You're speaking": only while the call runs with an open mic. Muting hides it; the
            // Mute control already says so in red.
            if call.phase == .active, !call.isMuted {
                SpeakingIndicatorView { await calls.localAudioLevel() }
                    .padding(.top, 6)
                    .transition(.opacity.combined(with: .scale(scale: 0.9, anchor: .center)))
            }
            if let notice = call.notice, call.phase != .ending {
                Text(notice)
                    .font(.system(size: 13))
                    .foregroundStyle(.white.opacity(0.8))
                    .multilineTextAlignment(.center)
                    .shadow(color: .black.opacity(0.5), radius: 3, y: 1)
            }
        }
    }

    /// Human: Over their picture, a shade from the top edge to below the name block keeps white
    /// text readable on any frame, and the status bar with it: it holds its depth down past the
    /// clock (4.5:1 there even on a blown-out white wall) and then falls away. A plain gradient:
    /// fading it in and out with the picture blends one layer, nothing is redrawn.
    private var topShade: some View {
        Color.clear
            .frame(maxWidth: .infinity)
            .frame(height: 220)
            .background(
                LinearGradient(
                    stops: [
                        .init(color: .black.opacity(0.62), location: 0),
                        .init(color: .black.opacity(0.58), location: 0.42),
                        .init(color: .black.opacity(0.3), location: 0.7),
                        .init(color: .black.opacity(0), location: 1),
                    ],
                    startPoint: .top,
                    endPoint: .bottom
                ),
                // Up under the status bar (and round the sides in landscape).
                ignoresSafeAreaEdges: [.top, .horizontal]
            )
            .frame(maxHeight: .infinity, alignment: .top)
            .allowsHitTesting(false)
            .accessibilityHidden(true)
    }

    // MARK: - Placing the name block

    /// What the name follows: a new call, or a picture arriving or leaving.
    private struct NamePlace: Equatable {
        let call: UUID
        let video: Bool
    }

    /// Human: A voice call, and a call where only our camera is on, keeps the name under the face.
    /// Their picture moves it into the corner, and their camera turning off brings it back. The
    /// first frame of a call is already in the right place; later changes travel on one spring.
    /// Reduce Motion fades instead of travelling.
    /// Agent: WRITES placedCall and inCorner. The `.task` id cancels an in-flight fade when the
    /// picture changes again; that fade leaves the text hidden for the next one to finish.
    private func placeName(_ id: UUID, inCorner video: Bool) async {
        if placedCall != id {
            placedCall = id
            var snap = Transaction()
            snap.disablesAnimations = true
            withTransaction(snap) {
                inCorner = video
                blockHidden = false
            }
            return
        }
        guard reduceMotion else {
            withAnimation(Motion.standard) {
                inCorner = video
                blockHidden = false
            }
            return
        }
        if inCorner == video {
            if blockHidden { withAnimation(Motion.reduced) { blockHidden = false } }
            return
        }
        if !blockHidden {
            withAnimation(Motion.reduced) { blockHidden = true }
            do {
                try await Task.sleep(for: .seconds(Motion.reducedDuration))
            } catch {
                return
            }
        }
        var unseen = Transaction()
        unseen.disablesAnimations = true
        withTransaction(unseen) { inCorner = video }
        withAnimation(Motion.reduced) { blockHidden = false }
    }

    /// The other person's face, where their picture opens from. It keeps its place in the layout
    /// while the picture shows, so nothing below it moves; it only swells and fades as the circle
    /// opens out of it, and comes back in front as the circle closes onto it.
    @ViewBuilder
    private func face(for call: CallController.ActiveCall) -> some View {
        let open = showsRemoteVideo || showsRemoteScreen
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
        GlassEffectContainer(spacing: Self.controlSpacing(for: call.phase)) {
            controlRow(for: call)
        }
    }

    /// Five controls in a call (Share joined them) fit a narrow phone a little closer together;
    /// the two answers of a ring keep their room.
    private static func controlSpacing(for phase: CallController.Phase) -> CGFloat {
        phase == .incomingRinging ? 22 : 14
    }

    @ViewBuilder
    private func controlRow(for call: CallController.ActiveCall) -> some View {
        HStack(spacing: Self.controlSpacing(for: call.phase)) {
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

                // Next to the camera, never instead of it: the whole screen, through the system's
                // broadcast. Their app must be able to show it.
                // Dimmed when it cannot be used yet, but it still takes the tap, which says why.
                let sharing = call.isSharingScreen || call.screenShareStarting
                let shareAvailable = call.canShareScreen || sharing
                callButton(
                    icon: "rectangle.inset.filled.on.rectangle",
                    label: "Share",
                    tint: sharing ? Theme.accent : nil,
                    accessibilityLabel: sharing ? "Stop sharing your screen" : "Share your screen"
                ) {
                    toggleShare()
                }
                .opacity(shareAvailable ? 1 : 0.45)
                .accessibilityHint(shareAvailable ? "" : "Not available yet.")

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
        // Bright enough for 4.5:1 over the top shade even on a white picture, still a step
        // below the name.
        .foregroundStyle(.white.opacity(0.85))
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

    /// One call control: a 60 pt glass circle (tinted when `tint` is set) over its caption.
    private func callButton(
        icon: String,
        label: String,
        tint: Color?,
        accessibilityLabel: String? = nil,
        action: @escaping () -> Void
    ) -> some View {
        Button {
            // A control used over their screen keeps the controls up a while longer.
            chromeTouch += 1
            action()
        } label: {
            VStack(spacing: 8) {
                Image(systemName: icon)
                    .font(.system(size: 21, weight: .semibold))
                    .foregroundStyle(.white)
                    // Mute / video glyphs morph through their slashed variant.
                    .contentTransition(.symbolEffect(.replace))
                    .frame(width: 60, height: 60)
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

// MARK: - Stage layout

/// Places the call screen's face and the name block that belongs to it.
///
/// Human: The face sits in the middle of the space above the controls. On a voice call, and
/// while only our camera is on, the name, the status line and the speaking meter hang under it.
/// Their picture puts that group in the top-leading corner, level with our own picture and clear
/// of it. On the way the group travels up and across together, one straight glide; the face stays
/// in the middle and is drawn on top, so a long name passes behind it. Only positions change per
/// frame: the text keeps one size and one line throughout, so none of it is laid out or drawn
/// again on the way.
/// Agent: Expects two subviews, face then block. `progress` is animatable (0 under the face, 1 in
/// the corner); the geometry is `frames(stage:face:block:progress:)`, unit-tested in
/// CallStageLayoutTests, and `placeSubviews` only applies it.
struct CallStageLayout: Layout {
    /// 0: the block hangs under the face. 1: it sits in the corner.
    var progress: CGFloat

    var animatableData: CGFloat {
        get { progress }
        set { progress = newValue }
    }

    /// Between the face and the block under it.
    static let gap: CGFloat = 28
    /// The block's side margins under the face.
    static let margin: CGFloat = 24
    /// The docked block's top-leading corner, from the stage's: level with our own picture.
    static let corner = CGPoint(x: 20, y: InCallOverlay.selfViewInsets.top)
    /// The block never grows wider than this (landscape, iPad): a notice wraps instead.
    static let cornerMaxWidth: CGFloat = 320

    func sizeThatFits(proposal: ProposedViewSize, subviews: Subviews, cache: inout Void) -> CGSize {
        proposal.replacingUnspecifiedDimensions()
    }

    func placeSubviews(
        in bounds: CGRect,
        proposal: ProposedViewSize,
        subviews: Subviews,
        cache: inout Void
    ) {
        guard subviews.count == 2 else { return }
        let offered = ProposedViewSize(width: Self.blockWidth(stage: bounds.width), height: nil)
        let face = subviews[0].sizeThatFits(.unspecified)
        let block = subviews[1].sizeThatFits(offered)
        let frames = Self.frames(stage: bounds, face: face, block: block, progress: progress)
        subviews[0].place(at: frames.face.origin, proposal: ProposedViewSize(frames.face.size))
        // The measured size, not the max width offered above. A wider proposal would let the
        // stack re-align its lines inside a box the geometry does not move, and redraw the text
        // on the way.
        subviews[1].place(at: frames.block.origin, proposal: ProposedViewSize(frames.block.size))
    }

    /// One width for the block in both places, so a long name never re-wraps on the way: what the
    /// corner leaves before our own picture, within the side margins, at most `cornerMaxWidth`.
    static func blockWidth(stage width: CGFloat) -> CGFloat {
        max(0, min(cornerMaxWidth, width - corner.x - InCallOverlay.selfViewReserve, width - margin * 2))
    }

    /// Where the face and the block are in `stage` (the space above the controls) at `progress`.
    /// Both ends sit on whole points, so text at rest is on the pixel grid.
    static func frames(stage: CGRect, face: CGSize, block: CGSize, progress: CGFloat) -> (face: CGRect, block: CGRect) {
        let under = underFace(stage: stage, face: face, block: block)
        let docked = inCorner(stage: stage, face: face, block: block)
        // The dock spring can run a little past 0 or 1. Past either end the block would leave
        // the corner, so the ends hold while it settles.
        let progress = min(1, max(0, progress))
        if progress == 0 { return under }
        if progress == 1 { return docked }
        // Up and across on the same fraction: one straight glide, not a slide out and a rise after.
        let blockOrigin = CGPoint(
            x: under.block.minX + (docked.block.minX - under.block.minX) * progress,
            y: under.block.minY + (docked.block.minY - under.block.minY) * progress
        )
        let faceOrigin = CGPoint(
            x: under.face.minX + (docked.face.minX - under.face.minX) * progress,
            y: under.face.minY + (docked.face.minY - under.face.minY) * progress
        )
        return (CGRect(origin: faceOrigin, size: face), CGRect(origin: blockOrigin, size: block))
    }

    /// Before the dock: the face in the middle of the stage, as it stays when docked, and the
    /// block under it. On a stage too short for both (landscape) the face goes up just far enough
    /// for the block to fit, never above the stage.
    private static func underFace(stage: CGRect, face: CGSize, block: CGSize) -> (face: CGRect, block: CGRect) {
        let centred = stage.midY - face.height / 2
        let fits = stage.maxY - (face.height + gap + block.height)
        let top = max(stage.minY, min(centred, fits))
        return (
            whole(CGPoint(x: stage.midX - face.width / 2, y: top), face),
            whole(CGPoint(x: stage.midX - block.width / 2, y: top + face.height + gap), block)
        )
    }

    /// Docked: the block in the corner, the face alone in the middle.
    private static func inCorner(stage: CGRect, face: CGSize, block: CGSize) -> (face: CGRect, block: CGRect) {
        let blockFrame = CGRect(origin: CGPoint(x: stage.minX + corner.x, y: stage.minY + corner.y), size: block)
        var faceOrigin = CGPoint(x: stage.midX - face.width / 2, y: stage.midY - face.height / 2)
        // A short stage (landscape) with a long name: the face moves out from under the block,
        // beside it where there is room, else below it.
        if CGRect(origin: faceOrigin, size: face).intersects(blockFrame) {
            if blockFrame.maxX + gap + face.width <= stage.maxX {
                faceOrigin.x = blockFrame.maxX + gap
            } else {
                faceOrigin.y = blockFrame.maxY + gap
            }
        }
        return (whole(faceOrigin, face), whole(blockFrame.origin, block))
    }

    private static func whole(_ origin: CGPoint, _ size: CGSize) -> CGRect {
        CGRect(origin: CGPoint(x: origin.x.rounded(), y: origin.y.rounded()), size: size)
    }
}

#Preview("Call stage") {
    // Tap to dock and undock.
    @Previewable @State var docked = false
    ZStack {
        Color(red: 0.08, green: 0.10, blue: 0.16).ignoresSafeArea()
        CallStageLayout(progress: docked ? 1 : 0) {
            AvatarView(initials: "JC", size: 104, fontSize: 36)
                .zIndex(1)
            VStack(alignment: .center, spacing: 6) {
                Text("Jane Cooper")
                    .font(.system(size: 26, weight: .semibold))
                    .foregroundStyle(.white)
                Text("0:42")
                    .font(.system(size: 15))
                    .monospacedDigit()
                    .foregroundStyle(.white.opacity(0.85))
            }
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
    }
    .onTapGesture { withAnimation(Motion.standard) { docked.toggle() } }
}
