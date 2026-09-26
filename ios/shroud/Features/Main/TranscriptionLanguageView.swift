import SwiftUI

/// Picks the language hint Whisper should use for voice messages.
///
/// Human: Whisper is one multilingual model. This screen does not download extra packs —
/// it only pins a language when auto-detection is wrong. The model itself downloads the
/// first time a voice note is transcribed.
/// Agent: READS/WRITES `TranscriptionLanguage.override`. Does not start a model download.
struct TranscriptionLanguageView: View {
    @Environment(\.dismiss) private var dismiss

    @State private var available: [Locale] = []
    @State private var selection: Locale?
    @State private var install = TranscriptionModelInstall.shared

    var body: some View {
        GroupedScreen {
            ScrollView {
                VStack(alignment: .leading, spacing: 14) {
                    header

                    if install.phase == .downloading {
                        downloadProgressCard
                    }

                    automaticCard
                    languageCard

                    Color.clear.frame(height: 24)
                }
                .padding(.horizontal, 16)
            }
        }
        // System navigation bar: Liquid Glass back button, large title, scroll edge fade.
        .navigationTitle("Transcription")
        .navigationBarTitleDisplayMode(.large)
        .toolbar(.visible, for: .navigationBar)
        .onAppear {
            available = TranscriptionLanguage.whisperLocales
            selection = TranscriptionLanguage.override
        }
    }

    // MARK: - Header

    private var header: some View {
        VStack(alignment: .leading, spacing: 6) {
            Text("Voice messages are transcribed on this device with Whisper. Audio never leaves it. The model downloads once, the first time you transcribe, then works for every language.")
                .font(.system(size: 14))
                .foregroundStyle(Theme.textSecondary)
        }
        .padding(.top, 4)
        .padding(.bottom, 4)
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
        if install.isDeterminate, percent > 0 {
            return "Downloading Whisper… \(percent)%"
        }
        return "Downloading Whisper…"
    }

    private var automaticCard: some View {
        VStack(spacing: 0) {
            row(
                title: "Automatic",
                subtitle: "Detects the spoken language. Remembers it per chat when Whisper is unsure.",
                isSelected: selection == nil
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
                    subtitle: nil,
                    // By language, not full tag: overrides saved before Whisper carry a region
                    // ("de-DE") while this list is plain languages ("de").
                    isSelected: selection?.language.languageCode == locale.language.languageCode
                ) {
                    choose(locale)
                }
            }
        }
        .background(Theme.background)
        .clipShape(RoundedRectangle(cornerRadius: 14, style: .continuous))
    }

    private func row(
        title: String,
        subtitle: String?,
        isSelected: Bool,
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

                if isSelected {
                    Image(systemName: "checkmark")
                        .font(.system(size: 14, weight: .bold))
                        .foregroundStyle(Theme.accent)
                        .transition(Motion.iconSwap)
                }
            }
            .padding(.horizontal, 14)
            .padding(.vertical, 11)
            .contentShape(Rectangle())
        }
        .buttonStyle(HighlightRowButtonStyle())
        .accessibilityAddTraits(isSelected ? .isSelected : [])
        .accessibilityLabel(title)
    }

    private func choose(_ locale: Locale?) {
        withAnimation(Motion.snappy) {
            selection = locale
        }
        TranscriptionLanguage.override = locale
        Haptics.impact(.light)
    }
}

#Preview {
    NavigationStack {
        TranscriptionLanguageView()
    }
}
