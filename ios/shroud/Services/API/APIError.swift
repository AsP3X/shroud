import Foundation

/// Canonical API error envelope from `/api/v1` — mirrors server `AppError` JSON.
nonisolated struct APIErrorResponse: Decodable, Equatable, Sendable {
    struct Detail: Decodable, Equatable, Sendable {
        let code: String
        let message: String
        /// Why a `DEVICE_REMOVED` happened. `"account_deleted"` when the account itself is gone;
        /// absent when only this device was removed.
        let reason: String?
    }

    let error: Detail
}

/// Typed client-side API failure mapped from HTTP status + error envelope.
nonisolated enum APIError: Error, Equatable, Sendable {
    case transport(String)
    case server(code: String, message: String, statusCode: Int, reason: String?)
    case decoding

    /// True for HTTP 401 — the session token was rejected (not offline / unreachable).
    ///
    /// Transport failures and other 4xx/5xx must not be treated as auth failure so we never
    /// force-logout a user who is merely offline or hitting a bad server.
    var isAuthenticationFailure: Bool {
        if case let .server(_, _, statusCode, _) = self, statusCode == 401 {
            return true
        }
        return false
    }

    /// The server removed this device from the account (`401 DEVICE_REMOVED`). Unlike a plain
    /// 401 this is never a hiccup: the app wipes everything of the account at once.
    /// An account deletion uses the same code, with `reason` `account_deleted`.
    var isDeviceRemoval: Bool {
        if case let .server(code, _, statusCode, _) = self, statusCode == 401 {
            return code == Self.deviceRemovedCode
        }
        return false
    }

    /// `401 DEVICE_REMOVED` because the account was deleted (`reason` `account_deleted`).
    var isAccountDeletion: Bool {
        if case let .server(code, _, statusCode, reason) = self, statusCode == 401 {
            return code == Self.deviceRemovedCode && reason == Self.accountDeletedReason
        }
        return false
    }

    static let deviceRemovedCode = "DEVICE_REMOVED"
    static let accountDeletedReason = "account_deleted"

    /// The server's minimum version is above this build (`426 UPDATE_REQUIRED`). Not an auth
    /// failure: `ClientVersionBridge` runs the version check, which blocks the app with
    /// "Update required".
    var isUpdateRequired: Bool {
        if case let .server(code, _, statusCode, _) = self, statusCode == 426 {
            return code == Self.updateRequiredCode
        }
        return false
    }

    static let updateRequiredCode = "UPDATE_REQUIRED"

    /// Builds an `APIError` from a failed HTTP response body when possible.
    static func from(data: Data, statusCode: Int) -> APIError {
        if let envelope = try? JSONDecoder().decode(APIErrorResponse.self, from: data) {
            return .server(
                code: envelope.error.code,
                message: envelope.error.message,
                statusCode: statusCode,
                reason: envelope.error.reason
            )
        }
        // Preserve 401 even without a JSON envelope so session policy can still revoke.
        if statusCode == 401 {
            return .server(
                code: "unauthorized",
                message: "Unauthorized",
                statusCode: 401,
                reason: nil
            )
        }
        return .transport("Request failed with status \(statusCode)")
    }
}
