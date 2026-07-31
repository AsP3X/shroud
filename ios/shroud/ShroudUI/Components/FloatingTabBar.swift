import SwiftUI

/// Bottom tab strip — maps to reusable `Tab Bar` in `iOS-App.pen`.
/// Uses iOS liquid glass via `glassEffect`.
enum MainTab: Int, CaseIterable, Identifiable, Hashable {
    case chats
    case contacts
    case calls
    case settings

    var id: Int { rawValue }

    var title: String {
        switch self {
        case .chats: "Chats"
        case .contacts: "Contacts"
        case .calls: "Calls"
        case .settings: "Settings"
        }
    }

    var systemImage: String {
        switch self {
        case .chats: "bubble.left.and.bubble.right.fill"
        case .contacts: "person.2.fill"
        case .calls: "phone.fill"
        case .settings: "gearshape.fill"
        }
    }
}

/// Floating liquid-glass tab bar with a sliding selection pill (`matchedGeometryEffect`).
struct FloatingTabBar: View {
    @Binding var selection: MainTab
    @Namespace private var tabNamespace

    private let selectionSpring = Motion.snappy

    var body: some View {
        GlassEffectContainer {
            HStack(spacing: 0) {
                ForEach(MainTab.allCases) { tab in
                    tabButton(tab)
                }
            }
            .padding(6)
            .frame(height: 56)
            .glassEffect(
                .regular.interactive(),
                in: RoundedRectangle(cornerRadius: 28, style: .continuous)
            )
        }
        .padding(.horizontal, 16)
        .padding(.bottom, 12)
        .accessibilityElement(children: .contain)
    }

    private func tabButton(_ tab: MainTab) -> some View {
        let selected = selection == tab
        return Button {
            // Parent `MainTabView` owns direction-aware content animation via the binding.
            // The press haptic already fired on touch-down (`PressableButtonStyle`).
            withAnimation(selectionSpring) {
                selection = tab
            }
        } label: {
            VStack(spacing: 2) {
                Image(systemName: tab.systemImage)
                    .font(.system(size: 18, weight: .semibold))
                    .symbolEffect(.bounce, value: selected)
                    // Active glyph sits a hair larger — reinforces the pill without moving layout.
                    .scaleEffect(selected ? 1.08 : 1)
                Text(tab.title)
                    .font(.system(size: 10, weight: selected ? .semibold : .medium))
            }
            .foregroundStyle(selected ? Theme.accent : Theme.textSecondary)
            .frame(maxWidth: .infinity, maxHeight: .infinity)
            .background {
                if selected {
                    RoundedRectangle(cornerRadius: 22, style: .continuous)
                        .fill(Theme.accentSoft.opacity(0.92))
                        .matchedGeometryEffect(id: "tabSelectionPill", in: tabNamespace)
                }
            }
            .contentShape(Rectangle())
            .animation(selectionSpring, value: selected)
        }
        .pressable(scale: 0.9, dimming: 0)
        .accessibilityLabel(tab.title)
        .accessibilityAddTraits(selected ? .isSelected : [])
    }
}

#Preview {
    ZStack {
        Theme.backgroundGrouped.ignoresSafeArea()
        LinearGradient(
            colors: [Theme.accentSoft, Theme.background],
            startPoint: .topLeading,
            endPoint: .bottomTrailing
        )
        .ignoresSafeArea()
        VStack {
            Spacer()
            FloatingTabBar(selection: .constant(.chats))
        }
    }
}
