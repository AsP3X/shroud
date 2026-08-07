import Foundation

/// Byte-level progress for one `URLSession` transfer, reported as 0…1.
///
/// Human: URLSession's async APIs only hand back the *finished* bytes, so a multi-megabyte
/// media transfer looks frozen to the user. A per-task delegate gives us the task the moment
/// it is created; from there we sample its byte counters, which is the one progress signal
/// that behaves identically for uploads and downloads.
/// Agent: WRITES nothing but the callback. Owns a polling Task — callers MUST `finish()`
/// in a `defer` so the poll never outlives the request.
nonisolated final class TransferProgressObserver: NSObject, URLSessionTaskDelegate, @unchecked Sendable {
    enum Direction {
        case upload
        case download
    }

    /// Sampling cadence: fast enough that the ring looks continuous, slow enough that a
    /// 24 MB transfer doesn't flood the main actor with layout passes.
    private static let sampleInterval = Duration.milliseconds(100)
    /// Ignore sub-half-percent deltas so a fast local server doesn't animate frame-by-frame.
    private static let reportThreshold = 0.005

    private let direction: Direction
    private let onProgress: @Sendable (Double) -> Void
    private let lock = NSLock()
    private var poller: Task<Void, Never>?
    private var lastReported: Double = -1

    init(direction: Direction, onProgress: @escaping @Sendable (Double) -> Void) {
        self.direction = direction
        self.onProgress = onProgress
    }

    func urlSession(_ session: URLSession, didCreateTask task: URLSessionTask) {
        let poll = Task { [weak self] in
            while !Task.isCancelled {
                self?.sample(task)
                try? await Task.sleep(for: Self.sampleInterval)
            }
        }
        lock.lock()
        poller?.cancel()
        poller = poll
        lock.unlock()
    }

    /// Stops sampling. Safe to call more than once.
    func finish() {
        lock.lock()
        let running = poller
        poller = nil
        lock.unlock()
        running?.cancel()
    }

    private func sample(_ task: URLSessionTask) {
        let done: Int64
        let total: Int64
        switch direction {
        case .upload:
            done = task.countOfBytesSent
            total = task.countOfBytesExpectedToSend
        case .download:
            done = task.countOfBytesReceived
            total = task.countOfBytesExpectedToReceive
        }
        // A chunked response has no expected length; the UI falls back to its indeterminate look.
        guard total > 0 else { return }
        let fraction = min(1, max(0, Double(done) / Double(total)))

        lock.lock()
        let shouldReport = fraction >= 1 || fraction - lastReported >= Self.reportThreshold
        if shouldReport { lastReported = fraction }
        lock.unlock()

        guard shouldReport else { return }
        onProgress(fraction)
    }
}
