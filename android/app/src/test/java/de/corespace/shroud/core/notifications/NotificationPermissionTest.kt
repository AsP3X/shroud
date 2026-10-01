package de.corespace.shroud.core.notifications

import de.corespace.shroud.core.notifications.NotificationAuthorization.Authorized
import de.corespace.shroud.core.notifications.NotificationAuthorization.Denied
import de.corespace.shroud.core.notifications.NotificationAuthorization.NotDetermined
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The permission rule of notifications-push §5.11 (iOS `UNAuthorizationStatus`,
 * `NotificationsController.swift:65-67`): Android 13+ asks for `POST_NOTIFICATIONS` at most twice;
 * before 13 notifications are on unless turned off in Settings.
 */
class NotificationPermissionTest {
    private fun rule(sdk: Int, granted: Boolean = false, enabled: Boolean = true, asked: Boolean = false, rationale: Boolean = false) =
        NotificationPermission.authorization(sdk, granted, enabled, asked, rationale)

    @Test
    fun beforeAndroid13ThereIsNothingToAsk() {
        assertEquals(Authorized, rule(sdk = 30))
        assertEquals(Authorized, rule(sdk = 32, asked = true))
        assertEquals(Denied, rule(sdk = 31, enabled = false))
    }

    @Test
    fun grantedFollowsTheSystemSwitch() {
        assertEquals(Authorized, rule(sdk = 33, granted = true))
        assertEquals("blocked in Settings after granting", Denied, rule(sdk = 37, granted = true, enabled = false))
    }

    @Test
    fun theDialogShowsTwiceAtMost() {
        assertEquals("never asked", NotDetermined, rule(sdk = 33))
        assertEquals("one \"Don't allow\": the system asks again", NotDetermined, rule(sdk = 34, asked = true, rationale = true))
        assertEquals("twice: only Settings can change it", Denied, rule(sdk = 36, asked = true, rationale = false))
    }
}
