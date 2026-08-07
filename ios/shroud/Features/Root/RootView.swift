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
            // Only restore if Keychain still has a session (logout clears it first).
            if sessionController.isSignedIn {
                await router.restoreUnlockedSessionIfNeeded()
            } else {
                router.hasUnlockedMessaging = false
            }
            if router.isUnlocked {
                messagingController.start()
                PushNotificationService.shared.start()
            }
        }
        .onChange(of: sessionController.isSignedIn) { _, signedIn in
            if !signedIn {
                router.hasUnlockedMessaging = false
                cryptoController.lock(wipeStore: false)
                // AppRouter.logOut already stops messaging; this covers server-driven logout (401).
                messagingController.stop()
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
                Task {
                    if !cryptoController.isUnlocked,
                       let userID = sessionController.userID
                    {
                        // One automatic Face ID per lock cycle; cancel → Welcome button only.
                        let ok = await cryptoController.unlockHistoryIfPossible(
                            for: userID,
                            automatic: true
                        )
                        if ok {
                            router.hasUnlockedMessaging = true
                            messagingController.start()
                        } else if cryptoController.needsHistoryUnlock {
                            router.hasUnlockedMessaging = false
                        }
                    } else if router.isUnlocked {
                        messagingController.handleAppBecameActive()
                    }
                }
            case .inactive:
                break
            @unknown default:
                break
            }
        }
    }

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
}

#Preview {
    RootView()
}
