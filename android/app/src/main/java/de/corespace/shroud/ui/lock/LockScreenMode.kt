package de.corespace.shroud.ui.lock

import de.corespace.shroud.core.devices.DeviceNoun
import de.corespace.shroud.core.keys.BiometricLabel
import de.corespace.shroud.core.keys.VaultState
import kotlin.math.max
import kotlin.math.min

/**
 * What the lock screen offers (settings-lock §11.2). iOS has two of these — "Chats are locked" with
 * or without biometry, and "Set a device passcode" (`LockScreenView.swift:319-371`); Android adds
 * [PhraseNeeded] for a vault the system invalidated (crypto §10.3: a new fingerprint, a removed
 * biometric or a changed screen lock kills the auth-bound wrap key).
 */
enum class LockScreenMode {
    /** Screen lock + an enrolled strong (class 3) biometric + a usable vault: design `yGDcx`. */
    Biometric,

    /** Screen lock, but no strong biometric (none, or weak face unlock only): "Unlock with screen lock". */
    ScreenLockOnly,

    /** No PIN, pattern or password: the vault cannot exist, so nothing unlocks (design `o5GgZ`, `LockScreenView.swift:556`). */
    NoScreenLock,

    /** The identity is here but its vault is gone or invalidated: the phrase sets it up again (design `LRnnR`). */
    PhraseNeeded,
    ;

    companion object {
        /**
         * The mode from what the phone reports (settings-lock §11.2): no screen lock wins
         * (`canUseDeviceAuth`, `LockScreenView.swift:75, 349`); a vault that is invalidated or missing
         * while the identity is here needs the phrase; otherwise the strong biometric decides
         * (`hasBiometry`, `:354-371`). Without the identity (wiped mid-session) the normal mode
         * stays: its button runs the unlock, which reconciles the orphaned session
         * (`LockScreenView.swift:487-492`).
         */
        fun of(isDeviceSecure: Boolean, strongBiometric: Boolean, vaultState: VaultState, hasIdentity: Boolean): LockScreenMode = when {
            !isDeviceSecure || vaultState == VaultState.NoScreenLock -> NoScreenLock
            hasIdentity && (vaultState == VaultState.KeyInvalidated || vaultState == VaultState.NotFound) -> PhraseNeeded
            strongBiometric -> Biometric
            else -> ScreenLockOnly
        }
    }
}

/**
 * One reading of the phone's security for the lock screen (settings-lock §11.1): taken off the main
 * thread (Keystore, disk) when the screen appears, on every resume (back from system Settings,
 * `LockScreenView.swift:131-133`) and after a failed unlock (`:505-506`).
 *
 * @property softwareKeystore the vault's wrap key sits in a software-only Keystore (P3c decided:
 *   allowed, with a one-line notice — [LockCopy.softwareKeystoreNotice]).
 */
data class LockProbe(
    val isDeviceSecure: Boolean,
    val strongBiometric: Boolean,
    val biometricLabel: BiometricLabel,
    val vaultState: VaultState,
    val hasIdentity: Boolean,
    val softwareKeystore: Boolean = false,
) {
    val mode: LockScreenMode get() = LockScreenMode.of(isDeviceSecure, strongBiometric, vaultState, hasIdentity)
}

/**
 * The lock screen's words (settings-lock §11.2-11.3; copy marked [A] there is the Android wording
 * of the design's *Platform Notes*; P14; S6, S10, S14). Pure, so the copy is tested on the JVM.
 * [noun] is [DeviceNoun.current]: "phone", or "tablet" from 600 dp.
 */
object LockCopy {
    /** `LockScreenView.swift:319`; [A] "screen lock" for "device passcode"; Android-only "Phrase needed" (`LRnnR`). */
    fun title(mode: LockScreenMode): String = when (mode) {
        LockScreenMode.NoScreenLock -> "Set a screen lock to use Shroud"
        LockScreenMode.PhraseNeeded -> "Phrase needed"
        else -> "Chats are locked"
    }

    /** `LockScreenView.swift:325-329` with "this phone" ([A]); the design's `LRnnR` body for [LockScreenMode.PhraseNeeded]. */
    fun body(mode: LockScreenMode, noun: String = DeviceNoun.PHONE): String = when (mode) {
        LockScreenMode.NoScreenLock ->
            "Shroud keeps your chats sealed behind this $noun's screen lock. Add one in Settings, then come back."
        LockScreenMode.PhraseNeeded ->
            "Your fingerprints or screen lock changed, so this $noun can’t unlock your chats on its own. Enter your 12-word phrase once to set it up again."
        else -> "Your messages stay encrypted on this $noun until you unlock them."
    }

    /**
     * The primary button at rest: "Unlock with \(biometryName)" (`:356`) by what is enrolled (S6),
     * "Unlock with screen lock" for iOS "Unlock with passcode" (`:366`), "Check again" (`:350`), and
     * *Phrase needed*'s "Enter encryption phrase" (`LRnnR`).
     */
    fun primaryIdle(mode: LockScreenMode, label: BiometricLabel): String = when (mode) {
        LockScreenMode.Biometric -> "Unlock with ${label.word}"
        LockScreenMode.ScreenLockOnly -> "Unlock with screen lock"
        LockScreenMode.NoScreenLock -> "Check again"
        LockScreenMode.PhraseNeeded -> "Enter encryption phrase"
    }

    /** `primaryTitle(for:)` (`LockScreenView.swift:414-420`): "Checking…" under the prompt, "Unlocked" from verified on. */
    fun primary(mode: LockScreenMode, label: BiometricLabel, phase: UnlockPhase): String = when (phase) {
        UnlockPhase.Idle -> primaryIdle(mode, label)
        UnlockPhase.Checking -> CHECKING
        UnlockPhase.Verified, UnlockPhase.Releasing, UnlockPhase.Revealing -> "Unlocked"
    }

    /** The secondary button of [LockScreenMode.Biometric] (`:464`; [A] "Use screen lock"), "Checking…" while its prompt is up. */
    fun secondary(checkingThisMethod: Boolean): String = if (checkingThisMethod) CHECKING else "Use screen lock"

    /**
     * The words before "Use encryption phrase" (`:379`): iOS "Lost access to \(Face ID)?" becomes
     * "<Label> not working?" (design `yGDcx`, S6), "Prefer another way?" without a biometric; no row
     * at all without a screen lock or on *Phrase needed* (design `o5GgZ`, `LRnnR`).
     */
    fun fallbackLead(mode: LockScreenMode, label: BiometricLabel): String? = when (mode) {
        LockScreenMode.Biometric -> when (label) {
            BiometricLabel.Fingerprint -> "Fingerprint not working?"
            BiometricLabel.Face -> "Face unlock not working?"
            BiometricLabel.Generic -> "Biometrics not working?"
        }
        LockScreenMode.ScreenLockOnly -> "Prefer another way?"
        LockScreenMode.NoScreenLock, LockScreenMode.PhraseNeeded -> null
    }

    /** The account chip's TalkBack label (`:315`). */
    fun account(username: String): String = "Signed in as $username"

    /**
     * The software-only Keystore notice (P3c decided "allow + a one-line notice"; crypto D3). No
     * copy exists in the design or the specs: this wording waits for the owner's and W3-DESIGN's
     * confirmation (reported with the package).
     */
    fun softwareKeystoreNotice(noun: String = DeviceNoun.PHONE): String = "This $noun keeps keys in software, not secure hardware."

    const val CHECKING = "Checking…"
    const val USE_PHRASE = "Use encryption phrase"

    /** S14: the design's "device" (iOS "this \(deviceName)", `:400`). */
    const val TRUST = "Keys never leave this device"

    /** "Check again" still without a screen lock ([A] for iOS "No device passcode yet.", `:565`). */
    const val NO_SCREEN_LOCK_YET = "No screen lock yet."
}

/**
 * Where the unlock choreography is (`LockScreenView.Phase`, `:24-34`): a tap starts [Checking] (the
 * system prompt is up), the vault opening plays [Verified] → [Releasing] → [Revealing]; the reveal
 * itself is the shell's cross-fade into Chats.
 */
enum class UnlockPhase {
    Idle,

    /** The system prompt is up: the badge breathes, the button says "Checking…". */
    Checking,

    /** The vault opened: the badge springs open, the chips decrypt, the button turns green. */
    Verified,

    /** The rings ripple out, the mark lifts, everything else settles away. */
    Releasing,

    /** The main shell fades in underneath; the mark shrinks up and dissolves. */
    Revealing,
    ;

    /** `verified` (`:165`). */
    val isVerified: Boolean get() = this == Verified || this == Releasing || this == Revealing

    /** `released` (`:166`). */
    val isReleased: Boolean get() = this == Releasing || this == Revealing
}

/**
 * The hero's numbers (`LockScreenView.swift:81-87, 225-245`) and the choreography's holds
 * (`:51-52`), pure so they are tested.
 */
object LockHeroMath {
    /** Everything but the hero: nav row 50 + hero top 4 + copy ≈ 150 + actions ≈ 222 (`:79-81`). */
    const val NON_HERO_HEIGHT_DP = 426f

    /** The hero stage is 300 × 300 before scaling. */
    const val STAGE_DP = 300f

    /** Verified → Release → Reveal, from the storyboard's time chips (`:50-52`). */
    const val VERIFIED_HOLD_MS = 300L
    const val RELEASE_HOLD_MS = 340L

    /** The badge's breath while the prompt is up: 0.9 s each way (`:121-126`). */
    const val BREATH_MS = 900

    /** 1 on tall phones, down to 0.6 on short ones, so the gear and the footer stay on screen (`:85-87`). */
    fun heroScale(availableHeightDp: Float): Float = min(1f, max(0.6f, (availableHeightDp - NON_HERO_HEIGHT_DP) / STAGE_DP))

    /** `markScale` (`:225-232`). */
    fun markScale(phase: UnlockPhase): Float = when (phase) {
        UnlockPhase.Idle, UnlockPhase.Checking -> 1f
        UnlockPhase.Verified -> 1.04f
        UnlockPhase.Releasing -> 1.16f
        UnlockPhase.Revealing -> 0.5f
    }

    /** `markOffset` in dp (`:234-240`). */
    fun markOffsetY(phase: UnlockPhase): Float = when (phase) {
        UnlockPhase.Idle, UnlockPhase.Checking, UnlockPhase.Verified -> 0f
        UnlockPhase.Releasing -> -24f
        UnlockPhase.Revealing -> -150f
    }

    /** `badgeScale` (`:242-245`): 1.1 once verified, else the breath. */
    fun badgeScale(phase: UnlockPhase, breath: Float): Float = if (phase.isVerified) 1.1f else breath

    /** The inner disc (`:186`): 1.25 released, 1.04 verified. */
    fun discScale(phase: UnlockPhase): Float = when {
        phase.isReleased -> 1.25f
        phase.isVerified -> 1.04f
        else -> 1f
    }
}
