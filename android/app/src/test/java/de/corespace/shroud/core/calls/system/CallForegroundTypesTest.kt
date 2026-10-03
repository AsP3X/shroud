package de.corespace.shroud.core.calls.system

import android.content.pm.ServiceInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CallForegroundTypesTest {
    @Test
    fun sharingTriesTheProjectionTypeBeforeACallOnlyFallback() {
        val phone = ServiceInfo.FOREGROUND_SERVICE_TYPE_PHONE_CALL
        val mic = ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        val projection = ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
        assertNotEquals(0, projection)
        val attempts = callForegroundTypeAttempts(microphone = true, sharing = true)
        assertEquals(phone or mic or projection, attempts[0])
        assertEquals(phone or projection, attempts[1])
        assertTrue(attempts.take(2).all { (it and projection) != 0 })
        assertTrue(attempts.drop(2).all { (it and projection) == 0 })
    }

    @Test
    fun aCallWithoutSharingDoesNotAskForMediaProjection() {
        val phone = ServiceInfo.FOREGROUND_SERVICE_TYPE_PHONE_CALL
        val mic = ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        val projection = ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
        val attempts = callForegroundTypeAttempts(microphone = true, sharing = false)
        assertEquals(listOf(phone or mic, phone), attempts)
        assertTrue(attempts.none { (it and projection) != 0 })
    }
}
