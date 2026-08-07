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
    // Asset is "SeparatorLine": a "Separator" asset generates a symbol that collides with
    // UIKit's own `UIColor.separator`.
    static let separator = Color("SeparatorLine")
    static let online = Color("Online")
    static let danger = Color("Danger")

    static let warningBackground = Color(red: 253 / 255, green: 241 / 255, blue: 220 / 255)
    static let warningText = Color(red: 138 / 255, green: 94 / 255, blue: 12 / 255)
    static let warningIcon = Color(red: 185 / 255, green: 125 / 255, blue: 16 / 255)
    static let successBackground = Color(red: 230 / 255, green: 247 / 255, blue: 236 / 255)
    static let successText = Color(red: 29 / 255, green: 122 / 255, blue: 62 / 255)
    static let strengthPanelBackground = Color(red: 250 / 255, green: 250 / 255, blue: 252 / 255)
    static let strengthTrackBackground = Color(red: 236 / 255, green: 236 / 255, blue: 239 / 255)

    static let brandGradient = LinearGradient(
        colors: [
            Color(red: 124 / 255, green: 122 / 255, blue: 255 / 255),
            Color(red: 94 / 255, green: 92 / 255, blue: 230 / 255),
        ],
        startPoint: .top,
        endPoint: .bottom
    )
}
