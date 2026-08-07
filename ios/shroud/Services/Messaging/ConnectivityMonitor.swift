import Foundation
import Network

/// Lightweight reachability for offline-first messaging.
///
/// Human: When the radio drops we keep showing cached chats instead of empty error screens.
/// Agent: `isOnline` is main-actor published; path updates arrive on a private queue and hop back.
@MainActor
@Observable
final class ConnectivityMonitor {
    private(set) var isOnline: Bool = true

    private let monitor = NWPathMonitor()
    private let queue = DispatchQueue(label: "com.shroud.connectivity")
    private var started = false

    func start() {
        guard !started else { return }
        started = true
        monitor.pathUpdateHandler = { [weak self] path in
            let online = path.status == .satisfied
            Task { @MainActor in
                guard let self else { return }
                if self.isOnline != online {
                    self.isOnline = online
                }
            }
        }
        monitor.start(queue: queue)
    }

    func stop() {
        guard started else { return }
        started = false
        monitor.cancel()
    }
}
