package de.corespace.shroud.core.push.unifiedpush

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** Google Play registration names our receiver. Another distributor is left to its own package. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class UnifiedPushBroadcasterTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test
    fun embeddedRegistrationDoesNotBroadcastToItself() {
        AndroidUnifiedPushBroadcaster(context).register(context.packageName, "token", "vapid-key")
        val registers = shadowOf(context as Application).broadcastIntents
            .filter { it.action == UnifiedPushProtocol.ACTION_REGISTER }
        assertEquals(emptyList<Any>(), registers)
    }

    @Test
    fun anotherDistributorIsNotSentToOurReceiver() {
        AndroidUnifiedPushBroadcaster(context).register("io.heckel.ntfy", "token", "vapid-key")
        val intent = shadowOf(context as Application).broadcastIntents.single()
        assertEquals("io.heckel.ntfy", intent.`package`)
        assertNull(intent.component)
    }
}
