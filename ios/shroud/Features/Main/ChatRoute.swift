import Foundation

/// Typed push destinations for the Chats tab `NavigationStack` only.
enum ChatRoute: Hashable {
    case conversation(peerID: UUID, username: String)
}
