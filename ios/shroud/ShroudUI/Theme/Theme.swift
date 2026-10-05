import SwiftUI

/// Design-system color tokens — values from `design-system.mdc`; never use raw hex in feature views.
enum Theme {
    // Asset is "AccentColor", the build's global accent (window tint for carets, alerts, menus),
    // so there's one brand indigo. A separate "Accent" asset would clash with it: both generate
    // the `accent` asset symbol.
    static let accent = Color("AccentColor")
    static let accentSoft = Color("AccentSoft")
    /// Accent for *text* on bubbles, cards and `accentSoft` fills (links, a preview's site name,
    /// `SecondaryButton`). Same as `accent` in light mode; lighter in dark mode so it keeps 4.5:1
    /// on the dark incoming bubble and on dark `accentSoft` (the web client's `--accent-text`).
    static let accentText = Color("AccentText")
    static let background = Color("Background")
    static let backgroundGrouped = Color("BackgroundGrouped")
    static let backgroundChat = Color("BackgroundChat")
    /// Behind the pages of the PDF viewer: #ECECF0 light, #111113 dark (`docs/file-sharing.md` §10.2).
    static let pdfCanvas = Color("PDFCanvas")
    /// The PDF viewer's pages sidebar and drawer: #F7F7F9 light, #1A1A1D dark.
    static let pdfSidebar = Color("PDFSidebar")
    static let bubbleIncoming = Color("BubbleIncoming")
    static let textPrimary = Color("TextPrimary")
    static let textSecondary = Color("TextSecondary")
    // Asset is "SeparatorLine": a "Separator" asset generates a symbol that collides with
    // UIKit's own `UIColor.separator`.
    static let separator = Color("SeparatorLine")
    /// Row disclosure chevron: design-system #C7C7CC in light mode (systemGray3), and dimmer
    /// than `textSecondary` in dark mode instead of a bright fixed grey.
    static let chevron = Color(uiColor: .systemGray3)
    static let online = Color("Online")
    static let danger = Color("Danger")
    /// Danger for small *text* on tinted fills; keeps 4.5:1 where `danger` doesn't.
    static let dangerText = Color("DangerText")
    /// Fill behind white text in outgoing bubbles. Accent's light value in both appearances:
    /// dark mode lightens `accent`, which would drop white body text below 4.5:1.
    static let bubbleOutgoing = Color("BubbleOutgoing")
    /// Unread-count fill for muted chats; solid so the white count keeps 4.5:1.
    static let mutedBadge = Color("MutedBadge")

    // Warning and success cards; each has a dark variant so the cards don't glow in dark mode.
    static let warningBackground = Color("WarningBackground")
    static let warningText = Color("WarningText")
    static let warningIcon = Color("WarningIcon")
    static let successBackground = Color("SuccessBackground")
    static let successText = Color("SuccessText")
    /// Fill behind white text for success states (Saved, Unlocked); the same dark green in both
    /// modes. `online` is only 2.2:1 behind white, and successText's dark value is a pale green
    /// meant for text on dark cards.
    static let successFill = Color("SuccessFill")
    static let strengthPanelBackground = Color("StrengthPanel")
    static let strengthTrackBackground = Color("StrengthTrack")

    static let brandGradient = LinearGradient(
        colors: [
            Color(red: 124 / 255, green: 122 / 255, blue: 255 / 255),
            Color(red: 94 / 255, green: 92 / 255, blue: 230 / 255),
        ],
        startPoint: .top,
        endPoint: .bottom
    )
}
