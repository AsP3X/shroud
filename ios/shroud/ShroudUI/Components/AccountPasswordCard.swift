import SwiftUI
import UIKit

/// The account-password row that confirms a destructive action: label, field and show/hide eye
/// on one card. Delete Account and Remove Device use it.
///
/// Human: Taken out of `DeleteAccountView` unchanged so both confirmations look and behave the
/// same. A wrong password stays in the field, selected, so it can be retyped (C7).
/// Agent: The owner keeps the password and the busy flag; bumping `selectRequest` focuses the
/// field and selects its text. Keeps a SecureField and a plain TextField mounted and shows one,
/// so revealing keeps the text and the focus.
struct AccountPasswordCard: View {
    @Binding var password: String
    var label: String = DeleteAccountCopy.passwordLabel
    var placeholder: String = DeleteAccountCopy.passwordPlaceholder
    /// The request is running: the card fades and takes no input.
    var isBusy: Bool = false
    /// Changed by the owner after a wrong password.
    var selectRequest: Int = 0
    /// Focus the field as soon as the card appears (a sheet that asks for nothing else).
    var focusOnAppear: Bool = false
    var onSubmit: () -> Void

    @State private var revealed = false
    @FocusState private var focused: Field?

    private enum Field: Hashable {
        case secure, revealed
    }

    var body: some View {
        HStack(spacing: 10) {
            Text(label)
                .font(.system(size: 16))
                .foregroundStyle(Theme.textPrimary)
            ZStack(alignment: .leading) {
                SecureField(placeholder, text: $password)
                    .textContentType(.password)
                    .focused($focused, equals: .secure)
                    .opacity(revealed ? 0 : 1)
                    .allowsHitTesting(!revealed)
                    .accessibilityHidden(revealed)
                TextField(placeholder, text: $password)
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
            .onSubmit { onSubmit() }
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
            .disabled(isBusy)
            .accessibilityLabel(revealed ? "Hide password" : "Show password")
        }
        .padding(.leading, 14)
        .padding(.trailing, 3)
        .frame(minHeight: 50)
        .background(Theme.background)
        .clipShape(RoundedRectangle(cornerRadius: 14, style: .continuous))
        .disabled(isBusy)
        .opacity(isBusy ? 0.5 : 1)
        .onChange(of: selectRequest) { selectPasswordText() }
        .onAppear {
            if focusOnAppear { focused = .secure }
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
