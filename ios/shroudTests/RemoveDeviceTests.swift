import Foundation
import Testing
@testable import shroud

/// Removing a device asks for the account password (security audit L4): the request body, the
/// answer map, the copy shared with Delete Account, and the 401-streak exclusion.
@MainActor
struct RemoveDeviceTests {
    @Test
    func bodyCarriesOnlyThePassword() throws {
        let data = try JSONEncoder.api.encode(RemoveDeviceRequest(password: "correct horse"))
        let object = try #require(try JSONSerialization.jsonObject(with: data) as? [String: Any])
        #expect(object.count == 1)
        #expect(object["password"] as? String == "correct horse")
        let id = UUID(uuidString: "3F2504E0-4F89-41D3-9A0C-0305E82C3301")!
        #expect(DevicesService.revokePath(id) == "devices/3f2504e0-4f89-41d3-9a0c-0305e82c3301")
    }

    @Test
    func mapsRemoveDeviceAnswers() {
        let wrong = Data(#"{"error":{"code":"INVALID_CREDENTIALS","message":"Invalid username or password."}}"#.utf8)
        #expect(RemoveDevice.answer(of: APIError.from(data: wrong, statusCode: 401)) == .wrongPassword)
        #expect(RemoveDevice.message(for: .wrongPassword) == "That password isn't right.")

        let limited = Data(#"{"error":{"code":"RATE_LIMITED","message":"slow down"}}"#.utf8)
        #expect(RemoveDevice.answer(of: APIError.from(data: limited, statusCode: 429)) == .tooManyTries)
        #expect(RemoveDevice.message(for: .tooManyTries) == "Too many tries. Try again later.")

        let missing = Data(#"{"error":{"code":"NOT_FOUND","message":"Device not found."}}"#.utf8)
        #expect(RemoveDevice.answer(of: APIError.from(data: missing, statusCode: 404)) == .alreadyRemoved)
        #expect(RemoveDevice.message(for: .alreadyRemoved) == nil)
        #expect(RemoveDevice.message(for: .removed) == nil)

        // A 401 that is not a wrong password is not one: the session policy deals with it.
        let unauthorized = Data(#"{"error":{"code":"UNAUTHORIZED","message":"This session was signed out."}}"#.utf8)
        #expect(RemoveDevice.answer(of: APIError.from(data: unauthorized, statusCode: 401))
            == .failed("This session was signed out."))
        let server = Data(#"{"error":{"code":"INTERNAL","message":"Something broke."}}"#.utf8)
        #expect(RemoveDevice.answer(of: APIError.from(data: server, statusCode: 500)) == .failed("Something broke."))
        #expect(RemoveDevice.answer(of: APIError.transport("The request timed out."))
            == .failed("The request timed out."))
        #expect(RemoveDevice.message(for: .failed("The request timed out.")) == "The request timed out.")

        // Only these two keep the confirmation open; the rest close it as before.
        #expect(RemoveDevice.keepsConfirmationOpen(.wrongPassword))
        #expect(RemoveDevice.keepsConfirmationOpen(.tooManyTries))
        #expect(!RemoveDevice.keepsConfirmationOpen(.removed))
        #expect(!RemoveDevice.keepsConfirmationOpen(.alreadyRemoved))
        #expect(!RemoveDevice.keepsConfirmationOpen(.failed("x")))
    }

    @Test
    func passwordRowIsDeleteAccounts() {
        #expect(RemoveDeviceCopy.passwordLabel == DeleteAccountCopy.passwordLabel)
        #expect(RemoveDeviceCopy.passwordPlaceholder == DeleteAccountCopy.passwordPlaceholder)
        #expect(RemoveDeviceCopy.wrongPassword == DeleteAccountCopy.wrongPassword)
        #expect(RemoveDeviceCopy.tooManyTries == DeleteAccountCopy.tooManyTries)
        #expect(RemoveDeviceCopy.passwordPrompt == "Enter your password to confirm.")
        #expect(RemoveDeviceCopy.removing == "Removing\u{2026}")
    }

    /// A wrong password while removing a device must not move the 401 streak, like Delete Account's.
    @Test
    func wrongRemovePasswordIsNotASessionFailure() {
        let wrong = Data(#"{"error":{"code":"INVALID_CREDENTIALS","message":"Invalid username or password."}}"#.utf8)
        let path = DevicesService.revokePath(UUID())
        #expect(APIClient.isRemoveDevice(method: "DELETE", path: path))
        #expect(APIClient.isRemoveDevice(method: "delete", path: "/" + path))
        #expect(!APIClient.isRemoveDevice(method: "PUT", path: path + "/name"))
        #expect(!APIClient.isRemoveDevice(method: "DELETE", path: path + "/name"))
        #expect(!APIClient.isRemoveDevice(method: "GET", path: "devices"))
        #expect(APIClient.sessionAuthNote(
            method: "DELETE", path: path, status: 401, data: wrong, hasBearer: true
        ) == .none)

        let plain = Data(#"{"error":{"code":"UNAUTHORIZED","message":"nope"}}"#.utf8)
        #expect(APIClient.sessionAuthNote(
            method: "DELETE", path: path, status: 401, data: plain, hasBearer: true
        ) == .authenticationFailure)
        let removed = Data(#"{"error":{"code":"DEVICE_REMOVED","message":"This device was removed from your account."}}"#.utf8)
        #expect(APIClient.sessionAuthNote(
            method: "DELETE", path: path, status: 401, data: removed, hasBearer: true
        ) == .deviceRemoved)
        #expect(APIClient.sessionAuthNote(
            method: "DELETE", path: path, status: 204, data: Data(), hasBearer: true
        ) == .success)
    }
}
