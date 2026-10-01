import SwiftUI

/// Single post-logout re-login screen — elements morph between credential and phrase steps in place.
struct LogInFlowView: View {
    let router: AppRouter

    @Environment(SessionController.self) private var sessionController
    @Environment(CryptoController.self) private var cryptoController
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    @State private var phase: Phase = .credentials
    /// Opened from the lock screen with a session already in place: only the phrase step
    /// applies, so Back leaves the screen instead of showing a login form.
    @State private var startedSignedIn = false
    @State private var username = ""
    @State private var password = ""
    @State private var isPasswordVisible = false
    @State private var phraseWords: [String] = Array(repeating: "", count: 12)
    @State private var revealedWordCount = EncryptionPhraseGenerator.wordCount
    @State private var revealTask: Task<Void, Never>?
    @State private var isSubmitting = false
    @State private var errorMessage: String?
    /// The server has no identity key for this account (`KEYS_REQUIRED`): it never had a
    /// phrase, so "I never got a 12-word phrase" is offered. Checked once per phrase step.
    @State private var accountHasNoPhrase = false
    /// Showing a fresh phrase to write down instead of the 12 entry fields.
    @State private var creatingPhrase = false
    @State private var newPhraseWords: [String] = []
    @State private var wroteDownNewPhrase = false
    @State private var toast: Toast?
    @FocusState private var focusedField: Field?

    private enum Phase {
        case credentials
        case encryptionPhrase
    }

    /// Every text field on the screen. The password has two: a secure one and the revealed
    /// twin the eye swaps in (see `credentialRow`).
    private enum Field: Hashable {
        case username
        case password
        case revealedPassword
        case word(Int)
    }

    private var isCredentialsPhase: Bool { phase == .credentials }
    private var activeStep: Int { isCredentialsPhase ? 1 : 2 }

    private var signedInUsername: String {
        if let name = sessionController.username, !name.isEmpty {
            return name
        }
        let trimmed = username.trimmingCharacters(in: .whitespacesAndNewlines)
        return trimmed.isEmpty ? "user" : trimmed
    }

    private var canSubmitCredentials: Bool {
        !username.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
            && !password.isEmpty
            && !isSubmitting
    }

    /// All 12 words present (local unlock only — never sent to the server). A new phrase also
    /// needs every word shown and the "I wrote down" box ticked, as at Sign Up.
    private var canUnlockWithPhrase: Bool {
        guard !isSubmitting else { return false }
        if creatingPhrase {
            return newPhraseWords.count == EncryptionPhraseGenerator.wordCount
                && revealedWordCount == EncryptionPhraseGenerator.wordCount
                && wroteDownNewPhrase
        }
        return phraseWords.allSatisfy { !$0.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty }
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
        .animation(Motion.respecting(reduceMotion, Motion.standard), value: phase)
        .onAppear {
            // Already have a server session (e.g. locked chats) — jump to phrase unlock. Without
            // animation: the push lands on the phrase step instead of morphing into it.
            if sessionController.isSignedIn {
                startedSignedIn = true
                var transaction = Transaction()
                transaction.disablesAnimations = true
                withTransaction(transaction) {
                    phase = .encryptionPhrase
                }
            }
        }
        .onDisappear {
            revealTask?.cancel()
        }
        // One `GET keys/identity/{me}` each time the phrase step opens.
        .task(id: phase) {
            guard phase == .encryptionPhrase else { return }
            await checkWhetherAccountHasAPhrase()
        }
        .toast($toast)
        // The error appears above the button while focus stays on it: say it out loud.
        .onChange(of: errorMessage) { _, message in
            if let message {
                AccessibilityNotification.Announcement(message).post()
            }
        }
    }

    /// Glass back circle and a "Sign Up" capsule that fades out once the phrase step is up.
    private var navRow: some View {
        GlassBarRow {
            GlassBarButton(systemImage: "chevron.left") {
                if isCredentialsPhase || startedSignedIn {
                    router.pop()
                } else {
                    // The phrase step's error belongs to the phrase step.
                    errorMessage = nil
                    focusedField = nil
                    leaveNewPhrase()
                    accountHasNoPhrase = false
                    phase = .credentials
                }
            }
            .accessibilityLabel("Back")
        } center: {
            EmptyView()
        } trailing: {
            GlassBarButton("Sign Up") {
                router.showSignUp()
            }
            .opacity(isCredentialsPhase ? 1 : 0)
            .allowsHitTesting(isCredentialsPhase)
            .accessibilityHidden(!isCredentialsPhase)
            .animation(Motion.fade, value: isCredentialsPhase)
        }
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
                // Usernames run to 32 characters: one line, inside the strip's rounded ends.
                .lineLimit(1)
                .minimumScaleFactor(0.8)
                .truncationMode(.middle)
        }
        .padding(.horizontal, 12)
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
                // Reduce Motion turns the morph into a plain cross-fade: no scaling.
                BrandLogoMark(size: 64)
                    .scaleEffect(isCredentialsPhase || reduceMotion ? 1 : 0.82)
                    .opacity(isCredentialsPhase ? 1 : 0)

                ZStack {
                    BrandTileBackground()
                        .frame(width: 64, height: 64)
                        .clipShape(BrandLogoMark.tileShape(size: 64))
                    Image(systemName: "key.fill")
                        .font(.system(size: 28, weight: .semibold))
                        .foregroundStyle(Color.white)
                        .rotationEffect(.degrees(-45))
                }
                .shadow(color: Theme.accent.opacity(0.25), radius: 10, y: 8)
                .scaleEffect(!isCredentialsPhase || reduceMotion ? 1 : 0.82)
                .opacity(isCredentialsPhase ? 0 : 1)
            }

            VStack(spacing: 6) {
                Text(isCredentialsPhase ? "Welcome Back" : creatingPhrase ? "Your New Phrase" : "Enter Encryption Phrase")
                    .font(.system(size: 30, weight: .bold))
                    .foregroundStyle(Theme.textPrimary)
                    .multilineTextAlignment(.center)
                    .contentTransition(.interpolate)
                    .accessibilityAddTraits(.isHeader)

                Text(isCredentialsPhase
                    ? "Log in with your account to continue. You'll unlock your messages in the next step."
                    : creatingPhrase
                    ? "Write these 12 words down. They become this account’s encryption phrase."
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
                .scaleEffect(isCredentialsPhase || reduceMotion ? 1 : 0.96, anchor: .top)
                .allowsHitTesting(isCredentialsPhase)

            phraseStepContent
                .opacity(isCredentialsPhase ? 0 : 1)
                .scaleEffect(!isCredentialsPhase || reduceMotion ? 1 : 0.96, anchor: .top)
                .allowsHitTesting(!isCredentialsPhase)
                .accessibilityHidden(isCredentialsPhase)
        }
    }

    // Human: The 12 entry fields, or — for an account that never had a phrase — a fresh phrase
    // to write down. Accounts like that exist: a sign-up abandoned between register and the key
    // upload, or one made by an old build. Without this the user has no way in (web
    // `Auth.tsx:231-267`, android-port P11b). The link shows only after the server said
    // `KEYS_REQUIRED`, so someone who has a phrase is not tempted to make another.
    // Agent: The new phrase goes through the same `unlockWithPhrase`; its published-key check
    // refuses it if the account has a key after all, so it can only ever be the first one.
    private var phraseStepContent: some View {
        VStack(spacing: 12) {
            if creatingPhrase {
                newPhraseContent
                    .transition(.opacity)
            } else {
                phraseCard
                    .transition(.opacity)
            }
            if accountHasNoPhrase {
                phraseSourceLink
                    .transition(.opacity)
            }
        }
        .animation(Motion.respecting(reduceMotion, Motion.standard), value: creatingPhrase)
        .animation(Motion.respecting(reduceMotion, Motion.standard), value: accountHasNoPhrase)
    }

    private var phraseSourceLink: some View {
        Button {
            if creatingPhrase {
                leaveNewPhrase()
            } else {
                showNewPhrase()
            }
        } label: {
            Text(creatingPhrase ? "I do have a phrase — let me type it" : "I never got a 12-word phrase")
                .font(.system(size: 14, weight: .semibold))
                .foregroundStyle(Theme.accentText)
                .frame(maxWidth: .infinity)
                .multilineTextAlignment(.center)
                // About 18 pt of text; the target reaches 13 pt past it to 44 pt.
                .contentShape(Rectangle().inset(by: -13))
        }
        .pressable()
        .disabled(isSubmitting)
        .accessibilityIdentifier("login.phraseSource")
    }

    /// The fresh phrase (same card and reveal as Sign Up), the warning, and "I wrote it down".
    private var newPhraseContent: some View {
        VStack(alignment: .leading, spacing: 10) {
            HStack {
                Text("ENCRYPTION PHRASE")
                    .font(.system(size: 11, weight: .semibold))
                    .kerning(0.8)
                    .foregroundStyle(Theme.textSecondary)
                Spacer()
                Button {
                    copyNewPhrase()
                } label: {
                    HStack(spacing: 5) {
                        Image(systemName: "doc.on.doc")
                            .font(.system(size: 12, weight: .semibold))
                        Text("Copy")
                            .font(.system(size: 12, weight: .semibold))
                    }
                    .foregroundStyle(Theme.accentText)
                    .padding(.horizontal, 10)
                    .padding(.vertical, 5)
                    .background(Theme.accentSoft)
                    .clipShape(Capsule())
                    // The pill is ~24 pt tall; the target reaches 10 pt past it on every side.
                    .contentShape(Rectangle().inset(by: -10))
                }
                .pressable()
                .disabled(revealedWordCount < EncryptionPhraseGenerator.wordCount)
                .opacity(revealedWordCount < EncryptionPhraseGenerator.wordCount ? 0.45 : 1)
            }
            .padding(.horizontal, 4)

            EncryptionPhraseCard(words: newPhraseWords, revealedCount: revealedWordCount)

            HStack(alignment: .top, spacing: 9) {
                Image(systemName: "exclamationmark.triangle.fill")
                    .font(.system(size: 15))
                    .foregroundStyle(Theme.warningIcon)
                    .accessibilityHidden(true)
                Text("This is only for an account that has never had a phrase. If you already set one on another device, go back and enter that phrase. A different one is not saved.")
                    .font(.system(size: 12))
                    .foregroundStyle(Theme.warningText)
                    .lineSpacing(3)
                    .fixedSize(horizontal: false, vertical: true)
            }
            .padding(.horizontal, 12)
            .padding(.vertical, 10)
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(Theme.warningBackground)
            .clipShape(RoundedRectangle(cornerRadius: 12, style: .continuous))
            .accessibilityElement(children: .combine)

            wroteDownRow
        }
    }

    /// Sign Up's "I wrote down my encryption phrase" check, gating Save as it gates Create Account.
    private var wroteDownRow: some View {
        Button {
            wroteDownNewPhrase.toggle()
        } label: {
            HStack(spacing: 9) {
                ZStack {
                    RoundedRectangle(cornerRadius: 7, style: .continuous)
                        .fill(wroteDownNewPhrase ? Theme.accent : Color.clear)
                        .frame(width: 22, height: 22)
                        .overlay(
                            RoundedRectangle(cornerRadius: 7, style: .continuous)
                                .stroke(wroteDownNewPhrase ? Theme.accent : Theme.textSecondary, lineWidth: 1.5)
                        )
                    if wroteDownNewPhrase {
                        Image(systemName: "checkmark")
                            .font(.system(size: 12, weight: .bold))
                            .foregroundStyle(Color.white)
                            .accessibilityHidden(true)
                    }
                }
                Text("I wrote down my encryption phrase")
                    .font(.system(size: 13, weight: .medium))
                    .foregroundStyle(Theme.textPrimary)
                Spacer(minLength: 0)
            }
            .padding(.horizontal, 4)
            .padding(.top, 2)
            // The whole row, ~44 pt tall, without changing the stack's spacing.
            .contentShape(Rectangle().inset(by: -11))
        }
        .pressable(scale: 0.97)
        .accessibilityAddTraits(.isToggle)
        .accessibilityValue(wroteDownNewPhrase ? "Checked" : "Not checked")
    }

    private var bottomSection: some View {
        VStack(spacing: 12) {
            if let errorMessage {
                Text(errorMessage)
                    .font(.system(size: 13, weight: .medium))
                    .foregroundStyle(Theme.dangerText)
                    .frame(maxWidth: .infinity, alignment: .leading)
            }

            PrimaryButton(
                title: isCredentialsPhase
                    ? (isSubmitting ? "Signing in…" : "Log In")
                    : (isSubmitting ? "Unlocking…" : creatingPhrase ? "Save Phrase and Continue" : "Unlock Messages"),
                isLoading: isSubmitting
            ) {
                if isCredentialsPhase {
                    Task { await submitCredentials() }
                } else {
                    Task { await submitPhraseUnlock() }
                }
            }
            .opacity(primaryButtonDimmed ? 0.45 : 1)
            .disabled(primaryButtonDisabled)

            if !isCredentialsPhase {
                Text("Shroud cannot recover a lost phrase. Messages on this device stay locked without it.")
                    .font(.system(size: 12))
                    .foregroundStyle(Theme.textSecondary)
                    .multilineTextAlignment(.center)
                    .padding(.horizontal, 8)
            }
        }
    }

    private var credentialsCard: some View {
        VStack(spacing: 0) {
            credentialRow(
                icon: "at",
                placeholder: "Username",
                text: $username,
                field: .username,
                contentType: .username
            )
            Divider().padding(.leading, 42)
            credentialRow(
                icon: "lock.fill",
                placeholder: "Password",
                text: $password,
                field: .password,
                contentType: .password,
                trailing: {
                    Button {
                        // Hand the keyboard to the twin that is about to show, so it stays up.
                        let wasEditing = focusedField == .password || focusedField == .revealedPassword
                        isPasswordVisible.toggle()
                        if wasEditing {
                            focusedField = isPasswordVisible ? .revealedPassword : .password
                        }
                    } label: {
                        Image(systemName: isPasswordVisible ? "eye" : "eye.slash")
                            .font(.system(size: 18))
                            .foregroundStyle(Theme.textSecondary)
                            // 44 pt target around the glyph; the row is 50 pt tall.
                            .frame(width: 44, height: 44)
                            .contentShape(Rectangle())
                    }
                    .pressable(scale: 0.88)
                    .accessibilityLabel(isPasswordVisible ? "Hide password" : "Show password")
                    // The wider target reaches into the row's inset; the glyph stays where it was.
                    .padding(.trailing, -11)
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
                    .foregroundStyle(Theme.accentText)
                    .padding(.horizontal, 10)
                    .padding(.vertical, 5)
                    .background(Theme.accentSoft)
                    .clipShape(Capsule())
                    // The pill is ~24 pt tall; the target reaches 10 pt past it on every side.
                    .contentShape(Rectangle().inset(by: -10))
                }
                .pressable()
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
            // Empty clipboard, "Don't Allow" on the paste prompt, or not a valid phrase.
            Haptics.notification(.error)
            errorMessage = "The clipboard doesn’t hold a valid 12-word phrase."
            return
        }

        errorMessage = nil
        focusedField = nil
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
            // The field names its own position ("Word 3"), so VoiceOver skips the badge.
            PhraseWordNumberBadge(number: index + 1, isRevealed: isRevealed)
                .accessibilityHidden(true)

            Group {
                if isRevealed {
                    TextField("word", text: Binding(
                        get: { phraseWords[index] },
                        set: { phraseWords[index] = $0 }
                    ))
                    .font(.system(size: 14, weight: .medium, design: .monospaced))
                    .textInputAutocapitalization(.never)
                    .autocorrectionDisabled()
                    .accessibilityLabel("Word \(index + 1)")
                    .focused($focusedField, equals: .word(index))
                    .submitLabel(index < phraseWords.count - 1 ? .next : .go)
                    .onSubmit { submitPhraseWord(at: index) }
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
        // The whole cell focuses its word, not only the text line.
        .contentShape(Rectangle())
        .onTapGesture {
            if isRevealed { focusedField = .word(index) }
        }
        .background(Theme.backgroundGrouped)
        .clipShape(RoundedRectangle(cornerRadius: 10, style: .continuous))
        .animation(EncryptionPhraseReveal.wordRevealSpring, value: isRevealed)
    }

    /// Return moves on to the next word; on the last one it unlocks once all 12 are in.
    private func submitPhraseWord(at index: Int) {
        if index < phraseWords.count - 1 {
            focusedField = .word(index + 1)
        } else {
            focusedField = nil
            if canUnlockWithPhrase {
                Task { await submitPhraseUnlock() }
            }
        }
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

    // Human: A tap anywhere on the 50 pt row focuses its field; Return goes username → password
    // → Log In. The eye swaps the password's two fields without dropping the keyboard.
    // Agent: The password row keeps a SecureField and a plain TextField mounted and shows one
    // (`isPasswordVisible`); `focusedField` moves between them. The hidden twin is out of
    // hit-testing and accessibility.
    private func credentialRow<Trailing: View>(
        icon: String,
        placeholder: String,
        text: Binding<String>,
        field: Field,
        contentType: UITextContentType? = nil,
        @ViewBuilder trailing: () -> Trailing = { EmptyView() }
    ) -> some View {
        HStack(spacing: 10) {
            Image(systemName: icon)
                .font(.system(size: 18))
                .foregroundStyle(Theme.textSecondary)
                .frame(width: 18)
            if field == .password {
                ZStack {
                    SecureField(placeholder, text: text)
                        .textContentType(contentType)
                        .focused($focusedField, equals: .password)
                        .opacity(isPasswordVisible ? 0 : 1)
                        .allowsHitTesting(!isPasswordVisible)
                        .accessibilityHidden(isPasswordVisible)
                    TextField(placeholder, text: text)
                        .textContentType(contentType)
                        .textInputAutocapitalization(.never)
                        .autocorrectionDisabled()
                        .focused($focusedField, equals: .revealedPassword)
                        .opacity(isPasswordVisible ? 1 : 0)
                        .allowsHitTesting(isPasswordVisible)
                        .accessibilityHidden(!isPasswordVisible)
                }
            } else {
                TextField(placeholder, text: text)
                    .textContentType(contentType)
                    .textInputAutocapitalization(.never)
                    .autocorrectionDisabled()
                    .focused($focusedField, equals: field)
            }
            trailing()
        }
        .font(.system(size: 16))
        .submitLabel(field == .username ? .next : .go)
        .onSubmit { submitCredentialField(field) }
        .padding(.horizontal, 14)
        .frame(height: 50)
        .contentShape(Rectangle())
        .onTapGesture { focusedField = focusTarget(for: field) }
    }

    /// The field that takes the keyboard for a credential row: the visible password twin.
    private func focusTarget(for field: Field) -> Field {
        field == .password && isPasswordVisible ? .revealedPassword : field
    }

    private func submitCredentialField(_ field: Field) {
        if field == .username {
            focusedField = focusTarget(for: .password)
        } else if canSubmitCredentials {
            Task { await submitCredentials() }
        }
    }

    private var primaryButtonDimmed: Bool {
        // In flight the button shows its spinner at full strength, not the invalid-form dim.
        if isSubmitting { return false }
        if isCredentialsPhase {
            return !canSubmitCredentials
        }
        return !canUnlockWithPhrase
    }

    private var primaryButtonDisabled: Bool {
        if isCredentialsPhase {
            return !canSubmitCredentials
        }
        return !canUnlockWithPhrase
    }

    // Human: Server session first; phrase step stays local for decrypt later.
    // Agent: CALLS SessionController.login; never sends phraseWords; does NOT unlock main yet.
    private func submitCredentials() async {
        guard canSubmitCredentials else { return }
        isSubmitting = true
        errorMessage = nil
        defer { isSubmitting = false }

        do {
            try await sessionController.login(username: username, password: password)
            // Stay on this screen — RootView must not treat session alone as messaging unlock.
            // The credentials card hides without resigning its field: drop the keyboard here.
            focusedField = nil
            withAnimation(Motion.respecting(reduceMotion, Motion.standard)) {
                phase = .encryptionPhrase
            }
        } catch {
            errorMessage = SessionController.userMessage(for: error)
        }
    }

    // Human: Phrase unlock is local crypto; server session already established.
    // Agent: CALLS CryptoController.unlockWithPhrase then unlockMessages; never uploads phraseWords.
    private func submitPhraseUnlock() async {
        guard canUnlockWithPhrase else {
            errorMessage = creatingPhrase
                ? "Write down all 12 words first."
                : "Enter all 12 words of your encryption phrase."
            return
        }
        guard let userID = sessionController.userID,
              let token = sessionController.bearerToken
        else {
            errorMessage = "Session expired. Log in again."
            focusedField = nil
            phase = .credentials
            return
        }

        isSubmitting = true
        errorMessage = nil
        defer { isSubmitting = false }

        let words = (creatingPhrase ? newPhraseWords : phraseWords)
            .map { $0.trimmingCharacters(in: .whitespacesAndNewlines).lowercased() }
            .filter { !$0.isEmpty }

        do {
            try await cryptoController.unlockWithPhrase(
                mnemonicWords: words,
                userID: userID,
                bearerToken: token
            )
            router.unlockMessages()
        } catch {
            errorMessage = CryptoController.userMessage(for: error)
        }
    }

    // MARK: - Accounts that never had a phrase

    /// Asks the server for this account's published identity key. Only `KEYS_REQUIRED` (no key
    /// at all) offers a new phrase; a key, offline, or any other answer keeps the link hidden.
    private func checkWhetherAccountHasAPhrase() async {
        guard let userID = sessionController.userID,
              let token = sessionController.bearerToken
        else { return }
        let hasNone: Bool
        do {
            _ = try await KeyBundleService().fetchIdentity(userID: userID, bearerToken: token)
            hasNone = false
        } catch {
            hasNone = Self.isKeysRequired(error)
        }
        // The user may have gone back to the credentials meanwhile.
        guard !Task.isCancelled, phase == .encryptionPhrase else { return }
        accountHasNoPhrase = hasNone
        if !hasNone, creatingPhrase { leaveNewPhrase() }
    }

    /// The server's "this account has no keys" answer to `GET keys/identity/{id}`.
    nonisolated static func isKeysRequired(_ error: Error) -> Bool {
        guard case let .server(code, _, _)? = error as? APIError else { return false }
        return code == "KEYS_REQUIRED"
    }

    /// "I never got a 12-word phrase": a fresh phrase, revealed like Sign Up's.
    private func showNewPhrase() {
        errorMessage = nil
        focusedField = nil
        let generated = EncryptionPhraseGenerator.generate()
        newPhraseWords = generated
        wroteDownNewPhrase = false
        revealedWordCount = 0
        creatingPhrase = true
        EncryptionPhraseReveal.start(
            wordCount: generated.count,
            setRevealedCount: { revealedWordCount = $0 },
            existingTask: &revealTask
        )
    }

    /// Back to the entry fields; the unused phrase is dropped.
    private func leaveNewPhrase() {
        guard creatingPhrase else { return }
        revealTask?.cancel()
        errorMessage = nil
        creatingPhrase = false
        newPhraseWords = []
        wroteDownNewPhrase = false
        revealedWordCount = EncryptionPhraseGenerator.wordCount
    }

    private func copyNewPhrase() {
        guard revealedWordCount == EncryptionPhraseGenerator.wordCount,
              newPhraseWords.count == EncryptionPhraseGenerator.wordCount
        else {
            toast = .info("Wait until all 12 words appear")
            return
        }
        if EncryptionPhrasePasteboard.copy(newPhraseWords.joined(separator: " ")) {
            Haptics.notification(.success)
            toast = Toast("Encryption phrase copied")
        } else {
            Haptics.notification(.error)
            toast = .failure("Couldn’t copy phrase — try again")
        }
    }
}

#Preview {
    NavigationStack {
        LogInFlowView(router: AppRouter())
            .environment(SessionController())
            .environment(CryptoController())
    }
}
