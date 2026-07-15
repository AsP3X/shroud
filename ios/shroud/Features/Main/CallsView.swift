import SwiftUI

/// Calls tab — recent history placeholder.
/// Sticky large title collapses into a compact bar title while scrolling.
struct CallsView: View {
    @State private var searchText = ""

    private var filtered: [CallListItem] {
        let query = searchText.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !query.isEmpty else { return MainSampleData.calls }
        return MainSampleData.calls.filter {
            $0.name.localizedCaseInsensitiveContains(query)
                || $0.detail.localizedCaseInsensitiveContains(query)
        }
    }

    var body: some View {
        MainScrollScreen(title: "Calls", collapsesTitle: true) {
            Button("Edit") {}
                .font(.system(size: 16))
                .foregroundStyle(Theme.accent)
        } navTrailing: {
            Button {} label: {
                Image(systemName: "phone.badge.plus")
                    .font(.system(size: 18, weight: .semibold))
                    .foregroundStyle(Theme.accent)
            }
            .buttonStyle(.plain)
            .accessibilityLabel("New call")
        } accessory: {
            SearchField(text: $searchText)
                .padding(.horizontal, 16)
                .padding(.bottom, 10)
        } content: {
            LazyVStack(spacing: 0) {
                ForEach(Array(filtered.enumerated()), id: \.element.id) { index, call in
                    HStack(spacing: 12) {
                        AvatarView(
                            initials: AvatarView.initials(for: call.name),
                            gradient: call.gradient
                        )

                        VStack(alignment: .leading, spacing: 3) {
                            Text(call.name)
                                .font(.system(size: 16, weight: .semibold))
                                .foregroundStyle(
                                    call.direction == .missed ? Theme.danger : Theme.textPrimary
                                )
                            HStack(spacing: 4) {
                                Image(systemName: directionIcon(call.direction))
                                    .font(.system(size: 12, weight: .semibold))
                                Text(call.detail)
                                    .font(.system(size: 14))
                            }
                            .foregroundStyle(Theme.textSecondary)
                        }

                        Spacer(minLength: 0)

                        Button {} label: {
                            Image(systemName: "phone.fill")
                                .font(.system(size: 16, weight: .semibold))
                                .foregroundStyle(Theme.accent)
                                .frame(width: 36, height: 36)
                                .background(Theme.accentSoft)
                                .clipShape(Circle())
                        }
                        .buttonStyle(.plain)
                        .accessibilityLabel("Call \(call.name)")
                    }
                    .padding(.horizontal, 16)
                    .padding(.vertical, 10)
                    .background(Theme.background)

                    if index < filtered.count - 1 {
                        Rectangle()
                            .fill(Theme.separator)
                            .frame(height: 1)
                            .padding(.leading, 80)
                    }
                }

                if filtered.isEmpty {
                    VStack(spacing: 8) {
                        Text("No recent calls")
                            .font(.system(size: 16, weight: .semibold))
                            .foregroundStyle(Theme.textPrimary)
                        Text("Voice and video calls will show up here.")
                            .font(.system(size: 14))
                            .foregroundStyle(Theme.textSecondary)
                            .multilineTextAlignment(.center)
                    }
                    .frame(maxWidth: .infinity)
                    .padding(.top, 48)
                    .padding(.horizontal, 24)
                }

                Color.clear.frame(height: 16)
            }
        }
        .background(Theme.background)
    }

    private func directionIcon(_ direction: CallListItem.Direction) -> String {
        switch direction {
        case .incoming: "phone.arrow.down.left"
        case .outgoing: "phone.arrow.up.right"
        case .missed: "phone.down.fill"
        }
    }
}

#Preview {
    CallsView()
}
