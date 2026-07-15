import SwiftUI

/// Full-width capsule primary action from the design system recipe.
struct PrimaryButton: View {
    let title: String
    var showsArrow = true
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            HStack(spacing: 8) {
                Text(title)
                    .font(.system(size: 17, weight: .semibold))
                if showsArrow {
                    Image(systemName: "arrow.right")
                        .font(.system(size: 18, weight: .semibold))
                }
            }
            .foregroundStyle(Color.white)
            .frame(maxWidth: .infinity)
            .frame(height: 54)
            .background(Theme.accent)
            .clipShape(Capsule())
            .shadow(color: Theme.accent.opacity(0.25), radius: 20, y: 8)
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
