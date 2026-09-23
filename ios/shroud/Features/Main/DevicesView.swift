import SwiftUI

/// Settings → Devices: every device linked to the account, with a way to remove the others.
///
/// Human: Same data and rules as the web client's Devices page — this device can't be
/// removed here (that's Log Out), every other one can, one at a time or all at once. The
/// account holds at most `DevicesService.deviceLimit` devices, so this is also where you make
/// room for a new one.
/// Agent: CALLS DevicesService (GET/DELETE `/devices`); READS SessionController for the token
/// and this device's id; 401s are counted by `SessionAuthBridge` like every other request.
struct DevicesView: View {
    /// Reports the device count back to the Settings row after every load or removal.
    var onCount: ((Int) -> Void)? = nil

    @Environment(SessionController.self) private var sessionController
    @Environment(\.dismiss) private var dismiss

    @State private var devices: [LinkedDeviceDTO]?
    @State private var loadError: String?
    /// Last removal failure, shown under the list (the toast only confirms successes).
    @State private var actionError: String?
    @State private var revokingIDs: Set<UUID> = []
    @State private var isRevokingAll = false
    @State private var pendingRevoke: LinkedDeviceDTO?
    @State private var showRevokeAllConfirm = false
    @State private var detailDevice: LinkedDeviceDTO?
    /// Remove tapped in the detail sheet; the confirmation waits until the sheet is gone,
    /// since an alert presented mid-dismissal is dropped.
    @State private var revokeAfterSheet: LinkedDeviceDTO?
    @State private var toast: String?

    private let service = DevicesService()

    private var currentDevice: LinkedDeviceDTO? {
        devices?.first { isCurrent($0) }
    }

    /// Most recently active first, so a stale device sinks to the bottom.
    private var otherDevices: [LinkedDeviceDTO] {
        (devices ?? [])
            .filter { !isCurrent($0) }
            .sorted { ($0.lastSeenAt ?? $0.createdAt) > ($1.lastSeenAt ?? $1.createdAt) }
    }

    var body: some View {
        GroupedScreen {
            VStack(spacing: 0) {
                navRow

                ScrollView {
                    VStack(alignment: .leading, spacing: 0) {
                        if let devices {
                            loadedContent(devices)
                        } else {
                            loadingCard
                        }
                        Color.clear.frame(height: 24)
                    }
                    .padding(.horizontal, 16)
                    .padding(.top, 8)
                }
                .refreshable { await load() }
            }
        }
        .navigationBarBackButtonHidden(true)
        .toolbar(.hidden, for: .navigationBar)
        .toast($toast)
        .task { await load() }
        .alert(
            "Remove \(pendingRevoke.map(displayName) ?? "this device")?",
            isPresented: Binding(
                get: { pendingRevoke != nil },
                set: { if !$0 { pendingRevoke = nil } }
            ),
            presenting: pendingRevoke
        ) { device in
            Button("Remove", role: .destructive) { revoke(device) }
            Button("Cancel", role: .cancel) {}
        } message: { _ in
            Text(Self.revokeConsequences(plural: false))
        }
        .alert(
            "Remove all other devices?",
            isPresented: $showRevokeAllConfirm
        ) {
            Button("Remove \(otherDevices.count)", role: .destructive) { revokeAllOthers() }
            Button("Cancel", role: .cancel) {}
        } message: {
            Text(
                "Only this \(UIDevice.current.model) stays signed in. "
                    + Self.revokeConsequences(plural: true)
            )
        }
        .sheet(item: $detailDevice, onDismiss: {
            pendingRevoke = revokeAfterSheet
            revokeAfterSheet = nil
        }) { device in
            DeviceDetailSheet(
                device: device,
                isCurrent: isCurrent(device),
                isRevoking: revokingIDs.contains(device.id) || isRevokingAll,
                onCopyID: { copyID(of: device) },
                onRevoke: {
                    revokeAfterSheet = device
                    detailDevice = nil
                }
            )
            .presentationDetents([.medium, .large])
            .presentationDragIndicator(.visible)
        }
    }

    /// Shared by both confirmations — what a removal actually does, server side included.
    ///
    /// Human: The device row carries the messages it sent and the files it uploaded, so the
    /// server deletes those with it. Chats already stored on a phone keep them; a fresh
    /// device loading history from the server won't see them. Said here so nobody is surprised.
    private static func revokeConsequences(plural: Bool) -> String {
        plural
            ? "They are signed out right away and stop receiving messages. Messages and files sent from them are deleted from the server, so they vanish from chat history wherever they aren't already stored. Signing in there again takes your password and 12-word phrase."
            : "It is signed out right away and stops receiving messages. Messages and files sent from it are deleted from the server, so they vanish from chat history wherever they aren't already stored. Signing in there again takes your password and 12-word phrase."
    }

    /// A 404 means the device is already gone (removed elsewhere meanwhile) — the goal is met.
    private static func isAlreadyRemoved(_ error: Error) -> Bool {
        if case let .server(_, _, statusCode) = error as? APIError { return statusCode == 404 }
        return false
    }

    // MARK: - Content

    @ViewBuilder
    private func loadedContent(_ devices: [LinkedDeviceDTO]) -> some View {
        sectionHeader("This device")
        if let currentDevice {
            settingsCard {
                deviceRow(currentDevice)
            }
        } else {
            // The server always lists the calling device; a miss means the list is stale.
            settingsCard {
                Text("This \(UIDevice.current.model) is missing from the list. Pull to refresh.")
                    .font(.system(size: 14))
                    .foregroundStyle(Theme.textSecondary)
                    .padding(14)
                    .frame(maxWidth: .infinity, alignment: .leading)
            }
        }

        if !otherDevices.isEmpty {
            settingsCard {
                revokeAllButton
            }
            .padding(.top, 14)
            sectionFooter("Signs out every device except this \(UIDevice.current.model).")
        }

        sectionHeader(otherDevices.isEmpty ? "Other devices" : "Other devices — \(otherDevices.count)")
            .padding(.top, otherDevices.isEmpty ? 14 : 8)
        settingsCard {
            if otherDevices.isEmpty {
                VStack(alignment: .leading, spacing: 4) {
                    Text("No other devices")
                        .font(.system(size: 16))
                        .foregroundStyle(Theme.textPrimary)
                    Text(
                        "To add one, sign in on the web or another phone with your username and password, then unlock with your 12-word phrase."
                    )
                    .font(.system(size: 13))
                    .foregroundStyle(Theme.textSecondary)
                    .fixedSize(horizontal: false, vertical: true)
                }
                .padding(.horizontal, 14)
                .padding(.vertical, 12)
                .frame(maxWidth: .infinity, alignment: .leading)
            } else {
                ForEach(otherDevices) { device in
                    deviceRow(device)
                    if device.id != otherDevices.last?.id {
                        divider
                    }
                }
            }
        }

        if let actionError {
            Text(actionError)
                .font(.system(size: 13))
                .foregroundStyle(Theme.danger)
                .padding(.horizontal, 14)
                .padding(.top, 8)
                .fixedSize(horizontal: false, vertical: true)
        }

        sectionHeader("Device limit")
            .padding(.top, 8)
        settingsCard {
            capacityRow(count: devices.count)
        }
        sectionFooter(capacityFooter(count: devices.count))

        settingsCard {
            VStack(alignment: .leading, spacing: 8) {
                Label("Your phrase stays on each device", systemImage: "checkmark.shield.fill")
                    .font(.system(size: 15, weight: .semibold))
                    .foregroundStyle(Theme.accent)
                Text(
                    "Every device unlocks with your 12-word phrase, which never leaves it. For each device the server records only the name it signed in with, when it was linked and when it was last active — all shown here. Removing a device does not erase what is already stored on it."
                )
                .font(.system(size: 13))
                .foregroundStyle(Theme.textSecondary)
                .fixedSize(horizontal: false, vertical: true)
            }
            .padding(14)
            .frame(maxWidth: .infinity, alignment: .leading)
        }
        .padding(.top, 14)
    }

    private var loadingCard: some View {
        settingsCard {
            VStack(spacing: 10) {
                if let loadError {
                    Image(systemName: "exclamationmark.triangle.fill")
                        .font(.system(size: 22))
                        .foregroundStyle(Theme.warningIcon)
                    Text(loadError)
                        .font(.system(size: 14))
                        .foregroundStyle(Theme.textSecondary)
                        .multilineTextAlignment(.center)
                    Button("Try Again") {
                        self.loadError = nil
                        Task { await load() }
                    }
                    .font(.system(size: 15, weight: .semibold))
                    .foregroundStyle(Theme.accent)
                } else {
                    ProgressView()
                    Text("Loading devices…")
                        .font(.system(size: 14))
                        .foregroundStyle(Theme.textSecondary)
                }
            }
            .frame(maxWidth: .infinity)
            .padding(.vertical, 24)
            .padding(.horizontal, 14)
        }
        .padding(.top, 8)
    }

    private func deviceRow(_ device: LinkedDeviceDTO) -> some View {
        let current = isCurrent(device)
        let revoking = revokingIDs.contains(device.id)
        return HStack(spacing: 12) {
            Button {
                Haptics.impact(.light)
                detailDevice = device
            } label: {
                HStack(spacing: 12) {
                    DeviceIconTile(name: device.name, size: 30)
                    VStack(alignment: .leading, spacing: 2) {
                        Text(displayName(device))
                            .font(.system(size: 16))
                            .foregroundStyle(Theme.textPrimary)
                            .lineLimit(1)
                        statusLine(device, current: current)
                    }
                    .frame(maxWidth: .infinity, alignment: .leading)
                    if current {
                        Image(systemName: "chevron.right")
                            .font(.system(size: 13, weight: .semibold))
                            .foregroundStyle(Color(red: 199 / 255, green: 199 / 255, blue: 204 / 255))
                    }
                }
                .contentShape(Rectangle())
            }
            .buttonStyle(.plain)

            if !current {
                if revoking || isRevokingAll {
                    ProgressView()
                        .controlSize(.small)
                        .frame(minWidth: 60)
                } else {
                    Button("Remove") {
                        Haptics.impact(.light)
                        pendingRevoke = device
                    }
                    .font(.system(size: 15, weight: .medium))
                    .foregroundStyle(Theme.danger)
                    .accessibilityLabel("Remove \(displayName(device))")
                }
            }
        }
        .padding(.horizontal, 14)
        .padding(.vertical, 10)
    }

    @ViewBuilder
    private func statusLine(_ device: LinkedDeviceDTO, current: Bool) -> some View {
        if current {
            HStack(spacing: 5) {
                Circle()
                    .fill(Theme.online)
                    .frame(width: 7, height: 7)
                Text("Active now · This \(UIDevice.current.model)")
                    .font(.system(size: 13))
                    .foregroundStyle(Theme.accent)
            }
        } else {
            Text(Self.lastActiveLabel(for: device))
                .font(.system(size: 13))
                .foregroundStyle(Theme.textSecondary)
                .lineLimit(1)
        }
    }

    private var revokeAllButton: some View {
        Button {
            Haptics.impact(.light)
            showRevokeAllConfirm = true
        } label: {
            HStack(spacing: 12) {
                ZStack {
                    RoundedRectangle(cornerRadius: 8, style: .continuous)
                        .fill(Theme.danger)
                        .frame(width: 30, height: 30)
                    Image(systemName: "hand.raised.fill")
                        .font(.system(size: 14, weight: .semibold))
                        .foregroundStyle(Color.white)
                }
                Text(isRevokingAll ? "Removing other devices…" : "Remove All Other Devices")
                    .font(.system(size: 16))
                    .foregroundStyle(Theme.danger)
                    .frame(maxWidth: .infinity, alignment: .leading)
                if isRevokingAll {
                    ProgressView().controlSize(.small)
                }
            }
            .padding(.horizontal, 14)
            .padding(.vertical, 10)
            .contentShape(Rectangle())
        }
        .buttonStyle(HighlightRowButtonStyle())
        .disabled(isRevokingAll || !revokingIDs.isEmpty)
    }

    private func capacityRow(count: Int) -> some View {
        let limit = DevicesService.deviceLimit
        let full = count >= limit
        return VStack(alignment: .leading, spacing: 10) {
            HStack {
                Text("Linked devices")
                    .font(.system(size: 16))
                    .foregroundStyle(Theme.textPrimary)
                Spacer()
                Text("\(count) of \(limit)")
                    .font(.system(size: 16).monospacedDigit())
                    .foregroundStyle(full ? Theme.warningIcon : Theme.textSecondary)
            }
            HStack(spacing: 4) {
                ForEach(0 ..< limit, id: \.self) { index in
                    Capsule()
                        .fill(index < count ? (full ? Theme.warningIcon : Theme.accent) : Theme.separator)
                        .frame(height: 5)
                }
            }
            .accessibilityHidden(true)
        }
        .padding(.horizontal, 14)
        .padding(.vertical, 12)
        .accessibilityElement(children: .combine)
    }

    /// Human: At the cap the server doesn't refuse outright — it hands the new sign-in the
    /// longest-idle device nobody is signed in on, and refuses only when all of them are live.
    private func capacityFooter(count: Int) -> String {
        let limit = DevicesService.deviceLimit
        if count >= limit {
            return "Your account is at the limit. A new sign-in takes over a device that has been logged out; if every device is still signed in, it is refused until you remove one here."
        }
        let left = limit - count
        return "You can sign in on \(left) more \(left == 1 ? "device" : "devices"). A logged-out device stays listed until it signs in again or you remove it."
    }

    // MARK: - Chrome

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

            Text("Devices")
                .font(.system(size: 17, weight: .semibold))
                .foregroundStyle(Theme.textPrimary)

            Spacer()

            Color.clear.frame(width: 44, height: 44)
        }
        .padding(.horizontal, 16)
        .padding(.bottom, 4)
    }

    private func sectionHeader(_ title: String) -> some View {
        Text(title.uppercased())
            .font(.system(size: 13))
            .foregroundStyle(Theme.textSecondary)
            .padding(.horizontal, 14)
            .padding(.bottom, 6)
            .padding(.top, 6)
    }

    private func sectionFooter(_ text: String) -> some View {
        Text(text)
            .font(.system(size: 13))
            .foregroundStyle(Theme.textSecondary)
            .fixedSize(horizontal: false, vertical: true)
            .padding(.horizontal, 14)
            .padding(.top, 6)
            .padding(.bottom, 8)
    }

    private var divider: some View {
        Rectangle()
            .fill(Theme.separator)
            .frame(height: 1)
            .padding(.leading, 56)
    }

    private func settingsCard<Content: View>(@ViewBuilder content: () -> Content) -> some View {
        VStack(spacing: 0) {
            content()
        }
        .background(Theme.background)
        .clipShape(RoundedRectangle(cornerRadius: 14, style: .continuous))
    }

    // MARK: - Actions

    private func isCurrent(_ device: LinkedDeviceDTO) -> Bool {
        device.isCurrent || device.id == sessionController.session?.deviceID
    }

    private func displayName(_ device: LinkedDeviceDTO) -> String {
        Self.displayName(device)
    }

    static func displayName(_ device: LinkedDeviceDTO) -> String {
        let trimmed = device.name?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
        return trimmed.isEmpty ? "Unnamed device" : trimmed
    }

    static func lastActiveLabel(for device: LinkedDeviceDTO) -> String {
        if let lastSeen = device.lastSeenAt {
            return "Last active \(ChatListFormatting.timeLabel(for: lastSeen))"
        }
        return "Linked \(ChatListFormatting.timeLabel(for: device.createdAt))"
    }

    private func load() async {
        guard let token = sessionController.bearerToken else {
            loadError = "Sign in to see your devices."
            return
        }
        do {
            let list = try await service.list(token: token)
            if devices != list { devices = list }
            loadError = nil
            onCount?(list.count)
        } catch {
            // Pull-to-refresh released early or the screen closed: URLSession reports that as
            // a transport error, which is not worth showing.
            if Task.isCancelled { return }
            let message = SessionController.userMessage(for: error)
            if devices == nil {
                loadError = message
            } else {
                actionError = message
            }
        }
    }

    private func revoke(_ device: LinkedDeviceDTO) {
        guard !isCurrent(device), let token = sessionController.bearerToken else { return }
        pendingRevoke = nil
        actionError = nil
        revokingIDs.insert(device.id)
        Task {
            defer { revokingIDs.remove(device.id) }
            do {
                try await service.revoke(deviceID: device.id, token: token)
                removeLocally([device.id])
                Haptics.notification(.success)
                showToast("\(displayName(device)) removed")
            } catch where Self.isAlreadyRemoved(error) {
                removeLocally([device.id])
                showToast("\(displayName(device)) was already removed")
            } catch {
                actionError = SessionController.userMessage(for: error)
                Haptics.notification(.error)
            }
            await load()
        }
    }

    /// No bulk endpoint on the server — one DELETE per device, carrying on past failures so a
    /// single stale row doesn't leave the rest signed in.
    private func revokeAllOthers() {
        guard let token = sessionController.bearerToken else { return }
        let targets = otherDevices
        guard !targets.isEmpty else { return }
        actionError = nil
        isRevokingAll = true
        Task {
            var removed: [UUID] = []
            var lastError: Error?
            for device in targets {
                do {
                    try await service.revoke(deviceID: device.id, token: token)
                    removed.append(device.id)
                } catch where Self.isAlreadyRemoved(error) {
                    removed.append(device.id)
                } catch {
                    lastError = error
                }
            }
            removeLocally(removed)
            isRevokingAll = false
            if let lastError {
                let failed = targets.count - removed.count
                actionError = "\(failed) of \(targets.count) devices could not be removed. "
                    + SessionController.userMessage(for: lastError)
                Haptics.notification(.error)
            } else {
                Haptics.notification(.success)
                showToast(removed.count == 1 ? "1 device removed" : "\(removed.count) devices removed")
            }
            await load()
        }
    }

    private func removeLocally(_ ids: [UUID]) {
        guard !ids.isEmpty, var list = devices else { return }
        list.removeAll { ids.contains($0.id) }
        devices = list
        onCount?(list.count)
    }

    private func copyID(of device: LinkedDeviceDTO) {
        UIPasteboard.general.string = device.id.uuidString.lowercased()
        Haptics.impact(.light)
        showToast("Device ID copied")
    }

    private func showToast(_ message: String) {
        toast = message
        Task {
            try? await Task.sleep(nanoseconds: 1_800_000_000)
            if toast == message { toast = nil }
        }
    }
}

// MARK: - Detail sheet

/// Everything the server knows about one device, plus its actions.
private struct DeviceDetailSheet: View {
    let device: LinkedDeviceDTO
    let isCurrent: Bool
    let isRevoking: Bool
    let onCopyID: () -> Void
    let onRevoke: () -> Void

    var body: some View {
        ScrollView {
            VStack(spacing: 14) {
                VStack(spacing: 10) {
                    DeviceIconTile(name: device.name, size: 64)
                    Text(DevicesView.displayName(device))
                        .font(.system(size: 22, weight: .bold))
                        .foregroundStyle(Theme.textPrimary)
                        .multilineTextAlignment(.center)
                    Text(isCurrent ? "This \(UIDevice.current.model) · Active now" : DevicesView.lastActiveLabel(for: device))
                        .font(.system(size: 14))
                        .foregroundStyle(isCurrent ? Theme.accent : Theme.textSecondary)
                }
                .padding(.top, 24)
                .padding(.bottom, 4)

                card {
                    infoRow("Type", value: DeviceKind(name: device.name).label)
                    divider
                    infoRow("Linked", value: device.createdAt.formatted(date: .long, time: .shortened))
                    divider
                    infoRow(
                        "Last active",
                        value: isCurrent
                            ? "Now"
                            : device.lastSeenAt?.formatted(date: .long, time: .shortened) ?? "Never"
                    )
                    divider
                    Button(action: onCopyID) {
                        HStack(alignment: .firstTextBaseline, spacing: 12) {
                            Text("Device ID")
                                .font(.system(size: 16))
                                .foregroundStyle(Theme.textPrimary)
                            Spacer(minLength: 8)
                            Text(device.id.uuidString.lowercased())
                                .font(.system(size: 12, design: .monospaced))
                                .foregroundStyle(Theme.textSecondary)
                                .multilineTextAlignment(.trailing)
                            Image(systemName: "doc.on.doc")
                                .font(.system(size: 13, weight: .semibold))
                                .foregroundStyle(Theme.accent)
                        }
                        .padding(.horizontal, 14)
                        .padding(.vertical, 12)
                        .contentShape(Rectangle())
                    }
                    .buttonStyle(HighlightRowButtonStyle())
                    .accessibilityLabel("Copy device ID")
                }

                if isCurrent {
                    Text(
                        "To remove this \(UIDevice.current.model) from your account, use Log Out in Settings. It also erases everything Shroud keeps here."
                    )
                    .font(.system(size: 13))
                    .foregroundStyle(Theme.textSecondary)
                    .fixedSize(horizontal: false, vertical: true)
                    .padding(.horizontal, 14)
                    .frame(maxWidth: .infinity, alignment: .leading)
                } else {
                    card {
                        Button(action: onRevoke) {
                            HStack(spacing: 8) {
                                if isRevoking { ProgressView().controlSize(.small) }
                                Text(isRevoking ? "Removing…" : "Remove Device")
                                    .font(.system(size: 16, weight: .semibold))
                                    .foregroundStyle(Theme.danger)
                            }
                            .frame(maxWidth: .infinity)
                            .padding(.vertical, 14)
                            .contentShape(Rectangle())
                        }
                        .buttonStyle(HighlightRowButtonStyle())
                        .disabled(isRevoking)
                    }
                }
            }
            .padding(.horizontal, 16)
            .padding(.bottom, 24)
        }
        .background(Theme.backgroundGrouped)
    }

    private func infoRow(_ title: String, value: String) -> some View {
        HStack(alignment: .firstTextBaseline, spacing: 12) {
            Text(title)
                .font(.system(size: 16))
                .foregroundStyle(Theme.textPrimary)
            Spacer(minLength: 8)
            Text(value)
                .font(.system(size: 15))
                .foregroundStyle(Theme.textSecondary)
                .multilineTextAlignment(.trailing)
        }
        .padding(.horizontal, 14)
        .padding(.vertical, 12)
        .accessibilityElement(children: .combine)
    }

    private var divider: some View {
        Rectangle()
            .fill(Theme.separator)
            .frame(height: 1)
            .padding(.leading, 14)
    }

    private func card<Content: View>(@ViewBuilder content: () -> Content) -> some View {
        VStack(spacing: 0) {
            content()
        }
        .background(Theme.background)
        .clipShape(RoundedRectangle(cornerRadius: 14, style: .continuous))
    }
}

// MARK: - Device kind

/// Best guess at what a device is from its name. iOS registers `UIDevice.current.name`
/// ("iPhone", "iPad"); the web client registers "<Browser> on <OS>".
private enum DeviceKind {
    case iPhone, iPad, android, browser, mac, pc, unknown

    init(name: String?) {
        let lower = (name ?? "").lowercased()
        if lower.contains("iphone") {
            self = .iPhone
        } else if lower.contains("ipad") {
            self = .iPad
        } else if lower.contains("android") || lower.contains("phone") {
            self = .android
        } else if ["chrome", "safari", "firefox", "edge", "browser", " on "].contains(where: lower.contains) {
            self = .browser
        } else if lower.contains("mac") {
            self = .mac
        } else if lower.contains("windows") || lower.contains("linux") {
            self = .pc
        } else {
            self = .unknown
        }
    }

    var label: String {
        switch self {
        case .iPhone: "iPhone app"
        case .iPad: "iPad app"
        case .android: "Phone"
        case .browser: "Web browser"
        case .mac: "Mac"
        case .pc: "Computer"
        case .unknown: "Unknown"
        }
    }

    var systemImage: String {
        switch self {
        case .iPhone: "iphone"
        case .iPad: "ipad"
        case .android: "smartphone"
        case .browser: "globe"
        case .mac: "laptopcomputer"
        case .pc: "pc"
        case .unknown: "desktopcomputer"
        }
    }

    var tint: Color {
        switch self {
        case .iPhone, .iPad: Color(red: 46 / 255, green: 143 / 255, blue: 224 / 255)
        case .android: Color(red: 47 / 255, green: 168 / 255, blue: 91 / 255)
        case .browser: Color(red: 247 / 255, green: 107 / 255, blue: 28 / 255)
        case .mac, .pc: Color(red: 155 / 255, green: 74 / 255, blue: 230 / 255)
        case .unknown: Theme.textSecondary
        }
    }
}

private struct DeviceIconTile: View {
    let name: String?
    let size: CGFloat

    var body: some View {
        let kind = DeviceKind(name: name)
        ZStack {
            RoundedRectangle(cornerRadius: size * 0.27, style: .continuous)
                .fill(kind.tint)
                .frame(width: size, height: size)
            Image(systemName: kind.systemImage)
                .font(.system(size: size * 0.47, weight: .semibold))
                .foregroundStyle(Color.white)
        }
        .accessibilityHidden(true)
    }
}

#Preview {
    DevicesView()
        .environment(SessionController())
}
