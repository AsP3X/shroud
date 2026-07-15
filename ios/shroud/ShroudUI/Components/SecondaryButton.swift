import SwiftUI

/// Secondary capsule action — accent-soft fill with accent label.
struct SecondaryButton: View {
    let title: String
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            Text(title)
                .font(.system(size: 17, weight: .semibold))
                .foregroundStyle(Theme.accent)
                .frame(maxWidth: .infinity)
                .frame(height: 52)
                .background(Theme.accentSoft)
                .clipShape(Capsule())
        }
        .buttonStyle(.plain)
    }
}
