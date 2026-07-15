import SwiftUI

/// Application entry — wires root navigation once onboarding state is implemented.
@main
struct ShroudApp: App {
    var body: some Scene {
        WindowGroup {
            RootView(viewModel: RootViewModel())
        }
    }
}
