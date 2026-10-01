import Foundation
import Testing
@testable import shroud

/// "I never got a 12-word phrase" shows only when the server says the account has no
/// published identity key (`KEYS_REQUIRED`); everything else keeps it hidden.
struct LogInNewPhraseTests {
    @Test
    func onlyKeysRequiredOffersANewPhrase() {
        let keysRequired = APIError.server(
            code: "KEYS_REQUIRED",
            message: "No pre-key bundle is available for this user.",
            statusCode: 404
        )
        let notFound = APIError.server(code: "NOT_FOUND", message: "User not found.", statusCode: 404)
        let unauthorized = APIError.server(code: "UNAUTHORIZED", message: "Unauthorized", statusCode: 401)
        #expect(LogInFlowView.isKeysRequired(keysRequired))
        #expect(!LogInFlowView.isKeysRequired(notFound))
        #expect(!LogInFlowView.isKeysRequired(unauthorized))
        #expect(!LogInFlowView.isKeysRequired(APIError.transport("The Internet connection appears to be offline.")))
        #expect(!LogInFlowView.isKeysRequired(APIError.decoding))
        #expect(!LogInFlowView.isKeysRequired(CancellationError()))
    }
}
