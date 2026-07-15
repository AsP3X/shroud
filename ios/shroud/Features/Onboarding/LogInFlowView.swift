import SwiftUI

/// Single post-logout re-login screen — elements morph between credential and phrase steps in place.
struct LogInFlowView: View {
    let router: AppRouter

    @State private var phase: Phase = .credentials
    @State private var username = ""
    @State private var password = ""
    @State private var isPasswordVisible = false
    @State private var phraseWords: [String] = Array(repeating: "", count: 12)
    @State private var revealedWordCount = EncryptionPhraseGenerator.wordCount
    @State private var revealTask: Task<Void, Never>?

    private enum Phase {
        case credentials
        case encryptionPhrase
    }

    private var isCredentialsPhase: Bool { phase == .credentials }
    private var activeStep: Int { isCredentialsPhase ? 1 : 2 }

    private var signedInUsername: String {
        let trimmed = username.trimmingCharacters(in: .whitespacesAndNewlines)
        return trimmed.isEmpty ? "alex" : trimmed
    }

    var body: some View {
        GroupedScreen {
            VStack(spacing: 0) {
                navRow

                ScrollView {
                    VStack(spacing: 14) {
                        signedInBanner
                        heroSection
                        FlowStepper(activeStep: activeStep)
                        mainContentSlot

                        if isCredentialsPhase {
                            Text("You'll enter your encryption phrase in the next step.")
                                .font(.system(size: 12))
                                .foregroundStyle(Theme.textSecondary)
                                .frame(maxWidth: .infinity)
                                .multilineTextAlignment(.center)
                                .transition(.opacity)
                        }

                        privacyHint
                    }
                    .screenContent()
                    .padding(.top, 10)
                }

                bottomSection
                    .screenContent()
                    .padding(.vertical, 8)
            }
        }
        .navigationBarHidden(true)
        .onboardingHeroDestination()
        .animation(.spring(response: 0.42, dampingFraction: 0.86), value: phase)
        .onDisappear {
            revealTask?.cancel()
        }
    }

    private var navRow: some View {
        HStack {
            Button {
                if isCredentialsPhase {
                    router.pop()
                } else {
                    phase = .credentials
                }
            } label: {
                HStack(spacing: 2) {
                    Image(systemName: "chevron.left")
                    Text("Back")
                }
                .font(.system(size: 16))
                .foregroundStyle(Theme.accent)
            }

            Spacer()

            Text("Sign Up")
                .font(.system(size: 16))
                .foregroundStyle(Theme.accent)
                .opacity(isCredentialsPhase ? 1 : 0)
                .allowsHitTesting(isCredentialsPhase)
                .onTapGesture {
                    router.showSignUp()
                }
        }
        .padding(.horizontal, 12)
        .padding(.vertical, 4)
    }

    // Human: Green confirmation strip that grows in below the nav once credentials are accepted.
    // Agent: READS signedInUsername (local state only); collapses to zero height in the credentials state.
    private var signedInBanner: some View {
        HStack(spacing: 8) {
            Image(systemName: "checkmark.circle.fill")
                .font(.system(size: 15))
                .foregroundStyle(Theme.online)
            Text("Signed in as @\(signedInUsername)")
                .font(.system(size: 13, weight: .semibold))
                .foregroundStyle(Theme.successText)
        }
        .frame(maxWidth: .infinity)
        .padding(.vertical, 8)
        .background(Theme.successBackground)
        .clipShape(RoundedRectangle(cornerRadius: 10, style: .continuous))
        .opacity(isCredentialsPhase ? 0 : 1)
        .frame(maxHeight: isCredentialsPhase ? 0 : nil)
        .clipped()
    }

    // Human: Persistent brand mark that morphs shield→key; matches Welcome logo for the zoom transition landing.
    // Agent: The container never moves between login phases — only the glyph, title, and subtitle change.
    private var heroSection: some View {
        VStack(spacing: 10) {
            ZStack {
                BrandLogoMark(size: 64)
                    .scaleEffect(isCredentialsPhase ? 1 : 0.82)
                    .opacity(isCredentialsPhase ? 1 : 0)

                ZStack {
                    RoundedRectangle(cornerRadius: 18, style: .continuous)
                        .fill(Theme.brandGradient)
                        .frame(width: 64, height: 64)
                    Image(systemName: "key.fill")
                        .font(.system(size: 28, weight: .semibold))
                        .foregroundStyle(Color.white)
                        .rotationEffect(.degrees(-45))
                }
                .shadow(color: Theme.accent.opacity(0.25), radius: 10, y: 8)
                .scaleEffect(isCredentialsPhase ? 0.82 : 1)
                .opacity(isCredentialsPhase ? 0 : 1)
            }

            VStack(spacing: 6) {
                Text(isCredentialsPhase ? "Welcome Back" : "Enter Encryption Phrase")
                    .font(.system(size: 30, weight: .bold))
                    .foregroundStyle(Theme.textPrimary)
                    .multilineTextAlignment(.center)
                    .contentTransition(.interpolate)

                Text(isCredentialsPhase
                    ? "Log in with your account to continue. You'll unlock your messages in the next step."
                    : "Enter your 12-word phrase to decrypt your message history on this device.")
                    .font(.system(size: 14))
                    .foregroundStyle(Theme.textSecondary)
                    .multilineTextAlignment(.center)
                    .lineSpacing(4)
                    .frame(maxWidth: 300)
                    .contentTransition(.interpolate)
            }
        }
        .frame(maxWidth: .infinity)
    }

    private var mainContentSlot: some View {
        ZStack(alignment: .top) {
            credentialsCard
                .opacity(isCredentialsPhase ? 1 : 0)
                .scaleEffect(isCredentialsPhase ? 1 : 0.96, anchor: .top)
                .allowsHitTesting(isCredentialsPhase)

            phraseCard
                .opacity(isCredentialsPhase ? 0 : 1)
                .scaleEffect(isCredentialsPhase ? 0.96 : 1, anchor: .top)
                .allowsHitTesting(!isCredentialsPhase)
        }
    }

    private var bottomSection: some View {
        VStack(spacing: 12) {
            PrimaryButton(title: isCredentialsPhase ? "Log In" : "Unlock Messages") {
                if isCredentialsPhase {
                    phase = .encryptionPhrase
                } else {
                    router.unlockMessages()
                }
            }

            ZStack {
                VStack(spacing: 12) {
                    orDivider
                    SecondaryButton(title: "Use Face ID") {}
                }
                .opacity(isCredentialsPhase ? 1 : 0)
                .frame(maxHeight: isCredentialsPhase ? nil : 0)
                .allowsHitTesting(isCredentialsPhase)

                Button("I lost my encryption phrase") {}
                    .font(.system(size: 15, weight: .semibold))
                    .foregroundStyle(Theme.accent)
                    .opacity(isCredentialsPhase ? 0 : 1)
                    .frame(maxHeight: isCredentialsPhase ? 0 : nil)
                    .allowsHitTesting(!isCredentialsPhase)
            }
            .clipped()
        }
    }

    private var orDivider: some View {
        HStack(spacing: 10) {
            Rectangle()
                .fill(Theme.separator.opacity(0.8))
                .frame(height: 1)
            Text("or")
                .font(.system(size: 12, weight: .medium))
                .foregroundStyle(Theme.textSecondary)
            Rectangle()
                .fill(Theme.separator.opacity(0.8))
                .frame(height: 1)
        }
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

    private var phraseCard: some View {
        VStack(alignment: .leading, spacing: 10) {
            HStack {
                Text("ENCRYPTION PHRASE")
                    .font(.system(size: 11, weight: .semibold))
                    .kerning(0.8)
                    .foregroundStyle(Theme.textSecondary)
                Spacer()
                Button {
                    pastePhraseFromPasteboard()
                } label: {
                    HStack(spacing: 5) {
                        Image(systemName: "doc.on.clipboard")
                            .font(.system(size: 12, weight: .semibold))
                        Text("Paste")
                            .font(.system(size: 12, weight: .semibold))
                    }
                    .foregroundStyle(Theme.accent)
                    .padding(.horizontal, 10)
                    .padding(.vertical, 5)
                    .background(Theme.accentSoft)
                    .clipShape(Capsule())
                }
                .disabled(isPhraseRevealInProgress)
                .opacity(isPhraseRevealInProgress ? 0.45 : 1)
            }

            LazyVGrid(columns: [GridItem(.flexible()), GridItem(.flexible())], spacing: 8) {
                ForEach(0 ..< 12, id: \.self) { index in
                    phraseWordField(index: index)
                }
            }
        }
        .padding(14)
        .background(Theme.background)
        .clipShape(RoundedRectangle(cornerRadius: 14, style: .continuous))
    }

    private var isPhraseRevealInProgress: Bool {
        revealedWordCount < EncryptionPhraseGenerator.wordCount
            && phraseWords.contains { !$0.isEmpty }
    }

    // Human: Reads the pasteboard, parses 12 words, then replays the Sign Up staggered shimmer reveal.
    // Agent: READS EncryptionPhrasePasteboard, WRITES phraseWords + revealedWordCount; phrase never leaves device.
    private func pastePhraseFromPasteboard() {
        guard let pastedText = EncryptionPhrasePasteboard.read(),
              let words = EncryptionPhraseParser.parse(pastedText) else {
            return
        }

        revealTask?.cancel()
        phraseWords = words
        revealedWordCount = 0

        EncryptionPhraseReveal.start(
            wordCount: words.count,
            setRevealedCount: { revealedWordCount = $0 },
            existingTask: &revealTask
        )
    }

    private func phraseWordField(index: Int) -> some View {
        let isRevealed = index < revealedWordCount

        return HStack(spacing: 6) {
            PhraseWordNumberBadge(number: index + 1, isRevealed: isRevealed)

            Group {
                if isRevealed {
                    TextField("word", text: Binding(
                        get: { phraseWords[index] },
                        set: { phraseWords[index] = $0 }
                    ))
                    .font(.system(size: 14, weight: .medium, design: .monospaced))
                    .textInputAutocapitalization(.never)
                    .autocorrectionDisabled()
                    .transition(
                        .asymmetric(
                            insertion: .opacity.combined(with: .scale(scale: 0.86, anchor: .leading)),
                            removal: .opacity
                        )
                    )
                } else {
                    ShimmerPlaceholder(height: 14, width: 72)
                        .transition(.opacity)
                }
            }
            .frame(maxWidth: .infinity, alignment: .leading)
        }
        .padding(.horizontal, 12)
        .frame(height: 40)
        .background(Theme.backgroundGrouped)
        .clipShape(RoundedRectangle(cornerRadius: 10, style: .continuous))
        .animation(EncryptionPhraseReveal.wordRevealSpring, value: isRevealed)
    }

    private var privacyHint: some View {
        HStack(spacing: 6) {
            Image(systemName: "lock.fill")
                .font(.system(size: 13))
                .foregroundStyle(Theme.textSecondary)
            Text("Your phrase never leaves this device")
                .font(.system(size: 12))
                .foregroundStyle(Theme.textSecondary)
        }
        .frame(maxWidth: .infinity)
        .opacity(isCredentialsPhase ? 0 : 1)
        .frame(maxHeight: isCredentialsPhase ? 0 : nil)
        .clipped()
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
                .foregroundStyle(Theme.textSecondary)
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
        LogInFlowView(router: AppRouter())
    }
}
