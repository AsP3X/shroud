import SwiftUI

/// One `beginBackgroundTask` balanced with a single `end`, from the work or from the expiry.
private final class AwayTask: @unchecked Sendable {
    private let lock = NSLock()
    private var id: UIBackgroundTaskIdentifier = .invalid

    func begin(_ application: UIApplication) {
        let started = application.beginBackgroundTask(withName: "shroud.away") { [weak self] in
            self?.end(application)
        }
        lock.lock()
        id = started
        lock.unlock()
    }

    func end(_ application: UIApplication) {
        lock.lock()
        let current = id
        id = .invalid
        lock.unlock()
        guard current != .invalid else { return }
        application.endBackgroundTask(current)
    }
}

/// Root navigation shell — routes between onboarding and the main tab shell.
///
/// Onboarding and Main **must not** share one `NavigationStack`: nested stacks under a typed
/// path (e.g. `AppRoute` + `ChatRoute` / `SettingsRoute`) crash with
/// `AnyNavigationPath.Error.comparisonTypeMismatch`.
struct RootView: View {
    @Environment(\.scenePhase) private var scenePhase
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @Environment(\.openURL) private var openURL

    @State private var sessionController = SessionController()
    @State private var cryptoController = CryptoController()
    @State private var messagingController = MessagingController()
    @State private var callController = CallController()
    @State private var serverConfig = ServerConfigurationController()
    @State private var router = AppRouter()
    @State private var deviceWipe = DeviceWipeController()
    @State private var notifications = NotificationsController.shared
    @State private var colorTheme = ColorThemePreference.shared
    @State private var clientVersion = ClientVersionController()
    @State private var updateToast: Toast?
    /// Reads what is presented over the root: the update alert waits for it, the required screen
    /// clears it.
    @State private var rootPresentation = RootPresentation()
    /// The "Update available" alert may show. Set only while nothing is presented over the root
    /// (presenting over a child's sheet or cover dismisses it); kept while the alert itself is up.
    @State private var updateAlertArmed = false
    /// "Check again" is running; keeps "Checking…" up for at least `minimumVersionCheck`.
    @State private var isRecheckingVersion = false
    /// The scene went to the background; the next `.active` is a return worth an update check.
    /// Face ID's sheet only makes the scene inactive, so unlocking never counts as one.
    @State private var returnsFromBackground = false
    /// Set when the scene leaves `.active` with the chats open. See `showsPrivacyCover`.
    @State private var privacyCoverArmed = false
    /// The screen is being recorded, mirrored or shared (`UITraitCollection.sceneCaptureState`).
    @State private var isScreenCaptured = false
    /// When the app went to the background with the chats unlocked and a later auto-lock due.
    @State private var leftForBackgroundAt: Date?
    @Namespace private var onboardingNamespace

    var body: some View {
        ZStack {
            // Unlock reveal: Chats fades in while the lock screen dissolves over it; its content
            // rises in from 0.96 (inside `MainTabView`, so the tab bar stays put at the bottom).
            // The lock screen may have built the shell already (hidden, see
            // `AppRouter.prewarmMainShell`); the reveal then animates that same view in.
            Group {
                if router.mountsMainShell {
                    MainTabView(router: router, isRevealed: router.isUnlocked)
                        .opacity(router.isUnlocked ? 1 : 0)
                        .allowsHitTesting(router.isUnlocked)
                        // Also out of VoiceOver's reach under the call screen and under the
                        // screen-capture cover: both hide the chats only visually.
                        .accessibilityHidden(
                            !router.isUnlocked || coversForScreenCapture || callController.active != nil
                        )
                        .onAppear { router.mainShellMounted = true }
                        .onDisappear { router.mainShellMounted = false }
                        // No scale: it would carry the tab bar in from 0.96 too.
                        .transition(.opacity)
                }
                if !router.isUnlocked {
                    onboardingStack
                        // A call answered while the chats are locked covers the lock screen.
                        .accessibilityHidden(callController.active != nil)
                        .transition(.opacity)
                        .zIndex(1)
                }
            }
            .animation(Motion.respecting(reduceMotion, Motion.gentle), value: router.isUnlocked)

            // Telegram's in-app banner for arrivals in other chats (unlocked only). Under the
            // call screen: a tap there would open a chat hidden behind the call.
            if router.isUnlocked {
                InAppNotificationHost()
                    .zIndex(90)
                    .allowsHitTesting(notifications.banner != nil)
                    .accessibilityHidden(coversForScreenCapture || callController.active != nil)
            }

            InCallOverlay()
                .zIndex(callAboveCaptureCover ? 160 : 100)
                .allowsHitTesting(callController.active != nil)

            // The app switcher snapshots the screen as the app leaves; this covers the chats
            // first, so the snapshot on disk shows the mark, not a conversation.
            if showsPrivacyCover {
                AppSwitcherPrivacyCover()
                    // Under a call's screen VoiceOver stays on the call.
                    .accessibilityHidden(callAboveCaptureCover && callController.active != nil)
                    .zIndex(150)
            }

            // The server no longer serves this build: over the lock screen and the chats, under
            // a running wipe. A call is never covered; the screen waits until it ends.
            if showsUpdateRequired, case let .required(latest, url) = clientVersion.prompt {
                UpdateRequiredView(
                    latestVersion: latest,
                    updateURL: url,
                    currentVersion: clientVersion.currentVersion,
                    isChecking: clientVersion.isChecking || isRecheckingVersion,
                    toast: $updateToast,
                    onUpdate: { if let url { openURL(url) } },
                    onCheckAgain: checkVersionAgain
                )
                // A toast left from this time must not greet the next one.
                .onDisappear { updateToast = nil }
                .transition(.opacity)
                .zIndex(180)
            }

            // Human: Above everything, calls included — the switch to Welcome happens under it.
            if deviceWipe.isPresented {
                DeviceWipeOverlay()
                    .transition(.opacity)
                    .zIndex(200)
            }
        }
        .animation(Motion.respecting(reduceMotion, Motion.gentle), value: showsUpdateRequired)
        .alert(
            "Update available",
            isPresented: Binding(get: { showsUpdateAlert }, set: { _ in }),
            presenting: availableUpdate
        ) { update in
            // Every button dismisses for this process; a newer version or a cold launch asks again.
            if let url = update.url {
                Button("Update") {
                    clientVersion.dismissAvailable()
                    openURL(url)
                }
                Button("Later", role: .cancel) { clientVersion.dismissAvailable() }
            } else {
                Button("OK", role: .cancel) { clientVersion.dismissAvailable() }
            }
        } message: { update in
            Text(ClientVersionPolicy.availableMessage(latest: update.latest, current: clientVersion.currentVersion))
        }
        .background { RootPresentationProbe(presentation: rootPresentation) }
        .background {
            SceneCaptureStateReader { captured in
                if isScreenCaptured != captured { isScreenCaptured = captured }
            }
        }
        // Light or dark from Settings › Appearance, on the window itself: see ColorThemePreference.
        .background { WindowColorTheme(theme: colorTheme.theme) }
        .environment(\.onboardingNamespace, onboardingNamespace)
        .environment(sessionController)
        .environment(cryptoController)
        .environment(messagingController)
        .environment(callController)
        .environment(serverConfig)
        .environment(deviceWipe)
        .environment(notifications)
        // Settings › About Shroud reads the same answer and runs its own manual checks.
        .environment(clientVersion)
        // Its own task: the answer shouldn't wait for the session checks below.
        .task {
            await clientVersion.check(.launch, configuration: serverConfig.configuration)
        }
        .onChange(of: serverConfig.configuration) { _, configuration in
            Task { await clientVersion.check(.serverChanged, configuration: configuration) }
        }
        // An offer waiting behind a sheet, a call or a wipe: look again until the screen is free.
        // UIKit sends nothing when a child's sheet closes, so this polls, and only while waiting.
        .task(id: updateAlertWaiting) {
            while updateAlertWaiting, !Task.isCancelled {
                armUpdateAlertIfClear()
                guard !updateAlertArmed else { return }
                do { try await Task.sleep(for: Self.updateAlertRecheck) } catch { return }
            }
        }
        // Gone (dismissed, or no longer offered), or a call or wipe took the screen: ask the
        // screen again before the next showing.
        .onChange(of: updateAlertMayStayArmed) { _, mayStay in
            if !mayStay { updateAlertArmed = false }
        }
        // While it blocks the app nothing may sit over it or type behind it: the keyboard goes,
        // and sheets, covers and dialogs are dismissed, also any that open later.
        .task(id: showsUpdateRequired) {
            guard showsUpdateRequired else { return }
            AccessibilityNotification.ScreenChanged(nil).post()
            while !Task.isCancelled {
                rootPresentation.clearForBlockingScreen()
                do { try await Task.sleep(for: Self.updateRequiredSweep) } catch { return }
            }
        }
        .task {
            SensitiveTempFiles.prepareAtLaunch()
            // Shared-file blobs a kill left half-sealed (ciphertext, but never finished).
            LocalFileStore().sweepStaging()
            SecurityPreferences.removeRetiredKeys()
            // Wire before any network call so 401s during validateSession count toward force-logout.
            SessionAuthBridge.controller = sessionController
            SessionAuthBridge.deviceWipe = deviceWipe
            // …and a 426 (this build is below the server's minimum) brings up "Update required".
            ClientVersionBridge.controller = clientVersion
            ClientVersionBridge.serverConfig = serverConfig
            router.sessionController = sessionController
            router.cryptoController = cryptoController
            router.messagingController = messagingController
            router.callController = callController
            router.deviceWipe = deviceWipe
            deviceWipe.session = sessionController
            deviceWipe.messaging = messagingController
            deviceWipe.crypto = cryptoController
            deviceWipe.calls = callController
            deviceWipe.router = router
            messagingController.bind(
                session: sessionController,
                crypto: cryptoController,
                calls: callController
            )
            callController.bind(session: sessionController, messaging: messagingController)
            PushNotificationService.shared.bind(session: sessionController, calls: callController)
            // A logout the app was killed in the middle of is finished before anything reads the
            // session; so is whatever an older version's logout left behind.
            if await deviceWipe.finishInterruptedWipeIfNeeded() {
                router.postAuthToast = "Signed out · this \(UIDevice.current.model) was cleared"
            }
            SessionStore().dropLegacyDeviceName()
            await sessionController.validateSessionIfNeeded()
            // Session without local identity (app data wipe / incomplete login) → Sign Up / Log In.
            if sessionController.isSignedIn {
                let clearedOrphan = await router.reconcileOrphanedSessionIfNeeded()
                if !clearedOrphan {
                    await router.restoreUnlockedSessionIfNeeded()
                }
            } else {
                router.hasUnlockedMessaging = false
            }
            notifications.isUnlocked = router.isUnlocked
            notifications.isSignedIn = sessionController.isSignedIn
            // A tap that launched a signed-out app belongs to no one here.
            if !sessionController.isSignedIn { notifications.pendingOpen = nil }
            // Pushes, including a call, have to reach a signed-in phone that is still on the
            // lock screen. The chats themselves wait for unlock.
            if sessionController.isSignedIn {
                PushNotificationService.shared.start()
            }
            if router.isUnlocked {
                messagingController.start()
                syncDeviceName()
            }
        }
        // While signed in but messaging is locked, messaging polls are stopped — so re-probe
        // `/auth/me` here. Repeated 401s still force-logout + full wipe; offline never counts.
        .task(id: lockScreenSessionProbeActive) {
            guard lockScreenSessionProbeActive else { return }
            await runLockScreenSessionValidationLoop()
        }
        .onChange(of: sessionController.pendingFullLocalWipe) { _, pending in
            // Repeated 401s set the flag and leave the token in place. The wipe revokes it.
            // Consume the flag now: the wipe's own logout would otherwise see it and start
            // a second wipe after this one has already finished.
            guard pending, !deviceWipe.isPresented else { return }
            _ = sessionController.consumePendingFullLocalWipe()
            deviceWipe.start(reason: sessionController.sessionEndedByDeviceRemoval ? .removed : .sessionEnded)
        }
        // A server picked on the lock screen takes effect once its Log Out is over: the wipe
        // revoked the session on the old server, and nothing of it is left for the new one.
        .onChange(of: deviceWipe.isPresented) { _, presented in
            guard !presented, let next = router.pendingServerConfiguration else { return }
            router.pendingServerConfiguration = nil
            try? serverConfig.save(next)
        }
        .onChange(of: sessionController.isSignedIn) { _, signedIn in
            notifications.isSignedIn = signedIn
            if !signedIn {
                // Repeated HTTP 401s: the server no longer accepts this session, so the iPhone is
                // cleared exactly like Log Out does it — overlay, every store, verified.
                let fullWipe = sessionController.consumePendingFullLocalWipe()
                router.hasUnlockedMessaging = false
                if fullWipe {
                    if !deviceWipe.isPresented {
                        deviceWipe.start(reason: sessionController.sessionEndedByDeviceRemoval ? .removed : .sessionEnded)
                    }
                    return
                }
                cryptoController.lock(wipeStore: false)
                // The logout wipe already stopped messaging; this covers every other sign-out.
                messagingController.stop(wipeDisk: true)
                callController.clearLocalState()
                PushNotificationService.shared.stop()
            }
        }
        .onChange(of: router.isUnlocked) { _, unlocked in
            notifications.isUnlocked = unlocked
            if !unlocked { notifications.dismissBanner() }
            if unlocked, !deviceWipe.isPresented {
                // Drop any leftover onboarding path before the main shell appears.
                router.path = []
                messagingController.start()
                PushNotificationService.shared.start()
                syncDeviceName()
            } else if sessionController.isSignedIn {
                // Keep the 90-day local cache + Notes when only messaging is locked.
                messagingController.stop(wipeDisk: false)
                PushNotificationService.shared.stop()
            } else {
                messagingController.stop(wipeDisk: true)
                PushNotificationService.shared.stop()
            }
        }
        // The call screen is modal for VoiceOver: move focus onto it when a call appears.
        .onChange(of: callController.active?.id) { old, new in
            if new != nil, old == nil {
                AccessibilityNotification.ScreenChanged(nil).post()
            }
        }
        // A call that ends while the app is in the background: nothing needs the socket now,
        // and closing it lets the server push again (see `.background` below).
        .onChange(of: callController.active == nil) { _, ended in
            guard ended else { return }
            if scenePhase == .active, !cryptoController.isUnlocked {
                router.hasUnlockedMessaging = false
            }
            guard scenePhase == .background, router.isUnlocked else { return }
            stepAway()
        }
        .onChange(of: scenePhase) { _, phase in
            privacyCoverArmed = phase != .active && router.isUnlocked
            switch phase {
            case .background:
                returnsFromBackground = true
                // Tell the server this iPhone is away, and close the socket, before iOS
                // suspends the process. A call keeps the socket for signaling.
                if router.isUnlocked {
                    stepAway()
                }
                notifications.dismissBanner()
                // Drop plaintext history from RAM; sealed files stay on disk.
                // Vault remains; re-open via biometry/passcode when returning.
                // A later auto-lock is checked on the way back (`lockIfAutoLockDue`): a suspended
                // app runs no timers.
                if router.isUnlocked || cryptoController.isUnlocked {
                    switch SecurityPreferences.autoLockDelay {
                    case .immediately: lockChatsInMemory()
                    case .never: leftForBackgroundAt = nil
                    default: leftForBackgroundAt = Date()
                    }
                }
            case .active:
                lockIfAutoLockDue()
                Task { await notifications.refreshAuthorization() }
                if returnsFromBackground {
                    returnsFromBackground = false
                    Task { await clientVersion.check(.foreground, configuration: serverConfig.configuration) }
                }
                // A waiting update offer needn't sit out the rest of its poll interval.
                if updateAlertWaiting { armUpdateAlertIfClear() }
                // A wipe (one a removal's push started in the background) owns the stores:
                // nothing may reconnect or refill them under it.
                guard sessionController.isSignedIn, !deviceWipe.isPresented else { return }
                // Face ID is opt-in via the lock screen's unlock button — never auto-prompt here
                // (auto-prompt raced with Welcome and left the system sheet stuck).
                if !cryptoController.isUnlocked {
                    // Answering on the lock screen brings the scene forward. Dropping into the
                    // chat lock then stops messaging and, while the device is still locked,
                    // used to sign the phone out. The lock screen waits until the call is over.
                    if !callController.isInCall {
                        router.hasUnlockedMessaging = false
                        // Immediate probe when returning to the lock screen (don't wait for loop sleep).
                        Task { await sessionController.validateSessionIfNeeded() }
                    }
                } else if router.isUnlocked {
                    messagingController.handleAppBecameActive()
                }
            case .inactive:
                // Coming back goes background → inactive → active; locking here happens while
                // the privacy cover still hides the chats.
                lockIfAutoLockDue()
            @unknown default:
                break
            }
        }
    }

    /// Seals this iPhone's name for the device list; needs the history key, so only unlocked.
    private func syncDeviceName() {
        guard let session = sessionController.session,
              let historyKey = cryptoController.material?.historyKey
        else { return }
        Task { await DeviceNameSync.syncIfNeeded(session: session, historyKey: historyKey) }
    }

    private func lockChatsInMemory() {
        leftForBackgroundAt = nil
        messagingController.lockSensitiveMemory()
        cryptoController.lockHistoryInMemory()
        SensitiveTempFiles.sweep(olderThan: Self.staleTempFileAge)
    }

    /// Back from the background: locks when the chosen auto-lock delay has passed meanwhile.
    /// The `.active` handler then routes to the lock screen as for an immediate lock.
    private func lockIfAutoLockDue() {
        guard let leftAt = leftForBackgroundAt else { return }
        leftForBackgroundAt = nil
        if SecurityPreferences.autoLockDelay.isDue(leftAt: leftAt, now: Date()) {
            lockChatsInMemory()
        }
    }

    /// Leaves the foreground with enough time for the "away" frame to leave the device.
    /// A suspended app otherwise keeps its socket, and the server sends nothing.
    private func stepAway() {
        guard router.isUnlocked else { return }
        let keepSocket = callController.active != nil
        let messaging = messagingController
        let application = UIApplication.shared
        let task = AwayTask()
        task.begin(application)
        Task { @MainActor in
            await messaging.leaveForeground(keepSocket: keepSocket)
            task.end(application)
        }
    }

    /// Cover the main shell while the scene is not active — but only if it was already showing
    /// when the scene left `.active`. Not the lock screen: Face ID's sheet makes the scene
    /// inactive, and the lock screen's choreography plays under it.
    ///
    /// Human: On a device the Face ID indicator is still up when the unlock reveal hands over
    /// to Chats, so the scene is still inactive. Keyed on `isUnlocked` alone, the cover faded in
    /// over the reveal — a second mark on a dark screen — and vanished once Face ID let go.
    private var showsPrivacyCover: Bool {
        guard router.isUnlocked else { return false }
        if coversForScreenCapture { return true }
        return privacyCoverArmed && scenePhase != .active
    }

    /// The chats are recorded, mirrored or shared and "Hide chats during screen recording" is on.
    ///
    /// Human: Also during a call, under the call screen (`callAboveCaptureCover`).
    private var coversForScreenCapture: Bool {
        router.isUnlocked
            && isScreenCaptured
            && SecurityPreferences.hidesDuringScreenCapture
    }

    /// The call screen goes over the screen-capture cover, which stays up under it.
    ///
    /// Human: Sharing your screen in a call must show the call, not the mark. Dropping the
    /// cover for the call instead let the chats show through the call screen while it faded in.
    /// The app switcher's cover still goes over a call.
    private var callAboveCaptureCover: Bool {
        coversForScreenCapture && !(privacyCoverArmed && scenePhase != .active)
    }

    /// "Update available" from the server's last answer, unless dismissed for this version.
    private var availableUpdate: AvailableUpdate? {
        guard case let .available(latest, url) = clientVersion.prompt else { return nil }
        return AvailableUpdate(latest: latest, url: url)
    }

    /// The required screen, unless a call is on: it waits for the call to end.
    private var showsUpdateRequired: Bool {
        clientVersion.isUpdateRequired && callController.active == nil
    }

    private var showsUpdateAlert: Bool {
        updateAlertArmed && updateAlertMayStayArmed
    }

    /// An offer is there and nothing else owns the screen: no call, no wipe.
    private var updateAlertMayStayArmed: Bool {
        availableUpdate != nil
            && callController.active == nil
            && !deviceWipe.isPresented
    }

    /// An offer is waiting for the screen to be free.
    private var updateAlertWaiting: Bool {
        availableUpdate != nil && !updateAlertArmed
    }

    /// Arms the alert when the app is in front and nothing is presented over the root.
    ///
    /// Human: Never checked while the alert is up — it is itself presented over the root. Asks
    /// the window scene, not `scenePhase`: this runs in a task, where the environment is stale.
    private func armUpdateAlertIfClear() {
        guard !updateAlertArmed,
              updateAlertMayStayArmed,
              rootPresentation.isInForeground,
              !rootPresentation.isPresentingOverRoot
        else { return }
        updateAlertArmed = true
    }

    /// "Check again" on `UpdateRequiredView`. Says so when nothing changed, so the tap is seen.
    private func checkVersionAgain() {
        guard !isRecheckingVersion else { return }
        isRecheckingVersion = true
        Task {
            defer { isRecheckingVersion = false }
            let started = ContinuousClock.now
            let outcome = await clientVersion.check(.manual, configuration: serverConfig.configuration)
            // "Checking…" stays long enough to be read; a local server answers in milliseconds.
            try? await Task.sleep(until: started + Self.minimumVersionCheck)
            switch outcome {
            case .answered(.updateRequired):
                Haptics.notification(.warning)
                updateToast = .info("This server still needs a newer version")
            case .failed:
                Haptics.notification(.error)
                updateToast = .failure("Couldn’t reach the server")
            case .answered, .skipped:
                break
            }
        }
    }

    private static let minimumVersionCheck: Duration = .milliseconds(600)
    /// How often a waiting update alert looks for a free screen.
    private static let updateAlertRecheck: Duration = .milliseconds(1500)
    /// How often the required screen clears what was presented over it since.
    private static let updateRequiredSweep: Duration = .seconds(1)

    private struct AvailableUpdate {
        let latest: String?
        let url: URL?
    }

    /// Temp files untouched this long are no playback or recording in progress: locking clears them.
    private static let staleTempFileAge: TimeInterval = 10 * 60

    /// True when a server session exists but the main shell is not shown (lock / Welcome).
    /// Drives the lock-screen auth probe task — messaging is stopped so it would not see 401s.
    private var lockScreenSessionProbeActive: Bool {
        sessionController.isSignedIn && !router.isUnlocked
    }

    /// Interval between `/auth/me` probes on the lock screen.
    /// Short enough that three consecutive 401s force-logout within ~12s; long enough offline.
    private static let lockScreenSessionProbeInterval: Duration = .seconds(4)

    /// Signed in with a local identity while the main shell is not shown → the lock screen.
    ///
    /// Human: Deliberately not keyed on the vault: it opens a beat before the unlock
    /// choreography hands over to the main shell, and keying on it flashed Welcome in between.
    /// Signed-in-without-identity (data wipe) is reconciled to Welcome instead.
    private var needsChatUnlock: Bool {
        guard sessionController.isSignedIn, let userID = sessionController.userID else { return false }
        return cryptoController.hasLocalIdentity(for: userID)
    }

    /// Pre-auth flow only — path elements are always `AppRoute`.
    private var onboardingStack: some View {
        NavigationStack(path: $router.path) {
            Group {
                if needsChatUnlock {
                    LockScreenView(router: router)
                } else {
                    WelcomeView(router: router)
                }
            }
            .animation(Motion.respecting(reduceMotion, Motion.fade), value: needsChatUnlock)
                .navigationDestination(for: AppRoute.self) { route in
                    switch route {
                    case .welcome:
                        WelcomeView(router: router)
                    case .signUp:
                        SignUpView(router: router)
                    case .logIn:
                        LogInFlowView(router: router)
                    }
                }
        }
    }

    /// Keeps validating the session while the user sits on the locked Welcome UI.
    ///
    /// Each probe goes through `APIClient` → `SessionAuthBridge`, so real 401s accumulate toward
    /// force-logout; transport/offline errors never do.
    private func runLockScreenSessionValidationLoop() async {
        while !Task.isCancelled {
            guard sessionController.isSignedIn, !router.isUnlocked else { return }
            await sessionController.validateSessionIfNeeded()
            guard sessionController.isSignedIn else { return }
            do {
                try await Task.sleep(for: Self.lockScreenSessionProbeInterval)
            } catch {
                return
            }
        }
    }
}

#Preview {
    RootView()
}

/// Reports the window scene's `sceneCaptureState`: recording, mirroring or sharing the screen.
///
/// Human: SwiftUI has no environment value for it; a view inherits the scene's traits, so an
/// invisible one reports them.
private struct SceneCaptureStateReader: UIViewRepresentable {
    let onChange: (Bool) -> Void

    func makeUIView(context: Context) -> CaptureStateView {
        let view = CaptureStateView()
        view.onChange = onChange
        view.isUserInteractionEnabled = false
        return view
    }

    func updateUIView(_ view: CaptureStateView, context: Context) {
        view.onChange = onChange
    }

    final class CaptureStateView: UIView {
        var onChange: ((Bool) -> Void)?

        override init(frame: CGRect) {
            super.init(frame: frame)
            registerForTraitChanges([UITraitSceneCaptureState.self]) { (view: CaptureStateView, _) in
                view.report()
            }
        }

        @available(*, unavailable)
        required init?(coder: NSCoder) { fatalError("init(coder:) is not used") }

        override func didMoveToWindow() {
            super.didMoveToWindow()
            report()
        }

        private func report() {
            onChange?(traitCollection.sceneCaptureState == .active)
        }
    }
}

/// What the app switcher shows instead of an open chat — and what a screen recording shows.
private struct AppSwitcherPrivacyCover: View {
    var body: some View {
        ZStack {
            Theme.background.ignoresSafeArea()
            BrandLogoMark(size: 72)
        }
        // Only reachable during a screen capture (the chats under it are hidden from VoiceOver
        // then); in the app switcher nothing is being read.
        .accessibilityElement(children: .ignore)
        .accessibilityLabel("Chats are hidden while the screen is recorded or mirrored")
    }
}
