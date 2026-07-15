import SwiftUI

/// Full-width capsule primary action from the design system recipe.
struct PrimaryButton: View {
    let title: String
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            Text(title)
                .font(.system(size: 17, weight: .semibold))
                .foregroundStyle(Color.white)
                .frame(maxWidth: .infinity)
                .frame(height: 52)
                .background(Theme.accent)
                .clipShape(Capsule())
        }
        .buttonStyle(.plain)
        .accessibilityLabel(title)
    }
}

#Preview {
    PrimaryButton(title: "Get Started", action: {})
        .padding()
        .background(Theme.backgroundGrouped)
}
