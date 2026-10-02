package de.corespace.shroud.ui.contacts

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * What the scanner shows (iOS `QRCodeScannerView.swift:41-65, 169-200`; contacts §5.5 [AND],
 * design oGuCt): the permission flow, the designed denied screen for first and permanent denials
 * alike, the camera starting after access was granted in Settings, and the no-camera hint.
 */
class ScannerPhasesTest {
    @Test
    fun openingAsksOnlyWhenThereIsACameraAndNoAccessYet() {
        assertEquals(ScannerPhase.Live, ScannerPhases.initial(hasCamera = true, granted = true))
        assertEquals(ScannerPhase.Asking, ScannerPhases.initial(hasCamera = true, granted = false))
        assertEquals(ScannerPhase.NoCamera, ScannerPhases.initial(hasCamera = false, granted = false))
        assertEquals(ScannerPhase.NoCamera, ScannerPhases.initial(hasCamera = false, granted = true))
    }

    @Test
    fun theAnswerStartsTheCameraOrShowsTheDeniedScreen() {
        assertEquals(ScannerPhase.Live, ScannerPhases.afterRequest(granted = true))
        // "Don't allow" and "don't ask again" (answered without a dialog) look the same.
        assertEquals(ScannerPhase.Denied, ScannerPhases.afterRequest(granted = false))
    }

    @Test
    fun accessGrantedInSettingsStartsTheCameraOnReturn() {
        assertEquals(ScannerPhase.Live, ScannerPhases.onResume(ScannerPhase.Denied, granted = true))
        assertEquals(ScannerPhase.Denied, ScannerPhases.onResume(ScannerPhase.Denied, granted = false))
        // While the dialog is up, its own answer decides, not the resume.
        assertEquals(ScannerPhase.Asking, ScannerPhases.onResume(ScannerPhase.Asking, granted = false))
        assertEquals(ScannerPhase.NoCamera, ScannerPhases.onResume(ScannerPhase.NoCamera, granted = true))
        assertEquals(ScannerPhase.Live, ScannerPhases.onResume(ScannerPhase.Live, granted = true))
    }

    @Test
    fun aCameraThatCannotBeOpenedShowsTheNoCameraHint() {
        assertEquals(ScannerPhase.NoCamera, ScannerPhases.onBindFailure(ScannerPhase.Live))
        assertEquals(ScannerPhase.Denied, ScannerPhases.onBindFailure(ScannerPhase.Denied))
    }
}
