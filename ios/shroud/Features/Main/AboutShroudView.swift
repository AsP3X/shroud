import SwiftUI

/// Settings → About Shroud: this build, its update status, the server, and what Shroud is built on.
///
/// Human: Same page and copy as the web and Android clients. The update row shows the server's
/// last answer from the shared `ClientVersionController` (an "available" the alert's Later hid
/// still shows here); "Check for Updates" asks again at once, past the ten-minute throttle, and
/// says "Checking…" for at least 600 ms like the Update required screen.
/// Agent: READS `ClientVersionController` and `ServerConfigurationController` from the
/// environment; CALLS `ClientVersionController.check(.manual, …)`; pushes `.licenses`.
struct AboutShroudView: View {
    @Binding var navigationPath: [SettingsRoute]

    @Environment(ClientVersionController.self) private var clientVersion
    @Environment(ServerConfigurationController.self) private var serverConfig
    @Environment(\.openURL) private var openURL

    /// "Check for Updates" is running; keeps "Checking…" up for at least `minimumCheck`.
    @State private var isCheckingManually = false

    // Design sizes at the default text size, scaled with Dynamic Type.
    @ScaledMetric(relativeTo: .title2) private var nameSize: CGFloat = 22
    @ScaledMetric(relativeTo: .body) private var bodySize: CGFloat = 16
    @ScaledMetric(relativeTo: .subheadline) private var noteSize: CGFloat = 15
    @ScaledMetric(relativeTo: .footnote) private var headerSize: CGFloat = 13
    @ScaledMetric(relativeTo: .subheadline) private var buttonSize: CGFloat = 14
    @ScaledMetric(relativeTo: .body) private var iconSize: CGFloat = 20
    @ScaledMetric(relativeTo: .body) private var iconWidth: CGFloat = 24

    static let sourceCodeURL = URL(string: "https://github.com/AsP3X/shroud")!
    static let privacyNote = "Messages, media and calls are end-to-end encrypted. They’re sealed on your devices, so the server passes them on without being able to read them."
    private static let minimumCheck: Duration = .milliseconds(600)

    private var status: UpdateCheckStatus {
        isCheckingManually ? .checking : clientVersion.updateStatus
    }

    var body: some View {
        GroupedScreen {
            ScrollView {
                VStack(alignment: .leading, spacing: 14) {
                    header

                    section("Updates") {
                        statusRow
                        divider
                        checkRow
                    }

                    section("Server") {
                        valueRow("Address", value: serverConfig.configuration.addressLabel)
                        divider
                        valueRow("Server version", value: clientVersion.serverVersion ?? "—")
                    }

                    section("Privacy") {
                        Text(Self.privacyNote)
                            .font(.system(size: noteSize))
                            .foregroundStyle(Theme.textSecondary)
                            .lineSpacing(2)
                            .fixedSize(horizontal: false, vertical: true)
                            .frame(maxWidth: .infinity, alignment: .leading)
                            .padding(.horizontal, 14)
                            .padding(.vertical, 12)
                    }

                    section("More") {
                        linkRow("Source Code", trailingSymbol: "arrow.up.right") {
                            openURL(Self.sourceCodeURL)
                        }
                        .accessibilityAddTraits(.isLink)
                        divider
                        linkRow("Open-Source Licenses", trailingSymbol: "chevron.right") {
                            navigationPath.append(.licenses)
                        }
                    }

                    Color.clear.frame(height: 24)
                }
                .padding(.horizontal, 16)
                .padding(.top, 8)
            }
        }
        // Inline, like the other screens pushed from Settings.
        .navigationTitle("About Shroud")
        .navigationBarTitleDisplayMode(.inline)
        .toolbar(.visible, for: .navigationBar)
    }

    // MARK: - Header

    private var header: some View {
        VStack(spacing: 8) {
            BrandLogoMark(size: 72)
                .shadow(color: Theme.accent.opacity(0.22), radius: 16, y: 10)
                .padding(.bottom, 4)
                .accessibilityHidden(true)
            Text("Shroud")
                .font(.system(size: nameSize, weight: .bold))
                .foregroundStyle(Theme.textPrimary)
                .accessibilityAddTraits(.isHeader)
            Text("Version \(clientVersion.currentVersion) (\(ClientVersionService.buildNumber))")
                .font(.system(size: noteSize))
                .foregroundStyle(Theme.textSecondary)
                .textSelection(.enabled)
        }
        .frame(maxWidth: .infinity)
        .padding(.top, 12)
        .padding(.bottom, 6)
    }

    // MARK: - Updates

    private var statusRow: some View {
        HStack(spacing: 12) {
            statusIcon
                .frame(width: iconWidth)
                .accessibilityHidden(true)
            Text(ClientVersionPolicy.statusTitle(status))
                .font(.system(size: bodySize))
                .foregroundStyle(Theme.textPrimary)
                .fixedSize(horizontal: false, vertical: true)
                .frame(maxWidth: .infinity, alignment: .leading)
                .contentTransition(.opacity)
            if case let .available(_, url?) = status {
                Button {
                    openURL(url)
                } label: {
                    Text("Update")
                        .font(.system(size: buttonSize, weight: .semibold))
                        .foregroundStyle(Color.white)
                        .padding(.horizontal, 14)
                        .padding(.vertical, 6)
                        .background(Capsule().fill(Theme.accent))
                        // A 44 pt target without growing the capsule.
                        .contentShape(Rectangle().inset(by: -8))
                }
                .buttonStyle(.plain)
                .pressable()
                .transition(.opacity)
                .accessibilityIdentifier("about.update")
            }
        }
        .padding(.horizontal, 14)
        .padding(.vertical, 12)
        .animation(Motion.fade, value: status)
        .accessibilityElement(children: .contain)
    }

    @ViewBuilder
    private var statusIcon: some View {
        switch status {
        case .checking:
            ProgressView()
                .controlSize(.small)
        case .current:
            statusSymbol("checkmark.circle.fill", color: Theme.online)
        case .available:
            statusSymbol("arrow.down.circle.fill", color: Theme.accent)
        case .required:
            statusSymbol("exclamationmark.triangle.fill", color: Theme.warningIcon)
        case .failed:
            statusSymbol("exclamationmark.circle.fill", color: Theme.danger)
        case .unknown:
            statusSymbol("clock.arrow.circlepath", color: Theme.textSecondary)
        }
    }

    private func statusSymbol(_ name: String, color: Color) -> some View {
        Image(systemName: name)
            .font(.system(size: iconSize))
            .foregroundStyle(color)
            .transition(Motion.iconSwap)
    }

    private var checkRow: some View {
        Button(action: checkForUpdates) {
            Text(status == .checking ? "Checking…" : "Check for Updates")
                .font(.system(size: bodySize))
                .foregroundStyle(Theme.accent)
                .frame(maxWidth: .infinity, alignment: .leading)
                .padding(.horizontal, 14)
                .padding(.vertical, 12)
                .contentShape(Rectangle())
        }
        .buttonStyle(HighlightRowButtonStyle())
        .disabled(status == .checking)
        .accessibilityIdentifier("about.checkForUpdates")
    }

    /// Asks the server now. The status row shows what came of it; a failure reads
    /// "Couldn’t check for updates" there, so no toast.
    private func checkForUpdates() {
        guard !isCheckingManually else { return }
        isCheckingManually = true
        Task {
            defer { isCheckingManually = false }
            let started = ContinuousClock.now
            let outcome = await clientVersion.check(.manual, configuration: serverConfig.configuration)
            // "Checking…" stays long enough to be read; a local server answers in milliseconds.
            try? await Task.sleep(until: started + Self.minimumCheck)
            switch outcome {
            case .answered(.current):
                Haptics.notification(.success)
            case .answered:
                Haptics.notification(.warning)
            case .failed:
                Haptics.notification(.error)
            case .skipped:
                break
            }
        }
    }

    // MARK: - Rows

    private func valueRow(_ title: String, value: String) -> some View {
        HStack(alignment: .firstTextBaseline, spacing: 12) {
            Text(title)
                .font(.system(size: bodySize))
                .foregroundStyle(Theme.textPrimary)
            Spacer(minLength: 8)
            Text(value)
                .font(.system(size: bodySize))
                .foregroundStyle(Theme.textSecondary)
                .multilineTextAlignment(.trailing)
                .lineLimit(2)
                .truncationMode(.middle)
                .textSelection(.enabled)
        }
        .padding(.horizontal, 14)
        .padding(.vertical, 12)
        .accessibilityElement(children: .combine)
    }

    private func linkRow(_ title: String, trailingSymbol: String, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            HStack(spacing: 12) {
                Text(title)
                    .font(.system(size: bodySize))
                    .foregroundStyle(Theme.textPrimary)
                    .frame(maxWidth: .infinity, alignment: .leading)
                Image(systemName: trailingSymbol)
                    .font(.system(size: 13, weight: .semibold))
                    .foregroundStyle(Theme.chevron)
                    .accessibilityHidden(true)
            }
            .padding(.horizontal, 14)
            .padding(.vertical, 12)
            .contentShape(Rectangle())
        }
        .buttonStyle(HighlightRowButtonStyle())
    }

    // MARK: - Sections

    private func section<Content: View>(_ title: String, @ViewBuilder content: () -> Content) -> some View {
        VStack(alignment: .leading, spacing: 6) {
            Text(title.uppercased())
                .font(.system(size: headerSize))
                .foregroundStyle(Theme.textSecondary)
                .padding(.horizontal, 14)
                .accessibilityAddTraits(.isHeader)
            VStack(spacing: 0) {
                content()
            }
            .background(Theme.background)
            .clipShape(RoundedRectangle(cornerRadius: 14, style: .continuous))
        }
    }

    private var divider: some View {
        Rectangle()
            .fill(Theme.separator)
            .frame(height: 1)
            .padding(.leading, 14)
    }
}

#Preview {
    NavigationStack {
        AboutShroudView(navigationPath: .constant([]))
    }
    .environment(ClientVersionController())
    .environment(ServerConfigurationController())
}
