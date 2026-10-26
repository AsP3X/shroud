import Foundation

/// Copy for the Remove Device confirmation. The password row and its errors are Delete
/// Account's, word for word (`DeleteAccountCopy`).
enum RemoveDeviceCopy {
    static let passwordPrompt = "Enter your password to confirm."
    static let passwordLabel = DeleteAccountCopy.passwordLabel
    static let passwordPlaceholder = DeleteAccountCopy.passwordPlaceholder
    static let removing = "Removing\u{2026}"
    static let cancel = DeleteAccountCopy.cancel
    static let wrongPassword = DeleteAccountCopy.wrongPassword
    static let tooManyTries = DeleteAccountCopy.tooManyTries
}

/// What `DELETE /devices/{id}` told the app to do. Shared by the Devices screen and the tests.
enum RemoveDeviceAnswer: Equatable, Sendable {
    /// `204`.
    case removed
    /// `404`: no longer a live device of the account (removed elsewhere meanwhile). The goal is met.
    case alreadyRemoved
    /// `401 INVALID_CREDENTIALS`. The confirmation stays open.
    case wrongPassword
    /// `429`, the budget removal shares with password change and account deletion.
    case tooManyTries
    /// Anything else, with the message to show.
    case failed(String)
}

enum RemoveDevice {
    /// Maps a thrown failure of `DevicesService.revoke`.
    static func answer(of error: Error) -> RemoveDeviceAnswer {
        guard let api = error as? APIError else {
            return .failed(SessionController.userMessage(for: error))
        }
        if case let .server(code, message, status, _) = api {
            if status == 404 { return .alreadyRemoved }
            if status == 401, code == "INVALID_CREDENTIALS" { return .wrongPassword }
            if status == 429 { return .tooManyTries }
            return .failed(message)
        }
        return .failed(SessionController.userMessage(for: api))
    }

    /// A wrong password or the rate limit: the confirmation stays open with the error under the
    /// password. Any other answer closes it, and the Devices screen shows the result as before.
    static func keepsConfirmationOpen(_ answer: RemoveDeviceAnswer) -> Bool {
        answer == .wrongPassword || answer == .tooManyTries
    }

    /// The error to show (under the password, or under the list); nil once the device is gone.
    static func message(for answer: RemoveDeviceAnswer) -> String? {
        switch answer {
        case .removed, .alreadyRemoved: nil
        case .wrongPassword: RemoveDeviceCopy.wrongPassword
        case .tooManyTries: RemoveDeviceCopy.tooManyTries
        case let .failed(message): message
        }
    }
}
