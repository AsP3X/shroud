import SwiftUI

/// Rounded search field — matches Chats / Contacts header search in `iOS-App.pen`.
struct SearchField: View {
    @Binding var text: String
    var placeholder: String = "Search"

    @FocusState private var focused: Bool

    var body: some View {
        HStack(spacing: 6) {
            Image(systemName: "magnifyingglass")
                .font(.system(size: 15, weight: .medium))
                // Glyph adopts the accent while the field is live.
                .foregroundStyle(focused ? Theme.accent : Theme.textSecondary)
            TextField(placeholder, text: $text)
                .font(.system(size: 15))
                .foregroundStyle(Theme.textPrimary)
                .textInputAutocapitalization(.never)
                .autocorrectionDisabled()
                .submitLabel(.search)
                .focused($focused)
            if !text.isEmpty {
                Button {
                    text = ""
                } label: {
                    Image(systemName: "xmark.circle.fill")
                        .font(.system(size: 14))
                        .foregroundStyle(Theme.textSecondary)
                        .frame(width: 24, height: 24)
                        // 44 pt to the finger without moving the 24 pt glyph box.
                        .contentShape(Rectangle().inset(by: -10))
                }
                .pressable(scale: 0.8)
                .accessibilityLabel("Clear search")
                .transition(Motion.iconSwap)
            }
        }
        .padding(.horizontal, 10)
        .frame(height: 36)
        .background(Theme.backgroundGrouped)
        .clipShape(RoundedRectangle(cornerRadius: 10, style: .continuous))
        .overlay {
            // Decorative focus ring — must not intercept taps meant for the field.
            RoundedRectangle(cornerRadius: 10, style: .continuous)
                .strokeBorder(Theme.accent.opacity(focused ? 0.35 : 0), lineWidth: 1)
                .allowsHitTesting(false)
        }
        .animation(Motion.snappy, value: focused)
        .animation(Motion.snappy, value: text.isEmpty)
    }
}

#Preview {
    VStack(spacing: 12) {
        SearchField(text: .constant(""))
        SearchField(text: .constant("jane"))
    }
    .padding()
}
