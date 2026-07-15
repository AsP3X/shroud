import Foundation

/// App-wide server endpoint configuration.
@MainActor
@Observable
final class ServerConfigurationController {
    private let store: ServerConfigurationStore
    private(set) var configuration: ServerConfiguration

    init(store: ServerConfigurationStore = ServerConfigurationStore()) {
        self.store = store
        self.configuration = store.load()
    }

    var baseURLString: String {
        configuration.resolvedBaseURLString
    }

    func save(_ configuration: ServerConfiguration) throws {
        if let error = configuration.validationError() {
            throw ServerConfigurationError.invalid(error)
        }
        store.save(configuration)
        self.configuration = configuration
    }
}

enum ServerConfigurationError: Error, Equatable, LocalizedError {
    case invalid(String)

    var errorDescription: String? {
        switch self {
        case let .invalid(message):
            return message
        }
    }
}
