import LocalAuthentication
import SwiftUI

/// Chats-locked screen — maps to `Locked` in `iOS-App.pen`; the unlock choreography maps to
/// `Locked — Unlock Animation` (Tap → Verified → Release → Reveal).
///
/// Human: Shown instead of Welcome when a server session and a local identity exist but the
/// history vault is still sealed. One job: unlock. The success animation runs here through
/// `.releasing`; the final cross-fade into Chats is `RootView`'s transition.
/// Agent: CALLS cryptoController.unlockHistoryIfPossible; on success loads the chats cache
/// (messagingController.prepareCachedState), plays phases, then router.unlockMessages().
/// Never auto-prompts biometry — every prompt is a tap.
struct LockScreenView: View {
    @Bindable var router: AppRouter

    @Environment(ServerConfigurationController.self) private var serverConfig
    @Environment(SessionController.self) private var sessionController
    @Environment(CryptoController.self) private var cryptoController
    @Environment(MessagingController.self) private var messagingController
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    /// Where the unlock choreography is.
    enum Phase: Equatable {
        case idle
        /// A vault prompt is up (system sheet) — the badge breathes, the label says so.
        case checking
        /// Vault opened: badge springs open, chips decrypt, the button turns green.
        case verified
        /// Rings ripple out, the mark lifts, everything else settles away.
        case releasing
        /// Main shell is fading in underneath; the mark shrinks up and dissolves.
        case revealing
    }

    @State private var phase: Phase = .idle
    @State private var hasArrived = false
    @State private var showServerSettings = false
    @State private var toastMessage: String?
    @State private var toastDismissTask: Task<Void, Never>?
    /// Which control is running the vault prompt (nil = idle).
    @State private var unlockingMethod: HistoryKeyVault.UnlockMethod?
    /// Drives the badge's breathing while the system sheet is up.
    @State private var badgeBreathing = false
    /// Device biometry, probed once per screen — LAContext is not free and this never changes.
    @State private var biometry = Self.detectBiometry()

    /// Verified → Release → Reveal, from the storyboard's time chips.
    private static let verifiedHold: Duration = .milliseconds(300)
    private static let releaseHold: Duration = .milliseconds(340)

    private var isBusy: Bool { phase != .idle }

    /// Name and SF Symbol of the biometry this device can evaluate; nil when it has none.
    private static func detectBiometry() -> (name: String, symbol: String)? {
        let context = LAContext()
        var error: NSError?
        guard context.canEvaluatePolicy(.deviceOwnerAuthenticationWithBiometrics, error: &error) else {
            return nil
        }
        switch context.biometryType {
        case .touchID: return ("Touch ID", "touchid")
        case .opticID: return ("Optic ID", "opticid")
        default: return ("Face ID", "faceid")
        }
    }

    private var hasBiometry: Bool { biometry != nil }
    private var biometryName: String { biometry?.name ?? "Face ID" }
    private var biometrySymbol: String { biometry?.symbol ?? "faceid" }

    private var canUseDeviceAuth: Bool { HistoryKeyVault.canProtectWrapKey }

    private var deviceName: String { UIDevice.current.model }

    var body: some View {
        VStack(spacing: 0) {
            navRow
            hero
                .padding(.top, 4)
            content
                .frame(maxHeight: .infinity, alignment: .top)
            actions
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .background(Theme.background)
        .navigationBarHidden(true)
        .toast($toastMessage)
        .onAppear {
            if !hasArrived {
                withAnimation(Motion.respecting(reduceMotion, Motion.gentle).delay(0.05)) {
                    hasArrived = true
                }
            }
            presentPostAuthToastIfNeeded()
            // Orphan session (identity gone) must not trap the user here.
            Task {
                if await router.reconcileOrphanedSessionIfNeeded() {
                    presentPostAuthToastIfNeeded()
                }
            }
        }
        .onChange(of: router.postAuthToast) { _, _ in
            presentPostAuthToastIfNeeded()
        }
        .onChange(of: phase) { _, new in
            guard !reduceMotion else { return }
            if new == .checking {
                withAnimation(.easeInOut(duration: 0.9).repeatForever(autoreverses: true)) {
                    badgeBreathing = true
                }
            } else {
                withAnimation(Motion.snappy) { badgeBreathing = false }
            }
        }
        .onDisappear { toastDismissTask?.cancel() }
        .serverSettingsSheet(
            isPresented: $showServerSettings,
            context: .onboarding,
            serverConfig: serverConfig
        )
    }

    // MARK: - Chrome

    private var navRow: some View {
        HStack {
            Spacer(minLength: 0)
            Button {
                showServerSettings = true
            } label: {
                Image(systemName: "gearshape.fill")
                    .font(.system(size: 18, weight: .semibold))
                    .foregroundStyle(Theme.accent)
                    .frame(width: 44, height: 44)
                    .background(Theme.backgroundGrouped)
                    .clipShape(Circle())
            }
            .pressable(scale: 0.88)
            .disabled(isBusy)
            .accessibilityLabel("Server settings")
        }
        .padding(.horizontal, 16)
        .padding(.top, 4)
        .opacity(phase == .idle || phase == .checking ? 1 : 0)
    }

    // MARK: - Hero

    private var verified: Bool { phase == .verified || phase == .releasing || phase == .revealing }
    private var released: Bool { phase == .releasing || phase == .revealing }

    private var hero: some View {
        ZStack {
            // Rings ripple outward on release.
            Circle()
                .stroke(Theme.accent.opacity(0.06), lineWidth: 1.5)
                .frame(width: 300, height: 300)
                .scaleEffect(released ? 1.3 : 1)
                .opacity(released ? 0 : 1)
            Circle()
                .stroke(Theme.accent.opacity(0.12), lineWidth: 1.5)
                .frame(width: 240, height: 240)
                .scaleEffect(released ? 1.3 : 1)
                .opacity(released ? 0 : 1)
            Circle()
                .fill(Theme.accentSoft)
                .frame(width: 160, height: 160)
                .scaleEffect(released ? 1.25 : (verified ? 1.04 : 1))
                .opacity(released ? 0 : 1)

            sealedChip
                .rotationEffect(.degrees(3))
                .offset(x: -98, y: 68)
                .opacity(released ? 0 : 1)
                .scaleEffect(released ? 0.9 : 1)

            lockedChip
                .rotationEffect(.degrees(-5))
                .offset(x: 97, y: -83)
                .opacity(released ? 0 : 1)
                .scaleEffect(released ? 0.9 : 1)

            BrandLogoMark(size: 100)
                // Zoom source for the phrase push, as Welcome's mark is for Sign Up / Log In.
                .onboardingHeroSource()
                .shadow(color: Theme.accent.opacity(released ? 0.4 : 0.3), radius: released ? 22 : 18, y: released ? 22 : 16)
                .scaleEffect(markScale)
                .offset(y: markOffset)
                .opacity(phase == .revealing ? 0 : 1)

            lockBadge
                .offset(x: 47, y: 48)
                .scaleEffect(badgeScale)
                .opacity(released ? 0 : 1)
        }
        .frame(maxWidth: .infinity)
        .frame(height: 300)
        .scaleEffect(hasArrived || reduceMotion ? 1 : 0.9)
        .opacity(hasArrived ? 1 : 0)
        .animation(Motion.respecting(reduceMotion, Motion.gentle), value: released)
        .animation(Motion.respecting(reduceMotion, Motion.bouncy), value: verified)
        .allowsHitTesting(false)
        .accessibilityHidden(true)
    }

    private var markScale: CGFloat {
        switch phase {
        case .idle, .checking: return 1
        case .verified: return 1.04
        case .releasing: return 1.16
        case .revealing: return 0.5
        }
    }

    private var markOffset: CGFloat {
        switch phase {
        case .idle, .checking, .verified: return 0
        case .releasing: return -24
        case .revealing: return -150
        }
    }

    private var badgeScale: CGFloat {
        if verified { return 1.1 }
        return badgeBreathing ? 1.12 : 1
    }

    private var lockBadge: some View {
        ZStack {
            Circle()
                // Bubble surface, not the page background: stays a visible disc in dark mode.
                .fill(verified ? Theme.online : Theme.bubbleIncoming)
                .frame(width: 40, height: 40)
                .shadow(color: Color.black.opacity(0.16), radius: 8, y: 6)
            Image(systemName: verified ? "lock.open.fill" : "lock.fill")
                .font(.system(size: 16, weight: .semibold))
                .foregroundStyle(verified ? Color.white : Theme.accent)
                .contentTransition(.symbolEffect(.replace))
        }
    }

    /// Outgoing bubble that "decrypts" on verify — dots become the message.
    private var lockedChip: some View {
        HStack(spacing: 6) {
            Image(systemName: verified ? "lock.open.fill" : "lock.fill")
                .font(.system(size: 11, weight: .semibold))
                .foregroundStyle(Color.white.opacity(0.8))
                .contentTransition(.symbolEffect(.replace))
            Text(verified ? "Hey! 👋" : "•••• ••••")
                .font(.system(size: 14, weight: .semibold))
                .tracking(verified ? 0 : 2)
                .foregroundStyle(Color.white)
                .contentTransition(.opacity)
        }
        .padding(.horizontal, 13)
        .padding(.vertical, 9)
        .background(Theme.accent)
        .clipShape(UnevenRoundedRectangle(topLeadingRadius: 16, bottomLeadingRadius: 16, bottomTrailingRadius: 4, topTrailingRadius: 16, style: .continuous))
        .shadow(color: Theme.accent.opacity(0.3), radius: 9, y: 6)
    }

    private var sealedChip: some View {
        HStack(spacing: 5) {
            Image(systemName: verified ? "checkmark.circle.fill" : "checkmark.shield.fill")
                .font(.system(size: 12, weight: .semibold))
                .foregroundStyle(Theme.online)
                .contentTransition(.symbolEffect(.replace))
            Text(verified ? "Unlocked" : "Sealed")
                .font(.system(size: 12, weight: .medium))
                .foregroundStyle(verified ? Theme.textPrimary : Theme.textSecondary)
                .contentTransition(.opacity)
        }
        .padding(.horizontal, 11)
        .padding(.vertical, 7)
        .background(Theme.bubbleIncoming)
        .clipShape(Capsule())
        .shadow(color: Color.black.opacity(0.12), radius: 9, y: 6)
    }

    // MARK: - Copy

    private var content: some View {
        VStack(spacing: 20) {
            if let username = sessionController.username {
                HStack(spacing: 8) {
                    AvatarView(initials: AvatarView.initials(for: username), size: 26, fontSize: 11)
                    Text("@\(username)")
                        .font(.system(size: 13, weight: .semibold))
                        .foregroundStyle(Theme.textPrimary)
                }
                .padding(.leading, 5)
                .padding(.trailing, 12)
                .padding(.vertical, 5)
                .background(Theme.backgroundGrouped)
                .clipShape(Capsule())
                .accessibilityLabel("Signed in as \(username)")
            }

            VStack(spacing: 10) {
                Text("Chats are locked")
                    .font(.system(size: 30, weight: .bold))
                    .tracking(-0.6)
                    .foregroundStyle(Theme.textPrimary)
                    .multilineTextAlignment(.center)
                Text("Your messages stay encrypted on this \(deviceName) until you unlock them.")
                    .font(.system(size: 15))
                    .foregroundStyle(Theme.textSecondary)
                    .multilineTextAlignment(.center)
                    .lineSpacing(4)
                    .frame(maxWidth: 300)
                    .fixedSize(horizontal: false, vertical: true)
            }
        }
        .padding(.top, 8)
        .screenContent()
        .opacity(released ? 0 : (hasArrived ? 1 : 0))
        .offset(y: released ? 24 : (hasArrived || reduceMotion ? 0 : 14))
        .animation(Motion.respecting(reduceMotion, Motion.gentle), value: released)
    }

    // MARK: - Actions

    private var actions: some View {
        VStack(spacing: 12) {
            if hasBiometry {
                unlockButton(
                    title: primaryTitle(for: "Unlock with \(biometryName)"),
                    symbol: biometrySymbol,
                    method: .biometryPreferred
                )
                .accessibilityIdentifier("lock.unlockBiometry")
                if canUseDeviceAuth {
                    passcodeButton
                }
            } else if canUseDeviceAuth {
                unlockButton(
                    title: primaryTitle(for: "Unlock with passcode"),
                    symbol: "lock.open",
                    method: .passcodeOnly
                )
                .accessibilityIdentifier("lock.unlockPasscode")
            } else {
                PrimaryButton(title: "Use encryption phrase", showsArrow: false) {
                    router.showLogIn()
                }
                .accessibilityIdentifier("lock.usePhrase")
            }

            if canUseDeviceAuth {
                // The whole sentence is the control so the fallback keeps a 44 pt hit area.
                Button {
                    router.showLogIn()
                } label: {
                    HStack(spacing: 4) {
                        Text(hasBiometry ? "Lost access to \(biometryName)?" : "Prefer another way?")
                            .foregroundStyle(Theme.textSecondary)
                        Text("Use encryption phrase")
                            .foregroundStyle(Theme.accent)
                            .fontWeight(.semibold)
                    }
                    .font(.system(size: 13))
                    .frame(maxWidth: .infinity, minHeight: 44)
                    .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
                .disabled(isBusy)
                .accessibilityLabel("Use encryption phrase")
                .accessibilityIdentifier("lock.usePhrase")
                .padding(.top, 2)
                .padding(.bottom, -6)
            }

            HStack(spacing: 6) {
                Image(systemName: "key.fill")
                    .font(.system(size: 10, weight: .semibold))
                Text("Keys never leave this \(deviceName)")
                    .font(.system(size: 11))
            }
            .foregroundStyle(Theme.textSecondary.opacity(0.75))
            .padding(.top, 4)
        }
        .padding(.horizontal, 24)
        .padding(.top, 8)
        .padding(.bottom, 12)
        .opacity(released ? 0 : (hasArrived ? 1 : 0))
        .offset(y: released ? 24 : (hasArrived || reduceMotion ? 0 : 20))
        .animation(Motion.respecting(reduceMotion, Motion.gentle), value: released)
    }

    private func primaryTitle(for idle: String) -> String {
        switch phase {
        case .idle: return idle
        case .checking: return "Checking…"
        case .verified, .releasing, .revealing: return "Unlocked"
        }
    }

    /// Filled capsule with the biometry glyph — turns green with a check once verified.
    private func unlockButton(title: String, symbol: String, method: HistoryKeyVault.UnlockMethod) -> some View {
        Button {
            Task { await unlock(method: method) }
        } label: {
            HStack(spacing: 10) {
                Image(systemName: verified ? "checkmark" : symbol)
                    .font(.system(size: 20, weight: .semibold))
                    .contentTransition(.symbolEffect(.replace))
                Text(title)
                    .font(.system(size: 17, weight: .semibold))
                    .contentTransition(.numericText())
            }
            .foregroundStyle(Color.white)
            .frame(maxWidth: .infinity)
            .frame(height: 54)
            .background(verified ? Theme.online : Theme.accent)
            .clipShape(Capsule())
            .shadow(color: (verified ? Theme.online : Theme.accent).opacity(0.25), radius: 20, y: 8)
            .animation(Motion.respecting(reduceMotion, Motion.bouncy), value: verified)
            .animation(Motion.snappy, value: title)
        }
        .pressable(scale: 0.975, dimming: 0.05, haptic: .medium)
        .disabled(isBusy)
        .accessibilityLabel(title)
    }

    private var passcodeButton: some View {
        Button {
            Task { await unlock(method: .passcodeOnly) }
        } label: {
            Text(unlockingMethod == .passcodeOnly && phase == .checking ? "Checking…" : "Use device passcode")
                .font(.system(size: 17, weight: .semibold))
                .foregroundStyle(Theme.accent)
                .contentTransition(.numericText())
                .frame(maxWidth: .infinity)
                .frame(height: 54)
                .background(Theme.backgroundGrouped)
                .clipShape(Capsule())
        }
        .pressable(scale: 0.975, dimming: 0.06)
        .disabled(isBusy)
        .accessibilityIdentifier("lock.unlockPasscode")
    }

    // MARK: - Unlock flow

    private func unlock(method: HistoryKeyVault.UnlockMethod) async {
        guard phase == .idle else { return }
        guard let userID = sessionController.userID else {
            router.showLogIn()
            return
        }
        // Identity gone (data wiped mid-session) — leave the lock screen entirely.
        if !cryptoController.hasLocalIdentity(for: userID) {
            _ = await router.reconcileOrphanedSessionIfNeeded()
            presentPostAuthToastIfNeeded()
            return
        }
        unlockingMethod = method
        withAnimation(Motion.snappy) { phase = .checking }
        let ok = await cryptoController.unlockHistoryIfPossible(
            for: userID,
            automatic: false,
            method: method
        )
        unlockingMethod = nil
        guard ok else {
            withAnimation(Motion.snappy) { phase = .idle }
            if !cryptoController.hasLocalIdentity(for: userID) {
                _ = await router.reconcileOrphanedSessionIfNeeded()
                presentPostAuthToastIfNeeded()
            } else {
                toastMessage = cryptoController.lastUnlockErrorMessage
                    ?? CryptoController.userMessage(for: CryptoControllerError.historyLocked)
            }
            return
        }
        // Heavy and synchronous: load the chats while the screen still reads "Checking…",
        // so nothing is moving and Chats is inserted with its rows already in place.
        messagingController.prepareCachedState()
        await playUnlockChoreography()
    }

    /// Verified → Release here; Reveal is RootView's cross-fade into the main shell.
    private func playUnlockChoreography() async {
        Haptics.notification(.success)
        if reduceMotion {
            withAnimation(Motion.reduced) { phase = .revealing }
            router.unlockMessages()
            recoverIfStillLocked()
            return
        }
        withAnimation(Motion.bouncy) { phase = .verified }
        try? await Task.sleep(for: Self.verifiedHold)
        withAnimation(Motion.gentle) { phase = .releasing }
        try? await Task.sleep(for: Self.releaseHold)
        withAnimation(Motion.gentle) {
            phase = .revealing
            router.unlockMessages()
        }
        recoverIfStillLocked()
    }

    /// The vault can re-seal during the choreography (app backgrounded, session force-ended),
    /// in which case `unlockMessages()` declines. Bring the controls back instead of leaving a
    /// faded, disabled screen.
    private func recoverIfStillLocked() {
        guard !router.isUnlocked else { return }
        messagingController.discardPreparedCachedState()
        withAnimation(Motion.gentle) { phase = .idle }
    }

    private func presentPostAuthToastIfNeeded() {
        guard let message = router.postAuthToast else { return }
        router.postAuthToast = nil
        toastDismissTask?.cancel()
        toastMessage = message
        toastDismissTask = Task {
            try? await Task.sleep(nanoseconds: 2_400_000_000)
            guard !Task.isCancelled else { return }
            toastMessage = nil
        }
    }
}

#Preview {
    NavigationStack {
        LockScreenView(router: AppRouter())
            .environment(ServerConfigurationController())
            .environment(SessionController())
            .environment(CryptoController())
            .environment(MessagingController())
    }
}
