import Foundation
import Security
import Testing
@testable import shroud

/// A locked phone cannot read identity items. That must not look like the account was wiped.
struct IdentityPresenceTests {
    private let user = UUID(uuidString: "AAAAAAAA-BBBB-CCCC-DDDD-EEEEEEEEEEEE")!

    @Test func aLockedKeychainIsNotAMissingIdentity() {
        #expect(
            IdentityKeyStore.presence(
                userIDStatus: errSecInteractionNotAllowed,
                storedUserID: nil,
                expected: user,
                privateKeyStatus: errSecInteractionNotAllowed
            ) == .unavailable
        )
        #expect(
            IdentityKeyStore.presence(
                userIDStatus: errSecSuccess,
                storedUserID: user,
                expected: user,
                privateKeyStatus: errSecInteractionNotAllowed
            ) == .unavailable
        )
    }

    @Test func aMissingItemIsTheOnlyProofTheIdentityIsGone() {
        #expect(
            IdentityKeyStore.presence(
                userIDStatus: errSecItemNotFound,
                storedUserID: nil,
                expected: user,
                privateKeyStatus: errSecItemNotFound
            ) == .absent
        )
        #expect(
            IdentityKeyStore.presence(
                userIDStatus: errSecSuccess,
                storedUserID: user,
                expected: user,
                privateKeyStatus: errSecItemNotFound
            ) == .absent
        )
        let other = UUID(uuidString: "11111111-2222-3333-4444-555555555555")!
        #expect(
            IdentityKeyStore.presence(
                userIDStatus: errSecSuccess,
                storedUserID: other,
                expected: user,
                privateKeyStatus: errSecSuccess
            ) == .absent
        )
    }

    @Test func aReadableMatchingIdentityIsPresent() {
        #expect(
            IdentityKeyStore.presence(
                userIDStatus: errSecSuccess,
                storedUserID: user,
                expected: user,
                privateKeyStatus: errSecSuccess
            ) == .present
        )
    }

    @Test func anyOtherKeychainFailureStaysUnknown() {
        #expect(
            IdentityKeyStore.presence(
                userIDStatus: errSecAuthFailed,
                storedUserID: nil,
                expected: user,
                privateKeyStatus: errSecSuccess
            ) == .unavailable
        )
    }
}
