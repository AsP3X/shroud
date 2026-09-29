import SwiftUI
import UIKit

/// Where the server sheet was opened from — affects signed-in warnings.
enum ServerSettingsContext: Equatable {
    /// Welcome gear — not signed in yet.
    case onboarding
    /// Signed in: the lock screen gear (`iOS-App.pen` Server Settings). Warns, and an endpoint
    /// change is a full Log Out (`onSignOut`). Settings → Server has its own `ServerSettingsView`.
    case accountSettings
}

/// Modal server configuration — maps to `Server Settings` in `iOS-App.pen`.
/// Official (no extra fields) vs Self-hosted (host / port / path).
///
/// Human: Animates field reveal when switching modes; persists non-secret endpoint settings.
/// Agent: WRITES ServerConfigurationController; when the endpoint changes while signed in, CALLS
/// `onSignOut` instead and saves nothing itself.
struct ServerSettingsSheet: View {
    @Environment(ServerConfigurationController.self) private var serverConfig
    @Environment(SessionController.self) private var sessionController: SessionController?
    @Environment(\.dismiss) private var dismiss
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    var context: ServerSettingsContext = .onboarding
    /// `.accountSettings` only: "Save and sign out" hands the new configuration here. The caller
    /// runs the app's Log Out and saves it afterwards (`AppRouter.logOut(switchingTo:)`), so the
    /// old server hears the sign-out and the new one never sees this session's token.
    var onSignOut: ((ServerConfiguration) -> Void)?

    @State private var draft: ServerConfiguration
    @State private var errorMessage: String?
    @State private var showSignOutConfirm = false
    @Namespace private var modeNamespace

    private var spring: Animation { Motion.respecting(reduceMotion, Motion.standard) }

    /// Scroll id of the error line, so a failed Save brings it into view.
    private static let errorAnchor = "serverError"

    init(
        context: ServerSettingsContext = .onboarding,
        initial: ServerConfiguration? = nil,
        onSignOut: ((ServerConfiguration) -> Void)? = nil
    ) {
        self.context = context
        self.onSignOut = onSignOut
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
        ScrollViewReader { proxy in
            ScrollView {
                VStack(alignment: .leading, spacing: 12) {
                    header
                    modePicker
                    selfHostedSection
                    // Right under the fields it's about, not below the cards; every
                    // validation error comes from the self-hosted fields.
                    if let errorMessage {
                        Text(errorMessage)
                            .font(.system(size: 13, weight: .medium))
                            .foregroundStyle(Theme.dangerText)
                            .transition(
                                reduceMotion ? AnyTransition.opacity : .opacity.combined(with: .move(edge: .top))
                            )
                            .id(Self.errorAnchor)
                    }
                    infoCard
                    if isSignedInContext {
                        signedInWarning
                    }
                    actions
                }
                .padding(.horizontal, 20)
                .padding(.top, 4)
                .padding(.bottom, 28)
                .animation(spring, value: draft.mode)
            }
            .onChange(of: errorMessage) { _, message in
                guard message != nil else { return }
                // A main-queue turn later, once the new line has a frame to scroll to.
                DispatchQueue.main.async {
                    withAnimation(spring) {
                        proxy.scrollTo(Self.errorAnchor, anchor: .center)
                    }
                }
            }
        }
        .background(Theme.background)
        .scrollDismissesKeyboard(.interactively)
        .safeAreaInset(edge: .top, spacing: 0) {
            // Design drag handle; the system indicator is hidden (see
            // `.presentationDragIndicator(.hidden)`). Secondary grey keeps it quiet in dark mode.
            Capsule()
                .fill(Theme.textSecondary.opacity(0.35))
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
                .accessibilityAddTraits(.isHeader)
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
                    // Unselected ring in secondary grey: the separator tone was ~1.1:1.
                    Circle()
                        .stroke(selected ? Theme.accent : Theme.textSecondary, lineWidth: selected ? 0 : 2)
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
                reduceMotion
                    ? AnyTransition.opacity
                    : .asymmetric(
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
                .foregroundStyle(Theme.accentText)
            Text(infoCopy)
                .font(.system(size: 12))
                .foregroundStyle(Theme.accentText)
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

    /// The shared CTA pair, as on Welcome: the press style gives Save its scale and haptic.
    private var actions: some View {
        VStack(spacing: 8) {
            PrimaryButton(title: "Save", showsArrow: false) {
                attemptSave()
            }
            SecondaryButton(title: "Cancel") {
                dismiss()
            }
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
            // Signing out, nothing is saved here: saved now, Log Out would revoke the session
            // on the new server, and the old one would keep this iPhone's push tokens.
            if !signOutAfter { try serverConfig.save(draft) }
            Haptics.notification(.success)
            // A beat for the confirmation dialog to finish leaving before the sheet goes.
            DispatchQueue.main.asyncAfter(deadline: .now() + 0.12) {
                if signOutAfter {
                    // Human: New endpoint needs a fresh session. The full Log Out (the wipe and
                    // its overlay) starts under the leaving sheet and ends on Welcome.
                    onSignOut?(draft)
                }
                dismiss()
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
    ///
    /// Human: Opens on the saved configuration (not the build default animating over to it),
    /// and only as a large card: at the medium detent Save and Cancel started below the fold.
    func serverSettingsSheet(
        isPresented: Binding<Bool>,
        context: ServerSettingsContext,
        serverConfig: ServerConfigurationController,
        onSignOut: ((ServerConfiguration) -> Void)? = nil
    ) -> some View {
        sheet(isPresented: isPresented) {
            ServerSettingsSheet(context: context, initial: serverConfig.configuration, onSignOut: onSignOut)
                .environment(serverConfig)
                .presentationDetents([.large])
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
            serverConfig: ServerConfigurationController(),
            onSignOut: { _ in }
        )
        .environment(SessionController())
}
