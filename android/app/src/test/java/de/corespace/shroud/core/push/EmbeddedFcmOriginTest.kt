package de.corespace.shroud.core.push

import de.corespace.shroud.core.push.unifiedpush.allowsEmbeddedSender
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The embedded receiver is not exported. A missing sender is still this app. */
class EmbeddedFcmOriginTest {
    @Test
    fun thisAppAndAMissingSenderAreAccepted() {
        assertTrue(allowsEmbeddedSender(null, OUR))
        assertTrue(allowsEmbeddedSender(OUR, OUR))
    }

    @Test
    fun anotherAppIsRejected() {
        assertFalse(allowsEmbeddedSender("io.heckel.ntfy", OUR))
    }

    private companion object {
        const val OUR = "de.corespace.shroud"
    }
}
