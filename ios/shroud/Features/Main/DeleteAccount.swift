import Foundation

/// Copy for Delete Account, word for word from the account-deletion plan (§3.3).
enum DeleteAccountCopy {
    static let rowTitle = "Delete Account"
    static let rowSubtitle = "Deletes your account and erases it from every device."
    static let navigationTitle = "Delete Account"
    static let heading = "Delete your account?"
    static let consequences: [(icon: Icon, text: String)] = [
        (.messageDeleted, "Your messages are replaced with \u{201C}Message deleted\u{201D} for everyone."),
        (.people, "Contacts who let you clear chats for them lose those chats. Everyone else keeps their own messages."),
        (.trash, "Your contacts, Saved Messages, photos, files and call history are deleted."),
        (.at, "Your username and share code are released, so someone else can take them."),
        (.phone, "Every device signed in to this account is signed out and erased."),
    ]
    static let confirmPrompt = "This can't be undone. Enter your password to confirm."
    static let passwordLabel = "Password"
    static let passwordPlaceholder = "Your account password"
    static let deleteButton = "Delete Account"
    static let deleting = "Deleting\u{2026}"
    static let cancel = "Cancel"
    static let wrongPassword = "That password isn't right."
    static let tooManyTries = "Too many tries. Try again later."
    static let unreachable = "Couldn't reach the server. Your account wasn't deleted."
    /// C10 plus the trailing space the other wipe leads use.
    static let accountDeletedLead = "This account was deleted. "

    enum Icon: Equatable, Sendable {
        case messageDeleted, people, trash, at, phone
    }
}

/// What `DELETE /auth/account` told the app to do (§3.2). Shared by the screen and the tests.
enum DeleteAccountAnswer: Equatable, Sendable {
    /// `204`, or `401 DEVICE_REMOVED` with any reason: wipe as account deleted.
    case deleted
    /// `401 INVALID_CREDENTIALS`. Stay on the screen.
    case wrongPassword
    /// `429`.
    case tooManyTries
    /// Network error, timeout, or `5xx`. Nothing was deleted.
    case unreachable
}

enum DeleteAccount {
    /// Maps the HTTP status and body of `DELETE /auth/account`.
    static func answer(status: Int, body: Data) -> DeleteAccountAnswer {
        if (200 ..< 300).contains(status) { return .deleted }
        return answer(of: APIError.from(data: body, statusCode: status))
    }

    /// Maps a thrown failure of that call. A removal on this call wipes as an account deletion
    /// whatever `reason` the body carried.
    static func answer(of error: Error) -> DeleteAccountAnswer {
        guard let api = error as? APIError else { return .unreachable }
        if api.isDeviceRemoval { return .deleted }
        switch api {
        case let .server(code, _, status, _):
            if status == 401, code == "INVALID_CREDENTIALS" { return .wrongPassword }
            if status == 429 { return .tooManyTries }
            if status >= 500 { return .unreachable }
            return .unreachable
        case .transport, .decoding:
            return .unreachable
        }
    }

    static func message(for answer: DeleteAccountAnswer) -> String? {
        switch answer {
        case .deleted: nil
        case .wrongPassword: DeleteAccountCopy.wrongPassword
        case .tooManyTries: DeleteAccountCopy.tooManyTries
        case .unreachable: DeleteAccountCopy.unreachable
        }
    }
}
