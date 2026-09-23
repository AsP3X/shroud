import SwiftUI
import UIKit
import XCTest

@testable import shroud

/// The chat hides its back button, which switches UIKit's back swipe off. These push real
/// `NavigationStack` screens in a window and ask the pop recognisers whether they would start.
/// Since iOS 26 the content pop recogniser is the one that actually drives the swipe (checked by
/// hand on the simulator: with only the classic edge recogniser restored, nothing pops).
@MainActor
final class InteractivePopGestureTests: XCTestCase {
    enum Route: Hashable {
        /// Like the chat: hidden bar, swipe restored.
        case chat
        /// Hidden bar and nothing else, like a screen pushed from the chat.
        case plain
    }

    @Observable
    final class Model {
        var path: [Route] = []
        var enabled = true
    }

    struct Harness: View {
        @Bindable var model: Model

        var body: some View {
            NavigationStack(path: $model.path) {
                Color.red
                    .navigationDestination(for: Route.self) { route in
                        switch route {
                        case .chat:
                            Color.blue
                                .navigationBarBackButtonHidden(true)
                                .toolbar(.hidden, for: .navigationBar)
                                .interactivePopGesture(enabled: model.enabled)
                        case .plain:
                            Color.green
                                .navigationBarBackButtonHidden(true)
                                .toolbar(.hidden, for: .navigationBar)
                        }
                    }
            }
        }
    }

    private var window: UIWindow?
    private let model = Model()

    override func setUp() async throws {
        let scene = try XCTUnwrap(
            UIApplication.shared.connectedScenes.compactMap { $0 as? UIWindowScene }.first
        )
        let window = UIWindow(windowScene: scene)
        window.rootViewController = UIHostingController(rootView: Harness(model: model))
        window.makeKeyAndVisible()
        self.window = window
        try await settle { self.navigationController != nil }
    }

    override func tearDown() async throws {
        window?.isHidden = true
        window = nil
    }

    func testSwipeStartsOnPushedChat() async throws {
        try await push(.chat, depth: 1)
        XCTAssertEqual(try shouldBegin(), true)
    }

    func testSwipeWaitsWhileChatHoldsIt() async throws {
        try await push(.chat, depth: 1)
        model.enabled = false
        try await settle { (try? self.shouldBegin()) == false }
        model.enabled = true
        try await settle { (try? self.shouldBegin()) == true }
    }

    func testNothingToPopAtRoot() async throws {
        try await push(.chat, depth: 1)
        model.path = []
        try await settle { self.idleDepth == 0 }
        XCTAssertEqual(try shouldBegin(), false)
    }

    /// A chat that closes while it holds the swipe off must not leave it off for the next screen.
    func testClosingChatReleasesTheSwipe() async throws {
        try await push(.chat, depth: 1)
        model.enabled = false
        try await settle { (try? self.shouldBegin()) == false }
        model.path = []
        try await settle { self.idleDepth == 0 }
        try await push(.plain, depth: 1)
        XCTAssertEqual(try shouldBegin(), true)
    }

    /// A screen pushed from the chat (like the contact profile) can be swiped away as well.
    func testScreenPushedFromChatSwipesToo() async throws {
        try await push(.chat, depth: 1)
        try await push(.plain, depth: 2)
        XCTAssertEqual(try shouldBegin(), true)
    }

    /// The iOS 26 content recogniser drives the swipe, so it must not keep UIKit's delegate
    /// (which refuses whenever the back button is hidden).
    func testBothPopRecognizersAreTakenOver() async throws {
        try await push(.chat, depth: 1)
        let nav = try XCTUnwrap(navigationController)
        let edge = try XCTUnwrap(nav.interactivePopGestureRecognizer)
        let content = try XCTUnwrap(nav.interactiveContentPopGestureRecognizer)
        XCTAssertTrue(edge.delegate === content.delegate)
        XCTAssertEqual(content.delegate?.gestureRecognizerShouldBegin?(content), true)
    }

    // MARK: - Helpers

    private var navigationController: UINavigationController? {
        func find(_ controller: UIViewController?) -> UINavigationController? {
            guard let controller else { return nil }
            if let nav = controller as? UINavigationController { return nav }
            for child in controller.children {
                if let nav = find(child) { return nav }
            }
            return nil
        }
        return find(window?.rootViewController)
    }

    /// Stack depth once no push or pop is animating (nil while one is).
    private var idleDepth: Int? {
        guard let nav = navigationController, nav.transitionCoordinator == nil else { return nil }
        return nav.viewControllers.count - 1
    }

    private func push(_ route: Route, depth: Int) async throws {
        model.path.append(route)
        try await settle { self.idleDepth == depth }
        // `viewDidAppear` installs the delegate one pass after the transition ends.
        try await Task.sleep(for: .milliseconds(100))
    }

    /// What the content pop recogniser (the one that drives the swipe) would answer.
    private func shouldBegin() throws -> Bool {
        let nav = try XCTUnwrap(navigationController)
        let recognizer = try XCTUnwrap(nav.interactiveContentPopGestureRecognizer)
        guard recognizer.isEnabled else { return false }
        return recognizer.delegate?.gestureRecognizerShouldBegin?(recognizer) ?? true
    }

    private func settle(
        timeout: Duration = .seconds(5),
        file: StaticString = #filePath,
        line: UInt = #line,
        _ condition: @escaping () throws -> Bool
    ) async throws {
        let deadline = ContinuousClock.now + timeout
        while ContinuousClock.now < deadline {
            if (try? condition()) == true { return }
            try await Task.sleep(for: .milliseconds(20))
        }
        XCTFail("Condition not met within \(timeout)", file: file, line: line)
    }
}
