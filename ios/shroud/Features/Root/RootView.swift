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

    @State private var sessionController = SessionController()
    @State private var cryptoController = CryptoController()
    @State private var messagingController = MessagingController()
    @State private var callController = CallController()
    @State private var serverConfig = ServerConfigurationController()
    @State private var router = AppRouter()
    @State private var deviceWipe = DeviceWipeController()
    @State private var notifications = NotificationsController.shared
    /// Set when the scene leaves `.active` with the chats open. See `showsPrivacyCover`.
    @State private var privacyCoverArmed = false
    /// The screen is being recorded, mirrored or shared (`UITraitCollection.sceneCaptureState`).
    @State private var isScreenCaptured = false
    /// When the app went to the background with the chats unlocked and a later auto-lock due.
    @State private var leftForBackgroundAt: Date?
    @Namespace private var onboardingNamespace

    var body: some View {
        ZStack {
            // Unlock reveal: Chats rises in from 0.96 while the lock screen dissolves over it.
            // The lock screen may have built the shell already (hidden, see
            // `AppRouter.prewarmMainShell`); the reveal then animates that same view in.
            Group {
                if router.mountsMainShell {
                    MainTabView(router: router)
                        .opacity(router.isUnlocked ? 1 : 0)
                        .scaleEffect(router.isUnlocked || reduceMotion ? 1 : 0.96)
                        .allowsHitTesting(router.isUnlocked)
                        .accessibilityHidden(!router.isUnlocked)
                        .onAppear { router.mainShellMounted = true }
                        .onDisappear { router.mainShellMounted = false }
                        .transition(.asymmetric(
                            insertion: .scale(scale: 0.96).combined(with: .opacity),
                            removal: .opacity
                        ))
                }
                if !router.isUnlocked {
                    onboardingStack
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
            }

            InCallOverlay()
                .zIndex(100)
                .allowsHitTesting(callController.active != nil)

            // The app switcher snapshots the screen as the app leaves; this covers the chats
            // first, so the snapshot on disk shows the mark, not a conversation.
            if showsPrivacyCover {
                AppSwitcherPrivacyCover()
                    .zIndex(150)
            }

            // Human: Above everything, calls included — the switch to Welcome happens under it.
            if deviceWipe.isPresented {
                DeviceWipeOverlay()
                    .transition(.opacity)
                    .zIndex(200)
            }
        }
        .background {
            SceneCaptureStateReader { captured in
                if isScreenCaptured != captured { isScreenCaptured = captured }
            }
        }
        .environment(\.onboardingNamespace, onboardingNamespace)
        .environment(sessionController)
        .environment(cryptoController)
        .environment(messagingController)
        .environment(callController)
        .environment(serverConfig)
        .environment(deviceWipe)
        .environment(notifications)
        .task {
            SensitiveTempFiles.prepareAtLaunch()
            SecurityPreferences.removeRetiredKeys()
            // Wire before any network call so 401s during validateSession count toward force-logout.
            SessionAuthBridge.controller = sessionController
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
            deviceWipe.start(reason: .sessionEnded)
        }
        .onChange(of: sessionController.isSignedIn) { _, signedIn in
            notifications.isSignedIn = signedIn
            if !signedIn {
                // Repeated HTTP 401s: the server no longer accepts this session, so the iPhone is
                // cleared exactly like Log Out does it — overlay, every store, verified.
                let fullWipe = sessionController.consumePendingFullLocalWipe()
                router.hasUnlockedMessaging = false
                if fullWipe {
                    if !deviceWipe.isPresented { deviceWipe.start(reason: .sessionEnded) }
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
            if unlocked {
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
                guard sessionController.isSignedIn else { return }
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
        if isScreenCaptured, SecurityPreferences.hidesDuringScreenCapture { return true }
        return privacyCoverArmed && scenePhase != .active
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
        .accessibilityHidden(true)
    }
}
