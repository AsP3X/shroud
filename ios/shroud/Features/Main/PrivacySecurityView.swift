import SwiftUI

/// Privacy & security — lock chats, background wipe, vault explanation.
struct PrivacySecurityView: View {
    let router: AppRouter

    @Environment(CryptoController.self) private var crypto
    @Environment(MessagingController.self) private var messaging
    @Environment(\.dismiss) private var dismiss

    @State private var lockOnBackground = SecurityPreferences.lockChatsOnBackground
    @State private var toast: String?

    var body: some View {
        GroupedScreen {
            VStack(spacing: 0) {
                navRow

                ScrollView {
                    VStack(spacing: 14) {
                        settingsCard {
                            Toggle(isOn: $lockOnBackground) {
                                VStack(alignment: .leading, spacing: 4) {
                                    Text("Lock chats in background")
                                        .font(.system(size: 16))
                                        .foregroundStyle(Theme.textPrimary)
                                    Text(
                                        "When you leave the app, decrypted messages are cleared from memory. Tap the Face ID unlock icon on the welcome screen to re-open."
                                    )
                                    .font(.system(size: 13))
                                    .foregroundStyle(Theme.textSecondary)
                                    .fixedSize(horizontal: false, vertical: true)
                                }
                            }
                            .tint(Theme.accent)
                            .padding(.horizontal, 14)
                            .padding(.vertical, 12)
                            .onChange(of: lockOnBackground) { _, value in
                                SecurityPreferences.lockChatsOnBackground = value
                                Haptics.impact(.light)
                            }
                        }

                        settingsCard {
                            Toggle(isOn: Binding(
                                get: { SecurityPreferences.requireUserPresence },
                                set: { newValue in
                                    SecurityPreferences.requireUserPresence = newValue
                                    Haptics.impact(.light)
                                }
                            )) {
                                VStack(alignment: .leading, spacing: 4) {
                                    Text("Require Face ID / passcode")
                                        .font(.system(size: 16))
                                        .foregroundStyle(Theme.textPrimary)
                                    Text(
                                        "When on, the history wrap key asks for biometrics or device passcode. Turn off only on devices that cannot prompt (e.g. some simulators). Lock and unlock chats after changing so the vault re-wraps."
                                    )
                                    .font(.system(size: 13))
                                    .foregroundStyle(Theme.textSecondary)
                                    .fixedSize(horizontal: false, vertical: true)
                                }
                            }
                            .tint(Theme.accent)
                            .padding(.horizontal, 14)
                            .padding(.vertical, 12)
                        }

                        settingsCard {
                            Button {
                                lockChatsNow()
                            } label: {
                                HStack(spacing: 12) {
                                    ZStack {
                                        RoundedRectangle(cornerRadius: 8, style: .continuous)
                                            .fill(Theme.danger)
                                            .frame(width: 30, height: 30)
                                        Image(systemName: "lock.fill")
                                            .font(.system(size: 14, weight: .semibold))
                                            .foregroundStyle(Color.white)
                                    }
                                    VStack(alignment: .leading, spacing: 2) {
                                        Text("Lock chats now")
                                            .font(.system(size: 16))
                                            .foregroundStyle(Theme.textPrimary)
                                        Text("Leave the chat shell until you unlock again.")
                                            .font(.system(size: 13))
                                            .foregroundStyle(Theme.textSecondary)
                                    }
                                    Spacer(minLength: 0)
                                }
                                .padding(.horizontal, 14)
                                .padding(.vertical, 12)
                                .contentShape(Rectangle())
                            }
                            .buttonStyle(HighlightRowButtonStyle())
                        }

                        settingsCard {
                            VStack(alignment: .leading, spacing: 8) {
                                Label("Encrypted on this device", systemImage: "checkmark.shield.fill")
                                    .font(.system(size: 15, weight: .semibold))
                                    .foregroundStyle(Theme.accent)
                                Text(
                                    "Chat history is sealed with a key from your encryption phrase. That key is not kept in plain Keychain storage — it is wrapped and only unwrapped after you authenticate with biometrics, passcode, or your 12-word phrase."
                                )
                                .font(.system(size: 13))
                                .foregroundStyle(Theme.textSecondary)
                                .fixedSize(horizontal: false, vertical: true)
                            }
                            .padding(14)
                        }

                        Color.clear.frame(height: 24)
                    }
                    .padding(.horizontal, 16)
                    .padding(.top, 8)
                }
            }
        }
        .toast($toast)
    }

    private var navRow: some View {
        HStack {
            Button {
                dismiss()
            } label: {
                Image(systemName: "chevron.left")
                    .font(.system(size: 17, weight: .semibold))
                    .foregroundStyle(Theme.accent)
                    .frame(width: 44, height: 44, alignment: .leading)
                    .contentShape(Rectangle())
            }
            .pressable(scale: 0.88)
            .accessibilityLabel("Back")

            Spacer()

            Text("Privacy and Security")
                .font(.system(size: 17, weight: .semibold))
                .foregroundStyle(Theme.textPrimary)

            Spacer()

            Color.clear.frame(width: 44, height: 44)
        }
        .padding(.horizontal, 16)
        .padding(.bottom, 4)
    }

    private func lockChatsNow() {
        Haptics.notification(.warning)
        messaging.lockSensitiveMemory()
        messaging.stop(wipeDisk: false)
        crypto.lockHistoryInMemory()
        router.hasUnlockedMessaging = false
        toast = "Chats locked"
    }

    private func settingsCard<Content: View>(@ViewBuilder content: () -> Content) -> some View {
        VStack(spacing: 0) {
            content()
        }
        .background(Theme.background)
        .clipShape(RoundedRectangle(cornerRadius: 14, style: .continuous))
    }
}
