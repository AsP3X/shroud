import SwiftUI

/// Scrollable main-tab screen under a Liquid Glass bar.
///
/// Collapsing screens (Chats / Contacts / Calls):
/// ```
/// (Edit)       Title       (actions)
/// ```
/// One row: glass controls at the sides, the plain title centred on the screen. Rows scroll
/// under the bar and the status bar, where the scroll edge effect fades them out.
///
/// Settings-style: the 32 pt title scrolls away with the content; the bar keeps only the
/// leading and trailing actions.
///
/// Human: The bar used to paint its own material gradient and watch the scroll offset to
/// thicken it. iOS 26 does that with `safeAreaBar` + the scroll edge effect, so the screen
/// no longer tracks the offset at all.
/// Agent: READS `tabBarClearance` to end the list at the floating tab bar. RETURNS a
/// `ScrollView` with the bar pinned by `glassTopBar`; the callers keep the same slots.
struct MainScrollScreen<NavLeading: View, NavTrailing: View, Accessory: View, Content: View>: View {
    let title: String
    /// When true the title lives in the bar; when false the accessory carries it.
    var collapsesTitle: Bool = true

    @ViewBuilder var navLeading: () -> NavLeading
    @ViewBuilder var navTrailing: () -> NavTrailing
    @ViewBuilder var accessory: () -> Accessory
    @ViewBuilder var content: () -> Content

    @Environment(\.tabBarClearance) private var tabBarClearance

    var body: some View {
        ScrollView {
            VStack(spacing: 0) {
                accessory()
                    .frame(maxWidth: .infinity, alignment: .leading)

                content()
            }
        }
        .scrollDismissesKeyboard(.interactively)
        // Last row ends at the floating tab bar's top edge instead of under it.
        .safeAreaPadding(.bottom, tabBarClearance)
        .glassTopBar {
            GlassBarRow {
                navLeading()
            } center: {
                if collapsesTitle {
                    GlassBarTitle(title: title)
                }
            } trailing: {
                navTrailing()
            }
        }
    }
}

// MARK: - Settings-style (title scrolls away)

extension MainScrollScreen where Accessory == ScrollAwayTitle {
    init(
        title: String,
        @ViewBuilder navLeading: @escaping () -> NavLeading,
        @ViewBuilder navTrailing: @escaping () -> NavTrailing,
        @ViewBuilder content: @escaping () -> Content
    ) {
        self.title = title
        self.collapsesTitle = false
        self.navLeading = navLeading
        self.navTrailing = navTrailing
        self.accessory = { ScrollAwayTitle(title: title) }
        self.content = content
    }
}

struct ScrollAwayTitle: View {
    let title: String

    var body: some View {
        Text(title)
            .font(.system(size: 32, weight: .bold))
            .foregroundStyle(Theme.textPrimary)
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(.horizontal, 16)
            .padding(.bottom, 10)
    }
}

#Preview("Collapsing title") {
    MainScrollScreen(title: "Chats", collapsesTitle: true) {
        GlassBarButton("Edit") {}
    } navTrailing: {
        GlassBarButton(systemImage: "square.and.pencil") {}
    } accessory: {
        SearchField(text: .constant(""))
            .padding(.horizontal, 16)
            .padding(.bottom, 10)
    } content: {
        LazyVStack(spacing: 0) {
            ForEach(0 ..< 24, id: \.self) { index in
                ChatRowView(
                    title: "Contact \(index)",
                    subtitle: "Preview text",
                    time: "9:41",
                    avatarGradient: AvatarView.gradient(for: "c\(index)")
                )
            }
        }
    }
    .background(Theme.background)
}
