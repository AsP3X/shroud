package de.corespace.shroud.ui.conversation.bubble

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Bubble decoding: small pictures at full size, full photos sampled down to what the bubble draws. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class BubbleImagesTest {
    @Test
    fun aThumbnailDecodesAtItsOwnSize() {
        val image = BubbleImages.decodeSmall(BubbleRenderFixtures.landscape(40, 30))
        assertNotNull(image)
        assertEquals(40, image!!.width)
    }

    @Test
    fun aFullPhotoIsSampledToTheBubble() = runBlocking {
        val image = BubbleImages.decodeSampled(BubbleRenderFixtures.landscape(1200, 900), maxEdgePx = 500)
        assertNotNull(image)
        assertEquals(600, image!!.width)
    }

    @Test
    fun theSampleKeepsTheEdgeAtOrAboveTheTarget() {
        assertEquals(1, BubbleImages.sampleSize(500, 600))
        assertEquals(2, BubbleImages.sampleSize(1200, 500))
        assertEquals(4, BubbleImages.sampleSize(4000, 900))
        assertEquals(1, BubbleImages.sampleSize(4000, 0))
    }
}
