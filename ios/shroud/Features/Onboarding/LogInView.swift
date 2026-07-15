import SwiftUI

/// Log In screen — maps to `Log In` in `iOS-App.pen`.
struct LogInView: View {
    let router: AppRouter

    @State private var username = ""
    @State private var password = ""
    @State private var isPasswordVisible = false

    var body: some View {
        GroupedScreen {
            VStack(spacing: 0) {
                navRow

                ScrollView {
                    VStack(alignment: .leading, spacing: 16) {
                        Text("Log In")
                            .font(.system(size: 32, weight: .bold))
                            .foregroundStyle(Theme.textPrimary)

                        FlowStepsCard(style: .standard)

                        credentialsCard

                        Text("Step 1 of 2 — log in with your account. You'll enter your encryption phrase next to unlock messages on this device.")
                            .font(.system(size: 13))
                            .foregroundStyle(Theme.textSecondary)
                            .lineSpacing(3)
                    }
                    .screenContent()
                    .padding(.top, 6)
                }

                VStack(spacing: 12) {
                    PrimaryButton(title: "Continue") {
                        let trimmed = username.trimmingCharacters(in: .whitespacesAndNewlines)
                        router.completeLogIn(username: trimmed.isEmpty ? "alex" : trimmed)
                    }
                    SecondaryButton(title: "Use Face ID") {}
                }
                .screenContent()
                .padding(.vertical, 8)
            }
        }
        .navigationBarHidden(true)
    }

    private var navRow: some View {
        HStack {
            Button {
                router.showWelcome()
            } label: {
                HStack(spacing: 2) {
                    Image(systemName: "chevron.left")
                    Text("Back")
                }
                .font(.system(size: 16))
                .foregroundStyle(Theme.accent)
            }
            Spacer()
            Button("Sign Up") {
                router.showSignUp()
            }
            .font(.system(size: 16))
            .foregroundStyle(Theme.accent)
        }
        .padding(.horizontal, 12)
        .padding(.vertical, 4)
    }

    private var credentialsCard: some View {
        VStack(spacing: 0) {
            credentialRow(icon: "at", placeholder: "Username", text: $username)
            Divider().padding(.leading, 42)
            credentialRow(
                icon: "lock.fill",
                placeholder: "Password",
                text: $password,
                isSecure: !isPasswordVisible,
                trailing: {
                    Button {
                        isPasswordVisible.toggle()
                    } label: {
                        Image(systemName: isPasswordVisible ? "eye" : "eye.slash")
                            .font(.system(size: 18))
                            .foregroundStyle(Theme.textSecondary)
                    }
                }
            )
        }
        .background(Theme.background)
        .clipShape(RoundedRectangle(cornerRadius: 14, style: .continuous))
    }

    private func credentialRow<Trailing: View>(
        icon: String,
        placeholder: String,
        text: Binding<String>,
        isSecure: Bool = false,
        @ViewBuilder trailing: () -> Trailing = { EmptyView() }
    ) -> some View {
        HStack(spacing: 10) {
            Image(systemName: icon)
                .font(.system(size: 18))
                .foregroundStyle(Theme.accent)
                .frame(width: 18)
            if isSecure {
                SecureField(placeholder, text: text)
            } else {
                TextField(placeholder, text: text)
                    .textInputAutocapitalization(.never)
                    .autocorrectionDisabled()
            }
            trailing()
        }
        .font(.system(size: 16))
        .padding(.horizontal, 14)
        .frame(height: 50)
    }
}

#Preview {
    NavigationStack {
        LogInView(router: AppRouter())
    }
}
