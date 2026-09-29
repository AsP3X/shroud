import SwiftUI
import UIKit

/// Light, dark, or whatever this iPhone is set to.
enum ColorTheme: String, CaseIterable, Identifiable {
    case system
    case light
    case dark

    var id: String { rawValue }

    var title: String {
        switch self {
        case .system: "System"
        case .light: "Light"
        case .dark: "Dark"
        }
    }

    var systemImage: String {
        switch self {
        case .system: "circle.lefthalf.filled"
        case .light: "sun.max.fill"
        case .dark: "moon.fill"
        }
    }

    var iconBackground: Color {
        switch self {
        case .system: Theme.textSecondary
        case .light: Color(red: 255 / 255, green: 159 / 255, blue: 10 / 255)
        // The app icon's deepest indigo.
        case .dark: Color(red: 52 / 255, green: 50 / 255, blue: 184 / 255)
        }
    }

    var userInterfaceStyle: UIUserInterfaceStyle {
        switch self {
        case .system: .unspecified
        case .light: .light
        case .dark: .dark
        }
    }
}

/// The colour theme picked in Settings › Appearance, kept on this iPhone only.
///
/// Human: Applied to the window (`WindowColorTheme`), beneath SwiftUI's `preferredColorScheme`.
/// The call screen and the photo and video compose screens still turn the window dark while they
/// are up, and it falls back to this choice when they go. A `preferredColorScheme` at the root
/// would cancel theirs instead: the outermost one wins, and even a nil there overrides a child's.
/// A logout's wipe removes the stored choice with every other setting; `forget()` drops the one
/// in memory, so Welcome follows the system again.
/// Agent: READS/WRITES UserDefaults `shroud.theme` (the web client's localStorage key).
@MainActor
@Observable
final class ColorThemePreference {
    static let shared = ColorThemePreference()
    static let defaultsKey = "shroud.theme"

    private(set) var theme: ColorTheme
    private let defaults: UserDefaults

    init(defaults: UserDefaults = .standard) {
        self.defaults = defaults
        theme = defaults.string(forKey: Self.defaultsKey).flatMap(ColorTheme.init(rawValue:)) ?? .system
    }

    func choose(_ newTheme: ColorTheme) {
        theme = newTheme
        // System is the default, so nothing is stored for it.
        if newTheme == .system {
            defaults.removeObject(forKey: Self.defaultsKey)
        } else {
            defaults.set(newTheme.rawValue, forKey: Self.defaultsKey)
        }
    }

    /// Back to System after a logout.
    func forget() {
        choose(.system)
    }
}

/// Puts the chosen theme on the window this view lives in; a change cross-fades.
///
/// Human: UIKit's window override is the layer under SwiftUI's `preferredColorScheme`, which
/// writes to the root view controller instead. So a screen that asks for dark still gets it,
/// and hands back to the window's theme when it asks for nil. Sheets and alerts inherit it too.
struct WindowColorTheme: UIViewRepresentable {
    let theme: ColorTheme

    func makeUIView(context: Context) -> ApplierView {
        let view = ApplierView()
        view.isUserInteractionEnabled = false
        view.style = theme.userInterfaceStyle
        return view
    }

    func updateUIView(_ view: ApplierView, context: Context) {
        view.apply(theme.userInterfaceStyle)
    }

    final class ApplierView: UIView {
        var style: UIUserInterfaceStyle = .unspecified

        override func didMoveToWindow() {
            super.didMoveToWindow()
            // The first frame already in the chosen theme: no fade at launch.
            window?.overrideUserInterfaceStyle = style
        }

        func apply(_ newStyle: UIUserInterfaceStyle) {
            guard newStyle != style else { return }
            style = newStyle
            guard let window, window.overrideUserInterfaceStyle != newStyle else { return }
            // A cross-fade, like iOS's own switch: every colour changes at once, nothing moves,
            // so it stays under Reduce Motion.
            UIView.transition(
                with: window,
                duration: 0.3,
                options: [.transitionCrossDissolve, .allowUserInteraction]
            ) {
                window.overrideUserInterfaceStyle = newStyle
            }
        }
    }
}
