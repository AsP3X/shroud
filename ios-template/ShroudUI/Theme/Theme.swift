import SwiftUI

/// Design-system color tokens — values from `design-system.mdc`; never use raw hex in feature views.
enum Theme {
    static let accent = Color("Accent")
    static let accentSoft = Color("AccentSoft")
    static let background = Color("Background")
    static let backgroundGrouped = Color("BackgroundGrouped")
    static let backgroundChat = Color("BackgroundChat")
    static let bubbleIncoming = Color("BubbleIncoming")
    static let textPrimary = Color("TextPrimary")
    static let textSecondary = Color("TextSecondary")
    static let separator = Color("Separator")
    static let online = Color("Online")
    static let danger = Color("Danger")
}
