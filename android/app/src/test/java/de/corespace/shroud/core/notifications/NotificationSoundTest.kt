package de.corespace.shroud.core.notifications

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * The sounds (iOS `NotificationSound`, `NotificationSound.swift:9-41`; notifications-push §5.5) and
 * their bundled files (`NotificationSettingsTests.testBundledSoundsExist`,
 * `NotificationPayloadTests.swift:197-202`). Robolectric to open the raw resources.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class NotificationSoundTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun pickerOrderRawValuesAndTitles() {
        assertEquals(listOf("default", "note", "chime", "glass", "pop", "pulse", "none"), NotificationSound.entries.map { it.raw })
        assertEquals(listOf("Default", "Note", "Chime", "Glass", "Pop", "Pulse", "None"), NotificationSound.entries.map { it.title })
        for (sound in NotificationSound.entries) assertEquals(sound.raw, sound.serverName)
    }

    @Test
    fun unknownOrAbsentRawReadsAsDefault() {
        assertEquals(NotificationSound.Standard, NotificationSound.fromRaw(null))
        assertEquals(NotificationSound.Standard, NotificationSound.fromRaw("trumpet"))
        assertEquals(NotificationSound.Chime, NotificationSound.fromRaw("chime"))
        assertEquals(NotificationSound.None, NotificationSound.fromRaw("none"))
    }

    /** `testBundledSoundsExist`: every Shroud tone has a file; the phone's tone and None have none. */
    @Test
    fun bundledSoundsExistAndAreWaves() {
        assertNull(NotificationSound.Standard.resId)
        assertNull(NotificationSound.None.resId)
        for (sound in NotificationSound.entries - NotificationSound.Standard - NotificationSound.None) {
            val resId = checkNotNull(sound.resId)
            val bytes = context.resources.openRawResource(resId).use { it.readBytes() }
            assertEquals("RIFF", String(bytes, 0, 4, Charsets.US_ASCII))
            assertEquals("WAVE", String(bytes, 8, 4, Charsets.US_ASCII))
        }
    }

    /** The same bytes as the iOS app and the web play (`scripts/gen_notification_sounds.py`). */
    @Test
    fun sameBytesAsTheOtherClients() {
        val web = File("../../web/public/sounds")
        assumeTrue("the repository's web client is next to android/", web.isDirectory)
        for (sound in NotificationSound.entries.filter { it.resId != null }) {
            val ours = context.resources.openRawResource(sound.resId!!).use { it.readBytes() }
            assertArrayEquals(sound.raw, File(web, "${sound.raw}.wav").readBytes(), ours)
            assertArrayEquals(sound.raw, File("../../ios/shroud/Resources/Sounds/${sound.raw}.wav").readBytes(), ours)
        }
    }
}
