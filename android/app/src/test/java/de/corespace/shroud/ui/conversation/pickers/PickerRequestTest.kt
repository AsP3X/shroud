package de.corespace.shroud.ui.conversation.pickers

import androidx.activity.result.contract.ActivityResultContracts
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What the system photo picker is opened with (iOS `.photosPicker`, `ConversationView.swift:405-417,
 * 667-689`; conversation-compose-media §8.1, §8.4).
 */
class PickerRequestTest {
    @Test
    fun `Photos in the attach sheet picks up to ten photos and videos (CV 410-414)`() {
        val request = PickerRequest.newPick()
        assertEquals(PickerRequest(10, PickerFilter.ImageAndVideo), request)
        assertFalse(request.isSingle)
    }

    @Test
    fun `Add offers the room left in the send, at least one (CV 410-412, 670-673)`() {
        assertEquals(7, PickerRequest.append(staged = 3, videoComposeOpen = false, photoComposeOpen = true).maxItems)
        val last = PickerRequest.append(staged = 9, videoComposeOpen = false, photoComposeOpen = true)
        assertEquals(1, last.maxItems)
        // PickMultipleVisualMedia refuses fewer than two: one item goes through PickVisualMedia.
        assertTrue(last.isSingle)
        assertEquals(1, PickerRequest.append(staged = 12, videoComposeOpen = false, photoComposeOpen = true).maxItems)
    }

    @Test
    fun `Add only offers the open screen's kind, video first (CV 675-682)`() {
        assertEquals(PickerFilter.VideoOnly, PickerRequest.append(2, videoComposeOpen = true, photoComposeOpen = false).filter)
        assertEquals(PickerFilter.ImageOnly, PickerRequest.append(2, videoComposeOpen = false, photoComposeOpen = true).filter)
        assertEquals(PickerFilter.VideoOnly, PickerRequest.append(2, videoComposeOpen = true, photoComposeOpen = true).filter)
        assertEquals(PickerFilter.ImageAndVideo, PickerRequest.append(0, videoComposeOpen = false, photoComposeOpen = false).filter)
    }

    @Test
    fun `filters map to the AndroidX picker media types`() {
        assertEquals(ActivityResultContracts.PickVisualMedia.ImageAndVideo, PickerRequest.newPick().visualMediaRequest().mediaType)
        assertEquals(ActivityResultContracts.PickVisualMedia.ImageOnly, PickerRequest(3, PickerFilter.ImageOnly).visualMediaRequest().mediaType)
        assertEquals(ActivityResultContracts.PickVisualMedia.VideoOnly, PickerRequest(3, PickerFilter.VideoOnly).visualMediaRequest().mediaType)
    }
}
