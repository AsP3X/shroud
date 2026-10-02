package de.corespace.shroud.core.media.video

import android.media.MediaDataSource
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.UUID

/**
 * [VideoPipeline.durationMs][de.corespace.shroud.core.media.VideoPipeline.durationMs] as
 * [VideoMedia] runs it ([sealedClipDuration]). The platform retriever needs a device, so [read]
 * stands in for it. This test does not claim a device run.
 */
class VideoMediaDurationTest {
    private val messageId = UUID.fromString("0f8fad5b-d9cb-469f-a165-70867728950e")

    @Test
    fun noSourceIsNullAndNothingIsRead() = runTest {
        var reads = 0
        val duration = sealedClipDuration(SealedVideoSources.Unavailable, messageId) {
            reads++
            1_500
        }
        assertNull(duration)
        assertEquals(0, reads)
    }

    @Test
    fun aPositiveDurationIsReturnedAndTheSourceIsClosed() = runTest {
        val source = FakeSource()
        val duration = sealedClipDuration(sources(source), messageId) { 3_400 }
        assertEquals(3_400, duration)
        assertEquals(1, source.closed)
    }

    @Test
    fun aMissingOrNonPositiveDurationIsNullAndTheSourceIsClosed() = runTest {
        for (reported in listOf(null, 0, -1)) {
            val source = FakeSource()
            val duration = sealedClipDuration(sources(source), messageId) { reported }
            assertNull(duration)
            assertEquals(1, source.closed)
        }
        val broken = FakeSource()
        val duration = sealedClipDuration(sources(broken), messageId) { throw IllegalStateException("unreadable") }
        assertNull(duration)
        assertEquals(1, broken.closed)
    }

    private fun sources(source: MediaDataSource) = object : SealedVideoSources by SealedVideoSources.Unavailable {
        override fun retrieverDataSource(messageId: UUID): MediaDataSource = source
    }

    private class FakeSource : MediaDataSource() {
        var closed = 0
        override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int = 0
        override fun getSize(): Long = 0
        override fun close() {
            closed++
        }
    }
}
