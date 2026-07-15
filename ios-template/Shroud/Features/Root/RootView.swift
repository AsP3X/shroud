import SwiftUI

/// Bootstrap landing screen — replaced by onboarding from the design file.
struct RootView: View {
    let viewModel: RootViewModel

    var body: some View {
        VStack(spacing: 24) {
            Spacer()

            VStack(spacing: 8) {
                Text(viewModel.welcomeTitle)
                    .font(.system(size: 32, weight: .bold))
                    .foregroundStyle(Theme.textPrimary)

                Text(viewModel.welcomeSubtitle)
                    .font(.system(size: 16))
                    .foregroundStyle(Theme.textSecondary)
                    .multilineTextAlignment(.center)
            }

            PrimaryButton(title: "Get Started", action: {})

            Spacer()
        }
        .padding(.horizontal, 20)
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .background(Theme.backgroundGrouped)
    }
}

#Preview {
    RootView(viewModel: RootViewModel())
}
