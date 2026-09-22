import SwiftUI

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
    @Namespace private var onboardingNamespace

    var body: some View {
        ZStack {
            // Unlock reveal: Chats rises in from 0.96 while the lock screen dissolves over it.
            Group {
                if router.isUnlocked {
                    MainTabView(router: router)
                        .transition(.asymmetric(
                            insertion: .scale(scale: 0.96).combined(with: .opacity),
                            removal: .opacity
                        ))
                } else {
                    onboardingStack
                        .transition(.opacity)
                        .zIndex(1)
                }
            }
            .animation(Motion.respecting(reduceMotion, Motion.gentle), value: router.isUnlocked)

            InCallOverlay()
                .zIndex(100)
                .allowsHitTesting(callController.active != nil)

            // Human: Above everything, calls included — the switch to Welcome happens under it.
            if deviceWipe.isPresented {
                DeviceWipeOverlay()
                    .transition(.opacity)
                    .zIndex(200)
            }
        }
        .environment(\.onboardingNamespace, onboardingNamespace)
        .environment(sessionController)
        .environment(cryptoController)
        .environment(messagingController)
        .environment(callController)
        .environment(serverConfig)
        .environment(deviceWipe)
        .task {
            SecurityPreferences.applyToVault()
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
            if router.isUnlocked {
                messagingController.start()
                PushNotificationService.shared.start()
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
            if unlocked {
                // Drop any leftover onboarding path before the main shell appears.
                router.path = []
                messagingController.start()
                PushNotificationService.shared.start()
            } else if sessionController.isSignedIn {
                // Keep the 90-day local cache + Notes when only messaging is locked.
                messagingController.stop(wipeDisk: false)
                PushNotificationService.shared.stop()
            } else {
                messagingController.stop(wipeDisk: true)
                PushNotificationService.shared.stop()
            }
        }
        .onChange(of: scenePhase) { _, phase in
            switch phase {
            case .background:
                // Drop plaintext history from RAM; sealed files stay on disk.
                // Vault remains; re-open via biometry/passcode when returning.
                if SecurityPreferences.lockChatsOnBackground,
                   (router.isUnlocked || cryptoController.isUnlocked)
                {
                    messagingController.lockSensitiveMemory()
                    cryptoController.lockHistoryInMemory()
                }
            case .active:
                guard sessionController.isSignedIn else { return }
                // Face ID is opt-in via the lock screen's unlock button — never auto-prompt here
                // (auto-prompt raced with Welcome and left the system sheet stuck).
                if !cryptoController.isUnlocked {
                    router.hasUnlockedMessaging = false
                    // Immediate probe when returning to the lock screen (don't wait for loop sleep).
                    Task { await sessionController.validateSessionIfNeeded() }
                } else if router.isUnlocked {
                    messagingController.handleAppBecameActive()
                }
            case .inactive:
                break
            @unknown default:
                break
            }
        }
    }

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
