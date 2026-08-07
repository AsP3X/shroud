import SwiftUI

/// Root navigation shell — routes between onboarding and the main tab shell.
///
/// Onboarding and Main **must not** share one `NavigationStack`: nested stacks under a typed
/// path (e.g. `AppRoute` + `ChatRoute` / `SettingsRoute`) crash with
/// `AnyNavigationPath.Error.comparisonTypeMismatch`.
struct RootView: View {
    @Environment(\.scenePhase) private var scenePhase

    @State private var sessionController = SessionController()
    @State private var cryptoController = CryptoController()
    @State private var messagingController = MessagingController()
    @State private var callController = CallController()
    @State private var serverConfig = ServerConfigurationController()
    @State private var router = AppRouter()
    @Namespace private var onboardingNamespace

    var body: some View {
        ZStack {
            Group {
                if router.isUnlocked {
                    MainTabView(router: router)
                } else {
                    onboardingStack
                }
            }

            InCallOverlay()
                .zIndex(100)
                .allowsHitTesting(callController.active != nil)
        }
        .environment(\.onboardingNamespace, onboardingNamespace)
        .environment(sessionController)
        .environment(cryptoController)
        .environment(messagingController)
        .environment(callController)
        .environment(serverConfig)
        .task {
            SecurityPreferences.applyToVault()
            // Wire before any network call so 401s during validateSession count toward force-logout.
            SessionAuthBridge.controller = sessionController
            router.sessionController = sessionController
            router.cryptoController = cryptoController
            router.messagingController = messagingController
            router.callController = callController
            messagingController.bind(
                session: sessionController,
                crypto: cryptoController,
                calls: callController
            )
            callController.bind(session: sessionController, messaging: messagingController)
            PushNotificationService.shared.bind(session: sessionController, calls: callController)
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
        .onChange(of: sessionController.isSignedIn) { _, signedIn in
            if !signedIn {
                // Repeated HTTP 401s → full wipe (keys included). User Log Out keeps identity
                // for phrase re-unlock on the same device.
                let fullWipe = sessionController.consumePendingFullLocalWipe()
                router.hasUnlockedMessaging = false
                cryptoController.lock(wipeStore: fullWipe)
                // AppRouter.logOut already stops messaging; this covers server-driven logout.
                messagingController.stop(wipeDisk: true)
                callController.clearLocalState()
                PushNotificationService.shared.stop()
                if fullWipe {
                    router.postAuthToast = "Signed out · authentication failed · local data cleared"
                    router.path = []
                }
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
                // Face ID is opt-in via the Welcome unlock button — never auto-prompt here
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

    /// Pre-auth flow only — path elements are always `AppRoute`.
    private var onboardingStack: some View {
        NavigationStack(path: $router.path) {
            WelcomeView(router: router)
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
