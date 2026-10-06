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
/// whole screen, through the system's broadcast, started from a small Share capsule in the
/// top-trailing corner (its arrow picks the resolution and frame rate); a red pill at the top
/// says it is shared and stops it.
///
/// While our camera is on, a Center Stage circle sits beside Share: on, our camera goes out cut
/// around the faces in it, following them (docs/calls.md, "Framing and Center Stage"). The call
/// screen's size goes to the other side with every `media_state`, so their camera comes in cut
/// to the shape it fills here.
///
/// A contact whose safety number has not been compared gets an amber "Not verified" badge in the
/// top-leading corner, opposite Share. It closes down to a round shield after a few seconds and
/// opens the number in a popover; the docked name sits under it (`safetyBadge(for:number:)`).
struct InCallOverlay: View {
    @Environment(CallController.self) private var calls
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @Environment(\.displayScale) private var displayScale
    /// Where the face is on screen: their picture opens from it and closes back into it. A
    /// reference, so measuring the face never re-renders the call screen.
    @State private var face = FaceSpot()
    /// The call screen's size (points), as last measured: what shows their camera unless their
    /// screen does. A reference, so measuring it never re-renders the call screen.
    @State private var callScreen = CallScreenSpot()
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
    /// The call whose safety number is open in the popover from the "Not verified" badge. Tied to
    /// the call, so a popover still open when one call ends never opens by itself on the next.
    @State private var safetyShownFor: UUID?
    /// The call whose "Not verified" badge has closed down to its round shield.
    @State private var safetyBadgeClosedFor: UUID?
    /// The phase the call was last in, kept once it is gone. The screen fades out only from "Call
    /// ended"; straight from ringing (decline, cancel, sign-out) it goes at once, because the
    /// breathing avatar's repeating animation would keep a removal transition from finishing.
    @State private var lastPhase: CallController.Phase?
    /// The call screen has faded in and covers the app: only then does the window take the dark
    /// scheme, for a light status bar (`body`).
    @State private var windowDark = false

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
        ZStack {
            if let active = calls.active {
                content(for: active)
                    .transition(.opacity.combined(with: .scale(scale: reduceMotion ? 1 : 0.98)))
            }
        }
        // The call screen fades in over the app, and out after "Call ended" (see `lastPhase`).
        .animation(
            calls.active != nil || lastPhase == .ending ? Motion.respecting(reduceMotion, Motion.gentle) : nil,
            value: calls.active == nil
        )
        .onChange(of: calls.active?.phase) { _, phase in
            if let phase { lastPhase = phase }
        }
        // A light status bar over the dark screen. `preferredColorScheme` flips the whole window,
        // the app behind included, so only while the screen covers it: from the end of the fade
        // in (`darkenWindow`) until the call goes, when the app shows through the fade out light.
        .preferredColorScheme(calls.active != nil && windowDark ? .dark : nil)
        .onChange(of: calls.active == nil) { _, gone in
            if gone { windowDark = false }
        }
    }

    /// How long the screen takes to cover the app as it fades in (`Motion.gentle`, near enough).
    static let fadeInCover: Duration = .milliseconds(600)

    /// Once the call screen has faded in, the window goes dark under it. A call that follows on
    /// from an ending one finds it dark already.
    private func darkenWindow() async {
        guard !windowDark else { return }
        do {
            try await Task.sleep(for: Self.fadeInCover)
        } catch {
            return
        }
        if calls.active != nil { windowDark = true }
    }

    /// Tells the controller the area that shows their camera, in device pixels
    /// (`theirCameraArea`).
    private func reportVideoArea(theirScreen: Bool) {
        let area = Self.theirCameraArea(callScreen: callScreen.size, theirScreen: theirScreen, scale: displayScale)
        calls.setVideoArea(width: area.width, height: area.height)
    }

    /// The area that shows their camera, in device pixels: the whole call screen, or while their
    /// shared screen fills it, their camera's tile beside it.
    static func theirCameraArea(callScreen: CGSize, theirScreen: Bool, scale: CGFloat) -> (width: Int, height: Int) {
        let points = theirScreen ? tileSize : callScreen
        return (Int((points.width * scale).rounded()), Int((points.height * scale).rounded()))
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
        // The badge goes at once when the call ends; the name keeps its place for that moment.
        let badgeRoom = !call.safetyVerified && calls.safetyNumberForActiveCall() != nil
        let unverified = badgeRoom && !ending
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
            // The whole screen, where their picture fills: its size in device pixels tells them
            // the shape to cut their camera to; while their shared screen fills it, their camera's
            // tile does instead. Handed to the controller, never kept as state, so a rotation or a
            // resize re-renders nothing here.
            // Agent: the proxy's size stops at the safe area even here; its insets add the rest.
            .onGeometryChange(for: CGSize.self) { proxy in
                let insets = proxy.safeAreaInsets
                return CGSize(
                    width: proxy.size.width + insets.leading + insets.trailing,
                    height: proxy.size.height + insets.top + insets.bottom
                )
            } action: { [callScreen] size in
                callScreen.size = size
                reportVideoArea(theirScreen: screen)
            }
            .onChange(of: screen) { _, shown in
                reportVideoArea(theirScreen: shown)
            }

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

            // Down as far as the docked name goes: under the badge, and under the sharing pill.
            topShade(drop: (badgeRoom ? Self.safetyBadgeReserve : 0) + (sharing ? Self.sharingIndicatorInset : 0))
                .opacity((showsRemoteVideo || screen) && !chromeAway ? 1 : 0)
                .animation(.easeOut(duration: 0.3), value: showsRemoteVideo || screen)
                .animation(.easeOut(duration: 0.3), value: chromeAway)

            VStack(spacing: 28) {
                // The docked name sits under the "Not verified" badge while it shows.
                CallStageLayout(progress: docked ? 1 : 0, cornerDrop: badgeRoom ? Self.safetyBadgeReserve : 0) {
                    face(for: call)
                        // On top: a name too long to clear the face passes behind it.
                        .zIndex(1)
                    info(for: call)
                        .animation(Motion.snappy, value: call.phase)
                        .animation(Motion.snappy, value: call.isMuted)
                        .animation(Motion.snappy, value: call.remoteMicMuted)
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

            // In its own container: glass outside one leaves at once, whatever the transition says.
            GlassEffectContainer {
                if unverified, let number = calls.safetyNumberForActiveCall() {
                    safetyBadge(for: call, number: number)
                        .transition(.scale(scale: 0.6, anchor: .topLeading).combined(with: .opacity))
                }
            }
            .opacity(chromeAway ? 0 : 1)
            .animation(.easeOut(duration: 0.25), value: chromeAway)
            .allowsHitTesting(!chromeAway)
            .accessibilityHidden(chromeAway)
            .padding(.top, Self.selfViewInsets.top)
            .padding(.leading, Self.selfViewInsets.trailing)
            .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topLeading)
            .padding(.top, sharing ? Self.sharingIndicatorInset : 0)

            ZStack {
                if sharing {
                    sharingIndicator(starting: !call.isSharingScreen)
                        .padding(.top, 4)
                        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .top)
                        .transition(.move(edge: .top).combined(with: .opacity))
                }
            }
            // Ending, it goes at once: its dot's repeating pulse would keep a removal from
            // finishing, and the screen's own fade out after it (`lastPhase`).
            .animation(nil, value: ending)

            // The system's broadcast picker, out of sight; Share opens it.
            BroadcastPickerHost(trigger: broadcastPicker)
                .frame(width: 1, height: 1)
                .allowsHitTesting(false)
                .accessibilityHidden(true)
                .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .bottomLeading)
        }
        // Covers the whole app: VoiceOver stays on the call screen while it is up.
        .accessibilityElement(children: .contain)
        .accessibilityAddTraits(.isModal)
        .animation(Motion.standard, value: showsLocalVideo(call))
        .animation(Motion.standard, value: screen)
        .animation(Motion.snappy, value: sharing)
        // Compared: the badge goes and the docked name rises into its place.
        .animation(Motion.respecting(reduceMotion, Motion.standard), value: call.safetyVerified)
        // Dark inside the screen only, as the media viewers are: dark menus, popovers and glass
        // over the dark screen. The status bar follows the window (`body`).
        .environment(\.colorScheme, .dark)
        .task(id: call.id) { await darkenWindow() }
        .task(id: NamePlace(call: call.id, video: videoOn)) {
            await placeName(call.id, inCorner: videoOn)
        }
        // The number stays in reach while it is read out; the timer starts over once it closes.
        .onChange(of: safetyShownFor) { chromeTouch += 1 }
        // What the screen says in passing is read out too: a notice, reconnecting, how it ended.
        .onChange(of: call.notice) { _, notice in
            if let notice { AccessibilityNotification.Announcement(notice).post() }
        }
        .onChange(of: call.reconnecting) { _, on in
            if on, call.phase == .active { AccessibilityNotification.Announcement("Reconnecting").post() }
        }
        .onChange(of: call.phase) { _, phase in
            if phase == .ending { AccessibilityNotification.Announcement(statusLine(for: call)).post() }
        }
        .task(id: ChromeClock(screen: screen, hidden: chromeHidden, touch: chromeTouch)) {
            await lingerChrome(screen: screen)
        }
    }

    /// The top-trailing corner: Share (with Center Stage before it while our camera is on), then
    /// the pictures under it, theirs above ours while their screen fills the rest, ours alone
    /// otherwise. Tapping ours flips the camera. The row keeps its room while it steps aside over
    /// their screen, so the pictures never move for it; Center Stage comes and goes beside Share,
    /// never above the pictures, so they never move for it either.
    @ViewBuilder
    private func tiles(for call: CallController.ActiveCall, screen: Bool) -> some View {
        let size = screen ? Self.tileSize : Self.selfViewSize
        // Ending, it goes at once but keeps its room, as the controls do.
        let away = chromeAway || call.phase == .ending
        let framing = showsLocalVideo(call)
        VStack(alignment: .trailing, spacing: 10) {
            if call.phase != .incomingRinging || framing {
                // In a container of its own: glass outside one leaves at once, whatever the
                // transition says. No blending distance, so the two circles never fuse.
                GlassEffectContainer(spacing: 0) {
                    HStack(spacing: Self.controlRowSpacing) {
                        if framing {
                            centerStageControl()
                                .transition(.scale(scale: 0.6, anchor: .trailing).combined(with: .opacity))
                        }
                        if call.phase != .incomingRinging {
                            shareControl(for: call)
                                .transition(.opacity)
                        }
                    }
                }
                .opacity(away ? 0 : 1)
                .animation(.easeOut(duration: 0.25), value: away)
                .allowsHitTesting(!away)
                .accessibilityHidden(away)
                .transition(.opacity)
            }
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
                    // One element with the glyph: a button while the camera can be switched.
                    .accessibilityElement(children: .ignore)
                    .accessibilityLabel(calls.canSwitchCamera ? "Switch camera" : "Your camera")
                    .accessibilityAddTraits(calls.canSwitchCamera ? .isButton : .isImage)
                    .accessibilityAction { calls.switchCamera() }
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
                    // A finger-sized target around the small disc: 30 pt drawn, 44 pt to touch. The
                    // extra reaches past the pill's edge, so the pill stays small.
                    .padding(4)
                    .contentShape(Rectangle().inset(by: -7))
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
        let safetyShown = safetyShownFor != nil && safetyShownFor == calls.active?.id
        guard !chromeHidden, !safetyShown, !UIAccessibility.isVoiceOverRunning else { return }
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

    /// Share, in the top-trailing corner above the pictures: a small glass button that opens a
    /// menu. Share Screen (Stop Sharing while it runs) comes first, then the resolution and the
    /// frame rate our screen goes out at, as Discord offers them, kept on this phone; a change
    /// while sharing applies at once. It steps aside with the other controls over their screen.
    /// Dimmed when sharing cannot be used yet, but Share Screen still takes the tap, which says why.
    private func shareControl(for call: CallController.ActiveCall) -> some View {
        let sharing = call.isSharingScreen || call.screenShareStarting
        let available = call.canShareScreen || sharing
        let quality = calls.screenShareQuality
        // Toggles rather than inline pickers: a menu shows a section's title only over plain
        // items, and a toggle that is on gets the menu's own checkmark.
        return Menu {
            Section {
                if sharing {
                    Button("Stop Sharing", systemImage: "stop.fill", role: .destructive) { toggleShare() }
                } else {
                    Button("Share Screen", systemImage: "rectangle.inset.filled.on.rectangle") { toggleShare() }
                }
            }
            Section("Resolution") {
                ForEach(ScreenShareQuality.Resolution.allCases, id: \.self) { resolution in
                    Toggle(resolution.label, isOn: Binding(
                        get: { quality.resolution == resolution },
                        set: { if $0 { calls.setScreenShareQuality(ScreenShareQuality(resolution: resolution, frameRate: quality.frameRate)) } }
                    ))
                }
            }
            Section("Frame rate") {
                ForEach(ScreenShareQuality.FrameRate.allCases, id: \.self) { rate in
                    Toggle(rate.label, isOn: Binding(
                        get: { quality.frameRate == rate },
                        set: { if $0 { calls.setScreenShareQuality(ScreenShareQuality(resolution: quality.resolution, frameRate: rate)) } }
                    ))
                }
            }
        } label: {
            Image(systemName: "rectangle.inset.filled.on.rectangle")
                .font(.system(size: 15, weight: .semibold))
                .foregroundStyle(.white)
                .opacity(available ? 1 : 0.45)
                .frame(width: Self.shareControlSize, height: Self.shareControlSize)
                .contentShape(Circle())
                .glassEffect(sharing ? .regular.tint(Theme.accent).interactive() : .regular.interactive(), in: .circle)
        }
        .menuOrder(.fixed)
        // A menu that opens also keeps the controls up.
        .simultaneousGesture(TapGesture().onEnded { chromeTouch += 1 })
        .animation(Motion.snappy, value: sharing)
        .accessibilityLabel(sharing ? "Screen sharing" : "Share your screen")
        .accessibilityValue(sharing ? "On, \(quality.label)" : quality.label)
        .accessibilityHint(available ? "" : "Not available yet.")
    }

    static let shareControlSize: CGFloat = 40
    /// Between Center Stage and Share.
    static let controlRowSpacing: CGFloat = 10

    /// Center Stage, beside Share while our camera is on: a glass circle like it, tinted while
    /// on. On, our camera goes out cut around the faces in it and follows them; off, it goes out
    /// whole (in the shape they show it in). Kept on this phone. A button of its own beside the
    /// pictures, so a tap or a hold on it never reaches our picture's camera flip.
    private func centerStageControl() -> some View {
        let on = calls.centerStageEnabled
        return Button {
            // A control used over their screen keeps the controls up a while longer.
            chromeTouch += 1
            calls.setCenterStage(!on)
        } label: {
            Image(systemName: "person.crop.rectangle")
                .font(.system(size: 15, weight: .semibold))
                .foregroundStyle(.white)
                .frame(width: Self.shareControlSize, height: Self.shareControlSize)
                .contentShape(Circle())
                .glassEffect(on ? .regular.tint(Theme.accent).interactive() : .regular.interactive(), in: .circle)
        }
        .buttonStyle(PressableButtonStyle(scale: 1, dimming: 0, haptic: .medium))
        .animation(Motion.snappy, value: on)
        .accessibilityLabel("Center Stage")
        .accessibilityValue(on ? "On" : "Off")
        .accessibilityHint("Keeps your camera framed on you.")
    }

    /// Share Screen or Stop Sharing, chosen in the menu. The system's broadcast picker opens (the
    /// person starts the broadcast there) once the menu has gone: a sheet asked for while the
    /// menu is still closing does not show. Not at all when the call ended or moved on meanwhile.
    /// Stopping needs no picker.
    private func toggleShare() {
        guard let callID = calls.active?.id, calls.toggleScreenShare() else { return }
        Task {
            try? await Task.sleep(for: .milliseconds(350))
            guard let call = calls.active, call.id == callID, call.phase == .active || call.phase == .connecting else { return }
            broadcastPicker.open()
        }
    }

    /// Human: A safety number not compared yet: an amber "Not verified" badge in the top-leading
    /// corner, a round glass control like Share opposite it. It reads out in full for a moment,
    /// then closes down to the shield alone: the capsule narrows onto the shield, which never
    /// moves, while the words fade out ahead of its edge. A tap opens the number in a popover to
    /// read out on the call and mark as compared. It stays open while the popover is up, and
    /// closes a few seconds after it goes.
    /// Agent: WRITES safetyShownFor and safetyBadgeClosedFor, both keyed by call id so neither
    /// carries over to the next call.
    private func safetyBadge(for call: CallController.ActiveCall, number: String) -> some View {
        let shown = Binding(
            get: { safetyShownFor == call.id },
            set: { safetyShownFor = $0 ? call.id : nil }
        )
        let closed = safetyBadgeClosedFor == call.id
        let size = Self.shareControlSize
        return Button {
            shown.wrappedValue = true
        } label: {
            HStack(spacing: 0) {
                Image(systemName: "exclamationmark.shield.fill")
                    .font(.system(size: 16, weight: .semibold))
                    .frame(width: size, height: size)
                Text("Not verified")
                    .font(.system(size: 13, weight: .semibold))
                    .lineLimit(1)
                    .fixedSize()
                    .padding(.trailing, 14)
                    // Gone before the narrowing edge reaches the words, so they are never cut.
                    .opacity(closed ? 0 : 1)
                    .blur(radius: closed && !reduceMotion ? 3 : 0)
                    .animation(Motion.respecting(reduceMotion, .easeOut(duration: 0.2)), value: closed)
            }
            .foregroundStyle(Self.unverifiedTint)
            // Open, as wide as the words; closed, a circle round the shield. The shield keeps its
            // place, so only the trailing edge travels.
            .frame(width: closed ? size : nil, height: size, alignment: .leading)
            .clipShape(.capsule)
            .contentShape(.capsule)
            .glassEffect(.regular.tint(Self.unverifiedTint.opacity(0.12)).interactive(), in: .capsule)
            .animation(Motion.respecting(reduceMotion, Motion.gentle), value: closed)
        }
        .buttonStyle(.plain)
        .accessibilityLabel("Not verified")
        .accessibilityHint("Shows the safety number to compare with \(call.peerUsername).")
        .popover(isPresented: shown, arrowEdge: .top) {
            SafetyNumberPopover(name: call.peerUsername, number: number) {
                shown.wrappedValue = false
                calls.confirmSafety()
            }
            .presentationCompactAdaptation(.popover)
            // Dark whenever it opens, the window perhaps not yet: this reaches the popover only.
            .preferredColorScheme(.dark)
        }
        .task(id: SafetyBadgeClock(call: call.id, open: shown.wrappedValue)) {
            guard !shown.wrappedValue, safetyBadgeClosedFor != call.id else { return }
            do {
                try await Task.sleep(for: Self.safetyBadgeLinger)
            } catch {
                return
            }
            safetyBadgeClosedFor = call.id
        }
    }

    /// What the badge's timer follows: a new call, and its popover opening or closing.
    private struct SafetyBadgeClock: Equatable {
        let call: UUID
        let open: Bool
    }

    /// How long the badge reads "Not verified" before it closes down to the shield.
    static let safetyBadgeLinger: Duration = .seconds(3)
    /// What the badge takes at the top of the corner: the docked name block sits below it.
    static let safetyBadgeReserve: CGFloat = shareControlSize + 10

    /// The badge's amber, the web client's `#ffd9a8`: readable over any picture.
    static let unverifiedTint = Color(red: 1, green: 217 / 255, blue: 168 / 255)

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
            // Their picture hides the face and the muted badge on it, so it is said here instead.
            if call.remoteMicMuted, call.phase == .active, showsRemoteVideo || showsRemoteScreen {
                Label("\(call.peerUsername) is muted", systemImage: "mic.slash.fill")
                    .font(.system(size: 13, weight: .medium))
                    .foregroundStyle(.white.opacity(0.85))
                    .lineLimit(1)
                    .truncationMode(.middle)
                    .shadow(color: .black.opacity(0.5), radius: 3, y: 1)
                    .transition(.opacity)
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
                    // As bright as the status line: it sits lower in the shade still.
                    .foregroundStyle(.white.opacity(0.85))
                    .multilineTextAlignment(.center)
                    .shadow(color: .black.opacity(0.5), radius: 3, y: 1)
            }
        }
    }

    /// Human: Over their picture, a shade from the top edge to below the name block keeps white
    /// text readable on any frame, and the status bar with it: it holds its depth down past the
    /// clock (4.5:1 there even on a blown-out white wall) and then falls away. A plain gradient:
    /// fading it in and out with the picture blends one layer, nothing is redrawn.
    /// Agent: `drop` is how far the docked block sits lower than usual (the "Not verified" badge,
    /// the sharing pill). The hold and the fall move down by it, so the timer keeps its 4.5:1.
    /// The stops are tuned on a 59 pt safe-area top plus the 220 pt below it; at `drop` 0 they
    /// come out at the measured 0.42 and 0.7.
    private func topShade(drop: CGFloat) -> some View {
        let span: CGFloat = 279 + drop
        return Color.clear
            .frame(maxWidth: .infinity)
            .frame(height: 220 + drop)
            .background(
                LinearGradient(
                    stops: [
                        .init(color: .black.opacity(0.62), location: 0),
                        .init(color: .black.opacity(0.58), location: (117 + drop) / span),
                        .init(color: .black.opacity(0.3), location: (195 + drop) / span),
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
                // Breathing to still at once, never through a removal: the repeating animation
                // would keep it from finishing, and the screen's own fade out after it (`lastPhase`).
                .animation(nil, value: isRinging(call.phase))
                .overlay(alignment: .bottomTrailing) {
                    if call.remoteMicMuted {
                        Image(systemName: "mic.slash.fill")
                            .font(.system(size: 12, weight: .bold))
                            .foregroundStyle(.white)
                            .padding(6)
                            .background(Theme.danger)
                            .clipShape(Circle())
                            .offset(x: 4, y: 4)
                            .accessibilityLabel("\(call.peerUsername) is muted")
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
                    tint: call.speakerOn ? Theme.accent : nil,
                    accessibilityLabel: call.speakerOn ? "Turn speaker off" : "Turn speaker on"
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
    /// slow network; once connected it settles to a steady state. Under Reduce Motion it holds
    /// still, dimmed a little until connected; the status line says the call is on its way.
    @ViewBuilder
    private func avatar(for call: CallController.ActiveCall) -> some View {
        let base = AvatarView(
            initials: AvatarView.initials(for: call.peerUsername),
            size: 104,
            gradient: AvatarView.gradient(for: call.peerUsername),
            fontSize: 36
        )

        if isRinging(call.phase), !reduceMotion {
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
                    let time = elapsed(from: start, now: context.date)
                    Text(time)
                        .monospacedDigit()
                        .rollingDigits(value: time)
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

    /// "0:42", "12:05", then "1:02:03" past an hour, as the web client's clock reads.
    private func elapsed(from start: Date, now: Date) -> String {
        let seconds = max(0, Int(now.timeIntervalSince(start)))
        let h = seconds / 3600
        let m = (seconds % 3600) / 60
        let s = seconds % 60
        return h > 0 ? String(format: "%d:%02d:%02d", h, m, s) : String(format: "%d:%02d", m, s)
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
    /// How far below `corner` the docked block sits, clear of what is above it (the safety badge).
    var cornerDrop: CGFloat = 0

    var animatableData: AnimatablePair<CGFloat, CGFloat> {
        get { AnimatablePair(progress, cornerDrop) }
        set {
            progress = newValue.first
            cornerDrop = newValue.second
        }
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
        let frames = Self.frames(stage: bounds, face: face, block: block, progress: progress, cornerDrop: cornerDrop)
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
    static func frames(
        stage: CGRect,
        face: CGSize,
        block: CGSize,
        progress: CGFloat,
        cornerDrop: CGFloat = 0
    ) -> (face: CGRect, block: CGRect) {
        let under = underFace(stage: stage, face: face, block: block)
        let docked = inCorner(stage: stage, face: face, block: block, drop: cornerDrop)
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
    private static func inCorner(stage: CGRect, face: CGSize, block: CGSize, drop: CGFloat) -> (face: CGRect, block: CGRect) {
        let blockFrame = CGRect(origin: CGPoint(x: stage.minX + corner.x, y: stage.minY + corner.y + drop), size: block)
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

/// The safety number from the call screen's badge: twelve groups of five, four to a row so each is
/// easy to find again while reading them out, and a button once they match.
private struct SafetyNumberPopover: View {
    let name: String
    let number: String
    let confirm: () -> Void

    private var rows: [[String]] {
        let groups = number.split(separator: " ").map(String.init)
        return stride(from: 0, to: groups.count, by: 4).map { Array(groups[$0..<min($0 + 4, groups.count)]) }
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            Text("Safety number")
                .font(.system(size: 15, weight: .semibold))
            Text("Compare it with \(name): read it out on this call, or check it in person. If it matches, nobody else can listen in.")
                .font(.system(size: 13))
                .foregroundStyle(.secondary)
                .fixedSize(horizontal: false, vertical: true)
            Grid(horizontalSpacing: 14, verticalSpacing: 6) {
                ForEach(rows.indices, id: \.self) { row in
                    GridRow {
                        ForEach(rows[row].indices, id: \.self) { column in
                            Text(rows[row][column])
                        }
                    }
                }
            }
            .font(.system(size: 15, weight: .medium, design: .monospaced))
            .frame(maxWidth: .infinity)
            .padding(.vertical, 10)
            .background(.primary.opacity(0.06), in: RoundedRectangle(cornerRadius: 12, style: .continuous))
            .accessibilityElement(children: .ignore)
            .accessibilityLabel(number)
            Button(action: confirm) {
                Text("Mark as Verified")
                    .font(.system(size: 15, weight: .semibold))
                    .frame(maxWidth: .infinity)
                    .padding(.vertical, 4)
            }
            .buttonStyle(.borderedProminent)
            .buttonBorderShape(.capsule)
            .tint(Theme.accent)
        }
        .padding(16)
        .frame(width: 300)
    }
}

/// The call screen's size in points, as last measured. A reference, like `FaceSpot`: measuring it
/// never re-renders the call screen.
final class CallScreenSpot {
    var size: CGSize = .zero
}
