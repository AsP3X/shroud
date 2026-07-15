import SwiftUI

/// Enter Encryption Phrase — maps to `Enter Encryption Phrase` in `iOS-App.pen`.
struct EnterEncryptionPhraseView: View {
    let router: AppRouter
    let username: String

    @State private var words: [String] = Array(repeating: "", count: 12)

    var body: some View {
        GroupedScreen {
            VStack(spacing: 0) {
                navRow

                ScrollView {
                    VStack(spacing: 16) {
                        signedInBanner
                        hero
                        phraseCard
                        privacyHint
                    }
                    .screenContent()
                    .padding(.top, 8)
                }

                VStack(spacing: 12) {
                    PrimaryButton(title: "Unlock Messages") {
                        router.unlockMessages()
                    }
                    Button("I lost my encryption phrase") {}
                        .font(.system(size: 14))
                        .foregroundStyle(Theme.accent)
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
        }
        .padding(.horizontal, 12)
        .padding(.vertical, 4)
    }

    private var signedInBanner: some View {
        HStack(spacing: 8) {
            ZStack {
                RoundedRectangle(cornerRadius: 6, style: .continuous)
                    .fill(Color.white)
                    .frame(width: 22, height: 22)
                Image(systemName: "checkmark")
                    .font(.system(size: 13, weight: .bold))
                    .foregroundStyle(Theme.online)
            }
            Text("Logged in as @\(username)")
                .font(.system(size: 13, weight: .semibold))
                .foregroundStyle(Theme.textPrimary)
            Spacer(minLength: 0)
        }
        .padding(.horizontal, 12)
        .padding(.vertical, 7)
        .background(Theme.successBackground)
        .clipShape(RoundedRectangle(cornerRadius: 10, style: .continuous))
    }

    private var hero: some View {
        VStack(spacing: 12) {
            ZStack {
                Circle()
                    .fill(Theme.accentSoft)
                    .frame(width: 56, height: 56)
                Image(systemName: "key.fill")
                    .font(.system(size: 26, weight: .semibold))
                    .foregroundStyle(Theme.accent)
            }

            VStack(spacing: 6) {
                Text("Enter Encryption Phrase")
                    .font(.system(size: 30, weight: .bold))
                    .foregroundStyle(Theme.textPrimary)
                Text("Step 2 of 2 — you logged back in, but your keys were removed when you logged out. Enter your 12-word phrase to decrypt your messages. This step is required.")
                    .font(.system(size: 14))
                    .foregroundStyle(Theme.textSecondary)
                    .multilineTextAlignment(.center)
                    .lineSpacing(4)
                    .frame(maxWidth: 310)
                Text("STEP 2 OF 2")
                    .font(.system(size: 11, weight: .semibold))
                    .kerning(0.8)
                    .foregroundStyle(Theme.accent)
                    .padding(.horizontal, 12)
                    .padding(.vertical, 6)
                    .background(Theme.accentSoft)
                    .clipShape(Capsule())
            }
        }
        .frame(maxWidth: .infinity)
    }

    private var phraseCard: some View {
        VStack(alignment: .leading, spacing: 10) {
            HStack {
                Text("ENCRYPTION PHRASE")
                    .font(.system(size: 11, weight: .semibold))
                    .kerning(0.8)
                    .foregroundStyle(Theme.textSecondary)
                Spacer()
                Button {} label: {
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

    private func phraseWordField(index: Int) -> some View {
        HStack(spacing: 6) {
            Text("\(index + 1).")
                .font(.system(size: 12, weight: .medium, design: .monospaced))
                .foregroundStyle(Theme.textSecondary)
            TextField("word", text: Binding(
                get: { words[index] },
                set: { words[index] = $0 }
            ))
            .font(.system(size: 14, weight: .medium, design: .monospaced))
            .textInputAutocapitalization(.never)
            .autocorrectionDisabled()
        }
        .padding(.horizontal, 12)
        .frame(height: 44)
        .background(Theme.backgroundGrouped)
        .clipShape(RoundedRectangle(cornerRadius: 10, style: .continuous))
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
    }
}

#Preview {
    NavigationStack {
        EnterEncryptionPhraseView(router: AppRouter(), username: "alex")
    }
}
