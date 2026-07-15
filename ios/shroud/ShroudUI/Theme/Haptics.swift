import UIKit

/// Thin wrapper around UIKit feedback generators.
/// Simulator has no haptic pattern library (`hapticpatternlibrary.plist`), so UIKit logs
/// CHHapticPattern errors on every impact/notification call. We no-op there.
enum Haptics {
    static func impact(_ style: UIImpactFeedbackGenerator.FeedbackStyle = .light) {
        #if targetEnvironment(simulator)
        return
        #else
        UIImpactFeedbackGenerator(style: style).impactOccurred()
        #endif
    }

    static func notification(_ type: UINotificationFeedbackGenerator.FeedbackType) {
        #if targetEnvironment(simulator)
        return
        #else
        UINotificationFeedbackGenerator().notificationOccurred(type)
        #endif
    }
}
