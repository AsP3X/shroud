package de.corespace.shroud.core.keys

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The lock screen's biometric noun (crypto spec §10.4; settings-lock §11.2 S6): API 31+ reads the
 * system's own button label for `BIOMETRIC_STRONG`, API 30 the fingerprint feature.
 */
class DeviceSecurityTest {
    @Test
    fun theSystemLabelWinsWhenItNamesOneKind() {
        assertEquals(BiometricLabel.Fingerprint, DeviceSecurity.labelFor("Use fingerprint", hasFingerprintFeature = false))
        assertEquals(BiometricLabel.Face, DeviceSecurity.labelFor("Use face unlock", hasFingerprintFeature = true))
        assertEquals(BiometricLabel.Generic, DeviceSecurity.labelFor("Use fingerprint or face", hasFingerprintFeature = true))
        assertEquals(BiometricLabel.Generic, DeviceSecurity.labelFor("Use biometrics", hasFingerprintFeature = false))
    }

    @Test
    fun withoutAUsableLabelTheSensorDecides() {
        assertEquals(BiometricLabel.Fingerprint, DeviceSecurity.labelFor(null, hasFingerprintFeature = true))
        assertEquals(BiometricLabel.Fingerprint, DeviceSecurity.labelFor("Fingerabdruck verwenden", hasFingerprintFeature = true))
        assertEquals(BiometricLabel.Generic, DeviceSecurity.labelFor(null, hasFingerprintFeature = false))
    }

    @Test
    fun theNounsAreTheCopyWords() {
        assertEquals(listOf("fingerprint", "face", "biometrics"), BiometricLabel.entries.map { it.word })
    }
}
