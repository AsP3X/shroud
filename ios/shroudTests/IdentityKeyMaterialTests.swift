import Foundation
import Testing
@testable import shroud

struct IdentityKeyMaterialTests {
    @Test
    func samePhraseSameIdentityDifferentPreKeys() throws {
        let words = Array(repeating: "abandon", count: 11) + ["about"]
        let userID = UUID()
        let a = try IdentityKeyMaterial.establish(mnemonicWords: words, userID: userID, oneTimePreKeyCount: 5)
        let b = try IdentityKeyMaterial.establish(mnemonicWords: words, userID: userID, oneTimePreKeyCount: 5)

        #expect(a.identityPublicKeyData == b.identityPublicKeyData)
        #expect(a.registrationID == b.registrationID)
        #expect(a.signingPublicKeyData == b.signingPublicKeyData)
        // SPK is random per establish
        #expect(a.signedPreKeyPublicData != b.signedPreKeyPublicData || a.signedPreKeyID != b.signedPreKeyID)
        #expect(a.matchesMnemonic(words))
        #expect(!a.matchesMnemonic(Array(repeating: "ability", count: 12)))
    }

    @Test
    func putBundleRequestEncodes() throws {
        let words = EncryptionPhraseGenerator.generate()
        let material = try IdentityKeyMaterial.establish(
            mnemonicWords: words,
            userID: UUID(),
            oneTimePreKeyCount: 3
        )
        let request = try KeyBundleService.makePutRequest(from: material)
        #expect(request.registrationId >= 0 && request.registrationId <= 16_383)
        #expect(!request.identityKey.isEmpty)
        #expect(request.oneTimePreKeys.count == 3)
        #expect(!request.signedPreKey.signature.isEmpty)
    }
}
