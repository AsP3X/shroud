package de.corespace.shroud.core.keys

import de.corespace.shroud.core.keys.IdentityKeyStore.ReadStatus.DeviceLocked
import de.corespace.shroud.core.keys.IdentityKeyStore.ReadStatus.Failed
import de.corespace.shroud.core.keys.IdentityKeyStore.ReadStatus.NotFound
import de.corespace.shroud.core.keys.IdentityKeyStore.ReadStatus.Success
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * A locked phone cannot read identity items; that must not look like the account was wiped
 * (iOS `ios/shroudTests/IdentityPresenceTests.swift`; crypto spec §9.2). The Keychain statuses map
 * as `errSecInteractionNotAllowed → DeviceLocked`, `errSecSuccess → Success`,
 * `errSecItemNotFound → NotFound`, `errSecAuthFailed → Failed`.
 */
class IdentityPresenceTest {
    private val user = "AAAAAAAA-BBBB-CCCC-DDDD-EEEEEEEEEEEE"

    private fun presence(userIdStatus: IdentityKeyStore.ReadStatus, stored: String?, privateKeyStatus: IdentityKeyStore.ReadStatus) =
        IdentityKeyStore.presence(userIdStatus, stored, user, privateKeyStatus)

    /** `aLockedKeychainIsNotAMissingIdentity` (`:10-27`). */
    @Test
    fun aLockedKeychainIsNotAMissingIdentity() {
        assertEquals(IdentityPresence.Unavailable, presence(DeviceLocked, null, DeviceLocked))
        assertEquals(IdentityPresence.Unavailable, presence(Success, user, DeviceLocked))
    }

    /** `aMissingItemIsTheOnlyProofTheIdentityIsGone` (`:29-55`). */
    @Test
    fun aMissingItemIsTheOnlyProofTheIdentityIsGone() {
        assertEquals(IdentityPresence.Absent, presence(NotFound, null, NotFound))
        assertEquals(IdentityPresence.Absent, presence(Success, user, NotFound))
        assertEquals(IdentityPresence.Absent, presence(Success, "11111111-2222-3333-4444-555555555555", Success))
    }

    /** `aReadableMatchingIdentityIsPresent` (`:57-66`); the stored id compares case-insensitively. */
    @Test
    fun aReadableMatchingIdentityIsPresent() {
        assertEquals(IdentityPresence.Present, presence(Success, user, Success))
        assertEquals(IdentityPresence.Present, presence(Success, user.lowercase(), Success))
    }

    /** `anyOtherKeychainFailureStaysUnknown` (`:68-77`). */
    @Test
    fun anyOtherKeychainFailureStaysUnknown() {
        assertEquals(IdentityPresence.Unavailable, presence(Failed, null, Success))
        assertEquals(IdentityPresence.Unavailable, presence(Success, user, Failed))
    }
}
