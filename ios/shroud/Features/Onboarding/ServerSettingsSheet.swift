import SwiftUI
import UIKit

/// Where the server sheet was opened from — affects signed-in warnings.
enum ServerSettingsContext: Equatable {
    /// Welcome gear — not signed in yet.
    case onboarding
    /// Settings → Server while logged in (`iOS-App.pen` Server Settings).
    case accountSettings
}

/// Modal server configuration — maps to `Server Settings` in `iOS-App.pen`.
/// Official (no extra fields) vs Self-hosted (host / port / path).
///
/// Human: Animates field reveal when switching modes; persists non-secret endpoint settings.
/// Agent: WRITES ServerConfigurationController; may CALL SessionController.logout when endpoint changes while signed in.
struct ServerSettingsSheet: View {
    @Environment(ServerConfigurationController.self) private var serverConfig
    @Environment(SessionController.self) private var sessionController: SessionController?
    @Environment(\.dismiss) private var dismiss

    var context: ServerSettingsContext = .onboarding

    @State private var draft: ServerConfiguration
    @State private var errorMessage: String?
    @State private var savePulse = false
    @State private var showSignOutConfirm = false
    @Namespace private var modeNamespace

    private let spring = Animation.spring(response: 0.42, dampingFraction: 0.86)

    init(
        context: ServerSettingsContext = .onboarding,
        initial: ServerConfiguration? = nil
    ) {
        self.context = context
        _draft = State(initialValue: initial ?? .default)
    }

    private var isSignedInContext: Bool {
        context == .accountSettings && sessionController?.isSignedIn == true
    }

    private var endpointChanged: Bool {
        draft.resolvedBaseURLString != serverConfig.configuration.resolvedBaseURLString
            || draft.mode != serverConfig.configuration.mode
    }

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 12) {
                header
                modePicker
                selfHostedSection
                infoCard
                if isSignedInContext {
                    signedInWarning
                }
                if let errorMessage {
                    Text(errorMessage)
                        .font(.system(size: 13, weight: .medium))
                        .foregroundStyle(Theme.danger)
                        .transition(.opacity.combined(with: .move(edge: .top)))
                }
                actions
            }
            .padding(.horizontal, 20)
            .padding(.top, 4)
            .padding(.bottom, 28)
            .animation(spring, value: draft.mode)
        }
        .background(Theme.background)
        .scrollDismissesKeyboard(.interactively)
        .safeAreaInset(edge: .top, spacing: 0) {
            // Design drag handle (system indicator is also shown).
            Capsule()
                .fill(Color(red: 216 / 255, green: 216 / 255, blue: 220 / 255))
                .frame(width: 36, height: 5)
                .padding(.top, 10)
                .padding(.bottom, 8)
                .frame(maxWidth: .infinity)
                .background(Theme.background)
        }
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

    // MARK: - Sections

    private var header: some View {
        VStack(alignment: .leading, spacing: 6) {
            Text("Server")
                .font(.system(size: 28, weight: .bold))
                .foregroundStyle(Theme.textPrimary)
            Text("Use the official Shroud network or connect to your own self-hosted server.")
                .font(.system(size: 14))
                .foregroundStyle(Theme.textSecondary)
                .fixedSize(horizontal: false, vertical: true)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }

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
                            .matchedGeometryEffect(id: "modeRadio", in: modeNamespace)
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
            .background(selected ? Theme.accentSoft : Theme.backgroundGrouped)
            .clipShape(RoundedRectangle(cornerRadius: 14, style: .continuous))
            .overlay(
                RoundedRectangle(cornerRadius: 14, style: .continuous)
                    .stroke(selected ? Theme.accent.opacity(0.35) : Color.clear, lineWidth: 1.5)
            )
            .scaleEffect(selected ? 1.0 : 0.99)
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
                .background(Theme.backgroundGrouped)
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

    /// Copy from `Server Settings` in `iOS-App.pen`.
    private var infoCopy: String {
        switch draft.mode {
        case .official:
            return "Official uses Shroud’s managed infrastructure. Self-hosted never leaves your network except as you configure."
        case .selfHosted:
            return "For Docker Compose on a simulator use 127.0.0.1 and port 8080. On a physical device, use your Mac’s LAN IP."
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

    private var actions: some View {
        VStack(spacing: 8) {
            Button {
                attemptSave()
            } label: {
                Text("Save")
                    .font(.system(size: 17, weight: .semibold))
                    .foregroundStyle(Color.white)
                    .frame(maxWidth: .infinity)
                    .frame(height: 50)
                    .background(Theme.accent)
                    .clipShape(Capsule())
                    .shadow(color: Theme.accent.opacity(0.25), radius: 16, y: 8)
                    .scaleEffect(savePulse ? 0.97 : 1)
            }
            .pressable(scale: 0.98, dimming: 0.05, haptic: .medium)

            Button {
                dismiss()
            } label: {
                Text("Cancel")
                    .font(.system(size: 17, weight: .semibold))
                    .foregroundStyle(Theme.accent)
                    .frame(maxWidth: .infinity)
                    .frame(height: 46)
                    .background(Theme.backgroundGrouped)
                    .clipShape(Capsule())
            }
            .pressable(scale: 0.98, dimming: 0.06)
        }
        .padding(.top, 4)
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
            .background(Theme.backgroundGrouped)
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
        errorMessage = nil
        if let validation = draft.validationError() {
            Haptics.notification(.error)
            withAnimation(spring) {
                errorMessage = validation
            }
            return
        }

        if isSignedInContext, endpointChanged {
            showSignOutConfirm = true
            return
        }

        performSave(signOutAfter: false)
    }

    private func performSave(signOutAfter: Bool) {
        do {
            try serverConfig.save(draft)
            Haptics.notification(.success)
            withAnimation(.spring(response: 0.28, dampingFraction: 0.7)) {
                savePulse = true
            }
            DispatchQueue.main.asyncAfter(deadline: .now() + 0.12) {
                savePulse = false
                if signOutAfter {
                    // Human: New endpoint needs a fresh session; log out then dismiss to Welcome.
                    Task {
                        await sessionController?.logout()
                        dismiss()
                    }
                } else {
                    dismiss()
                }
            }
        } catch {
            Haptics.notification(.error)
            withAnimation(spring) {
                errorMessage = error.localizedDescription
            }
        }
    }
}

// MARK: - Presentation (matches pen sheet: large card, radius 22, drag indicator)

extension View {
    /// Presents `Server Settings` as designed in `iOS-App.pen`.
    func serverSettingsSheet(
        isPresented: Binding<Bool>,
        context: ServerSettingsContext,
        serverConfig: ServerConfigurationController
    ) -> some View {
        sheet(isPresented: isPresented) {
            ServerSettingsSheet(context: context)
                .environment(serverConfig)
                .presentationDetents([.medium, .large])
                .presentationDragIndicator(.hidden) // custom handle in sheet content
                .presentationCornerRadius(22)
                .presentationBackground(Theme.background)
        }
    }
}

#Preview("Onboarding") {
    Text("Preview")
        .serverSettingsSheet(
            isPresented: .constant(true),
            context: .onboarding,
            serverConfig: ServerConfigurationController()
        )
}

#Preview("Account settings") {
    Text("Preview")
        .serverSettingsSheet(
            isPresented: .constant(true),
            context: .accountSettings,
            serverConfig: ServerConfigurationController()
        )
        .environment(SessionController())
}
