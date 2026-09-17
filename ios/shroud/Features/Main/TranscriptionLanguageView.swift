import SwiftUI

/// Picks the language voice messages are transcribed in.
///
/// Human: Auto-detection can only choose between models already installed on the device, and
/// device locale is a poor proxy for the language someone speaks (an English UI with a German
/// region is common). This screen is the escape hatch: choose the language explicitly and every
/// transcription uses it, downloading the model once.
/// Agent: READS/WRITES `TranscriptionLanguage.override`; CALLS VoiceTranscriber.prepareModel
/// to fetch the chosen model. No message or key material is touched.
struct TranscriptionLanguageView: View {
    @Environment(\.dismiss) private var dismiss

    @State private var available: [Locale] = []
    @State private var installed: Set<String> = []
    @State private var selection: Locale?
    @State private var isLoading = true
    @State private var downloading: String?
    @State private var install = TranscriptionModelInstall.shared
    @State private var toast: String?

    var body: some View {
        GroupedScreen {
            VStack(spacing: 0) {
                navRow

                ScrollView {
                    VStack(alignment: .leading, spacing: 14) {
                        header

                        if isLoading {
                            loadingCard
                        } else if available.isEmpty {
                            unavailableCard
                        } else {
                            if install.phase == .downloading {
                                downloadProgressCard
                            }
                            automaticCard
                            languageCard
                        }

                        Color.clear.frame(height: 24)
                    }
                    .padding(.horizontal, 16)
                }
            }
        }
        .navigationBarBackButtonHidden(true)
        .toolbar(.hidden, for: .navigationBar)
        .toast($toast)
        .task { await load() }
    }

    // MARK: - Chrome

    private var navRow: some View {
        HStack {
            Button {
                dismiss()
            } label: {
                HStack(spacing: 2) {
                    Image(systemName: "chevron.left")
                        .font(.system(size: 16, weight: .semibold))
                    Text("Settings")
                        .font(.system(size: 16))
                }
                .foregroundStyle(Theme.accent)
            }
            .pressable(scale: 0.9)

            Spacer()
        }
        .padding(.horizontal, 12)
        .frame(height: 44)
    }

    private var header: some View {
        VStack(alignment: .leading, spacing: 6) {
            Text("Transcription")
                .font(.system(size: 32, weight: .bold))
                .foregroundStyle(Theme.textPrimary)
            Text("Voice messages are transcribed on this device. Audio never leaves it. The language model downloads automatically the first time you transcribe.")
                .font(.system(size: 14))
                .foregroundStyle(Theme.textSecondary)
        }
        .padding(.top, 4)
        .padding(.bottom, 4)
    }

    // MARK: - Cards

    private var loadingCard: some View {
        HStack(spacing: 10) {
            ProgressView()
            Text("Checking available languages…")
                .font(.system(size: 15))
                .foregroundStyle(Theme.textSecondary)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(14)
        .background(Theme.background)
        .clipShape(RoundedRectangle(cornerRadius: 14, style: .continuous))
    }

    private var unavailableCard: some View {
        VStack(alignment: .leading, spacing: 6) {
            Text("Not available on this device")
                .font(.system(size: 15, weight: .semibold))
                .foregroundStyle(Theme.textPrimary)
            Text("This device has no on-device speech models. Transcription falls back to a shorter, less accurate engine where one is available.")
                .font(.system(size: 13))
                .foregroundStyle(Theme.textSecondary)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(14)
        .background(Theme.background)
        .clipShape(RoundedRectangle(cornerRadius: 14, style: .continuous))
    }

    private var downloadProgressCard: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text(downloadProgressTitle)
                .font(.system(size: 15, weight: .semibold))
                .foregroundStyle(Theme.textPrimary)
            if install.isDeterminate {
                ProgressView(value: max(install.fractionCompleted, 0.02))
                    .progressViewStyle(.linear)
                    .tint(Theme.accent)
            } else {
                ProgressView()
                    .controlSize(.small)
                    .tint(Theme.accent)
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(14)
        .background(Theme.background)
        .clipShape(RoundedRectangle(cornerRadius: 14, style: .continuous))
    }

    private var downloadProgressTitle: String {
        let percent = Int((install.fractionCompleted * 100).rounded())
        if let name = install.languageName {
            return install.isDeterminate && percent > 0
                ? "Downloading \(name)… \(percent)%"
                : "Downloading \(name)…"
        }
        return "Downloading transcription model…"
    }

    private var automaticCard: some View {
        VStack(spacing: 0) {
            row(
                title: "Automatic",
                subtitle: automaticSubtitle,
                isSelected: selection == nil,
                isInstalled: true,
                isDownloading: downloading != nil && selection == nil
            ) {
                choose(nil)
            }
        }
        .background(Theme.background)
        .clipShape(RoundedRectangle(cornerRadius: 14, style: .continuous))
    }

    private var languageCard: some View {
        VStack(spacing: 0) {
            ForEach(Array(available.enumerated()), id: \.element.identifier) { index, locale in
                if index > 0 {
                    Rectangle()
                        .fill(Theme.separator)
                        .frame(height: 1)
                        .padding(.leading, 14)
                }
                row(
                    title: TranscriptionLanguage.displayName(for: locale),
                    subtitle: subtitle(for: locale),
                    isSelected: selection?.identifier(.bcp47) == locale.identifier(.bcp47),
                    isInstalled: installed.contains(locale.identifier(.bcp47)),
                    isDownloading: downloading == locale.identifier(.bcp47)
                ) {
                    choose(locale)
                }
            }
        }
        .background(Theme.background)
        .clipShape(RoundedRectangle(cornerRadius: 14, style: .continuous))
    }

    private var automaticSubtitle: String {
        if downloading != nil, selection == nil { return "Downloading model…" }
        return "Detects the spoken language. Downloads a model if one isn't on this device yet."
    }

    private func subtitle(for locale: Locale) -> String? {
        if downloading == locale.identifier(.bcp47) { return "Downloading model…" }
        return installed.contains(locale.identifier(.bcp47)) ? "On this device" : "Tap to download"
    }

    private func row(
        title: String,
        subtitle: String?,
        isSelected: Bool,
        isInstalled: Bool,
        isDownloading: Bool,
        action: @escaping () -> Void
    ) -> some View {
        Button(action: action) {
            HStack(spacing: 12) {
                VStack(alignment: .leading, spacing: 2) {
                    Text(title)
                        .font(.system(size: 16))
                        .foregroundStyle(Theme.textPrimary)
                        .multilineTextAlignment(.leading)
                    if let subtitle {
                        Text(subtitle)
                            .font(.system(size: 13))
                            .foregroundStyle(Theme.textSecondary)
                            .multilineTextAlignment(.leading)
                    }
                }
                .frame(maxWidth: .infinity, alignment: .leading)

                if isDownloading {
                    ProgressView()
                        .controlSize(.mini)
                } else if isSelected {
                    Image(systemName: "checkmark")
                        .font(.system(size: 14, weight: .bold))
                        .foregroundStyle(Theme.accent)
                        .transition(Motion.iconSwap)
                } else if !isInstalled {
                    Image(systemName: "arrow.down.circle")
                        .font(.system(size: 16, weight: .medium))
                        .foregroundStyle(Theme.accent)
                }
            }
            .padding(.horizontal, 14)
            .padding(.vertical, 11)
            .contentShape(Rectangle())
        }
        .buttonStyle(HighlightRowButtonStyle())
        .disabled(isDownloading)
        .accessibilityAddTraits(isSelected ? .isSelected : [])
        .accessibilityLabel(accessibilityLabel(title: title, isInstalled: isInstalled, isDownloading: isDownloading))
    }

    private func accessibilityLabel(title: String, isInstalled: Bool, isDownloading: Bool) -> String {
        if isDownloading { return "\(title), downloading" }
        if isInstalled { return title }
        return "\(title), tap to download"
    }

    // MARK: - Actions

    private func choose(_ locale: Locale?) {
        withAnimation(Motion.snappy) {
            selection = locale
        }
        TranscriptionLanguage.override = locale
        Haptics.impact(.light)
        Task { await downloadIfNeeded(locale) }
    }

    private func load() async {
        let locales = await VoiceTranscriber.availableLocales()
        var installedSet: Set<String> = []
        for locale in locales where await VoiceTranscriber.modelIsInstalled(locale: locale) {
            installedSet.insert(locale.identifier(.bcp47))
        }
        available = locales
        installed = installedSet
        selection = TranscriptionLanguage.override
        withAnimation(Motion.fade) { isLoading = false }
        await downloadIfNeeded(selection)
    }

    /// Downloads the model for the selected language, or the device language when Automatic is on.
    private func downloadIfNeeded(_ locale: Locale?) async {
        let target = locale ?? Locale.current
        if await VoiceTranscriber.modelIsInstalled(locale: target) {
            installed.insert(target.identifier(.bcp47))
            return
        }

        downloading = target.identifier(.bcp47)
        let ok = await VoiceTranscriber.prepareModel(locale: locale)
        downloading = nil
        if ok {
            installed.insert(target.identifier(.bcp47))
            Haptics.notification(.success)
        } else if !available.isEmpty {
            toast = "Could not download that language. Check your connection and try again."
            Task {
                try? await Task.sleep(nanoseconds: 2_500_000_000)
                toast = nil
            }
        }
    }
}

#Preview {
    NavigationStack {
        TranscriptionLanguageView()
    }
}
