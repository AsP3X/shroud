import SwiftUI

/// Application entry — hosts onboarding navigation from the design file.
@main
struct ShroudApp: App {
    @UIApplicationDelegateAdaptor(AppDelegate.self) private var appDelegate

    var body: some Scene {
        WindowGroup {
            RootView()
        }
    }
}
