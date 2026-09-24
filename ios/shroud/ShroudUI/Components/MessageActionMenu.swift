import SwiftUI
import UIKit

/// Long-press focus stack — reaction bar + context menu from `Conversation — * Message Menu`.
/// Layout order is owned by the host: emoji bar → **message** → menu (Telegram).
struct MessageActionMenu: View {
    let isMine: Bool
    var onReaction: (String) -> Void
    var onAction: (MessageMenuAction) -> Void

    var body: some View {
        VStack(spacing: 9) {
            MessageReactionBar(onReaction: { emoji, _ in onReaction(emoji) }, onMore: { onAction(.moreReactions) })
            MessageContextMenuCard(isMine: isMine, onAction: onAction)
        }
        .frame(width: 250)
    }
}

// MARK: - Reaction bar (above the focused bubble)

/// Where each emoji of the bar (or grid) is on screen, so a pick can fly from it.
/// A reference type in `@State`: writing a frame never redraws the bar.
final class ReactionPickFrames {
    var frames: [String: CGRect] = [:]
}

struct MessageReactionBar: View {
    /// The emoji, and where it was on screen (the start of its flight to the chip).
    var onReaction: (String, CGRect?) -> Void
    var onMore: () -> Void
    /// 0…1 continuous progress (drives opacity + offset; avoid Bool for smooth close).
    var progress: CGFloat = 1
    /// Our reactions on the message: ringed, and tapping one takes it back.
    var selected: Set<String> = []
    /// Inside `MessageReactionPanel`, which draws the capsule itself and moves the quick seven
    /// into the grid's first row when it grows.
    var glide: Namespace.ID?

    @State private var pickFrames = ReactionPickFrames()

    static let reactions = ["❤️", "🔥", "👍", "😢", "🙏", "😮", "👎"]
    /// Telegram's double tap (and the VoiceOver action standing in for it).
    static let quickReaction = "❤️"
    /// Telegram's standard reaction set, shown when the bar expands ("More"). The quick seven
    /// come first so they keep their places.
    static let expanded: [String] = reactions + [
        "🥰", "👏", "😁", "🤔", "🤯", "😱", "🤬", "🎉", "🤩", "🤮", "💩", "👌", "🕊️", "🤡",
        "🥱", "🥴", "😍", "🐳", "❤️‍🔥", "🌚", "🌭", "💯", "🤣", "⚡", "🍌", "🏆", "💔", "🤨",
        "😐", "🍓", "🍾", "💋", "🖕", "😈", "😴", "😭", "🤓", "👻", "👨‍💻", "👀", "🎃", "🙈",
        "😇", "😨", "🤝", "✍️", "🤗", "🫡", "🎅", "🎄", "☃️", "💅", "🤪", "🗿", "🆒", "💘",
        "🙉", "🦄", "😘", "💊", "🙊", "😎", "👾", "🤷", "😡",
    ]
    private static let emojiSize: CGFloat = 34
    private static let moreSize: CGFloat = 30
    private static let itemSpacing: CGFloat = 6
    private static let horizontalPadding: CGFloat = 12
    private static let verticalPadding: CGFloat = 8

    /// Intrinsic capsule width (emojis + more + spacing + padding).
    static var barWidth: CGFloat {
        let emojiCount = CGFloat(reactions.count)
        let items = emojiCount + 1 // more button
        return emojiCount * emojiSize
            + moreSize
            + (items - 1) * itemSpacing
            + horizontalPadding * 2
    }

    /// Intrinsic capsule height.
    static var barHeight: CGFloat {
        max(emojiSize, moreSize) + verticalPadding * 2
    }

    var body: some View {
        HStack(spacing: Self.itemSpacing) {
            ForEach(Self.reactions, id: \.self) { emoji in
                Button {
                    onReaction(emoji, pickFrames.frames[emoji])
                } label: {
                    Text(emoji)
                        .font(.system(size: 26))
                        .frame(width: Self.emojiSize, height: Self.emojiSize)
                        .onGeometryChange(for: CGRect.self, of: { $0.frame(in: .global) }) { frame in
                            pickFrames.frames[emoji] = frame
                        }
                        .background {
                            if selected.contains(emoji) {
                                Circle().fill(Color.white.opacity(0.18))
                                    .frame(width: Self.emojiSize + 4, height: Self.emojiSize + 4)
                            }
                        }
                        .contentShape(Rectangle())
                        .reactionGlide(emoji, in: glide)
                }
                // Emoji squash hard on press — the most playful control in the app.
                .pressable(scale: 0.78, dimming: 0)
                .accessibilityLabel(emoji)
                .accessibilityAddTraits(selected.contains(emoji) ? .isSelected : [])
            }
            Button(action: onMore) {
                Image(systemName: "chevron.down")
                    .font(.system(size: 12, weight: .bold))
                    .foregroundStyle(Color.white.opacity(0.85))
                    .frame(width: Self.moreSize, height: Self.moreSize)
                    .background(Color.white.opacity(0.12))
                    .clipShape(Circle())
            }
            .pressable(scale: 0.85, dimming: 0)
            .accessibilityLabel("More reactions")
        }
        .padding(.horizontal, Self.horizontalPadding)
        .padding(.vertical, Self.verticalPadding)
        .fixedSize(horizontal: true, vertical: true)
        .background {
            if glide == nil {
                Capsule()
                    .fill(MessageReactionPanel.surface)
            }
        }
        .overlay {
            if glide == nil {
                Capsule()
                    .stroke(Color.white.opacity(0.08), lineWidth: 0.5)
            }
        }
        // Fade with the hero flight (no extra slide — hero owns the travel).
        .opacity(progress)
        .allowsHitTesting(progress > 0.5)
    }
}

// MARK: - Expanded reactions (the bar's "More")

/// The full standard set with a search field over it, inside `MessageReactionPanel` once the
/// bar has grown. Eight columns at the bar's pitch, so the quick seven land in the first row a
/// hair from where they were, and the eighth takes the place of "More".
struct MessageReactionGrid: View {
    var onReaction: (String, CGRect?) -> Void
    /// The "fewer" button: back to the bar.
    var onCollapse: () -> Void
    var selected: Set<String> = []
    @Binding var query: String
    /// What `query` leaves of the set (`ReactionSearch.matches`), sized for by the panel.
    var results: [String]
    var glide: Namespace.ID?

    @State private var pickFrames = ReactionPickFrames()
    @FocusState private var searchFocused: Bool

    static let columns = 8
    static let cell: CGFloat = 40
    static let padding: CGFloat = 10
    /// The grid's side padding: eight cells fill the bar's width.
    static var sidePadding: CGFloat { (MessageReactionBar.barWidth - cell * CGFloat(columns)) / 2 }
    static let searchHeight: CGFloat = 36
    /// Between the search row and the grid.
    static let searchGap: CGFloat = 6
    /// Rows shown before the grid scrolls.
    static let visibleRows: CGFloat = 5.5

    /// The panel's tallest: the search row and 5.5 rows of emoji.
    static var height: CGFloat { height(rows: Int(visibleRows.rounded(.up))) }

    /// The panel's height showing `rows` rows of emoji: as tall as they need, up to 5.5 rows
    /// (the rest scrolls); never less than one row, which the "no matches" line takes.
    static func height(rows: Int) -> CGFloat {
        let shown = min(CGFloat(max(rows, 1)), visibleRows)
        return padding + searchHeight + searchGap + cell * shown + padding
    }

    /// How many rows `count` emoji fill.
    static func rows(for count: Int) -> Int {
        (count + columns - 1) / columns
    }

    var body: some View {
        VStack(spacing: Self.searchGap) {
            searchRow
                .padding(.horizontal, Self.padding)
                .padding(.top, Self.padding)
            ScrollView {
                if results.isEmpty {
                    Text("No reactions match “\(query.trimmingCharacters(in: .whitespaces))”")
                        .font(.system(size: 14))
                        .foregroundStyle(Color.white.opacity(0.55))
                        .lineLimit(1)
                        .padding(.horizontal, 20)
                        .frame(maxWidth: .infinity, minHeight: Self.cell)
                } else {
                    LazyVGrid(
                        columns: Array(repeating: GridItem(.fixed(Self.cell), spacing: 0), count: Self.columns),
                        spacing: 0
                    ) {
                        ForEach(results, id: \.self) { emoji in
                            Button {
                                onReaction(emoji, pickFrames.frames[emoji])
                            } label: {
                                Text(emoji)
                                    .font(.system(size: 26))
                                    .frame(width: Self.cell, height: Self.cell)
                                    .onGeometryChange(for: CGRect.self, of: { $0.frame(in: .global) }) { frame in
                                        pickFrames.frames[emoji] = frame
                                    }
                                    .background {
                                        if selected.contains(emoji) {
                                            Circle().fill(Color.white.opacity(0.18))
                                        }
                                    }
                                    .contentShape(Rectangle())
                                    .reactionGlide(emoji, in: glide)
                            }
                            .pressable(scale: 0.78, dimming: 0)
                            .accessibilityLabel(emoji)
                            .accessibilityAddTraits(selected.contains(emoji) ? .isSelected : [])
                        }
                    }
                    .padding(.horizontal, Self.sidePadding)
                    .padding(.bottom, Self.padding)
                    // The results themselves switch at once; only the panel around them animates.
                    // A crowd of cells fading and sliding under a shrinking panel reads as noise.
                    .transaction(value: query) { $0.animation = nil }
                }
            }
            .scrollIndicators(.hidden)
            .scrollDismissesKeyboard(.immediately)
        }
    }

    /// The search field and, beside it, the way back to the bar.
    private var searchRow: some View {
        HStack(spacing: 8) {
            HStack(spacing: 6) {
                Image(systemName: "magnifyingglass")
                    .font(.system(size: 14, weight: .medium))
                    .foregroundStyle(Color.white.opacity(searchFocused ? 0.85 : 0.55))
                TextField("", text: $query, prompt: Text("Search reactions").foregroundStyle(Color.white.opacity(0.45)))
                    .font(.system(size: 15))
                    .foregroundStyle(.white)
                    .tint(.white)
                    .textInputAutocapitalization(.never)
                    .autocorrectionDisabled()
                    .submitLabel(.search)
                    .focused($searchFocused)
                    .accessibilityLabel("Search reactions")
                if !query.isEmpty {
                    Button {
                        query = ""
                    } label: {
                        Image(systemName: "xmark.circle.fill")
                            .font(.system(size: 14))
                            .foregroundStyle(Color.white.opacity(0.55))
                            .frame(width: 24, height: 24)
                            .contentShape(Rectangle())
                    }
                    .pressable(scale: 0.8, dimming: 0)
                    .accessibilityLabel("Clear search")
                    .transition(Motion.iconSwap)
                }
            }
            .padding(.horizontal, 10)
            .frame(height: Self.searchHeight)
            .background(Capsule().fill(Color.white.opacity(0.1)))
            .overlay {
                Capsule().strokeBorder(Color.white.opacity(searchFocused ? 0.35 : 0), lineWidth: 1)
                    .allowsHitTesting(false)
            }
            .animation(Motion.snappy, value: searchFocused)
            .animation(Motion.snappy, value: query.isEmpty)
            Button {
                searchFocused = false
                onCollapse()
            } label: {
                Image(systemName: "chevron.up")
                    .font(.system(size: 12, weight: .bold))
                    .foregroundStyle(Color.white.opacity(0.85))
                    .frame(width: 30, height: 30)
                    .background(Color.white.opacity(0.12))
                    .clipShape(Circle())
            }
            .pressable(scale: 0.85, dimming: 0)
            .accessibilityLabel("Fewer reactions")
        }
    }
}

// MARK: - Reaction panel (the bar, and the set it grows into)

/// The reaction bar and the full set it grows into, one surface over the lifted bubble.
///
/// Human: "More" grows the capsule in place into a rounded panel — down from the bar's top
/// edge, kept on screen — with a search field over the grid and a "fewer" button back. The
/// quick seven slide into the grid's first row while the rest fade in; the panel's shape,
/// size and place animate together, so it feels like one thing changing rather than a swap.
/// Agent: The host gives it the bar's frame and the container; it places itself with
/// `frame(expanded:bar:container:safeArea:height:)` and animates the change of `expanded` and
/// of its own height (`Motion.standard`). READS the safe area it is given — the keyboard
/// included, so an open search never leaves the panel under it. Draw it in a container-sized
/// slot aligned top-leading.
struct MessageReactionPanel: View {
    var onReaction: (String, CGRect?) -> Void
    @Binding var expanded: Bool
    /// 0…1: fades with the hero flight, like the bar did.
    var progress: CGFloat = 1
    var selected: Set<String> = []
    /// Where the bar sits (the panel grows down from its top edge), in the container's space.
    var bar: CGRect
    var container: CGSize
    var safeArea: EdgeInsets

    @State private var query = ""
    @Namespace private var glide
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    /// The dark surface both states share (not the theme: the menu floats over a dimmed thread).
    static let surface = Color(red: 0.14, green: 0.14, blue: 0.16).opacity(0.94)
    static let expandedCornerRadius: CGFloat = 22

    /// Where the panel sits: on the bar's frame collapsed; expanded, `height` tall — as tall as
    /// the rows a search leaves — grown down from the bar's top edge, no higher than 8 pt under
    /// the status bar and no lower than 10 pt above the home indicator or the keyboard, and no
    /// taller than that leaves room for.
    static func frame(
        expanded: Bool,
        bar: CGRect,
        container: CGSize,
        safeArea: EdgeInsets,
        height: CGFloat = MessageReactionGrid.height
    ) -> CGRect {
        guard expanded else { return bar }
        let height = min(height, container.height - safeArea.top - safeArea.bottom - 16)
        let lowest = container.height - safeArea.bottom - 10 - height
        return CGRect(x: bar.minX, y: max(safeArea.top + 8, min(bar.minY, lowest)), width: bar.width, height: height)
    }

    private var shape: RoundedRectangle {
        RoundedRectangle(
            cornerRadius: expanded ? Self.expandedCornerRadius : MessageReactionBar.barHeight / 2,
            style: .continuous
        )
    }

    var body: some View {
        let results = ReactionSearch.matches(query, in: MessageReactionBar.expanded)
        let wanted = MessageReactionGrid.height(rows: MessageReactionGrid.rows(for: results.count))
        let frame = Self.frame(expanded: expanded, bar: bar, container: container, safeArea: safeArea, height: wanted)
        ZStack(alignment: .top) {
            if expanded {
                MessageReactionGrid(
                    onReaction: onReaction,
                    onCollapse: { setExpanded(false) },
                    selected: selected,
                    query: $query,
                    results: results,
                    glide: glide
                )
                .transition(.opacity)
            } else {
                MessageReactionBar(
                    onReaction: onReaction,
                    onMore: { setExpanded(true) },
                    selected: selected,
                    glide: glide
                )
                .transition(.opacity)
            }
        }
        .frame(width: frame.width, height: frame.height, alignment: .top)
        .background { shape.fill(Self.surface) }
        .overlay { shape.stroke(Color.white.opacity(0.08), lineWidth: 0.5) }
        .clipShape(shape)
        .position(x: frame.midX, y: frame.midY)
        // A search that leaves fewer rows shrinks the panel around them, and back. Keyed on the
        // wanted height, not the frame: the bar has to ride the hero flight with no lag.
        .animation(Motion.respecting(reduceMotion, Motion.standard), value: wanted)
        .opacity(progress)
        .allowsHitTesting(progress > 0.5)
    }

    private func setExpanded(_ next: Bool) {
        Haptics.impact(.light)
        if !next { query = "" }
        withAnimation(Motion.respecting(reduceMotion, Motion.standard)) {
            expanded = next
        }
    }
}

private extension View {
    /// Moves an emoji between the bar and the grid's first row when the panel grows or shrinks:
    /// whichever of the two is being shown is the source, the other follows it in.
    @ViewBuilder
    func reactionGlide(_ emoji: String, in namespace: Namespace.ID?) -> some View {
        if let namespace {
            matchedGeometryEffect(id: emoji, in: namespace, properties: .position)
        } else {
            self
        }
    }
}

// MARK: - Context menu card (below the focused bubble)

struct MessageContextMenuCard: View {
    let isMine: Bool
    var onAction: (MessageMenuAction) -> Void
    /// 0…1 continuous progress (drives opacity + offset; avoid Bool for smooth close).
    var progress: CGFloat = 1
    /// The message contains a link: adds "Copy Link" under "Copy" (`Conversation — Link Message Menu`).
    var hasLink: Bool = false

    static let width: CGFloat = 250
    private static let rowHeight: CGFloat = 44

    /// The card's height: the muted "read" row on your own messages, the primary actions
    /// ("Copy Link" only with a link), "Select", and a 1 pt hairline between rows.
    static func height(isMine: Bool, hasLink: Bool) -> CGFloat {
        let rows: CGFloat = (isMine ? 1 : 0) + (hasLink ? 6 : 5) + 1
        return rows * rowHeight + (rows - 1)
    }

    var body: some View {
        VStack(spacing: 0) {
            if isMine {
                menuRow(
                    title: "read",
                    systemImage: "checkmark",
                    destructive: false,
                    muted: true,
                    action: {}
                )
                separator
            }

            ForEach(Array(primaryActions.enumerated()), id: \.element.id) { index, action in
                if index > 0 { separator }
                menuRow(
                    title: action.title,
                    systemImage: action.systemImage,
                    destructive: action.isDestructive,
                    muted: false
                ) {
                    onAction(action)
                }
            }

            separator
            menuRow(
                title: MessageMenuAction.select.title,
                systemImage: MessageMenuAction.select.systemImage,
                destructive: false,
                muted: false
            ) {
                onAction(.select)
            }
        }
        .frame(width: Self.width)
        .background {
            RoundedRectangle(cornerRadius: 14, style: .continuous)
                .fill(Color(red: 0.12, green: 0.12, blue: 0.14).opacity(0.94))
        }
        .overlay {
            RoundedRectangle(cornerRadius: 14, style: .continuous)
                .stroke(Color.white.opacity(0.08), lineWidth: 0.5)
        }
        .clipShape(RoundedRectangle(cornerRadius: 14, style: .continuous))
        // Fade with the hero flight (no extra slide — hero owns the travel).
        .opacity(progress)
        .allowsHitTesting(progress > 0.5)
    }

    private var primaryActions: [MessageMenuAction] {
        hasLink
            ? [.reply, .copy, .copyLink, .pin, .forward, .delete]
            : [.reply, .copy, .pin, .forward, .delete]
    }

    private var separator: some View {
        Rectangle()
            .fill(Color.white.opacity(0.08))
            .frame(height: 1)
    }

    private func menuRow(
        title: String,
        systemImage: String,
        destructive: Bool,
        muted: Bool,
        action: @escaping () -> Void
    ) -> some View {
        Button(action: action) {
            HStack(spacing: 12) {
                Image(systemName: systemImage)
                    .font(.system(size: 15, weight: .medium))
                    .foregroundStyle(
                        destructive
                            ? Theme.danger
                            : (muted ? Color.white.opacity(0.45) : Color.white.opacity(0.85))
                    )
                    .frame(width: 22)
                Text(title)
                    .font(.system(size: 16))
                    .foregroundStyle(
                        destructive
                            ? Theme.danger
                            : (muted ? Color.white.opacity(0.55) : Color.white)
                    )
                Spacer()
            }
            .padding(.horizontal, 14)
            .frame(height: Self.rowHeight)
            .contentShape(Rectangle())
        }
        // Dark menu — highlight with a light wash rather than the grouped-background token.
        .buttonStyle(HighlightRowButtonStyle(fill: Color.white.opacity(0.1)))
        .disabled(muted)
    }
}

// MARK: - Layout (Telegram)

/// Where the long-press menu's parts go, by Telegram's rules.
///
/// Human: The reaction bar always sits right on top of the bubble and the action card right
/// under it. When that stack does not fit where the bubble is, the *bubble* moves — down from
/// under the header, up from the composer — just far enough, and the dimmed thread behind it
/// stays where it was (Telegram lifts the message out of the list the same way). A message too
/// tall to show together with its card starts under the reaction bar and the stack scrolls,
/// opening at its bottom so the card is in reach.
/// Agent: Pure geometry in the overlay's (full-screen) points. `safeArea` is the overlay's
/// safe area — status bar, home indicator, and the keyboard when it is up. Margins are
/// Telegram's: 8 pt under the status bar, 10 pt above the bottom inset, 12 pt from the sides.
enum MessageMenuLayout {
    /// The fixed parts of the stack.
    struct Metrics: Equatable {
        var reactionSize: CGSize
        var cardSize: CGSize
        /// Between the reaction bar and the bubble, and between the bubble and the card.
        var spacing: CGFloat = 10
        /// Closest the bar or the card may come to the screen's sides.
        var sideInset: CGFloat = 12
        /// Below the status bar.
        var topMargin: CGFloat = 8
        /// Above the home indicator or the keyboard.
        var bottomMargin: CGFloat = 10
    }

    /// The resting stack for one bubble.
    struct Plan: Equatable {
        /// The lifted bubble at rest, in scroll-content coordinates — the same as screen
        /// coordinates unless the stack `scrolls`.
        var hero: CGRect
        /// Height of the scrollable stack: the container's own height unless the message is
        /// too tall to show together with its card.
        var contentHeight: CGFloat
        var containerSize: CGSize
        /// Highest the reaction bar may sit, in screen coordinates.
        var reactionMinY: CGFloat

        /// True when the message and its card are taller than the screen allows.
        var scrolls: Bool { contentHeight > containerSize.height + 0.5 }
        /// Scroll offset that shows the stack's bottom — where a scrolling stack opens.
        var initialOffset: CGFloat { max(0, contentHeight - containerSize.height) }
    }

    /// The resting stack for a bubble whose list slot is `source`.
    static func plan(
        source: CGRect,
        container: CGSize,
        safeArea: EdgeInsets,
        metrics: Metrics
    ) -> Plan {
        let reactionMinY = safeArea.top + metrics.topMargin
        let bottomLimit = container.height - safeArea.bottom - metrics.bottomMargin
        // Highest the bubble may go: the reaction bar has to fit above it.
        let highest = reactionMinY + metrics.reactionSize.height + metrics.spacing
        let belowBubble = metrics.spacing + metrics.cardSize.height

        var y = max(source.minY, highest)
        let overshoot = y + source.height + belowBubble - bottomLimit
        if overshoot > 0 { y -= overshoot }
        y = max(y, highest)

        let hero = CGRect(x: source.minX, y: y, width: source.width, height: source.height)
        let stackBottom = hero.maxY + belowBubble
        return Plan(
            hero: hero,
            contentHeight: max(container.height, stackBottom + container.height - bottomLimit),
            containerSize: container,
            reactionMinY: reactionMinY
        )
    }

    /// Reaction bar and card around the bubble as it is drawn right now (screen coordinates —
    /// mid-flight, or scrolled), on the bubble's side and inside the screen's sides.
    ///
    /// Agent: The bar never rises above `plan.reactionMinY`; over a scrolled tall message it
    /// stays pinned there, on top of the bubble, as Telegram's does.
    static func chrome(
        hero: CGRect,
        isMine: Bool,
        plan: Plan,
        metrics: Metrics
    ) -> (reactions: CGRect, card: CGRect) {
        let width = plan.containerSize.width
        func minX(for itemWidth: CGFloat) -> CGFloat {
            let preferred = isMine ? hero.maxX - itemWidth : hero.minX
            let upper = max(metrics.sideInset, width - metrics.sideInset - itemWidth)
            return min(max(preferred, metrics.sideInset), upper)
        }
        let reactionY = max(plan.reactionMinY, hero.minY - metrics.spacing - metrics.reactionSize.height)
        return (
            CGRect(origin: CGPoint(x: minX(for: metrics.reactionSize.width), y: reactionY), size: metrics.reactionSize),
            CGRect(origin: CGPoint(x: minX(for: metrics.cardSize.width), y: hero.maxY + metrics.spacing), size: metrics.cardSize)
        )
    }
}

// MARK: - Overlay

/// The long-press menu over the dimmed thread: reaction bar, the lifted bubble, and the action
/// card, placed by `MessageMenuLayout` and flown out of the bubble's slot in the list.
///
/// Human: `progress` 0 draws the bubble exactly where it sits in the thread (the host hides the
/// list's own copy meanwhile), 1 is the resting stack; the bar and card ride along with the
/// bubble and fade with it. Only a message too tall for the screen gets a scroll view, opened
/// at its bottom so the card is visible first — the rest of the time the stack is static.
/// Agent: The host animates `progress` and owns what the buttons do. READS the overlay's safe
/// area (keyboard included) on every layout; WRITES only the scroll offset of a tall stack.
struct MessageMenuOverlay<Hero: View, Card: View>: View {
    /// The bubble's frame in global coordinates when the hold began.
    let sourceGlobalFrame: CGRect
    let isMine: Bool
    /// Height of `card` (see `MessageContextMenuCard.height(isMine:hasLink:)`).
    let cardHeight: CGFloat
    /// 0 = bubble in its list slot, 1 = menu open.
    let progress: CGFloat
    var onReaction: (String, CGRect?) -> Void
    /// Our reactions on the message, ringed in the bar and the grid.
    var selectedReactions: Set<String> = []
    /// False for a message that can't take reactions (still sending, failed, a todo): no bar,
    /// and the bubble no longer makes room for one.
    var showsReactions: Bool = true
    /// A tap outside the stack.
    var onBackdropTap: () -> Void
    @ViewBuilder var hero: () -> Hero
    @ViewBuilder var card: () -> Card

    /// Content offset of a scrolling stack; nil until the scroll view reports it.
    @State private var scrollOffset: CGFloat?
    /// "More" grows the bar in place into the full set (Telegram), over bubble and card; the
    /// panel animates it and puts it back.
    @State private var showsAllReactions = false

    var body: some View {
        GeometryReader { outer in
            let safeArea = outer.safeAreaInsets
            GeometryReader { proxy in
                stack(in: proxy, safeArea: safeArea)
            }
            .ignoresSafeArea()
        }
    }

    @ViewBuilder
    private func stack(in proxy: GeometryProxy, safeArea: EdgeInsets) -> some View {
        let container = proxy.frame(in: .global)
        let source = CGRect(
            x: sourceGlobalFrame.minX - container.minX,
            y: sourceGlobalFrame.minY - container.minY,
            width: max(1, sourceGlobalFrame.width),
            height: max(1, sourceGlobalFrame.height)
        )
        let metrics = MessageMenuLayout.Metrics(
            reactionSize: showsReactions
                ? CGSize(width: MessageReactionBar.barWidth, height: MessageReactionBar.barHeight)
                : .zero,
            cardSize: CGSize(width: MessageContextMenuCard.width, height: cardHeight)
        )
        let plan = MessageMenuLayout.plan(source: source, container: proxy.size, safeArea: safeArea, metrics: metrics)
        // Scroll-content coordinates: while scrolled, the list slot sits `offset` further down.
        let offset = plan.scrolls ? (scrollOffset ?? plan.initialOffset) : 0
        let heroInContent = Self.lerp(source.offsetBy(dx: 0, dy: offset), plan.hero, progress)
        let heroOnScreen = heroInContent.offsetBy(dx: 0, dy: -offset)
        let chrome = MessageMenuLayout.chrome(hero: heroOnScreen, isMine: isMine, plan: plan, metrics: metrics)

        ZStack(alignment: .topLeading) {
            MessageMenuBackdrop(onTap: onBackdropTap, progress: progress)

            if plan.scrolls {
                ScrollView {
                    bubbleAndCard(hero: heroInContent, card: chrome.card.offsetBy(dx: 0, dy: offset))
                        .frame(width: proxy.size.width, height: plan.contentHeight, alignment: .topLeading)
                        .background {
                            // The backdrop is under the scroll view; empty space dismisses here.
                            Color.clear
                                .contentShape(Rectangle())
                                .onTapGesture(perform: onBackdropTap)
                        }
                }
                .scrollIndicators(.hidden)
                .defaultScrollAnchor(.bottom)
                .onScrollGeometryChange(for: CGFloat.self, of: { $0.contentOffset.y }) { _, newOffset in
                    scrollOffset = newOffset
                }
                .ignoresSafeArea()
                .allowsHitTesting(progress > 0.5)
            } else {
                bubbleAndCard(hero: heroOnScreen, card: chrome.card)
            }

            // Over the bubble: on a scrolled tall message the bar stays pinned at the top;
            // grown into the full set, the panel keeps the bar's top edge where it can.
            if showsReactions {
                MessageReactionPanel(
                    onReaction: onReaction,
                    expanded: $showsAllReactions,
                    progress: progress,
                    selected: selectedReactions,
                    bar: chrome.reactions,
                    container: proxy.size,
                    safeArea: safeArea
                )
                .frame(width: proxy.size.width, height: proxy.size.height, alignment: .topLeading)
            }
        }
        .frame(width: proxy.size.width, height: proxy.size.height)
    }

    /// Extra width offered to the lifted bubble beyond its measured frame.
    ///
    /// Human: The list reports a bubble's frame rounded to the pixel grid, which can be a hair
    /// narrower than the bubble's own ideal width. Offered exactly that, a one-line bubble no
    /// longer "fits" and re-wraps onto two lines the moment the menu opens. One spare point,
    /// pinned to the bubble's side, keeps the list's layout.
    private static var heroSlack: CGFloat { 1 }

    /// The lifted bubble and the card, placed in one coordinate space.
    private func bubbleAndCard(hero heroFrame: CGRect, card cardFrame: CGRect) -> some View {
        let slotWidth = heroFrame.width + Self.heroSlack
        return ZStack(alignment: .topLeading) {
            hero()
                // Same size as the list bubble, so progress 0 is a seamless handoff.
                .frame(width: slotWidth, height: heroFrame.height, alignment: isMine ? .topTrailing : .topLeading)
                .position(
                    x: isMine ? heroFrame.maxX - slotWidth / 2 : heroFrame.minX + slotWidth / 2,
                    y: heroFrame.midY
                )
                .allowsHitTesting(false)
            card()
                .frame(width: cardFrame.width, height: cardFrame.height, alignment: .top)
                .position(x: cardFrame.midX, y: cardFrame.midY)
        }
    }

    private static func lerp(_ a: CGRect, _ b: CGRect, _ t: CGFloat) -> CGRect {
        CGRect(
            x: a.minX + (b.minX - a.minX) * t,
            y: a.minY + (b.minY - a.minY) * t,
            width: a.width + (b.width - a.width) * t,
            height: a.height + (b.height - a.height) * t
        )
    }
}

enum MessageMenuAction: String, Identifiable {
    case reply, copy, copyLink, edit, pin, forward, select, delete, moreReactions

    var id: String { rawValue }

    var title: String {
        switch self {
        case .reply: "Reply"
        case .copy: "Copy"
        case .copyLink: "Copy Link"
        case .edit: "Edit"
        case .pin: "Pin"
        case .forward: "Forward"
        case .select: "Select"
        case .delete: "Delete"
        case .moreReactions: "More"
        }
    }

    var systemImage: String {
        switch self {
        case .reply: "arrowshape.turn.up.left"
        case .copy: "doc.on.doc"
        case .copyLink: "link"
        case .edit: "pencil"
        case .pin: "pin"
        case .forward: "arrowshape.turn.up.right"
        case .select: "checkmark.circle"
        case .delete: "trash"
        case .moreReactions: "chevron.down"
        }
    }

    var isDestructive: Bool { self == .delete }
}

// MARK: - Backdrop

/// Dim veil driven by a single continuous progress.
/// Solid scrim only — Material opacity animation stutters on dismiss.
struct MessageMenuBackdrop: View {
    var onTap: () -> Void
    /// 0…1 continuous open amount.
    var progress: CGFloat = 1

    var body: some View {
        // Layered solid dim approximates the old frosted look without per-frame blur cost.
        ZStack {
            Color.black.opacity(0.42 * progress)
            Color(red: 0.06, green: 0.06, blue: 0.08).opacity(0.28 * progress)
        }
        .ignoresSafeArea()
        .contentShape(Rectangle())
        .onTapGesture(perform: onTap)
        .allowsHitTesting(progress > 0.05)
    }
}

// MARK: - Decoded image cache (avoid UIImage(data:) on long-press)

/// Populated when chat bubbles first decode; long-press reuses the same instance.
enum DecodedImageCache {
    nonisolated(unsafe) private static var storage: [UUID: UIImage] = [:]
    /// Byte count of the data that produced the cached image — so preview → full replaces correctly.
    nonisolated(unsafe) private static var dataCounts: [UUID: Int] = [:]

    static func store(_ id: UUID, image: UIImage) {
        storage[id] = image
    }

    static func image(for id: UUID) -> UIImage? {
        storage[id]
    }

    /// Chats locked: every decoded photo leaves memory with the history it came from.
    static func removeAll() {
        storage.removeAll()
        dataCounts.removeAll()
    }

    /// Drop decoded bitmaps for deleted messages so nothing stays in RAM.
    static func remove(ids: [UUID]) {
        for id in ids {
            storage.removeValue(forKey: id)
            dataCounts.removeValue(forKey: id)
        }
    }

    /// Cache hit for the same byte payload, else decode once and store.
    static func image(forMessage id: UUID, data: Data?) -> UIImage? {
        guard let data else { return nil }
        if let cached = storage[id], dataCounts[id] == data.count {
            return cached
        }
        guard let image = UIImage(data: data) else { return nil }
        storage[id] = image
        dataCounts[id] = data.count
        return image
    }
}

// MARK: - Hero (same bubble views as the list so close lands without a pop)

struct MessageMenuHeroContent: View {
    let message: MessagingController.ChatMessage
    let timeLabel: String
    /// Kept for API stability; image hero reads `DecodedImageCache` via `ImageMessageBubble`.
    let heroImage: UIImage?
    /// Mirrors the list bubble so a voice note lifts off with the same fold.
    var inTranscriptTail = false
    /// The same quote header the list bubble draws — without it the hero would be shorter
    /// than the bubble it flies out of, and the open would visibly jump.
    var reply: ReplyQuoteContent? = nil
    /// The link preview's picture, resolved by the host exactly as for the list bubble.
    var linkPreviewImage: LinkPreviewImage = .none
    /// The list bubble's reaction chips (not tappable here: the hero ignores touches).
    var reactions: [ReactionChipContent] = []

    var body: some View {
        switch message.kind {
        case .image:
            ImageMessageBubble(
                message: message,
                time: timeLabel,
                isRowEmbedded: false,
                reply: reply,
                reactions: reactions
            )
        case .video:
            VideoMessageBubble(
                message: message,
                time: timeLabel,
                isRowEmbedded: false,
                reply: reply,
                reactions: reactions
            )
        case .voice:
            VoiceMessageBubble(
                message: message,
                time: timeLabel,
                inTranscriptTail: inTranscriptTail,
                revealsArrival: false,
                reply: reply,
                reactions: reactions
            )
        case .text:
            MessageBubbleView(
                text: message.text,
                time: timeLabel,
                isMine: message.isMine,
                isDeleted: message.deleted,
                receipt: message.receipt,
                isRowEmbedded: false,
                reply: reply,
                linkPreview: message.linkPreview,
                linkPreviewImage: linkPreviewImage,
                reactions: reactions
            )
        case .todo:
            TodoMessageBubble(
                text: message.text,
                time: timeLabel,
                isDone: message.todoDone == true,
                onToggle: {}
            )
        }
    }
}

#Preview {
    ZStack {
        MessageMenuBackdrop(onTap: {}, progress: 1)
        VStack(spacing: 10) {
            MessageReactionBar(onReaction: { _, _ in }, onMore: {}, progress: 1)
            MessageBubbleView(
                text: "Hey! How are you?",
                time: "14:22",
                isMine: true,
                receipt: .read
            )
            MessageContextMenuCard(isMine: true, onAction: { _ in }, progress: 1)
        }
        .padding(24)
    }
}
