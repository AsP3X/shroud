import SwiftUI

/// Centered day label — maps to `Date Chip` in `Conversation`.
struct ChatDateChip: View {
    let label: String

    var body: some View {
        Text(label)
            .font(.system(size: 12, weight: .medium))
            .foregroundStyle(Theme.textSecondary)
            .padding(.horizontal, 10)
            .padding(.vertical, 4)
            .background(Theme.textPrimary.opacity(0.06))
            .clipShape(Capsule())
    }
}

/// Soft accent banner under the first date chip.
struct ChatE2ENotice: View {
    var body: some View {
        HStack(spacing: 6) {
            Image(systemName: "lock.fill")
                .font(.system(size: 11, weight: .semibold))
                .foregroundStyle(Theme.accent)
            Text("Messages and calls are end-to-end encrypted")
                .font(.system(size: 12))
                .foregroundStyle(Theme.accent)
                .multilineTextAlignment(.center)
        }
        .padding(.horizontal, 12)
        .padding(.vertical, 6)
        .background(Theme.accentSoft)
        .clipShape(Capsule())
    }
}

#Preview {
    VStack(spacing: 8) {
        ChatDateChip(label: "Today")
        ChatE2ENotice()
    }
    .padding()
    .background(Theme.backgroundChat)
}
