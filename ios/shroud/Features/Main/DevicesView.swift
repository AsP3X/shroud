import SwiftUI

/// Settings → Devices: every device linked to the account, with a way to remove the others.
///
/// Human: Same data and rules as the web client's Devices page — this device can't be
/// removed here (that's Log Out), every other one can, one at a time or all at once. The
/// account holds at most `DevicesService.deviceLimit` devices, so this is also where you make
/// room for a new one.
/// Agent: CALLS DevicesService (GET/DELETE `/devices`, PUT `/devices/{id}/name` sealed); READS
/// SessionController for the token and this device's id, CryptoController for the history key
/// that opens and seals the names; 401s are counted by `SessionAuthBridge` like every request.
struct DevicesView: View {
    /// Reports the device count back to the Settings row after every load or removal.
    var onCount: ((Int) -> Void)? = nil

    @Environment(SessionController.self) private var sessionController
    @Environment(CryptoController.self) private var cryptoController
    @Environment(\.dismiss) private var dismiss
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

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
    @State private var toast: Toast?

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
            ScrollView {
                VStack(alignment: .leading, spacing: 0) {
                    if let devices {
                        loadedContent(devices)
                    } else if let loadError {
                        ListLoadErrorView(
                            title: "Can't load devices",
                            message: loadError,
                            retry: { await load() }
                        )
                    } else {
                        loadingCard
                    }
                    Color.clear.frame(height: 24)
                }
                .padding(.horizontal, 16)
                .padding(.top, 8)
                // Like the sibling lists: the loaded list fades in, a removed row folds away
                // instead of the rows below jumping, and the error lines fade.
                .animation(Motion.respecting(reduceMotion, Motion.standard), value: devices?.map(\.id))
                .animation(Motion.fade, value: loadError)
                .animation(Motion.fade, value: actionError)
            }
            // A new pull starts clean; a failure sets the error again.
            .refreshable {
                actionError = nil
                await load()
            }
        }
        // System navigation bar: Liquid Glass back button, inline title, scroll edge fade.
        .navigationTitle("Devices")
        .navigationBarTitleDisplayMode(.inline)
        .toolbar(.visible, for: .navigationBar)
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
                label: label(device),
                isCurrent: isCurrent(device),
                isRevoking: revokingIDs.contains(device.id) || isRevokingAll,
                onRename: { name in await rename(device, to: name) },
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
    /// Human: The server keeps the removed device's row as history, so the messages and files
    /// it sent stay in every chat. It only loses its sessions, keys and push token. The web
    /// confirmation in `web/src/components/settings/DevicesView.tsx` says the same.
    private static func revokeConsequences(plural: Bool) -> String {
        plural
            ? "They are signed out right away and erase everything of your account on them: messages, keys and files, as soon as they are online or next opened. What they already sent stays in your chats. Signing in there again takes your password and 12-word phrase."
            : "It is signed out right away and erases everything of your account on it: messages, keys and files, as soon as it is online or next opened. What it already sent stays in your chats. Signing in there again takes your password and 12-word phrase."
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
                    "Every device unlocks with your 12-word phrase, which never leaves it. Device names are encrypted with it too, so only your own devices can read them. The server records when each device was linked and when it was last active — both shown here. A removed device erases everything of your account on it as soon as it is online or next opened."
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
                ProgressView()
                Text("Loading devices…")
                    .font(.system(size: 14))
                    .foregroundStyle(Theme.textSecondary)
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
                    DeviceIconTile(label: label(device), size: 30)
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
                            .foregroundStyle(Theme.chevron)
                    }
                }
                // The row's padding sits inside the button, so its whole height opens the details.
                .padding(.leading, 14)
                .padding(.vertical, 10)
                .contentShape(Rectangle())
            }
            .buttonStyle(.plain)

            if !current {
                if revoking || isRevokingAll {
                    ProgressView()
                        .controlSize(.small)
                        .frame(minWidth: 60)
                } else {
                    Button {
                        Haptics.impact(.light)
                        pendingRevoke = device
                    } label: {
                        // 44 pt tall target; still shorter than the row, so the row keeps its height.
                        Text("Remove")
                            .font(.system(size: 15, weight: .medium))
                            .foregroundStyle(Theme.danger)
                            .frame(minHeight: 44)
                            .contentShape(Rectangle())
                    }
                    .accessibilityLabel("Remove \(displayName(device))")
                }
            }
        }
        .padding(.trailing, 14)
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
        // HighlightRowButtonStyle gives the press-down tick; no second haptic on release.
        Button {
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
                    // warningText, not warningIcon: text needs 4.5:1 on the card in both modes.
                    .foregroundStyle(full ? Theme.warningText : Theme.textSecondary)
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
            return "Your account is at the limit. A new sign-in takes over a device that has been logged out; if every device is still signed in, it asks for your password and 12-word phrase, then offers to log out the one used least recently."
        }
        let left = limit - count
        return "You can sign in on \(left) more \(left == 1 ? "device" : "devices"). A logged-out device stays listed until it signs in again or you remove it."
    }

    // MARK: - Chrome


    private func sectionHeader(_ title: String) -> some View {
        Text(title.uppercased())
            .font(.system(size: 13))
            .foregroundStyle(Theme.textSecondary)
            .padding(.horizontal, 14)
            .padding(.bottom, 6)
            .padding(.top, 6)
            .accessibilityAddTraits(.isHeader)
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

    /// The device's name, opened with this account's history key; nil when it has none yet.
    private func label(_ device: LinkedDeviceDTO) -> DeviceNameSeal.Label? {
        guard let historyKey = cryptoController.material?.historyKey else { return nil }
        return DeviceNameSeal.open(device.sealedName, deviceID: device.id, historyKey: historyKey)
    }

    private func displayName(_ device: LinkedDeviceDTO) -> String {
        Self.displayName(label(device))
    }

    static func displayName(_ label: DeviceNameSeal.Label?) -> String {
        let trimmed = label?.name.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
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
                toast = Toast("\(displayName(device)) removed")
            } catch where Self.isAlreadyRemoved(error) {
                removeLocally([device.id])
                toast = .info("\(displayName(device)) was already removed")
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
                let lead = targets.count == 1
                    ? "The device could not be removed. "
                    : "\(failed) of \(targets.count) devices could not be removed. "
                actionError = lead + SessionController.userMessage(for: lastError)
                Haptics.notification(.error)
            } else {
                Haptics.notification(.success)
                toast = Toast(removed.count == 1 ? "1 device removed" : "\(removed.count) devices removed")
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

    /// Seals the new name (marked as chosen, so an iPhone keeps it instead of its own) and
    /// reloads. Returns an error message, or nil once saved. The stored kind is kept, so
    /// renaming an Android device here keeps kind 4 (a build without it wrote back `.other`).
    private func rename(_ device: LinkedDeviceDTO, to name: String) async -> String? {
        guard let token = sessionController.bearerToken,
              let historyKey = cryptoController.material?.historyKey
        else { return "Unlock Shroud to rename devices." }
        let kind = label(device)?.kind
            ?? (isCurrent(device) ? DeviceNameSync.currentLabel().kind : .other)
        do {
            let sealed = try DeviceNameSeal.seal(
                .init(name: name, kind: kind, custom: true),
                deviceID: device.id,
                historyKey: historyKey
            )
            try await service.putName(deviceID: device.id, sealedName: sealed, token: token)
        } catch DeviceNameSeal.SealError.emptyName {
            return "Enter a name."
        } catch {
            return SessionController.userMessage(for: error)
        }
        await load()
        // The open sheet shows the new name.
        if let updated = devices?.first(where: { $0.id == device.id }) { detailDevice = updated }
        Haptics.impact(.light)
        return nil
    }

    private func copyID(of device: LinkedDeviceDTO) {
        // The row's HighlightRowButtonStyle already ticks on press-down; the detail sheet
        // shows the confirmation, since this screen's toast would sit under it.
        UIPasteboard.general.string = device.id.uuidString.lowercased()
    }
}

// MARK: - Detail sheet

/// Everything the server knows about one device, plus its actions.
private struct DeviceDetailSheet: View {
    let device: LinkedDeviceDTO
    let label: DeviceNameSeal.Label?
    let isCurrent: Bool
    let isRevoking: Bool
    /// Returns an error message, or nil once the name is saved.
    let onRename: (String) async -> String?
    let onCopyID: () -> Void
    let onRevoke: () -> Void

    @State private var isRenaming = false
    @State private var draft = ""
    @State private var isSavingName = false
    @State private var renameError: String?
    @State private var toast: Toast?

    var body: some View {
        ScrollView {
            VStack(spacing: 14) {
                VStack(spacing: 10) {
                    DeviceIconTile(label: label, size: 64)
                    Text(DevicesView.displayName(label))
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
                    Button {
                        draft = label?.name ?? ""
                        isRenaming = true
                    } label: {
                        HStack(spacing: 12) {
                            Text("Name")
                                .font(.system(size: 16))
                                .foregroundStyle(Theme.textPrimary)
                            Spacer(minLength: 8)
                            if isSavingName {
                                ProgressView().controlSize(.small)
                            } else {
                                Text("Rename")
                                    .font(.system(size: 15, weight: .medium))
                                    .foregroundStyle(Theme.accent)
                            }
                        }
                        .padding(.horizontal, 14)
                        .padding(.vertical, 12)
                        .contentShape(Rectangle())
                    }
                    .buttonStyle(HighlightRowButtonStyle())
                    .disabled(isSavingName)
                    .accessibilityLabel("Rename \(DevicesView.displayName(label))")
                    divider
                    infoRow("Type", value: DeviceKind(label: label).label)
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
                    Button {
                        onCopyID()
                        toast = Toast("Device ID copied")
                    } label: {
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

                if let renameError {
                    Text(renameError)
                        .font(.system(size: 13))
                        .foregroundStyle(Theme.danger)
                        .padding(.horizontal, 14)
                        .frame(maxWidth: .infinity, alignment: .leading)
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
        // The sheet stays open after a copy and covers the Devices screen, so the
        // confirmation floats on the sheet's own bottom edge.
        .toast($toast)
        // Like MyQRCodeSheet: the sheet covers the tab bar it inherits this from.
        .environment(\.tabBarClearance, 0)
        .alert("Rename Device", isPresented: $isRenaming) {
            TextField("Name", text: $draft)
                .textInputAutocapitalization(.words)
            Button("Cancel", role: .cancel) {}
            Button("Save") { saveName() }
                .disabled(DeviceNameSeal.normalize(draft).isEmpty)
        } message: {
            Text("The name is encrypted — only your devices can read it.")
        }
    }

    private func saveName() {
        let name = DeviceNameSeal.normalize(draft)
        guard !name.isEmpty else { return }
        isSavingName = true
        renameError = nil
        Task {
            renameError = await onRename(name)
            isSavingName = false
        }
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

/// What a device is: the kind sealed with its name, else a guess from the name.
///
/// Human: The Android app seals kind 4, which shows as "Android app" with the green phone tile
/// (android-port decision P4). Before this build knew kind 4 it read as `.other` and fell back
/// to the name guess, so "Pixel 9 Pro" showed as "Unknown". A name that only looks like a phone
/// ("android", "phone") stays the guess "Phone" with the same tile: it may be any phone, or an
/// Android device renamed by a client that dropped its kind — only the sealed kind says
/// "Android app" (decision S1; the web's `deviceKind()` does the same).
/// Agent: Internal (not private) for `DeviceNameSealTests`.
enum DeviceKind: Equatable {
    case iPhone, iPad, android, phone, browser, mac, pc, unknown

    init(label: DeviceNameSeal.Label?) {
        switch label?.kind {
        case .iPhone: self = .iPhone; return
        case .iPad: self = .iPad; return
        case .web: self = .browser; return
        case .android: self = .android; return
        case .other, nil: break
        }
        let lower = (label?.name ?? "").lowercased()
        if lower.contains("iphone") {
            self = .iPhone
        } else if lower.contains("ipad") {
            self = .iPad
        } else if lower.contains("android") || lower.contains("phone") {
            self = .phone
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
        case .android: "Android app"
        case .phone: "Phone"
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
        case .android, .phone: "smartphone"
        case .browser: "globe"
        case .mac: "laptopcomputer"
        case .pc: "pc"
        case .unknown: "desktopcomputer"
        }
    }

    var tint: Color {
        switch self {
        case .iPhone, .iPad: Color(red: 46 / 255, green: 143 / 255, blue: 224 / 255)
        case .android, .phone: Color(red: 47 / 255, green: 168 / 255, blue: 91 / 255)
        case .browser: Color(red: 247 / 255, green: 107 / 255, blue: 28 / 255)
        case .mac, .pc: Color(red: 155 / 255, green: 74 / 255, blue: 230 / 255)
        case .unknown: Theme.textSecondary
        }
    }
}

/// The rounded kind tile of a device row; a nil label is the grey "unknown" computer.
/// Agent: Internal for the login's `DeviceLimitSheet`, which can't open device names.
struct DeviceIconTile: View {
    let label: DeviceNameSeal.Label?
    let size: CGFloat

    var body: some View {
        let kind = DeviceKind(label: label)
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
        .environment(CryptoController())
}
