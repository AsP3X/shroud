package de.corespace.shroud.core.push

import de.corespace.shroud.core.push.unifiedpush.UnifiedPushProtocol
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** AND_3.1.0 names, pinned so a connector-era extra cannot slip back in. */
class UnifiedPushProtocolTest {
    @Test
    fun actionsAndExtrasMatchTheSpec() {
        assertEquals("AND_3.1.0", UnifiedPushProtocol.SPEC)
        assertEquals("org.unifiedpush.android.distributor.REGISTER", UnifiedPushProtocol.ACTION_REGISTER)
        assertEquals("org.unifiedpush.android.distributor.UNREGISTER", UnifiedPushProtocol.ACTION_UNREGISTER)
        assertEquals("org.unifiedpush.android.distributor.MESSAGE_ACK", UnifiedPushProtocol.ACTION_MESSAGE_ACK)
        assertEquals("org.unifiedpush.android.connector.NEW_ENDPOINT", UnifiedPushProtocol.ACTION_NEW_ENDPOINT)
        assertEquals("org.unifiedpush.android.connector.REGISTRATION_FAILED", UnifiedPushProtocol.ACTION_REGISTRATION_FAILED)
        assertEquals("org.unifiedpush.android.connector.UNREGISTERED", UnifiedPushProtocol.ACTION_UNREGISTERED)
        assertEquals("org.unifiedpush.android.connector.MESSAGE", UnifiedPushProtocol.ACTION_MESSAGE)
        assertEquals("org.unifiedpush.android.connector.TEMP_UNAVAILABLE", UnifiedPushProtocol.ACTION_TEMP_UNAVAILABLE)
        assertEquals("org.unifiedpush.android.connector.RAISE_TO_FOREGROUND", UnifiedPushProtocol.ACTION_RAISE_TO_FOREGROUND)
        assertEquals("token", UnifiedPushProtocol.EXTRA_TOKEN)
        assertEquals("endpoint", UnifiedPushProtocol.EXTRA_ENDPOINT)
        assertEquals("bytesMessage", UnifiedPushProtocol.EXTRA_BYTES_MESSAGE)
        assertEquals("id", UnifiedPushProtocol.EXTRA_ID)
        assertEquals("vapid", UnifiedPushProtocol.EXTRA_VAPID)
        assertEquals("message", UnifiedPushProtocol.EXTRA_MESSAGE)
        assertEquals("pi", UnifiedPushProtocol.EXTRA_PI)
        assertEquals("reason", UnifiedPushProtocol.EXTRA_REASON)
        assertEquals("useDistributor", UnifiedPushProtocol.EXTRA_USE_DISTRIBUTOR)
        assertEquals(setOf("token", "vapid", "message", "pi"), UnifiedPushProtocol.registerExtraNames(33))
        assertEquals(setOf("token", "vapid", "message"), UnifiedPushProtocol.registerExtraNames(34))
        assertFalse(UnifiedPushProtocol.registerExtraNames(34).contains("application"))
        assertFalse(UnifiedPushProtocol.registerExtraNames(33).contains("features"))
        assertEquals("INTERNAL_ERROR", UnifiedPushProtocol.REASON_INTERNAL_ERROR)
        assertEquals("NETWORK", UnifiedPushProtocol.REASON_NETWORK)
        assertEquals("ACTION_REQUIRED", UnifiedPushProtocol.REASON_ACTION_REQUIRED)
        assertEquals("VAPID_REQUIRED", UnifiedPushProtocol.REASON_VAPID_REQUIRED)
    }

    @Test
    fun aForeignTokenIsDropped() {
        assertTrue(UnifiedPushProtocol.tokenMatches("stored-token", "stored-token"))
        assertFalse(UnifiedPushProtocol.tokenMatches("stored-token", "other-token"))
        assertFalse(UnifiedPushProtocol.tokenMatches("stored-token", null))
        assertFalse(UnifiedPushProtocol.tokenMatches(null, "stored-token"))
        assertFalse(UnifiedPushProtocol.tokenMatches("", "stored-token"))
        assertFalse(UnifiedPushProtocol.tokenMatches("stored-token", ""))
    }

    @Test
    fun vapidExtraIs87UrlCharacters() {
        val key = "BCVxsr7N_eNgVRqvHtD0zTZsEc6-VV-JvLexhqUzORcxaOzi6-AYWXvTBHm4bjyPjs7Vd8pZGH6SRpkNtoIAiw4"
        assertEquals(87, key.length)
        assertEquals(key, UnifiedPushProtocol.vapidExtra("$key="))
        assertNull(UnifiedPushProtocol.vapidExtra("short"))
        assertNull(UnifiedPushProtocol.vapidExtra("$key+"))
    }
}
