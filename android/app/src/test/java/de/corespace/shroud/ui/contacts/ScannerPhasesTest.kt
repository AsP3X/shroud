package de.corespace.shroud.ui.contacts

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * What the scanner shows (iOS `QRCodeScannerView.swift:41-65, 169-200`; contacts §5.5 [AND],
 * designs w6957 and oGuCt): the system dialog over the Add Contact sheet before the scanner opens,
 * the designed denied screen for first and permanent denials alike, the camera starting after
 * access was granted in Settings, and the no-camera hint.
 */
class ScannerPhasesTest {
    @Test
    fun scanAsksOverTheSheetOnlyWhenThereIsACameraAndNoAccessYet() {
        assertEquals(ScannerPhases.Start.Open(ScannerPhase.Live), ScannerPhases.onScanTapped(hasCamera = true, granted = true))
        // w6957: the dialog sits over the sheet, the scanner is not up yet.
        assertEquals(ScannerPhases.Start.AskFirst, ScannerPhases.onScanTapped(hasCamera = true, granted = false))
        // iOS `configureSession()` false without a device: the hint says so, nothing is asked.
        assertEquals(ScannerPhases.Start.Open(ScannerPhase.NoCamera), ScannerPhases.onScanTapped(hasCamera = false, granted = false))
        assertEquals(ScannerPhases.Start.Open(ScannerPhase.NoCamera), ScannerPhases.onScanTapped(hasCamera = false, granted = true))
    }

    @Test
    fun theAnswerOpensTheLiveScannerOrTheDeniedScreen() {
        assertEquals(ScannerPhase.Live, ScannerPhases.afterRequest(granted = true))
        // "Don't allow" and "don't ask again" (answered without a dialog) look the same (oGuCt).
        assertEquals(ScannerPhase.Denied, ScannerPhases.afterRequest(granted = false))
    }

    @Test
    fun accessGrantedInSettingsStartsTheCameraOnReturn() {
        assertEquals(ScannerPhase.Live, ScannerPhases.onResume(ScannerPhase.Denied, granted = true))
        assertEquals(ScannerPhase.Denied, ScannerPhases.onResume(ScannerPhase.Denied, granted = false))
        assertEquals(ScannerPhase.NoCamera, ScannerPhases.onResume(ScannerPhase.NoCamera, granted = true))
        assertEquals(ScannerPhase.Live, ScannerPhases.onResume(ScannerPhase.Live, granted = true))
    }

    @Test
    fun aCameraThatCannotBeOpenedShowsTheNoCameraHint() {
        assertEquals(ScannerPhase.NoCamera, ScannerPhases.onBindFailure(ScannerPhase.Live))
        assertEquals(ScannerPhase.Denied, ScannerPhases.onBindFailure(ScannerPhase.Denied))
    }
}
