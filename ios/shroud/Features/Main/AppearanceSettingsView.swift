import SwiftUI

/// Picks the colour theme (System, Light or Dark) and the Shroud logo: the detailed veil from the
/// app icon, or the simplified flat one.
///
/// Human: The theme stays on this iPhone; the web client keeps its own. One logo choice covers the
/// home screen icon and every in-app mark, so they never disagree. iOS confirms an icon change
/// with its own alert; that is not ours to suppress.
/// Agent: WRITES through `ColorThemePreference.choose(_:)` and `BrandLogoPreference.choose(_:)`;
/// READS their `theme` / `style`.
struct AppearanceSettingsView: View {
    @State private var theme = ColorThemePreference.shared
    @State private var preference = BrandLogoPreference.shared
    @State private var failure: String?

    var body: some View {
        GroupedScreen {
            ScrollView {
                VStack(alignment: .leading, spacing: 14) {
                    VStack(alignment: .leading, spacing: 6) {
                        sectionHeader("Theme")
                        themeCard
                    }
                    sectionFooter("System follows your \(UIDevice.current.model)’s light or dark setting. The choice applies to this \(UIDevice.current.model) only.")

                    VStack(alignment: .leading, spacing: 6) {
                        sectionHeader("Logo")
                        logoCard
                    }
                    sectionFooter("The logo on your home screen and inside the app.")

                    if let failure {
                        Text(failure)
                            .font(.system(size: 13))
                            .foregroundStyle(Theme.danger)
                            .padding(.horizontal, 14)
                    }

                    Color.clear.frame(height: 24)
                }
                .padding(.horizontal, 16)
                .padding(.top, 8)
            }
        }
        // Inline, like the other screens pushed from Settings.
        .navigationTitle("Appearance")
        .navigationBarTitleDisplayMode(.inline)
        .toolbar(.visible, for: .navigationBar)
    }

    // MARK: - Theme

    private var themeCard: some View {
        VStack(spacing: 0) {
            ForEach(Array(ColorTheme.allCases.enumerated()), id: \.element) { index, item in
                if index > 0 {
                    Rectangle()
                        .fill(Theme.separator)
                        .frame(height: 1)
                        .padding(.leading, 56)
                }
                themeRow(item)
            }
        }
        .background(Theme.background)
        .clipShape(RoundedRectangle(cornerRadius: 14, style: .continuous))
        .animation(Motion.snappy, value: theme.theme)
    }

    private func themeRow(_ item: ColorTheme) -> some View {
        let isSelected = theme.theme == item
        return Button {
            // No haptic here: HighlightRowButtonStyle already ticks on press-down.
            theme.choose(item)
        } label: {
            HStack(spacing: 12) {
                ZStack {
                    RoundedRectangle(cornerRadius: 8, style: .continuous)
                        .fill(item.iconBackground)
                        .frame(width: 30, height: 30)
                    Image(systemName: item.systemImage)
                        .font(.system(size: 14, weight: .semibold))
                        .foregroundStyle(Color.white)
                }
                .accessibilityHidden(true)
                Text(item.title)
                    .font(.system(size: 16))
                    .foregroundStyle(Theme.textPrimary)
                    .frame(maxWidth: .infinity, alignment: .leading)

                if isSelected {
                    Image(systemName: "checkmark")
                        .font(.system(size: 14, weight: .bold))
                        .foregroundStyle(Theme.accent)
                        .transition(Motion.iconSwap)
                }
            }
            .padding(.horizontal, 14)
            .padding(.vertical, 10)
            .contentShape(Rectangle())
        }
        .buttonStyle(HighlightRowButtonStyle())
        .accessibilityAddTraits(isSelected ? .isSelected : [])
        .accessibilityLabel(item.title)
    }

    // MARK: - Logo

    private var logoCard: some View {
        VStack(spacing: 0) {
            ForEach(Array(BrandLogoStyle.allCases.enumerated()), id: \.element) { index, style in
                if index > 0 {
                    Rectangle()
                        .fill(Theme.separator)
                        .frame(height: 1)
                        .padding(.leading, 70)
                }
                row(style)
            }
        }
        .background(Theme.background)
        .clipShape(RoundedRectangle(cornerRadius: 14, style: .continuous))
        .animation(Motion.snappy, value: preference.style)
    }

    private func row(_ style: BrandLogoStyle) -> some View {
        let isSelected = preference.style == style
        return Button {
            choose(style)
        } label: {
            HStack(spacing: 12) {
                BrandLogoMark(size: 44, style: style)
                VStack(alignment: .leading, spacing: 2) {
                    Text(style.title)
                        .font(.system(size: 16))
                        .foregroundStyle(Theme.textPrimary)
                    Text(subtitle(style))
                        .font(.system(size: 13))
                        .foregroundStyle(Theme.textSecondary)
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
            .padding(.vertical, 10)
            .contentShape(Rectangle())
        }
        .buttonStyle(HighlightRowButtonStyle())
        .accessibilityAddTraits(isSelected ? .isSelected : [])
        .accessibilityLabel(style.title)
        // The description is all that tells the two apart without seeing the marks.
        .accessibilityHint(subtitle(style))
    }

    private func subtitle(_ style: BrandLogoStyle) -> String {
        switch style {
        case .detailed: "The veil with its folds and shading."
        case .simple: "One flat shape."
        }
    }

    private func choose(_ style: BrandLogoStyle) {
        // No haptic here: HighlightRowButtonStyle already ticks on press-down.
        guard style != preference.style, !preference.isChanging else { return }
        failure = nil
        Task {
            do {
                try await preference.choose(style)
            } catch {
                failure = "The app icon couldn’t be changed. Try again."
            }
        }
    }

    // MARK: - Sections

    private func sectionHeader(_ title: String) -> some View {
        Text(title.uppercased())
            .font(.system(size: 13))
            .foregroundStyle(Theme.textSecondary)
            .padding(.horizontal, 14)
            .accessibilityAddTraits(.isHeader)
    }

    private func sectionFooter(_ text: String) -> some View {
        Text(text)
            .font(.system(size: 13))
            .foregroundStyle(Theme.textSecondary)
            .fixedSize(horizontal: false, vertical: true)
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(.horizontal, 14)
            .padding(.top, -6)
    }
}

#Preview {
    NavigationStack {
        AppearanceSettingsView()
    }
}
