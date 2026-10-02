package de.corespace.shroud.ui.lock

import de.corespace.shroud.core.keys.BiometricLabel
import de.corespace.shroud.core.keys.VaultState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The lock screen's mode from what the phone reports, its words and its hero numbers
 * (`LockScreenView.swift:81-87, 225-245, 319-420`; settings-lock §11.2-11.4, §18.3
 * `LockScreenModeTest`; P14 copy).
 */
class LockScreenModeTest {
    private fun mode(secure: Boolean = true, strong: Boolean = true, vault: VaultState = VaultState.Ready, identity: Boolean = true) =
        LockScreenMode.of(secure, strong, vault, identity)

    @Test
    fun aSecurePhoneWithAStrongBiometricAndAVaultUnlocksWithTheBiometric() {
        assertEquals(LockScreenMode.Biometric, mode())
    }

    @Test
    fun withoutAStrongBiometricItIsTheScreenLock() {
        // No biometric, or weak (class 2) face unlock only (crypto §10.4).
        assertEquals(LockScreenMode.ScreenLockOnly, mode(strong = false))
    }

    @Test
    fun noScreenLockWinsOverEverything() {
        // `canUseDeviceAuth` first (`:349`): without a lock the rebuilt vault could not exist either (`:556`).
        assertEquals(LockScreenMode.NoScreenLock, mode(secure = false))
        assertEquals(LockScreenMode.NoScreenLock, mode(secure = false, vault = VaultState.KeyInvalidated))
        assertEquals(LockScreenMode.NoScreenLock, mode(vault = VaultState.NoScreenLock))
        assertEquals(LockScreenMode.NoScreenLock, mode(secure = false, strong = false, identity = false))
    }

    @Test
    fun anInvalidatedOrMissingVaultWithTheIdentityHereNeedsThePhrase() {
        assertEquals(LockScreenMode.PhraseNeeded, mode(vault = VaultState.KeyInvalidated))
        assertEquals(LockScreenMode.PhraseNeeded, mode(vault = VaultState.NotFound))
        assertEquals(LockScreenMode.PhraseNeeded, mode(strong = false, vault = VaultState.KeyInvalidated))
    }

    @Test
    fun withoutTheIdentityTheNormalModeStaysSoItsButtonReconcilesTheSession() {
        // `LockScreenView.swift:487-492`: the unlock tap leaves the lock screen for Welcome.
        assertEquals(LockScreenMode.Biometric, mode(vault = VaultState.NotFound, identity = false))
        assertEquals(LockScreenMode.ScreenLockOnly, mode(strong = false, vault = VaultState.KeyInvalidated, identity = false))
    }

    @Test
    fun theProbeDerivesItsMode() {
        val probe = LockProbe(isDeviceSecure = true, strongBiometric = false, biometricLabel = BiometricLabel.Face, vaultState = VaultState.Ready, hasIdentity = true)
        assertEquals(LockScreenMode.ScreenLockOnly, probe.mode)
        assertFalse(probe.softwareKeystore)
        assertEquals(LockScreenMode.PhraseNeeded, probe.copy(vaultState = VaultState.KeyInvalidated).mode)
    }

    @Test
    fun titlesAndBodiesPerMode() {
        assertEquals("Chats are locked", LockCopy.title(LockScreenMode.Biometric))
        assertEquals("Chats are locked", LockCopy.title(LockScreenMode.ScreenLockOnly))
        assertEquals("Set a screen lock to use Shroud", LockCopy.title(LockScreenMode.NoScreenLock))
        assertEquals("Phrase needed", LockCopy.title(LockScreenMode.PhraseNeeded))
        assertEquals("Your messages stay encrypted on this phone until you unlock them.", LockCopy.body(LockScreenMode.Biometric))
        assertEquals("Your messages stay encrypted on this tablet until you unlock them.", LockCopy.body(LockScreenMode.ScreenLockOnly, "tablet"))
        assertEquals(
            "Shroud keeps your chats sealed behind this phone's screen lock. Add one in Settings, then come back.",
            LockCopy.body(LockScreenMode.NoScreenLock),
        )
        assertEquals(
            "Your fingerprints or screen lock changed, so this phone can’t unlock your chats on its own. Enter your 12-word phrase once to set it up again.",
            LockCopy.body(LockScreenMode.PhraseNeeded),
        )
    }

    @Test
    fun theUnlockButtonNamesTheEnrolledBiometric() {
        // S6 / P14: "Unlock with fingerprint/face/biometrics".
        assertEquals("Unlock with fingerprint", LockCopy.primaryIdle(LockScreenMode.Biometric, BiometricLabel.Fingerprint))
        assertEquals("Unlock with face", LockCopy.primaryIdle(LockScreenMode.Biometric, BiometricLabel.Face))
        assertEquals("Unlock with biometrics", LockCopy.primaryIdle(LockScreenMode.Biometric, BiometricLabel.Generic))
        assertEquals("Unlock with screen lock", LockCopy.primaryIdle(LockScreenMode.ScreenLockOnly, BiometricLabel.Fingerprint))
        assertEquals("Check again", LockCopy.primaryIdle(LockScreenMode.NoScreenLock, BiometricLabel.Fingerprint))
        assertEquals("Enter encryption phrase", LockCopy.primaryIdle(LockScreenMode.PhraseNeeded, BiometricLabel.Fingerprint))
    }

    @Test
    fun theButtonTitleFollowsTheChoreography() {
        // `primaryTitle(for:)` (`:414-420`).
        val label = BiometricLabel.Fingerprint
        assertEquals("Unlock with fingerprint", LockCopy.primary(LockScreenMode.Biometric, label, UnlockPhase.Idle))
        assertEquals("Checking…", LockCopy.primary(LockScreenMode.Biometric, label, UnlockPhase.Checking))
        for (phase in listOf(UnlockPhase.Verified, UnlockPhase.Releasing, UnlockPhase.Revealing)) {
            assertEquals("Unlocked", LockCopy.primary(LockScreenMode.ScreenLockOnly, label, phase))
        }
        assertEquals("Use screen lock", LockCopy.secondary(checkingThisMethod = false))
        assertEquals("Checking…", LockCopy.secondary(checkingThisMethod = true))
    }

    @Test
    fun theFallbackRowLead() {
        assertEquals("Fingerprint not working?", LockCopy.fallbackLead(LockScreenMode.Biometric, BiometricLabel.Fingerprint))
        assertEquals("Face unlock not working?", LockCopy.fallbackLead(LockScreenMode.Biometric, BiometricLabel.Face))
        assertEquals("Biometrics not working?", LockCopy.fallbackLead(LockScreenMode.Biometric, BiometricLabel.Generic))
        assertEquals("Prefer another way?", LockCopy.fallbackLead(LockScreenMode.ScreenLockOnly, BiometricLabel.Face))
        // Design `o5GgZ` and `LRnnR`: no phrase row.
        assertNull(LockCopy.fallbackLead(LockScreenMode.NoScreenLock, BiometricLabel.Fingerprint))
        assertNull(LockCopy.fallbackLead(LockScreenMode.PhraseNeeded, BiometricLabel.Fingerprint))
    }

    @Test
    fun theSmallPrint() {
        assertEquals("Signed in as alice", LockCopy.account("alice"))
        assertEquals("Use encryption phrase", LockCopy.USE_PHRASE)
        assertEquals("Keys never leave this device", LockCopy.TRUST)
        assertEquals("No screen lock yet.", LockCopy.NO_SCREEN_LOCK_YET)
        assertEquals("This tablet keeps keys in software, not secure hardware.", LockCopy.softwareKeystoreNotice("tablet"))
    }

    @Test
    fun phasesKnowWhetherTheyAreVerifiedOrReleased() {
        assertFalse(UnlockPhase.Idle.isVerified)
        assertFalse(UnlockPhase.Checking.isVerified)
        assertTrue(UnlockPhase.Verified.isVerified)
        assertFalse(UnlockPhase.Verified.isReleased)
        assertTrue(UnlockPhase.Releasing.isVerified && UnlockPhase.Releasing.isReleased)
        assertTrue(UnlockPhase.Revealing.isVerified && UnlockPhase.Revealing.isReleased)
    }

    @Test
    fun theHeroScalesBetweenSixtyAndOneHundredPercent() {
        // `heroScale(forHeight:)` (`:85-87`): min(1, max(0.6, (h − 426) / 300)).
        assertEquals(1f, LockHeroMath.heroScale(900f), 0f)
        assertEquals(1f, LockHeroMath.heroScale(726f), 0f)
        assertEquals(0.74f, LockHeroMath.heroScale(648f), 1e-5f)
        assertEquals(0.6f, LockHeroMath.heroScale(606f), 1e-5f)
        assertEquals(0.6f, LockHeroMath.heroScale(300f), 0f)
    }

    @Test
    fun theMarkBadgeAndDiscFollowThePhase() {
        // `markScale`, `markOffset`, `badgeScale` (`:225-245`) and the disc (`:186`).
        assertEquals(listOf(1f, 1f, 1.04f, 1.16f, 0.5f), UnlockPhase.entries.map(LockHeroMath::markScale))
        assertEquals(listOf(0f, 0f, 0f, -24f, -150f), UnlockPhase.entries.map(LockHeroMath::markOffsetY))
        assertEquals(listOf(1f, 1f, 1.04f, 1.25f, 1.25f), UnlockPhase.entries.map(LockHeroMath::discScale))
        assertEquals(1.12f, LockHeroMath.badgeScale(UnlockPhase.Checking, 1.12f), 0f)
        assertEquals(1.1f, LockHeroMath.badgeScale(UnlockPhase.Verified, 1.12f), 0f)
        assertEquals(1.1f, LockHeroMath.badgeScale(UnlockPhase.Revealing, 1f), 0f)
        assertEquals(300L, LockHeroMath.VERIFIED_HOLD_MS)
        assertEquals(340L, LockHeroMath.RELEASE_HOLD_MS)
        assertEquals(900, LockHeroMath.BREATH_MS)
    }
}
