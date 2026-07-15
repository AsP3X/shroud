import SwiftUI

/// Applies horizontal screen padding from the design system (16–20 pt).
struct ScreenContent<Content: View>: View {
    let content: Content

    init(@ViewBuilder content: () -> Content) {
        self.content = content()
    }

    var body: some View {
        content
            .padding(.horizontal, 20)
    }
}

extension View {
    func screenContent() -> some View {
        padding(.horizontal, 20)
    }
}

/// Grouped screen background wrapper.
struct GroupedScreen<Content: View>: View {
    let content: Content

    init(@ViewBuilder content: () -> Content) {
        self.content = content()
    }

    var body: some View {
        content
            .frame(maxWidth: .infinity, maxHeight: .infinity)
            .background(Theme.backgroundGrouped)
    }
}
