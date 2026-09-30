import SwiftUI

/// Bottom tab strip — maps to reusable `Tab Bar` in `iOS-App.pen`.
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
        case .contacts: "person.crop.circle.fill"
        case .calls: "phone.fill"
        case .settings: "gearshape.fill"
        }
    }

    /// Tabs whose list the bottom search filters in place; the others hand search to Chats.
    var isSearchable: Bool {
        self == .chats || self == .contacts
    }
}

/// Telegram-style (iOS 26) floating tab bar: equal-width tabs in a liquid-glass capsule with
/// a sliding selection lens, and a round search button beside it that grows into a search field.
///
/// Human: Press anywhere on the tabs and the lens lifts under the finger; drag to scrub across
/// tabs (the one under the lens lights up), release to select. Tapping search morphs the bar
/// into a search field with a ✕ — the parent owns what the query filters.
/// Agent: WRITES `selection`, `isSearching`, `query`; READS `badges`. Placement (bottom gap,
/// side insets, keyboard) belongs to `MainTabView`; this view only lays out its own row.
struct FloatingTabBar: View {
    @Binding var selection: MainTab
    @Binding var isSearching: Bool
    @Binding var query: String
    var searchFocus: FocusState<Bool>.Binding
    /// Red count badge per tab; a missing or zero entry shows none.
    var badges: [MainTab: Int] = [:]

    /// Capsule height: 56pt items inside a 4pt glass rim (Telegram's metrics).
    static let height: CGFloat = 64
    /// The search field (and its ✕) are slimmer than the tab capsule.
    static let searchHeight: CGFloat = 48

    private let rim: CGFloat = 4
    private let itemHeight: CGFloat = 56
    private let spacing: CGFloat = 8
    /// How far the lens swells while a finger holds it.
    private let liftScale: CGFloat = 1.12

    private let tabs = MainTab.allCases

    @Environment(\.colorScheme) private var colorScheme
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @Namespace private var glassNamespace
    @GestureState(resetTransaction: Transaction(animation: Motion.standard))
    private var lensDrag: LensDrag?

    /// The lens while a finger is down: where it is and which tab it covers.
    private struct LensDrag: Equatable {
        var x: CGFloat
        var tab: MainTab
    }

    var body: some View {
        // Zero blend distance: pressed glass swells a few points, and it must not bridge the
        // 8pt gap between the capsule and the search circle.
        GlassEffectContainer(spacing: 0) {
            HStack(spacing: spacing) {
                if isSearching {
                    searchField
                    closeSearchButton
                } else {
                    tabCapsule
                    searchButton
                }
            }
        }
        .frame(height: isSearching ? Self.searchHeight : Self.height, alignment: .bottom)
    }

    // MARK: - Tabs

    private var tabCapsule: some View {
        GeometryReader { geo in
            let itemWidth = max(1, (geo.size.width - rim * 2) / CGFloat(tabs.count))
            let lensX = lensDrag?.x ?? lensOffset(for: selection, itemWidth: itemWidth)
            let lifted = lensDrag != nil
            let lensScale = lifted && !reduceMotion ? liftScale : 1
            let lens = Capsule()
                .frame(width: itemWidth, height: itemHeight)
                .scaleEffect(lensScale)
                .offset(x: lensX)

            ZStack(alignment: .leading) {
                Capsule()
                    .fill(lensFill(lifted: lifted))
                    .frame(width: itemWidth, height: itemHeight)
                    .scaleEffect(lensScale)
                    .offset(x: lensX)

                // Two copies of the row: plain outside the lens, accent (and magnified while
                // held) inside it, so a sliding lens tints whatever it passes over.
                itemRow(itemWidth: itemWidth, color: Theme.textPrimary, magnification: 1)
                    .mask(alignment: .leading) {
                        Rectangle()
                            .overlay(alignment: .leading) {
                                lens.blendMode(.destinationOut)
                            }
                            .compositingGroup()
                    }
                itemRow(itemWidth: itemWidth, color: Theme.accent, magnification: lensScale)
                    .mask(alignment: .leading) { lens }
                    .accessibilityHidden(true)

                badgeRow(itemWidth: itemWidth)
            }
            .padding(rim)
            .contentShape(Capsule())
            .gesture(lensGesture(itemWidth: itemWidth))
        }
        .frame(height: Self.height)
        .glassEffect(.regular.interactive(), in: .capsule)
        .glassEffectID("tabs", in: glassNamespace)
        .accessibilityElement(children: .contain)
        // VoiceOver reads the items as tabs ("tab, 1 of 4"), like the system bar this replaces.
        .accessibilityAddTraits(.isTabBar)
    }

    private func itemRow(itemWidth: CGFloat, color: Color, magnification: CGFloat) -> some View {
        HStack(spacing: 0) {
            ForEach(tabs) { tab in
                TabItemLabel(tab: tab, isSelected: selection == tab)
                    .foregroundStyle(color)
                    .scaleEffect(lensDrag?.tab == tab ? magnification : 1)
                    .frame(width: itemWidth, height: itemHeight)
                    .accessibilityElement(children: .ignore)
                    .accessibilityLabel(tab.title)
                    .accessibilityValue(badgeText(for: tab).map { "\($0) unread" } ?? "")
                    .accessibilityAddTraits(selection == tab ? [.isButton, .isSelected] : .isButton)
                    .accessibilityAction { select(tab) }
            }
        }
    }

    /// Badges sit above both rows so the lens never tints them.
    private func badgeRow(itemWidth: CGFloat) -> some View {
        HStack(spacing: 0) {
            ForEach(tabs) { tab in
                Color.clear
                    .frame(width: itemWidth, height: itemHeight)
                    .overlay(alignment: .topLeading) {
                        if let text = badgeText(for: tab) {
                            // Trailing edge 24pt right of the icon's centre, 5pt below the top.
                            HStack(spacing: 0) {
                                Spacer(minLength: 0)
                                TabBadge(text: text)
                            }
                            .frame(width: itemWidth / 2 + 24)
                            .padding(.top, 5)
                            .transition(Motion.iconSwap)
                        }
                    }
            }
        }
        .allowsHitTesting(false)
        .accessibilityHidden(true)
        .animation(Motion.bouncy, value: badges)
    }

    private func lensGesture(itemWidth: CGFloat) -> some Gesture {
        DragGesture(minimumDistance: 0)
            .updating($lensDrag) { value, state, transaction in
                let hovered = tab(at: value.location.x, itemWidth: itemWidth)
                if var drag = state {
                    // Follows the finger 1:1 — no animation while it moves.
                    drag.x = clampedLensX(
                        lensOffset(for: tab(at: value.startLocation.x, itemWidth: itemWidth), itemWidth: itemWidth)
                            + value.translation.width,
                        itemWidth: itemWidth
                    )
                    drag.tab = hovered
                    state = drag
                } else {
                    // Touch-down: the lens jumps to the pressed tab and lifts.
                    Haptics.impact(.light)
                    transaction.animation = Motion.respecting(reduceMotion, Motion.snappy)
                    state = LensDrag(x: lensOffset(for: hovered, itemWidth: itemWidth), tab: hovered)
                }
            }
            .onEnded { value in
                select(tab(at: value.location.x, itemWidth: itemWidth))
            }
    }

    private func select(_ tab: MainTab) {
        // Parent `MainTabView` owns direction-aware content animation via the binding.
        withAnimation(Motion.respecting(reduceMotion, Motion.standard)) {
            selection = tab
        }
    }

    private func tab(at x: CGFloat, itemWidth: CGFloat) -> MainTab {
        let index = Int(((x - rim) / itemWidth).rounded(.down))
        return tabs[min(max(index, 0), tabs.count - 1)]
    }

    private func lensOffset(for tab: MainTab, itemWidth: CGFloat) -> CGFloat {
        CGFloat(tabs.firstIndex(of: tab) ?? 0) * itemWidth
    }

    private func clampedLensX(_ x: CGFloat, itemWidth: CGFloat) -> CGFloat {
        min(max(x, 0), CGFloat(tabs.count - 1) * itemWidth)
    }

    private func badgeText(for tab: MainTab) -> String? {
        guard let count = badges[tab], count > 0 else { return nil }
        return count > 99 ? "99+" : "\(count)"
    }

    /// A faint wash over the glass (lighter in dark mode) that brightens while held.
    private func lensFill(lifted: Bool) -> Color {
        colorScheme == .dark
            ? Color.white.opacity(lifted ? 0.2 : 0.15)
            : Color.black.opacity(lifted ? 0.1 : 0.07)
    }

    // MARK: - Search

    private var searchButton: some View {
        Button {
            withAnimation(Motion.respecting(reduceMotion, Motion.gentle)) {
                isSearching = true
            }
        } label: {
            Image(systemName: "magnifyingglass")
                .font(.system(size: 22, weight: .medium))
                .foregroundStyle(Theme.textPrimary)
                .frame(width: Self.height, height: Self.height)
                .contentShape(Circle())
        }
        .buttonStyle(PressableButtonStyle(scale: 1, dimming: 0))
        .glassEffect(.regular.interactive(), in: .circle)
        .glassEffectID("search", in: glassNamespace)
        .accessibilityLabel("Search")
    }

    private var searchField: some View {
        HStack(spacing: 8) {
            Image(systemName: "magnifyingglass")
                .font(.system(size: 17, weight: .medium))
                .foregroundStyle(Theme.textSecondary)
            TextField("Search", text: $query)
                .font(.system(size: 17))
                .foregroundStyle(Theme.textPrimary)
                .textInputAutocapitalization(.never)
                .autocorrectionDisabled()
                .submitLabel(.search)
                .focused(searchFocus)
            if !query.isEmpty {
                Button {
                    query = ""
                } label: {
                    Image(systemName: "xmark.circle.fill")
                        .font(.system(size: 17))
                        .foregroundStyle(Theme.textSecondary)
                        .frame(width: 28, height: 28)
                        // 44 pt to the finger without moving the 28 pt glyph box.
                        .contentShape(Rectangle().inset(by: -8))
                }
                .pressable(scale: 0.8)
                .accessibilityLabel("Clear search")
                .transition(Motion.iconSwap)
            }
        }
        .padding(.leading, 14)
        .padding(.trailing, 10)
        .frame(maxWidth: .infinity)
        .frame(height: Self.searchHeight)
        .glassEffect(.regular.interactive(), in: .capsule)
        .glassEffectID("search", in: glassNamespace)
        .animation(Motion.snappy, value: query.isEmpty)
    }

    private var closeSearchButton: some View {
        Button {
            withAnimation(Motion.respecting(reduceMotion, Motion.gentle)) {
                isSearching = false
            }
        } label: {
            Image(systemName: "xmark")
                .font(.system(size: 17, weight: .semibold))
                .foregroundStyle(Theme.textPrimary)
                .frame(width: Self.searchHeight, height: Self.searchHeight)
                .contentShape(Circle())
        }
        .buttonStyle(PressableButtonStyle(scale: 1, dimming: 0))
        .glassEffect(.regular.interactive(), in: .circle)
        .glassEffectID("close", in: glassNamespace)
        .accessibilityLabel("Close search")
    }
}

/// Icon over a 10pt semibold title, laid out on Telegram's grid (icon centred 20pt from the
/// item's top, title 8pt off its bottom).
private struct TabItemLabel: View {
    let tab: MainTab
    let isSelected: Bool
    /// Counts selections only: bouncing on `isSelected` itself also bounced the tab being left.
    @State private var bounceTrigger = 0

    var body: some View {
        VStack(spacing: 1) {
            Image(systemName: tab.systemImage)
                .font(.system(size: tab.symbolPointSize, weight: .medium))
                .symbolEffect(.bounce, value: bounceTrigger)
                .frame(height: 32)
            Text(tab.title)
                .font(.system(size: 10, weight: .semibold))
                .lineLimit(1)
                .minimumScaleFactor(0.8)
                .frame(height: 12)
        }
        .padding(.top, 4)
        .padding(.bottom, 7)
        .onChange(of: isSelected) { _, selected in
            if selected { bounceTrigger += 1 }
        }
    }
}

private extension MainTab {
    /// SF Symbols run different widths at one point size; these land each glyph in Telegram's
    /// ~23pt icon box (the double bubble is wide, so it runs smaller).
    var symbolPointSize: CGFloat {
        self == .chats ? 20 : 23
    }
}

private struct TabBadge: View {
    let text: String

    var body: some View {
        Text(text)
            .font(.system(size: 13))
            .monospacedDigit()
            .foregroundStyle(Color.white)
            .padding(.horizontal, 5)
            .frame(minWidth: 18, minHeight: 18)
            .background(Capsule().fill(Theme.danger))
    }
}

#Preview {
    @Previewable @State var selection: MainTab = .chats
    @Previewable @State var isSearching = false
    @Previewable @State var query = ""
    @Previewable @FocusState var searchFocused: Bool

    ZStack {
        LinearGradient(
            colors: [Theme.accentSoft, Theme.background],
            startPoint: .topLeading,
            endPoint: .bottomTrailing
        )
        .ignoresSafeArea()
        VStack {
            Spacer()
            FloatingTabBar(
                selection: $selection,
                isSearching: $isSearching,
                query: $query,
                searchFocus: $searchFocused,
                badges: [.chats: 3]
            )
            .padding(.horizontal, 20)
        }
    }
}
