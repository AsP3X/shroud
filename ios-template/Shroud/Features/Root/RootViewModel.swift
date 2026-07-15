import Foundation

/// Placeholder root view model until onboarding / session routing is built.
@MainActor
@Observable
final class RootViewModel {
    /// Human: Will reflect auth + keychain state once registration flows exist.
    /// Agent: READS session store (future); no key material in this bootstrap shell.
    var welcomeTitle: String {
        "Shroud"
    }

    var welcomeSubtitle: String {
        "End-to-end encrypted messaging"
    }
}
