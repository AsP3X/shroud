import SwiftUI
import UIKit

/// Sign Up screen — maps to `Sign Up` in `iOS-App.pen`.
struct SignUpView: View {
    let router: AppRouter

    @Environment(SessionController.self) private var sessionController
    @Environment(CryptoController.self) private var cryptoController

    @State private var username = ""
    @State private var password = ""
    @State private var wroteDownPhrase = false
    @State private var phraseWords: [String] = Array(repeating: "", count: 12)
    @State private var revealedWordCount = 0
    @State private var revealTask: Task<Void, Never>?
    @State private var toastMessage: String?
    @State private var toastDismissTask: Task<Void, Never>?
    @State private var isSubmitting = false
    @State private var errorMessage: String?

    private var passwordEvaluation: PasswordStrengthEvaluation {
        PasswordStrengthEvaluator.evaluate(password)
    }

    private var canCreateAccount: Bool {
        wroteDownPhrase
            && passwordEvaluation.meetsRequirements
            && !username.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
            && !isSubmitting
    }

    var body: some View {
        GroupedScreen {
            VStack(spacing: 0) {
                navRow

                ScrollView {
                    VStack(alignment: .leading, spacing: 12) {
                        BrandLogoMark(size: 48)
                            .padding(.bottom, 4)

                        Text("Create Account")
                            .font(.system(size: 32, weight: .bold))
                            .foregroundStyle(Theme.textPrimary)

                        sectionHeader(number: 1, title: "Choose your identity")
                        identityCard
                        Text("No phone number or email required.")
                            .font(.system(size: 12))
                            .foregroundStyle(Theme.textSecondary)

                        sectionHeader(number: 2, title: "Save your encryption phrase", showsCopy: true) {
                            copyPhraseToPasteboard()
                        }
                        EncryptionPhraseCard(words: phraseWords, revealedCount: revealedWordCount)
                        warningCard
                    }
                    .screenContent()
                    .padding(.top, 6)
                }

                VStack(spacing: 12) {
                    if let errorMessage {
                        Text(errorMessage)
                            .font(.system(size: 13, weight: .medium))
                            .foregroundStyle(Theme.danger)
                            .frame(maxWidth: .infinity, alignment: .leading)
                    }
                    confirmRow
                    PrimaryButton(title: isSubmitting ? "Creating…" : "Create Account") {
                        Task { await createAccount() }
                    }
                    .opacity(canCreateAccount ? 1 : 0.45)
                    .disabled(!canCreateAccount)
                }
                .screenContent()
                .padding(.vertical, 8)
            }
        }
        .navigationBarHidden(true)
        .onboardingHeroDestination()
        .toast($toastMessage)
        .task {
            await startPhraseGeneration()
        }
        .onDisappear {
            revealTask?.cancel()
            toastDismissTask?.cancel()
        }
    }

    private var navRow: some View {
        HStack {
            Button {
                router.pop()
            } label: {
                HStack(spacing: 2) {
                    Image(systemName: "chevron.left")
                    Text("Back")
                }
                .font(.system(size: 16))
                .foregroundStyle(Theme.accent)
            }
            Spacer()
            Button("Log In") {
                router.showLogIn()
            }
            .font(.system(size: 16))
            .foregroundStyle(Theme.accent)
        }
        .padding(.horizontal, 12)
        .padding(.vertical, 4)
    }

    private func sectionHeader(
        number: Int,
        title: String,
        showsCopy: Bool = false,
        onCopy: (() -> Void)? = nil
    ) -> some View {
        HStack(spacing: 8) {
            ZStack {
                RoundedRectangle(cornerRadius: 11, style: .continuous)
                    .fill(Theme.accent)
                    .frame(width: 22, height: 22)
                Text("\(number)")
                    .font(.system(size: 12, weight: .bold))
                    .foregroundStyle(Color.white)
            }
            Text(title)
                .font(.system(size: 16, weight: .semibold))
                .foregroundStyle(Theme.textPrimary)
            Spacer()
            if showsCopy {
                Button("Copy", action: { onCopy?() })
                    .font(.system(size: 12, weight: .semibold))
                    .foregroundStyle(Theme.accent)
                    .padding(.horizontal, 9)
                    .padding(.vertical, 4)
                    .background(Theme.accentSoft)
                    .clipShape(Capsule())
                    .disabled(revealedWordCount < EncryptionPhraseGenerator.wordCount)
                    .opacity(revealedWordCount < EncryptionPhraseGenerator.wordCount ? 0.45 : 1)
            }
        }
        .padding(.top, 6)
    }

    private var identityCard: some View {
        VStack(spacing: 0) {
            credentialRow(icon: "at", placeholder: "Username", text: $username)
            Divider().padding(.leading, 42)
            credentialRow(icon: "lock.fill", placeholder: "Password", text: $password, isSecure: true)
            Divider().padding(.leading, 42)
            PasswordStrengthMeter(evaluation: passwordEvaluation)
        }
        .background(Theme.background)
        .clipShape(RoundedRectangle(cornerRadius: 14, style: .continuous))
    }

    // Human: Generates the encryption phrase once, then reveals each word with a staggered shimmer transition.
    // Agent: CALLS EncryptionPhraseGenerator.generate(), UPDATES revealedWordCount on MainActor with timed delays.
    private func startPhraseGeneration() async {
        revealTask?.cancel()
        wroteDownPhrase = false
        revealedWordCount = 0

        let generated = EncryptionPhraseGenerator.generate()
        phraseWords = generated

        EncryptionPhraseReveal.start(
            wordCount: generated.count,
            setRevealedCount: { revealedWordCount = $0 },
            existingTask: &revealTask
        )

        await revealTask?.value
    }

    private func copyPhraseToPasteboard() {
        guard revealedWordCount == EncryptionPhraseGenerator.wordCount else {
            showToast("Wait until all 12 words appear")
            return
        }

        let phrase = phraseWords
            .map { $0.trimmingCharacters(in: .whitespacesAndNewlines) }
            .filter { !$0.isEmpty }
            .joined(separator: " ")

        guard phrase.split(separator: " ").count == EncryptionPhraseGenerator.wordCount else {
            showToast("Phrase isn’t ready to copy yet")
            return
        }

        if EncryptionPhrasePasteboard.copy(phrase) {
            Haptics.notification(.success)
            showToast("Encryption phrase copied")
        } else {
            Haptics.notification(.error)
            showToast("Couldn’t copy phrase — try again")
        }
    }

    private func showToast(_ message: String) {
        toastDismissTask?.cancel()
        toastMessage = message
        toastDismissTask = Task {
            try? await Task.sleep(nanoseconds: 2_000_000_000)
            if Task.isCancelled { return }
            await MainActor.run {
                toastMessage = nil
            }
        }
    }

    private var warningCard: some View {
        HStack(alignment: .top, spacing: 9) {
            Image(systemName: "exclamationmark.triangle.fill")
                .font(.system(size: 15))
                .foregroundStyle(Theme.warningIcon)
            Text("These 12 words are the only way to restore your messages. Shroud cannot recover them for you.")
                .font(.system(size: 12))
                .foregroundStyle(Theme.warningText)
                .lineSpacing(3)
        }
        .padding(.horizontal, 12)
        .padding(.vertical, 10)
        .background(Theme.warningBackground)
        .clipShape(RoundedRectangle(cornerRadius: 12, style: .continuous))
    }

    private var confirmRow: some View {
        Button {
            wroteDownPhrase.toggle()
        } label: {
            HStack(spacing: 9) {
                ZStack {
                    RoundedRectangle(cornerRadius: 7, style: .continuous)
                        .fill(wroteDownPhrase ? Theme.accent : Color.clear)
                        .frame(width: 22, height: 22)
                        .overlay(
                            RoundedRectangle(cornerRadius: 7, style: .continuous)
                                .stroke(wroteDownPhrase ? Theme.accent : Theme.separator, lineWidth: 1.5)
                        )
                    if wroteDownPhrase {
                        Image(systemName: "checkmark")
                            .font(.system(size: 12, weight: .bold))
                            .foregroundStyle(Color.white)
                    }
                }
                Text("I wrote down my encryption phrase")
                    .font(.system(size: 13, weight: .medium))
                    .foregroundStyle(Theme.textPrimary)
                Spacer(minLength: 0)
            }
            .padding(.horizontal, 4)
        }
        .pressable(scale: 0.97)
    }

    private func credentialRow(
        icon: String,
        placeholder: String,
        text: Binding<String>,
        isSecure: Bool = false
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
        }
        .font(.system(size: 16))
        .padding(.horizontal, 14)
        .frame(height: 50)
    }

    // Human: Phrase stays on device only; only username/password go to the API.
    // Agent: CALLS register → CryptoController.establishFromSignup → unlock; never sends phraseWords.
    private func createAccount() async {
        guard canCreateAccount else { return }
        isSubmitting = true
        errorMessage = nil
        defer { isSubmitting = false }

        let words = phraseWords
            .map { $0.trimmingCharacters(in: .whitespacesAndNewlines).lowercased() }
            .filter { !$0.isEmpty }
        guard words.count == EncryptionPhraseGenerator.wordCount else {
            errorMessage = "Wait until all 12 words are ready."
            return
        }
        // Checked before the account exists: without a passcode the keys cannot be stored.
        guard HistoryKeyVault.canProtectWrapKey else {
            errorMessage = CryptoController.userMessage(for: HistoryKeyVault.VaultError.passcodeNotSet)
            return
        }

        do {
            _ = try BIP39Seed.validateMnemonic(words)
            try await sessionController.register(username: username, password: password)
            guard let userID = sessionController.userID,
                  let token = sessionController.bearerToken
            else {
                errorMessage = "Account created but session is missing. Try logging in."
                return
            }
            try await cryptoController.establishFromSignup(
                mnemonicWords: words,
                userID: userID,
                bearerToken: token
            )
            router.unlockMessages()
        } catch {
            if error is BIP39Seed.SeedError || error is CryptoControllerError
                || error is HistoryKeyVault.VaultError
            {
                errorMessage = CryptoController.userMessage(for: error)
            } else {
                errorMessage = SessionController.userMessage(for: error)
            }
        }
    }
}

#Preview {
    NavigationStack {
        SignUpView(router: AppRouter())
            .environment(SessionController())
            .environment(CryptoController())
    }
}
