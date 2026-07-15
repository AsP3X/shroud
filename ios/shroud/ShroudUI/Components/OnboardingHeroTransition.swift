import SwiftUI

/// Shared identity for Welcome → auth hero zoom transitions.
enum OnboardingHeroID {
    static let brand = "onboardingBrandHero"
}

private struct OnboardingNamespaceKey: EnvironmentKey {
    static let defaultValue: Namespace.ID? = nil
}

extension EnvironmentValues {
    /// Namespace owned by `RootView` so Welcome and auth screens share one zoom source.
    var onboardingNamespace: Namespace.ID? {
        get { self[OnboardingNamespaceKey.self] }
        set { self[OnboardingNamespaceKey.self] = newValue }
    }
}

extension View {
    /// Marks the Welcome brand mark as the zoom source for auth pushes.
    // Human: Logo is the matched hero — tapping Start Messaging / Log In zooms out from this mark.
    // Agent: Applies matchedTransitionSource when RootView provides onboardingNamespace; no-op in previews.
    func onboardingHeroSource() -> some View {
        modifier(OnboardingHeroSourceModifier())
    }

    /// Destination side of the Welcome → auth zoom transition.
    func onboardingHeroDestination() -> some View {
        modifier(OnboardingHeroDestinationModifier())
    }
}

private struct OnboardingHeroSourceModifier: ViewModifier {
    @Environment(\.onboardingNamespace) private var namespace

    func body(content: Content) -> some View {
        if let namespace {
            content.matchedTransitionSource(id: OnboardingHeroID.brand, in: namespace)
        } else {
            content
        }
    }
}

private struct OnboardingHeroDestinationModifier: ViewModifier {
    @Environment(\.onboardingNamespace) private var namespace

    func body(content: Content) -> some View {
        if let namespace {
            content.navigationTransition(.zoom(sourceID: OnboardingHeroID.brand, in: namespace))
        } else {
            content
        }
    }
}
