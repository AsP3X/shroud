import SwiftUI

/// Picks the Shroud logo: the detailed veil from the app icon, or the simplified flat one.
///
/// Human: One choice covers the home screen icon and every in-app mark, so they never disagree.
/// iOS confirms an icon change with its own alert; that is not ours to suppress.
/// Agent: WRITES through `BrandLogoPreference.choose(_:)`; READS its `style`.
struct AppearanceSettingsView: View {
    @State private var preference = BrandLogoPreference.shared
    @State private var failure: String?

    var body: some View {
        GroupedScreen {
            ScrollView {
                VStack(alignment: .leading, spacing: 14) {
                    Text("The logo on your home screen and inside the app.")
                        .font(.system(size: 14))
                        .foregroundStyle(Theme.textSecondary)
                        .padding(.vertical, 4)

                    logoCard

                    if let failure {
                        Text(failure)
                            .font(.system(size: 13))
                            .foregroundStyle(Theme.danger)
                            .padding(.horizontal, 4)
                    }

                    Color.clear.frame(height: 24)
                }
                .padding(.horizontal, 16)
            }
        }
        .navigationTitle("Appearance")
        .navigationBarTitleDisplayMode(.large)
        .toolbar(.visible, for: .navigationBar)
    }

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
    }

    private func subtitle(_ style: BrandLogoStyle) -> String {
        switch style {
        case .detailed: "The veil with its folds and shading."
        case .simple: "One flat shape."
        }
    }

    private func choose(_ style: BrandLogoStyle) {
        guard style != preference.style, !preference.isChanging else { return }
        Haptics.impact(.light)
        failure = nil
        Task {
            do {
                try await preference.choose(style)
            } catch {
                failure = "The app icon couldn’t be changed. Try again."
            }
        }
    }
}

#Preview {
    NavigationStack {
        AppearanceSettingsView()
    }
}
