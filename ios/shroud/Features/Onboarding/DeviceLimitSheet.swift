import SwiftUI

// MARK: - State

/// A login that found every device slot signed in, waiting for the encryption phrase.
///
/// Human: The server answers `409 DEVICE_LIMIT` when every device slot of the account is signed
/// in, naming the least recently active device and the account's published identity key. The
/// login screen then moves on to the phrase step without a session. Only a phrase that derives
/// that key opens the "Log out your oldest device?" sheet, so a full account never costs a
/// device unless both the password and the phrase were right. The retry names the device shown;
/// if the server meanwhile picked another one (the first is gone and the account filled up
/// again), the sheet shows the new one instead of logging out a device the user never saw.
/// Agent: Memory only — the password and words live as long as the login screen, never on disk.
/// The caller's `retry` closure logs in. Driven by `LogInFlowView`; `DeviceLimitLoginTests`
/// covers every outcome.
@MainActor
@Observable
final class DeviceLimitConfirmation {
    enum Outcome: Equatable {
        /// Logged in; the sheet closed. Finish the phrase step with these (already checked) words.
        case loggedIn(words: [String])
        /// The server named a different device; the sheet stays open and shows it.
        case anotherDevice
        /// The retry's answer carries another identity key than the phrase derives: the sheet
        /// closed, and the phrase step shows its wrong-phrase error.
        case phraseMismatch
        /// Any other failure; the sheet closed and the message goes inline on the phrase step.
        case failed(String)
    }

    /// The credentials of the login that hit the limit, for the retry.
    private(set) var username = ""
    private(set) var password = ""
    /// The device on offer. Kept after the sheet closes, so it doesn't go blank sliding away.
    private(set) var device: OldestDeviceDTO?
    /// The account's published X25519 identity key, which the phrase has to derive.
    private(set) var identityKey: Data?
    /// The words that derived `identityKey`, handed back on `.loggedIn`.
    private(set) var verifiedWords: [String] = []
    private(set) var isPresented = false
    /// The retry is in flight: the sheet shows its busy button and can't be dismissed.
    private(set) var isLoggingOut = false

    /// A login is waiting for the phrase: there is no session yet.
    var isPending: Bool { identityKey != nil }

    /// The credentials step got `DEVICE_LIMIT`: remember the attempt, no sheet yet.
    func begin(_ limit: DeviceLimitError, username: String, password: String) {
        self.username = username
        self.password = password
        device = limit.oldestDevice
        identityKey = limit.identityKey
        verifiedWords = []
        isPresented = false
    }

    /// Back to the credentials step: the attempt is dropped.
    func reset() {
        guard !isLoggingOut else { return }
        username = ""
        password = ""
        device = nil
        identityKey = nil
        verifiedWords = []
        isPresented = false
    }

    /// The phrase step's submit while a login is pending. Opens the sheet only for words that
    /// derive the account's identity key; anything else throws the phrase step's usual errors
    /// (`BIP39Seed.SeedError`, `CryptoControllerError.phraseDoesNotMatchAccount`) and sends nothing.
    func checkPhrase(_ words: [String]) throws {
        guard let identityKey, !isLoggingOut else { return }
        let derived = try IdentityKeyMaterial.identityPublicKeyData(fromMnemonic: words)
        guard derived == identityKey else {
            throw CryptoControllerError.phraseDoesNotMatchAccount
        }
        verifiedWords = words
        isPresented = true
    }

    /// Cancel or a swipe down: back on the phrase step with the words in place. Ignored while
    /// the retry runs.
    func cancel() {
        guard !isLoggingOut else { return }
        isPresented = false
    }

    /// Logs in again with `retry(username, password, device.id)`, the id going out as
    /// `replace_device_id`. Nil when there is nothing to confirm or a retry is already running.
    func confirm(
        retry: (_ username: String, _ password: String, _ replacingDeviceID: UUID) async throws -> Void
    ) async -> Outcome? {
        guard isPresented, !isLoggingOut, let device, let identityKey else { return nil }
        isLoggingOut = true
        defer { isLoggingOut = false }
        do {
            try await retry(username, password, device.id)
            let words = verifiedWords
            // The session exists now; the phrase step finishes the normal way.
            isPresented = false
            username = ""
            password = ""
            self.identityKey = nil
            verifiedWords = []
            return .loggedIn(words: words)
        } catch let limit as DeviceLimitError {
            self.device = limit.oldestDevice
            self.identityKey = limit.identityKey
            guard limit.identityKey == identityKey else {
                isPresented = false
                verifiedWords = []
                return .phraseMismatch
            }
            return .anotherDevice
        } catch {
            isPresented = false
            return .failed(SessionController.userMessage(for: error))
        }
    }
}

// MARK: - Sheet

/// Asks before a login logs out the account's least recently used device. Shown over the
/// phrase step, once the phrase checked out.
///
/// Human: A sheet, not an alert: the device card doesn't fit in one. The device's name is
/// sealed with the 12-word phrase, which this device doesn't have yet, so the card shows the
/// grey "unknown" tile and the dates Settings › Devices shows. Same copy on web and Android.
/// Agent: Sized to its content (`presentationDetents` at the measured height; a fitted form
/// sheet on iPad). Dismissal is locked while `isLoggingOut`.
struct DeviceLimitSheet: View {
    let device: OldestDeviceDTO
    let isLoggingOut: Bool
    let onConfirm: () -> Void
    let onCancel: () -> Void

    /// Measured height of the content; the single detent follows it.
    @State private var contentHeight: CGFloat = 460

    var body: some View {
        ScrollView {
            content
                .onGeometryChange(for: CGFloat.self) { proxy in
                    proxy.size.height
                } action: { height in
                    contentHeight = height
                }
        }
        .scrollBounceBehavior(.basedOnSize)
        .frame(idealHeight: contentHeight)
        .background(Theme.backgroundGrouped)
        .presentationDetents([.height(contentHeight)])
        .presentationDragIndicator(.visible)
        // iPad: a form-width card as tall as its content, not a stretched phone sheet.
        .presentationSizing(.form.fitted(horizontal: false, vertical: true))
        .presentationBackground(Theme.backgroundGrouped)
        .interactiveDismissDisabled(isLoggingOut)
    }

    private var content: some View {
        VStack(spacing: 0) {
            Text("Log out your oldest device?")
                .font(.system(size: 22, weight: .bold))
                .foregroundStyle(Theme.textPrimary)
                .multilineTextAlignment(.center)
                .fixedSize(horizontal: false, vertical: true)
                .accessibilityAddTraits(.isHeader)

            Text(Self.message)
                .font(.system(size: 15))
                .foregroundStyle(Theme.textSecondary)
                .multilineTextAlignment(.center)
                .lineSpacing(2)
                .fixedSize(horizontal: false, vertical: true)
                .padding(.top, 8)

            deviceCard
                .padding(.top, 18)

            Text("It’s logged out right away and erases everything of your account on it: messages, keys and files. What it already sent stays in your chats.")
                .font(.system(size: 13))
                .foregroundStyle(Theme.textSecondary)
                .fixedSize(horizontal: false, vertical: true)
                .frame(maxWidth: .infinity, alignment: .leading)
                .padding(.horizontal, 14)
                .padding(.top, 8)

            PrimaryButton(
                title: isLoggingOut ? "Logging Out…" : "Log Out and Continue",
                showsArrow: false,
                isLoading: isLoggingOut,
                tint: Theme.danger,
                action: onConfirm
            )
            .padding(.top, 24)

            Button(action: onCancel) {
                Text("Cancel")
                    .font(.system(size: 17, weight: .semibold))
                    .foregroundStyle(Theme.accentText)
                    .frame(maxWidth: .infinity)
                    .frame(height: 44)
                    .contentShape(Rectangle())
            }
            .pressable(scale: 0.975, dimming: 0.06)
            .disabled(isLoggingOut)
            .opacity(isLoggingOut ? 0.45 : 1)
            .padding(.top, 8)
        }
        .padding(.horizontal, 20)
        // Clears the drag indicator above the title.
        .padding(.top, 32)
        .padding(.bottom, 12)
        .animation(Motion.snappy, value: device)
    }

    /// One row of Settings › Devices: the unknown-device tile, "Last active …" and "Linked …".
    private var deviceCard: some View {
        HStack(spacing: 12) {
            DeviceIconTile(label: nil, size: 30)
            VStack(alignment: .leading, spacing: 2) {
                Text(Self.lastActiveLine(for: device))
                    .font(.system(size: 16))
                    .foregroundStyle(Theme.textPrimary)
                    .lineLimit(1)
                Text(Self.linkedLine(for: device))
                    .font(.system(size: 13))
                    .foregroundStyle(Theme.textSecondary)
                    .lineLimit(1)
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            .contentTransition(.opacity)
        }
        .padding(.horizontal, 14)
        .padding(.vertical, 10)
        .background(Theme.background)
        .clipShape(RoundedRectangle(cornerRadius: 14, style: .continuous))
        .accessibilityElement(children: .combine)
    }

    static var message: String {
        "Your account is logged in on \(DevicesService.deviceLimit) devices, the most it can have. To log in here, Shroud logs out the one you used least recently:"
    }

    /// "Last active Yesterday" — the Devices list's own relative label — or "Never active".
    static func lastActiveLine(for device: OldestDeviceDTO) -> String {
        guard let lastSeen = device.lastSeenAt else { return "Never active" }
        return "Last active \(ChatListFormatting.timeLabel(for: lastSeen))"
    }

    /// "Linked 12 March 2025": date only, long style.
    static func linkedLine(for device: OldestDeviceDTO) -> String {
        "Linked \(device.createdAt.formatted(date: .long, time: .omitted))"
    }
}

#Preview {
    Color.clear
        .sheet(isPresented: .constant(true)) {
            DeviceLimitSheet(
                device: OldestDeviceDTO(
                    id: UUID(),
                    createdAt: .now.addingTimeInterval(-86400 * 200),
                    lastSeenAt: .now.addingTimeInterval(-86400 * 12)
                ),
                isLoggingOut: false,
                onConfirm: {},
                onCancel: {}
            )
        }
}
