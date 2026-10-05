import SwiftUI

/// Picks the language hint Whisper should use for voice messages.
///
/// Human: Whisper is one multilingual model. This screen does not download extra packs —
/// it only pins a language when auto-detection is wrong. The model itself downloads the
/// first time a voice note is transcribed.
/// Agent: READS/WRITES `TranscriptionLanguage.override` and
/// `TranscriptionPreferences.transcribesAutomatically`. Does not start a model download.
struct TranscriptionLanguageView: View {
    @Environment(\.dismiss) private var dismiss

    @State private var available: [Locale] = []
    @State private var selection: Locale?
    @State private var transcribesAutomatically = TranscriptionPreferences.transcribesAutomatically
    @State private var install = TranscriptionModelInstall.shared

    var body: some View {
        GroupedScreen {
            ScrollView {
                VStack(alignment: .leading, spacing: 14) {
                    header

                    automaticTranscriptionCard

                    if install.phase == .downloading {
                        downloadProgressCard
                    }

                    automaticCard
                    languageCard

                    Color.clear.frame(height: 24)
                }
                .padding(.horizontal, 16)
                .padding(.top, 8)
            }
        }
        // System navigation bar: Liquid Glass back button, inline title (like the other
        // screens pushed from Settings), scroll edge fade.
        .navigationTitle("Transcription")
        .navigationBarTitleDisplayMode(.inline)
        .toolbar(.visible, for: .navigationBar)
        .onAppear {
            available = Self.pickerOrder(TranscriptionLanguage.whisperLocales)
            selection = TranscriptionLanguage.override
        }
    }

    /// The device's own languages first, as `whisperLocales` lists them, then the rest A–Z by
    /// their shown name, so a language can be found without reading all thirty rows.
    /// Only the picker is sorted: the transcriber reads `whisperLocales` in its own order.
    private static func pickerOrder(_ all: [Locale]) -> [Locale] {
        let preferred = Set(TranscriptionLanguage.preferredLanguageTags.compactMap {
            Locale(identifier: $0).language.languageCode?.identifier
        })
        let head = all.prefix(while: { preferred.contains($0.language.languageCode?.identifier ?? "") })
        let rest = all.dropFirst(head.count).sorted {
            TranscriptionLanguage.displayName(for: $0)
                .localizedStandardCompare(TranscriptionLanguage.displayName(for: $1)) == .orderedAscending
        }
        return Array(head) + rest
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

    /// Off by default (`TranscriptionPreferences`): a note is then transcribed only on a tap.
    private var automaticTranscriptionCard: some View {
        Toggle(isOn: $transcribesAutomatically) {
            VStack(alignment: .leading, spacing: 4) {
                Text("Transcribe automatically")
                    .font(.system(size: 16))
                    .foregroundStyle(Theme.textPrimary)
                Text("Transcribes the voice messages you send, right after sending. When off, tap the transcript button next to a voice message to transcribe it.")
                    .font(.system(size: 13))
                    .foregroundStyle(Theme.textSecondary)
                    .fixedSize(horizontal: false, vertical: true)
            }
        }
        .tint(Theme.accent)
        .padding(.horizontal, 14)
        .padding(.vertical, 12)
        .background(Theme.background)
        .clipShape(RoundedRectangle(cornerRadius: 14, style: .continuous))
        .onChange(of: transcribesAutomatically) { _, value in
            TranscriptionPreferences.transcribesAutomatically = value
            Haptics.impact(.light)
        }
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
        // The label skips the checkmark; the explanation stays reachable as the hint.
        .accessibilityHint(subtitle ?? "")
    }

    private func choose(_ locale: Locale?) {
        withAnimation(Motion.snappy) {
            selection = locale
        }
        // No haptic here: HighlightRowButtonStyle already ticks on press-down.
        TranscriptionLanguage.override = locale
    }
}

#Preview {
    NavigationStack {
        TranscriptionLanguageView()
    }
}
