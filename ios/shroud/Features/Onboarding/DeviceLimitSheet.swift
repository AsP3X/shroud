import CryptoKit
import SwiftUI

// MARK: - State

/// A login that found every device slot signed in, waiting for the encryption phrase.
///
/// Human: The server answers `409 DEVICE_LIMIT` when every device slot of the account is signed
/// in, listing the account's devices (least recently active first) and its published identity
/// key. The login screen then moves on to the phrase step without a session. Only a phrase that
/// derives that key opens the "Log out your oldest device?" sheet, so a full account never costs
/// a device unless both the password and the phrase were right. The same phrase gives the history
/// key that opens the devices' sealed names, so the sheet can say which device it is, and the
/// user may pick another one instead. If the retry finds the account full again (the chosen
/// device is gone meanwhile), the sheet shows the new list instead of logging out a device the
/// user never saw.
/// Agent: Memory only — the password, words, history key and names live as long as the login
/// screen, never on disk or the wire. The caller's `retry` closure logs in. Driven by
/// `LogInFlowView`; `DeviceLimitLoginTests` covers every outcome.
@MainActor
@Observable
final class DeviceLimitConfirmation {
    enum Outcome: Equatable {
        /// Logged in; the sheet closed. Finish the phrase step with these (already checked) words.
        case loggedIn(words: [String])
        /// The account is full again with another list; the sheet stays open and shows it.
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
    /// Every device of the account, least recently active first. Kept after the sheet closes,
    /// so it doesn't go blank sliding away.
    private(set) var devices: [DeviceLimitDeviceDTO] = []
    /// The device "Log Out and Continue" logs out; the oldest unless the user chose another.
    private(set) var selectedID: UUID?
    /// The devices' names, opened with the phrase's history key once the phrase checked out.
    private(set) var labels: [UUID: DeviceNameSeal.Label] = [:]
    /// The account's published X25519 identity key, which the phrase has to derive.
    private(set) var identityKey: Data?
    /// The words that derived `identityKey`, handed back on `.loggedIn`.
    private(set) var verifiedWords: [String] = []
    private(set) var isPresented = false
    /// The device picker is up, on top of the sheet.
    private(set) var isChoosing = false
    /// The retry is in flight: the sheet shows its busy button and can't be dismissed.
    private(set) var isLoggingOut = false
    /// From the checked phrase; opens the names again when a retry brings a new list.
    private var historyKey: SymmetricKey?

    /// A login is waiting for the phrase: there is no session yet.
    var isPending: Bool { identityKey != nil }

    var selectedDevice: DeviceLimitDeviceDTO? {
        devices.first { $0.id == selectedID } ?? devices.first
    }

    /// The sheet's title follows this: "oldest device" only while it is the oldest.
    var isOldestSelected: Bool {
        guard let selected = selectedDevice else { return true }
        return selected.id == devices.first?.id
    }

    /// "Choose Another Device" only when there is another one.
    var canChooseAnother: Bool { devices.count > 1 }

    /// The credentials step got `DEVICE_LIMIT`: remember the attempt, no sheet yet.
    func begin(_ limit: DeviceLimitError, username: String, password: String) {
        self.username = username
        self.password = password
        devices = limit.devices
        selectedID = limit.devices.first?.id
        identityKey = limit.identityKey
        labels = [:]
        historyKey = nil
        verifiedWords = []
        isChoosing = false
        isPresented = false
    }

    /// Back to the credentials step: the attempt is dropped.
    func reset() {
        guard !isLoggingOut else { return }
        username = ""
        password = ""
        devices = []
        selectedID = nil
        labels = [:]
        historyKey = nil
        identityKey = nil
        verifiedWords = []
        isChoosing = false
        isPresented = false
    }

    /// The phrase step's submit while a login is pending. Opens the sheet only for words that
    /// derive the account's identity key, and opens the device names with them; anything else
    /// throws the phrase step's usual errors (`BIP39Seed.SeedError`,
    /// `CryptoControllerError.phraseDoesNotMatchAccount`) and sends nothing.
    func checkPhrase(_ words: [String]) throws {
        guard let identityKey, !isLoggingOut else { return }
        let derived = try IdentityKeyMaterial.identityPublicKeyData(fromMnemonic: words)
        guard derived == identityKey else {
            throw CryptoControllerError.phraseDoesNotMatchAccount
        }
        let key = try IdentityKeyMaterial.historyKey(fromMnemonic: words)
        historyKey = key
        labels = Self.openNames(of: devices, historyKey: key)
        verifiedWords = words
        isChoosing = false
        isPresented = true
    }

    /// The device's opened name and kind; nil when unnamed or not opened ("Unnamed device").
    func label(for device: DeviceLimitDeviceDTO) -> DeviceNameSeal.Label? {
        labels[device.id]
    }

    /// "Choose Another Device". Ignored while the retry runs.
    func chooseAnother() {
        guard isPresented, canChooseAnother, !isLoggingOut else { return }
        isChoosing = true
    }

    /// A row of the picker: that device is the one to log out; back to the sheet.
    func select(_ deviceID: UUID) {
        guard !isLoggingOut, devices.contains(where: { $0.id == deviceID }) else { return }
        selectedID = deviceID
        isChoosing = false
    }

    /// The picker's Cancel or a swipe down: back to the sheet, selection unchanged.
    func stopChoosing() {
        isChoosing = false
    }

    /// Cancel or a swipe down: back on the phrase step with the words in place. Ignored while
    /// the retry runs.
    func cancel() {
        guard !isLoggingOut else { return }
        isChoosing = false
        isPresented = false
    }

    /// Logs in again with `retry(username, password, selected.id)`, the id going out as
    /// `replace_device_id`. Nil when there is nothing to confirm or a retry is already running.
    func confirm(
        retry: (_ username: String, _ password: String, _ replacingDeviceID: UUID) async throws -> Void
    ) async -> Outcome? {
        guard isPresented, !isLoggingOut, let device = selectedDevice, let identityKey else { return nil }
        isLoggingOut = true
        isChoosing = false
        defer { isLoggingOut = false }
        do {
            try await retry(username, password, device.id)
            let words = verifiedWords
            // The session exists now; the phrase step finishes the normal way.
            isPresented = false
            username = ""
            password = ""
            self.identityKey = nil
            historyKey = nil
            verifiedWords = []
            return .loggedIn(words: words)
        } catch let limit as DeviceLimitError {
            self.identityKey = limit.identityKey
            guard limit.identityKey == identityKey, let historyKey else {
                devices = limit.devices
                selectedID = limit.devices.first?.id
                labels = [:]
                self.historyKey = nil
                isPresented = false
                verifiedWords = []
                return .phraseMismatch
            }
            // Keep the user's choice while it is still on the account, else the new oldest.
            let keepsSelection = limit.devices.contains { $0.id == device.id }
            devices = limit.devices
            selectedID = keepsSelection ? device.id : limit.devices.first?.id
            labels = Self.openNames(of: limit.devices, historyKey: historyKey)
            return .anotherDevice
        } catch {
            isPresented = false
            return .failed(SessionController.userMessage(for: error))
        }
    }

    private static func openNames(
        of devices: [DeviceLimitDeviceDTO],
        historyKey: SymmetricKey
    ) -> [UUID: DeviceNameSeal.Label] {
        var labels: [UUID: DeviceNameSeal.Label] = [:]
        for device in devices {
            labels[device.id] = DeviceNameSeal.open(device.sealedName, deviceID: device.id, historyKey: historyKey)
        }
        return labels
    }
}

// MARK: - Sheet

/// Asks before a login logs out one of the account's devices — the least recently used one,
/// unless the user chooses another. Shown over the phrase step, once the phrase checked out.
///
/// Human: A sheet, not an alert: the device card doesn't fit in one. The card is a row of
/// Settings › Devices — the device's tile, name and dates — opened with the phrase just entered.
/// "Choose Another Device" stacks the picker on top. Same copy on web and Android.
/// Agent: Sized to its content (`presentationDetents` at the measured height; a fitted form
/// sheet on iPad). Dismissal is locked while `isLoggingOut`.
struct DeviceLimitSheet: View {
    let confirmation: DeviceLimitConfirmation
    let onConfirm: () -> Void

    private var isLoggingOut: Bool { confirmation.isLoggingOut }

    var body: some View {
        content
            .contentSizedSheet(dismissDisabled: isLoggingOut)
            .sheet(isPresented: Binding(
                get: { confirmation.isChoosing },
                set: { if !$0 { confirmation.stopChoosing() } }
            )) {
                DeviceLimitPicker(confirmation: confirmation)
            }
    }

    private var content: some View {
        VStack(spacing: 0) {
            Text(Self.title(oldest: confirmation.isOldestSelected))
                .font(.system(size: 22, weight: .bold))
                .foregroundStyle(Theme.textPrimary)
                .multilineTextAlignment(.center)
                .fixedSize(horizontal: false, vertical: true)
                .contentTransition(.opacity)
                .accessibilityAddTraits(.isHeader)

            Text(Self.message)
                .font(.system(size: 15))
                .foregroundStyle(Theme.textSecondary)
                .multilineTextAlignment(.center)
                .lineSpacing(2)
                .fixedSize(horizontal: false, vertical: true)
                .padding(.top, 8)

            if let device = confirmation.selectedDevice {
                deviceCard(device)
                    .padding(.top, 18)
            }

            if confirmation.canChooseAnother {
                Button {
                    confirmation.chooseAnother()
                } label: {
                    Text("Choose Another Device")
                        .font(.system(size: 15, weight: .semibold))
                        .foregroundStyle(Theme.accentText)
                        .frame(maxWidth: .infinity)
                        .frame(height: 40)
                        .contentShape(Rectangle())
                }
                .pressable()
                .disabled(isLoggingOut)
                .opacity(isLoggingOut ? 0.45 : 1)
                .padding(.top, 4)
            }

            Text("It’s logged out right away and erases everything of your account on it: messages, keys and files. What it already sent stays in your chats.")
                .font(.system(size: 13))
                .foregroundStyle(Theme.textSecondary)
                .fixedSize(horizontal: false, vertical: true)
                .frame(maxWidth: .infinity, alignment: .leading)
                .padding(.horizontal, 14)
                .padding(.top, confirmation.canChooseAnother ? 4 : 8)

            PrimaryButton(
                title: isLoggingOut ? "Logging Out…" : "Log Out and Continue",
                showsArrow: false,
                isLoading: isLoggingOut,
                tint: Theme.danger,
                action: onConfirm
            )
            .padding(.top, 24)

            Button {
                confirmation.cancel()
            } label: {
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
        .animation(Motion.snappy, value: confirmation.selectedDevice)
    }

    /// One row of Settings › Devices: the device's tile, its name, "Last active … · Linked …".
    private func deviceCard(_ device: DeviceLimitDeviceDTO) -> some View {
        let label = confirmation.label(for: device)
        return HStack(spacing: 12) {
            DeviceIconTile(label: label, size: 30)
            VStack(alignment: .leading, spacing: 2) {
                Text(DevicesView.displayName(label))
                    .font(.system(size: 16))
                    .foregroundStyle(Theme.textPrimary)
                    .lineLimit(1)
                Text(Self.datesLine(for: device))
                    .font(.system(size: 13))
                    .foregroundStyle(Theme.textSecondary)
                    .lineLimit(2)
                    .fixedSize(horizontal: false, vertical: true)
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

    static func title(oldest: Bool) -> String {
        oldest ? "Log out your oldest device?" : "Log out this device?"
    }

    static var message: String {
        "Your account is logged in on \(DevicesService.deviceLimit) devices, the most it can have. To log in here, Shroud logs out:"
    }

    /// "Last active Yesterday" — the Devices list's own relative label — or "Never active".
    static func lastActiveLine(for device: DeviceLimitDeviceDTO) -> String {
        guard let lastSeen = device.lastSeenAt else { return "Never active" }
        return "Last active \(ChatListFormatting.timeLabel(for: lastSeen))"
    }

    /// "Linked 12 March 2025": date only, long style.
    static func linkedLine(for device: DeviceLimitDeviceDTO) -> String {
        "Linked \(device.createdAt.formatted(date: .long, time: .omitted))"
    }

    /// The card's second line: "Last active Yesterday · Linked 12 March 2025".
    static func datesLine(for device: DeviceLimitDeviceDTO) -> String {
        "\(lastActiveLine(for: device)) · \(linkedLine(for: device))"
    }
}

// MARK: - Picker

/// "Choose a device to log out": every device of the account, least recently used first.
///
/// Human: Stacked on the sheet like a second dialog. A tap picks the device and goes straight
/// back to the sheet, which still asks before anything is logged out.
/// Agent: Rows styled like Settings › Devices (`DevicesView.deviceRow`); selection lives in
/// `DeviceLimitConfirmation`.
private struct DeviceLimitPicker: View {
    let confirmation: DeviceLimitConfirmation

    var body: some View {
        VStack(spacing: 0) {
            Text("Choose a device to log out")
                .font(.system(size: 22, weight: .bold))
                .foregroundStyle(Theme.textPrimary)
                .multilineTextAlignment(.center)
                .fixedSize(horizontal: false, vertical: true)
                .accessibilityAddTraits(.isHeader)

            Text("Least recently used first.")
                .font(.system(size: 15))
                .foregroundStyle(Theme.textSecondary)
                .multilineTextAlignment(.center)
                .padding(.top, 8)

            VStack(spacing: 0) {
                ForEach(Array(confirmation.devices.enumerated()), id: \.element.id) { index, device in
                    if index > 0 {
                        Rectangle()
                            .fill(Theme.separator)
                            .frame(height: 1)
                            .padding(.leading, 56)
                    }
                    row(device, isOldest: index == 0)
                }
            }
            .background(Theme.background)
            .clipShape(RoundedRectangle(cornerRadius: 14, style: .continuous))
            .padding(.top, 18)

            Button {
                confirmation.stopChoosing()
            } label: {
                Text("Cancel")
                    .font(.system(size: 17, weight: .semibold))
                    .foregroundStyle(Theme.accentText)
                    .frame(maxWidth: .infinity)
                    .frame(height: 44)
                    .contentShape(Rectangle())
            }
            .pressable(scale: 0.975, dimming: 0.06)
            .padding(.top, 16)
        }
        .padding(.horizontal, 20)
        .padding(.top, 32)
        .padding(.bottom, 12)
        .contentSizedSheet(dismissDisabled: false)
    }

    private func row(_ device: DeviceLimitDeviceDTO, isOldest: Bool) -> some View {
        let label = confirmation.label(for: device)
        let name = DevicesView.displayName(label)
        let selected = device.id == confirmation.selectedDevice?.id
        let lastActive = DeviceLimitSheet.lastActiveLine(for: device)
        return Button {
            confirmation.select(device.id)
        } label: {
            HStack(spacing: 12) {
                DeviceIconTile(label: label, size: 30)
                VStack(alignment: .leading, spacing: 2) {
                    HStack(alignment: .firstTextBaseline, spacing: 6) {
                        Text(name)
                            .font(.system(size: 16))
                            .foregroundStyle(Theme.textPrimary)
                            .lineLimit(1)
                        if isOldest {
                            Text("Oldest")
                                .font(.system(size: 13))
                                .foregroundStyle(Theme.textSecondary)
                                .fixedSize()
                        }
                    }
                    Text(lastActive)
                        .font(.system(size: 13))
                        .foregroundStyle(Theme.textSecondary)
                        .lineLimit(1)
                }
                .frame(maxWidth: .infinity, alignment: .leading)
                if selected {
                    Image(systemName: "checkmark")
                        .font(.system(size: 15, weight: .semibold))
                        .foregroundStyle(Theme.accent)
                }
            }
            .padding(.horizontal, 14)
            .padding(.vertical, 10)
            .contentShape(Rectangle())
        }
        .buttonStyle(HighlightRowButtonStyle())
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(isOldest ? "\(name), oldest, \(lastActive)" : "\(name), \(lastActive)")
        .accessibilityAddTraits(selected ? [.isButton, .isSelected] : .isButton)
    }
}

// MARK: - Sizing

private extension View {
    /// A sheet as tall as its content: one detent at the measured height, drag indicator, and a
    /// fitted form sheet on iPad. Scrolls when the content outgrows the screen (large text).
    func contentSizedSheet(dismissDisabled: Bool) -> some View {
        modifier(ContentSizedSheet(dismissDisabled: dismissDisabled))
    }
}

private struct ContentSizedSheet: ViewModifier {
    let dismissDisabled: Bool

    /// Measured height of the content; the single detent follows it.
    @State private var contentHeight: CGFloat = 460

    func body(content: Content) -> some View {
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
        .interactiveDismissDisabled(dismissDisabled)
    }
}

#Preview {
    let words = EncryptionPhraseGenerator.generate()
    let confirmation = DeviceLimitConfirmation()
    if let key = try? IdentityKeyMaterial.identityPublicKeyData(fromMnemonic: words) {
        confirmation.begin(
            DeviceLimitError(
                devices: [
                    DeviceLimitDeviceDTO(
                        id: UUID(),
                        createdAt: .now.addingTimeInterval(-86400 * 200),
                        lastSeenAt: .now.addingTimeInterval(-86400 * 12)
                    ),
                    DeviceLimitDeviceDTO(id: UUID(), createdAt: .now.addingTimeInterval(-86400 * 90), lastSeenAt: nil),
                ],
                identityKey: key,
                message: ""
            ),
            username: "preview",
            password: ""
        )
        try? confirmation.checkPhrase(words)
    }
    return Color.clear
        .sheet(isPresented: .constant(true)) {
            DeviceLimitSheet(confirmation: confirmation, onConfirm: {})
        }
}
