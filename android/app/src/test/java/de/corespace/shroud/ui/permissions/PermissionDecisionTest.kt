package de.corespace.shroud.ui.permissions

import android.Manifest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * First denial vs permanent denial (00-plan W1-UI-OVERLAYS tests; conversation-compose-media §4.7,
 * §7.4; contacts §8: "the denied screen must handle both first denial and don't ask again").
 */
class PermissionDecisionTest {
    @Test
    fun grantedIsGrantedWhateverElseHappened() {
        for (dialog in listOf(true, false)) {
            for (before in listOf(true, false)) {
                for (after in listOf(true, false)) {
                    assertEquals(PermissionOutcome.Granted, PermissionDecision.outcome(true, dialog, before, after))
                }
            }
        }
    }

    @Test
    fun firstDontAllowIsADenialTheSystemWillAskAgain() {
        // Never asked: no rationale before; "Don't allow" makes Android ask for one.
        assertEquals(
            PermissionOutcome.Denied,
            PermissionDecision.outcome(granted = false, dialogShown = true, rationaleBefore = false, rationaleAfter = true),
        )
    }

    @Test
    fun secondDontAllowIsPermanent() {
        // The rationale was due before the dialog and is gone after it: Android stops asking.
        assertEquals(
            PermissionOutcome.PermanentlyDenied,
            PermissionDecision.outcome(granted = false, dialogShown = true, rationaleBefore = true, rationaleAfter = false),
        )
    }

    @Test
    fun anAnswerWithoutTheDialogIsPermanent() {
        // Already permanently denied: the launcher answers at once, the activity never pauses.
        assertEquals(
            PermissionOutcome.PermanentlyDenied,
            PermissionDecision.outcome(granted = false, dialogShown = false, rationaleBefore = false, rationaleAfter = false),
        )
    }

    @Test
    fun aDialogClosedWithoutAnAnswerAsksAgainNextTime() {
        // Back or a tap outside on the first request: no rationale before or after.
        assertEquals(
            PermissionOutcome.Denied,
            PermissionDecision.outcome(granted = false, dialogShown = true, rationaleBefore = false, rationaleAfter = false),
        )
        // Dismissed after one earlier denial: the rationale stays due.
        assertEquals(
            PermissionOutcome.Denied,
            PermissionDecision.outcome(granted = false, dialogShown = true, rationaleBefore = true, rationaleAfter = true),
        )
    }

    @Test
    fun notificationsAreARuntimePermissionFromAndroid13Only() {
        assertFalse(PermissionDecision.isRuntimePermission(Manifest.permission.POST_NOTIFICATIONS, 30))
        assertFalse(PermissionDecision.isRuntimePermission(Manifest.permission.POST_NOTIFICATIONS, 32))
        assertTrue(PermissionDecision.isRuntimePermission(Manifest.permission.POST_NOTIFICATIONS, 33))
        assertTrue(PermissionDecision.isRuntimePermission(Manifest.permission.RECORD_AUDIO, 30))
        assertTrue(PermissionDecision.isRuntimePermission(Manifest.permission.CAMERA, 37))
    }
}
