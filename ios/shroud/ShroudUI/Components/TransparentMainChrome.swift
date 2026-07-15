import SwiftUI

/// Scrollable main-tab screen with Telegram-style transparent chrome.
///
/// Collapsing screens (Chats / Contacts / Calls):
/// ```
/// [ Edit ]     Title     [ actions ]
/// ```
/// Single-line sticky bar: compact centered title between side buttons.
///
/// Settings: large title scrolls away; bar keeps only leading/trailing actions.
struct MainScrollScreen<NavLeading: View, NavTrailing: View, Accessory: View, Content: View>: View {
    let title: String
    /// When true, title stays sticky & centered and shrinks on scroll.
    var collapsesTitle: Bool = true

    @ViewBuilder var navLeading: () -> NavLeading
    @ViewBuilder var navTrailing: () -> NavTrailing
    @ViewBuilder var accessory: () -> Accessory
    @ViewBuilder var content: () -> Content

    @State private var scrollOffsetY: CGFloat = 0

    // MARK: - Metrics

    private let navRowHeight: CGFloat = 44
    private let collapseDistance: CGFloat = 36

    private var collapseProgress: CGFloat {
        guard collapsesTitle else { return 0 }
        return min(1, max(0, scrollOffsetY / collapseDistance))
    }

    private var scrollBoost: CGFloat {
        if collapsesTitle {
            return collapseProgress
        }
        return min(1, max(0, scrollOffsetY / 36))
    }

    private var gradientTopOpacity: CGFloat { 0.88 + 0.08 * scrollBoost }
    private var gradientMidOpacity: CGFloat { 0.55 + 0.2 * scrollBoost }

    private var stickyHeaderHeight: CGFloat { navRowHeight }

    private var scrollTopInset: CGFloat { navRowHeight }

    var body: some View {
        ZStack(alignment: .top) {
            ScrollView {
                VStack(spacing: 0) {
                    Color.clear
                        .frame(height: scrollTopInset)
                        .accessibilityHidden(true)

                    accessory()
                        .frame(maxWidth: .infinity, alignment: .leading)

                    content()
                }
                .background {
                    GeometryReader { proxy in
                        Color.clear.preference(
                            key: MainScrollOffsetKey.self,
                            value: -proxy.frame(in: .named("mainScroll")).minY
                        )
                    }
                }
            }
            .coordinateSpace(name: "mainScroll")
            .onPreferenceChange(MainScrollOffsetKey.self) { value in
                if abs(value - scrollOffsetY) > 0.5 {
                    scrollOffsetY = value
                }
            }
            .scrollDismissesKeyboard(.interactively)

            stickyHeader
        }
    }

    // MARK: - Sticky header

    private var stickyHeader: some View {
        VStack(spacing: 0) {
            if collapsesTitle {
                collapsingTitleBar
            } else {
                actionsOnlyBar
            }
        }
        .frame(maxWidth: .infinity)
        .frame(height: stickyHeaderHeight, alignment: .top)
        .background { telegramGradientBackground }
    }

    /// Single row like Telegram compact nav: Edit | Title | actions.
    private var collapsingTitleBar: some View {
        HStack(spacing: 0) {
            HStack(spacing: 0) {
                navLeading()
                Spacer(minLength: 0)
            }
            .frame(maxWidth: .infinity, alignment: .leading)

            Text(title)
                .font(.system(size: 17, weight: .semibold))
                .foregroundStyle(Theme.textPrimary)
                .lineLimit(1)
                .minimumScaleFactor(0.8)
                .multilineTextAlignment(.center)
                .frame(maxWidth: .infinity)
                .accessibilityAddTraits(.isHeader)

            HStack(spacing: 0) {
                Spacer(minLength: 0)
                navTrailing()
            }
            .frame(maxWidth: .infinity, alignment: .trailing)
        }
        .padding(.horizontal, 16)
        .frame(height: navRowHeight)
    }

    private var actionsOnlyBar: some View {
        HStack(spacing: 0) {
            HStack(spacing: 0) {
                navLeading()
                Spacer(minLength: 0)
            }
            .frame(maxWidth: .infinity, alignment: .leading)

            HStack(spacing: 0) {
                Spacer(minLength: 0)
                navTrailing()
            }
            .frame(maxWidth: .infinity, alignment: .trailing)
        }
        .padding(.horizontal, 16)
        .frame(height: navRowHeight)
    }

    private var telegramGradientBackground: some View {
        ZStack(alignment: .top) {
            Rectangle()
                .fill(.ultraThinMaterial)
                .mask(
                    LinearGradient(
                        stops: [
                            .init(color: .white.opacity(0.95), location: 0),
                            .init(color: .white.opacity(0.55), location: 0.45),
                            .init(color: .white.opacity(0.12), location: 0.82),
                            .init(color: .clear, location: 1),
                        ],
                        startPoint: .top,
                        endPoint: .bottom
                    )
                )
                .opacity(0.75 + 0.2 * Double(scrollBoost))

            LinearGradient(
                stops: [
                    .init(
                        color: Theme.background.opacity(Double(gradientTopOpacity)),
                        location: 0
                    ),
                    .init(
                        color: Theme.background.opacity(Double(gradientMidOpacity)),
                        location: 0.42
                    ),
                    .init(
                        color: Theme.background.opacity(0.12 + 0.1 * Double(scrollBoost)),
                        location: 0.78
                    ),
                    .init(color: Theme.background.opacity(0), location: 1),
                ],
                startPoint: .top,
                endPoint: .bottom
            )
        }
        .padding(.bottom, 20)
        .ignoresSafeArea(edges: .top)
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

// MARK: - Preference

private struct MainScrollOffsetKey: PreferenceKey {
    static var defaultValue: CGFloat = 0
    static func reduce(value: inout CGFloat, nextValue: () -> CGFloat) {
        value = nextValue()
    }
}
