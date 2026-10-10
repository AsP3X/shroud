import SwiftUI
import UIKit

/// Warning, then the account password, then `DELETE /auth/account`.
///
/// Human: One screen. The button stays pale until the field has something in it. While the
/// request runs the screen cannot be left. A wrong password stays in the field, selected.
/// The password is not stored or logged; it is cleared when this screen goes away or the
/// delete succeeds.
/// Agent: CALLS AuthService.deleteAccount; CALLS DeviceWipeController.start(.accountDeleted)
/// on success or on `DEVICE_REMOVED`. Does not count a wrong password toward the 401 streak
/// (`APIClient.noteOutcome` excludes this call).
struct DeleteAccountView: View {
    @Environment(SessionController.self) private var session
    @Environment(DeviceWipeController.self) private var wipe
    @Environment(\.dismiss) private var dismiss
    @Environment(\.horizontalSizeClass) private var horizontalSizeClass

    @State private var password = ""
    @State private var revealed = false
    @State private var isDeleting = false
    @State private var errorMessage: String?
    @FocusState private var focused: PasswordField?

    private enum PasswordField: Hashable {
        case secure, revealed
    }

    private var hasPassword: Bool { !password.isEmpty }

    var body: some View {
        GroupedScreen {
            GeometryReader { geo in
                ScrollView {
                    VStack(alignment: .leading, spacing: 14) {
                        Text(DeleteAccountCopy.heading)
                            .font(.system(size: 28, weight: .bold))
                            .foregroundStyle(Theme.textPrimary)
                            .fixedSize(horizontal: false, vertical: true)

                        consequences

                        Text(DeleteAccountCopy.confirmPrompt)
                            .font(.system(size: 15))
                            .foregroundStyle(Theme.textSecondary)
                            .fixedSize(horizontal: false, vertical: true)

                        VStack(alignment: .leading, spacing: 8) {
                            passwordCard
                            if let errorMessage {
                                Text(errorMessage)
                                    .font(.system(size: 13))
                                    .foregroundStyle(Theme.danger)
                                    .fixedSize(horizontal: false, vertical: true)
                                    .accessibilityAddTraits(.updatesFrequently)
                            }
                        }

                        if horizontalSizeClass == .compact {
                            Spacer(minLength: 12)
                        }

                        deleteButton
                        cancelButton
                    }
                    .padding(.horizontal, 16)
                    .padding(.top, 8)
                    .padding(.bottom, 12)
                    .frame(maxWidth: .infinity, minHeight: geo.size.height, alignment: .top)
                }
                .scrollBounceBehavior(.basedOnSize)
                .scrollDismissesKeyboard(.interactively)
            }
        }
        .navigationTitle(DeleteAccountCopy.navigationTitle)
        .navigationBarTitleDisplayMode(.inline)
        .toolbar(.visible, for: .navigationBar)
        // Idle uses the system glass circle, the same one as the other settings screens.
        // While the request runs that button is hidden, so it cannot pop, and a faded
        // stand-in keeps the circle in the bar.
        .navigationBarBackButtonHidden(isDeleting)
        .toolbar {
            if isDeleting {
                ToolbarItem(placement: .topBarLeading) {
                    Button {} label: {
                        Image(systemName: "chevron.backward")
                            .font(.system(size: 17, weight: .semibold))
                            .foregroundStyle(Theme.textPrimary)
                    }
                    .disabled(true)
                    // The explicit colour would otherwise stay at full strength while disabled.
                    .opacity(0.35)
                    .accessibilityLabel("Back")
                }
            }
        }
        .interactivePopGesture(enabled: !isDeleting)
        .interactiveDismissDisabled(isDeleting)
        .onKeyPress(.escape) {
            isDeleting ? .handled : .ignored
        }
        .background {
            if isDeleting {
                Button("") {}
                    .keyboardShortcut(.escape, modifiers: [])
                    .accessibilityHidden(true)
                    .frame(width: 0, height: 0)
                    .opacity(0)
            }
        }
        .onDisappear { password = "" }
        .onChange(of: password) {
            guard !isDeleting else { return }
            errorMessage = nil
        }
    }

    private var consequences: some View {
        VStack(alignment: .leading, spacing: 0) {
            ForEach(Array(DeleteAccountCopy.consequences.enumerated()), id: \.offset) { index, row in
                if index > 0 {
                    Rectangle()
                        .fill(Theme.separator)
                        .frame(height: 1)
                        .padding(.leading, 48)
                }
                HStack(alignment: .top, spacing: 12) {
                    consequenceIcon(row.icon)
                    Text(row.text)
                        .font(.system(size: 16))
                        .foregroundStyle(Theme.textPrimary)
                        .fixedSize(horizontal: false, vertical: true)
                        .frame(maxWidth: .infinity, alignment: .leading)
                }
                .padding(.horizontal, 14)
                .padding(.vertical, 12)
            }
        }
        .background(Theme.background)
        .clipShape(RoundedRectangle(cornerRadius: 14, style: .continuous))
    }

    @ViewBuilder
    private func consequenceIcon(_ icon: DeleteAccountCopy.Icon) -> some View {
        Group {
            switch icon {
            case .messageDeleted:
                ZStack {
                    Image(systemName: "bubble")
                        .font(.system(size: 16))
                    Image(systemName: "xmark")
                        .font(.system(size: 7, weight: .bold))
                        .offset(y: -0.5)
                }
            case .people:
                Image(systemName: "person.2")
                    .font(.system(size: 15))
            case .trash:
                Image(systemName: "trash")
                    .font(.system(size: 15))
            case .at:
                Image(systemName: "at")
                    .font(.system(size: 16, weight: .medium))
            case .phone:
                Image(systemName: "iphone")
                    .font(.system(size: 16))
            }
        }
        .foregroundStyle(Theme.danger)
        .frame(width: 22, height: 22)
        .accessibilityHidden(true)
    }

    private var passwordCard: some View {
        HStack(spacing: 10) {
            Text(DeleteAccountCopy.passwordLabel)
                .font(.system(size: 16))
                .foregroundStyle(Theme.textPrimary)
            ZStack(alignment: .leading) {
                SecureField(DeleteAccountCopy.passwordPlaceholder, text: $password)
                    .textContentType(.password)
                    .focused($focused, equals: .secure)
                    .opacity(revealed ? 0 : 1)
                    .allowsHitTesting(!revealed)
                    .accessibilityHidden(revealed)
                TextField(DeleteAccountCopy.passwordPlaceholder, text: $password)
                    .textContentType(.password)
                    .textInputAutocapitalization(.never)
                    .autocorrectionDisabled()
                    .focused($focused, equals: .revealed)
                    .opacity(revealed ? 1 : 0)
                    .allowsHitTesting(revealed)
                    .accessibilityHidden(!revealed)
            }
            .font(.system(size: 16))
            .submitLabel(.go)
            .onSubmit { Task { await submit() } }
            Button {
                let wasEditing = focused != nil
                revealed.toggle()
                if wasEditing {
                    focused = revealed ? .revealed : .secure
                }
            } label: {
                Image(systemName: revealed ? "eye" : "eye.slash")
                    .font(.system(size: 18))
                    .foregroundStyle(Theme.textSecondary)
                    .frame(width: 44, height: 44)
                    .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
            .disabled(isDeleting)
            .accessibilityLabel(revealed ? "Hide password" : "Show password")
        }
        .padding(.leading, 14)
        .padding(.trailing, 3)
        .frame(minHeight: 50)
        .background(Theme.background)
        .clipShape(RoundedRectangle(cornerRadius: 14, style: .continuous))
        .disabled(isDeleting)
        .opacity(isDeleting ? 0.5 : 1)
    }

    private var deleteButton: some View {
        Button {
            Task { await submit() }
        } label: {
            HStack(spacing: 8) {
                if isDeleting {
                    ProgressView()
                        .tint(Color.white)
                }
                Text(isDeleting ? DeleteAccountCopy.deleting : DeleteAccountCopy.deleteButton)
                    .font(.system(size: 17, weight: .semibold))
                    .contentTransition(.opacity)
            }
            .foregroundStyle(Color.white)
            .frame(maxWidth: .infinity)
            .frame(height: 54)
            .background(hasPassword ? Theme.danger : Theme.danger.opacity(0.45))
            .clipShape(Capsule())
            .animation(Motion.snappy, value: isDeleting)
        }
        .pressable(scale: 0.975, dimming: 0.05, haptic: .medium)
        .disabled(!hasPassword || isDeleting)
        .accessibilityLabel(isDeleting ? DeleteAccountCopy.deleting : DeleteAccountCopy.deleteButton)
    }

    private var cancelButton: some View {
        Button(DeleteAccountCopy.cancel) {
            dismiss()
        }
        .font(.system(size: 17))
        .foregroundStyle(Theme.accent)
        .frame(maxWidth: .infinity)
        .frame(minHeight: 44)
        .disabled(isDeleting)
        // The explicit colour would otherwise stay at full strength while disabled.
        .opacity(isDeleting ? 0.35 : 1)
    }

    private func submit() async {
        guard !isDeleting, hasPassword else { return }
        guard let token = session.bearerToken else {
            show(.unreachable)
            return
        }
        let typed = password
        isDeleting = true
        errorMessage = nil
        defer { isDeleting = false }
        do {
            try await AuthService().deleteAccount(password: typed, token: token)
            password = ""
            wipe.start(reason: .accountDeleted)
        } catch {
            switch DeleteAccount.answer(of: error) {
            case .deleted:
                password = ""
                wipe.start(reason: .accountDeleted)
            case .wrongPassword:
                show(.wrongPassword)
                selectPasswordText()
            case .tooManyTries:
                show(.tooManyTries)
            case .unreachable:
                show(.unreachable)
            }
        }
    }

    private func show(_ answer: DeleteAccountAnswer) {
        errorMessage = DeleteAccount.message(for: answer)
        Haptics.notification(.error)
        if let errorMessage {
            AccessibilityNotification.Announcement(errorMessage).post()
        }
    }

    /// C7 leaves the password in the field and selects it so it can be retyped.
    private func selectPasswordText() {
        focused = revealed ? .revealed : .secure
        Task { @MainActor in
            try? await Task.sleep(for: .milliseconds(80))
            UIApplication.shared.sendAction(
                #selector(UIResponder.selectAll(_:)),
                to: nil,
                from: nil,
                for: nil
            )
        }
    }
}
