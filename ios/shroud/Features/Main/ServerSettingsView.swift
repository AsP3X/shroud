import SwiftUI
import UIKit

/// Full-screen Server settings — maps to `Settings — Server` in `iOS-App.pen`.
/// Pushed from Settings (not a sheet). Back returns to Settings; Save may sign out if endpoint changes.
///
/// Human: Official vs self-hosted endpoint while logged in.
/// Agent: WRITES ServerConfigurationController; may CALL router.logOut when endpoint changes.
struct ServerSettingsView: View {
    let router: AppRouter

    @Environment(ServerConfigurationController.self) private var serverConfig
    @Environment(SessionController.self) private var sessionController
    @Environment(\.dismiss) private var dismiss

    @State private var draft: ServerConfiguration
    @State private var errorMessage: String?
    @State private var savePhase: SavePhase = .idle
    @State private var showSignOutConfirm = false
    @Namespace private var modeNamespace

    private let spring = Animation.spring(response: 0.42, dampingFraction: 0.86)

    /// Bottom Save button lifecycle — idle → spinner → success check, then pop.
    private enum SavePhase: Equatable {
        case idle
        case saving
        case success
    }

    private var isBusy: Bool {
        savePhase != .idle
    }

    init(router: AppRouter, initial: ServerConfiguration? = nil) {
        self.router = router
        _draft = State(initialValue: initial ?? .default)
    }

    private var endpointChanged: Bool {
        draft.resolvedBaseURLString != serverConfig.configuration.resolvedBaseURLString
            || draft.mode != serverConfig.configuration.mode
    }

    var body: some View {
        VStack(spacing: 0) {
            navRow

            ScrollView {
                VStack(alignment: .leading, spacing: 16) {
                    header
                    modePicker
                    selfHostedSection
                    signedInWarning
                    infoCard
                    if let errorMessage {
                        Text(errorMessage)
                            .font(.system(size: 13, weight: .medium))
                            .foregroundStyle(Theme.danger)
                            .transition(.opacity.combined(with: .move(edge: .top)))
                    }
                }
                .padding(.horizontal, 16)
                .padding(.bottom, 16)
                .animation(spring, value: draft.mode)
                .opacity(isBusy ? 0.55 : 1)
                .allowsHitTesting(!isBusy)
            }
            .scrollDismissesKeyboard(.interactively)

            bottomSave
        }
        .background(Theme.backgroundGrouped)
        .navigationBarHidden(true)
        .interactiveDismissDisabled(isBusy)
        .toolbar {
            ToolbarItemGroup(placement: .keyboard) {
                Spacer()
                Button("Done") {
                    UIApplication.shared.sendAction(
                        #selector(UIResponder.resignFirstResponder),
                        to: nil,
                        from: nil,
                        for: nil
                    )
                }
                .font(.system(size: 16, weight: .semibold))
                .foregroundStyle(Theme.accent)
            }
        }
        .onAppear {
            draft = serverConfig.configuration
        }
        .confirmationDialog(
            "Change server?",
            isPresented: $showSignOutConfirm,
            titleVisibility: .visible
        ) {
            Button("Save and sign out", role: .destructive) {
                performSave(signOutAfter: true)
            }
            Button("Cancel", role: .cancel) {}
        } message: {
            Text("Switching servers signs you out of this account on this device. You can sign in again on the new server.")
        }
    }

    // MARK: - Chrome

    private var navRow: some View {
        HStack {
            Button {
                Haptics.impact(.light)
                dismiss()
            } label: {
                HStack(spacing: 2) {
                    Image(systemName: "chevron.left")
                        .font(.system(size: 16, weight: .semibold))
                    Text("Settings")
                        .font(.system(size: 16))
                }
                .foregroundStyle(Theme.accent)
            }
            .pressable(scale: 0.9)
            .disabled(isBusy)
            .opacity(isBusy ? 0.4 : 1)

            Spacer()

            Button("Save") {
                attemptSave()
            }
            .font(.system(size: 16, weight: .semibold))
            .foregroundStyle(Theme.accent)
            .pressable(scale: 0.9)
            .disabled(isBusy)
            .opacity(isBusy ? 0.4 : 1)
        }
        .padding(.horizontal, 12)
        .padding(.vertical, 4)
        .frame(height: 44)
    }

    private var header: some View {
        VStack(alignment: .leading, spacing: 6) {
            Text("Server")
                .font(.system(size: 32, weight: .bold))
                .foregroundStyle(Theme.textPrimary)
            Text("Use the official Shroud network or connect to your own self-hosted server.")
                .font(.system(size: 14))
                .foregroundStyle(Theme.textSecondary)
                .fixedSize(horizontal: false, vertical: true)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(.top, 4)
    }

    private var bottomSave: some View {
        VStack(spacing: 0) {
            Button {
                attemptSave()
            } label: {
                HStack(spacing: 10) {
                    switch savePhase {
                    case .idle:
                        Text("Save")
                            .font(.system(size: 17, weight: .semibold))
                            .transition(.opacity.combined(with: .scale(scale: 0.92)))
                    case .saving:
                        ProgressView()
                            .progressViewStyle(.circular)
                            .tint(.white)
                            .scaleEffect(0.95)
                        Text("Saving…")
                            .font(.system(size: 17, weight: .semibold))
                            .transition(.opacity.combined(with: .scale(scale: 0.92)))
                    case .success:
                        Image(systemName: "checkmark.circle.fill")
                            .font(.system(size: 20, weight: .semibold))
                            .symbolEffect(.bounce, value: savePhase == .success)
                        Text("Saved")
                            .font(.system(size: 17, weight: .semibold))
                            .transition(.opacity.combined(with: .scale(scale: 0.92)))
                    }
                }
                .foregroundStyle(Color.white)
                .frame(maxWidth: .infinity)
                .frame(height: 50)
                .background(savePhase == .success ? Theme.online : Theme.accent)
                .clipShape(Capsule())
                .shadow(
                    color: (savePhase == .success ? Theme.online : Theme.accent).opacity(0.28),
                    radius: 16,
                    y: 8
                )
                .scaleEffect(savePhase == .saving ? 0.98 : 1)
            }
            .pressable(scale: 0.98, dimming: 0.05, haptic: .medium)
            .disabled(isBusy)
            .animation(.spring(response: 0.38, dampingFraction: 0.82), value: savePhase)
            .padding(.horizontal, 16)
            .padding(.top, 8)
            .padding(.bottom, 8)
        }
        .background(Theme.backgroundGrouped.opacity(0.95))
    }

    // MARK: - Form

    private var modePicker: some View {
        VStack(alignment: .leading, spacing: 10) {
            Text("CONNECTION")
                .font(.system(size: 11, weight: .semibold))
                .tracking(0.8)
                .foregroundStyle(Theme.textSecondary)

            VStack(spacing: 8) {
                modeOption(
                    mode: .official,
                    title: "Official Shroud server",
                    subtitle: "Managed by Shroud · always up to date",
                    badge: "checkmark.seal.fill"
                )
                modeOption(
                    mode: .selfHosted,
                    title: "Self-hosted",
                    subtitle: "Your Docker / private server",
                    badge: "externaldrive.fill"
                )
            }
        }
    }

    private func modeOption(
        mode: ServerConnectionMode,
        title: String,
        subtitle: String,
        badge: String
    ) -> some View {
        let selected = draft.mode == mode

        return Button {
            selectMode(mode)
        } label: {
            HStack(spacing: 12) {
                ZStack {
                    Circle()
                        .stroke(selected ? Theme.accent : Theme.separator, lineWidth: selected ? 0 : 2)
                        .frame(width: 22, height: 22)
                    if selected {
                        Circle()
                            .fill(Theme.accent)
                            .frame(width: 22, height: 22)
                            .matchedGeometryEffect(id: "modeRadioFull", in: modeNamespace)
                        Circle()
                            .fill(Color.white)
                            .frame(width: 8, height: 8)
                    }
                }

                VStack(alignment: .leading, spacing: 2) {
                    Text(title)
                        .font(.system(size: 15, weight: .semibold))
                        .foregroundStyle(Theme.textPrimary)
                    Text(subtitle)
                        .font(.system(size: 12))
                        .foregroundStyle(Theme.textSecondary)
                        .multilineTextAlignment(.leading)
                }
                .frame(maxWidth: .infinity, alignment: .leading)

                Image(systemName: badge)
                    .font(.system(size: 18, weight: .semibold))
                    .foregroundStyle(selected ? Theme.accent : Theme.textSecondary)
                    .symbolEffect(.bounce, value: selected)
            }
            .padding(14)
            // On grouped background, unselected cards are white (design).
            .background(selected ? Theme.accentSoft : Theme.background)
            .clipShape(RoundedRectangle(cornerRadius: 14, style: .continuous))
            .overlay(
                RoundedRectangle(cornerRadius: 14, style: .continuous)
                    .stroke(selected ? Theme.accent.opacity(0.35) : Color.clear, lineWidth: 1.5)
            )
        }
        .pressable(scale: 0.98)
        .accessibilityAddTraits(selected ? .isSelected : [])
        .accessibilityLabel("\(title). \(subtitle)")
    }

    @ViewBuilder
    private var selfHostedSection: some View {
        if draft.mode == .selfHosted {
            VStack(alignment: .leading, spacing: 10) {
                Text("SELF-HOSTED DETAILS")
                    .font(.system(size: 11, weight: .semibold))
                    .tracking(0.8)
                    .foregroundStyle(Theme.textSecondary)

                field(
                    title: "Address / host",
                    systemImage: "globe",
                    text: $draft.host,
                    keyboard: .URL,
                    autocapitalization: .never
                )

                HStack(alignment: .top, spacing: 10) {
                    field(
                        title: "Port",
                        systemImage: "number",
                        text: $draft.port,
                        keyboard: .numberPad,
                        autocapitalization: .never
                    )
                    field(
                        title: "API path",
                        systemImage: "folder",
                        text: $draft.apiPath,
                        keyboard: .default,
                        autocapitalization: .never
                    )
                }

                Toggle(isOn: $draft.useHTTPS.animation(spring)) {
                    Text("Use HTTPS")
                        .font(.system(size: 15, weight: .medium))
                        .foregroundStyle(Theme.textPrimary)
                }
                .tint(Theme.accent)
                .padding(.vertical, 2)

                HStack(spacing: 8) {
                    Image(systemName: "link")
                        .font(.system(size: 13, weight: .semibold))
                        .foregroundStyle(Theme.textSecondary)
                    Text(draft.selfHostedPreviewString)
                        .font(.system(size: 12, weight: .medium, design: .monospaced))
                        .foregroundStyle(Theme.textSecondary)
                        .lineLimit(2)
                        .contentTransition(.numericText())
                }
                .padding(.horizontal, 12)
                .padding(.vertical, 10)
                .frame(maxWidth: .infinity, alignment: .leading)
                .background(Theme.background)
                .clipShape(RoundedRectangle(cornerRadius: 12, style: .continuous))
            }
            .transition(
                .asymmetric(
                    insertion: .opacity
                        .combined(with: .move(edge: .top))
                        .combined(with: .scale(scale: 0.98, anchor: .top)),
                    removal: .opacity.combined(with: .move(edge: .top))
                )
            )
        }
    }

    private var signedInWarning: some View {
        HStack(alignment: .top, spacing: 10) {
            Image(systemName: "exclamationmark.triangle.fill")
                .font(.system(size: 15))
                .foregroundStyle(Theme.warningIcon)
            Text("Changing the server while signed in will sign you out of this device so you can reconnect with the new endpoint.")
                .font(.system(size: 12))
                .foregroundStyle(Theme.warningText)
                .fixedSize(horizontal: false, vertical: true)
        }
        .padding(.horizontal, 12)
        .padding(.vertical, 10)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(Theme.warningBackground)
        .clipShape(RoundedRectangle(cornerRadius: 14, style: .continuous))
    }

    private var infoCard: some View {
        HStack(alignment: .top, spacing: 10) {
            Image(systemName: "info.circle.fill")
                .font(.system(size: 16))
                .foregroundStyle(Theme.accent)
            Text(infoCopy)
                .font(.system(size: 12))
                .foregroundStyle(Theme.accent)
                .fixedSize(horizontal: false, vertical: true)
        }
        .padding(.horizontal, 12)
        .padding(.vertical, 10)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(Theme.accentSoft)
        .clipShape(RoundedRectangle(cornerRadius: 14, style: .continuous))
        .animation(spring, value: draft.mode)
    }

    private var infoCopy: String {
        switch draft.mode {
        case .official:
            return "Official uses Shroud’s managed infrastructure. Self-hosted never leaves your network except as you configure."
        case .selfHosted:
            return "For Docker Compose on a simulator use 127.0.0.1 and port 8080. On a physical device, use your Mac’s LAN IP."
        }
    }

    // MARK: - Helpers

    private func field(
        title: String,
        systemImage: String,
        text: Binding<String>,
        keyboard: UIKeyboardType,
        autocapitalization: TextInputAutocapitalization
    ) -> some View {
        VStack(alignment: .leading, spacing: 6) {
            Text(title)
                .font(.system(size: 13, weight: .medium))
                .foregroundStyle(Theme.textPrimary)
            HStack(spacing: 10) {
                Image(systemName: systemImage)
                    .font(.system(size: 15, weight: .semibold))
                    .foregroundStyle(Theme.accent)
                    .frame(width: 18)
                TextField(title, text: text)
                    .font(.system(size: 15, weight: .medium))
                    .foregroundStyle(Theme.textPrimary)
                    .keyboardType(keyboard)
                    .textInputAutocapitalization(autocapitalization)
                    .autocorrectionDisabled()
            }
            .padding(.horizontal, 12)
            .frame(height: 48)
            .background(Theme.background)
            .clipShape(RoundedRectangle(cornerRadius: 12, style: .continuous))
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }

    private func selectMode(_ mode: ServerConnectionMode) {
        guard draft.mode != mode else { return }
        Haptics.impact(.soft)
        withAnimation(spring) {
            draft.mode = mode
            errorMessage = nil
        }
    }

    private func attemptSave() {
        guard !isBusy else { return }
        errorMessage = nil
        if let validation = draft.validationError() {
            Haptics.notification(.error)
            withAnimation(spring) {
                errorMessage = validation
            }
            return
        }

        if sessionController.isSignedIn, endpointChanged {
            showSignOutConfirm = true
            return
        }

        performSave(signOutAfter: false)
    }

    private func performSave(signOutAfter: Bool) {
        guard !isBusy else { return }

        // Human: Persist is instant; deliberate phases make Save feel finished before pop.
        // Agent: savePhase idle→saving→success; then dismiss / logOut with animation.
        Task { @MainActor in
            withAnimation(.spring(response: 0.34, dampingFraction: 0.86)) {
                savePhase = .saving
            }
            Haptics.impact(.soft)

            // Let the spinner register even when UserDefaults write is synchronous.
            try? await Task.sleep(for: .milliseconds(480))

            do {
                try serverConfig.save(draft)
            } catch {
                Haptics.notification(.error)
                withAnimation(spring) {
                    savePhase = .idle
                    errorMessage = error.localizedDescription
                }
                return
            }

            withAnimation(.spring(response: 0.4, dampingFraction: 0.78)) {
                savePhase = .success
            }
            Haptics.notification(.success)

            // Hold the success state briefly, then pop with a soft spring.
            try? await Task.sleep(for: .milliseconds(620))

            if signOutAfter {
                // Human: Endpoint change invalidates the current session.
                router.logOut()
            } else {
                withAnimation(.spring(response: 0.48, dampingFraction: 0.9)) {
                    dismiss()
                }
            }
        }
    }
}

#Preview {
    NavigationStack {
        ServerSettingsView(router: AppRouter())
    }
    .environment(SessionController())
    .environment(ServerConfigurationController())
}
