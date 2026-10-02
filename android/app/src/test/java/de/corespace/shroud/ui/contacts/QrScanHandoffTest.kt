package de.corespace.shroud.ui.contacts

import de.corespace.shroud.core.contacts.ContactInviteParser
import de.corespace.shroud.core.net.ServerConfiguration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The scanner emits once and unbinds (W3-CONTACTS-UI acceptance; iOS `QRCodeScannerView.swift:207-216`
 * `didEmit` + `stopSession()` before `onCode`): the first code stops the camera, then goes to the
 * sheet; later codes and codes after the scanner closed are dropped.
 */
class QrScanHandoffTest {
    private val official = ContactInviteParser.shareUrl("ABCD234567", ServerConfiguration.official)

    @Test
    fun theFirstCodeStopsTheCameraBeforeItIsHandedOn() {
        val log = mutableListOf<String>()
        val handoff = QrScanHandoff(unbind = { log += "unbind" }, onCode = { log += "code:$it" })

        assertTrue(handoff.deliver(official))
        assertEquals(listOf("unbind", "code:$official"), log)
        assertTrue(handoff.isClosed)
    }

    @Test
    fun laterCodesAreDropped() {
        val codes = mutableListOf<String>()
        var unbinds = 0
        val handoff = QrScanHandoff(unbind = { unbinds++ }, onCode = { codes += it })

        handoff.deliver(official)
        // Frames already queued for the main thread when the camera stopped.
        assertFalse(handoff.deliver(official))
        assertFalse(handoff.deliver("https://shroud.corespace.de/u/ZZZZ234567"))
        assertEquals(listOf(official), codes)
        assertEquals(1, unbinds)
    }

    @Test
    fun nothingIsHandedOnOnceTheScannerClosed() {
        val codes = mutableListOf<String>()
        var unbinds = 0
        val handoff = QrScanHandoff(unbind = { unbinds++ }, onCode = { codes += it })

        handoff.close()
        assertFalse(handoff.deliver(official))
        assertEquals(emptyList<String>(), codes)
        assertEquals("the closing scanner unbinds itself", 0, unbinds)
    }

    @Test
    fun anEmptyReadIsNotACode() {
        val codes = mutableListOf<String>()
        val handoff = QrScanHandoff(unbind = {}, onCode = { codes += it })

        assertFalse(handoff.deliver(""))
        assertFalse(handoff.isClosed)
        assertTrue(handoff.deliver(official))
        assertEquals(listOf(official), codes)
    }

    @Test
    fun theAnalyzerAndTheHandoffTogetherEmitOneCodePerScan() {
        // The analyzer thread reads two frames of the code before the main thread runs a hop.
        val queued = mutableListOf<String>()
        val analyzer = QrFrameAnalyzer { queued += it }
        val frame = QrFrameAnalyzerTest.Frame.of(official, scale = 5, rowPadding = 16)
        analyzer.process(frame.buffer(), frame.rowStride, 1, frame.width, frame.height)
        analyzer.process(frame.buffer(), frame.rowStride, 1, frame.width, frame.height)

        val delivered = mutableListOf<String>()
        var unbinds = 0
        val handoff = QrScanHandoff(unbind = { unbinds++ }, onCode = { delivered += it })
        queued.forEach { handoff.deliver(it) }

        assertEquals(listOf(official), delivered)
        assertEquals(1, unbinds)
    }
}
