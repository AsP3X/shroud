package de.corespace.shroud.core.contacts

import de.corespace.shroud.core.net.ContactRequestDto
import de.corespace.shroud.core.realtime.MessagingForeground
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

/**
 * The seams `MessagingController` (W2-MSG-CORE) implements fit its public API of plan §1.7.7:
 * this stand-in declares the members that API shares with [ContactsHooks] and
 * [MessagingForeground] with the API's own types, and must compile. A seam whose member clashes
 * with that API (as `isRealtimeConnected: Boolean` did against `StateFlow<Boolean>`) fails here, in
 * wave 1, instead of in W2-MSG-CORE.
 */
class ContactsSeamsTest {
    @Suppress("unused")
    private class MessagingControllerShape : MessagingForeground, ContactsHooks {
        // Plan §1.7.7 `MessagingController` members with names the seams use too.
        val isOffline: StateFlow<Boolean> = MutableStateFlow(false)
        override val isRealtimeConnected: StateFlow<Boolean> = MutableStateFlow(true)
        val lastError: StateFlow<String?> = MutableStateFlow(null)

        override fun handleAppBecameActive() = Unit
        override suspend fun leaveForeground(keepSocket: Boolean) = Unit
        override fun persistRoster() = Unit
        override fun onPeerOffline(userId: UUID) = Unit
        override suspend fun onBlocked(userId: UUID) = Unit
        override fun announceContactRequest(request: ContactRequestDto) = Unit
        override fun setLastError(message: String?) = Unit
        override fun setOffline(offline: Boolean) = Unit
    }

    @Test
    fun messagingControllerCanImplementBothSeams() {
        val hooks: ContactsHooks = MessagingControllerShape()
        assertTrue(hooks.isRealtimeConnected.value)
    }
}
